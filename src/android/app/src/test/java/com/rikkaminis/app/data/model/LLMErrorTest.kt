package com.rikkaminis.app.data.model

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the group-fallback classification contract.
 *
 * [T-fallback-network-errors] ChatViewModel's shouldFallback uses
 * LLMError.isFallbackable directly, consulted ONLY after the bounded same-
 * provider retry loop is exhausted. So the contract is:
 *
 *  - rate limits (429), invalid keys (401), provider errors (4xx/5xx):
 *    fall back to the next group member immediately (retrying same model
 *    is pointless)
 *  - network / transient errors (stream reset, timeouts, DNS, proxy drop):
 *    retry in place first (isRetryable), and FALL BACK once retries are
 *    exhausted — "this endpoint can't help right now" is exactly the group
 *    fallback case
 *  - decoding, cancellation, unknown: surface to the user / respect intent
 *
 * [T-error-no-permanent-scars] userMessage must NEVER contain raw error codes
 * ("stream was reset: CANCEL", HTTP status numbers) — that text only ever
 * appears in the collapsed technical-details disclosure, never as the
 * primary banner text.
 */
class LLMErrorTest {

    @Test
    fun `fallbackable - rate limit 429 triggers group fallback`() {
        assertTrue(LLMError.RateLimited().isFallbackable)
    }

    @Test
    fun `fallbackable - invalid API key 401 triggers group fallback`() {
        assertTrue(LLMError.InvalidApiKey("401 Unauthorized").isFallbackable)
    }

    @Test
    fun `fallbackable - provider 5xx triggers group fallback`() {
        assertTrue(LLMError.ProviderError("HTTP 502 Bad Gateway").isFallbackable)
    }

    @Test
    fun `fallbackable - provider 4xx triggers group fallback (per-provider quota, bad request)`() {
        assertTrue(LLMError.ProviderError("403 quota exceeded").isFallbackable)
        assertTrue(LLMError.ProviderError("400 bad request").isFallbackable)
    }

    @Test
    fun `fallbackable - network errors fall back once retries are exhausted`() {
        // The retry loop (AUTO_RETRY_DELAYS_SEC) gets first crack on the same
        // provider; isFallbackable is only consulted after that, so including
        // NetworkError here stops the "retried 3x then hard-stopped" dead end
        // without ever skipping the retry.
        assertTrue(LLMError.NetworkError(IOException("socket closed")).isFallbackable)
        assertTrue(LLMError.NetworkError(IOException("stream was reset: CANCEL")).isFallbackable)
    }

    @Test
    fun `fallbackable - transient errors fall back once retries are exhausted`() {
        assertTrue(LLMError.TransientError("temporary").isFallbackable)
    }

    @Test
    fun `retryable - network and transient errors retry on the same provider`() {
        assertTrue(LLMError.NetworkError(IOException("timeout")).isRetryable)
        assertTrue(LLMError.TransientError("temporary").isRetryable)
        assertFalse(LLMError.RateLimited().isRetryable)
        assertFalse(LLMError.InvalidApiKey().isRetryable)
        assertFalse(LLMError.ProviderError("500").isRetryable)
    }

    @Test
    fun `fallbackable - decoding, cancellation and unknown surface to the user`() {
        assertFalse(LLMError.DecodingError(RuntimeException("json")).isFallbackable)
        assertFalse(LLMError.Cancelled().isFallbackable)
        assertFalse(LLMError.Unknown(null).isFallbackable)
    }

    @Test
    fun `fallbackReason - every type has a user-facing reason`() {
        assertEquals("Rate limited", LLMError.RateLimited().fallbackReason)
        assertEquals("Invalid API key", LLMError.InvalidApiKey().fallbackReason)
        assertEquals("Provider error", LLMError.ProviderError("x").fallbackReason)
        assertEquals("Network error", LLMError.NetworkError(IOException("x")).fallbackReason)
        assertEquals("Transient error", LLMError.TransientError("x").fallbackReason)
        assertEquals("Decoding error", LLMError.DecodingError(RuntimeException("x")).fallbackReason)
        assertEquals("Cancelled", LLMError.Cancelled().fallbackReason)
        assertEquals("Unknown error", LLMError.Unknown(null).fallbackReason)
    }

    @Test
    fun `userMessage - human summary, never a raw error code`() {
        // The exact string the user complained about must never surface
        // as the primary banner text.
        val reset = LLMError.NetworkError(IOException("stream was reset: CANCEL"))
        assertEquals("Connection failed", reset.userMessage)
        assertFalse(reset.userMessage.contains("stream was reset"))
        assertFalse(reset.userMessage.contains("CANCEL"))

        assertEquals("Rate limited — try again in a moment", LLMError.RateLimited().userMessage)
        assertEquals("API key is invalid or expired", LLMError.InvalidApiKey("401").userMessage)
        assertEquals("Provider returned an error", LLMError.ProviderError("HTTP 502").userMessage)
        assertEquals("Service temporarily unavailable", LLMError.TransientError("x").userMessage)
        assertEquals("Unexpected response from provider", LLMError.DecodingError(RuntimeException("x")).userMessage)
        assertEquals("Request was cancelled", LLMError.Cancelled().userMessage)
        assertEquals("Something went wrong", LLMError.Unknown(null).userMessage)
    }

    @Test
    fun `userMessage - provider detail with status numbers stays out of the summary`() {
        val err = LLMError.ProviderError("HTTP 502 Bad Gateway")
        assertEquals("Provider returned an error", err.userMessage)
        assertFalse(err.userMessage.contains("502"))
    }

    @Test
    fun `fallbackExhaustedError - carries human summary separate from raw detail`() {
        val e = FallbackExhaustedError(
            summary = "Connection failed — tried 3 models, none available.",
            detail = "⚠️ deepseek-v4-flash: Network error: stream was reset: CANCEL\nHTTP 502",
        )
        assertEquals("Connection failed — tried 3 models, none available.", e.summary)
        assertTrue(e.detail.contains("stream was reset: CANCEL"))
        // Exception.message stays human — UI fallbacks that print e.message
        // must not leak the raw trail.
        assertEquals("Connection failed — tried 3 models, none available.", e.message)
    }

    // ─── Retry-After parsing (RFC 7231 §7.1.3) ─────────────────────────────

    @Test
    fun `parseRetryAfter - delay seconds form`() {
        assertEquals(120_000L, parseRetryAfterMs("120", nowMs = 1000L))
        assertEquals(0L, parseRetryAfterMs("0", nowMs = 1000L))
    }

    @Test
    fun `parseRetryAfter - http date form`() {
        // "Mon, 01 Jan 2024 00:00:10 GMT" = 10s after "Mon, 01 Jan 2024 00:00:00 GMT"
        // epoch for 2024-01-01 00:00:00 GMT = 1704067200 seconds = 1704067200000 ms
        assertEquals(10_000L, parseRetryAfterMs("Mon, 01 Jan 2024 00:00:10 GMT", nowMs = 1704067200000L))
    }

    @Test
    fun `parseRetryAfter - date already in the past clamps to zero`() {
        assertEquals(0L, parseRetryAfterMs("Mon, 01 Jan 2024 00:00:00 GMT", nowMs = 1704067200000L + 1000L))
    }

    @Test
    fun `parseRetryAfter - garbage or blank returns null`() {
        assertNull(parseRetryAfterMs(null, 1000L))
        assertNull(parseRetryAfterMs("", 1000L))
        assertNull(parseRetryAfterMs("   ", 1000L))
        assertNull(parseRetryAfterMs("not-a-date", 1000L))
        assertNull(parseRetryAfterMs("12abc", 1000L))
    }
}
