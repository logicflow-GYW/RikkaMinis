package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r8-C-F05] Copying a reply as plain text must not damage inline code.
 *
 * `stripInline` removed the backticks and then kept running the emphasis /
 * link regexes over the code's CONTENT, so:
 *
 *   `__init__`      → `init`        (bold-underscore ate the dunders)
 *   `fn[0](x)`      → `fn`          (link regex ate `[0](x)`)
 *   `a*b*c`         → `a b c`       (italic-star ate the stars)
 *   `snake_case`    → `snakecase`   (italic-underscore ate the inner `_`)
 *
 * The KDoc above `markdownToPlainText` promises "inline code unwrapped (content
 * preserved verbatim)". The rich-text path already does the right thing
 * (`inlineToHtml` lifts code spans into placeholders first); this pins the same
 * contract for the plain-text path, which is the default Copy action.
 */
class MarkdownClipboardInlineTest {

    private fun plain(markdown: String): String = MarkdownClipboard.markdownToPlainText(markdown)

    @Test
    fun `a dunder name inside inline code survives the copy`() {
        assertEquals("call __init__ now", plain("call `__init__` now"))
        assertEquals("`__init__`".replace("`", ""), plain("`__init__`"))
    }

    @Test
    fun `an index-and-call expression inside inline code survives the copy`() {
        assertEquals("use fn[0](x) here", plain("use `fn[0](x)` here"))
    }

    @Test
    fun `multiplication stars inside inline code survive the copy`() {
        assertEquals("compute a*b*c", plain("compute `a*b*c`"))
    }

    @Test
    fun `a snake_case identifier inside inline code keeps its underscores`() {
        assertEquals("set max_output_tokens", plain("set `max_output_tokens`"))
    }

    @Test
    fun `an escaped-looking link inside inline code is not turned into a link`() {
        assertEquals("see a[0](b) ok", plain("see `a[0](b)` ok"))
    }

    @Test
    fun `two code spans on one line both survive`() {
        assertEquals(
            "compare __init__ with max_output_tokens",
            plain("compare `__init__` with `max_output_tokens`"),
        )
    }

    @Test
    fun `an image-looking sequence inside inline code survives`() {
        assertEquals("literal ![alt](url) text", plain("literal `![alt](url)` text"))
    }

    // ── the non-code paths must keep working ────────────────────────────────

    @Test
    fun `bold still loses its markers`() {
        assertEquals("important", plain("**important**"))
        assertEquals("important", plain("__important__"))
    }

    @Test
    fun `a link still collapses to its text`() {
        assertEquals("the docs", plain("[the docs](https://example.com/x)"))
    }

    @Test
    fun `an image still collapses to its alt text`() {
        assertEquals("a chart", plain("![a chart](https://example.com/c.png)"))
    }

    @Test
    fun `italics and strikethrough still lose their markers`() {
        assertEquals("soft", plain("*soft*"))
        assertEquals("soft", plain("_soft_"))
        assertEquals("gone", plain("~~gone~~"))
    }

    @Test
    fun `emphasis around a code span still applies to the prose`() {
        // Bold markers go away, the code span's own content is restored verbatim
        // (no backticks, underscores intact) — the two halves of the contract
        // in one line.
        assertEquals("very x_y indeed", plain("**very `x_y` indeed**"))
    }

    @Test
    fun `a fenced block is copied verbatim with no inline processing`() {
        val md = "```python\ndef f(a, b):\n    return a*b\n```"
        assertEquals("def f(a, b):\n    return a*b", plain(md))
    }

    @Test
    fun `code spans in a heading and a list item survive too`() {
        assertEquals("Title __init__", plain("## Title `__init__`"))
        assertEquals("• item a*b*c", plain("- item `a*b*c`"))
    }

    @Test
    fun `an unmatched backtick is left as text`() {
        assertEquals("a ` b", plain("a ` b"))
        assertTrue(plain("no code here").contains("no code here"))
    }
}
