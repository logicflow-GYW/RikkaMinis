package com.rikkaminis.app.data.model

sealed class LLMError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidApiKey(val detail: String = "") : LLMError(if (detail.isBlank()) "Invalid API key" else "Invalid API key: $detail")
    class NetworkError(cause: Throwable) : LLMError("Network error: ${cause.message}", cause)
    class ProviderError(val detail: String) : LLMError("Provider error: $detail")
    class DecodingError(cause: Throwable) : LLMError("Decoding error: ${cause.message}", cause)
    class RateLimited(val retryAfterMs: Long? = null) : LLMError("Rate limited — please try again later")
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
    val isFallbackable: Boolean get() = this is RateLimited || this is InvalidApiKey || this is ProviderError || this is NetworkError || this is TransientError

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
            is ProviderError -> "Provider error"
            is TransientError -> "Transient error"
            is NetworkError -> "Network error"
            is DecodingError -> "Decoding error"
            is Cancelled -> "Cancelled"
            is Unknown -> "Unknown error"
        }
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
