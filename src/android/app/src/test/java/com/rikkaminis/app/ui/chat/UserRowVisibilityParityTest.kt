package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B-05 deep-audit-1037] Five visibility gates asked a text-only question and
 * the answers disagreed.
 *
 * The audited shape: a user row whose only content is a non-image attachment
 * (no caption) was invisible to every one of the five gates that decide "is
 * this a visible user turn?" — each spelled its own test (`content.isBlank()`,
 * `restoredImageUris.isEmpty()`, an inline JSON walk over parts comparing
 * `type` to "text"). Consequences: the row vanished on session reload
 * (transcript rebuild), retry/edit refused it (ChatViewModel guards), the
 * edit/retry cutoff walks under-counted it so truncation cut the wrong turn,
 * and the queued bubble rendered nothing at all while its withdraw button —
 * gated by the same content-only `if` — disappeared with it.
 *
 * The fix routes all five through the two shared predicates pinned below. The
 * value expectations are literals (evidence discipline); the call-site and
 * structural assertions are placement contracts, which no value-level test can
 * express.
 *
 * Sources:
 *  - `.../ui/chat/ChatTranscriptRebuild.kt` (predicates + rebuild call site)
 *  - `.../ui/chat/ChatUserMessageUI.kt`   (bubble gate + withdraw independence)
 *  - `.../ui/chat/ChatViewModel.kt`       (retry guard + cutoff walk)
 *  - `.../ui/chat/ChatQueueInterruption.kt` (cutoff walk)
 */
class UserRowVisibilityParityTest {

    private val rebuildFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTranscriptRebuild.kt"
    private val uiFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatUserMessageUI.kt"
    private val viewModelFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
    private val queueFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"

    // ── userRowHasVisibleContent: the UI-side verdict ───────────────────────

    @Test
    fun `a caption alone is visible content`() {
        assertTrue(userRowHasVisibleContent("hello", emptyList<String>(), emptyList<String>()))
    }

    @Test
    fun `an image alone is visible content`() {
        assertTrue(userRowHasVisibleContent("", listOf("file://a.png"), emptyList<String>()))
    }

    @Test
    fun `a non-image attachment alone is visible content - the B-05 defect case`() {
        // The exact row that used to vanish: no caption, no image, one file.
        assertTrue(userRowHasVisibleContent("", emptyList<String>(), listOf("file://a.pdf")))
    }

    @Test
    fun `an empty row carries no visible content`() {
        assertFalse(userRowHasVisibleContent("", emptyList<String>(), emptyList<String>()))
    }

    @Test
    fun `a whitespace-only caption is not visible content`() {
        assertFalse(userRowHasVisibleContent("   \n\t ", emptyList<String>(), emptyList<String>()))
    }

    // ── userPartsJsonHasVisibleContent: the DB-side verdict ────────────────

    @Test
    fun `a non-blank text part is a visible user turn`() {
        assertTrue(userPartsJsonHasVisibleContent("""[{"type":"text","value":"hi"}]"""))
    }

    @Test
    fun `a mediaRef part is a visible user turn`() {
        assertTrue(userPartsJsonHasVisibleContent("""[{"type":"mediaRef","value":{"path":"a.pdf"}}]"""))
    }

    @Test
    fun `a mediaRef survives next to a tool result part`() {
        assertTrue(
            userPartsJsonHasVisibleContent(
                """[{"type":"toolResult","value":{"output":"x"}},{"type":"mediaRef","value":{}}]""",
            ),
        )
    }

    @Test
    fun `blank and whitespace text parts are not visible turns`() {
        assertFalse(userPartsJsonHasVisibleContent("""[{"type":"text","value":""}]"""))
        assertFalse(userPartsJsonHasVisibleContent("""[{"type":"text","value":"   "}]"""))
    }

    @Test
    fun `a text part that is only the attachment inventory XML is not visible`() {
        // The XML inventory is display-stripped, so the bubble would be empty.
        assertFalse(
            userPartsJsonHasVisibleContent(
                """[{"type":"text","value":"<user-attached-files>\n- a.pdf\n</user-attached-files>"}]""",
            ),
        )
    }

    @Test
    fun `a real caption after the attachment inventory XML is visible`() {
        assertTrue(
            userPartsJsonHasVisibleContent(
                """[{"type":"text","value":"<user-attached-files>\n- a.pdf\n</user-attached-files>\nhello"}]""",
            ),
        )
    }

