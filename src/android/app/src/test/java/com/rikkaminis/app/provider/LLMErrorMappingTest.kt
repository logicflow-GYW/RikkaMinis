package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [refactor/reuse-basics-1128] The three providers' `mapError` bodies were
 * byte-identical; they now call [llmErrorOf]. This pins the exact behaviour
 * they had:
 *
 *  - an already-classified [LLMError] is handed back as the SAME instance
 *    (the stream path stores it in `cancel(...)` and readers compare identity);
 *  - an [IOException] becomes a NEW [LLMError.NetworkError] wrapping the
 *    original throwable (so `cause` is the live object, not a copy);
 *  - anything else becomes [LLMError.Unknown] with the original as cause.
 */
class LLMErrorMappingTest {

    private val ioCause = IOException("connection reset")

    @Test
    fun `an LLMError is passed through as the same instance`() {
        val original = LLMError.InvalidApiKey("revoked")
        assertSame(original, llmErrorOf(original))
    }

    @Test
    fun `every LLMError subclass survives unchanged`() {
        val samples = listOf(
            LLMError.InvalidApiKey(),
            LLMError.NetworkError(IOException("x")),
            LLMError.ProviderError("p"),
            LLMError.DecodingError(IOException("d")),
            LLMError.RateLimited(1_000L),
            LLMError.TransientError("t"),
            LLMError.Cancelled(),
            LLMError.Unknown(null),
        )
        for (sample in samples) {
            assertSame(sample::class.java.name, sample, llmErrorOf(sample))
        }
    }

    @Test
    fun `an IOException becomes a NetworkError that wraps the original`() {
        val mapped = llmErrorOf(ioCause)
        assertTrue(mapped is LLMError.NetworkError)
        assertSame(ioCause, mapped.cause)
        assertEquals("Network error: connection reset", mapped.message)
        assertTrue(mapped.isNetworkError)
        assertTrue(mapped.isRetryable)
        assertTrue(mapped.isFallbackable)
    }

    @Test
    fun `an IOException subclass is still a NetworkError`() {
        val timeout = SocketTimeoutException("read timed out")
        val mapped = llmErrorOf(timeout)
        assertTrue(mapped is LLMError.NetworkError)
        assertSame(timeout, mapped.cause)
    }

    @Test
    fun `any other throwable becomes Unknown with the original cause`() {
        val boom = IllegalStateException("boom")
        val mapped = llmErrorOf(boom)
        assertTrue(mapped is LLMError.Unknown)
        assertSame(boom, mapped.cause)
        assertEquals("Unknown error: boom", mapped.message)
        assertFalse(mapped.isNetworkError)
        assertFalse(mapped.isRetryable)
        assertFalse(mapped.isFallbackable)
    }

    @Test
    fun `a throwable without a message keeps the Unknown prefix`() {
        val mapped = llmErrorOf(RuntimeException())
        assertTrue(mapped is LLMError.Unknown)
        assertEquals("Unknown error: null", mapped.message)
    }
}
