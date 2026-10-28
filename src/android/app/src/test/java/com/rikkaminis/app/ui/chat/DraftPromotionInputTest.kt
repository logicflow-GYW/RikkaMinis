package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 C1 / F-01] "New Chat" must not drop a draft that holds only
 * attachments.
 *
 * Three things are pinned here:
 *
 *  1. **Behaviour, real object** — [hasPromotableInput] is the promotion's entry
 *     guard. It has to agree with `send()`, which accepts an attachments-only
 *     turn verbatim ("T180: allow attachments-only sends (no caption). Mirrors
 *     iOS"), and with [shouldSkipCleanup], whose `hasAttachments` bit exists for
 *     the same reason.
 *  2. **Behaviour, real object** — [promoteTitleForDraft] labels the row a
 *     bodyless draft produces: no text means the attachment's file name is the
 *     only thing that says what the row is.
 *  3. **Source shape, real file** — `promoteDraftIfNeeded` cannot run without a
 *     Room database and a Compose host, so the ORDER it reads things in is read
 *     off the compiled source. [guardViolations] is the criterion and
 *     [PRE_FIX_GUARD] is a verbatim transcription of the pre-fix lines, so a
 *     criterion that stops recognising the old shape fails here.
 *
 * Every expectation is a literal — nothing is derived from a production
 * constant, so changing one turns a test red instead of quietly moving the bar.
 */
class DraftPromotionInputTest {

    // ── 1. what counts as promotable input ──────────────────────────────────

    @Test
    fun `an attachments-only draft is promotable`() {
        assertTrue(
            "the images/files are the input: `send()` accepts the same message and the exit " +
                "sweep treats staged attachments as content",
            hasPromotableInput(text = "", stagedAttachments = 2),
        )
    }

    @Test
    fun `text alone is promotable`() {
        assertTrue(hasPromotableInput(text = "看这张", stagedAttachments = 0))
    }

    @Test
    fun `a whitespace-only draft with no attachments is not promotable`() {
        assertFalse(
            "nothing to promote — promoting it would materialise an empty session out of a " +
                "stray space",
            hasPromotableInput(text = "  \n\t ", stagedAttachments = 0),
        )
    }

    // ── 2. the row label of a bodyless draft ────────────────────────────────

    @Test
    fun `text wins the row label`() {
        assertEquals(
            "the body prefix is the label whenever there is a body",
            "帮我看下这张图",
            promoteTitleForDraft(text = "帮我看下这张图", firstAttachmentName = "IMG_0042.jpg"),
        )
    }

    @Test
    fun `a bodyless draft is labelled by its first attachment`() {
        assertEquals(
            "a promoted attachments-only row must not read as an empty 'New Chat' the user " +
                "cannot connect to the images they just attached",
            "IMG_0042.jpg",
            promoteTitleForDraft(text = "", firstAttachmentName = "IMG_0042.jpg"),
        )
    }

    @Test
    fun `the attachment label is trimmed and capped like the text label`() {
        assertEquals(
            "the row label stays short",
            50,
            promoteTitleForDraft(text = "", firstAttachmentName = "影".repeat(120)).length,
        )
        assertEquals(
            "and carries no surrounding whitespace",
            "report.pdf",
            promoteTitleForDraft(text = "   ", firstAttachmentName = "  report.pdf  "),
        )
    }

    @Test
    fun `neither source leaves the label empty for the caller's fallback`() {
        assertEquals(
            "the caller applies its own 'New Chat' fallback; this function must not invent a " +
                "second one",
            "",
            promoteTitleForDraft(text = "", firstAttachmentName = null),
        )
    }

    // ── 3. the real guard's order (source shape) ────────────────────────────

    @Test
    fun `the real promotion reads the staged attachments before its early return`() {
        val violations = guardViolations(promoteBody(source(ENTRY_REL)))
        assertTrue(
            "the promotion must not consult the text alone — that shape drops an " +
                "attachments-only draft and the two New-Chat entrances navigate anyway: " +
                violations,
            violations.isEmpty(),
        )
    }

    @Test
    fun `the pre-fix guard is caught by the criterion`() {
        val violations = guardViolations(PRE_FIX_GUARD)
        assertTrue(
            "the criterion must still recognise the shape this branch was written for: " +
                violations,
            violations.any { it.contains("isBlank") },
        )
        assertTrue(
            "and it must also see that the attachments were read too late: $violations",
            violations.any { it.contains("does not consult the staged attachments") },
        )
    }

    // ── shape probe ─────────────────────────────────────────────────────────

    private fun guardViolations(raw: String): List<String> {
        // Comments quote the old code (that is where this branch's rationale lives),
        // so the criterion reads the code only. `//` inside a string literal would be
        // cut too — no string in this body contains one.
        val body = raw.lineSequence().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at >= 0) line.substring(0, at) else line
        }
        val out = mutableListOf<String>()
        val attachmentsAt = body.indexOf("_attachments.value")
        val guardAt = body.indexOf("hasPromotableInput(")
        if (guardAt < 0) {
            out += "the promotion's entry guard does not consult the staged attachments at all"
        }
        if (attachmentsAt < 0) {
            out += "the promotion never reads _attachments.value"
        } else if (guardAt >= 0 && attachmentsAt > guardAt) {
            out += "the entry guard runs before the attachments are read"
        }
        if (Regex("if\\s*\\(\\s*text\\.isBlank\\(\\)\\s*\\)\\s*return").containsMatchIn(body)) {
            out += "the pre-fix `if (text.isBlank()) return false` guard is still in place"
        }
        return out
    }

    private fun promoteBody(src: String): String {
        val decl = "fun ChatViewModel.promoteDraftIfNeeded("
        val start = src.indexOf(decl)
        if (start < 0) return ""
        val open = src.indexOf("{", start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return ""
    }

    // ── source location (same technique as FastioWiringTest) ────────────────

    private fun source(rel: String): String = sourceFile(rel).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }

    private companion object {
        const val ENTRY_REL = "src/main/java/com/rikkaminis/app/ui/chat/ChatSessionEntryExt.kt"

        /** Verbatim pre-fix guard, `ChatSessionEntryExt.kt` @ main `bfb5b0e6` 116-118. */
        val PRE_FIX_GUARD = """
            val text = composerTextForPromotion(_pendingComposerText.value, _inputText.value)
            if (text.isBlank()) return false
            val stagedAttachments = _attachments.value
        """.trimIndent()
    }
}
