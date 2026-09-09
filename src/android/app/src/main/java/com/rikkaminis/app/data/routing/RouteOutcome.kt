package com.rikkaminis.app.data.routing

/**
 * Outcome of a request routed to a group member — the input to
 * [GroupRouter.recordResult].
 *
 * Mirrors the error taxonomy the chat loop already classifies
 * (LLMError), but as pure data with no Exception semantics so the router
 * stays trivially testable. Only outcomes that should affect the member's
 * runtime health appear here; everything else is deliberately absent:
 *
 * - NetworkError / Timeout are NOT represented: transient connectivity is
 *   the user's side, not the provider's fault. The loop retries on the same
 *   member (AUTO_RETRY_DELAYS_SEC backoff) and only consults
 *   isFallbackable after retries are exhausted — demoting member health
 *   over a wifi blip would churn the whole group for no reason.
 * - Cancelled is NOT represented: user-initiated, no signal about the member.
 */
sealed class RouteOutcome {
    /** Request completed. Clears any demotion (back to [MemberHealth.Healthy]). */
    object Success : RouteOutcome()

    /**
     * HTTP 429 / rate limit. [retryAfterMs] from the Retry-After header,
     * null when the provider didn't send one (router falls back to the
     * cooldown default).
     */
    data class RateLimited(val retryAfterMs: Long?) : RouteOutcome()

    /** HTTP 5xx (ProviderError). Counts toward the circuit breaker. */
    object ServerError : RouteOutcome()

    /** HTTP 401/403 (InvalidApiKey) — permanent until user re-auths. */
    object AuthError : RouteOutcome()
}
