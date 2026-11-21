package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Entire production Engine; FakeHost records parameters, never qualifies a capsule. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnthropicReplayHistoryEngineTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun engine(host: AgentLoopHost) = AgentLoopEngine(host, ChatAgentTraceObserver(
        traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> }))
    private val tool = AgentToolDefinition("read_probe", "fixture", emptyMap())
    private fun host() = FakeAgentLoopHost(agentTools = listOf(tool))
    private fun replay(text: String = "before", id: String = "call-1", name: String = "read_probe", input: String = "{}") =
        AnthropicReplayCodec.complete(AnthropicReplay.Source.fromActualRequest(
            "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "fixture-instance")!!,
            Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"PRIVATE-SIGNATURE"},{"type":"text","text":"$text"},{"type":"redacted_thinking","data":"PRIVATE-CIPHER"},{"type":"tool_use","id":"$id","name":"$name","input":$input}]""") as JsonArray)
    private fun chunks(capsule: AnthropicReplay? = replay(), reason: String? = "tool_use", truncated: Boolean = false,
        input: JSONObject = JSONObject(), name: String = "read_probe", text: String = "before", id: String = "call-1") = listOf(
        LLMStreamChunk.Started, LLMStreamChunk.Text(text), LLMStreamChunk.ToolUseStart(id, name),
        LLMStreamChunk.ToolCallComplete(id, name, input), LLMStreamChunk.Finished(reason, truncated, capsule))
    private fun done() = TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn")))
    private suspend fun runTool(fake: FakeAgentLoopHost, script: TurnScript) {
        fake.scripts.add(script); fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
    }
    private fun assertAbsent(fake: FakeAgentLoopHost) {
        assertTrue("no persisted capsule", fake.persistedReplays.all { it.second == null })
        assertTrue("no terminal capsule", fake.updatedReplays.all { it.second == null })
        assertTrue("no history capsule", fake.agentHistory.all { it.anthropicReplay == null })
        assertTrue("no request capsule", fake.streamMessageSnapshots.flatten().all { it.anthropicReplay == null })
    }

    @Test fun completeToolPublishesOnlyAfterRealResultAndUpdatesTheEarlyRow() = runTest(dispatcher) {
        val fake = host(); val capsule = replay()
        fake.beforeExecuteTool = { assertTrue(fake.agentHistory.all { it.anthropicReplay == null }) }
        fake.toolResults.add(ToolExecutionResult("real result", true, toolTitle = "probe"))
        runTool(fake, TurnScript(chunks(capsule)))
        assertEquals(1, fake.toolExecutions.size)
        val early = fake.persistedTurns.first().dbId
        assertNull(fake.persistedReplays.first().second)
        assertEquals(early, fake.updatedPersistedTurns.single().first)
        assertSame(capsule, fake.updatedReplays.single().second)
        val native = fake.streamMessageSnapshots[1].single { it.anthropicReplay != null }
        assertSame(capsule, native.anthropicReplay)
        assertEquals(capsule.blocksCopy(), native.anthropicReplay!!.blocksCopy())
        assertTrue(fake.assistantSnapshots.none { it.first.contains("PRIVATE-") })
        assertEquals(2, fake.persistedTurns.size) // tool turn + final answer, not duplicate tool rows
    }
    @Test fun interleavedTwoToolGroupPreservesNativeAndPublicOrder() = runTest(dispatcher) {
        val fake = host()
        val capsule = AnthropicReplayCodec.complete(AnthropicReplay.Source.fromActualRequest(
            "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "fixture-instance")!!,
            Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"ORDER-SENTINEL"},{"type":"text","text":"one"},{"type":"tool_use","id":"a","name":"read_probe","input":{"tag":"first"}},{"type":"text","text":"two"},{"type":"tool_use","id":"b","name":"read_probe","input":{"tag":"second"}}]""") as JsonArray)
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("one"), LLMStreamChunk.ToolUseStart("a", "read_probe"),
            LLMStreamChunk.ToolCallComplete("a", "read_probe", JSONObject("""{"tag":"first"}""")),
            LLMStreamChunk.Text("two"), LLMStreamChunk.ToolUseStart("b", "read_probe"),
            LLMStreamChunk.ToolCallComplete("b", "read_probe", JSONObject("""{"tag":"second"}""")),
            LLMStreamChunk.Finished("tool_use", anthropicReplay = capsule))))
        fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(listOf("a", "b"), fake.toolExecutions.map { it.toolId })
        val message = fake.streamMessageSnapshots[1].single { it.anthropicReplay != null }
        assertSame(capsule, message.anthropicReplay)
        assertEquals(listOf("text", "tool", "text", "tool"), message.contentParts.map {
            when (it) { is AgentContentPart.Text -> "text"; is AgentContentPart.ToolUse -> "tool"; else -> "other" } })
        assertEquals(capsule.blocksCopy(), message.anthropicReplay!!.blocksCopy())
    }
    @Test fun engineTerminalWriteReloadsThroughTheRealRowMapper() = runTest(dispatcher) {
        val fake = host()
        val rows = linkedMapOf<String, MessageEntity>()
        val capsule = replay()
        val host = object : AgentLoopHost by fake {
            override suspend fun persistAssistantTurn(parts: List<AgentContentPart>, usage: LLMUsage?,
                reasoningContent: String?, toolBlockMeta: Map<String, AssistantBlock>, modelId: String?,
                entryId: String?, stableId: String?, anthropicReplay: AnthropicReplay?): String? {
                val id = fake.persistAssistantTurn(parts, usage, reasoningContent, toolBlockMeta, modelId, entryId, stableId, anthropicReplay)
                    ?: return null
                rows[id] = MessageEntity(id, "sid", "assistant", buildAssistantTurnPartsJson(parts, toolBlockMeta, anthropicReplay),
                    0, sortOrder = rows.size)
                return id
            }
            override suspend fun updatePersistedAssistantTurn(dbId: String, parts: List<AgentContentPart>,
                toolBlockMeta: Map<String, AssistantBlock>, anthropicReplay: AnthropicReplay?) {
                fake.updatePersistedAssistantTurn(dbId, parts, toolBlockMeta, anthropicReplay)
                rows[dbId] = rows.getValue(dbId).copy(partsJson = buildAssistantTurnPartsJson(parts, toolBlockMeta, anthropicReplay))
            }
            override suspend fun persistToolResultMessage(parts: List<AgentContentPart>, transcriptRedactions: Map<String, String>): String? {
                val id = fake.persistToolResultMessage(parts, transcriptRedactions) ?: return null
                rows[id] = MessageEntity(id, "sid", "user", buildToolResultPartsJson(parts.filterIsInstance<AgentContentPart.ToolResult>()),
                    0, sortOrder = rows.size)
                return id
            }
        }
        fake.beforeExecuteTool = {
            assertEquals(1, rows.size)
            assertFalse(rows.values.single().partsJson.contains("PRIVATE-"))
        }
        fake.scripts.add(TurnScript(chunks(capsule))); fake.scripts.add(done())
        engine(host).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(3, rows.size) // assistant(tool), user(results), assistant(done)
        val restored = buildLlmMessagesFromParsed(parseRows(rows.values.toList()), File(".")) { _, _ -> null }
        assertEquals(capsule.blocksCopy(), restored.first().anthropicReplay!!.blocksCopy())
        assertEquals(fake.updatedPersistedTurns.single().first, restored.first().dbMessageId)
        assertFalse(restored.joinToString { it.content }.contains("PRIVATE-"))
    }
    @Test fun realToolFailureIsStillACompletePairedResult() = runTest(dispatcher) {
        val fake = host(); fake.toolResults.add(ToolExecutionResult("failure", false, toolTitle = "probe"))
        runTool(fake, TurnScript(chunks()))
        assertNotNull(fake.updatedReplays.single().second)
        assertTrue(fake.streamMessageSnapshots[1].flatMap { it.contentParts }
            .filterIsInstance<AgentContentPart.ToolResult>().single().isError)
    }
    @Test fun eofMaxTokensUnknownAndDuplicateFinishNeverQualify() = runTest(dispatcher) {
        val scripts = listOf(chunks(reason = null), chunks(reason = "max_tokens"), chunks(truncated = true),
            chunks(reason = "mystery"), chunks() + LLMStreamChunk.Finished("tool_use", anthropicReplay = replay()),
            chunks().dropLast(1), chunks(capsule = AnthropicReplayCodec.unavailable()),
            chunks(capsule = AnthropicReplayCodec.absent()), chunks() + LLMStreamChunk.Text("late"))
        for (events in scripts) {
            val fake = host(); runTool(fake, TurnScript(events)); assertAbsent(fake)
        }
    }
    @Test fun missingStartRenamedIdOrDifferentNativeSequenceNeverQualifies() = runTest(dispatcher) {
        for (events in listOf(chunks().filterNot { it is LLMStreamChunk.ToolUseStart },
            chunks(replay(id = "other")), chunks(replay(name = "other")),
            chunks().dropLast(1) + listOf(LLMStreamChunk.ToolUseStart("call-1", "read_probe"),
                LLMStreamChunk.ToolCallComplete("call-1", "read_probe", JSONObject()),
                LLMStreamChunk.Finished("tool_use", anthropicReplay = replay())))) {
            val fake = host(); runTool(fake, TurnScript(events)); assertAbsent(fake)
        }
    }
    @Test fun collectExceptionAfterFinishDoesNotPublishItsCapsule() = runTest(dispatcher) {
        val fake = host()
        fake.scripts.add(TurnScript(chunks(), throwAfter = LLMError.NetworkError(java.io.IOException("reset"))))
        fake.scripts.add(done())
        try { engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null) } catch (_: Exception) { }
        assertEquals(2, fake.streamRequests.size)
        assertEquals(1, fake.autoRetryCalls.size)
        assertEquals(0, fake.toolExecutions.size)
        assertEquals("done", fake.finalAssistantText)
        assertAbsent(fake)
    }
    @Test fun cancelDuringToolNeverPublishesThePendingCapsule() = runTest(dispatcher) {
        val fake = host(); fake.toolThrowables.add(CancellationException("stop"))
        fake.scripts.add(TurnScript(chunks()))
        try { engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null) } catch (_: CancellationException) { }
        assertAbsent(fake)
        assertTrue(fake.persistedTurns.isNotEmpty())
    }
    @Test fun unknownExecutionOutcomeAndBudgetRefusalDoNotQualify() = runTest(dispatcher) {
        val unknown = host()
        unknown.toolResults.add(ToolExecutionResult("may have run", false, outcomeKnown = false))
        runTool(unknown, TurnScript(chunks())); assertEquals(1, unknown.toolExecutions.size); assertAbsent(unknown)
        val budget = host()
        val observer = ChatAgentTraceObserver(traceDirResolver = { null }, wallClockMs = { 0L },
            monotonicClockMs = { 0L }, warn = { _, _ -> })
        budget.beforeExecuteTool = { observer.t7BudgetStopReason = "tool_calls" }
        budget.scripts.add(TurnScript(chunks()))
        AgentLoopEngine(budget, observer).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(1, budget.toolExecutions.size); assertAbsent(budget)
    }
    @Test fun sensitiveTranscriptAndNativeArgRepairInvalidateTheWholeGroup() = runTest(dispatcher) {
        val shell = AgentToolDefinition("shell_execute", "shell", mapOf("command" to AgentToolParam("string", "cmd")), listOf("command"))
        for (input in listOf("{\"command\":\"android-clipboard get\"}", "{\"command\":42}")) {
            val fake = FakeAgentLoopHost(agentTools = listOf(shell))
            val capsule = replay(name = "shell_execute", input = input)
            runTool(fake, TurnScript(chunks(capsule, input = JSONObject(input), name = "shell_execute")))
            assertAbsent(fake)
        }
    }
    @Test fun preflightSyntheticResultAndTextProjectionChangeInvalidateTheGroup() = runTest(dispatcher) {
        val fake = host(); runTool(fake, TurnScript(chunks(replay(name = "unknown"), name = "unknown")))
        assertEquals(0, fake.toolExecutions.size); assertAbsent(fake)
        val changed = host(); runTool(changed, TurnScript(chunks(replay(text = "different"))))
        assertAbsent(changed)
    }
    @Test fun newTurnCannotInheritThePreviousAttemptCapsule() = runTest(dispatcher) {
        val fake = host()
        fake.scripts.add(TurnScript(chunks()))
        fake.scripts.add(TurnScript(chunks(capsule = null, text = "second")))
        fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertNotNull(fake.updatedReplays[0].second); assertNull(fake.updatedReplays[1].second)
        assertEquals(1, fake.streamMessageSnapshots[2].count { it.anthropicReplay != null })
    }
    @Test fun failedAttemptFallbackDoesNotResurrectCapsule() = runTest(dispatcher) {
        val fake = host()
        fake.scripts.add(TurnScript(chunks(), throwAfter = CancellationException("rate").apply { initCause(LLMError.RateLimited()) }))
        fake.scripts.add(done())
        try { engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null,
            fallbackProviders = listOf(FallbackCandidate(ScriptedProvider(model = LLMModel("claude-sonnet-4-6", "fallback", "Anthropic")), "fallback"))) } catch (_: Exception) { }
        assertEquals(2, fake.streamRequests.size)
        assertEquals(1, fake.fallbackTriggers.size)
        assertEquals(0, fake.toolExecutions.size)
        assertEquals("done", fake.finalAssistantText)
        assertAbsent(fake)
    }
    @Test fun retryWithANewToolGroupOnlyPublishesTheNewCapsule() = runTest(dispatcher) {
        val fake = host(); val next = replay(text = "fresh", id = "fresh-call")
        fake.scripts.add(TurnScript(chunks(), throwAfter = LLMError.TransientError("reset")))
        fake.scripts.add(TurnScript(chunks(next, text = "fresh", id = "fresh-call"))); fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(3, fake.streamRequests.size)
        assertEquals(1, fake.autoRetryCalls.size)
        assertEquals(1, fake.toolExecutions.size)
        assertSame(next, fake.updatedReplays.single().second)
        assertSame(next, fake.streamMessageSnapshots.last().single { it.anthropicReplay != null }.anthropicReplay)
    }
    @Test fun continuationFoldAndSeamNeverRetainANativeToolSegment() = runTest(dispatcher) {
        val fake = host()
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("head"), LLMStreamChunk.Finished("max_tokens"))))
        fake.scripts.add(TurnScript(chunks(replay(text = "head+tail"), text = "head+tail")))
        fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(3, fake.streamRequests.size)
        assertEquals(1, fake.toolExecutions.size)
        assertTrue(fake.finalAssistantText.contains("head+tail"))
        assertAbsent(fake)
    }
    @Test fun ordinaryAndEmptyRecoveryCannotAdoptToolCapsule() = runTest(dispatcher) {
        val fake = host()
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Finished("end_turn", anthropicReplay = replay()))))
        fake.scripts.add(done())
        engine(fake).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(2, fake.streamRequests.size)
        assertEquals("done", fake.finalAssistantText)
        assertAbsent(fake)
    }
}

