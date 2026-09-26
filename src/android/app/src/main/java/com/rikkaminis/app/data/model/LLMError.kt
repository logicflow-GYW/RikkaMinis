package com.rikkaminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited(val retryAfterMs: Long? = null) : LLMError("Rate limited — please try again later")

    /**
     * [T-multi-api-key] The credential itself is out of quota / balance
     * (HTTP 402, or 429/403 whose body says so) — as opposed to a per-minute
     * rate limit.
     *
     * The distinction is load-bearing for multi-key routing:
     *
     *  - [RateLimited] is a *time* problem. Waiting fixes it, and the same
     *    credential becomes usable again on its own (free-tier "500 calls /
     *    5h" windows). Cooldown is bounded and self-healing.
     *  - [QuotaExhausted] is a *credential* problem. Waiting does NOT fix it;
     *    the key is spent until the user tops up. Retrying it is pure waste,
     *    and letting it stay in the rotation just adds a guaranteed failure
     *    to every fallback chain.
     *
     * Before this type existed, `insufficient_quota` fell through
     * OpenAIProvider.mapHttpError's three-way split (401/403 → InvalidApiKey,
     * 429 → RateLimited, else → ProviderError) into [ProviderError] →
     * [com.rikkaminis.app.data.routing.RouteOutcome.ServerError], i.e. it was
     * recorded as a *server-side* fault: the circuit opened for
     * CIRCUIT_OPEN_MS (5 min) and then retried the same dead key forever,
     * surfacing as "Provider returned an error". See
     * [com.rikkaminis.app.data.routing.RouteOutcome.QuotaExhausted].
     *
     * @param detail provider-supplied message, verbatim (shown behind the
     *   technical-details disclosure only).
     */
    class QuotaExhausted(val detail: String = "") :
        LLMError(if (detail.isBlank()) "Quota exhausted" else "Quota exhausted: $detail")

    class TransientError(val detail: String) : LLMError("Transient error: $detail")
    class Cancelled : LLMError("Request was cancelled")
    class Unknown(cause: Throwable?) : LLMError("Unknown error: ${cause?.message}", cause)

    /** Pure connectivity failure — the request didn't land at all. */
    val isNetworkError: Boolean get() = this is NetworkError

    /** Worth retrying on the same provider (bounded backoff). */
    val isRetryable: Boolean get() = this is NetworkError || this is TransientError

    /**
     * Should fall back to the next model in the group — same model won't help.
     *
     * [T-fallback-network-errors] NetworkError / TransientError are included:
     * "this endpoint can't help right now" is exactly the group-fallback case
     * (stream reset, connection timeout, DNS failure, proxy drop — the whole
     * OkHttp IOException family the user sees as "模型不可用"). The retry
     * loop still gets first crack at them (bounded backoff on the SAME
     * provider); `isFallbackable` is only consulted AFTER retries are
     * exhausted, so including them here never skips the retry — it just stops
     * the "retried 3×, then hard-stopped with a red banner" dead end.
     */
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError || this is QuotaExhausted || this is NetworkError || this is TransientError

    /**
     * [T-multi-api-key] Which failures actually implicate the CREDENTIAL (as
     * opposed to the endpoint, the network, or the model).
     *
     * [T-rotate-on-any-error] This is NO LONGER the gate on credential
     * rotation: the retry loop now rotates on any failure, so a problem
     * attributed to the wrong layer still gets the cheap retry before it
     * costs a model switch. The predicate survives as the single statement of
     * the taxonomy, and the loop still consults the same distinction for the
     * two decisions where it genuinely matters:
     *
     *  1. **Park the key?** Only a credential-scoped failure demotes the slot.
     *     A 5xx says nothing about this key, so marking it demoted would shrink
     *     the rotation pool for a fault the credential had no part in.
     *  2. **Refill the auto-retry budget?** Only a credential-scoped failure
     *     earns the next key a fresh 1s/2s/4s ladder. Granting that on a
     *     network/5xx failure would turn one dead wifi into 3×N requests
     *     (N = key count) before the user saw any error.
     *
     *  - `true`  → RateLimited / InvalidApiKey / QuotaExhausted.
     *  - `false` → ProviderError / TransientError / NetworkError.
     */
    val isKeyRotationEligible: Boolean
        get() = this is RateLimited || this is InvalidApiKey || this is QuotaExhausted

    /**
     * Human-readable, user-facing summary of the failure — NO raw error codes
     * (no "stream was reset: CANCEL", no HTTP status numbers). This is what
     * the inline error banner shows; the technical detail (raw message, fallback
     * trail) is collapsed behind the "technical details" disclosure instead of
     * being pasted into the chat record. [T-error-no-permanent-scars]
     */
    val userMessage: String
        get() = when (this) {
            is RateLimited -> "Rate limited — try again in a moment"
            is InvalidApiKey -> "API key is invalid or expired"
            is QuotaExhausted -> "API key is out of quota"
            is ProviderError -> "Provider returned an error"
            is NetworkError -> "Connection failed"
            is TransientError -> "Service temporarily unavailable"
            is DecodingError -> "Unexpected response from provider"
            is Cancelled -> "Request was cancelled"
            is Unknown -> "Something went wrong"
        }

    /** Short user-facing reason shown when a fallback engages. */
    val fallbackReason: String
        get() = when (this) {
            is RateLimited -> "Rate limited"
            is InvalidApiKey -> "Invalid API key"
            is QuotaExhausted -> "Quota exhausted"
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
}

