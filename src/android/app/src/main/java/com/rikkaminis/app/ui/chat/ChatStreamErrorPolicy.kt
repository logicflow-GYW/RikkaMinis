package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind

/**
 * Stream-error recovery policy — the engine's single decision point for
 * "what do we do when a stream dies mid-flight" (fix/stream-error-silent-recovery).
 *
 * Problem it fixes: a mid-stream failure (proxy drop / relay reset, seen by
 * the user as a "Stream error" banner + manual retry button) crossed the
 * worker→main-process boundary as an untyped message — the engine could not
 * tell a transient network failure from a fatal one, so it skipped auto-retry
 * AND fallback, surfacing a hard error for what should self-heal silently.
 *
 * The worker now stamps a machine-readable kind on its error line
 * (ChatStreamJsonl.errorLine(message, kind); classification in
 * ChatStreamErrorPolicyKind.of — cause-chain walk). [classify] maps that kind
 * to a recovery action.
 *
 * Old-behavior guarantee: a null kind (legacy worker / untyped line) classifies
 * as [Action.FATAL] — identical to pre-fix behavior — so this policy can only
 * WIDEN auto-recovery, never turn a previously-recoverable error into a dead
 * end. Locked by ChatStreamErrorPolicyTest.
 */
object ChatStreamErrorPolicy {

    enum class Action {
        /** Same-provider auto-retry is safe: the engine rolls back partial
         *  output before the resend, so the user never sees duplicates. */
        AUTO_RETRY,

        /** This member can't help — skip same-provider retries and hand off
         *  to the group fallback chain immediately. */
        FALLBACK_NOW,

        /** Not auto-recoverable: surface the error banner (previous behavior). */
        FATAL,
    }

    /**
     * Decide recovery for a stream failure by its wire kind.
     *
     *  - network / transient  → AUTO_RETRY (proxy drop, relay reset, timeout —
     *    the whole IOException family; same-provider retry absorbs the blip)
     *  - rate_limited / invalid_key / provider → FALLBACK_NOW (retrying the
     *    same member won't help; mirrors LLMError.isFallbackable semantics)
     *  - null (legacy untyped) / unknown → FATAL (conservative; never guess)
     */
    fun classify(kind: String?): Action = when (kind) {
        ChatStreamErrorPolicyKind.KIND_NETWORK, ChatStreamErrorPolicyKind.KIND_TRANSIENT -> Action.AUTO_RETRY
        ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
        ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
        ChatStreamErrorPolicyKind.KIND_PROVIDER -> Action.FALLBACK_NOW
        else -> Action.FATAL
    }

    /**
     * A stream-exception recovery decision (§17
     * [fix/permanent-4xx-retried-as-transient]).
     */
    data class StreamFailure(
        /** Absorbable by a same-provider retry (partial output is rolled back first). */
        val isTransient: Boolean,
        /** Skip same-provider retries; hand off to the group fallback chain. */
        val fallbackNow: Boolean,
        /** Which judgement fired — logged, so a misclassification stays auditable. */
        val basis: String,
    )

    /**
     * §17: decide recovery for a failure that crossed the worker boundary as a
     * `ModelExecutionStreamException` (worker death / worker-reported stream
     * error).
     *
     * This needs its own entry point because the engine's 0-chunk heuristic
     * ("the worker died before emitting anything, so a resend cannot duplicate
     * output") used to apply UNCONDITIONALLY — including to failures the worker
     * had already classified. That is how a permanent `[400] model not found`
     * got retried three times (1+2+4s ~ 7s) on the same member before the
     * fallback: the worker had stamped `kind=provider`, and the engine called it
     * transient anyway because hadChunks was false.
     *
     * Rule: a stamped kind always speaks for itself (provider / rate_limited /
     * invalid_key -> no same-provider retry). The 0-chunk heuristic survives
     * only for UNSTAMPED failures (legacy workers, real worker death, timeouts),
     * which keeps every path that existed before the kind stamping landed
     * byte-identical.
     */
    fun decideStreamFailure(hasChunks: Boolean, kind: String?): StreamFailure {
        if (kind != null) {
            return when (classify(kind)) {
                Action.AUTO_RETRY -> StreamFailure(
                    isTransient = true,
                    fallbackNow = false,
                    basis = "stamped kind=$kind",
                )
                Action.FALLBACK_NOW -> StreamFailure(
                    isTransient = false,
                    fallbackNow = true,
                    basis = "stamped permanent kind=$kind",
                )
                Action.FATAL -> StreamFailure(
                    isTransient = false,
                    fallbackNow = false,
                    basis = "stamped unknown kind=$kind",
                )
            }
        }
        // Unstamped: the pre-stamping behavior, unchanged.
        return if (!hasChunks) {
            StreamFailure(isTransient = true, fallbackNow = false, basis = "unstamped zero-chunk")
        } else {
            StreamFailure(isTransient = false, fallbackNow = false, basis = "unstamped mid-stream")
        }
    }
}
