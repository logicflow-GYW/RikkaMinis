package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the inline-math extraction / sizing logic in
 * [StreamingMarkdownText]:
 *
 * - [collectInlineMathLatex] — delimiter scanning for `$...$` and `\(...\)`
 *   spans, with currency rejection, display-math guard, newline-bounded
 *   close, escaped dollars and markdown table-pipe artifact filtering.
 * - [katexInlineTagFor] — placeholder tag construction.
 * - [inlineMathSizeEm] — pure em-unit size heuristic (extracted from
 *   [StreamingMarkdownText.estimateInlineMathSize] so it can run without
 *   Compose's [androidx.compose.ui.unit.TextUnit]).
 *
 * All functions are pure string/float logic — no Android or Compose runtime
 * is touched.
 */
class InlineMathExtractionTest {

    // ── collectInlineMathLatex ──────────────────────────────────────────────

    @Test fun `empty text collects nothing`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex(""))
    }

    @Test fun `plain prose without dollar spans collects nothing`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("no math here at all"))
    }

    @Test fun `backslash paren span is collected`() {
        assertEquals(
            listOf("x + y"),
            collectInlineMathLatex("value: \\(x + y\\)"),
        )
    }

    @Test fun `multiple backslash paren spans are collected in order`() {
        assertEquals(
            listOf("a", "b"),
            collectInlineMathLatex("\\(a\\) and \\(b\\)"),
        )
    }

    @Test fun `unclosed backslash paren span is ignored`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\\(unclosed"))
    }

    @Test fun `display math backslash bracket is not collected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\\[x + y\\]"))
    }

    @Test fun `single variable dollar span is collected`() {
        assertEquals(listOf("x"), collectInlineMathLatex("\$x\$"))
    }

    @Test fun `dollar span with math operator is collected`() {
        assertEquals(listOf("x+y"), collectInlineMathLatex("\$x+y\$"))
    }

    @Test fun `currency dollar is not collected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("cost is \$5"))
    }

    @Test fun `comma currency dollar is not collected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("price \$1,000"))
    }

    @Test fun `currency then real math keeps the real span`() {
        // [T-latex-inline] The rejected currency span must NOT consume the
        // `$` that opens the following real span (`cost $5, and $x+y$`).
        assertEquals(listOf("x+y"), collectInlineMathLatex("\$5 and \$x+y\$"))
    }

    @Test fun `rejected span with space keeps following span`() {
        assertEquals(listOf("b"), collectInlineMathLatex("\$a \$b\$"))
    }

    @Test fun `double dollar display math is not collected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\$\$x\$\$"))
    }

    @Test fun `escaped dollar is not treated as span opener`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("use \\\$5"))
    }

    @Test fun `latex command span is collected`() {
        assertEquals(
            listOf("\\frac{1}{2}"),
            collectInlineMathLatex("\$\\frac{1}{2}\$"),
        )
    }

    @Test fun `subscript span is collected`() {
        assertEquals(listOf("a_{b}"), collectInlineMathLatex("\$a_{b}\$"))
    }

    @Test fun `realistic text command formula is collected`() {
        assertEquals(
            listOf("W_c^{\\text{non-private}}"),
            collectInlineMathLatex("\$W_c^{\\text{non-private}}\$"),
        )
    }

    @Test fun `newline bounds the dollar span so unclosed is ignored`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\$x\ny\$"))
    }

    @Test fun `multiple spans in one line are collected`() {
        assertEquals(listOf("x", "y"), collectInlineMathLatex("\$x\$ and \$y\$"))
    }

    @Test fun `span embedded in prose is collected`() {
        assertEquals(listOf("x"), collectInlineMathLatex("text \$x\$ tail"))
    }

    @Test fun `balanced pipes absolute value is collected`() {
        assertEquals(listOf("|x|"), collectInlineMathLatex("\$|x|\$"))
    }

    @Test fun `pipe with surrounding spaces is a table artifact and rejected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\$x | y\$"))
    }

    @Test fun `single pipe odd count is a table artifact and rejected`() {
        assertEquals(emptyList<String>(), collectInlineMathLatex("\$ab|cd\$"))
    }

    @Test fun `currency dollar inside table row is rejected`() {
        assertEquals(
            emptyList<String>(),
            collectInlineMathLatex("| 月付 | \$20|\$ **3** |"),
        )
    }

    @Test fun `escaped pipe norm bar with even count is collected`() {
        // `\|x\|` — the two escaped pipes are norm bars, not table separators;
        // bare pipe count is 0 (even), so it is NOT a table artifact.
        assertEquals(listOf("\\|x\\|"), collectInlineMathLatex("\$\\|x\\|\$"))
    }

    // ── katexInlineTagFor ───────────────────────────────────────────────────

    @Test fun `katex tag prefixes latex`() {
        assertEquals("katex_inline:x", katexInlineTagFor("x"))
    }

    @Test fun `katex tag with empty latex is just the prefix`() {
        assertEquals("katex_inline:", katexInlineTagFor(""))
    }

    @Test fun `katex tag preserves latex verbatim`() {
        assertEquals("katex_inline:\\frac{1}{2}", katexInlineTagFor("\\frac{1}{2}"))
    }

    // ── inlineMathSizeEm ────────────────────────────────────────────────────

    @Test fun `empty latex gets minimum width`() {
        val (w, h) = inlineMathSizeEm("")
        assertEquals(1.5f, w, 0.001f)
        assertEquals(1.7f, h, 0.001f)
    }

    @Test fun `single char gets minimum width`() {
        val (w, h) = inlineMathSizeEm("x")
        assertEquals(1.5f, w, 0.001f)
        assertEquals(1.7f, h, 0.001f)
    }

    @Test fun `two plain chars get 1_9 em width`() {
        val (w, h) = inlineMathSizeEm("xy")
        assertEquals(1.9f, w, 0.001f)
        assertEquals(1.7f, h, 0.001f)
    }

    @Test fun `three plain chars get 2_85 em width`() {
        val (w, h) = inlineMathSizeEm("x+y")
        assertEquals(2.85f, w, 0.001f)
        assertEquals(1.7f, h, 0.001f)
    }

    @Test fun `spaces are skipped in width counting`() {
        val (w, _) = inlineMathSizeEm("a b")
        // 'a' + 'b' = 2 visible chars → 1.9 em; the space is ignored.
        assertEquals(1.9f, w, 0.001f)
    }

    @Test fun `tex command counts as one visible char`() {
        val (w, _) = inlineMathSizeEm("\\alpha")
        assertEquals(1.5f, w, 0.001f)
    }

    @Test fun `frac raises height and counts command once`() {
        val (w, h) = inlineMathSizeEm("\\frac{1}{2}")
        assertEquals(2.85f, w, 0.001f)
        assertEquals(3.2f, h, 0.001f)
    }

    @Test fun `sqrt with index raises height`() {
        val (w, h) = inlineMathSizeEm("\\sqrt[3]{x}")
        assertEquals(4.75f, w, 0.001f)
        assertEquals(3.2f, h, 0.001f)
    }

    @Test fun `begin environment raises tallest height`() {
        val (w, h) = inlineMathSizeEm("\\begin{matrix}")
        assertEquals(6.65f, w, 0.001f)
        assertEquals(4.5f, h, 0.001f)
    }

    @Test fun `double backslash line break raises tallest height`() {
        val (w, h) = inlineMathSizeEm("a \\\\ b")
        // Two consecutive backslashes each count as a TeX command → 1 visible
        // char each, so width = 4 chars × 0.95 = 3.8em.
        assertEquals(3.8f, w, 0.001f)
        assertEquals(4.5f, h, 0.001f)
    }

    @Test fun `sum with limits raises height`() {
        val (w, h) = inlineMathSizeEm("\\sum_{i=1}^{n} x_i")
        assertEquals(9.5f, w, 0.001f)
        assertEquals(3.2f, h, 0.001f)
    }

    @Test fun `binom raises height`() {
        val (_, h) = inlineMathSizeEm("\\binom{n}{k}")
        assertEquals(3.2f, h, 0.001f)
    }

    @Test fun `int and prod raise height`() {
        assertEquals(3.2f, inlineMathSizeEm("\\int").second, 0.001f)
        assertEquals(3.2f, inlineMathSizeEm("\\prod").second, 0.001f)
    }

    @Test fun `plain formula stays at base height`() {
        val (w, h) = inlineMathSizeEm("x^2")
        assertEquals(2.85f, w, 0.001f)
        assertEquals(1.7f, h, 0.001f)
    }

    @Test fun `width is capped at 22 em`() {
        val (w, _) = inlineMathSizeEm("abcdefghijklmnopqrstuvwxyz0123")
        // 30 visible chars × 0.95 = 28.5 → capped.
        assertEquals(22f, w, 0.001f)
    }

    @Test fun `width never goes below minimum`() {
        val (w, _) = inlineMathSizeEm("\\int")
        assertTrue(w >= 1.5f)
    }
}
