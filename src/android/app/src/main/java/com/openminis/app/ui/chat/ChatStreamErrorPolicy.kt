package com.openminis.app.ui.chat

import com.openminis.app.sandbox.offload.ChatStreamErrorPolicyKind

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
}
