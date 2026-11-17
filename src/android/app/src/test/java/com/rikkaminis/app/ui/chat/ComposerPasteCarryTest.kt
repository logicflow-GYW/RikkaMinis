package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [incr1118 D1-F02 / D2-F02] The two "which paste chips survive a composer
 * handoff" decisions, and the wiring that applies them.
 *
 * Both defects share one shape: a block of text the user pasted lived in a
 * buffer that was about to be emptied, and the copy of it nobody looked at was
 * the only one left.
 *
 *  - **D1-F02** exiting edit mode restored the draft's chips and then ran the
 *    composer's clear funnel, which drops the buffer (audit P1-C) — the draft's
 *    pastes were destroyed while its attachments survived.
 *  - **D2-F02** "Move to…" shipped inputText + attachments only. The source
 *    expands inline markers into the text first, so every block left in the
 *    buffer was one the text did NOT carry (detached paste / orphan row): those
 *    were dropped on the floor when the source cleared itself.
 *
 * Rule layer: [pastesAfterCancelEdit] (keep inline only while its literal is
 * still in the field) and [pastesCarriedByMove] (carry what the text does not;
 * never carry the same block twice). Shape layer: the call sites apply them.
 */
class ComposerPasteCarryTest {

    private fun paste(id: Int, inline: Boolean, body: String = "block$id") =
        PastedText(id = id, text = body, inlineMarker = inline)

    // ── D1-F02: cancelEdit restores usable chips ────────────────────────────

    @Test
    fun `exit edit keeps an inline chip inline when its literal is still typed`() {
        val snapshot = listOf(paste(1, inline = true))
        val restored = pastesAfterCancelEdit(snapshot, liveText = "please review [Pasted#1]")
        assertEquals(1, restored.size)
        assertTrue("literal present → still expandable inline", restored[0].inlineMarker)
    }

    @Test
    fun `exit edit demotes an inline chip whose literal the clear removed`() {
        val snapshot = listOf(paste(1, inline = true))
        val restored = pastesAfterCancelEdit(snapshot, liveText = "")
        assertEquals(1, restored.size)
        assertFalse(
            "no literal in the field → inline arm could never expand; demote to detached",
            restored[0].inlineMarker,
        )
    }

    @Test
    fun `exit edit leaves detached chips detached and loses none`() {
        val snapshot = listOf(paste(1, inline = true), paste(2, inline = false))
        val restored = pastesAfterCancelEdit(snapshot, liveText = "")
        assertEquals("neither chip may be dropped", 2, restored.size)
        assertEquals(listOf(1, 2), restored.map { it.id })
        assertTrue("bodies intact", restored.all { it.text.isNotEmpty() })
    }

    @Test
    fun `exit edit does not demote on a coincidental id`() {
        // #1 must not be satisfied by #10's literal: the placeholder carries
        // its own closing bracket, so containment is exact.
        val snapshot = listOf(paste(1, inline = true))
        val restored = pastesAfterCancelEdit(snapshot, liveText = "[Pasted#10]")
        assertFalse(restored[0].inlineMarker)
    }

    // ── D2-F02: move-to carries the blocks the text cannot ──────────────────

    @Test
    fun `move carries the detached paste that the text does not reference`() {
        val pastes = listOf(paste(1, inline = false, body = "REPORT"))
        val carried = pastesCarriedByMove(pastes, movedText = "Summarize this")
        assertEquals(1, carried.size)
        assertEquals("REPORT", carried[0].text)
        assertFalse("target has no literal for it → detached", carried[0].inlineMarker)
    }

    @Test
    fun `move does not carry a block whose literal is still in the text`() {
        // `expandPastedText()` resolves (and consumes) every literal it CAN, so
        // a literal that survives means the text already speaks for that row.
        // Carrying the block as well would put it in the target twice: once as
        // the literal on screen, once appended after the body on send.
        val pastes = listOf(paste(1, inline = true, body = "REPORT"))
        val carried = pastesCarriedByMove(pastes, movedText = "Please review [Pasted#1]")
        assertTrue("the literal already represents it", carried.isEmpty())
    }

    @Test
    fun `move carries an orphan row whose literal the user deleted`() {
        val pastes = listOf(paste(3, inline = true, body = "MATERIAL"))
        val carried = pastesCarriedByMove(pastes, movedText = "just a caption")
        assertEquals(1, carried.size)
        assertFalse("no literal survives → detached on the target", carried[0].inlineMarker)
    }

    @Test
    fun `move with an empty draft carries nothing and invents nothing`() {
        assertTrue(pastesCarriedByMove(emptyList(), movedText = "").isEmpty())
    }

    // ── SHAPE: the call sites apply the rules ───────────────────────────────

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
            "repo file not found walking up from ${System.getProperty("user.dir")}",
        )
    }

    private fun chatSrc(name: String): String = repoFile(
        "src/main/java/com/rikkaminis/app/ui/chat/$name",
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/$name",
    ).readText()

    @Test
    fun `PendingTransfer declares the carried-blocks field`() {
        val src = chatSrc("ChatViewModelStore.kt")
        assertTrue(
            "PendingTransfer must carry the folded blocks the text cannot",
            src.contains("val carriedPastes: List<PastedText>"),
        )
    }

    @Test
    fun `move-to stash fills the carried blocks from the live buffer`() {
        val src = chatSrc("ChatInputArea.kt")
        val at = src.indexOf("stashPendingTransfer(")
        assertTrue("stash call site missing", at >= 0)
        val call = src.substring(at, minOf(src.length, at + 900))
        assertTrue(
            "the stash must carry the buffer, or the clear right after it destroys the blocks",
            call.contains("carriedPastes = viewModel.pastesForMove("),
        )
    }

    @Test
    fun `move-to drain asks the composer to restore the blocks`() {
        val src = chatSrc("ChatScreen.kt")
        assertTrue(
            "the transfer's folded blocks must reach the target composer as an intent",
            src.contains("ComposerCommand.RestoreDraftPastes(transfer.carriedPastes)"),
        )
        // The buffer is the composer's: this screen must never write it.
        assertFalse(
            "ChatScreen owns no paste state (PasteFoldWiringTest pins the same rule)",
            src.contains("stashPastedText"),
        )
    }

    @Test
    fun `the composer restores the carried blocks as detached chips`() {
        val src = chatSrc("ChatInputArea.kt")
        val at = src.indexOf("is ComposerCommand.RestoreDraftPastes ->")
        assertTrue("the composer must consume the restore intent", at >= 0)
        val block = src.substring(at, minOf(src.length, at + 200))
        assertTrue(
            "restoration must go through the demoting helper (detached rows)",
            block.contains("restoreDraftPastesAfterClear("),
        )
    }

    @Test
    fun `exit-edit restores through the helper, not the raw snapshot`() {
        val src = chatSrc("ChatViewModel.kt")
        val at = src.indexOf("fun cancelEdit()")
        assertTrue(at >= 0)
        val body = src.substring(at, minOf(src.length, at + 2_000))
        assertTrue(
            "cancelEdit must apply pastesAfterCancelEdit (inline chips lose their literal on exit)",
            body.contains("pastesAfterCancelEdit("),
        )
    }
}
