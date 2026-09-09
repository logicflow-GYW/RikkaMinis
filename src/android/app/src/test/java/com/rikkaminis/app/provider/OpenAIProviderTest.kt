package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAIProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Builds an SSE stream body from raw JSON events, terminated by [DONE].
     *
     * sendMessage has always streamed internally ("some providers reject
     * stream=false outright"), so every mock response on this path must be
     * SSE-shaped: the stream parser skips any line that does not start with
     * `data:`, and a plain JSON body would surface as an empty-stream
     * TransientError instead of being parsed.
     */
    private fun sseBody(vararg events: String): String = buildString {
        for (event in events) {
            append("data: $event")
            append("\n\n")
        }
        append("data: [DONE]")
        append("\n\n")
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses ChatCompletions response`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"Hello from GPT!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from GPT!", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(5, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage parses cached tokens from prompt_tokens_details`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)

        // inputTokens is fresh-only (prompt_tokens minus cached), matching the
        // Anthropic convention — see parseChatCompletionsUsage. The full prompt
        // stays available as latestContextTokens.
        assertEquals(50, response.usage?.inputTokens)
        assertEquals(10, response.usage?.outputTokens)
        assertEquals(50, response.usage?.cacheReadInputTokens)
        assertNull(response.usage?.cacheCreationInputTokens)
        assertEquals(100, response.usage?.latestContextTokens)
    }

    @Test
    fun `sendMessage returns null cacheReadInputTokens when zero`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":0}}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertNull(response.usage?.cacheReadInputTokens)
    }

    @Test
    fun `sendMessage treats empty-choices stream as transient failure`() = runBlocking {
        // A 200 stream that ends with no content and no finish_reason is
        // treated as a dropped/upstream failure (failOnSilentEmptyCompletion) —
        // real OpenAI streams always carry finish_reason before [DONE]. This
        // locks in the current always-streaming contract; the old non-streaming
        // "return empty text" behavior no longer exists.
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        } catch (e: LLMError.TransientError) {
            return@runBlocking
        }
        throw AssertionError("Expected TransientError for empty-choices stream")
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes Bearer auth header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("/chat/completions"))
    }

    @Test
    fun `sendMessage includes system prompt as system message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")),
            "You are helpful", 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // System message should be first
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are helpful", messages.getJSONObject(0).getString("content"))
        // User message follows
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sendMessage omits system message when null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `sendMessage includes temperature when set`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.8)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.8, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `sendMessage uses max_completion_tokens for OpenAI`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 2048)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(2048, body.getInt("max_completion_tokens"))
        assertTrue(!body.has("max_tokens"))
    }

    @Test
    fun `sendMessage issues a streaming request internally`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        // sendMessage has always streamed internally since the "some providers
        // reject stream=false outright" change: the request must say
        // stream=true and carry stream_options.include_usage (OpenAI only).
        assertEquals(true, body.getBoolean("stream"))
        assertTrue(body.has("stream_options"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE events with DONE`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(streamBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        ).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", texts[0].text)
        assertEquals(" world", texts[1].text)

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(5, usageChunks[0].usage.inputTokens)
        assertEquals(2, usageChunks[0].usage.outputTokens)

        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage includes stream_options with include_usage`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        val streamOptions = body.getJSONObject("stream_options")
        assertTrue(streamOptions.getBoolean("include_usage"))
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 1.0).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1.0, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `streamMessage parses cached tokens in usage`() = runBlocking {
        val streamBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(50, usageChunks[0].usage.cacheReadInputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.RateLimited::class)
    fun `sendMessage throws RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test
    fun `sendMessage parses error body for ProviderError`() = runBlocking {
        val errorBody = """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message!!.contains("400"))
            assertTrue(e.message!!.contains("The model does not exist"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is OpenAI`() {
        assertEquals("OpenAI", provider.name)
    }

    @Test
    fun `provider model can be changed`() {
        provider.model = LLMModel.gpt4o
        assertEquals(LLMModel.gpt4o, provider.model)
    }

    // -- Qwen thinking-off (T-qwen-thinking-off-omission) --

    @Test
    fun `qwen thinking OFF emits enable_thinking false`() = runBlocking {
        provider.model = LLMModel("qwen3.8-max", "Qwen3.8 Max", "OpenAI", supportsReasoning = true)
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100,
            thinkingLevel = ThinkingLevel.OFF,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(false, body.getBoolean("enable_thinking"))
        assertTrue(!body.has("reasoning_effort"))
        assertTrue(!body.has("thinking"))
    }

    @Test
    fun `qwen thinking OFF does not affect non-qwen models`() = runBlocking {
        provider.model = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI")
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100,
            thinkingLevel = ThinkingLevel.OFF,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("enable_thinking"))
    }
}
