package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the public pure-Kotlin functions in
 * [StreamingMarkdownText]:
 *
 * - [splitMarkdownIntoBlockTexts] — parser that splits Markdown text into
 *   paragraph and fenced-code-block fragments.
 * - [coalesceMarkdownFragments] — merge adjacent plain-text fragments back
 *   into larger chunks (keeping code fences standalone).
 */
class StreamingMarkdownTextTest {

    // ── splitMarkdownIntoBlockTexts ────────────────────────────────────────

    @Test fun `split empty string returns empty list`() {
        assertEquals(emptyList<String>(), splitMarkdownIntoBlockTexts(""))
    }

    @Test fun `split single paragraph`() {
        val result = splitMarkdownIntoBlockTexts("Hello world")
        assertEquals(listOf("Hello world"), result)
    }

    @Test fun `split multiple paragraphs separated by blank line`() {
        val input = """
            First paragraph.
            
            Second paragraph.
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("First paragraph.", "Second paragraph."), result)
    }

    @Test fun `split paragraph with trailing blank lines`() {
        val result = splitMarkdownIntoBlockTexts("Hello\n\n\n\nWorld")
        assertEquals(listOf("Hello", "World"), result)
    }

    @Test fun `split preserves multi-line paragraph`() {
        val input = "Line one\nLine two\nLine three"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("Line one\nLine two\nLine three"), result)
    }

    @Test fun `split fenced code block as single fragment`() {
        val input = """
            Text before.
            
            ```kotlin
            val x = 1
            println(x)
            ```
            
            Text after.
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(3, result.size)
        assertEquals("Text before.", result[0])
        assertTrue(result[1].startsWith("```kotlin"))
        assertTrue(result[1].contains("val x = 1"))
        assertTrue(result[1].endsWith("```"))
        assertEquals("Text after.", result[2])
    }

