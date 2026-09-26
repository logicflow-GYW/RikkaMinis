package com.rikkaminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the quota-exhaustion classifier (T-multi-api-key).
 *
 * The classifier exists because providers disagree on how to report "this key
 * is spent", and every shape below has been observed in the wild. The
 * assertions pin the two decisions that keep the routing layer correct:
 *
 *  1. A spent credential must NOT classify as a rate limit (waiting cannot
 *     clear it) or a generic server error (5-minute circuit breaker would
 *     retry the same dead key forever).
 *  2. A per-minute rate limit must NOT classify as exhaustion — parking a
 *     key that would have recovered is the false positive this test guards.
 */
class QuotaExhaustionClassificationTest {

    // ── status-based ────────────────────────────────────────────────────────

    @Test
    fun http402_isExhaustion() {
        assertTrue(isQuotaExhaustedResponse(402, "{}"))
        assertTrue(isQuotaExhaustedResponse(402, "Payment Required"))
    }

    // ── body-marker based (any status) ─────────────────────────────────────

    @Test
    fun openAInsufficientQuota_isExhaustion() {
        // OpenAI's documented out-of-credit shape: 429 + insufficient_quota.
        assertTrue(
            isQuotaExhaustedResponse(
                429,
                """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}""",
            ),
        )
    }

    @Test
    fun deepSeek402Body_isExhaustion() {
        assertTrue(isQuotaExhaustedResponse(402, "Insufficient Balance"))
    }

    @Test
    fun relay403BalanceMessage_isExhaustion() {
        assertTrue(isQuotaExhaustedResponse(403, "余额不足"))
    }

    @Test
    fun anthropicCreditBalanceTooLow_isExhaustion() {
        // Anthropic's own credit-exhaustion shape: 400 invalid_request_error.
        assertTrue(
            isQuotaExhaustedResponse(
                400,
                """{"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is too low"}}""",
            ),
        )
    }

    @Test
    fun billingHardLimit_isExhaustion() {
        assertTrue(isQuotaExhaustedResponse(429, "billing_hard_limit_reached"))
    }

    @Test
    fun quotaMarkersAreCaseInsensitive() {
        assertTrue(isQuotaExhaustedResponse(429, "INSUFFICIENT_QUOTA"))
        assertTrue(isQuotaExhaustedResponse(429, "Insufficient_Quota"))
    }

    // ── negative cases (the false-positive guard) ──────────────────────────

    @Test
    fun plainRateLimit_isNotExhaustion() {
        // Gemini reuses 429 RESOURCE_EXHAUSTED for per-minute rate limits —
        // a bare status must keep falling through to the RateLimited rung.
        assertFalse(isQuotaExhaustedResponse(429, "RESOURCE_EXHAUSTED"))
        assertFalse(
            isQuotaExhaustedResponse(
                429,
                """{"error":{"code":429,"message":"Rate limit reached for requests"}}""",
            ),
        )
    }

    @Test
    fun authFailure_isNotExhaustion() {
        assertFalse(
            isQuotaExhaustedResponse(
                401,
                """{"error":{"message":"Incorrect API key provided"}}""",
            ),
        )
    }

    @Test
    fun serverError_isNotExhaustion() {
        assertFalse(isQuotaExhaustedResponse(500, "Internal Server Error"))
        assertFalse(isQuotaExhaustedResponse(503, "Service Unavailable"))
    }

    @Test
    fun emptyBody_isNotExhaustion() {
        assertFalse(isQuotaExhaustedResponse(400, ""))
        assertFalse(isQuotaExhaustedResponse(200, "{}"))
    }

    // ── the LLMError taxonomy ───────────────────────────────────────────────

    @Test
    fun quotaExhaustedIsFallbackable() {
        val e = LLMError.QuotaExhausted("Insufficient Balance")
        assertTrue("spent key must fall back to the next member", e.isFallbackable)
        assertTrue("spent key is credential-scoped", e.isKeyRotationEligible)
    }

    @Test
    fun rateLimitedIsCredentialScoped() {
        val e = LLMError.RateLimited(retryAfterMs = 1_000)
        assertTrue(e.isKeyRotationEligible)
    }

    @Test
    fun serverErrorIsNotCredentialScoped() {
        assertFalse(
            "a 5xx says nothing about this key",
            LLMError.ProviderError("500").isKeyRotationEligible,
        )
        assertFalse(LLMError.TransientError("upstream reset").isKeyRotationEligible)
        assertFalse(LLMError.NetworkError(java.io.IOException("proxy drop")).isKeyRotationEligible)
    }

    @Test
    fun quotaExhaustedUserMessageAndFallbackReason() {
        val e = LLMError.QuotaExhausted("Insufficient Balance")
        assertEquals("Quota exhausted: Insufficient Balance", e.message)
        assertEquals("API key is out of quota", e.userMessage)
        assertEquals("Quota exhausted", e.fallbackReason)
    }

    @Test
    fun quotaExhaustedBlankDetail() {
        val e = LLMError.QuotaExhausted("")
        assertEquals("Quota exhausted", e.message)
    }
}
