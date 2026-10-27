package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1044 B-05] The composer has ONE writer, and every entrance
 * outside it raises an intent.
 *
 * The defect family: `ChatViewModel.inputText` is a `MutableStateFlow` fed by
 * the composer's 150 ms IME-burst debounce, so it lags what the user sees.
 * Five entrances still resolved the composer body against it:
 *
 *  - the system share injection (`INLINE_TEXT`),
 *  - the cross-session Move-to transfer,
 *  - the selection "add to input" action (`appendToInputText`),
 *  - the message-edit prefill,
 *  - the 'Context Full' restore (`dismissContextExhaustedDialog`).
 *
 * Two failure modes, both observed: an APPEND built on the stale snapshot
 * (the dictation that had already landed in the field was rolled back), and a
 * REPLACE whose new text happened to equal the VM string — an equal write
 * `MutableStateFlow` drops, so the field kept its old text AND the still-live
 * burst flushed its pre-edit snapshot straight over the edit.
 *
 * The arithmetic is pinned on the JVM ([composeAppend] / [composerCaretFor] —
 * pure, in `ComposerTextOps.kt`); the wiring is a source-shape probe, because
 * the intent channel, the field and the burst job all need a composition.
 *
 * Arms are fail-closed: each asserts its own reason, and the pre-fix bodies
 * are quoted verbatim from the baseline (`f94413f5`).
 */
class ComposerCommandHandoffTest {

    private val inputAreaRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatInputArea.kt"
    private val viewModelRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt"

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun read(rel: String): String = repoFile(rel).readText()

