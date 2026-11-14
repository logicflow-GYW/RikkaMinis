package com.rikkaminis.app.ui.markdown

import com.rikkaminis.app.ui.chat.collectInlineMathLatex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [R8-B B-F09] A money sentence is not a formula.
 *
 * `$50 for a 3-day pass or $100` pairs (the streaming close rule allows a space
 * before the closing `$`; the settled parser rejects that close, which is a
 * separate divergence) into the content `50 for a 3-day pass or `. That content
 * contains `-`, which is in `MATH_GLYPHS`, so it was rendered as KaTeX and both
 * `$` disappeared from the sentence. The two currency nets cannot see it:
 * `isCurrencyShaped` requires "no letter at all" and `isCurrencyRangeShaped`
 * requires "no spaces" — this is English.
 *
 * The natural-language guard runs BEFORE the glyph scan, and LaTeX (`\`) always
 * wins over it so `\frac{1}{2}` and `\pi r^2` stay math.
 */
class MarkdownMathHeuristicMoneyTest {

    // ── the reported sentence ────────────────────────────────────────────────

    @Test
    fun `a money sentence with a hyphen is not math`() {
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("50 for a 3-day pass or "))
    }

    @Test
    fun `money prose ending at the closing dollar is not math`() {
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("50 for a 3-day pass or"))
    }

    @Test
    fun `neither renderer turns that sentence into a span`() {
        // Both public entry points, same answer: a sentence, not a formula.
        assertEquals(emptyList<String>(), collectInlineMathLatex("It costs \$50 for a 3-day pass or \$100 today"))
        assertEquals(
            emptyList<String>(),
            MarkdownParser.parseWithMath("It costs \$50 for a 3-day pass or \$100 today").mathSpans.map { it.latex },
        )
    }

    // ── formulas that must survive ───────────────────────────────────────────

    @Test
    fun `a two-variable expression is still math`() {
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("x - y"))
    }

    @Test
    fun `a short product is still math`() {
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("x+y"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("x^2"))
    }

    @Test
    fun `latex structure always wins over the prose guard`() {
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("\\frac{1}{2}"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("\\pi r^2"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("\\sum i from 1 to n"))
    }

    @Test
    fun `an equation with numbers is still math`() {
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("1+1=2"))
    }

    // ── existing currency arms must not regress ──────────────────────────────

    @Test
    fun `the r3-c and r5-f currency shapes stay money`() {
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("10+"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5k-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("10/mo-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5B+"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5k-US"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5 - 3"))
    }

    @Test
    fun `a single prose word is left to the old rules`() {
        // One word is not enough evidence of a sentence (a variable named
        // `price` or `total` is legal math), so the guard stays out of it.
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("price"))
    }

    // ── [review-1114 B] function names are formula evidence, not prose ───────

    @Test
    fun `function names are still formulas`() {
        // The prose guard counts 3+ letter ASCII words as sentence evidence,
        // and every math function name is exactly that — so a two-log sum, a
        // product of sines and a maximum over two values were all classified
        // as English prose and lost their `$`. Maths wins on these tokens.
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("log x + log y"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("sin x cos x"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("max a b"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("lim n to inf"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("O(n log n)"))
    }

    @Test
    fun `function names in any case are still formulas`() {
        // Case-insensitive, exact token: `LOG`/`Max` are the same formula
        // tokens, `maximum` is an ordinary word.
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("LOG x + LOG y"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("Max a b"))
    }

    @Test
    fun `an ordinary word that merely starts with a function name is prose`() {
        // `maximum` / `logarithm` are NOT `max` / `log`: exact-token matching
        // is what keeps the table from eating real sentences.
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("maximum value here"))
    }

    @Test
    fun `the money sentence stays prose after the token table`() {
        // Same sentence as the reported regression, re-asserted here so the
        // exclusion table can never be widened into "prose is math".
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("50 for a 3-day pass or "))
    }
}
