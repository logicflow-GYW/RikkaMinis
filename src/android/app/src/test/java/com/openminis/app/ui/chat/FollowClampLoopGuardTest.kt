package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/place-storm-follow-clamp-loop] Regression test for the SIMPLE_FOLLOW
 * clamp guard.
 *
 * Log forensics (minis-2026-09-01__2_.log, PlaceStorm dumps): while streaming
 * with the viewport pinned at the bottom clamp, the follow effect issued
 * requestScrollToItem(sentinel, 0) on EVERY visibleItemsInfo emission. That
 * target is unreachable (LazyListMeasure clamps it back), so each request
 * wrote scroll state, forced a remeasure, changed visibleItemsInfo, and
 * re-triggered the collector — a self-sustaining 60Hz measure loop that ran
 * for the entire streaming turn (60 places/sec on the newest item, size
 * unchanged, firstIdx/firstOff frozen at the clamped position in every dump).
 *
 * The guard: only request when the clamp is released (canScrollForward).
 */
class FollowClampLoopGuardTest {

    @Test
    fun `clamped viewport never re-requests`() {
        // At the bottom clamp: canScrollForward == false. The sentinel IS
        // visible and the stream IS running — the exact storm configuration.
        // The guard must veto the request or the clamp cycle re-arms.
        assertFalse(shouldRequestFollowScroll(canScrollForward = false))
    }

    @Test
    fun `released clamp requests follow scroll`() {
        // New content grew the list while we were pinned: the clamp is
        // released, the sentinel has scrolled out, and a follow request is
        // now both reachable and needed.
        assertTrue(shouldRequestFollowScroll(canScrollForward = true))
    }

    @Test
    fun `guard composes with the storm configuration end to end`() {
        // The full old contract that produced the storm:
        //   isStreaming && sentinelVisible && !scrollInProgress → request
        // replayed with the guard. The storm dump values (firstIdx=1,
        // firstOff=2366, totalItems=3) describe a viewport where item 1's
        // overhang consumes the entire remaining scroll — canScrollForward
        // is false — so the guarded contract declines to request.
        val isStreaming = true
        val sentinelVisible = true
        val isScrollInProgress = false
        val canScrollForward = false
        val oldContractWouldRequest = isStreaming && sentinelVisible && !isScrollInProgress
        assertTrue(oldContractWouldRequest) // the bug: it did request
        val guardedRequests = oldContractWouldRequest && shouldRequestFollowScroll(canScrollForward)
        assertFalse(guardedRequests) // the fix: it no longer does
    }
}
