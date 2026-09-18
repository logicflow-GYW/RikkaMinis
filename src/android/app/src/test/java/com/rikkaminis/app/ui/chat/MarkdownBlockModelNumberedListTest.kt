package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the ordered-list marker tightening in [parseMarkdownBlocks]
 * (fix/chat-numbered-list-regex, 2026-09-17).
 *
 * Chat messages render through THIS parser ([StreamingMarkdownText] →
 * `parseMarkdownBlocks`); `ui/markdown/MarkdownParser.kt` is a look-alike that
 * serves other screens only. The old marker `^\d+[.)\s]+` accepted a bare space,
 * so a paragraph opening like "4 分钟没扫完…" matched as ordered-list item #4 and
 * rendered as "4.  分钟没扫完" — user report, still reproducible on 40a58c94 even
 * though the twin parser had already been fixed (the fix had landed on the wrong
 * twin). These tests pin the tightened behaviour on the parser chat actually uses.
 *
 * Runs under a timeout like [MarkdownBlockModelHashLineTest]: the parser checks
 * `coroutineContext.ensureActive()` periodically, so a predicate drift fails fast
 * instead of hanging CI.
 */
class MarkdownBlockModelNumberedListTest {

    private fun parse(text: String): List<MdBlock> = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Default) { parseMarkdownBlocks(text) }
        }
    }

    @Test fun `number plus space paragraph stays a paragraph`() {
        val text = "4 分钟没扫完就是没扫完，这行字如果变成带编号的缩进列表，说明修复还有残留。"
        val blocks = parse(text)
        assertEquals(1, blocks.size)
        assertTrue("got ${blocks[0]::class.simpleName}", blocks[0] is MdBlock.Paragraph)
        assertEquals(text, blocks[0].raw)
    }

    @Test fun `decimal number is not a list`() {
        assertTrue(parse("3.14 是圆周率")[0] is MdBlock.Paragraph)
    }

    @Test fun `version number is not a list`() {
        assertTrue(parse("1.5倍速足够了")[0] is MdBlock.Paragraph)
    }

    @Test fun `two digit number plus space is not a list`() {
        val blocks = parse("33 个提交全部收口。")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test fun `year-like number is not a list`() {
        assertTrue(parse("2020 年之后情况不同。")[0] is MdBlock.Paragraph)
    }

    // ── fix/indented-bullet-continuation ────────────────────────────────────
    // An indented line that is itself a list marker is a REAL new item, not
    // a continuation: the old branch swallowed it into the previous item's
    // text, leaving the raw "- " marker visible in the body. Mirrors the
    // guard in ui/markdown/MarkdownParser.kt (fix/audit-0917-b9).

    @Test fun `indented bullet becomes a new item not continuation`() {
        val blocks = parse("- 顶层\n  - 子项\n- 另一条")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.UnorderedList
        assertEquals(listOf("顶层", "子项", "另一条"), list.items.map { it.text })
    }

    @Test fun `indented numbered line inside bullet list stays a continuation`() {
        // Twin parity: MarkdownParser's guard also excludes ONLY bullet
        // markers, so an indented numbered line inside a bullet list is a
        // continuation there too.
        val blocks = parse("- 顶层\n  2. 子项")
        val list = blocks[0] as MdBlock.UnorderedList
        assertEquals(listOf("顶层\n2. 子项"), list.items.map { it.text })
    }

    @Test fun `indented prose is still a continuation`() {
        val blocks = parse("- 第一行\n  续行内容\n- 第二条")
        val list = blocks[0] as MdBlock.UnorderedList
        assertEquals(listOf("第一行\n续行内容", "第二条"), list.items.map { it.text })
    }

    @Test fun `real ordered list keeps parsing`() {
        val blocks = parse("1. 第一项\n2. 第二项")
        assertEquals(1, blocks.size)
        val list = blocks[0]
        assertTrue("got ${list::class.simpleName}", list is MdBlock.OrderedList)
        list as MdBlock.OrderedList
        assertEquals(1, list.startNum)
        assertEquals(listOf("第一项", "第二项"), list.items.map { it.text })
    }

    @Test fun `parenthesis marker keeps parsing`() {
        val list = parse("2) 第二项")[0] as MdBlock.OrderedList
        assertEquals(2, list.startNum)
        assertEquals("第二项", list.items[0].text)
    }

    @Test fun `list followed by number paragraph closes the list`() {
        val blocks = parse("1. 第一项\n4 分钟没扫完。")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.OrderedList)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals("4 分钟没扫完。", blocks[1].raw)
    }

    @Test fun `number line inside paragraph does not split it`() {
        val text = "先看结论\n4 分钟没扫完。"
        val blocks = parse(text)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertEquals(text, blocks[0].raw)
    }
}
