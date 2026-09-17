package com.rikkaminis.app.ui.markdown

import com.rikkaminis.app.ui.markdown.MarkdownParser.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the ordered-list marker regex tightening
 * (fix/md-list-regex-and-storage-swr). The old `[.)\s]` accepted a bare
 * space as a valid list marker, so any paragraph opening with a 1-2 digit
 * number + space ("4 分钟没扫完…") was misparsed as ordered-list item #4
 * and rendered as "4. 分钟没扫完" — user report 2026-09-17.
 */
class MarkdownParserNumberedListTest {

    private fun blocks(md: String): List<Block> = MarkdownParser.parse(md)

    @Test
    fun `digit plus space paragraph stays a paragraph`() {
        val b = blocks("4 分钟没扫完——这本身就是重要线索。")
        assertEquals(1, b.size)
        assertTrue(b[0] is Block.Paragraph)
        assertEquals("4 分钟没扫完——这本身就是重要线索。", (b[0] as Block.Paragraph).content)
    }

    @Test
    fun `decimal number is not a list`() {
        val b = blocks("3.14 是圆周率")
        assertEquals(1, b.size)
        assertTrue(b[0] is Block.Paragraph)
        assertEquals("3.14 是圆周率", (b[0] as Block.Paragraph).content)
    }

    @Test
    fun `digit dot no space is not a list`() {
        val b = blocks("1.5倍速足够了")
        assertTrue(b[0] is Block.Paragraph)
    }

    @Test
    fun `two digit number plus space is not a list`() {
        val b = blocks("42 是答案")
        assertTrue(b[0] is Block.Paragraph)
    }

    @Test
    fun `year like opening is not a list`() {
        val b = blocks("2020 年之后情况不同")
        assertTrue(b[0] is Block.Paragraph)
        // "2020" 前两位 "20" 后跟 "2" 不是分隔符，整段必须保持段落
        assertEquals(1, b.size)
    }

    @Test
    fun `number dot space parses as list`() {
        val b = blocks("1. 第一项")
        assertEquals(1, b.size)
        val list = b[0] as Block.NumberedList
        assertEquals(1, list.startNumber)
        assertEquals(listOf("第一项"), list.items.map { it.content })
    }

    @Test
    fun `number paren parses as list`() {
        val b = blocks("1) 第一项")
        val list = b[0] as Block.NumberedList
        assertEquals(1, list.startNumber)
        assertEquals(listOf("第一项"), list.items.map { it.content })
    }

    @Test
    fun `multi item list preserves start number`() {
        val b = blocks("5. 五\n6. 六\n7. 七")
        assertEquals(1, b.size)
        val list = b[0] as Block.NumberedList
        assertEquals(5, list.startNumber)
        assertEquals(listOf("五", "六", "七"), list.items.map { it.content })
    }

    @Test
    fun `bare marker at end of line is an empty item`() {
        val b = blocks("4.")
        val list = b[0] as Block.NumberedList
        assertEquals(4, list.startNumber)
        assertEquals(listOf(""), list.items.map { it.content })
    }

    @Test
    fun `paragraph before a numbered list breaks correctly`() {
        val b = blocks("正文第一行\n1. 列表项")
        assertEquals(2, b.size)
        assertTrue(b[0] is Block.Paragraph)
        assertTrue(b[1] is Block.NumberedList)
    }

    @Test
    fun `list followed by paragraph is not absorbed`() {
        val b = blocks("1. a\nb")
        assertEquals(2, b.size)
        assertTrue(b[0] is Block.NumberedList)
        assertTrue(b[1] is Block.Paragraph)
        assertEquals("b", (b[1] as Block.Paragraph).content)
    }

    @Test
    fun `indented marker up to 3 spaces is a list`() {
        val b = blocks("   2. 缩进项")
        val list = b[0] as Block.NumberedList
        assertEquals(2, list.startNumber)
        assertEquals(listOf("缩进项"), list.items.map { it.content })
    }
}
