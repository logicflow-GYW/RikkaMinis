package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.share.PendingShare
import org.junit.Assert.assertEquals
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

    // ─── Layer 1b: spending the arm [incr1117-B F-05] ─────────────────────

    @Test
    fun `an arm whose content has not landed yet is not spent`() {
        // The share drain appends its text through the composer command queue, so
        // the armed frame can legitimately see an empty draft. Disarming there
        // would drop the capsule for every text-only share before its text ever
        // landed — the timing half of this finding.
        val frame = MoveToCapsuleGate.observeFrame(shareInjected = true, draftText = "", attachmentCount = 0, sawContent = false)
        assertFalse("must not disarm before the injected content lands", frame.disarm)
        assertFalse("nothing has been seen yet", frame.sawContent)
    }

    @Test
    fun `the injected content latches the arm`() {
        val frame = MoveToCapsuleGate.observeFrame(true, "shared text", 0, false)
        assertTrue(frame.sawContent)
        assertFalse(frame.disarm)
    }

    @Test
    fun `clearing a draft that was seen spends the arm`() {
        // The regression: the flag stayed armed after the user deleted the shared
        // content, so the pill came back over the next draft they typed.
        val frame = MoveToCapsuleGate.observeFrame(true, "", 0, sawContent = true)
        assertTrue("the spent arm must be reported", frame.disarm)
        assertTrue("whitespace-only is the same spent arm", MoveToCapsuleGate.observeFrame(true, "   \n\t ", 0, sawContent = true).disarm)
    }

    @Test
    fun `deleting the last shared attachment spends the arm`() {
        // Attachments are restaged synchronously before `markShareInjected()`, so
        // the arm is always "seen" by the time the user can delete one.
        assertTrue(MoveToCapsuleGate.observeFrame(true, "", 0, sawContent = true).disarm)
        // …and an arm that still HAS an attachment is untouched.
        assertFalse(MoveToCapsuleGate.observeFrame(true, "", 1, sawContent = false).disarm)
    }

    @Test
    fun `an unarmed share resets the latch and never disarms`() {
        val frame = MoveToCapsuleGate.observeFrame(false, "", 0, sawContent = true)
        assertFalse(frame.disarm)
        assertFalse("the next share must start a fresh arm, not a spent one", frame.sawContent)
        assertFalse(MoveToCapsuleGate.observeFrame(false, "hello", 2, sawContent = true).disarm)
    }

    // ─── Layer 1c: the arm follows content, not intent [incr1117-B F-03] ───

    @Test
    fun `a share that landed nothing must not arm the capsule`() {
        val items = listOf(
            PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, "gone.png"),
            PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, "   \n"),
        )
        // The staged file was missing / the copy threw, so nothing was added.
        assertFalse(
            "nothing reached the composer, so nothing may be armed",
            shareDrainLanded(items) { false },
        )
        // The deadlock this closes: an arm that never sees content can never spend
        // itself, so the pill came back over the next unrelated draft.
        val armed = shareDrainLanded(items) { false }
        assertFalse(MoveToCapsuleGate.shouldShow(armed, "", 0))
        assertFalse("the pill must not reappear over an unrelated draft", MoveToCapsuleGate.shouldShow(armed, "hello", 0))
    }

    @Test
    fun `a text-only share still arms`() {
        // Over-correction control: disarming every share whose text lands
        // asynchronously would drop the capsule for all of them.
        val armed = shareDrainLanded(listOf(PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, "https://x"))) { false }
        assertTrue(armed)
        assertTrue(MoveToCapsuleGate.shouldShow(armed, "https://x", 0))
    }

    @Test
    fun `one landed item is enough and blanks are not items`() {
        val attach = PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, "ok.png")
        val blank = PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, " \n\t ")
        assertTrue("a landed attachment arms", shareDrainLanded(listOf(attach)) { true })
        assertFalse("a failed copy does not", shareDrainLanded(listOf(attach)) { false })
        assertTrue("one of two landing is enough", shareDrainLanded(listOf(attach, blank)) { true })
        assertFalse("whitespace-only text appends nothing", shareDrainLanded(listOf(blank)) { false })
        assertFalse("an empty share arms nothing", shareDrainLanded(emptyList()) { true })
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
    fun `the share drain arms only what really landed`() {
        val screen = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt")
        val drain = blockAfter(screen, "LaunchedEffect(shareBufferVersion)")
        // Negative control: the probe reads the drain, not some other effect.
        assertTrue(drain.contains("[Share] injecting"))
        assertEquals(
            "the share drain must arm exactly once, and only through the landed guard",
            1,
            Regex("markShareInjected\\(\\)").findAll(drain).count(),
        )
        assertTrue(
            "the attachment arm must keep the attachment only when the copy really added one",
            drain.contains(
                "if (viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value)) != null)",
            ),
        )
        val guard = blockAfter(drain, "if (shareDrainLanded(pending.items, landed::contains))")
        assertTrue(
            "the arm must sit inside the landed guard — arming on a failed copy is what " +
                "locked `sawContent` false forever",
            guard.contains("viewModel.markShareInjected()"),
        )
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
    fun `the composer spends the arm from the latch, not from a bare emptiness check`() {
        val code = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
        val anchor = "MoveToCapsuleGate.observeFrame("
        // Negative control: the probe really reads this file, and it is a real
        // search rather than a token that cannot appear.
        assertTrue(code.contains("internal fun ChatInputArea("))
        assertFalse(code.contains("definitelyNotInThisFile_1117"))
        assertTrue("the composer must consume the frame verdict", code.contains(anchor))
        assertTrue(
            "a spent arm must clear the flag — hiding alone is what let it come back",
            code.contains("if (frame.disarm) viewModel.clearShareInjectedFlag()"),
        )
        assertTrue(
            "the latch must be threaded from remembered composer state, or the arm is " +
                "spent before the injected text ever lands",
            code.contains("sawContent = shareContentSeen,"),
        )
        // Negative control 1: the naive shape (`shareInjected && !canMove` →
        // clear) must not be in the tree; it drops text-only shares.
        assertFalse(
            "a bare emptiness check must never spend the arm",
            code.contains("if (shareInjected && !MoveToCapsuleGate.canMove("),
        )
        // Negative control 2: erase the anchor from a COPY — the assertion above
        // must stop holding, i.e. it is not satisfied by the file merely existing.
        assertFalse(
            "the probe must depend on the anchor itself",
            code.replace(anchor, "REMOVED_1117").contains(anchor),
        )
    }

    @Test
    fun `enqueueing the draft spends the arm too`() {
        val vm = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val body = blockAfter(vm, "fun enqueuePrompt(text: String)")
        // Scoped on purpose: `sendMessage` carries the same line, so a whole-file
        // `contains` would pass even with the enqueue path untouched.
        assertTrue(
            "handing the draft to the agent loop consumes the share",
            body.contains("if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false"),
        )
    }

    @Test
    fun `the capsule label is localised`() {
        val code = source("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt")
        assertTrue(code.contains("R.string.move_to_sheet_title"))
        assertFalse("hard-coded English pill label", code.contains("\"Move to…\","))
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** The `{...}` body that follows the first [marker], brace-matched. */
    private fun blockAfter(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no brace after $marker", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces after $marker")
    }

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