    @Test
    fun `the synthetic resume system-reminder row does not count`() {
        // Counting it would shift the retry/edit cutoff one user message early.
        assertFalse(
            userPartsJsonHasVisibleContent(
                """[{"type":"text","value":"<system-reminder>resume</system-reminder>"}]""",
            ),
        )
    }

    @Test
    fun `a toolResult-only row does not count`() {
        assertFalse(
            userPartsJsonHasVisibleContent("""[{"type":"toolResult","value":{"output":"x"}}]"""),
        )
    }

    @Test
    fun `an empty parts array does not count`() {
        assertFalse(userPartsJsonHasVisibleContent("[]"))
    }

    @Test
    fun `a malformed parts blob keeps the pre-fix fail-open semantic`() {
        // Both cutoff walks treated an unparsable row as a visible user turn
        // before the fix; changing that would silently move the cutoff.
        assertTrue(userPartsJsonHasVisibleContent("not json {"))
    }

    // ── the five call sites go through the shared predicates ───────────────

    @Test
    fun `the transcript rebuild asks the shared predicate`() {
        assertTrue(
            readRepoFile(rebuildFile).contains(
                "if (entity.role == \"user\" && !userRowHasVisibleContent(text, restoredImageUris, restoredAttachmentUris)) return@mapNotNull null",
            ),
        )
    }

    @Test
    fun `the retry guard asks the shared predicate`() {
        val vm = readRepoFile(viewModelFile)
        assertTrue(
            vm.contains("!userRowHasVisibleContent(message.content, message.imageUris, message.attachmentUris)"),
        )
    }

    @Test
    fun `both cutoff walks ask the parts-json predicate`() {
        assertTrue(readRepoFile(queueFile).contains("val hasText = userPartsJsonHasVisibleContent(entity.partsJson)"))
        assertTrue(readRepoFile(viewModelFile).contains("val hasText = userPartsJsonHasVisibleContent(entity.partsJson)"))
    }

    @Test
    fun `the queued bubble asks the shared predicate`() {
        assertTrue(
            readRepoFile(uiFile).contains(
                "if (userRowHasVisibleContent(message.content, message.imageUris, message.attachmentUris)) {",
            ),
        )
    }

    // ── the withdraw button is independent of the caption ──────────────────

    @Test
    fun `the withdraw button is not gated by the content predicate`() {
        val ui = readRepoFile(uiFile)
        val rowGate = ui.indexOf("if (userRowHasVisibleContent(message.content, message.imageUris, message.attachmentUris)) {")
        val captionGuard = ui.indexOf("if (message.content.isNotBlank()) {")
        val captionGuardEnd = ui.indexOf("} // end caption-only guard ([B-05 deep-audit-1037])")
        assertTrue("row gate must exist", rowGate >= 0)
        assertTrue("caption guard must exist", captionGuard > rowGate)
        assertTrue("caption guard must be closed", captionGuardEnd > captionGuard)
        // The withdraw GATE is the isQueued occurrence AFTER the caption-only
        // block closes. The first one (inside the block, ChatUserMessageUI.kt
        // :412) is the queued bubble's dashed-state modifier, not the button.
        val withdrawGate = ui.indexOf("if (isQueued && onWithdraw != null) {", captionGuardEnd)
        assertTrue(
            "the withdraw button must sit OUTSIDE the caption-only guard",
            withdrawGate > captionGuardEnd,
        )
        // Mirror of the gate's V3 criterion: the button's own condition
        // (walk back to the nearest `if (` from the contentDescription) must
        // not re-introduce the caption gate.
        val btn = ui.indexOf("chat_withdraw_queued")
        assertTrue("withdraw button must exist", btn > withdrawGate)
        val cond = ui.substring(ui.lastIndexOf("if (", btn), btn)
        assertTrue(
            "the withdraw button's guard must not gate on the caption",
            !cond.contains("content.isNotBlank"),
        )
    }

    // ── the old spellings must not come back ───────────────────────────────

    @Test
    fun `no file re-spells the check as a content-only test`() {
        val rebuild = readRepoFile(rebuildFile)
        assertFalse(
            "the image-only rebuild guard is the audited defect",
            rebuild.contains("entity.role == \"user\" && text.isBlank() && restoredImageUris.isEmpty()"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
