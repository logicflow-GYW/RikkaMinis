package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-C4] The two PROGRAMMATIC jump entry points in ChatScreen (the
 * session/search focus jump and the "scroll to first message" FAB) share one
 * verdict helper, [followEventForProgrammaticJump]. Because
 * `LazyListState.scrollToItem` fires no `DragInteraction.Stop`, the drag-stop
 * handler — the only FOLLOWING → DETACHED transition — never runs for a
 * programmatic jump; without the dispatch the streaming follow collector yanks
 * the viewport back to the bottom (streaming) and the return-to-bottom FAB
 * never appears (idle).
 *
 * Two layers, same split as the E5 fixtures:
 *  - RULE layer: pure `followReducer` runs on the verdict — "a jump to history
 *    must end DETACHED", "a jump to the newest row must leave the state alone",
 *    "a detached jump must not re-engage follow", "the verdict clears any
 *    queued bottom request" (otherwise the pending request scrolls to the
 *    bottom right after the jump — same symptom through the other door).
 *  - SHAPE layer: source pin that BOTH entry points dispatch the verdict BEFORE
 *    their `tracedScrollToItem` (order matters: the collector must already read
 *    DETACHED on the emission the jump itself causes).
 */
class ProgrammaticJumpFollowTest {

    // ── RULE layer ──────────────────────────────────────────────────────────

    private fun stateAfterJump(
        targetIsNewestRow: Boolean,
        from: FollowState = FollowState(),
    ): FollowState? {
        val event = followEventForProgrammaticJump(targetIsNewestRow) ?: return null
        return followReducer(from, event)
    }

    @Test
    fun jumpToHistory_endsDetached() {
        val next = stateAfterJump(targetIsNewestRow = false)!!
        assertEquals(FollowMode.DETACHED, next.mode)
        assertFalse(next.isFollowing)
    }

    @Test
    fun jumpToHistoryFromDetached_isIdempotent() {
        val detached = followReducer(FollowState(), FollowEvent.UserDragEnd(atBottom = false))
        assertEquals(FollowMode.DETACHED, detached.mode)
        val next = stateAfterJump(targetIsNewestRow = false, from = detached)!!
        assertEquals(FollowMode.DETACHED, next.mode)
    }

    @Test
    fun jumpToNewestRow_leavesStateMachineUntouched() {
        // null = no dispatch: a DETACHED reader who jumps to the newest row is
        // NOT silently re-engaged; a FOLLOWING reader keeps following.
        assertNull(followEventForProgrammaticJump(targetIsNewestRow = true))
    }

    @Test
    fun jumpToHistory_clearsPendingBottomRequest() {
        val following = followReducer(FollowState(), FollowEvent.Send)
        assertNotNull(following.pendingBottomRequest)
        val next = stateAfterJump(targetIsNewestRow = false, from = following)!!
        assertNull(next.pendingBottomRequest)
    }

    @Test
    fun jumpToHistory_doesNotBumpRowRevision() {
        val next = stateAfterJump(targetIsNewestRow = false)!!
        assertEquals(FollowState().rowRevision, next.rowRevision)
    }

    // ── SHAPE layer ─────────────────────────────────────────────────────────

    private fun repoFile(vararg candidates: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            val here: File = dir
            for (rel in candidates) {
                val f = File(here, rel)
                if (f.exists()) return f
            }
            dir = here.parentFile
            hops++
        }
        throw IllegalStateException(
            "repo file not found walking up from ${System.getProperty("user.dir")}"
        )
    }

    private fun chatScreenSrc(): String = repoFile(
        "src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt",
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt",
    ).readText()

    /** Both jump entry points must route through the shared verdict helper. */
    @Test
    fun bothJumpEntryPoints_dispatchTheJumpVerdict() {
        val src = chatScreenSrc()
        for (marker in listOf("FOCUS-MESSAGE", "FAB-UP")) {
            val at = src.indexOf("tracedScrollToItem(\"$marker\"")
            assertTrue("jump entry $marker not found in ChatScreen.kt", at >= 0)
            val window = src.substring(maxOf(0, at - 1500), at)
            assertTrue(
                "jump entry $marker no longer dispatches followEventForProgrammaticJump before scrolling",
                window.contains("followEventForProgrammaticJump("),
            )
        }
    }

    /** The verdict must be dispatched BEFORE the scroll, not after. */
    @Test
    fun verdictDispatch_precedesTheScrollAtBothEntries() {
        val src = chatScreenSrc()
        for (marker in listOf("FOCUS-MESSAGE", "FAB-UP")) {
            val scrollAt = src.indexOf("tracedScrollToItem(\"$marker\"")
            val dispatchAt = src.lastIndexOf("followEventForProgrammaticJump(", scrollAt)
            assertTrue(
                "no dispatch found before $marker scroll",
                dispatchAt in 0 until scrollAt,
            )
        }
    }

    /** Exactly one helper definition; the entry points must not grow their own copies. */
    @Test
    fun helperDefinedOnce_inChatScreen() {
        val src = chatScreenSrc()
        assertEquals(
            1,
            Regex("internal fun followEventForProgrammaticJump\\(").findAll(src).count(),
        )
    }
}
