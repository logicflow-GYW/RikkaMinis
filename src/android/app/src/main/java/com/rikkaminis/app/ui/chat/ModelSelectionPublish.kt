package com.rikkaminis.app.ui.chat

/**
 * [meta-audit-1028 F-06] The session's model-selection state as a value — the
 * whole surface a "which member serves this turn" decision publishes.
 *
 * `currentModel` / `currentProvider` are deliberately NOT modelled: they are
 * object references the caller already holds, and putting them here would drag
 * provider types into a function that must stay runnable on the JVM (same
 * reason [GroupSelectPlan] lives in its own Android-free file). Everything a
 * publication can get WRONG by running too early is here.
 *
 * The fields have two owners, and the distinction is the whole point of the
 * three call sites that publish them:
 *  - identity (`modelId` / `modelName` / `providerName` / `activeEntryId`) —
 *    who serves the next turn;
 *  - group mode (`selectedGroupId` / `selectedGroupName`) plus the one-shot
 *    member pick (`pendingEntryOverride`) — how the turn AFTER that is chosen.
 *
 * Nullable exactly where the live flow is nullable on purpose: `_activeEntryId`
 * is null before the first resolution and `currentModel` is null on a cold
 * start, so no call site has to invent a sentinel (an invented `""` would read
 * back as "an entry with an empty id" to `_traces_to_active`-style readers).
 */
internal data class SessionModelSelection(
    val modelId: String?,
    val modelName: String,
    val providerName: String,
    val activeEntryId: String?,
    val selectedGroupId: String?,
    val selectedGroupName: String,
    val pendingEntryOverride: String?,
)

/**
 * [meta-audit-1028 F-06] What a rotation is allowed to publish, as a value.
 *
 * @property applied false ⇒ publish NOTHING; `state` is the input state
 *   verbatim, so "failure changes nothing" is checkable field by field instead
 *   of being a comment.
 * @property provider the provider to hand `currentProvider`; null exactly when
 *   [applied] is false.
 * @property reason why the rotation declined (null when applied) — logged, and
 *   never null when `applied` is false.
 */
internal data class TurnRotationPlan<P>(
    val applied: Boolean,
    val provider: P?,
    val state: SessionModelSelection,
    val reason: String?,
)

/**
 * [meta-audit-1028 F-06] Plan ONE loadBalance rotation for a new user turn.
 *
 * `rotateForNewTurn` used to publish the rotated-in member FIRST
 * (`currentModel` / `_modelName` / `_providerName` / `_activeEntryId`) and only
 * then call `ProviderFactory.create`, the one call in the sequence that can
 * throw (unreachable base URL, a key shape the provider rejects, a provider
 * type that is no longer registered). A throw from there left the top bar, the
 * model picker and `_activeEntryId` claiming member B while `currentProvider`
 * still answered with member A — the session kept sending with the model the UI
 * said it had left. The direct-pick path fixed this shape in `[audit-0917]`
 * (`selectEntry`: "resolve the provider BEFORE mutating any field"); rotation,
 * the one path that runs unattended on every message, never got the same
 * treatment.
 *
 * Contract, in the order the arms are written:
 *  1. a failed (or absent) creation publishes nothing — `state` is the input
 *     state unchanged, `applied` is false, `reason` says why;
 *  2. a successful creation takes over the IDENTITY fields only;
 *  3. the group fields are carried over from [current] — rotation happens
 *     INSIDE a group, so it is the one publication that must NOT leave group
 *     mode (this is what distinguishes it from the new-chat/direct-entry
 *     publications, F-05).
 *
 * @param current the state at the moment of publication — the one-shot pick has
 *   already been consumed by the caller (it armed this turn; re-arming it after
 *   an abort would retry a creation that just failed on every following turn).
 */
internal fun <P> planTurnRotation(
    current: SessionModelSelection,
    targetEntryId: String,
    targetModelId: String,
    targetModelName: String,
    targetProviderName: String,
    created: P?,
    createError: String?,
): TurnRotationPlan<P> {
    val reason = when {
        createError != null -> createError
        created == null -> "ProviderFactory.create returned no provider"
        else -> null
    }
    if (reason != null || created == null) {
        return TurnRotationPlan(
            applied = false,
            provider = null,
            state = current,
            reason = reason ?: "ProviderFactory.create returned no provider",
        )
    }
    return TurnRotationPlan(
        applied = true,
        provider = created,
        state = current.copy(
            modelId = targetModelId,
            modelName = targetModelName,
            providerName = targetProviderName,
            activeEntryId = targetEntryId,
        ),
        reason = null,
    )
}
