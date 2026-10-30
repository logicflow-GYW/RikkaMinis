package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FIX-FW-NONSTREAM C1 + C2] The Anthropic non-streaming arm as the CALLER sees
 * it: what a 2xx that carries a failure raises, what a body with no message at
 * all raises, and which shapes must still come back as ordinary responses.
 *
 * The pure tables live in `NonStreamTerminalSignalTest` (compilable without
 * OkHttp/Android); this class needs MockWebServer + the real provider and
 * therefore only runs in the Gradle test task. Mirrors
 * `GeminiNonStreamingWireTest`, which fixed the same class of defect for Gemini
 * in R5-E2.
 *
 * Every expected value is a hand-written literal restated from the Messages
 * protocol and the gates' status table — never derived from the code under test.
 */
class AnthropicNonStreamingWireTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun oneUserTurn() = listOf(LLMMessage(LLMMessage.Role.USER, "hi"))

    // -- C1: a 2xx is not success --

    @Test
    fun `a 200 carrying an overloaded error is a retryable transient failure`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
            ),
        )
        try {
            val r = provider.sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected TransientError, got text=${r.text} stop=${r.stopReason}")
        } catch (e: LLMError.TransientError) {
            assertTrue(
                "the server's reason must reach the user, got: ${e.message}",
                e.message.orEmpty().contains("Overloaded"),
            )
        }
    }

    @Test
    fun `a 200 carrying a rate-limit error is a RateLimited failure`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests exceeded"}}""",
            ),
        )
        try {
            val r = provider.sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected RateLimited, got text=${r.text} stop=${r.stopReason}")
        } catch (_: LLMError.RateLimited) {
            // mapped by anthropicStreamErrorStatus + mapHttpError, exactly like
            // the streaming arm's in-band error frame
        }
    }

    @Test
    fun `a 200 carrying a revoked key error is InvalidApiKey`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
            ),
        )
        try {
            val r = provider.sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected InvalidApiKey, got text=${r.text} stop=${r.stopReason}")
        } catch (_: LLMError.InvalidApiKey) {
            // as above
        }
    }

    @Test
    fun `a 200 with no content and no stop reason never becomes an empty success`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        try {
            val r = provider.sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected ProviderError, got text=${r.text} stop=${r.stopReason}")
        } catch (e: LLMError.ProviderError) {
            assertTrue(
                "the refusal must say what was missing, got: ${e.message}",
                e.message.orEmpty().contains("no message content"),
            )
        }
    }

    @Test
    fun `an empty content array without a stop reason never becomes an empty success`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"content":[],"usage":{"input_tokens":1,"output_tokens":0}}"""),
        )
        try {
            val r = provider.sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected ProviderError, got text=${r.text} stop=${r.stopReason}")
        } catch (_: LLMError.ProviderError) {
            // as above
        }
    }

    // -- shapes that MUST stay successes --

    @Test
    fun `a normal message still parses`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}""",
            ),
        )
        val r = provider.sendMessage(oneUserTurn(), null, 100)
        assertEquals("ok", r.text)
        assertEquals("end_turn", r.stopReason)
        assertEquals(3, r.usage?.inputTokens)
    }

    @Test
    fun `a pure tool_use turn is still a response, not a failure`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a"}}],""" +
                    """"stop_reason":"tool_use"}""",
            ),
        )
        val r = provider.sendMessage(oneUserTurn(), null, 100)
        assertEquals("", r.text)
        assertEquals("tool_use", r.stopReason)
    }

    // -- C2: the ceiling is a SIGNAL, not an exception --

    @Test
    fun `a max_tokens stop is carried through instead of thrown`() = runBlocking {
        // Deliberate: retrying with the same max_tokens hits the same ceiling,
        // so throwing here would only produce a retry loop. The visible signal
        // is the stop reason (this assert) plus the C2 log line.
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"half a senten"}],"stop_reason":"max_tokens"}""",
            ),
        )
        val r = provider.sendMessage(oneUserTurn(), null, 100)
        assertEquals("half a senten", r.text)
        assertEquals("max_tokens", r.stopReason)
    }
}
