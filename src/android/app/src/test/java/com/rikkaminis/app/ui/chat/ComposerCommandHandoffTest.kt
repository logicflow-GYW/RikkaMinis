package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── the source-shape helpers for the meta-audit-1028 C3 arms ───────────

    /** The [size] characters after [marker] — fail-closed when it moved. */
    private fun windowOf(code: String, marker: String, size: Int): String {
        val at = code.indexOf(marker)
        assertTrue("`$marker` is gone from the composer — re-point this arm", at >= 0)
        return code.substring(at, minOf(code.length, at + size))
    }

    /** The characters between two markers — fail-closed on either side. */
    private fun between(code: String, start: String, end: String): String {
        val a = code.indexOf(start)
        assertTrue("`$start` is gone from the composer — re-point this arm", a >= 0)
        val b = code.indexOf(end, a)
        assertTrue("`$end` no longer follows `$start`", b > a)
        return code.substring(a, b)
    }

    // ── [meta-audit-1028 C3 / F-01] the funnel drains the one-shot caret ────
    //
    // Pre-fix, verbatim from the baseline (`bfb5b0e6`):
    //
    //     val at = composerCommitCaret(caret, lastTrueCaretEnd, text.length)
    //
    // and the four slash outlets above it pass `caret = null`, so the value
    // `showSlashMenuOverInput` / `executeSlashCommand` parks in `_pendingCaret`
    // could never be read: the funnel writes BOTH copies, so the
    // `inputFieldValue.text != inputText` guard on the mirror effect (the only
    // `consumePendingCaret()` reader) is already false when it re-runs.

    @Test
    fun `a slash outlet's pending caret wins over the remembered one`() {
        // "/" tapped over the draft "hello world": showSlashMenuOverInput parks
        // caret 1 and returns "/ hello world" (length 13). The caret belongs
        // AFTER the slash — the composer used to show "/ hello wor|ld" with the
        // caret at the pre-slash index 11.
        assertEquals(1, composerFunnelCaret(null, 1, lastTrueCaretEnd = 11, length = 13))
        // "/web-search " inserted from the menu: caret after the prefix (12),
        // not at the caret the body had before the insert.
        assertEquals(12, composerFunnelCaret(null, 12, lastTrueCaretEnd = 1, length = 12))
    }

    @Test
    fun `an explicit caller caret beats a pending one`() {
        // The mention-completion and caret-tagged intents resolve their own
        // caret; a stale intent must not displace it.
        assertEquals(3, composerFunnelCaret(3, 12, lastTrueCaretEnd = 11, length = 20))
    }

    @Test
    fun `with no pending caret the funnel keeps its old behaviour`() {
        assertEquals(7, composerFunnelCaret(null, null, lastTrueCaretEnd = 7, length = 20))
        // Still clamped into the replacement (TextRange throws past its end).
        assertEquals(5, composerFunnelCaret(null, 99, lastTrueCaretEnd = 7, length = 5))
        assertEquals(0, composerFunnelCaret(null, 3, lastTrueCaretEnd = 7, length = 0))
    }

    @Test
    fun `the body funnel drains the pending caret on every commit`() {
        val body = windowOf(
            codeOnly(read(inputAreaRel)),
            "val commitComposerBody: (String, Int?) -> Unit = {",
            700,
        )
        assertTrue(
            "the funnel must drain `consumePendingCaret()`: it writes both copies, so the " +
                "mirror effect that otherwise reads the intent is skipped — a pending caret " +
                "would strand here and displace the caret on the NEXT external write",
            body.contains("consumePendingCaret()"),
        )
        assertTrue(
            "the drain must feed the caret arithmetic (composerFunnelCaret)",
            body.contains("composerFunnelCaret("),
        )
        assertFalse(
            "the funnel must not resolve the caret from the caller alone any more",
            body.contains("composerCommitCaret(caret,"),
        )
    }

    // ── [meta-audit-1028 C3 / F-03] Shift+Enter is a newline, not a send ────
    //
    // Pre-fix, verbatim from the baseline (`bfb5b0e6`): the key handler returned
    // `false` for Shift+Enter ("so Compose inserts the newline") and the
    // soft-IME intercept had no way to tell that insertion from an IME Enter —
    // `sendOnEnter` made it call `performEnterSend()` on the very newline the
    // user asked for.

    @Test
    fun `a single added newline behind the caret is the enter shape`() {
        assertTrue(isEnterSendEdit("hello", "hello\n", 6))
        // A newline in the middle still sends (the caret is right behind it) —
        // unchanged from the pre-fix heuristic, pinned so the shape does not
        // silently widen.
        assertTrue(isEnterSendEdit("ab", "a\nb", 2))
    }

    @Test
    fun `a pasted block of newlines is not the enter shape`() {
        assertFalse(isEnterSendEdit("hello", "hello\nworld\n", 12))
        assertFalse(isEnterSendEdit("", "one\ntwo", 7))
    }

    @Test
    fun `an ordinary one-character edit is not the enter shape`() {
        assertFalse(isEnterSendEdit("hello", "hellox", 6))
        // A newline added but the caret is NOT behind it (e.g. a programmatic
        // insert above the cursor): the old guard refused, keep refusing.
        assertFalse(isEnterSendEdit("hello", "hello\n", 5))
        assertFalse(isEnterSendEdit("hello", "hello\n", 0))
    }

    @Test
    fun `the enter shape alone cannot separate shift enter from an ime enter`() {
        // Why the fix needs a flag rather than a sharper predicate: Compose
        // inserts the same single '\n' for a hardware Shift+Enter, so both
        // events arrive here identical.
        assertTrue(isEnterSendEdit("hello", "hello\n", 6))
    }

    @Test
    fun `the key handler flags shift enter and the intercept stands down`() {
        val code = codeOnly(read(inputAreaRel))
        val key = windowOf(code, "!event.isShiftPressed", 1000)
        assertTrue(
            "the key handler must flag a hardware Shift+Enter (it returns false so Compose " +
                "inserts the newline; without the flag the intercept below sends instead)",
            key.contains("event.isShiftPressed") && key.contains("shiftEnterNewlinePending = true"),
        )
        val intercept = windowOf(code, "val hardwareShiftEnterNewline = shiftEnterNewlinePending", 500)
        assertTrue(
            "the flag must be consumed on the next change whatever it is — a flag that " +
                "outlives its insertion swallows the following genuine soft Enter",
            intercept.contains("shiftEnterNewlinePending = false"),
        )
        assertTrue(
            "the intercept must stand down for the flagged newline",
            intercept.contains("!hardwareShiftEnterNewline"),
        )
        assertTrue(
            "the intercept must go through the shared predicate",
            intercept.contains("isEnterSendEdit("),
        )
    }

    // ── [meta-audit-1028 C3 / F-04] a clear takes its pickers with it ───────
    //
    // Pre-fix, verbatim from the baseline (`bfb5b0e6`): `clearComposerInput` was
    // the four writes below and nothing else — no `dismissMentionMenu()`, no
    // slash reset, no `savedInputBeforeSlash = null`. A StateFlow-only write is
    // not an `onValueChange`, so the `@`/`/` popups stayed on screen over the
    // empty composer, and the next `DismissSlashMenu` restored the draft that
    // had just been sent.

    @Test
    fun `the clear funnel takes the mention and slash menus with it`() {
        val body = windowOf(codeOnly(read(inputAreaRel)), "val clearComposerInput: () -> Unit = {", 1400)
        assertTrue(
            "the clear must dismiss the @-mention popup",
            body.contains("dismissMentionMenu()"),
        )
        assertTrue(
            "the clear must drop the slash menu's saved original — otherwise the next " +
                "`DismissSlashMenu` restores the message that was just sent",
            body.contains("savedInputBeforeSlash = null"),
        )
        assertTrue(
            "the clear must reset the slash menu filter/visibility",
            body.contains("updateSlashMenuState(\"\")"),
        )
        // Order matters: the saved original goes first, or updateSlashMenuState
        // takes the over-content branch and re-opens the menu on the way out.
        assertTrue(
            "drop `savedInputBeforeSlash` BEFORE `updateSlashMenuState`",
            body.indexOf("savedInputBeforeSlash = null") < body.indexOf("updateSlashMenuState("),
        )
    }

    @Test
    fun `tapping the slash button drops an open mention menu`() {
        val code = codeOnly(read(inputAreaRel))
        val arm = between(code, "ComposerCommand.ToggleSlashMenu ->", "ComposerCommand.DismissSlashMenu ->")
        assertTrue(
            "opening the slash menu over a live `@` token must not leave two pickers stacked",
            arm.contains("dismissMentionMenu()"),
        )
        assertTrue(
            "the dismiss must run before the menu is resolved (a resolution that prepends " +
                "`/ ` over a mention token would keep the mention menu alive underneath)",
            arm.indexOf("dismissMentionMenu()") < arm.indexOf("showSlashMenuOverInput("),
        )
    }

    // ── [meta-audit-1028 C3 / F-05] the burst delta is measured on the field ─
    //
    // Pre-fix, verbatim from the baseline (`bfb5b0e6`):
    //
    //     if (inputText != tfv.text) {
    //         if (shouldDebounceImeBurst(inputText, tfv.text)) { … }
    //         else { viewModel.setInputText(tfv.text); … }
    //     } else { viewModel.updateMentionMenuState(…) }
    //
    // `inputText` is the 150 ms-debounced ViewModel copy, so after a debounced
    // paste it still holds the PRE-paste text.

    @Test
    fun `the burst delta must be measured against the field not the debounced copy`() {
        // Paste lands in the field, the ViewModel is still empty → the paste
        // itself is (correctly) a burst.
        assertTrue(shouldDebounceImeBurst("", "hello world!"))
        // One more char typed against the FIELD: +1, commit immediately.
        assertFalse(shouldDebounceImeBurst("hello world!", "hello world!@"))
        // The same keystroke measured against the debounced copy: still "a
        // burst", i.e. one 150 ms stall per keystroke after every paste — the
        // defect, pinned as a literal so the comparison base cannot drift back.
        assertTrue(shouldDebounceImeBurst("", "hello world!@"))
    }

    @Test
    fun `the debounce decision reads the previous field text`() {
        val code = codeOnly(read(inputAreaRel))
        assertTrue(
            "the delta must be measured on the previous FIELD text (the authority)",
            code.contains("shouldDebounceImeBurst(previousFieldText,"),
        )
        assertFalse(
            "…never on the debounced ViewModel copy",
            code.contains("shouldDebounceImeBurst(inputText,"),
        )
    }

    @Test
    fun `a buffered burst cannot outlive an edit back to the debounced copy`() {
        val code = codeOnly(read(inputAreaRel))
        val onValueChange = between(code, "onValueChange = { tfv ->", "modifier = Modifier")
        assertTrue(
            "`text == the debounced copy` is NOT `nothing changed` while a burst is " +
                "buffered — cutting the paste back to it used to skip every cleanup",
            onValueChange.contains("if (inputText != tfv.text || imeBurstBuffer != null)"),
        )
        assertTrue(
            "both non-debounce exits must drop the buffered snapshot (nothing else " +
                "cancels the flush job on that path); found " +
                "${count(onValueChange, "cancelPendingImeBurst()")}",
            count(onValueChange, "cancelPendingImeBurst()") >= 2,
        )
    }
}
