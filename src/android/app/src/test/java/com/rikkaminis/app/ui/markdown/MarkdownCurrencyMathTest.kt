package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-c / B09 F-13, P1] `looksLikeMath` classified by OPERATOR FIRST and by
 * leading digit second, so the leading-digit guard was unreachable for the exact
 * shapes it existed for: `$5-$10` / `$10+$5` / `$5>$3` produced a math span
 * (`"5-"`, `"10+"`, `"5>"`) and both `$` markers disappeared from the sentence.
 *
 * Currency is now ruled out BEFORE the operator scan (`isCurrencyShaped`), and a
 * span that still contains a bare `$` (the residue of a pairing that jumped over
 * a `$` the close rule rejected) is refused outright.
 *
 * Driven through the real `parseWithMath`, not the private heuristic: the user
 * -visible contract is "no MathSpan, and the dollars stay in the paragraph text".
 * Expected values are literals, never derived from the production constants.
 */
class MarkdownCurrencyMathTest {

    private fun spans(input: String): List<String> =
        MarkdownParser.parseWithMath(input).mathSpans.map { it.latex }

    private fun paragraphText(input: String): String {
        val blocks = MarkdownParser.parseWithMath(input).blocks
        assertEquals("a single paragraph was expected (dead literal: 1)", 1, blocks.size)
        return (blocks[0] as MarkdownParser.Block.Paragraph).content
    }

    // ── money shapes: no math span, dollars preserved ─────────────────────

    @Test
    fun `price range with no space around the dash stays text`() {
        assertEquals(emptyList<String>(), spans("cost \$5-\$10 flat"))
        assertEquals("cost \$5-\$10 flat", paragraphText("cost \$5-\$10 flat"))
    }

    @Test
    fun `sum of two prices stays text`() {
        assertEquals(emptyList<String>(), spans("total \$10+\$5 tax"))
        assertEquals("total \$10+\$5 tax", paragraphText("total \$10+\$5 tax"))
    }

    @Test
    fun `price comparison stays text`() {
        assertEquals(emptyList<String>(), spans("compare \$5>\$3 here"))
        assertEquals("compare \$5>\$3 here", paragraphText("compare \$5>\$3 here"))
    }

    @Test
    fun `spaced price range stays text`() {
        assertEquals(emptyList<String>(), spans("Price range: \$5 - \$10 per user"))
    }

    @Test
    fun `decimal price stays text`() {
        assertEquals(emptyList<String>(), spans("price \$5.99 now"))
    }

    @Test
    fun `pairing that jumps over a rejected close is refused`() {
        // The `$` before `10` is preceded by a space, so it is not a close
        // delimiter; the first `$` pairs with the one before `20` and the span
        // content would be "5 - $10 and" — a bare `$` inside a formula.
        assertEquals(emptyList<String>(), spans("range \$5 - \$10 and \$20 done"))
    }

    // ── real math must keep rendering ─────────────────────────────────────

    @Test
    fun `digit-led formula with an equals sign is math`() {
        assertEquals(listOf("1+1=2"), spans("sum \$1+1=2\$ done"))
    }

    @Test
    fun `digit-led formula with latex structure is math`() {
        assertEquals(listOf("2^{10}"), spans("pow \$2^{10}\$ here"))
    }

    @Test
    fun `digit-led formula with a letter is math`() {
        assertEquals(listOf("2n+1"), spans("seq \$2n+1\$ here"))
    }

    @Test
    fun `letter-led formula with an operator is math`() {
        assertEquals(listOf("x+1"), spans("value \$x+1\$ here"))
        assertEquals(listOf("E=mc^2"), spans("eq \$E=mc^2\$ ok"))
    }

    @Test
    fun `single letter variable is math`() {
        assertEquals(listOf("x"), spans("var \$x\$ alone"))
    }

    @Test
    fun `latex command is math`() {
        assertEquals(listOf("\\frac{1}{2}"), spans("frac \$\\frac{1}{2}\$ x"))
    }

    @Test
    fun `display math is unaffected by the currency rule`() {
        assertEquals(listOf("E=mc^2"), spans("\$\$E=mc^2\$\$"))
    }

    // ── invariants the pre-fix code already honoured ──────────────────────

    @Test
    fun `plain amounts separated by text were and are text`() {
        val r = MarkdownParser.parseWithMath("it costs \$5 and \$10 total")
        assertTrue("no math span expected", r.mathSpans.isEmpty())
        assertEquals("it costs \$5 and \$10 total", (r.blocks[0] as MarkdownParser.Block.Paragraph).content)
    }

    @Test
    fun `escaped dollar is not a delimiter`() {
        val r = MarkdownParser.parseWithMath("\\\$5-\$10 is odd")
        assertTrue("escaped \$ must not open a span", r.mathSpans.isEmpty())
    }
}
