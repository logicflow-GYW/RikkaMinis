package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.provider.anthropic.AnthropicProvider
import com.rikkaminis.app.provider.gemini.GeminiProvider
import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import com.rikkaminis.app.sandbox.offload.ChatStreamJsonl
import com.rikkaminis.app.ui.chat.ChatStreamErrorPolicy
import com.rikkaminis.app.ui.chat.ContentFilterFinishPolicy
import com.rikkaminis.app.ui.chat.TruncatedToolCallPolicy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Fixed, inert protocol fixtures. No tool executor, real credentials or upstream. */
class ProviderTerminalCompatibilityTest {
    private val messages = listOf(LLMMessage(LLMMessage.Role.USER, "fixture"))
    private val tool = AgentToolDefinition("fixture_echo", "inert fixture", mapOf("value" to AgentToolParam("string", "value")))
    private fun model(id: String) = LLMModel(id, "fixture", "anthropic")
    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }
    private fun geminiBody(reason: String, text: String = "") = JSONObject().put("candidates", org.json.JSONArray().put(
        JSONObject().put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))
            .put("finishReason", reason),
    )).toString()

    internal fun streamGemini(reason: String, text: String = ""): Pair<List<LLMStreamChunk>, Throwable?> = runBlocking {
        val server = MockWebServer(); server.start()
        val chunks = mutableListOf<LLMStreamChunk>()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(geminiBody(reason, text))))
            val provider = GeminiProvider("fixture-key", basePath = server.url("/").toString().trimEnd('/'))
            var failure: Throwable? = null
            try { provider.streamMessage(messages, null, 2048, tools = if (reason == "UNEXPECTED_TOOL_CALL") emptyList() else listOf(tool)).collect { chunks.add(it) } }
            catch (e: Exception) { failure = e }
            chunks to failure
        } finally { server.shutdown() }
    }

    @Test fun `Gemini declared function failures cross worker JSONL as permanent failures not Finished`() {
        for (reason in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL")) {
            for (text in listOf("", "Recoverable partial text")) {
                val (chunks, failure) = streamGemini(reason, text)
                assertNotNull("$reason must fail", failure)
                assertTrue("no fabricated successful finish", chunks.none { it is LLMStreamChunk.Finished })
                assertEquals(text, chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
                val kind = ChatStreamErrorPolicyKind.of(failure!!)
                assertEquals("provider", kind)
                val line = ChatStreamJsonl.errorLine(failure.message.orEmpty(), kind)
                assertTrue(ChatStreamJsonl.isError(line))
                assertFalse(ChatStreamJsonl.isDone(line))
                val action = ChatStreamErrorPolicy.decideStreamFailure(chunks.isNotEmpty(), ChatStreamJsonl.errorKind(line))
                assertFalse(action.isTransient)
                assertTrue(action.fallbackNow)
            }
        }
    }

    @Test fun `Gemini nonstream shares the same explicit failure parser`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (reason in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL")) {
                server.enqueue(MockResponse().setBody(geminiBody(reason, "partial")))
                try {
                    GeminiProvider("fixture-key", basePath = server.url("/").toString().trimEnd('/')).sendMessage(messages, null, 2048)
                    fail("must not return successful LLMResponse for $reason")
                } catch (e: LLMError.ProviderError) { assertTrue(e.detail.contains(reason)) }
            }
        } finally { server.shutdown() }
    }

    @Test fun `Gemini STOP output ceiling safety and unknown reasons retain their contracts`() {
        for ((reason, expected) in listOf("STOP" to "end_turn", "MAX_TOKENS" to "max_tokens", "SAFETY" to "safety", "TOO_MANY_TOOL_CALLS" to "too_many_tool_calls", "FUTURE_MARKER" to "future_marker")) {
            val (chunks, failure) = streamGemini(reason)
            assertNull(failure)
            assertEquals(expected, chunks.filterIsInstance<LLMStreamChunk.Finished>().single().stopReason)
            assertEquals(reason == "MAX_TOKENS", TruncatedToolCallPolicy.isTruncatedFinish(expected))
            assertEquals(reason == "SAFETY", ContentFilterFinishPolicy.isBlockedFinish(expected))
        }
    }

    internal fun anthropicToolTurn(reason: String?, closeBlock: Boolean): List<LLMStreamChunk> = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            // Syntactically incomplete data, not a filesystem/command payload.
            val delta = JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", "{\"value\":\"fixture"))
            val events = mutableListOf(
                """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"fixture-id","name":"fixture_echo","input":{}}}""",
                delta.toString(),
            )
            if (closeBlock) events.add("""{"type":"content_block_stop","index":0}""")
            events.add(JSONObject().put("type", "message_delta").put("delta", JSONObject().putOpt("stop_reason", reason)).toString())
            // A trailing usage-only delta must not erase a declared reason.
            events.add("""{"type":"message_delta","delta":{},"usage":{"output_tokens":2}}""")
            events.add("""{"type":"message_stop"}""")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(*events.toTypedArray())))
            val chunks = mutableListOf<LLMStreamChunk>()
            AnthropicProvider("fixture-key", model("claude-haiku-4-5-20251001"), server.url("/").toString().trimEnd('/'))
                .streamMessage(messages, null, 2048, tools = listOf(tool)).collect { chunks.add(it) }
            chunks
        } finally { server.shutdown() }
    }

    @Test fun `Anthropic context and output truncation refuse inert calls after JSONL without dropping identity`() {
        for (reason in listOf("model_context_window_exceeded", "max_tokens")) {
            for (closed in listOf(true, false)) {
                val chunks = anthropicToolTurn(reason, closed).map { ChatStreamJsonl.decode(ChatStreamJsonl.encode(it))!! }
                val call = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single()
                val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().last()
                assertEquals("fixture-id", call.id)
                assertEquals("fixture_echo", call.name)
                assertEquals("{\"value\":\"fixture", chunks.filterIsInstance<LLMStreamChunk.ToolInputDelta>().last().accumulated)
                assertEquals(reason, finish.stopReason)
                assertNotNull(TruncatedToolCallPolicy.rejectionReason(call.name, finish.stopReason, finish.truncated))
                assertEquals("context exhaustion must not become an output wall", reason == "max_tokens", TruncatedToolCallPolicy.isTruncatedFinish(finish.stopReason))
            }
        }
    }

    @Test fun `Anthropic declared end with an unclosed tool is still incomplete`() {
        val chunks = anthropicToolTurn("tool_use", false)
        val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().last()
        assertTrue(finish.truncated)
        assertNotNull(TruncatedToolCallPolicy.rejectionReason("fixture_echo", finish.stopReason, finish.truncated))
    }

    @Test fun `Anthropic missing reason delta cannot hide an incomplete tool`() {
        val chunks = anthropicToolTurn(null, false)
        val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().last()
        assertTrue(finish.truncated)
    }

    @Test fun `context exhaustion preserves visible text and does not request EOF continuation`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse(
                """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}""",
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Recoverable partial text"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"model_context_window_exceeded"}}""",
                """{"type":"message_stop"}""",
            )))
            val chunks = mutableListOf<LLMStreamChunk>()
            AnthropicProvider("fixture-key", model("claude-haiku-4-5-20251001"), server.url("/").toString().trimEnd('/'))
                .streamMessage(messages, null, 2048).collect { chunks.add(it) }
            assertEquals("Recoverable partial text", chunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
            val finish = chunks.filterIsInstance<LLMStreamChunk.Finished>().single()
            assertEquals("model_context_window_exceeded", finish.stopReason)
            assertFalse(finish.truncated)
            assertFalse(TruncatedToolCallPolicy.isTruncatedFinish(finish.stopReason))
        } finally { server.shutdown() }
    }
}