/** The real serializer -> parts parser -> history builders, without a ViewModel/Room fake codec. */
class AnthropicReplayHistoryStorageTest {
    private val source = AnthropicReplay.Source.fromActualRequest("https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "fixture")!!
    private fun capsule(input: String = "{}", text: String = "hello") = AnthropicReplayCodec.complete(source,
        Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"STORAGE-SENTINEL"},{"type":"text","text":"$text"},{"type":"redacted_thinking","data":"CIPHER-SENTINEL"},{"type":"tool_use","id":"call","name":"read_probe","input":$input}]""") as JsonArray)
    private fun public(input: String = "{}", text: String = "hello") = listOf<AgentContentPart>(
        AgentContentPart.Text(text), AgentContentPart.ToolUse("call", "read_probe", JSONObject(input)))
    private fun entity(raw: String, role: String = "assistant") = MessageEntity("row", "sid", role, raw, 0, sortOrder = 0)
    private fun single(raw: String, role: String = "assistant"): LLMMessage {
        val row = parseRows(listOf(entity(raw, role))).single()
        return buildSingleLlmMessage(row.entity, raw, row.parts, row.malformed, File(".")) { _, _ -> null }
    }
    @Test fun realRoundTripKeepsEmptyThinkingCipherOrderAndExtremeNumbers() {
        // Numbers are in opaque structured values (including citations), never parsed by org.json.
        val native = Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"STORAGE-SENTINEL"},{"type":"text","text":"hello","citations":[{"big":123456789012345678901234567890,"fraction":0.12345678901234567890123456789,"huge":1e400}]},{"type":"redacted_thinking","data":"CIPHER-SENTINEL"},{"type":"tool_use","id":"call","name":"read_probe","input":{}}]""") as JsonArray
        val replay = AnthropicReplayCodec.complete(source, native)
        val raw = buildAssistantTurnPartsJson(public(), emptyMap(), replay)
        val message = single(raw)
        assertEquals(native, message.anthropicReplay!!.blocksCopy())
        assertEquals("", (message.anthropicReplay!!.blocksCopy()!![0] as JsonObject)["thinking"]!!.jsonPrimitive.content)
        assertEquals("hello", message.content)
        assertFalse(message.content.contains("SENTINEL"))
        assertEquals(2, tryParsePartsJson(raw)!!.size)
    }
    @Test fun exactToolInputRemainsPrivateButLossyPublicProjectionNeverQualifies() {
        val input = """{"big":123456789012345678901234567890,"fraction":0.12345678901234567890123456789}"""
        val replay = capsule(input)
        val raw = buildAssistantTurnPartsJson(public(input), emptyMap(), replay)
        val stored = AnthropicReplayCodec.decode(Json.parseToJsonElement(raw).jsonArray.last().jsonObject["value"])
        assertEquals(replay.blocksCopy(), stored.blocksCopy()) // Storage never rewrites private tokens.
        val projected = Json.parseToJsonElement(JSONObject(input).toString())
        if (projected == Json.parseToJsonElement(input)) {
            assertEquals(replay.blocksCopy(), single(raw).anthropicReplay!!.blocksCopy())
        } else {
            assertNull("a different actual/public numeric value is not replay evidence", single(raw).anthropicReplay)
        }
    }
    @Test fun opaqueToolExponentCanBeStoredWithoutLosingItsToken() {
        // Android rejects this input before the legacy ToolUse can carry it;
        // its provider fallback is {}. Exact private storage is independent,
        // but a mismatching public/execution projection cannot qualify reload.
        val input = """{"huge":1e400}"""
        val replay = capsule(input)
        val raw = buildAssistantTurnPartsJson(public(), emptyMap(), replay)
        val part = Json.parseToJsonElement(raw).jsonArray.last().jsonObject
        assertEquals("1e400", AnthropicReplayCodec.decode(part["value"]).blocksCopy()!!.last()
            .jsonObject["input"]!!.jsonObject["huge"]!!.jsonPrimitive.content)
    }
    @Test fun publicBytesAreIdenticalWithoutMetadataAndPrivateIsLast() {
        val before = buildAssistantTurnPartsJson(public(), emptyMap())
        assertEquals(before, buildAssistantTurnPartsJson(public(), emptyMap(), null))
        assertEquals(before, buildAssistantTurnPartsJson(public(), emptyMap(), AnthropicReplayCodec.unavailable()))
        val raw = buildAssistantTurnPartsJson(public(), emptyMap(), capsule())
        assertTrue(raw.startsWith(before.dropLast(1) + ","))
        assertEquals("anthropicReplayV1", Json.parseToJsonElement(raw).jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
    }
    @Test fun utf8BudgetDropsOnlyMetadataAtTheBoundary() {
        val text = "中".repeat(166_500)
        val public = buildAssistantTurnPartsJson(public(text = text), emptyMap())
        assertTrue(public.toByteArray().size < 500_000)
        assertEquals(public, buildAssistantTurnPartsJson(public(text = text), emptyMap(), capsule()))
        assertTrue(single(public).content.startsWith("中"))
    }
    @Test fun malformedDuplicateBadVersionUnknownAndUserCannotReloadComplete() {
        val raw = buildAssistantTurnPartsJson(public(), emptyMap(), capsule())
        val part = Json.parseToJsonElement(raw).jsonArray.last().toString()
        for (bad in listOf(raw.dropLast(1) + ",$part]", raw.replace("\"version\":1", "\"version\":2"),
            raw.replace("\"state\":\"complete\"", "\"state\":\"unavailable\""), raw.dropLast(1))) {
            val msg = single(bad)
            assertNull(msg.anthropicReplay)
            assertFalse(msg.content.contains("SENTINEL"))
        }
        assertNull(single(raw, "user").anthropicReplay)
        val prose = "user mentioned anthropicReplayV1 and STORAGE-SENTINEL literally"
        assertEquals(prose, single(buildAssistantTurnPartsJson(listOf(AgentContentPart.Text(prose)), emptyMap()), "user").content)
        assertEquals("ordinary [broken", single("ordinary [broken").content)
    }
    @Test fun editedPublicTextInputAndOrderCannotKeepOldPrivateProof() {
        val raw = buildAssistantTurnPartsJson(public("{\"path\":\"old\"}"), emptyMap(), capsule("{\"path\":\"old\"}"))
        val arr = Json.parseToJsonElement(raw).jsonArray
        val changed = JsonArray(listOf(JsonObject(arr[0].jsonObject + ("value" to JsonPrimitive("edited")))) + arr.drop(1)).toString()
        assertNull(single(changed).anthropicReplay)
        assertNull(single(raw.replace("\\\"path\\\":\\\"old\\\"", "\\\"path\\\":\\\"new\\\"")).anthropicReplay)
    }
    @Test fun actualBatchReadRequiresAllRealPairedResults() {
        val raw = buildAssistantTurnPartsJson(public(), emptyMap(), capsule())
        val assistant = entity(raw)
        fun batch(results: List<AgentContentPart.ToolResult>) = buildLlmMessagesFromParsed(parseRows(listOf(assistant,
            entity(buildToolResultPartsJson(results), "user").copy(id = "result", sortOrder = 1))), File(".")) { _, _ -> null }
        assertNotNull(batch(listOf(AgentContentPart.ToolResult("call", "read_probe", "failed", true)))[0].anthropicReplay)
        assertNull(batch(emptyList())[0].anthropicReplay)
        assertNull(batch(listOf(AgentContentPart.ToolResult("call", "read_probe", SANITIZE_PLACEHOLDER_RESULT_CONTENT, true)))[0].anthropicReplay)
        val message = single(raw)
        val result = LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("call", "read_probe", "ok")))
        assertNull(guardAnthropicReplayHistory(listOf(message, result), listOf(LLMMessage(LLMMessage.Role.USER, "head"), message, result))[0].anthropicReplay)
        assertNull(guardAnthropicReplayHistory(listOf(message.copy(contentParts = public(text = "edited")), result))[0].anthropicReplay)
    }
}
