package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [r5-f / F-07, P1] Price ranges whose amounts carry a **unit or magnitude
 * suffix** — `$5k-$10k`, `$5M-$10M`, `$10/mo-$20/mo`, `$5B+$2B`.
 *
 * [MarkdownCurrencyMathTest] closed the digit-only forms (`$5-$10`): those are
 * refused by `isCurrencyShaped` because a price never carries a letter. The
 * suffix forms slip past that net — `isCurrencyShaped` bails out on ANY letter
 * (`c.isLetter()`), so `"5k-"` reaches the operator scan, which returns `true`
 * on the `-` before the digit-led guard can refuse it. Both `$` then disappear
 * from the sentence and `5k-` is handed to KaTeX.
 *
 * Expected values are literals, never derived from the production constants, and
 * every case is driven through the real [MarkdownParser.parseWithMath] — the
 * user-visible contract is "no MathSpan, and the dollars stay in the paragraph".
 */
class MarkdownCurrencyRangeTest {

    private fun spans(input: String): List<String> =
        MarkdownParser.parseWithMath(input).mathSpans.map { it.latex }

    private fun paragraphText(input: String): String {
        val blocks = MarkdownParser.parseWithMath(input).blocks
        assertEquals("a single paragraph was expected (dead literal: 1)", 1, blocks.size)
        return (blocks[0] as MarkdownParser.Block.Paragraph).content
    }

    private fun assertMoney(input: String) {
        assertEquals("no math span expected for: $input", emptyList<String>(), spans(input))
        assertEquals("dollars must survive verbatim", input, paragraphText(input))
    }

    private fun assertMath(expectedLatex: String, input: String) {
        assertEquals("math span expected for: $input", listOf(expectedLatex), spans(input))
    }

    // ── money table: suffix-bearing ranges stay text ──────────────────────

    @Test
    fun `thousands range stays text`() {
        assertMoney("cost \$5k-\$10k flat")
    }

    @Test
    fun `millions range stays text`() {
        assertMoney("range \$5M-\$10M ok")
    }

    @Test
    fun `per-month range stays text`() {
        assertMoney("plan \$10/mo-\$20/mo here")
    }

    @Test
    fun `billions sum stays text`() {
        assertMoney("grow \$5B+\$2B total")
    }

    @Test
    fun `currency prefix on the second amount stays text`() {
        assertMoney("tier US\$5k-US\$10k flat")
    }

    @Test
    fun `unit and magnitude together stay text`() {
        assertMoney("seat \$5k/mo-\$10k/mo yearly")
    }

    @Test
    fun `five-digit suffix range stays text`() {
        assertMoney("band \$50k-\$100k wide")
    }

    @Test
    fun `decimal magnitude range stays text`() {
        assertMoney("raise \$1.5M-\$3M round")
    }

    @Test
    fun `cjk unit suffix stays text`() {
        assertMoney("每月 \$10/月-\$20/月 pricing")
    }

    @Test
    fun `spaced suffix range stays text`() {
        assertMoney("Price: \$5k - \$10k per seat")
    }

    // ── math table: the regressions this change must not cause ────────────

    @Test
    fun `letter-led difference is still math`() {
        assertMath("x-y", "diff \$x-y\$ ok")
    }

    @Test
    fun `letter-led sum is still math`() {
        assertMath("a+b", "sum \$a+b\$ ok")
    }

    @Test
    fun `single letter variable is still math`() {
        assertMath("c", "var \$c\$ alone")
    }

    @Test
    fun `digit-led equation is still math`() {
        assertMath("2+2=4", "eq \$2+2=4\$ ok")
        assertMath("1+1=2", "sum \$1+1=2\$ done")
    }

    @Test
    fun `digit-led formula with an interior operator is still math`() {
        assertMath("2n+1", "seq \$2n+1\$ here")
    }

    @Test
    fun `digit-led formula with a superscript is still math`() {
        assertMath("2^{10}", "pow \$2^{10}\$ here")
    }

    @Test
    fun `letter-led formula with caret is still math`() {
        assertMath("E=mc^2", "eq \$E=mc^2\$ ok")
    }

    @Test
    fun `latex command is still math`() {
        assertMath("\\frac{1}{2}", "frac \$\\frac{1}{2}\$ x")
    }

    @Test
    fun `digit-led subtraction of a lowercase variable is still math`() {
        // The narrowest clause of the new rule: a lowercase run after the
        // operator is a variable, not a currency prefix — currency prefixes are
        // written in caps (`US$5k-US$10k`). Widening it swallows this formula.
        assertMath("5-x", "expr \$5-x\$ ok")
    }

    @Test
    fun `display math is unaffected by the range rule`() {
        val r = MarkdownParser.parseWithMath("\$\$E=mc^2\$\$")
        assertEquals(listOf("E=mc^2"), r.mathSpans.map { it.latex })
        assertEquals(true, r.mathSpans[0].isBlock)
    }

    // ── invariants that must not move in either direction ─────────────────

    @Test
    fun `plain amounts separated by text stay text`() {
        assertMoney("it costs \$5 and \$10 total")
    }

    @Test
    fun `digit-led product is text before and after`() {
        // Recorded as the real pre-existing behaviour (a digit-led span with no
        // operator and no LaTeX glyph is refused by the digit guard), NOT as
        // "math": the report's repro list mentions `$5x2$` as math it must keep,
        // which the tree never did.
        assertMoney("rows \$5x2\$ here")
    }

    @Test
    fun `numeric inequality stays text by the recorded trade-off`() {
        assertMoney("cmp \$5 > 3\$ here")
    }

    @Test
    fun `escaped dollar is not a delimiter`() {
        val r = MarkdownParser.parseWithMath("\\\$5k-\$10k is odd")
        assertEquals(emptyList<String>(), r.mathSpans.map { it.latex })
    }
}
