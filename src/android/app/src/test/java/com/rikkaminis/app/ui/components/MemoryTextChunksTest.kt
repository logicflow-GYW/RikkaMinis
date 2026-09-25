package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-memory-file-jank] Unit tests for the memory-file chunker that
 * backs the virtualized viewer.
 *
 * The load-bearing property is round-trip fidelity: the viewer joins chunks
 * with "\n", so `chunks.joinToString("\n")` MUST equal the input exactly.
 * Any drift here silently corrupts what the user reads, and the symptom would
 * look like a rendering bug rather than a chunking bug.
 *
 * The second property is progress: a single line longer than the byte cap must
 * still be emitted, otherwise the walk never advances (infinite loop) — a
 * real case here, since memory files contain single lines up to 2221 chars.
 */
class MemoryTextChunksTest {

    // ─── round-trip fidelity (the load-bearing property) ────────────────

    @Test
    fun chunkText_joinWithNewline_roundTripsExactly() {
        val text = buildString {
            repeat(500) { append("line $it with some content\n") }
        }.trimEnd('\n')

        val chunks = chunkText(text)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun chunkText_roundTrips_whenTextHasBlankLinesAndTrailingNewline() {
        val text = "a\n\nb\n\n\nc\n"
        val chunks = chunkText(text)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun chunkText_roundTrips_whenTextIsAsciiAndCjkMixed() {
        val text = (1..300).joinToString("\n") { "第 $it 行 mixed ASCII content 内容" }
        val chunks = chunkText(text)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun chunkText_roundTrips_whenTextIsOneHugeLine() {
        val text = "x".repeat(50_000)
        val chunks = chunkText(text)
        assertEquals(text, chunks.joinToString("\n"))
    }

    // ─── empty / degenerate input ───────────────────────────────────────

    @Test
    fun chunkText_emptyString_yieldsNoChunks() {
        assertTrue(chunkText("").isEmpty())
    }

    @Test
    fun chunkText_singleChar_yieldsOneChunk() {
        assertEquals(listOf("a"), chunkText("a"))
    }

    // ─── chunk sizing ───────────────────────────────────────────────────

    @Test
    fun chunkText_respectsMaxLines() {
        val text = (1..100).joinToString("\n") { "l$it" }
        val chunks = chunkText(text, maxLines = 40, maxBytes = Int.MAX_VALUE)
        assertEquals(3, chunks.size)
        assertEquals(40, chunks[0].count { it == '\n' } + 1)
    }

    @Test
    fun chunkText_respectsMaxBytes() {
        // 50 chars per line, 20-byte cap => one line per chunk.
        val text = (1..10).joinToString("\n") { "y".repeat(50) }
        val chunks = chunkText(text, maxLines = 1000, maxBytes = 20)
        assertEquals(10, chunks.size)
        chunks.forEach { assertEquals(50, it.length) }
    }

    @Test
    fun chunkText_oversizedSingleLine_isStillEmittedAndAdvances() {
        // 10x the byte cap on ONE line: must produce exactly one chunk, not loop.
        val text = "z".repeat(200)
        val chunks = chunkText(text, maxLines = 40, maxBytes = 20)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun chunkText_capsByteSizeForRealisticLines() {
        // 40-line chunks of ~120 chars should land under the 4KB default cap.
        val text = (1..400).joinToString("\n") { "l".repeat(120) }
        val chunks = chunkText(text)
        assertTrue("expected >1 chunk, got ${chunks.size}", chunks.size > 1)
        chunks.forEach {
            assertTrue("chunk exceeded cap: ${it.toByteArray().size}", it.toByteArray().size <= 4 * 1024)
        }
    }

    // ─── ordering ───────────────────────────────────────────────────────

    @Test
    fun chunkText_preservesOrder() {
        val text = (1..100).joinToString("\n") { "line-$it" }
        val chunks = chunkText(text, maxLines = 10)
        val rebuilt = chunks.joinToString("\n").split("\n")
        assertEquals((1..100).map { "line-$it" }, rebuilt)
    }

    @Test
    fun chunkText_doesNotEmitEmptyChunks() {
        val text = (1..50).joinToString("\n") { "l$it" }
        val chunks = chunkText(text, maxLines = 7)
        assertFalse(chunks.any { it.isEmpty() })
    }

    // ─── guard rails ────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun chunkText_rejectsZeroMaxLines() {
        chunkText("a\nb", maxLines = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun chunkText_rejectsZeroMaxBytes() {
        chunkText("a\nb", maxBytes = 0)
    }

    // ─── windowed edit helpers ([fix-memory-editor-windowed-edit]) ──────

    @Test
    fun chunkStartOffsets_matchesJoinedText() {
        val text = (1..300).joinToString("\n") { "line $it" }
        val chunks = chunkText(text)
        val offsets = chunkStartOffsets(chunks)
        val joined = chunks.joinToString("\n")
        // Every chunk starts exactly where the join puts it.
        chunks.forEachIndexed { i, chunk ->
            assertEquals(joined.substring(offsets[i], offsets[i] + chunk.length), chunk)
        }
        // Last chunk ends at the end of the joined text.
        assertEquals(joined.length, offsets.last() + chunks.last().length)
    }

    @Test
    fun buildEditWindow_isByteExactInsideBase() {
        val text = (1..300).joinToString("\n") { "line $it" }
        val chunks = chunkText(text)
        val win = buildEditWindow(chunks, 2, 4)!!
        assertEquals(text.substring(win.startOffset, win.endOffset), win.text)
        assertEquals(chunks.subList(2, 5).joinToString("\n"), win.text)
    }

    @Test
    fun buildEditWindow_clampsOutOfRangeIndices() {
        val text = (1..100).joinToString("\n") { "l$it" }
        val chunks = chunkText(text, maxLines = 10)
        val win = buildEditWindow(chunks, -5, 500)!!
        assertEquals(chunks.joinToString("\n"), win.text)
        assertEquals(0, win.startOffset)
        assertEquals(text.length, win.endOffset)
    }

    @Test
    fun buildEditWindow_emptyChunks_returnsNull() {
        assertTrue(buildEditWindow(emptyList(), 0, 1) == null)
    }

    @Test
    fun buildEditWindow_isOrderInsensitive() {
        val text = (1..100).joinToString("\n") { "l$it" }
        val chunks = chunkText(text, maxLines = 10)
        val a = buildEditWindow(chunks, 3, 5)!!
        val b = buildEditWindow(chunks, 5, 3)!!
        assertEquals(a.text, b.text)
        assertEquals(a.startOffset, b.startOffset)
        assertEquals(a.endOffset, b.endOffset)
    }

    @Test
    fun spliceEditWindow_roundTripsUnchangedText() {
        val text = (1..300).joinToString("\n") { "line $it" }
        val win = buildEditWindow(chunkText(text), 2, 4)!!
        assertEquals(text, spliceEditWindow(text, win.startOffset, win.endOffset, win.text))
    }

    @Test
    fun spliceEditWindow_replacesEditedWindow() {
        val text = (1..300).joinToString("\n") { "line $it" }
        val win = buildEditWindow(chunkText(text), 2, 4)!!
        val edited = "REPLACED\nCONTENT"
        val spliced = spliceEditWindow(text, win.startOffset, win.endOffset, edited)
        assertEquals(
            text.substring(0, win.startOffset) + edited + text.substring(win.endOffset),
            spliced,
        )
        // Everything outside the window is byte-identical.
        assertTrue(spliced.startsWith(text.substring(0, win.startOffset)))
        assertTrue(spliced.endsWith(text.substring(win.endOffset)))
    }

    @Test
    fun spliceEditWindow_staleOffsets_refuseInsteadOfGuess() {
        val text = "abc"
        assertEquals(text, spliceEditWindow(text, 5, 6, "x"))  // start past length
        assertEquals(text, spliceEditWindow(text, 1, 0, "x"))  // inverted range
        assertEquals(text, spliceEditWindow(text, 0, 9, "x"))  // end past length
        assertEquals(text, spliceEditWindow(text, -1, 2, "x")) // negative start
    }
}
