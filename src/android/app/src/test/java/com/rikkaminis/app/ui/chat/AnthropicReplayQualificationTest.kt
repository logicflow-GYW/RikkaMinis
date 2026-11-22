package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.model.*
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

class AnthropicReplayQualificationTest {
    private val source = AnthropicReplay.Source.fromActualRequest(
        "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "qualification-fixture")!!
    private fun replay(input: String = "{}", id: String = "call") = AnthropicReplayCodec.complete(source,
        Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"QUALIFICATION-SYNTHETIC"},{"type":"text","text":"check"},{"type":"tool_use","id":"$id","name":"read_probe","input":$input}]""") as JsonArray)
    private fun parts(input: String = "{}", id: String = "call") = listOf<AgentContentPart>(
        AgentContentPart.Text("check"), AgentContentPart.ToolUse(id, "read_probe", JSONObject(input)))
    private fun assistant(c: AnthropicReplay = replay(), input: String = "{}", id: String = "call") =
        LLMMessage(LLMMessage.Role.ASSISTANT, "check", contentParts = parts(input, id), dbMessageId = "a-$id", anthropicReplay = c)
    private fun result(id: String = "call", content: String = "real") = LLMMessage(LLMMessage.Role.USER, "",
        contentParts = listOf(AgentContentPart.ToolResult(id, "read_probe", content)), dbMessageId = "r-$id")
    private fun read(c: AnthropicReplay, input: String): AnthropicReplay? {
        val raw = buildAssistantTurnPartsJson(parts(input), emptyMap(), c)
        return readAnthropicReplayPart(raw, "assistant", tryParsePartsJson(raw).orEmpty())
    }
    private fun attempt(c: AnthropicReplay, input: String): AnthropicReplay? {
        val a = AnthropicReplayAttempt()
        listOf(LLMStreamChunk.ToolUseStart("call", "read_probe"),
            LLMStreamChunk.ToolCallComplete("call", "read_probe", JSONObject(input)),
            LLMStreamChunk.Finished("tool_use", anthropicReplay = c)).forEach(a::observe)
        return a.finish(listOf(Triple("call", "read_probe", JSONObject(input))))
    }

    @Test fun roundedNestedExecutionCannotQualifyAttemptOrColdRead() {
        val native = """{"nested":[{"n":0.12345678901234567890123456789}]}"""
        val executed = """{"nested":[{"n":0.12345678901234568}]}"""
        val c = replay(native)
        assertFalse(replayMatchesPublic(c, parts(executed)))
        assertNull(attempt(c, executed))
        assertNull(read(c, executed))
        assertEquals("0.12345678901234567890123456789", c.blocksCopy()!!.last().jsonObject["input"]!!
            .jsonObject["nested"]!!.jsonArray[0].jsonObject["n"]!!.jsonPrimitive.content)
    }
    @Test fun exactIntegersAndDecimalsRemainEligibleAcrossKeyOrder() {
        val native = """{"integer":42,"nested":[0.125,true,null,"1"]}"""
        val actual = """{"nested":[0.125,true,null,"1"],"integer":42}"""
        val c = replay(native)
        assertTrue(replayMatchesPublic(c, parts(actual)))
        assertSame(c, attempt(c, actual))
        assertEquals(c.blocksCopy(), read(c, actual)!!.blocksCopy())
    }
    @Test fun distinctHighPrecisionEditsCannotHideBehindTheSameDouble() {
        val first = """{"n":1.0000000000000000001}"""
        val edited = """{"n":1.0000000000000000002}"""
        val c = replay(first)
        assertNull(attempt(c, edited))
        assertNull(read(c, edited))
        // Explicitly test the Android collision projection even on Maven org.json.
        assertNull(attempt(c, """{"n":1}"""))
        assertNull(read(c, """{"n":1}"""))
        assertEquals("1.0000000000000000001", c.blocksCopy()!!.last().jsonObject["input"]!!
            .jsonObject["n"]!!.jsonPrimitive.content)
    }
    @Test fun numericStringAndChangedExactNumberAreNotEquivalent() {
        for (actual in listOf("""{"n":"1"}""", """{"n":2}""", """{"n":null}""")) {
            assertNull(attempt(replay("""{"n":1}"""), actual))
            assertNull(read(replay("""{"n":1}"""), actual))
        }
    }
    @Test fun summaryInjectionAndStableRowCopiesDoNotInvalidateAFreshGroup() {
        val user = LLMMessage(LLMMessage.Role.USER, "new request", dbMessageId = "u")
        val a = assistant(); val r = result()
        val original = listOf(user, a, r)
        val projected = injectSummaryIntoPostAnchor(listOf(user, a.copy(), r.copy()), "<context-summary>old</context-summary>")
        assertNotSame(user, projected[0])
        assertNotEquals(user.content, projected[0].content)
        assertSame(a.anthropicReplay, guardAnthropicReplayHistory(projected, original)[1].anthropicReplay)
    }
    @Test fun onlyTheGroupWhoseRealResultChangedLosesEligibility() {
        val a = assistant(); val r = result(); val b = assistant(replay(id = "second"), id = "second"); val s = result("second")
        val original = listOf(a, r, LLMMessage(LLMMessage.Role.USER, "next", dbMessageId = "u2"), b, s)
        val edited = original.toMutableList().also { it[1] = result(content = "rewritten") }
        val guarded = guardAnthropicReplayHistory(edited, original)
        assertNull(guarded[0].anthropicReplay)
        assertSame(b.anthropicReplay, guarded[3].anthropicReplay)
    }
    @Test fun pairedContentErrorImageAndPlaceholderRewritesMustDecline() {
        val a = assistant(); val r = result(); val real = r.contentParts.single() as AgentContentPart.ToolResult
        val original = listOf(a, r)
        for (changed in listOf(real.copy(content = "changed"), real.copy(isError = true),
            real.copy(imageData = byteArrayOf(1), imageMimeType = "image/png"),
            real.copy(content = SANITIZE_PLACEHOLDER_RESULT_CONTENT))) {
            assertNull(guardAnthropicReplayHistory(listOf(a, r.copy(contentParts = listOf(changed))), original)[0].anthropicReplay)
        }
        assertNull(guardAnthropicReplayHistory(listOf(a, result(content = SANITIZE_PLACEHOLDER_RESULT_CONTENT)))[0].anthropicReplay)
    }
    @Test fun removedPredecessorAndMissingResultCannotKeepTheGroup() {
        val u = LLMMessage(LLMMessage.Role.USER, "head", dbMessageId = "u")
        val a = assistant(); val r = result(); val original = listOf(u, a, r)
        assertNull(guardAnthropicReplayHistory(listOf(a, r), original)[0].anthropicReplay)
        assertNull(guardAnthropicReplayHistory(listOf(u, a), original)[1].anthropicReplay)
        assertNull(guardAnthropicReplayHistory(listOf(u, a, r.copy(dbMessageId = "replacement")), original)[1].anthropicReplay)
    }
    @Test fun changedAssistantProjectionAndAmbiguousRowIdentityCannotQualify() {
        val a = assistant(); val r = result(); val original = listOf(a, r)
        val edited = a.copy(contentParts = listOf(AgentContentPart.Text("edited"), parts().last()))
        assertNull(guardAnthropicReplayHistory(listOf(edited, r), original)[0].anthropicReplay)
        assertNull(guardAnthropicReplayHistory(listOf(a, r), listOf(a, r, a.copy()))[0].anthropicReplay)
        assertNull(guardAnthropicReplayHistory(listOf(a, r, a.copy(), r), original)[0].anthropicReplay)
        val synthesized = a.copy(dbMessageId = null)
        assertNull(guardAnthropicReplayHistory(listOf(synthesized.copy(), r), listOf(synthesized, r))[0].anthropicReplay)
    }
    @Test fun existingCompactBoundaryRejectsOldGroupsButNotPostAnchorGroups() {
        val old = assistant(); val oldResult = result()
        val prompt = LLMMessage(LLMMessage.Role.USER, "post-anchor", dbMessageId = "u2")
        val fresh = assistant(replay(id = "fresh"), id = "fresh"); val freshResult = result("fresh")
        val original = listOf(old, oldResult, prompt, fresh, freshResult)
        val projected = original.toMutableList().also { it[2] = prompt.copy(content = "summary + prompt") }
        val guarded = guardAnthropicReplayHistory(projected, original, compactedThroughIndex = 1)
        assertNull(guarded[0].anthropicReplay)
        assertSame(fresh.anthropicReplay, guarded[3].anthropicReplay)
        assertTrue(guardAnthropicReplayHistory(projected, original, Int.MAX_VALUE).all { it.anthropicReplay == null })
    }
    @Test fun explicitCompactTrimOffloadClearingCannotBeResurrectedByTheGuard() {
        val history = mutableListOf(assistant(), result())
        val original = history.toList()
        clearAnthropicReplays(history)
        assertTrue(guardAnthropicReplayHistory(history, original).all { it.anthropicReplay == null })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnthropicReplayQualificationEngineTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun repeatedExistingSummaryProjectionAllowsTheNextRealToolRequest() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "synthetic", emptyMap())))
        fake.agentHistory.add(LLMMessage(LLMMessage.Role.USER, "fresh instruction", dbMessageId = "prompt"))
        val c = AnthropicReplayCodec.complete(AnthropicReplay.Source.fromActualRequest(
            "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "qualification-fixture")!!,
            Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"SUMMARY-QUALIFICATION-SYNTHETIC"},{"type":"tool_use","id":"call","name":"read_probe","input":{}}]""") as JsonArray)
        var projections = 0
        val host = object : AgentLoopHost by fake {
            override fun effectiveAgentHistory(): List<LLMMessage> {
                projections++
                return guardAnthropicReplayHistory(injectSummaryIntoPostAnchor(agentHistory,
                    "<context-summary>an earlier exchange already compacted</context-summary>"), agentHistory)
            }
        }
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.ToolUseStart("call", "read_probe"),
            LLMStreamChunk.ToolCallComplete("call", "read_probe", JSONObject()),
            LLMStreamChunk.Finished("tool_use", anthropicReplay = c))))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
        AgentLoopEngine(host, ChatAgentTraceObserver(traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> }))
            .runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "synthetic", "Anthropic")), null)
        assertEquals(2, fake.streamMessageSnapshots.size)
        assertEquals(1, fake.toolExecutions.size)
        assertTrue(projections >= 2)
        assertTrue(fake.streamMessageSnapshots.all { it.first().content.contains("<context-summary>") })
        assertEquals(1, fake.streamMessageSnapshots[1].count { it.anthropicReplay != null })
        assertEquals(1, fake.agentHistory.count { it.anthropicReplay != null })
    }

    @Test fun actualExecutionNumericLossRejectsHotAndColdButExactInputStillWorks() = runTest(dispatcher) {
        for (token in listOf("0.125", "42", "0.12345678901234567890123456789")) {
            val input = "{\"n\":$token}"
            val c = AnthropicReplayCodec.complete(AnthropicReplay.Source.fromActualRequest(
                "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "qualification-fixture")!!,
                Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"ENGINE-QUALIFICATION-SYNTHETIC"},{"type":"tool_use","id":"call","name":"read_probe","input":$input}]""") as JsonArray)
            val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "synthetic", emptyMap())))
            val rows = linkedMapOf<String, MessageEntity>()
            val host = object : AgentLoopHost by fake {
                override suspend fun persistAssistantTurn(parts: List<AgentContentPart>, usage: LLMUsage?, reasoningContent: String?, toolBlockMeta: Map<String, AssistantBlock>, modelId: String?, entryId: String?, stableId: String?, anthropicReplay: AnthropicReplay?): String? {
                    val id = fake.persistAssistantTurn(parts, usage, reasoningContent, toolBlockMeta, modelId, entryId, stableId, anthropicReplay) ?: return null
                    rows[id] = MessageEntity(id, "qualification", "assistant", buildAssistantTurnPartsJson(parts, toolBlockMeta, anthropicReplay), 0L, sortOrder = rows.size)
                    return id
                }
                override suspend fun updatePersistedAssistantTurn(dbId: String, parts: List<AgentContentPart>, toolBlockMeta: Map<String, AssistantBlock>, anthropicReplay: AnthropicReplay?) {
                    fake.updatePersistedAssistantTurn(dbId, parts, toolBlockMeta, anthropicReplay)
                    rows[dbId] = rows.getValue(dbId).copy(partsJson = buildAssistantTurnPartsJson(parts, toolBlockMeta, anthropicReplay))
                }
                override suspend fun persistToolResultMessage(parts: List<AgentContentPart>, transcriptRedactions: Map<String, String>): String? {
                    val id = fake.persistToolResultMessage(parts, transcriptRedactions) ?: return null
                    rows[id] = MessageEntity(id, "qualification", "user", buildToolResultPartsJson(parts.filterIsInstance<AgentContentPart.ToolResult>()), 0L, sortOrder = rows.size)
                    return id
                }
            }
            fake.scripts.add(TurnScript(listOf(LLMStreamChunk.ToolUseStart("call", "read_probe"),
                LLMStreamChunk.ToolCallComplete("call", "read_probe", JSONObject(input)),
                LLMStreamChunk.Finished("tool_use", anthropicReplay = c))))
            fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
            AgentLoopEngine(host, ChatAgentTraceObserver(traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> }))
                .runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "synthetic", "Anthropic")), null)
            assertEquals(2, fake.streamMessageSnapshots.size)
            assertEquals(1, fake.toolExecutions.size)
            val executed = Json.parseToJsonElement(fake.toolExecutions.single().argsJson).jsonObject["n"]!!.jsonPrimitive.content
            val expected = if (executed == token) 1 else 0
            assertEquals(expected, fake.streamMessageSnapshots[1].count { it.anthropicReplay != null })
            assertEquals(expected, buildLlmMessagesFromParsed(parseRows(rows.values.toList()), File(".")) { _, _ -> null }.count { it.anthropicReplay != null })
            assertEquals(token, c.blocksCopy()!!.last().jsonObject["input"]!!.jsonObject["n"]!!.jsonPrimitive.content)
        }
    }
}
