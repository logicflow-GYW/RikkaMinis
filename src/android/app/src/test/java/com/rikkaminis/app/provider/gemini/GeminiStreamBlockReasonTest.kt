package com.rikkaminis.app.provider.gemini

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [Rhub-Protocol-1105 F-04] Gemini's STREAMING arm must not lose a safety
 * refusal to the "silent empty completion" bucket.
 *
 * The defect (read off the real code at `00dc003a`):
 *  - the NON-streaming arm calls `geminiGenerateContentVerdict(json)`, which
 *    reads `promptFeedback.blockReason` and raises
 *    `LLMError.ProviderError` naming the reason;
 *  - the STREAMING arm (`rawStreamMessage`) only inspected
 *    `json.optJSONObject("error")` per frame. A frame carrying
 *    `promptFeedback.blockReason` and NO candidates fell through the frame
 *    loop, the stream reached EOF, and the empty EOF became
 *    `Finished(null, truncated = true)` — which `failOnSilentEmptyCompletion`
 *    rewrites into `LLMError.TransientError("Server returned an empty
 *    response …")`.
 *
 * Two user-visible consequences, both fixed by the same check:
 *  1. a refusal was reported as a TRANSIENT network failure, so the app
 *     auto-retried a request the server had already refused (it is refused
 *     again), and the `blockReason=SAFETY` diagnostic was lost entirely;
 *  2. when the refusal arrived AFTER some text had already streamed
 *     (`sawAnyOutput` true), `failOnSilentEmptyCompletion` did not fire at
 *     all and the turn became `truncated = true` — which triggers the
 *     truncation auto-continuation, whose follow-up request is refused too.
 *
 * Expected values are hand-written literals (evidence-discipline): the block
 * reason string, the error class, and the wording are re-stated from the
 * Gemini API contract — never read back out of the code under test.
 */
class GeminiStreamBlockReasonTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            apiKey = "test-key",
            model = LLMModel.gemini25Flash,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun oneUserTurn() = listOf(LLMMessage(LLMMessage.Role.USER, "hi"))

    private fun sse(vararg frames: String): String = buildString {
        for (f in frames) {
            appendLine("data: $f")
            appendLine()
        }
    }

    /** The chunks emitted before the stream died, plus the throwable that killed it. */
    private data class Outcome(
        val chunks: List<LLMStreamChunk>,
        val thrown: Throwable?,
    )

    private fun collect(): Outcome {
        val chunks = mutableListOf<LLMStreamChunk>()
        var thrown: Throwable? = null
        try {
            runBlocking {
                provider.streamMessage(oneUserTurn(), null, 1024).toList(chunks)
            }
        } catch (e: Throwable) {
            thrown = e
        }
        return Outcome(chunks, thrown)
    }

    /**
     * Mirrors the app's own unwrap (`ChatViewModel.unwrapFlowException`): the
     * provider cancels the flow with the failure as the cause, so the LLMError
     * sits in the cause chain, not at the top.
     */
    private fun Throwable.findLLMError(): LLMError? {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is LLMError) return cause
            cause = cause.cause
        }
        return null
    }

    // -- F-04 arm 1: the refusal frame ------------------------------------

    @Test
    fun `a blocked stream frame is a provider error naming the reason`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    sse(
                        """{"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":12}}""",
                    ),
                )
                .setHeader("Content-Type", "text/event-stream"),
        )

        val outcome = collect()

        val error = outcome.thrown?.findLLMError()
        assertNotNull(
            "the refusal must surface as an LLMError, got: ${outcome.thrown}",
            error,
        )
        val failure = error!!
        assertTrue(
            "the refusal must be a ProviderError (NOT a TransientError), got: $failure",
            failure is LLMError.ProviderError,
        )
        assertFalse(
            "a refusal must never be reported as a transient network failure, got: $failure",
            failure is LLMError.TransientError,
        )
        assertTrue(
            "the block reason must reach the user, got: ${failure.message}",
            failure.message.orEmpty().contains("SAFETY"),
        )
        assertFalse(
            "the refusal must not be reported as an empty upstream response, got: ${failure.message}",
            failure.message.orEmpty().contains("empty response"),
        )
    }

    // -- F-04 arm 2: the refusal arrives AFTER text has streamed -----------

    @Test
    fun `a mid-stream refusal is a provider error and not a truncated turn`() {
        server.enqueue(
            MockResponse()
                .setBody(
                    sse(
                        """{"candidates":[{"content":{"parts":[{"text":"partial answer"}]}}]}""",
                        """{"promptFeedback":{"blockReason":"SAFETY"}}""",
                    ),
                )
                .setHeader("Content-Type", "text/event-stream"),
        )

        val outcome = collect()

        val error = outcome.thrown?.findLLMError()
        assertNotNull(
            "the mid-stream refusal must surface as an LLMError, got: ${outcome.thrown}",
            error,
        )
        val failure = error!!
        assertTrue(
            "the mid-stream refusal must be a ProviderError, got: $failure",
            failure is LLMError.ProviderError,
        )
        assertTrue(
            "the block reason must reach the user, got: ${failure.message}",
            failure.message.orEmpty().contains("SAFETY"),
        )
        val truncated = outcome.chunks
            .filterIsInstance<LLMStreamChunk.Finished>()
            .any { it.truncated }
        assertFalse(
            "a refusal must not be reported as a truncated turn (that would trigger a " +
                "continuation request the server refuses as well), chunks=${outcome.chunks}",
            truncated,
        )
    }
}
