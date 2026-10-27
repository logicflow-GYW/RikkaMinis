package com.rikkaminis.app.ui.chat

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv19-B02] The full-content thinking viewer must keep following its text and
 * its theme, without re-measuring the whole buffer on every recomposition.
 *
 * Bug context: the dialog wraps a native `TextView` in an `AndroidView`, and
 * `AndroidView(factory = …)` opens the node once — so a dialog opened while the
 * block was still streaming showed the snapshot it was born with, and a theme
 * flip left it with the old text colour. The fix is an `update` block (NOT
 * `key(content)`: the node holds >100k chars in this code path, and rebuilding
 * it per appended token would reset the reader's scroll offset and selection).
 * Because `update` runs on every recomposition, re-assigning an identical
 * string would force a full re-measure/re-layout of that buffer, which is what
 * [shouldSyncFullContentText] decides against.
 */
class ThinkingFullContentSyncTest {

    // ── 1. the decision (pure, no Android) ──────────────────────────────

    @Test
    fun `identical text is not written back`() {
        assertFalse(shouldSyncFullContentText("abc", "abc"))
        assertFalse(
            shouldSyncFullContentText(
                "long block that was already handed to the view",
                "long block that was already handed to the view",
            ),
        )
    }

    @Test
    fun `append-only streaming growth is written back`() {
        assertTrue(shouldSyncFullContentText("abc", "abcd"))
        assertTrue(shouldSyncFullContentText("abc", "abc\ndef"))
        assertTrue(shouldSyncFullContentText("", "x"))
    }

    @Test
    fun `a same-length replacement is still written back`() {
        // A length-only comparison would silently keep the stale body here.
        assertTrue(shouldSyncFullContentText("abc", "abd"))
        assertTrue(shouldSyncFullContentText("aaa", "aba"))
    }

    @Test
    fun `a shrinking buffer is written back`() {
        assertTrue(shouldSyncFullContentText("abcdef", "abc"))
        assertTrue(shouldSyncFullContentText("abcdef", ""))
    }

    @Test
    fun `a textless view is always written`() {
        assertTrue(shouldSyncFullContentText(null, ""))
        assertTrue(shouldSyncFullContentText(null, "x"))
    }

    @Test
    fun `the comparison is by content, not by instance`() {
        // TextView keeps the exact CharSequence it was handed, but an
        // equivalent instance must still count as "already shown".
        assertFalse(shouldSyncFullContentText(StringBuilder("abc"), "abc"))
        assertTrue(shouldSyncFullContentText(StringBuilder("abc"), "abcd"))
    }

    // ── 2. the dialog wiring ────────────────────────────────────────────

    @Test
    fun `the thinking dialog syncs content and colour through an update block`() {
        val body = bodyOf(
            stripComments(readRepoFile(UI)),
            "private fun ThinkingFullContentDialog(",
        )
        val factory = body.indexOf("factory = {")
        val update = body.indexOf("update = {")
        assertTrue("the AndroidView factory is gone", factory >= 0)
        assertTrue(
            "the node is created once — an update{} block is what keeps it in sync",
            update >= 0,
        )
        assertTrue("update{} must follow the factory", update > factory)
        val sync = body.substring(update)
        assertTrue(
            "the colour must be re-applied on update (theme flips) — got: ${sync.take(200)}",
            sync.contains("textView.setTextColor(textColor.toArgb())"),
        )
        val decision = sync.indexOf("shouldSyncFullContentText(")
        assertTrue(
            "the text write must be guarded by the decision function",
            decision >= 0,
        )
        assertTrue(
            "the guarded write must actually assign the new content",
            sync.indexOf("textView.text = content") > decision,
        )
    }

    @Test
    fun `the huge thinking buffer is never rebuilt by keying the node on its content`() {
        val body = bodyOf(
            stripComments(readRepoFile(UI)),
            "private fun ThinkingFullContentDialog(",
        )
        assertFalse(
            "key(content) would rebuild the node (and lose the reader's scroll " +
                "offset and selection) on every appended token",
            Regex("""\bkey\s*\(\s*content\s*\)""").containsMatchIn(body),
        )
        assertTrue(
            "the view must stay selectable — that is why the node is recycled",
            body.contains("setTextIsSelectable(true)"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Balanced `{ ... }` body of the first function whose declaration matches [marker]. */
    private fun bodyOf(src: String, marker: String): String {
        val m = src.indexOf(marker)
        assertTrue("marker not found: $marker", m >= 0)
        val open = src.indexOf('{', m)
        assertTrue("no body after: $marker", open >= 0)
        var depth = 0
        for (j in open until src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
        }
        throw IllegalStateException("unbalanced body after: $marker")
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        nest--
                        i += 2
                    } else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        nest++
                        i += 2
                    } else {
                        i++
                    }
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        private const val UI =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatAssistantMessageUI.kt"
    }
}
