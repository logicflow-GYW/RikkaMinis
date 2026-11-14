package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [r8-C-F04] The `@`-mention commit must not throw on the upper bound.
 *
 * `selectMention` guarded with `anchor > currentText.length`, so `anchor ==
 * length` passed and the next line built `substring(anchor + 1)` →
 * `StringIndexOutOfBoundsException` on the main thread. The reachable case is
 * ordinary: the picker is open against a field ending in `@`, then the text
 * shrinks (backspace / cut / an external ReplaceText) to exactly the anchor
 * position, and the user taps a row or presses Return.
 *
 * `selectMention` is an extension on a ~4.8k-line ViewModel that needs a
 * Context, repositories and a live coroutine scope, so it cannot be
 * instantiated in a JVM unit test. The guard is therefore a pure function
 * ([mentionSpanIsValid]) and this class tests that function directly, PLUS a
 * source-shape assertion that `selectMention` really routes through it — a
 * tested-but-unwired guard would be the exact false protection this batch is
 * about. (Trade-off recorded in fix-reports/FIX-R8-C.md §2.)
 */
class ChatViewModelMentionSelectTest {

    // ── the guard itself: the boundary table ────────────────────────────────

    @Test
    fun `an anchor equal to the text length is rejected`() {
        // The defect: `a` sits at the very end with nothing after it, so the
        // `@` that opened the menu is no longer in the field.
        assertEquals(false, mentionSpanIsValid(anchor = 3, textLength = 3))
        assertEquals(false, mentionSpanIsValid(anchor = 0, textLength = 0))
    }

    @Test
    fun `an anchor past the end or negative is rejected`() {
        assertEquals(false, mentionSpanIsValid(anchor = 4, textLength = 3))
        assertEquals(false, mentionSpanIsValid(anchor = 99, textLength = 3))
        assertEquals(false, mentionSpanIsValid(anchor = -1, textLength = 3))
        assertEquals(false, mentionSpanIsValid(anchor = Int.MIN_VALUE, textLength = 3))
    }

    @Test
    fun `every anchor inside the text is accepted`() {
        assertEquals(true, mentionSpanIsValid(anchor = 0, textLength = 1))
        assertEquals(true, mentionSpanIsValid(anchor = 0, textLength = 3))
        assertEquals(true, mentionSpanIsValid(anchor = 2, textLength = 3))
    }

    /** The last valid anchor is `length - 1`, the first invalid one is `length`. */
    @Test
    fun `the upper bound is exclusive`() {
        val length = 7
        assertTrue(mentionSpanIsValid(length - 1, length))
        assertTrue(!mentionSpanIsValid(length, length))
    }

    // ── wiring: the guard is what selectMention actually consults ───────────

    @Test
    fun `selectMention routes through the pure guard instead of its own comparison`() {
        val body = blockAfter(source(), "internal fun ChatViewModel.selectMention(")
        assertTrue(
            "selectMention must consult mentionSpanIsValid(anchor, currentText.length): $body",
            body.contains("mentionSpanIsValid(anchor, currentText.length)"),
        )
        assertTrue(
            "the off-by-one guard (`anchor > currentText.length`) must be gone",
            !body.contains("anchor > currentText.length"),
        )
        assertTrue(
            "the early return must still return the original text/caret",
            body.contains("return currentText to currentCaret"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Walk up from user.dir so the probe works from the module dir or the repo root. */
    private fun source(): String {
        val relative = "src/main/java/com/rikkaminis/app/ui/chat/ChatViewModelMentionExt.kt"
        // -Dprobe.repo pins the repo root for out-of-tree harnesses (the sandbox
        // JVM arm runs with an unrelated cwd); Gradle leaves it unset and the
        // walk below finds src/android/app from user.dir = <module dir>.
        System.getProperty("probe.repo")?.let { root ->
            val f = File(root, "src/android/app/$relative")
            if (f.isFile) return f.readText()
            throw FileNotFoundException("probe.repo=$root has no $relative")
        }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (prefix in listOf("", "src/android/app/", "app/")) {
                val f = File(dir, prefix + relative)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

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
}