    @Test fun `split inline code backticks do not trigger fence`() {
        val input = "Use `code` inline"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("Use `code` inline"), result)
    }

    @Test fun `split multiple code blocks`() {
        val input = """
            ```json
            {"a": 1}
            ```
            
            Middle text.
            
            ```bash
            echo hi
            ```
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(3, result.size)
        assertTrue(result[0].startsWith("```json"))
        assertEquals("Middle text.", result[1])
        assertTrue(result[2].startsWith("```bash"))
    }

    @Test fun `split no blank lines returns single fragment`() {
        val input = "Line1\nLine2\nLine3\n```\ncode\n```\nLine4"
        val result = splitMarkdownIntoBlockTexts(input)
        // No blank lines between content and fence, so everything is glued
        // together except the fence fragments.
        // Actually the fence is still detected because it's a separate line.
        // Let me check the logic: fence line starts with ``` -> triggers flush.
        // But there's no blank line before the fence, so "Line1\nLine2\nLine3\n"
        // is in cur, then fence triggers flush() -> "Line1\nLine2\nLine3" is one fragment
        // Then fence opens, code lines accumulate, closing fence triggers flush
        // Then "Line4" is another fragment
        assertEquals(3, result.size)
        assertEquals("Line1\nLine2\nLine3", result[0])
        assertTrue(result[1].startsWith("```\ncode\n```"))
        assertEquals("Line4", result[2])
    }

    @Test fun `split blank lines only returns empty`() {
        assertEquals(emptyList<String>(), splitMarkdownIntoBlockTexts("\n\n\n"))
    }

    @Test fun `split consecutive blank lines between paragraphs`() {
        val input = "A\n\n\n\nB"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(listOf("A", "B"), result)
    }

    @Test fun `split preserves indentation inside code block`() {
        val input = """
            ```python
            def hello():
                print("world")
            ```
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("    print"))
    }

    @Test fun `split indented line in paragraph`() {
        val input = "Normal\n    Indented\nNormal again"
        val result = splitMarkdownIntoBlockTexts(input)
        assertEquals(1, result.size)
        assertTrue(result[0].contains("    Indented"))
    }

    @Test fun `split unclosed fence includes everything`() {
        val input = """
            Start.
            ```unclosed
            still inside
            no closing
        """.trimIndent()
        val result = splitMarkdownIntoBlockTexts(input)
        assertTrue(result[0].startsWith("Start."))
        assertTrue(result[1].startsWith("```unclosed"))
        assertTrue(result[1].contains("no closing"))
        // No closing fence, so the fragment stays open and gets flushed
        // at the end.
    }

    // ── coalesceMarkdownFragments ──────────────────────────────────────────

    @Test fun `coalesce empty list returns empty`() {
        assertEquals(emptyList<String>(), coalesceMarkdownFragments(emptyList()))
    }

    @Test fun `coalesce single fragment returns same`() {
        val input = listOf("Hello")
        assertEquals(input, coalesceMarkdownFragments(input))
    }

    @Test fun `coalesce merges adjacent plain fragments`() {
        val input = listOf("A", "B", "C")
        val result = coalesceMarkdownFragments(input)
        assertEquals(1, result.size)
        assertEquals("A\n\nB\n\nC", result[0])
    }

    @Test fun `coalesce keeps code fence fragments standalone`() {
        val input = listOf("A", "```\ncode\n```", "B")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A", result[0])
        assertEquals("```\ncode\n```", result[1])
        assertEquals("B", result[2])
    }

    @Test fun `coalesce respects maxChars`() {
        val input = listOf("A" * 1500, "B" * 1500, "C" * 1500)
        val result = coalesceMarkdownFragments(input, maxChars = 2000)
        // Each fragment is 1500 chars, so "A" + "\n\n" + "B" exceeds 2000
        // So A is alone, then B + "\n\n" + C = 3002 > 2000, so B is alone, then C alone
        assertEquals(3, result.size)
    }

    @Test fun `coalesce single oversized paragraph still gets its own row`() {
        val big = "X" * 5000
        val result = coalesceMarkdownFragments(listOf(big), maxChars = 2000)
        assertEquals(1, result.size)
        assertEquals(big, result[0])
    }

    @Test fun `coalesce multiple code fences with plain text between`() {
        val input = listOf("Intro", "```\na\n```", "Middle", "```\nb\n```", "Outro")
        val result = coalesceMarkdownFragments(input)
        assertEquals(5, result.size)
        assertEquals("Intro", result[0])
        assertEquals("```\na\n```", result[1])
        assertEquals("Middle", result[2])
        assertEquals("```\nb\n```", result[3])
        assertEquals("Outro", result[4])
    }

    @Test fun `coalesce merges fragments before and after code fence`() {
        val input = listOf("A", "B", "```\ncode\n```", "C", "D", "E")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A\n\nB", result[0])
        assertEquals("```\ncode\n```", result[1])
        assertEquals("C\n\nD\n\nE", result[2])
    }

    @Test fun `coalesce preserves maxChars for merged chunk`() {
        val small = "a"
        val big = "b" * 1500
        // small + "\n\n" + big = 1503, within 2000
        // But adding another big would exceed
        val input = listOf(small, big, "c" * 1500)
        val result = coalesceMarkdownFragments(input, maxChars = 2000)
        assertEquals(2, result.size)
        assertTrue(result[0].contains("a"))
        assertTrue(result[0].contains("b"))
        assertTrue(result[1].contains("c"))
    }

    @Test fun `coalesce code fence line with leading spaces is still fence`() {
        val input = listOf("A", "  ```\ncode\n  ```", "B")
        val result = coalesceMarkdownFragments(input)
        assertEquals(3, result.size)
        assertEquals("A", result[0])
        // Leading spaces: the fence fragment check looks at first non-blank line
        // and checks if it starts with ```. "  ```" starts with spaces, then ```.
        // trimStart() is called on the first line, so it becomes "```" -> fence detected.
        assertEquals("  ```\ncode\n  ```", result[1])
        assertEquals("B", result[2])
    }

    // ── mergeAgentTextSnapshot ──────────────────────────────────────────────

    @Test fun `mergeAgentTextSnapshot empty incoming returns current`() {
        assertEquals("Hello", mergeAgentTextSnapshot("Hello", ""))
    }

    @Test fun `mergeAgentTextSnapshot empty current returns incoming`() {
        assertEquals("Hello", mergeAgentTextSnapshot("", "Hello"))
    }

    @Test fun `mergeAgentTextSnapshot equal returns current`() {
        assertEquals("Hello", mergeAgentTextSnapshot("Hello", "Hello"))
    }

    @Test fun `mergeAgentTextSnapshot ignores regressive prefix snapshot`() {
        assertEquals("Hello, world!", mergeAgentTextSnapshot("Hello, world!", "Hello"))
    }

    @Test fun `mergeAgentTextSnapshot replaces divergent snapshot`() {
        assertEquals("最终版：完整内容", mergeAgentTextSnapshot("第一版：草稿内容", "最终版：完整内容"))
    }

    @Test fun `mergeAgentTextSnapshot keeps emoji and markdown snapshots`() {
        assertEquals("前缀😀 **完成**", mergeAgentTextSnapshot("前缀😀", "前缀😀 **完成**"))
    }

    @Test fun `mergeAgentTextSnapshot both empty returns empty`() {
        assertEquals("", mergeAgentTextSnapshot("", ""))
    }

    @Test fun `mergeAgentTextSnapshot incoming longer not regressive replaces`() {
        assertEquals("Hello world", mergeAgentTextSnapshot("Hello", "Hello world"))
    }

    // ── mergeLegacyStreamingText ────────────────────────────────────────────

    @Test fun `mergeLegacyStreamingText empty incoming returns current`() {
        assertEquals("Hello", mergeLegacyStreamingText("Hello", ""))
    }

    @Test fun `mergeLegacyStreamingText empty current returns incoming`() {
        assertEquals("Hello", mergeLegacyStreamingText("", "Hello"))
    }

    @Test fun `mergeLegacyStreamingText equal returns current`() {
        assertEquals("Hello", mergeLegacyStreamingText("Hello", "Hello"))
    }

    @Test fun `mergeLegacyStreamingText ignores regressive prefix snapshot`() {
        assertEquals("Hello, world!", mergeLegacyStreamingText("Hello, world!", "Hello"))
    }

    @Test fun `mergeLegacyStreamingText normal append replaces with longer`() {
        assertEquals("Hello, world!", mergeLegacyStreamingText("Hello, world", "Hello, world!"))
    }

    @Test fun `mergeLegacyStreamingText delta chunk suffix prefix overlap`() {
        assertEquals("Hello, world", mergeLegacyStreamingText("Hello", ", world"))
    }

    @Test fun `mergeLegacyStreamingText deduplicates overlapping delta chunks`() {
        assertEquals("Hello, world", mergeLegacyStreamingText("Hello, wor", "world"))
    }

    @Test fun `mergeLegacyStreamingText keeps tiny overlap as normal delta`() {
        assertEquals("abccde", mergeLegacyStreamingText("abc", "cde"))
    }

    @Test fun `mergeLegacyStreamingText ignores shorter divergent restarted table`() {
        val current = "好的，以下是一个示例表格：\n\n| 序号 | 姓名 |\n| --- | --- |\n| 1 | 张三 |"
        val incoming = "好的，以下是一个示例表格：\n\n| 序号 | 姓名 |\n|:---"
        assertEquals(current, mergeLegacyStreamingText(current, incoming))
    }

    @Test fun `mergeLegacyStreamingText both empty returns empty`() {
        assertEquals("", mergeLegacyStreamingText("", ""))
    }

    @Test fun `mergeLegacyStreamingText divergent snapshot keeps longer one`() {
        val current = "Common prefix A B C D E F G H I J K L M N O P longer ending"
        val incoming = "Common prefix A B C D E F G H I J K L M N O P short"
        // Same long common prefix, then diverge: incoming is shorter, so the
        // longer current snapshot wins instead of concatenating garbage.
        assertEquals(current, mergeLegacyStreamingText(current, incoming))
    }

    @Test fun `mergeLegacyStreamingText fallback concatenates unrelated text`() {
        assertEquals("abcxyz", mergeLegacyStreamingText("abc", "xyz"))
    }

    // ── shouldIgnoreRegressiveStreamingSnapshot ─────────────────────────────

    @Test fun `shouldIgnoreRegressiveStreamingSnapshot true when shorter and prefix`() {
        assertTrue(shouldIgnoreRegressiveStreamingSnapshot("Hello, world!", "Hello"))
    }

    @Test fun `shouldIgnoreRegressiveStreamingSnapshot false when empty`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("", "Hello"))
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", ""))
    }

    @Test fun `shouldIgnoreRegressiveStreamingSnapshot false when not prefix`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello, world!", "world"))
    }

    @Test fun `shouldIgnoreRegressiveStreamingSnapshot false when longer`() {
        assertFalse(shouldIgnoreRegressiveStreamingSnapshot("Hello", "Hello, world!"))
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private operator fun String.times(n: Int): String = buildString {
        repeat(n) { append(this@times) }
    }
}