package com.rikkaminis.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [2D-1119 / 6 F-03, P1] The prose function-word net reads **the text outside
 * the call groups**, not the whole span.
 *
 * `looksLikeNaturalLanguage` runs two nets over the content. Net 2 (the word
 * count) already skips a function call — `name` plus its balanced `( … )`
 * group is one structural unit of a formula, so neither the name nor its
 * arguments are words ([incr1117-B F-06] / [F-11]). Net 1, added later for
 * `100 + min(fee) total `, scanned the RAW content, so the arguments inside
 * the parentheses were still read as prose evidence — and a formula whose
 * ARGUMENT NAME happens to be an English function word was classified as a
 * sentence, lost its glyph scan and came back as raw `$ … $` text.
 *
 * The rule that fixes it, without widening the word table: an argument inside
 * a call group is an argument, never a prose word. Net 1 therefore runs on
 * [MarkdownMathHeuristic]'s view of the content with every call group
 * replaced by a blank, the same regions Net 2 already ignores.
 *
 * Trade-off, changed on purpose: `x + sin(the angle) + 3` was text because
 * `the` sat inside a call group. It is now math — a function call with a
 * parenthesised argument is formula structure, and prose that needs to be
 * rejected still has its words OUTSIDE the group (`… of a circle`,
 * `… total `). See the prose arms below and the FIX report.
 *
 * [r3d1119 / 2 F-01, P1] "Call group" is bracket-aware: `[`/`{` are openers and
 * `]`/`}` close one on the same counter, so a call written with a half-open
 * interval (`P([a, b) and c)`) is ONE balanced group whose terminator is the
 * call's `)`. See `callGroupEnd` in MarkdownMathHeuristic — the prose net and the
 * word-count net share that one definition, so the two renderers cannot drift.
 *
 * Every expectation is a literal — never derived from the constants under
 * test.
 */
class MarkdownMathProseScopeTest {

    private fun math(content: String) =
        assertEquals("expected MATH for <$content>", true, MarkdownMathHeuristic.looksLikeMath(content))

    private fun text(content: String) =
        assertEquals("expected TEXT for <$content>", false, MarkdownMathHeuristic.looksLikeMath(content))

    // ── F-03: wrong before the fix, correct after it ────────────────────────
    // Each of these carries a function word INSIDE a call group, so the old
    // whole-content scan read it as prose and rejected a real formula.

    @Test
    fun `a probability whose argument name is a prose word is math`() {
        // `at` is an argument name here, not the English preposition.
        math("P(at = 0)")
    }

    @Test
    fun `a complement probability with a prose word in the second call is math`() {
        // `not` sits inside `P(…)`; both call groups are structure, the `=` is
        // the formula evidence. Two calls, so an implementation that blanks
        // only the first one is caught here.
        math("Q(x) = 1 - P(not x)")
    }

    @Test
    fun `a set builder sum whose arguments carry prose words is math`() {
        // `for` / `in` are the argument list of `sum(…)`; `>` is the glyph.
        math("sum(x for x in S) > 0")
    }

    // ── regression: correct before the fix, still correct after it ──────────

    @Test
    fun `a function word outside the call group is still prose`() {
        // The sentence the prose net was written for: `min(` is a call, `total`
        // is outside it, and the sentence must stay text.
        text("100 + min(fee) total ")
        text("50 for a 3-day pass or ")
        text("10 min - 20 max or")
    }

    @Test
    fun `a sentence with a call and prose outside the call stays text`() {
        // The call is blanked, the prose outside it is still read — the guard
        // must not be turned off wholesale by the restriction.
        text("x + sin(the angle) of a circle")
        text("sin (the angle) of a circle")
        text("x + sin(the angle) + cost of the pass")
    }

    @Test
    fun `formulas with no prose word keep their old verdict`() {
        math("sin(alpha) + cos(beta) + tan(gamma)")
        math("max(err, tol, eps)")
        math("sin(theta)+cos(theta)")
        math("max(min(a, b), c)")
        math("x - y")
        math("x+y")
        math("1+1=2")
        math("\\frac{1}{2}")
        math("x^2 + y")
    }

    @Test
    fun `an unbalanced call is still fail-safe for the prose net`() {
        // Half-typed stream: `sin(x and` has no balanced group, so the words
        // inside it are NOT a call — the argument region is not exempt.
        text("sin(x and")
    }

    @Test
    fun `a prose word inside a group that is not a call is still prose`() {
        // `(a and b)` has no `name(` head — it is a group, not an argument
        // list, so the words inside it are still prose evidence. The fix is
        // scoped to call groups, not to every parenthesis.
        text("(a and b) is one case")
    }

    // ── [r3d1119 / 2 F-01, P1]: brackets are nesting on the same counter ──
    // `P([a, b) and c)` carries a half-open interval. The `)` that closes the
    // interval is not the `)` that closes the call, so a counter that read only
    // `(`/`)` closed the group at `b)`, handed `and` to the prose nets and
    // downgraded a probability to plain text. Brackets now count as nesting on
    // the shared counter, the group still ends on a `)` that brings it to zero,
    // and a closer never takes it below zero.

    @Test
    fun `a call whose argument is a half open interval is math`() {
        // `[` has no `]`; the interval is closed by the `)`, and the group must
        // keep scanning to the `)` that ends the CALL.
        math("P([a, b) and c)")
        // balanced brackets inside the call: the group ends at the call's `)`.
        math("P([a,b])")
        // brackets without a call head are not a call group at all — no
        // exemption, and the verdict comes from the length/glyph nets as before.
        math("[1, 2) ∪ (3, 5]")
    }

    @Test
    fun `a call with prose inside and no bracket is still math`() {
        // The bracket rule must not be what makes this one pass: `and` sits
        // inside a balanced call and is exempt either way.
        math("P(a and b)")
    }

    @Test
    fun `prose outside a call keeps its text verdict under the bracket rule`() {
        text("f(x) = x^2 where x > 0")
        text("hello and welcome")
    }

    @Test
    fun `an unclosed bracket does not make a call group`() {
        // `[` with no closer at all: the group never balances, nothing is
        // exempt, and the prose word inside it is read — the fail-safe side, the
        // same way an unclosed `(` is handled above.
        text("P([a, b and c")
    }

    @Test
    fun `a bracket closer cannot close a call group early`() {
        // `]` is not the group terminator. A stray closer takes the counter to
        // zero without ending the group, so the prose word behind it is still
        // read: the clamp fails toward text, never toward swallowing prose.
        text("f(x] and y)")
        // `{`/`}` count as nesting, so `f({x} + 1)` is one balanced group.
        math("f({x} + 1)")
    }
}
