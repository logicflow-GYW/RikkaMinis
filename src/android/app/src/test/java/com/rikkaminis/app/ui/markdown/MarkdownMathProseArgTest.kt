package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [incr1117-B F-06 / F-11, P1+P2] The two shapes the previous call rule got
 * wrong, pinned together because they are the SAME decision read from opposite
 * sides.
 *
 * **B5 (still open after [incr1117-B F-06])** — `sin(alpha) + cos(beta) +
 * tan(gamma)`. Only the function NAME was exempted from the word count; the
 * arguments inside the parentheses still counted. Three arguments of 3+ letters
 * is exactly "three words, one of them long", so a formula was called a
 * sentence and both `$` vanished.
 *
 * **B4 (same rule, new shape)** — `100 + min(fee) total `. Dropping `min(` from
 * the count left two words, under the three-word gate, so a money sentence fell
 * through to the operator scan and its `-`/`+` made the span KaTeX. Same family
 * as backlog §CD F-11 (`10 min - 20 max or`).
 *
 * The rule that fixes both without a second patch:
 *  1. a function call — `name(` plus its BALANCED parenthesis group, arguments,
 *     commas and nested calls included — is ONE structural unit: it is not a
 *     word and cannot produce the long word (fixes B5);
 *  2. prose has an independent signal: an English function word
 *     (`the/for/of/and/or/...`) anywhere in the span means a sentence,
 *     whatever the word count (fixes B4). The three-word + long-word gate
 *     stays as the safety net for `velocity + acceleration` style spans.
 *
 * Every expectation is a literal — never derived from the constants under test,
 * so a mutated constant cannot move its own expectation.
 */
class MarkdownMathProseArgTest {

    private fun math(content: String) =
        assertEquals("expected MATH for <$content>", true, MarkdownMathHeuristic.looksLikeMath(content))

    private fun text(content: String) =
        assertEquals("expected TEXT for <$content>", false, MarkdownMathHeuristic.looksLikeMath(content))

    // ── B5: a call with a parenthesised, multi-letter argument is math ──────

    @Test
    fun `a sum of three calls with multi-letter arguments is math`() {
        // The reported regression: words=3 (alpha/beta/gamma) with a long word
        // ⇒ prose ⇒ both `$` disappeared from a real formula.
        math("sin(alpha) + cos(beta) + tan(gamma)")
    }

    @Test
    fun `a definition whose head is an ordinary identifier is math`() {
        // `f(` is not in the function-name table — the call shape, not the
        // name, is what makes it structural.
        math("f(x) = sin(theta) + cos(phi)")
    }

    @Test
    fun `a call with several comma separated arguments is math`() {
        math("max(err, tol, eps)")
    }

    @Test
    fun `the already fixed two-call formula stays math`() {
        math("sin(theta)+cos(theta)")
    }

    @Test
    fun `nested calls are math`() {
        math("max(min(a, b), c)")
    }

    @Test
    fun `an unbalanced call still renders instead of being called prose`() {
        // Fail-safe: a half-typed stream (`sin(x + 1`) is not a balanced group,
        // so the head token keeps counting — and the `+` still makes it math.
        math("sin(x + 1")
    }

    // ── B4: a function word outside the calls means prose ───────────────────

    @Test
    fun `a money sentence with a min call and a trailing function word is text`() {
        // Reported: `min(` is exempt ⇒ words=2 ⇒ under the old gate ⇒ math, and
        // KaTeX swallowed both `$` of a price sentence.
        text("100 + min(fee) total ")
    }

    @Test
    fun `a unit abbreviation call does not rescue the sentence`() {
        text("50 for 30-min(or) ")
    }

    @Test
    fun `the older money sentences stay text`() {
        text("50 for a 3-day pass or ")
        text("10 min - 20 max or")
    }

    @Test
    fun `a function word inside the parentheses is still prose evidence`() {
        // The call group must NOT hide prose: if the group swallowed its own
        // content for the function-word test, `the` would vanish and this
        // sentence would fall through to the `+` scan and render as KaTeX.
        text("x + sin(the angle) + 3")
        // Same sentence as the [incr1117-B F-06] negative control, kept here so
        // both paths (inside the group, outside it) are pinned.
        text("x + sin(the angle) of a circle")
    }

    // ── boundaries: semantics deliberately unchanged ────────────────────────

    @Test
    fun `currency shapes are still money`() {
        text("5-")
        text("5k-")
        text("10/mo-")
    }

    @Test
    fun `a word-free call expression is still math`() {
        math("log x + log y")
        math("max a b")
    }

    @Test
    fun `two long words with no function word stay math`() {
        // Known trade-off, unchanged: the count gate needs three words, so a
        // bare sum of two named quantities still renders.
        math("velocity + acceleration")
    }

    @Test
    fun `latex still wins over every prose signal`() {
        math("\\frac{1}{2}")
        math("x^2 + y")
        math("1+1=2")
    }
}