/**
 * [T-multi-api-key] Classify a provider error BODY as out-of-quota rather than
 * a generic provider failure.
 *
 * Providers disagree on how to report "this key is spent", so the status code
 * is not sufficient — every shape below has been observed in the wild:
 *
 *  - **402 Payment Required** — OpenAI-compatible relays, DeepSeek.
 *  - **429 with `insufficient_quota`** — OpenAI's documented out-of-credit
 *    code (a 429 status carrying a non-rate-limit meaning).
 *  - **403 with a balance message** — several relays (e.g. "余额不足").
 *  - **400 `credit_balance_too_low`** — Anthropic's own credit-exhaustion
 *    shape (`invalid_request_error: Your credit balance is too low`).
 *  - **429 `RESOURCE_EXHAUSTED` + quota/billing** — Gemini.
 *
 * Matching is case-insensitive on the body so a relay that re-cases the code
 * still classifies. Kept as a pure function (no provider deps) so the whole
 * taxonomy is JVM-testable without an HTTP stack.
 *
 * @param statusCode HTTP status from the response.
 * @param body raw response body (already truncated by the caller if huge).
 * @return true when the failure is a spent credential, not a transient fault.
 */
fun isQuotaExhaustedResponse(statusCode: Int, body: String): Boolean {
    val lower = body.lowercase()
    // Unambiguous status: 402 means "pay up" on every OpenAI-compatible stack.
    if (statusCode == 402) return true
    val quotaMarkers = listOf(
        "insufficient_quota",
        "insufficient quota",
        "quota_exceeded",
        "quota exceeded",
        "exceeded your current quota",
        "billing_hard_limit_reached",
        "credit_balance_too_low",
        "insufficient_balance",
        "insufficient balance",
        "no credit",
        "out of credit",
        "balance is too low",
        "余额不足",
        "额度不足",
        "欠费",
    )
    if (quotaMarkers.any { lower.contains(it) }) return true
    return false
}

/**
 * [T-error-no-permanent-scars] Thrown when the group-fallback chain is fully
 * exhausted (every member failed). Carries a HUMAN-READABLE `summary` (what
 * the error banner shows) separately from the raw `detail` (per-model failure
 * trail + original error codes, shown only behind the "technical details"
 * disclosure). Splitting the two at the throw site keeps the banner text
 * clean without losing debuggability.
 */
class FallbackExhaustedError(
    val summary: String,
    val detail: String,
) : Exception(summary)

/**
 * Parse a `Retry-After` HTTP header into a cooldown duration in milliseconds.
 * Returns null when absent / unparseable — the caller falls back to
 * [com.rikkaminis.app.data.routing.GroupRouter.RATE_LIMIT_COOLDOWN_DEFAULT_MS].
 *
 * RFC 7231 §7.1.3 allows two forms; both are handled:
 *  - delay-seconds: `Retry-After: 120`
 *  - HTTP-date:     `Retry-After: Fri, 31 Dec 1999 23:59:59 GMT`
 *
 * A delay of 0 is valid (retry immediately) — coerced to 0 ms.
 */
fun parseRetryAfterMs(headerValue: String?, nowMs: Long): Long? {
    if (headerValue.isNullOrBlank()) return null
    val trimmed = headerValue.trim()
    trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
    return try {
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
            .parse(trimmed) ?: return null
        (date.time - nowMs).coerceAtLeast(0L)
    } catch (_: Exception) {
        null
    }
}
