package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/fold-paste-split-1114] The prompt/paste boundary and the reversible fold.
 *
 * Two defects this pins (both reported from the device, 2026-11-14):
 *
 *  1. Folding swallowed the PROMPT. The bubble folded the whole body, so a
 *     48-char instruction plus a 14,929-char paste became one card whose 600-char
 *     preview was the instruction followed by paste — the user could not see
 *     what they had asked. Fix: persist where the pasted block starts, render
 *     the prompt in full, fold only the material.
 *
 *  2. Expansion was a ONE-WAY latch. `expanded` was set true and never back, so
 *     one accidental tap committed the bubble to a wall of text for the rest of
 *     the session (`rememberSaveable`).
 *
 * Expected values are literals, never derived from the constants under test — a
 * derived expectation moves with the constant and turns a real regression into
 * a PASS.
 */
class MessageBodySplitTest {

    // ── the split itself ─────────────────────────────────────────────────────

    @Test
    fun `a body splits at the persisted offset`() {
        val prompt = "分析一下这段日志"
        val paste = "x".repeat(14_929)
        // The offset points at the FIRST CHARACTER of the pasted block, i.e.
        // just past the "\n\n" that appendUnlinkedPastes wrote.
        val content = "$prompt\n\n$paste"
        val split = splitMessageBody(content, pasteStartIndex = prompt.length + 2)
        assertEquals(prompt, split.prompt)
        assertEquals(paste, split.paste)
    }

    @Test
    fun `the join separator never leaks into the prompt`() {
        // appendUnlinkedPastes joins with "\n\n"; the prompt must render without
        // trailing blank lines, which would show as a gap under the bubble.
        val split = splitMessageBody("hello\n\nworld", pasteStartIndex = 7)
        assertEquals("hello", split.prompt)
        assertEquals("world", split.paste)
    }

    @Test
    fun `a null offset degrades to one body`() {
        val content = "y".repeat(20_000)
        val split = splitMessageBody(content, pasteStartIndex = null)
        assertEquals(content, split.prompt)
        assertNull(split.paste)
    }

    @Test
    fun `an out-of-range offset degrades to one body instead of losing text`() {
        // A damaged index must never cut the string and drop the tail silently —
        // degrading to "fold everything" hides nothing that was not hidden before.
        //
        // The "a prompt must be ≥1 char" policy lives in the CALLER (sendMessage
        // rejects an offset < 2, i.e. less than the joiner plus one prompt char);
        // this function only refuses values it cannot honour at all.
        val content = "short body"
        for (bad in listOf(0, -1, content.length, content.length + 5, 9_999)) {
            val split = splitMessageBody(content, pasteStartIndex = bad)
            assertEquals("offset=$bad must not cut", content, split.prompt)
            assertNull("offset=$bad must not split", split.paste)
        }
    }

    @Test
    fun `the two halves always cover the whole body`() {
        val content = "abc\ndef\n\n" + "PASTE".repeat(500)
        val at = content.indexOf("PASTE")
        val split = splitMessageBody(content, at)
        // prompt (after trimming the joiner) + the 2 joiner chars + paste.
        assertEquals(content.length, split.prompt.length + 2 + split.paste!!.length)
    }

    // ── wiring: the bubble must use the split ────────────────────────────────

    @Test
    fun `the user bubble splits instead of folding the whole body`() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatUserMessageUI.kt",
        )
        assertTrue(
            "ChatUserMessageUI no longer calls splitMessageBody — the prompt would be " +
                "folded into the paste card again, which is the exact defect this fixes",
            src.contains("splitMessageBody(message.content, message.pasteStartIndex)"),
        )
        assertTrue(
            "the paste segment must have its own stable key, or folding the material " +
                "would also hide the prompt (shared expand state)",
            src.contains("\"paste:\${message.id}\""),
        )
        assertTrue(
            "the bare \"user:${'$'}{message.id}\" key must remain for the no-paste arm",
            src.contains("\"user:\${message.id}\""),
        )
    }

    @Test
    fun `the guard renders a collapse control once expanded`() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/LargeContentGuard.kt",
        )
        assertTrue(
            "expansion has no way back — `expanded` was write-once, so one tap on a " +
                "15K paste committed the bubble to a wall of text for the whole session",
            src.contains("onCollapse = { expanded = false }"),
        )
        assertTrue(
            "the collapse control must be rendered inside the expanded branch",
            src.contains("if (expanded) {"),
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
