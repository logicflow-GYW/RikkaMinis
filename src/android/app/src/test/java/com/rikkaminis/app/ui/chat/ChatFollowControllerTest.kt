package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [followReducer] / [consumeBottomRequest] — the user-intent
 * follow state machine.
 *
 * Acceptance-critical cases from the fix plan:
 *  - DETACHED + 100 StreamRowsChanged → 0 bottom requests.
 *  - FAB Down / Send / Resume each raise exactly ONE request — no settle
 *    second call.
 *  - DragStart immediately blocks programmatic scrolls.
 */
class ChatFollowControllerTest {

    private fun apply(state: FollowState, vararg events: FollowEvent): FollowState =
        events.fold(state) { s, e -> followReducer(s, e) }

    // ── explicit intents ────────────────────────────────────────────────────

    @Test fun `initial open follows and raises one request`() {
        val s = apply(FollowState(), FollowEvent.InitialOpen)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.INITIAL_OPEN, s.pendingBottomRequest)
    }

    @Test fun `send re-engages follow with exactly one request`() {
        val s = apply(FollowState(), FollowEvent.Send)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.SEND, s.pendingBottomRequest)
        // No settle second call: the next state after consuming has no request.
        val consumed = consumeBottomRequest(s)
        assertNull(consumed.pendingBottomRequest)
    }

    @Test fun `fab down resume retry each raise exactly one request`() {
        for (event in listOf(FollowEvent.FabDown, FollowEvent.Resume, FollowEvent.Retry)) {
            val s = apply(FollowState(), event)
            assertTrue("$event should set a pending request", s.hasPendingBottomRequest)
            assertEquals(FollowMode.FOLLOWING, s.mode)
            val consumed = consumeBottomRequest(s)
            assertFalse(consumed.hasPendingBottomRequest)
        }
    }

    @Test fun `explicit intent from detached re-engages follow`() {
        val detached = FollowState(mode = FollowMode.DETACHED)
        val s = apply(detached, FollowEvent.FabDown)
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertEquals(BottomRequestReason.FAB_DOWN, s.pendingBottomRequest)
    }

    // ── drag protocol ───────────────────────────────────────────────────────

    @Test fun `drag start drops any pending request and blocks scrolls`() {
        val s = apply(FollowState(), FollowEvent.Send) // has a pending request
        val duringDrag = apply(s, FollowEvent.UserDragStart)
        assertNull(duringDrag.pendingBottomRequest)
        assertEquals(FollowMode.FOLLOWING, duringDrag.mode) // mode not flipped mid-gesture
    }

    @Test fun `drag end at bottom keeps following`() {
        val s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = true))
        assertEquals(FollowMode.FOLLOWING, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    @Test fun `drag end away from bottom detaches`() {
        val s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        assertEquals(FollowMode.DETACHED, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    // ── acceptance: 100 StreamRowsChanged while detached → 0 requests ───────

    @Test fun `detached never raises a request no matter how much data arrives`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        var requests = 0
        repeat(100) {
            s = apply(s, FollowEvent.StreamRowsChanged)
            if (s.hasPendingBottomRequest) requests++
        }
        assertEquals(0, requests)
        assertEquals(FollowMode.DETACHED, s.mode)
        assertEquals(100L, s.rowRevision) // data revision still tracked
    }

    @Test fun `detached ignores tokens tools stream end and retries`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        // Simulate a full agent turn while the user reads history. Retry is a
        // USER-explicit intent (reducer re-engages follow on Retry) — an
        // AUTOMATIC re-run manifests as plain data revisions, which is what
        // this simulates: none of it may yank the reader.
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertNull(s.pendingBottomRequest)
        assertEquals(FollowMode.DETACHED, s.mode)
    }

    // ── following + stream progress ─────────────────────────────────────────

    @Test fun `following raises a progress request per committed revision`() {
        var s = apply(FollowState(), FollowEvent.InitialOpen)
        s = consumeBottomRequest(s)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
        s = consumeBottomRequest(s)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
    }

    @Test fun `drag back to bottom resumes following and progress requests`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertNull(s.pendingBottomRequest)
        // User drags back down — sentinel visible.
        s = apply(s, FollowEvent.UserDragEnd(atBottom = true))
        assertEquals(FollowMode.FOLLOWING, s.mode)
        s = apply(s, FollowEvent.StreamRowsChanged)
        assertEquals(BottomRequestReason.STREAM_PROGRESS, s.pendingBottomRequest)
    }

    // ── IME ─────────────────────────────────────────────────────────────────

    @Test fun `ime viewport change is a deliberate no-op`() {
        val s = apply(FollowState(), FollowEvent.Send)
        val withIme = apply(s, FollowEvent.ImeViewportChanged)
        // Request untouched, mode untouched, revision untouched.
        assertEquals(s, withIme)
    }

    @Test fun `ime change while detached does not yank`() {
        var s = apply(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        s = apply(s, FollowEvent.ImeViewportChanged)
        s = apply(s, FollowEvent.ImeViewportChanged)
        assertEquals(FollowMode.DETACHED, s.mode)
        assertNull(s.pendingBottomRequest)
    }

    // ── revision monotonicity ───────────────────────────────────────────────

    @Test fun `row revision is monotonic and never regresses`() {
        var s = FollowState()
        var prev = s.rowRevision
        val events = listOf(
            FollowEvent.StreamRowsChanged,
            FollowEvent.UserDragEnd(atBottom = false),
            FollowEvent.StreamRowsChanged,
            FollowEvent.ImeViewportChanged,
            FollowEvent.FabDown,
            FollowEvent.StreamRowsChanged,
        )
        for (e in events) {
            s = apply(s, e)
            assertTrue("revision must be monotonic (${s.rowRevision} < $prev)", s.rowRevision >= prev)
            prev = s.rowRevision
        }
    }

    // ── fix/history-open-at-bottom-04: data-ready exactly-once consumer ──────

    // The bottom scroll is now a single data-ready `scrollToItem` driven by
    // [decideBottomScroll]; below we pin the pure decision gate. INITIAL_OPEN
    // no longer flows through the gate — it is owned by the flatten collector
    // (see shouldScrollToBottomOnFirstRows tests at the bottom).

    private fun decide(
        sentinelVisible: Boolean = false,
        hasRows: Boolean = true,
        isFollowing: Boolean = true,
        isScrollInProgress: Boolean = false,
        isUserDragging: Boolean = false,
        focusTarget: Boolean = false,
    ) = decideBottomScroll(
        sentinelVisible = sentinelVisible,
        hasRows = hasRows,
        isFollowing = isFollowing,
        isScrollInProgress = isScrollInProgress,
        isUserDragging = isUserDragging,
        focusTarget = focusTarget,
    )

    @Test fun `data ready and following and not dragging scrolls to bottom`() {
        assertEquals(BottomScrollAction.SCROLL_TO_BOTTOM, decide())
    }

    @Test fun `user dragging abandons the scroll and consumes`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(isUserDragging = true))
    }

    @Test fun `in-flight programmatic scroll consumes without a second scroll`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(isScrollInProgress = true))
    }

    @Test fun `focus target never scrolls to bottom`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(focusTarget = true))
    }

    @Test fun `sentinel already visible consumes without a scroll`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(sentinelVisible = true))
    }

    @Test fun `detached consumes without a scroll`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(isFollowing = false))
    }

    @Test fun `empty layout safely consumes instead of scrolling`() {
        assertEquals(BottomScrollAction.SKIP_AND_CONSUME, decide(hasRows = false))
    }

    // ── fix/history-open-at-bottom-04: collector-owned INITIAL_OPEN ──────────

    @Test fun `first rows fire the opening scroll only for an init open while following`() {
        assertTrue(
            shouldScrollToBottomOnFirstRows(
                pendingBottomRequest = BottomRequestReason.INITIAL_OPEN,
                isFollowing = true,
            ),
        )
    }

    @Test fun `first rows do not scroll when detached even with an init open pending`() {
        assertFalse(
            shouldScrollToBottomOnFirstRows(
                pendingBottomRequest = BottomRequestReason.INITIAL_OPEN,
                isFollowing = false,
            ),
        )
    }

    @Test fun `first rows do not scroll for non-open reasons`() {
        for (reason in listOf(
            BottomRequestReason.SEND,
            BottomRequestReason.RESUME,
            BottomRequestReason.RETRY,
            BottomRequestReason.FAB_DOWN,
            BottomRequestReason.STREAM_PROGRESS,
            null,
        )) {
            assertFalse(
                "reason=$reason must not fire the first-rows scroll",
                shouldScrollToBottomOnFirstRows(pendingBottomRequest = reason, isFollowing = true),
            )
        }
    }
}
