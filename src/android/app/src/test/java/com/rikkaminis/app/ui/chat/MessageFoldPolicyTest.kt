package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/user-msg-fold-1114] Fold thresholds for USER bubbles.
 *
 * Defect this pins: the composer folds a paste into a `[Pasted#N]` chip, but the
 * chip is UI-only state and `ChatViewModel.sendMessage` expands the body before
 * persisting it — so the bubble rendered the full paste. The only fold was
 * [LARGE_MESSAGE_THRESHOLD_CHARS] = 32_000 (a markdown GC-storm floor for legacy
 * 200KB tool_result rows), which a 15K paste sailed under.
 *
 * Expected values are literals on purpose: deriving them from the constants
 * would let a constant change move the expectation with it and turn a real
 * regression into a PASS (the self-referential test trap).
 */
class MessageFoldPolicyTest {

    // --- the thresholds themselves -------------------------------------------

    @Test
    fun `the user threshold is 4000 chars`() {
        assertEquals(4_000, USER_MESSAGE_COLLAPSE_THRESHOLD_CHARS)
    }

    @Test
    fun `the user preview is 600 chars`() {
        assertEquals(600, USER_MESSAGE_PREVIEW_CHARS)
    }

    @Test
    fun `the assistant render-cost floor is still 32000 chars`() {
        assertEquals(32_000, LARGE_MESSAGE_THRESHOLD_CHARS)
    }

    // --- the predicate -------------------------------------------------------

    @Test
    fun `a user body just under the threshold renders inline`() {
        assertFalse(shouldCollapseMessage(3_999, isStreaming = false, thresholdChars = 4_000))
    }

    @Test
    fun `the threshold itself still renders inline`() {
        // `>` not `>=`: the boundary belongs to the inline side.
        assertFalse(shouldCollapseMessage(4_000, isStreaming = false, thresholdChars = 4_000))
    }

    @Test
    fun `a user body one char over the threshold folds`() {
        assertTrue(shouldCollapseMessage(4_001, isStreaming = false, thresholdChars = 4_000))
    }

    @Test
    fun `a 15K paste folds`() {
        // The exact size the 2026-11-14 report came from.
        assertTrue(shouldCollapseMessage(14_929, isStreaming = false, thresholdChars = 4_000))
    }

    @Test
    fun `a streaming body never folds even when huge`() {
        // Folding mid-stream would flip on every growth tick.
        assertFalse(shouldCollapseMessage(100_000, isStreaming = true, thresholdChars = 4_000))
    }

    @Test
    fun `the assistant floor still splits at 32000`() {
        // The assistant/tool path must NOT start folding at the user threshold.
        assertFalse(shouldCollapseMessage(32_000, isStreaming = false, thresholdChars = 32_000))
        assertTrue(shouldCollapseMessage(32_001, isStreaming = false, thresholdChars = 32_000))
    }

    // --- wiring --------------------------------------------------------------

    @Test
    fun `the user bubble passes the short threshold to the guard`() {
        // Without this wiring the policy change is invisible: the guard keeps its
        // 32K default and the paste still fills the screen.
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatUserMessageUI.kt",
        )
        // [incr1116-B F-11] Count the call sites: the bubble has TWO guards (no
        // paste boundary, and the folded paste segment). A single `contains`
        // passed while either one lost the argument, i.e. while half the long
        // messages still fell back to the 32K floor.
        assertEquals(
            "both user-bubble guards must pass the short threshold (found " +
                Regex("collapseThresholdChars = USER_MESSAGE_COLLAPSE_THRESHOLD_CHARS").findAll(src).count() +
                ")",
            2,
            Regex("collapseThresholdChars = USER_MESSAGE_COLLAPSE_THRESHOLD_CHARS").findAll(src).count(),
        )
        assertEquals(
            "both user-bubble guards must pass the short preview length",
            2,
            Regex("previewChars = USER_MESSAGE_PREVIEW_CHARS").findAll(src).count(),
        )
        assertTrue(
            "ChatUserMessageUI no longer passes previewChars — the folded user badge " +
                "would preview 8K chars, i.e. it barely folds anything",
            src.contains("previewChars = USER_MESSAGE_PREVIEW_CHARS"),
        )
    }


    @Test
    fun `the folded preview never cuts a surrogate pair in half`() {
        // [incr1116-B F-12] `take(previewChars)` is UTF-16 code units: cutting
        // between the halves of an emoji leaves an orphan high surrogate, which
        // the Text layer renders as U+FFFD right before the ellipsis.
        val emojiAtBoundary = "a".repeat(599) + "\uD83D\uDE00" + "b".repeat(100)
        val preview = foldPreview(emojiAtBoundary, 600)
        assertEquals(
            "the cut must back off one code unit so the pair stays whole",
            "a".repeat(599) + "\u2026",
            preview,
        )
        val pairKept = foldPreview("a".repeat(598) + "\uD83D\uDE00" + "b".repeat(100), 600)
        assertEquals("a".repeat(598) + "\uD83D\uDE00" + "\u2026", pairKept)
    }

    @Test
    fun `the folded preview is the body itself when nothing is cut`() {
        assertEquals("short", foldPreview("short", 600))
        assertEquals(
            "an ellipsis must not appear on a body that fits",
            "\uD83D\uDE00\uD83D\uDE00",
            foldPreview("\uD83D\uDE00\uD83D\uDE00", 600),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (f in listOf(java.io.File(dir, relative), java.io.File(dir, "src/android/app/$relative"))) {
                if (f.exists()) return f.readText()
            }
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
