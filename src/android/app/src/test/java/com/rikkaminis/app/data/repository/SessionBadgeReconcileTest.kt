package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [fix/meta1028-d1 F-03] "The tail query failed" must not be delivered to the
 * badge store as "no session is interrupted".
 *
 * `SessionBadgeStore.reconcileInterruptedSessions` is a set difference: every
 * PAUSED session it is not told about loses its badge and the pruned map is
 * persisted. `ChatRepository.interruptedSessionIds()` reads one `parts_json` per
 * session out of a single cursor (it CAN fail on an oversized / corrupt row),
 * and both pre-fix call sites collapsed that failure into `emptySet()` — twice
 * (the repository's `runCatching`, then `MinisApp`'s). The result was every
 * PAUSED badge in the app disappearing on the next cold start / foreground
 * transition, until each session was reopened.
 *
 * [badgeReconcileInput] is the seam: null in, null out, and the caller skips the
 * reconcile.
 */
class SessionBadgeReconcileTest {

    @Test
    fun unknownTailQueryKeepsTheBadges() {
        assertNull(
            badgeReconcileInput(interruptedSessionIds = null, activeSessionIds = setOf("s-live")),
        )
    }

    @Test
    fun anEmptyAnswerIsNotTheSameAsAFailure() {
        assertEquals(
            emptySet<String>(),
            badgeReconcileInput(interruptedSessionIds = emptySet(), activeSessionIds = emptySet()),
        )
    }

    @Test
    fun streamingSessionsAreNeverReconciledAsPaused() {
        assertEquals(
            setOf("s-idle"),
            badgeReconcileInput(
                interruptedSessionIds = setOf("s-idle", "s-live"),
                activeSessionIds = setOf("s-live"),
            ),
        )
    }

    @Test
    fun previouslyClearedSessionsStayCleared() {
        // The empty set still has to reach the store on a HEALTHY read: that is
        // how a resumed session loses its PAUSED badge.
        assertEquals(
            emptySet<String>(),
            badgeReconcileInput(interruptedSessionIds = emptySet(), activeSessionIds = setOf("s1")),
        )
    }
}
