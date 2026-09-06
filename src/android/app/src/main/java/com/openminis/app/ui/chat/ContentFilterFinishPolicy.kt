package com.openminis.app.ui.chat

/**
 * Content-filter / safety-block finish-reason classification
 * (feat/content-filter-fallback, Tier-2 item #2).
 *
 * A whole family of provider-side "the answer was blocked" finish reasons
 * currently fall through the agent loop as ordinary turns:
 *
 *  - Chat Completions: finish_reason = "content_filter"
 *  - Gemini: finishReason = SAFETY / PROHIBITED_CONTENT / BLOCKLIST /
 *    RECITATION / SPII / IMAGE_SAFETY / IMAGE_PROHIBITED_CONTENT /
 *    IMAGE_OTHER (already lowercased by GeminiProvider.extractFinishReason)
 *  - Anthropic: stop_reason = "refusal"
 *
 * All of these mean the SAME thing for recovery: THIS member deterministically
 * refused the request — re-asking it is wasted latency and a re-billed input.
 * A different group member (different provider / different safety posture)
 * may answer fine, so the right move is to fall back IMMEDIATELY, skipping
 * same-provider retries. That is exactly how LLMError.RateLimited /
 * InvalidApiKey already flow through the engine's fallback decision.
 *
 * Pure function, JVM-testable, no Android dependencies — mirrors
 * ChatStreamErrorPolicy's shape (string-in, action-out, conservative default).
 */
object ContentFilterFinishPolicy {

    /** Chat Completions / Gemini (post-lowercase) / Anthropic blocked-turn
     *  finish reasons. All lower-case; comparison is case-insensitive anyway. */
    private val BLOCKED_REASONS = setOf(
        // OpenAI Chat Completions / Responses API
        "content_filter",
        // Gemini generateContent finishReason values
        "safety",
        "prohibited_content",
        "blocklist",
        "recitation",
        "spii",
        "image_safety",
        "image_prohibited_content",
        "image_other",
        // Anthropic Messages API
        "refusal",
    )

    /**
     * [fix/finish-reason-network-error] Error-SHAPED finish reasons: relays
     * (observed in the field: agentrouter.org / glm-5.3) sometimes surface an
     * upstream failure as a normal SSE finish frame — finish_reason =
     * "network_error" with zero content and a clean [DONE]. The stream looks
     * COMPLETE to every layer (no EOF, no error line, no exception) while the
     * model reply is actually missing. Classify these so the engine can treat
     * the turn as a transient stream failure instead of a finished answer.
     */
    private val ERROR_SHAPED_REASONS = setOf(
        "network_error", "server_error", "provider_error", "service_unavailable",
        "upstream_error", "bad_gateway", "timeout", "gateway_timeout",
    )

    /**
     * True when a turn's finish reason is an error-shaped pseudo-finish (the
     * relay reporting ITS OWN failure inside a normal finish frame). Null /
     * unknown reasons are NOT error-shaped (conservative: only the known
     * error spellings trigger recovery).
     */
    fun isErrorShapedFinish(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        return reason.trim().lowercase() in ERROR_SHAPED_REASONS
    }

    /**
     * True when a turn's finish reason means "the provider blocked this
     * content" — the member's answer is unusable and deterministic.
     * Null / unknown reasons are NOT blocked (conservative: never trigger
     * fallback on a reason we don't understand).
     */
    fun isBlockedFinish(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        return reason.trim().lowercase() in BLOCKED_REASONS
    }
}
