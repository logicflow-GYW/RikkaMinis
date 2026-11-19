package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r3-data-1119 F-15] The `@JavascriptInterface` callback must not write
 * Compose state from the JavaBridge thread.
 *
 * `AndroidBridge.onRendered` runs on Chromium's JavaBridge thread, not the
 * main thread. Its success path already hopped with `post { }`, but the three
 * error early-returns wrote the `MutableState` `renderError` directly: a write
 * racing snapshot commit / recomposition (IllegalStateException at worst, a
 * silently lost error at best — and a lost error leaves the row stuck on the
 * blank placeholder Box, which is what the user sees).
 *
 * The gate judges the DECISION, not the spelling: every assignment to
 * `renderError` inside `onRendered` must sit inside a `post { }` block. The
 * `onRendererGone` write is deliberately out of scope — that is a
 * WebViewClient callback, which the framework delivers on the main thread.
 */
class KatexBridgeThreadHopTest {

    private fun sourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/android/app/src/main/java/com/rikkaminis/app/ui/markdown/KaTeXView.kt",
            )
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("KaTeXView.kt not found from ${System.getProperty("user.dir")}")
    }

    /** Blank out comments and string literals (quotes preserved). */
    private fun stripCommentsAndStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var state: String? = null
        while (i < text.length) {
            val c = text[i]
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when (state) {
                null -> when {
                    two == "//" -> { state = "line"; i += 2 }
                    two == "/*" -> { state = "block"; i += 2 }
                    c == '"' -> { state = "str"; out.append(' '); i += 1 }
                    c == '\'' -> { state = "chr"; out.append(' '); i += 1 }
                    else -> { out.append(c); i += 1 }
                }
                "line" -> {
                    if (c == '\n') { state = null; out.append('\n') }
                    i += 1
                }
                "block" -> if (two == "*/") { state = null; i += 2 } else i += 1
                "str" -> when {
                    c == '\\' && i + 1 < text.length -> { out.append("  "); i += 2 }
                    c == '"' -> { state = null; out.append(' '); i += 1 }
                    else -> { out.append(' '); i += 1 }
                }
                "chr" -> when {
                    c == '\\' && i + 1 < text.length -> { out.append("  "); i += 2 }
                    c == '\'' -> { state = null; out.append(' '); i += 1 }
                    else -> { out.append(' '); i += 1 }
                }
                else -> i += 1
            }
        }
        return out.toString()
    }

    private fun braceEnd(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        throw AssertionError("unbalanced brace at $open")
    }

    private fun functionBody(source: String, signature: String): Pair<Int, String> {
        val at = source.indexOf(signature)
        assertTrue("`$signature` not found", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("`$signature` has no body", open > 0)
        val end = braceEnd(source, open)
        return at to source.substring(open, end + 1)
    }

    @Test
    fun `every renderError write inside onRendered hops to the main thread`() {
        val clean = stripCommentsAndStrings(sourceFile().readText())
        val (at, body) = functionBody(clean, "fun onRendered(width: Int, height: Int, error: String, token: Long)")

        // The callback's own span in the ORIGINAL text, so a `post { }` in a
        // sibling function cannot vouch for it.
        val writes = Regex("""renderError\s*=(?!=)""").findAll(body).toList()
        assertTrue("expected at least one renderError write in onRendered", writes.isNotEmpty())

        // Every `post {` block inside the callback, as index ranges.
        val posts = Regex("""\bpost\s*\{""").findAll(body).map {
            val open = body.indexOf('{', it.range.first)
            open to braceEnd(body, open)
        }.toList()
        assertTrue("onRendered must use post { } to reach the main thread", posts.isNotEmpty())

        for (w in writes) {
            val inside = posts.any { (s, e) -> w.range.first in s..e }
            assertTrue(
                "a renderError write at offset ${w.range.first} of onRendered is OUTSIDE " +
                    "every post { } block — the JavaBridge thread is writing Compose " +
                    "state directly (F-15)",
                inside,
            )
        }
        assertTrue("the callback itself was found", at >= 0)
    }

    @Test
    fun `the error hops still re-check the release latch`() {
        val clean = stripCommentsAndStrings(sourceFile().readText())
        val (_, body) = functionBody(clean, "fun onRendered(width: Int, height: Int, error: String, token: Long)")

        // The pre-existing S-07 contract: nothing meaningful may run on a
        // released renderer. Hopping to main must not drop that check, because
        // the row can be torn down between the callback and the hop. Judged
        // PER BLOCK (a whole-body count would be satisfied by the callback's
        // own top-level check plus any one hop that still has it).
        val posts = Regex("""\bpost\s*\{""").findAll(body).map {
            val open = body.indexOf('{', it.range.first)
            body.substring(open + 1, braceEnd(body, open))
        }.toList()
        assertTrue("onRendered must use post { } to reach the main thread", posts.isNotEmpty())

        for ((index, block) in posts.withIndex()) {
            val firstStatement = block.trimStart().take(120)
            assertTrue(
                "post { } #$index does not open with a release-latch re-check " +
                    "(starts: `${firstStatement.take(60)}`) — the row may be torn " +
                    "down between the bridge callback and the hop (S-07)",
                firstStatement.startsWith("if (!releaseLatch.canRunDeferred()) return@post"),
            )
        }
    }
}
