package com.rikkaminis.app.ui.markdown

import com.rikkaminis.app.ui.chat.collectInlineMathLatex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [r5-ef / F-F-07, P1] **Differential**: the settled parser and the streaming
 * renderer must classify the same `$ … $` text the same way.
 *
 * The bug this pins was a drift, not a wrong rule: `MarkdownParser` had learned
 * three currency lessons ([r3-c] operator-order + `$` residue, [r5-f] unit
 * suffixes) while `StreamingMarkdownText` kept the older crude glyph scan, so
 * `$5k-$10k` streamed as KaTeX and snapped back to dollars once the message
 * settled — two renderings of one sentence.
 *
 * Each case asserts BOTH entry points against a literal expectation, not just
 * against each other: "both wrong in the same way" would satisfy a pure parity
 * assertion, and that is exactly how this kind of drift survives. Expectations
 * are literals — never derived from [MarkdownMathHeuristic] constants.
 */
class MarkdownMathParityTest {

    private data class Case(val input: String, val expectedSpans: List<String>)

    private fun staticSpans(input: String): List<String> =
        MarkdownParser.parseWithMath(input).mathSpans.map { it.latex }

    private fun check(case: Case) {
        assertEquals(
            "settled parser disagreed with the table for: ${case.input}",
            case.expectedSpans,
            staticSpans(case.input),
        )
        assertEquals(
            "streaming renderer disagreed with the table for: ${case.input}",
            case.expectedSpans,
            collectInlineMathLatex(case.input),
        )
        // The contract, stated directly: one sentence, one answer.
        assertEquals(
            "streaming and settled rendering diverged for: ${case.input}",
            staticSpans(case.input),
            collectInlineMathLatex(case.input),
        )
    }

    /** The five shapes that used to diverge: streaming said math, settled said money. */
    @Test
    fun `suffix-bearing price ranges read as money on both paths`() {
        listOf(
            "cost \$5-\$10 flat",
            "cost \$5k-\$10k flat",
            "range \$5M-\$10M ok",
            "plan \$10/mo-\$20/mo here",
            "grow \$5B+\$2B total",
            "US\$5k-US\$10k per seat",
        ).forEach { check(Case(it, emptyList())) }
    }

    /** Real formulas keep rendering on both paths — the fix must not eat math. */
    @Test
    fun `real formulas read as math on both paths`() {
        check(Case("value \$x\$ here", listOf("x")))
        check(Case("equals \$1+1=2\$ plain", listOf("1+1=2")))
        check(Case("power \$x^2\$ here", listOf("x^2")))
        check(Case("frac \$\\frac{1}{2}\$ done", listOf("\\frac{1}{2}")))
        check(Case("sum \$x+y\$ here", listOf("x+y")))
        check(Case("greek \$\\pi\$ here", listOf("\\pi")))
    }

    /** A span left holding a stray `$` is pairing residue, not a formula. */
    @Test
    fun `residue span stays text on both paths`() {
        check(Case("It costs \$5 - \$10 and \$20 total", emptyList()))
    }

    /** Dollar-less prose is untouched by either path. */
    @Test
    fun `plain prose yields no spans on both paths`() {
        check(Case("no money here at all", emptyList()))
        check(Case("single \$5 price", emptyList()))
    }

    /** Mirror check on the shared object itself (cheap, catches const-table edits). */
    @Test
    fun `shared heuristic answers the divergence table directly`() {
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5k-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("10/mo-"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5B+"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath("5k-US"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("x"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("x+y"))
        assertEquals(true, MarkdownMathHeuristic.looksLikeMath("1+1=2"))
        assertEquals(false, MarkdownMathHeuristic.looksLikeMath(""))
    }
}
