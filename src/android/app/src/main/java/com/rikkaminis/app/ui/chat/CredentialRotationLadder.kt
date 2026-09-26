package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.routing.RouteOutcome
import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import com.rikkaminis.app.sandbox.offload.ModelStreamErrorException

/**
 * [T-multi-api-key] The credential-rotation ladder, as pure logic.
 *
 * Separated from `ChatCredentialRotation` (which is a ChatViewModel extension and
 * therefore untestable without a full ViewModel) because the ladder is exactly
 * where the feature silently broke: the wrapper's predicate matched typed
 * `LLMError` instances while the offloaded chat path only ever carries the
 * worker's stamped KIND string, so rotation never fired on a real request — the
 * multi-key feature behaved identically to a single-key one while looking
 * correct in each half's own tests.
 *
 * Everything here is decision-only: the caller owns the effects (parking the
 * spent credential, migrating sticky memory, logging, notifying the UI).
 */

/** Outcome of [decideCredentialRotation]. */
internal sealed interface RotationDecision {
    /** Retry with [nextIndex] (every ladder condition held). */
    data class Rotate(val nextIndex: Int) : RotationDecision

    /** No usable sibling, nothing left to try, or not a credential failure. */
    data object GiveUp : RotationDecision
}

/**
 * The kinds that implicate the credential itself, straight from the shared
 * wire-kind table so the two sides cannot drift apart.
 */
private val CREDENTIAL_SCOPED_KINDS = setOf(
    ChatStreamErrorPolicyKind.KIND_RATE_LIMITED,
    ChatStreamErrorPolicyKind.KIND_INVALID_KEY,
    ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED,
)

/**
 * Which credential-scoped failure kind [t] is, or null when the failure is not
 * about the credential (endpoint / network / model).
 *
 * Accepts BOTH shapes the failure can arrive in, which is the bug this fixes:
 *
 *  1. the WIRE shape — [ModelStreamErrorException] carrying the worker's stamped
 *     `kind`. This is what the offloaded chat path actually produces: the
 *     worker→main boundary has no typed LLMError
 *     ([com.rikkaminis.app.sandbox.offload.ModelExecutionStreamException] is
 *     constructed with `cause = null`), so a predicate that only matched
 *     LLMError *instances* could never fire there;
 *  2. the TYPED shape — [LLMError.RateLimited] / [LLMError.InvalidApiKey] /
 *     [LLMError.QuotaExhausted], i.e. in-process callers (and tests).
 *
 * Reading the kind is what makes the ladder documented in
 * [ChatStreamErrorPolicy] real ("the rotation wrapper tries sibling credentials
 * FIRST; this rung only fires once rotation is exhausted"): with a type-only
 * predicate that doc was unreachable.
 *
 * [T-rotate-on-any-error] Deliberately narrow — a 5xx says nothing about the
 * credential, so rotating on one would take every key of that endpoint down for
 * a fault that belonged to the endpoint alone.
 */
internal fun credentialScopedKind(t: Throwable): String? {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        val stamped = (cur as? ModelStreamErrorException)?.kind
        if (stamped != null) return stamped.takeIf { it in CREDENTIAL_SCOPED_KINDS }
        when (cur) {
            is LLMError.RateLimited -> return ChatStreamErrorPolicyKind.KIND_RATE_LIMITED
            is LLMError.InvalidApiKey -> return ChatStreamErrorPolicyKind.KIND_INVALID_KEY
            is LLMError.QuotaExhausted -> return ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED
        }
        cur = cur.cause
        depth++
    }
    return null
}

/**
 * The rotation ladder's decision for one failed attempt:
 *
 *  - the failure must be about the CREDENTIAL ([credentialScopedKind]);
 *  - it must be a ZERO-CHUNK failure — once the user has seen partial text a
 *    resend would duplicate it, and rollback belongs to the engine's fallback
 *    loop, not to this rung;
 *  - the turn must have attempts left. One shot per credential per turn
 *    ([keyCount] − 1 rotations) is the source feature's bound, kept because the
 *    cheap rung must stay cheap: a health-blind walk can otherwise hand the next
 *    attempt back to a slot that already failed;
 *  - a sibling credential must actually be healthy.
 *
 * [nextCandidate] is consulted only when the first three hold, so the caller can
 * pass a lambda with side effects (the router's walk) without it firing on a
 * non-credential failure.
 */
internal fun decideCredentialRotation(
    kind: String?,
    emittedContent: Boolean,
    rotationsDone: Int,
    keyCount: Int,
    nextCandidate: () -> Int?,
): RotationDecision {
    if (kind == null || emittedContent) return RotationDecision.GiveUp
    if (keyCount <= 1 || rotationsDone >= keyCount - 1) return RotationDecision.GiveUp
    val next = nextCandidate() ?: return RotationDecision.GiveUp
    return RotationDecision.Rotate(next)
}

/**
 * Map a credential-scoped [kind] to the health verdict to record for the
 * credential that just failed.
 *
 * Mapped from the WIRE KIND rather than from the exception type for the same
 * reason [credentialScopedKind] reads the kind: typed instances never cross the
 * worker boundary, so a type-based `when` fell through to [RouteOutcome.ServerError]
 * for every real failure — parking a spent key under the wrong verdict, which
 * keeps the circuit-breaker semantics (5 minutes, then retry the same dead key)
 * instead of the credential semantics (spent until the user tops up).
 */
internal fun credentialOutcomeFor(kind: String, e: Throwable?): RouteOutcome = when (kind) {
    ChatStreamErrorPolicyKind.KIND_QUOTA_EXHAUSTED -> RouteOutcome.QuotaExhausted
    // Only the typed shape carries Retry-After; the worker does not forward it.
    ChatStreamErrorPolicyKind.KIND_RATE_LIMITED ->
        RouteOutcome.RateLimited((e as? LLMError.RateLimited)?.retryAfterMs)
    ChatStreamErrorPolicyKind.KIND_INVALID_KEY -> RouteOutcome.AuthError
    else -> RouteOutcome.ServerError
}