    /**
     * Drops `//` and block comments so doc text cannot satisfy an arm.
     *
     * STRING-AWARE on purpose: ChatScreen.kt contains a wildcard mime literal
     * whose text is a slash followed by an asterisk — inside a string literal.
     * A naive stripper treats it as a comment opener and eats the rest of the
     * file (until the next comment terminator), which silently blanks every
     * later line and turns a source probe into a green nothing. The string is
     * kept whole here, and a comment marker inside it never starts a comment.
     */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                sb.append(text, i, stop)
                i = stop
            } else if (c == '"') {
                var j = i + 1
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\') j++
                    j++
                }
                val stop = if (j < text.length) j + 1 else text.length
                sb.append(text, i, stop)
                i = stop
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i)
                i = if (end < 0) text.length else end + 2
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + 1)
        }
        return n
    }

    // ── the arithmetic (pure, JVM-pinned) ──────────────────────────────────

    @Test
    fun `an append onto an empty composer yields the snippet and one trailing space`() {
        assertEquals("bar ", composeAppend(live = "", text = "bar"))
    }

    @Test
    fun `an append onto a non-empty composer inserts exactly one separator`() {
        assertEquals("foo bar ", composeAppend(live = "foo", text = "bar"))
    }

    @Test
    fun `an append never doubles the separator when the draft already ends in one`() {
        // the pre-fix `appendToInputText` normalized this by hand; the shared
        // arithmetic must keep doing it, or "foo " + "bar" becomes "foo  bar".
        assertEquals("foo bar ", composeAppend(live = "foo ", text = "bar"))
    }

    @Test
    fun `an append can keep the tail as it is`() {
        // the share injection uses this: the draft's own trailing newline is
        // part of the pasted paragraph, not noise to trim.
        assertEquals("foo\n bar ", composeAppend(live = "foo\n", text = "bar", trimExistingEnd = false))
    }

    @Test
    fun `an append can carry its own separator and skip the trailing space`() {
        assertEquals(
            "foo\nbar",
            composeAppend(live = "foo", text = "bar", separator = "\n", trailingSpace = false),
        )
    }

    @Test
    fun `the caret of a replace is the end of the new body by default`() {
        assertEquals(3, composerCaretFor("abc", null))
    }

    @Test
    fun `an out of range caret override is clamped into the new body`() {
        // a stale override computed against a longer draft would make
        // TextFieldValue throw (selection past the end of its text).
        assertEquals(3, composerCaretFor("abc", 99))
        assertEquals(0, composerCaretFor("abc", -5))
    }

    // ── the wiring (source shape) ──────────────────────────────────────────

    @Test
    fun `the screen never reads or writes the debounced composer string`() {
        val code = codeOnly(read(screenRel))
        assertEquals(
            "ChatScreen must not read viewModel.inputText — it is the 150 ms-debounced " +
                "snapshot, and every write it built on it was lost or rolled back",
            0,
            count(code, "viewModel.inputText"),
        )
        assertEquals(
            "ChatScreen must not write the composer body itself — it raises intents",
            0,
            count(code, "viewModel.setInputText("),
        )
    }

    @Test
    fun `the screen raises the append and replace intents`() {
        val code = codeOnly(read(screenRel))
        assertTrue(
            "the share / transfer appends must be routed as an intent",
            code.contains("ComposerCommand.AppendText("),
        )
        assertTrue(
            "the message-edit prefill must be routed as an intent",
            code.contains("ComposerCommand.ReplaceText("),
        )
        assertTrue(
            "every intent must reach the channel",
            count(code, "requestComposerCommand(") >= 2,
        )
    }

    @Test
    fun `the two viewmodel entrances raise an intent instead of writing the text`() {
        val code = codeOnly(read(viewModelRel))
        for (name in listOf("fun appendToInputText(", "fun dismissContextExhaustedDialog(")) {
            val at = code.indexOf(name)
            assertTrue("$name not found", at >= 0)
            val body = code.substring(at, minOf(code.length, at + 1200))
            assertTrue(
                "$name must raise a ComposerCommand — it used to resolve the composer body " +
                    "against the debounced copy",
                body.contains("requestComposerCommand("),
            )
            assertTrue(
                "$name must not write setInputText itself",
                !body.contains("setInputText("),
            )
        }
    }

    // ── [deep-audit-1049 B-06] the body funnel's caret rule ────────────────

    @Test
    fun `a funnel rewrite without a caret keeps the true caret`() {
        // The slash and `@` outlets carry no caret intent: they replace the
        // body and the user's caret stays where the field says it is.
        assertEquals(7, composerCommitCaret(null, lastTrueCaretEnd = 7, length = 20))
    }

    @Test
    fun `a funnel rewrite with a caret uses the caller's caret`() {
        assertEquals(3, composerCommitCaret(3, lastTrueCaretEnd = 7, length = 20))
    }

    @Test
    fun `the caret is clamped into the replacement`() {
        // A shift-delete during the 150 ms burst can leave the remembered caret
        // beyond the replacement; TextRange would throw on an out-of-range index.
        assertEquals(5, composerCommitCaret(9, lastTrueCaretEnd = 7, length = 5))
        assertEquals(0, composerCommitCaret(-4, lastTrueCaretEnd = 7, length = 5))
        assertEquals(0, composerCommitCaret(4, lastTrueCaretEnd = 7, length = 0))
    }

    @Test
    fun `every slash outlet rewrites through the body funnel`() {
        val code = codeOnly(read(inputAreaRel))
        for (call in listOf("dismissSlashMenu(", "executeSlashCommand(", "showSlashMenuOverInput(")) {
            var at = code.indexOf(call)
            assertTrue("$call is no longer called from the composer", at >= 0)
            var checked = 0
            while (at >= 0) {
                // The outlet's own arm: the call may be resolved a line above the
                // funnel call (`val next = if (…) … else …`), so the window is the
                // surrounding arm rather than the call expression itself.
                val window = code.substring(maxOf(0, at - 320), minOf(code.length, at + 320))
                assertTrue(
                    "$call must resolve its rewrite through commitComposerBody (a bare " +
                        "setInputText is dropped when the text equals the debounced copy)",
                    window.contains("commitComposerBody("),
                )
                assertTrue(
                    "$call must not write setInputText itself",
                    !window.contains("setInputText("),
                )
                checked++
                at = code.indexOf(call, at + call.length)
            }
            assertTrue("$call call sites found: 0", checked > 0)
        }
    }

    @Test
    fun `the composer executes both text intents against its live field`() {
        val code = codeOnly(read(inputAreaRel))
        assertTrue(
            "the collector must handle the replace intent",
            code.contains("is ComposerCommand.ReplaceText ->"),
        )
        assertTrue(
            "the collector must handle the append intent",
            code.contains("is ComposerCommand.AppendText ->"),
        )
        assertTrue(
            "the append must be resolved against the field's live text",
            code.contains("composeAppend("),
        )
    }
}
