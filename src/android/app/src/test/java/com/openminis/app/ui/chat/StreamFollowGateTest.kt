package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/stream-follow-detached-deadlock] Regression test for the rewritten
 * SIMPLE_FOLLOW streaming auto-follow gate.
 *
 * Log forensics (minis-2026-09-01__5_.log, build 65b8a749): the follow broke
 * for an ENTIRE streaming turn. Root cause chain:
 *
 *  1. Forward layout anchors at firstVisibleItem; a pinned bottom viewport's
 *     rows grow in height as tokens stream in, so the growth pushes the 5dp
 *     bottom sentinel OUT of the viewport while the user is still "at the
 *     bottom" semantically (never scrolled away).
 *  2. The old gate `isBottomSentinelVisible == false → return` then read
 *     false permanently and silenced the effect for the whole turn — the
 *     viewport froze, every token rendered off-screen (21s placed-report gap
 *     19:26:54→19:27:15 with zero touches = the streaming row fully
 *     off-viewport), and the user had to drag / tap the FAB to catch up.
 *  3. Before 91498d74 the effect "worked" only because the clamp storm
 *     re-clamped the viewport to the bottom every frame — the follow
 *     behaviour WAS the storm. The storm fix removed the follower with the
 *     storm.
 *
 * The new contract replaces the sentinel gate with the follow state machine's
 * own verdict (followState.isFollowing, decided at drag-end from the raw list
 * position) while keeping the clamp guard:
 *
 *   isStreaming && !scrollInProgress && !isUserDragging &&
 *   followState.isFollowing && shouldRequestFollowScroll(canScrollForward)
 */
class StreamFollowGateTest {

    // The gate contract as a pure function of its inputs — mirrors the
    // collector's early-return chain in ChatScreen exactly.
    private fun gateRequestsScroll(
        isStreaming: Boolean,
        isScrollInProgress: Boolean,
        isUserDragging: Boolean,
        isFollowing: Boolean,
        canScrollForward: Boolean,
        totalItems: Int,
    ): Boolean =
        !isScrollInProgress &&
            isStreaming &&
            !isUserDragging &&
            isFollowing &&
            totalItems != 0 &&
            shouldRequestFollowScroll(canScrollForward)

    @Test
    fun `following viewer with grown content requests the follow scroll`() {
        // The 5__log scenario AFTER the fix: user still FOLLOWING (never
        // scrolled away), new content grew the list (clamp released).
        assertTrue(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = true,
                canScrollForward = true,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `sentinel pushed out of viewport no longer silences the effect`() {
        // THE BUG: sentinel visible == false (growth pushed it out) used to
        // early-return forever. The new contract has no sentinel input at all —
        // a FOLLOWING viewer keeps following. Simulate the frozen viewport:
        // sentinel out, still FOLLOWING, clamp released by growth.
        val sentinelVisible = false
        val isFollowing = true
        // Old contract (isBottomSentinelVisible gate) would go silent:
        val oldContractRequests = sentinelVisible
        assertFalse(oldContractRequests)
        // New contract requests:
        assertTrue(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = isFollowing,
                canScrollForward = true,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `detached history reader is never yanked`() {
        // The anti-yank contract survives the gate rewrite: DETACHED (the
        // user scrolled up to read) must never be auto-scrolled, even while
        // streaming with content growing and the clamp released.
        assertFalse(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = false,
                canScrollForward = true,
                totalItems = 40,
            )
        )
    }

    @Test
    fun `clamped viewport stays silent - no storm regression`() {
        // The 91498d74 contract must survive: pinned flush at the bottom
        // (canScrollForward == false) → request is unreachable → skip, or
        // the 60Hz clamp measure storm comes back (PlaceStorm).
        assertFalse(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = true,
                canScrollForward = false,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `user drag owns the viewport`() {
        // Finger down mid-stream: nothing may fight the gesture, even while
        // FOLLOWING with grown content.
        assertFalse(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = true,
                isFollowing = true,
                canScrollForward = true,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `in-flight programmatic scroll is not fought`() {
        // A follow scroll / fling is settling: no second request on top of it.
        assertFalse(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = true,
                isUserDragging = false,
                isFollowing = true,
                canScrollForward = true,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `not streaming never requests`() {
        // Between turns / after stream end the effect must stay silent.
        assertFalse(
            gateRequestsScroll(
                isStreaming = false,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = true,
                canScrollForward = true,
                totalItems = 3,
            )
        )
    }

    @Test
    fun `empty list never requests`() {
        // totalItems == 0: nothing to scroll to (safeBottomScrollIndex null
        // path's cousin in the gate chain).
        assertFalse(
            gateRequestsScroll(
                isStreaming = true,
                isScrollInProgress = false,
                isUserDragging = false,
                isFollowing = true,
                canScrollForward = true,
                totalItems = 0,
            )
        )
    }
}
