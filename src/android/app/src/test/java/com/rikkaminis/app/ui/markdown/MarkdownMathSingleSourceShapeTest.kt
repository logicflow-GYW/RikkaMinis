package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [r5-ef / F-F-07, P1] Structural contract: the `$ … $` classifier must have
 * **exactly one implementation**.
 *
 * The bug this prevents is a *drift*, not a wrong rule: `MarkdownParser` and
 * `StreamingMarkdownText` each carried a private `looksLikeMath`, three rounds
 * of currency fixes landed in only one of them, and the streaming renderer
 * showed `$5k-$10k` as KaTeX that snapped back to dollars on settle. A
 * behavioural test cannot see a *third* copy being written tomorrow (it passes
 * until the copies disagree again), so this test pins the structure itself:
 *
 *  1. the logic lives in [MarkdownMathHeuristic] (glyph table + money regex);
 *  2. `MarkdownParser.looksLikeMath` is a delegation, not logic;
 *  3. `StreamingMarkdownText.looksLikeMath` is a delegation, not logic;
 *  4. every other `fun looksLikeMath(` / `isCurrencyShaped(` /
 *     `isCurrencyRangeShaped(` in production sources also delegates (no third
 *     copy, no re-inlined table);
 *  5. the old crude glyph table (the drifted artifact) is gone from the
 *     streaming file.
 */
class MarkdownMathSingleSourceShapeTest {

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }

    private val root = "src/android/app/src/main/java/com/rikkaminis/app"

    /** `[declaration, declaration + 200 chars)` — enough to see a one-line body. */
    private fun declarationWindow(source: String, declaration: String, len: Int = 200): String {
        val at = source.indexOf(declaration)
        assertTrue("declaration not found: $declaration", at >= 0)
        return source.substring(at, minOf(at + len, source.length))
    }

    private fun assertDelegates(window: String, argument: String, where: String) {
        assertTrue(
            "$where must delegate to the shared heuristic (found: ${window.take(120)})",
            "MarkdownMathHeuristic.looksLikeMath($argument)" in window,
        )
        // A re-inlined copy always brings its own table or its own scan loop.
        assertTrue("$where must not carry a glyph table", "charArrayOf" !in window)
        assertTrue("$where must not carry its own scan loop", "for (" !in window)
        assertTrue("$where must not carry the money regex", "MONEY_RANGE_CONTENT" !in window)
    }

    @Test
    fun `the heuristic lives in MarkdownMathHeuristic with its tables`() {
        val s = readRepoFile("$root/ui/markdown/MarkdownMathHeuristic.kt")
        assertTrue("object missing", "object MarkdownMathHeuristic" in s)
        assertTrue("glyph table missing", "NON_CURRENCY_MATH_GLYPHS = charArrayOf" in s)
        assertTrue("math glyph table missing", "MATH_GLYPHS =" in s)
        assertTrue("money regex missing", "MONEY_RANGE_CONTENT = Regex" in s)
        assertEquals(
            "the heuristic must be declared once",
            1,
            Regex("fun looksLikeMath\\(").findAll(s).count(),
        )
    }

    @Test
    fun `parser looksLikeMath delegates instead of deciding`() {
        val s = readRepoFile("$root/ui/markdown/MarkdownParser.kt")
        val window = declarationWindow(s, "private fun looksLikeMath(content: String): Boolean")
        assertDelegates(window, "content", "MarkdownParser.looksLikeMath")
    }

    @Test
    fun `streaming looksLikeMath delegates instead of deciding`() {
        val s = readRepoFile("$root/ui/chat/StreamingMarkdownText.kt")
        val window = declarationWindow(s, "internal fun looksLikeMath(latex: String): Boolean")
        assertDelegates(window, "latex", "StreamingMarkdownText.looksLikeMath")
        // The drifted artifact itself: the old crude table that made streaming
        // answer differently from the settled parser.
        val oldTable = "\"=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ\""
        assertTrue("old crude glyph table must stay deleted", oldTable !in s)
    }

    /**
     * The repo root as [readRepoFile] sees it. In CI, Gradle runs unit tests
     * with `user.dir` = the `app/` module directory, NOT the repo root, so a
     * bare `File("src/...")` resolves to nothing (first CI run taught this:
     * run `40997128602`, `repo layout changed` was a false alarm from the
     * wrong cwd). Walk up like [readRepoFile] and assert the tree really is
     * there — if this ever fails for real, the layout genuinely changed.
     */
    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "src/android/app").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        throw AssertionError("repo root (src/android/app) not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `no third implementation exists in production sources`() {
        val srcRoot = File(repoRoot, root)
        assertTrue("repo layout changed: $root missing", srcRoot.isDirectory)
        val declarations = Regex("fun (looksLikeMath|isCurrencyShaped|isCurrencyRangeShaped)\\(")
        val offenders = mutableListOf<String>()
        srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            declarations.findAll(text).forEach { m ->
                val window = text.substring(m.range.first, minOf(m.range.first + 200, text.length))
                val delegates = "MarkdownMathHeuristic." in window
                val inHeuristic = f.name == "MarkdownMathHeuristic.kt"
                if (!delegates && !inHeuristic) offenders += "${f.path}:${window.take(60)}"
            }
        }
        assertEquals(
            "the classifier must exist ONLY in MarkdownMathHeuristic.kt; offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }
}
