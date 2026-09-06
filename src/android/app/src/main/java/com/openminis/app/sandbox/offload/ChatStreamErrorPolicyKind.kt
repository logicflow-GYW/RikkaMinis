package com.openminis.app.sandbox.offload

/**
 * Worker-side stream-error kind stamping (fix/stream-error-silent-recovery).
 *
 * Lives in the offload package (NOT ui.chat) so the :modelservice worker can
 * classify failures without a layering inversion. The values are consumed by
 * [com.openminis.app.ui.chat.ChatStreamErrorPolicy] on the main process side;
 * the string literals are the wire contract — change both together or keep
 * referencing the constants here.
 *
 * The worker writes `{"t":"error","m":...,"k":<kind>}`; older clients ignore
 * the extra field, older workers simply omit it (null kind on the client = 
 * pre-fix behavior, exactly as before).
 */
object ChatStreamErrorPolicyKind {
    const val KIND_NETWORK = "network"
    const val KIND_TRANSIENT = "transient"
    const val KIND_RATE_LIMITED = "rate_limited"
    const val KIND_INVALID_KEY = "invalid_key"
    const val KIND_PROVIDER = "provider"

    /**
     * Classify a worker-side failure for the error line's `k` field.
     * Walks the cause chain because provider layers wrap real failures:
     * OpenAIProvider cancels its flow with `cancel("Stream error",
     * mapError(e))` — the mapped LLMError sits in the cause chain, and the
     * raw IOException (proxy drop / socket reset) one level deeper.
     */
    fun of(t: Throwable): String? {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            when (cur) {
                is com.openminis.app.data.model.LLMError.RateLimited -> return KIND_RATE_LIMITED
                is com.openminis.app.data.model.LLMError.InvalidApiKey -> return KIND_INVALID_KEY
                is com.openminis.app.data.model.LLMError.NetworkError -> return KIND_NETWORK
                is com.openminis.app.data.model.LLMError.TransientError -> return KIND_TRANSIENT
                is com.openminis.app.data.model.LLMError.ProviderError -> return KIND_PROVIDER
                is java.io.IOException -> return KIND_NETWORK
            }
            cur = cur.cause
            depth++
        }
        return null
    }
}
