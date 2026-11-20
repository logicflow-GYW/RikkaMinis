package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import com.rikkaminis.app.sandbox.offload.ChatStreamJsonl
import com.rikkaminis.app.ui.chat.ChatStreamErrorPolicy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Replay the error tokens/messages from the 2026-11-20 worker log, not a guessed dialect. */
class OpenAIResponsesRelayRecoveryTest {
    @Test fun `observed relay error codes have transient HTTP semantics`() {
        for (code in listOf("upstream_stream_read_error", "upstream_error", "bps_stream_error")) {
            assertEquals(code, 502, openAiStreamErrorStatus(code, ""))
            assertEquals(code, 502, openAiStreamErrorStatus("", code))
        }
    }

    @Test fun `only exact observed transport messages recover missing codes`() {
        for (message in listOf(
            "Unable to forward upstream stream",
            "Upstream response stream was interrupted",
            "Upstream HTTP/2 stream failed",
            "Upstream service temporarily unavailable",
            "Upstream request failed, please try again later",
        )) {
            assertEquals(message, 502, openAiStreamErrorStatus("unknown", "", message))
            assertEquals(message, 502, openAiStreamErrorStatus("", "", "  $message  "))
        }
    }

    @Test fun `permanent metadata and unknown errors never gain blind retries`() {
        for ((code, expected) in listOf(
            "400" to 400, "401" to 401, "403" to 403, "404" to 404, "429" to 429,
            "invalid_api_key" to 401, "rate_limit_exceeded" to 429,
            "model_not_found" to 404, "context_length_exceeded" to 400,
            "cyber_policy" to 0, "upstream_auth_error" to 0, "new_unknown_code" to 0,
        )) assertEquals(code, expected, openAiStreamErrorStatus(code, "", "Unable to forward upstream stream"))
        assertEquals(401, openAiStreamErrorStatus("unknown", "authentication_error", "Unable to forward upstream stream"))
        assertEquals(401, openAiStreamErrorStatus("upstream_error", "authentication_error", "Unable to forward upstream stream"))
        assertEquals(429, openAiStreamErrorStatus("bps_stream_error", "rate_limit_exceeded", "Unable to forward upstream stream"))
        assertEquals(0, openAiStreamErrorStatus("", "", "please retry later"))
        assertEquals(0, openAiStreamErrorStatus("", "", "user quoted: Unable to forward upstream stream"))
    }

    @Test fun `response failed stays retryable across the real worker error codec`() = runBlocking {
        for (code in listOf("upstream_stream_read_error", "upstream_error", "bps_stream_error", "unknown")) {
            val result = failure(code, "Unable to forward upstream stream")
            assertTrue("code=$code cause=${result.error}", result.error != null)
            assertEquals("transient", ChatStreamErrorPolicyKind.of(result.error!!))
            val line = ChatStreamJsonl.errorLine("Stream error", ChatStreamErrorPolicyKind.of(result.error))
            assertEquals(ChatStreamErrorPolicy.Action.AUTO_RETRY, ChatStreamErrorPolicy.classify(ChatStreamJsonl.errorKind(line)))
            assertEquals(listOf("Partial answer"), result.chunks.filterIsInstance<LLMStreamChunk.Text>().map { it.text })
            assertTrue("failed stream must not emit success", result.chunks.none { it is LLMStreamChunk.Finished })
        }
    }

    @Test fun `top level Responses errors share the relay mapping`() = runBlocking {
        val result = failure("", "Unable to forward upstream stream", topLevel = true)
        assertEquals("transient", ChatStreamErrorPolicyKind.of(result.error!!))
    }

    @Test fun `an explicit invalid key is not retried even with transport wording`() = runBlocking {
        val result = failure("invalid_api_key", "Unable to forward upstream stream")
        assertEquals("invalid_key", ChatStreamErrorPolicyKind.of(result.error!!))
    }

    @Test fun `policy failures stay provider errors`() = runBlocking {
        val result = failure("cyber_policy", "This request is not allowed")
        assertEquals("provider", ChatStreamErrorPolicyKind.of(result.error!!))
    }

    private data class Result(val chunks: List<LLMStreamChunk>, val error: Throwable?)

    private suspend fun failure(code: String, message: String, topLevel: Boolean = false): Result {
        MockWebServer().use { server ->
            server.start()
            val error = JSONObject().put("code", code).put("message", message)
            val event = if (topLevel) JSONObject().put("type", "error").put("error", error)
                else JSONObject().put("type", "response.failed").put("response", JSONObject().put("status", "failed").put("error", error))
            val body = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial answer\"}\n\n" +
                "data: $event\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                .throttleBody(80, 20, java.util.concurrent.TimeUnit.MILLISECONDS))
            val provider = OpenAIProvider(apiKey = "test-key", basePath = server.url("/v1").toString(), useResponsesAPI = true)
            val chunks = mutableListOf<LLMStreamChunk>()
            var caught: Throwable? = null
            try {
                provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 128).collect { chunks.add(it) }
            } catch (e: Throwable) { caught = e }
            assertEquals("/v1/responses", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
            return Result(chunks, caught)
        }
    }
}
