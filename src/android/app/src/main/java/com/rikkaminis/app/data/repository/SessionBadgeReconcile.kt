package com.rikkaminis.app.data.repository

/**
 * [fix/meta1028-d1 F-03] The set handed to
 * [com.rikkaminis.app.service.SessionBadgeStore.reconcileInterruptedSessions],
 * or `null` when the answer is UNKNOWN.
 *
 * Why this tiny function exists: `reconcileInterruptedSessions` is a
 * set-difference consumer — every PAUSED session it is NOT told about loses its
 * badge AND the pruned map is persisted. So "the tail query failed" being
 * collapsed into `emptySet()` (the pre-fix behaviour: `runCatching { … }
 * .getOrElse { emptyList() }` inside `ChatRepository.interruptedSessionIds`,
 * plus a second swallowing `runCatching` at both `MinisApp` call sites) wiped
 * every PAUSED badge in the app on the next cold start / foreground transition,
 * after which nothing re-adds them until the sessions are opened again.
 *
 * `interruptedSessionIds()` is derived from a single cursor that reads one
 * `parts_json` per session across the whole history, so it CAN fail
 * (`SQLiteBlobTooBigException` / `CursorWindow` `IllegalStateException` on an
 * oversized row, a locked/corrupt DB) — and "I could not read the tails" is not
 * evidence that no session is interrupted.
 *
 * @param interruptedSessionIds null = the read failed.
 * @param activeSessionIds sessions currently streaming; never flagged PAUSED.
 * @return the ids to reconcile, or null when the caller must skip the
 *   reconcile entirely and keep the persisted badges as they are.
 */
internal fun badgeReconcileInput(
    interruptedSessionIds: Set<String>?,
    activeSessionIds: Set<String>,
): Set<String>? = interruptedSessionIds?.minus(activeSessionIds)
