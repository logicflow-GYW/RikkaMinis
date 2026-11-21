package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.debug.LLMRequestLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Whole Provider and real Call; the terminal application interceptor never opens a socket.
 * Official URL admission remains production code, not a localhost exemption. */
class AnthropicNativeReplayWireTest {
    private val signature = "PRIVATE_SIGNATURE_D05_SYNTHETIC"
    private val redacted = "PRIVATE_REDACTED_D05_SYNTHETIC"
    private val thinking = "PRIVATE_THINKING_D05_SYNTHETIC"
    private val publicText = "PUBLIC_BODY_D05_CONTROL"
    private val precise = "12345678901234567890.123456789012345678901234567890"
    private val input get() = """{"decimal":$precise,"huge":123456789012345678901234567890,"exp":1.230000000000000000000001e-27}"""
    private val source get() = AnthropicReplay.Source.fromActualRequest(
        "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "lane-a")!!
    private fun blocks(emptyThinking: Boolean = false): JsonArray = Json.parseToJsonElement("""[
        {"type":"thinking","thinking":"${if (emptyThinking) "" else thinking}","signature":"$signature"},
        {"type":"redacted_thinking","data":"$redacted"},
        {"type":"tool_use","id":"tool_1","name":"lookup","input":$input},
        {"type":"text","text":"$publicText"}
    ]""") as JsonArray

    private class Transport(val provider: AnthropicProvider) {
        data class Reply(val body: String, val status: Int = 200, val finalUrl: String? = null)
        val requests = CopyOnWriteArrayList<String>()
        val replies = java.util.ArrayDeque<Reply>()
        val client: OkHttpClient
        init {
            val field = AnthropicProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
            val original = field.get(provider) as OkHttpClient
            client = original.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.method == "POST")
                    requests.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    val reply = synchronized(replies) { replies.removeFirst() }
                    Response.Builder().request(if (reply.finalUrl == null) request else request.newBuilder().url(reply.finalUrl).build())
                        .protocol(Protocol.HTTP_1_1).code(reply.status).message("synthetic")
                        .header("Retry-After", "7")
                        .body(reply.body.toResponseBody("text/event-stream".toMediaType())).build()
                }.build()
            field.set(provider, client)
        }
        fun reply(body: String, status: Int = 200, finalUrl: String? = null) {
            replies.add(Reply(body, status, finalUrl))
        }
    }
    private fun transport(base: String = "https://api.anthropic.com", modelId: String = "claude-haiku-4-5", lane: String = "lane-a"): Transport {
        val provider = AnthropicProvider("synthetic-not-a-key", LLMModel.claudeHaiku45.copy(id = modelId), base)
        provider.instanceContext = ProviderInstance(lane, "synthetic", ProviderType.anthropic, ProviderCredential.apiKey)
        return Transport(provider)
    }
    private fun event(type: String, fields: String = ""): String = """{"type":"$type"${if (fields.isEmpty()) "" else ",$fields"}}"""
    private fun frame(events: List<String>) = events.joinToString("") { "data: $it\n\n" }
    private fun events(emptyThinking: Boolean = false): List<String> = buildList {
        add(event("message_start", """"message":{"type":"message","role":"assistant","content":[],"usage":{"input_tokens":11}}"""))
        blocks(emptyThinking).forEachIndexed { index, element ->
            val block = element.jsonObject
            when (block["type"]!!.jsonPrimitive.content) {
                "thinking" -> {
                    add(event("content_block_start", """"index":$index,"content_block":{"type":"thinking","thinking":""}"""))
                    if (!emptyThinking) add(event("content_block_delta", """"index":$index,"delta":{"type":"thinking_delta","thinking":"$thinking"}"""))
                    add(event("content_block_delta", """"index":$index,"delta":{"type":"signature_delta","signature":"${signature.take(9)}"}"""))
                    add(event("content_block_delta", """"index":$index,"delta":{"type":"signature_delta","signature":"${signature.drop(9)}"}"""))
                }
                "tool_use" -> {
                    add(event("content_block_start", """"index":$index,"content_block":{"type":"tool_use","id":"tool_1","name":"lookup","input":{}}"""))
                    val split = input.length / 2
                    for (part in listOf(input.take(split), input.drop(split))) {
                        add(event("content_block_delta", """"index":$index,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(part)}}"""))
                    }
                }
                "text" -> {
                    add(event("content_block_start", """"index":$index,"content_block":{"type":"text","text":""}"""))
                    add(event("content_block_delta", """"index":$index,"delta":{"type":"text_delta","text":"$publicText"}"""))
                }
                else -> add(event("content_block_start", """"index":$index,"content_block":$block"""))
            }
            add(event("content_block_stop", """"index":$index"""))
        }
        add(event("message_delta", """"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":19}"""))
        add(event("message_stop"))
    }
    private fun response(blocks: JsonArray = blocks(), stop: String = "tool_use") =
        """{"type":"message","role":"assistant","content":$blocks,"stop_reason":"$stop","usage":{"input_tokens":11,"output_tokens":19}}"""
    private suspend fun collect(t: Transport, body: String): List<LLMStreamChunk> {
        t.reply(body)
        return withTimeout(5000) {
            t.provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096, thinkingLevel = ThinkingLevel.HIGH).toList()
        }
    }
    private fun history(capsule: AnthropicReplay?, emptyThinking: Boolean = false): List<LLMMessage> = listOf(
        LLMMessage(LLMMessage.Role.USER, "start"),
        LLMMessage(LLMMessage.Role.ASSISTANT, publicText, contentParts = listOf(
            AgentContentPart.ToolUse("tool_1", "lookup", JSONObject(input)), AgentContentPart.Text(publicText)),
            reasoningContent = if (emptyThinking) "" else thinking, anthropicReplay = capsule),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("tool_1", "lookup", "actual-result"))),
    )
    private suspend fun next(t: Transport, messages: List<LLMMessage>, stream: Boolean = false): String {
        if (stream) {
            t.reply(frame(listOf(
                event("message_start", """"message":{"type":"message","role":"assistant","content":[]}"""),
                event("content_block_start", """"index":0,"content_block":{"type":"text","text":""}"""),
                event("content_block_delta", """"index":0,"delta":{"type":"text_delta","text":"done"}"""),
                event("content_block_stop", """"index":0"""),
                event("message_delta", """"delta":{"stop_reason":"end_turn"}"""), event("message_stop"))))
            withTimeout(5000) { t.provider.streamMessage(messages, null, 4096, thinkingLevel = ThinkingLevel.HIGH).toList() }
        } else {
            t.reply("""{"type":"message","role":"assistant","content":[{"type":"text","text":"done"}],"stop_reason":"end_turn"}""")
            withTimeout(5000) { t.provider.sendMessage(messages, null, 4096, thinkingLevel = ThinkingLevel.HIGH) }
        }
        return t.requests.last()
    }
    private fun assistantBlocks(body: String): JsonArray = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
        .first { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject["content"]!!.jsonArray
    private fun assertNoPrivate(body: String) {
        for (sentinel in listOf(signature, redacted, thinking)) assertFalse("private sentinel: $sentinel", body.contains(sentinel))
        assertFalse(body.contains("\"signature\""))
        assertFalse(body.contains("redacted_thinking"))
    }
    private fun recordedLogs(): String = LLMRequestLog.getAll().joinToString("\n") { it.requestBody + it.responseBody }

    @Test fun streamCaptureToActualRequestPreservesBlockOrderAndExactNumbers() = runBlocking {
        for (empty in listOf(false, true)) {
            val t = transport()
            val chunks = collect(t, frame(events(empty)))
            val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
            assertEquals("tool_use", finished.stopReason)
            assertFalse(finished.truncated)
            val capsule = finished.anthropicReplay
            assertNotNull("real Finished must carry the captured native group", capsule)
            assertEquals(AnthropicReplay.State.COMPLETE, capsule!!.state)
            assertEquals(blocks(empty), capsule.blocksCopy())
            assertEquals(publicText, chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
            assertEquals(if (empty) "" else thinking, chunks.filterIsInstance<LLMStreamChunk.ThinkingDelta>().joinToString("") { it.text })
            assertEquals(1, chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().size)
            val body = next(t, history(capsule, empty))
            assertEquals("native order, signatures, redacted data and decimal lexemes", blocks(empty), assistantBlocks(body))
            assertTrue(body.contains(precise))
            assertEquals(source, capsule.source)
            assertNoPrivate(recordedLogs())
            if (com.rikkaminis.app.BuildConfig.DEBUG) {
                assertTrue("public request remains diagnosable", recordedLogs().contains("start"))
            }
        }
    }

    @Test fun nonstreamOriginalBodyCaptureUsesTheSameExactRequestPath() = runBlocking {
        val t = transport()
        t.reply(response())
        val r = withTimeout(5000) { t.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096, thinkingLevel = ThinkingLevel.HIGH) }
        assertEquals(publicText, r.text)
        assertEquals("tool_use", r.stopReason)
        assertEquals(19, r.usage!!.outputTokens)
        assertNotNull("real LLMResponse must capture native blocks", r.anthropicReplay)
        assertEquals(blocks(), r.anthropicReplay!!.blocksCopy())
        assertEquals(blocks(), assistantBlocks(next(t, history(r.anthropicReplay), stream = true)))
        assertNoPrivate(recordedLogs())
    }

    @Test fun incompleteUnknownTruncatedAndPostTerminalEventsNeverQualify() = runBlocking {
        val clean = events()
        val variants = listOf(
            "missing-message-stop" to clean.dropLast(1),
            "unknown-block" to clean.map { it.replace("\"redacted_thinking\"", "\"server_tool_use\"") },
            "bad-index" to clean.map { it.replace("\"index\":1", "\"index\":99") },
            "missing-signature" to clean.filterNot { it.contains("signature_delta") },
            "output-wall" to clean.map { it.replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"max_tokens\"") },
            "context-wall" to clean.map { it.replace("\"stop_reason\":\"tool_use\"", "\"stop_reason\":\"model_context_window_exceeded\"") },
            "after-message-stop" to (clean + event("content_block_delta", """"index":0,"delta":{"type":"signature_delta","signature":"late"}""")),
            "duplicate-start" to (clean.take(2) + clean[1] + clean.drop(2)),
            "unclosed-input" to clean.map { if (it.contains("partial_json") && it.contains("e-27")) it.replace("e-27}", "e-27") else it },
        )
        for ((name, frames) in variants) {
            val t = transport()
            val finished = collect(t, frame(frames)).filterIsInstance<LLMStreamChunk.Finished>().single()
            assertEquals(name, AnthropicReplay.State.UNAVAILABLE, finished.anthropicReplay?.state)
            assertNoPrivate(next(t, history(finished.anthropicReplay)))
        }
    }

    @Test fun eofBeforeDeclaredReasonIsTruncatedAndNeverComplete() = runBlocking {
        val t = transport()
        val finished = collect(t, frame(events().dropLast(2))).filterIsInstance<LLMStreamChunk.Finished>().single()
        assertTrue(finished.truncated)
        assertEquals(AnthropicReplay.State.UNAVAILABLE, finished.anthropicReplay?.state)
    }

    @Test fun lateErrorDoesNotPublishFinishedOrExposePrivateErrorBody() = runBlocking {
        val t = transport()
        val chunks = mutableListOf<LLMStreamChunk>()
        t.reply(frame(events() + event("error", """"error":{"type":"invalid_request_error","message":"signature $signature","signature":"$signature"}""")))
        val error = try {
            withTimeout(5000) { t.provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096, thinkingLevel = ThinkingLevel.HIGH).collect { chunks.add(it) } }
            null
        } catch (e: Exception) { e }
        assertNotNull("late error must still be an error", error)
        assertTrue("no success terminal before body closes", chunks.none { it is LLMStreamChunk.Finished })
        assertNoPrivate(error.toString() + (error?.cause?.toString() ?: "") + recordedLogs())
    }

    @Test fun consumerCancellationCannotDeliverCompleteCapsule() = runBlocking {
        val t = transport()
        t.reply(frame(events()))
        val seen = mutableListOf<LLMStreamChunk>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            t.provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096, thinkingLevel = ThinkingLevel.HIGH).collect {
                seen.add(it)
                if (it is LLMStreamChunk.ThinkingDelta) cancel("synthetic consumer stop")
            }
        }
        withTimeout(5000) { job.join() }
        assertTrue(job.isCancelled)
        assertTrue(seen.none { it is LLMStreamChunk.Finished })
    }

    @Test fun actualEndpointModelInstanceAndRedirectBindCaptureAndReplay() = runBlocking {
        val capsule = AnthropicReplayCodec.complete(source, blocks())
        assertEquals(AnthropicReplay.State.COMPLETE, capsule.state)
        for ((base, model, lane) in listOf(
            Triple("https://relay.invalid", "claude-haiku-4-5", "lane-a"),
            Triple("http://api.anthropic.com", "claude-haiku-4-5", "lane-a"),
            Triple("https://user@api.anthropic.com", "claude-haiku-4-5", "lane-a"),
            Triple("https://api.anthropic.com?trace=1", "claude-haiku-4-5", "lane-a"),
            Triple("https://api.anthropic.com", "claude-sonnet-4-6", "lane-a"),
            Triple("https://api.anthropic.com", "claude-haiku-4-5", "lane-b"),
            Triple("https://api.anthropic.com", "unknown-preserved-model", "lane-a"),
        )) {
            val t = transport(base, model, lane)
            assertNoPrivate(next(t, history(capsule)))
        }
        for (finalUrl in listOf("https://relay.invalid/v1/messages", "https://api.anthropic.com/v1/messages?trace=1")) {
            val t = transport()
            t.reply(response(), finalUrl = finalUrl)
            val r = withTimeout(5000) { t.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096) }
            assertEquals(AnthropicReplay.State.UNAVAILABLE, r.anthropicReplay?.state)
        }
        val relay = transport("https://relay.invalid")
        relay.reply(response())
        assertNull(relay.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096).anthropicReplay)
    }

    @Test fun editedMissingRepairedOrAdjacentAssistantPairsDiscardWholeCapsule() = runBlocking {
        val capsule = AnthropicReplayCodec.complete(source, blocks())
        val valid = history(capsule)
        val a = valid[1]
        val u = valid[2]
        val mutations = listOf(
            listOf(valid[0], a.copy(content = "edited"), u),
            listOf(valid[0], a.copy(reasoningContent = "edited"), u),
            listOf(valid[0], a.copy(contentParts = listOf(AgentContentPart.ToolUse("tool_1", "lookup", JSONObject("""{"decimal":0}""")), AgentContentPart.Text(publicText))), u),
            listOf(valid[0], a, u.copy(contentParts = listOf(AgentContentPart.ToolResult("wrong", "lookup", "result")))),
            listOf(valid[0], a, u.copy(contentParts = listOf(AgentContentPart.ToolResult("tool_1", "other-name", "result")))),
            listOf(valid[0], a, u.copy(contentParts = listOf(AgentContentPart.ToolResult("tool_1", "lookup", com.rikkaminis.app.ui.chat.SANITIZE_PLACEHOLDER_RESULT_CONTENT)))),
            listOf(valid[0], LLMMessage(LLMMessage.Role.ASSISTANT, "adjacent"), a, u),
            valid + LLMMessage(LLMMessage.Role.USER, "later ordinary turn"),
            listOf(valid[0], a, LLMMessage(LLMMessage.Role.USER, "missing result")),
        )
        for (messages in mutations) assertNoPrivate(next(transport(), messages))
    }

    @Test fun httpErrorsAreBoundedPrivateSafeAndKeepRetryClassification() = runBlocking {
        val capsule = AnthropicReplayCodec.complete(source, blocks())
        for (stream in listOf(false, true)) for (status in listOf(400, 429, 503)) {
            val t = transport()
            t.reply("""{"type":"error","error":{"type":"invalid_request_error","message":"echo $signature","signature":"$signature","data":"$redacted"}}""", status)
            val e = try {
                withTimeout(5000) {
                    if (stream) t.provider.streamMessage(history(capsule), null, 4096, thinkingLevel = ThinkingLevel.HIGH).toList()
                    else t.provider.sendMessage(history(capsule), null, 4096, thinkingLevel = ThinkingLevel.HIGH)
                }
                null
            } catch (error: Exception) { error }
            assertNotNull(e)
            val cause = generateSequence(e as Throwable?) { it.cause }.toList()
            when (status) {
                429 -> assertTrue(cause.any { it is LLMError.RateLimited && it.retryAfterMs == 7000L })
                503 -> assertTrue(cause.any { it is LLMError.TransientError })
                else -> assertTrue(cause.any { it is LLMError.ProviderError })
            }
            assertNoPrivate(cause.joinToString() + recordedLogs())
        }
        val t = transport()
        t.reply("""{"type":"error","error":{"type":"invalid_request_error","message":"PUBLIC_ERROR_D05_CONTROL"}}""", 400)
        val e = try { t.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096); null } catch (e: Exception) { e }
        assertTrue(e.toString().contains("PUBLIC_ERROR_D05_CONTROL"))
        val oversize = transport()
        oversize.reply("x".repeat(17000) + signature, 400)
        val bounded = try { oversize.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096); null } catch (e: Exception) { e }
        assertNoPrivate(bounded.toString())
        assertTrue(bounded.toString().length < 1000)
    }

    @Test fun legacyWithoutMetadataRequestBytesRemainStableAcrossMatrix() = runBlocking {
        val bodies = mutableListOf<String>()
        for (model in listOf("claude-haiku-4-5", "claude-sonnet-4-6")) {
            for (base in listOf("https://api.anthropic.com", "https://relay.invalid")) {
                for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)) {
                    for (messages in listOf(
                        listOf(LLMMessage(LLMMessage.Role.USER, "")),
                        history(null),
                    )) {
                        val t = transport(base, model)
                        t.reply("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
                        t.provider.sendMessage(messages, "stable-system", 4096, thinkingLevel = level)
                        val body = t.requests.single()
                        assertFalse(body.contains("\"signature\""))
                        assertFalse(body.contains("redacted_thinking"))
                        bodies.add(body)
                    }
                }
            }
        }
        assertEquals(16, bodies.size)
        // The independent old/new runner captures these exact wire strings, not a
        // copied builder predicate. On-device/CI this remains an ordinary assertion.
        val output = System.getProperty("d05.legacy.matrix")
        if (output != null) java.io.File(output).writeText(bodies.joinToString("\n") + "\n")
    }

    @Test fun publicReasoningWithoutMetadataRetainsRelayCompatibility() = runBlocking {
        val t = transport("https://relay.invalid")
        t.provider.model = t.provider.model.copy(interleavedReasoningField = "reasoning_content")
        val wire = next(t, history(null))
        assertTrue("legacy unsigned reasoning must survive", wire.contains(thinking))
        assertFalse(wire.contains(signature))
        assertFalse(wire.contains(redacted))
    }

    @Test fun oversizedAndDeepNativeGroupsDeclineWithoutLosingPublicResponse() = runBlocking {
        val oversized = JsonArray(blocks().map { block ->
            if (block.jsonObject["type"]!!.jsonPrimitive.content == "redacted_thinking") {
                JsonObject(block.jsonObject + ("data" to JsonPrimitive("x".repeat(128 * 1024))))
            } else block
        })
        val deepInput = "{\"value\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"
        val deep = JsonArray(blocks().map { block ->
            if (block.jsonObject["type"]!!.jsonPrimitive.content == "tool_use") {
                JsonObject(block.jsonObject + ("input" to Json.parseToJsonElement(deepInput)))
            } else block
        })
        for (group in listOf(oversized, deep)) {
            val t = transport()
            t.reply(response(group))
            val answer = withTimeout(5000) {
                t.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096)
            }
            assertEquals(publicText, answer.text)
            assertEquals(AnthropicReplay.State.UNAVAILABLE, answer.anthropicReplay?.state)
            assertNoPrivate(next(t, history(answer.anthropicReplay)))
        }
        val stream = transport()
        val oversizedEvents = events().map {
            if (it.contains("\"content_block\":{\"type\":\"redacted_thinking\"")) {
                event("content_block_start", "\"index\":1,\"content_block\":" + oversized[1])
            } else it
        }
        val terminal = collect(stream, frame(oversizedEvents)).filterIsInstance<LLMStreamChunk.Finished>().single()
        assertEquals(AnthropicReplay.State.UNAVAILABLE, terminal.anthropicReplay?.state)
    }

    @Test fun imageToolResultsKeepPublicBytesWhilePrivateCapsuleIsDeclined() = runBlocking {
        val t = transport()
        t.provider.model = t.provider.model.copy(inputModalities = listOf("text", "image"))
        val valid = history(AnthropicReplayCodec.complete(source, blocks()))
        val user = valid.last().copy(contentParts = listOf(AgentContentPart.ToolResult(
            "tool_1", "lookup", "actual-image-result", imageData = byteArrayOf(1, 2, 3), imageMimeType = "image/png")))
        val wire = next(t, valid.dropLast(1) + user)
        assertNoPrivate(wire)
        assertTrue("tool result text survives", wire.contains("actual-image-result"))
        assertTrue("tool result image survives", wire.contains("AQID"))
        assertTrue(wire.contains("image/png"))
    }

    @Test fun errorInsideSuccessfulHttpAndInvalidJsonNeverExposeCapsule() = runBlocking {
        for (body in listOf(
            """{"type":"error","error":{"type":"invalid_request_error","message":"signature $signature"}}""",
            """{"content":[],"message":"signature $signature"}""",
            """{"content":[{"type":"thinking","signature":"$signature"}], BROKEN""",
        )) {
            val t = transport()
            t.reply(body)
            val e = try { t.provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "start")), null, 4096); null } catch (e: Exception) { e }
            assertNotNull(e)
            assertNoPrivate(e.toString() + (e?.cause?.toString() ?: ""))
        }
    }
}
