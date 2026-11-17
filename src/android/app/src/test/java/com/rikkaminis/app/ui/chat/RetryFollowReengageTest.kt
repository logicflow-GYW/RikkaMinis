package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [incr1118 D1-F05 / D1-F08] Programmatic "retry from here" jumps must move the
 * FOLLOW STATE, not just the viewport.
 *
 * Real-device shape of the bug: the user scrolls up to read history (follow →
 * DETACHED), long-presses a message and taps Retry. The jump scrolls to the
 * bottom, a new answer streams — and the viewport never moves again, because
 * every automatic scroll is gated on `FOLLOWING`; the Down-FAB also stays on
 * screen over a list that is already at the bottom. The banner path already did
 * this (`FollowEvent.Resume`); the four retry/rerun entry points did not.
 *
 * RULE layer: `followReducer(DETACHED, Retry)` must re-engage and raise exactly
 * one bottom request — the same contract as Send / Resume.
 * SHAPE layer: each entry point dispatches it INSIDE its own callback, before
 * the scroll it launches, and routes the index through
 * [safeBottomScrollIndex] (D1-F08: `totalItemsCount - 1` on an unmeasured list
 * is -1, and `.coerceAtLeast(0)` silently turned that into "scroll to row 0").
 */
class RetryFollowReengageTest {

    private val detached = FollowState(mode = FollowMode.DETACHED, rowRevision = 7)

    // ── RULE layer ──────────────────────────────────────────────────────────

    @Test
    fun `retry from a history reader re-engages follow`() {
        val next = followReducer(detached, FollowEvent.Retry)
        assertTrue(next.isFollowing)
        assertEquals(BottomRequestReason.RETRY, next.pendingBottomRequest)
    }

    @Test
    fun `retry raises exactly one bottom request and no extra revisions`() {
        val next = followReducer(detached, FollowEvent.Retry)
        assertEquals("a jump is not a data revision", detached.rowRevision, next.rowRevision)
        assertEquals(1, listOfNotNull(next.pendingBottomRequest).size)
    }

    @Test
    fun `retry from a follower stays following`() {
        val following = FollowState()
        assertTrue(followReducer(following, FollowEvent.Retry).isFollowing)
    }

    // ── SHAPE layer ─────────────────────────────────────────────────────────

    private val entryPoints = listOf(
        "RETRY-FROM-MSG",
        "RERUN-FROM-TOOLRUN",
        "INLINE-RETRY-LAST",
        "RERUN-FROM-AGG-TOOL",
    )

    private fun chatScreenSrc(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            val here: File = dir
            for (rel in listOf(
                "src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt",
                "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt",
            )) {
                val f = File(here, rel)
                if (f.exists()) return f.readText()
            }
            dir = here.parentFile
            hops++
        }
        throw IllegalStateException("ChatScreen.kt not found from ${System.getProperty("user.dir")}")
    }

    /** The callback that owns this scroll: from its `onRetry =` / `onRerunFromHere =`
     *  line to the scroll call, bounded so a dispatch in a NEIGHBOURING callback
     *  cannot satisfy the assertion. */
    private fun callbackTextBefore(src: String, marker: String): String {
        val scrollAt = src.indexOf("tracedScrollToItem(\"$marker\"")
        assertTrue("entry point $marker not found", scrollAt >= 0)
        val anchors = listOf("onRetry =", "onRerunFromHere =", "onEdit =")
        val anchorAt = anchors
            .map { a -> src.lastIndexOf(a, scrollAt) }
            .filter { it >= 0 }
            .maxOrNull()
            ?: throw AssertionError("no callback anchor for $marker")
        val text = src.substring(anchorAt, scrollAt)
        assertTrue(
            "the dispatch for $marker must be in the same callback as its scroll",
            text.length < 1_500,
        )
        return text
    }

    @Test
    fun `every retry entry point dispatches FollowEvent Retry in its own callback`() {
        val src = chatScreenSrc()
        for (marker in entryPoints) {
            assertTrue(
                "$marker scrolls to the bottom without telling the follow reducer",
                callbackTextBefore(src, marker).contains("FollowEvent.Retry"),
            )
        }
    }

    @Test
    fun `every retry entry point resolves its index through the shared helper`() {
        val src = chatScreenSrc()
        for (marker in entryPoints + "EDIT-MSG") {
            assertTrue(
                "$marker must use safeBottomScrollIndex (index 0 on an empty list is a silent no-op)",
                callbackTextBefore(src, marker).contains("safeBottomScrollIndex("),
            )
        }
    }

    @Test
    fun `no call site still hand-rolls the bottom index`() {
        val src = chatScreenSrc()
        assertFalse(
            "`(totalItemsCount - 1).coerceAtLeast(0)` is the old shape: on an empty list it " +
                "asks for row 0 instead of asking for nothing",
            Regex("\\(listState\\.layoutInfo\\.totalItemsCount - 1\\)\\.coerceAtLeast\\(0\\)")
                .containsMatchIn(src),
        )
    }

    @Test
    fun `Resume keeps its own dispatch`() {
        // Guard against "fix the new ones by deleting the old one".
        assertTrue(
            "the resume banner's FollowEvent.Resume dispatch must stay",
            chatScreenSrc().contains("FollowEvent.Resume"),
        )
    }
}
