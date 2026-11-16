package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/move-to-capsule-empty-1116] The "Move to…" capsule must not outlive the
 * content it offers to move.
 *
 * Layer 1 drives the real gate. Layer 2 pins the two Compose call sites by
 * source shape — the composer cannot be composed in a JVM unit test, so the
 * shape is the only thing a JVM run can witness. Every shape assertion carries
 * an in-test negative control, so a probe that stopped looking fails loudly
 * instead of passing on an empty match.
 */
class MoveToCapsuleGateTest {

    // ─── Layer 1: the decision ────────────────────────────────────────────

    @Test
    fun `share armed with attachments shows the capsule`() {
        assertTrue(MoveToCapsuleGate.shouldShow(shareInjected = true, draftText = "", attachmentCount = 2))
    }

    @Test
    fun `share armed with text shows the capsule`() {
        assertTrue(MoveToCapsuleGate.shouldShow(shareInjected = true, draftText = "hello", attachmentCount = 0))
    }

    @Test
    fun `every shared attachment deleted hides the capsule`() {
        // The regression: the flag stays armed (removeAttachment never clears
        // it), so the pill used to float over an empty draft forever.
        assertFalse(MoveToCapsuleGate.shouldShow(shareInjected = true, draftText = "", attachmentCount = 0))
    }

    @Test
    fun `cleared text with no attachments hides the capsule`() {
        assertFalse(MoveToCapsuleGate.shouldShow(shareInjected = true, draftText = "", attachmentCount = 0))
        assertFalse(MoveToCapsuleGate.shouldShow(shareInjected = true, draftText = "   \n\t ", attachmentCount = 0))
    }

    @Test
    fun `whitespace is not content to move`() {
        assertFalse(MoveToCapsuleGate.canMove("  \n ", 0))
        assertTrue(MoveToCapsuleGate.canMove("  x ", 0))
        assertTrue(MoveToCapsuleGate.canMove("", 1))
    }

    @Test
    fun `an unarmed share never shows the capsule`() {
        // Normal typing / an ordinary attachment must not conjure the pill.
        assertFalse(MoveToCapsuleGate.shouldShow(shareInjected = false, draftText = "hello", attachmentCount = 3))
    }

    @Test
    fun `show implies move`() {
        // The two call sites must agree: a capsule the user can see is always a
        // move that will carry something, and vice versa.
        for (armed in listOf(true, false)) {
            for (text in listOf("", " ", "x")) {
                for (count in listOf(0, 1)) {
                    val canMove = MoveToCapsuleGate.canMove(text, count)
                    val show = MoveToCapsuleGate.shouldShow(armed, text, count)
                    assertTrue("show=$show canMove=$canMove armed=$armed", !show || canMove)
                }
            }
        }
    }

    // ─── Layer 2: the call sites ─────────────────────────────────────────

    @Test
    fun `the capsule is gated on the draft, not on the raw share flag`() {
        val code = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
        // Negative control: the probe is actually reading the file.
        assertTrue(code.contains("internal fun ChatInputArea("))
        assertTrue(
            "capsule must read the gate",
            code.contains("MoveToCapsuleGate.shouldShow("),
        )
        assertFalse(
            "the raw share flag alone must never show the capsule",
            code.contains("val showMoveCapsule by viewModel.hasInjectedShareContent"),
        )
        // Negative control: a token that does not exist cannot be "found".
        assertFalse(code.contains("definitelyNotInThisFile_1116"))
    }

    @Test
    fun `an empty move is refused at the source and never re-arms the target`() {
        val composer = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
        val screen = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt")
        assertTrue(
            "onSelect must refuse an empty draft",
            composer.contains("if (!MoveToCapsuleGate.canMove(inputFieldValue.text, viewModel.attachments.value.size))"),
        )
        assertTrue(
            "the transfer drain must only mark a non-empty transfer",
            screen.contains("if (MoveToCapsuleGate.canMove(transfer.inputText, transfer.attachments.size)) {"),
        )
        // Negative control: the unguarded shape must be gone.
        assertFalse(
            "unguarded markShareInjected in the transfer drain",
            screen.contains("for (a in transfer.attachments) viewModel.addAttachment(a)\n        viewModel.markShareInjected()"),
        )
    }

    @Test
    fun `the capsule label is localised`() {
        val code = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
        assertTrue(code.contains("R.string.move_to_sheet_title"))
        assertFalse("hard-coded English pill label", code.contains("\"Move to…\","))
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Walk up from `user.dir` (Gradle = module dir) until [relative] exists. */
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
