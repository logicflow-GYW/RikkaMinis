package com.rikkaminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ThinkingReadPlannerTest {
    @Test fun `manual expansion stays open after completion and later blocks`() {
        assertTrue(ThinkingReadPlanner.expanded(true, true, false, false))
        assertTrue(ThinkingReadPlanner.expanded(true, false, false, true))
        assertFalse(ThinkingReadPlanner.expanded(false, true, true, true))
    }
    @Test fun `automatic expansion follows setting stream and trailing block`() {
        assertTrue(ThinkingReadPlanner.expanded(null, true, true, true))
        assertFalse(ThinkingReadPlanner.expanded(null, false, true, true))
        assertFalse(ThinkingReadPlanner.expanded(null, true, false, true))
        assertFalse(ThinkingReadPlanner.expanded(null, true, true, false))
    }
    @Test fun `programmatic scrolling is never mistaken for a finger drag`() {
        val idle = ThinkingReadPlanner.Anchor()
        assertEquals(idle, ThinkingReadPlanner.updateAnchor(idle, 50, 1000, false, 2000))
        val reading = ThinkingReadPlanner.updateAnchor(idle, 50, 1000, true, 2000)
        assertEquals(2000, reading.frozenWindowStart)
        assertEquals(reading, ThinkingReadPlanner.updateAnchor(reading, 50, 1100, false, 3000))
        assertFalse(ThinkingReadPlanner.updateAnchor(reading, 1098, 1100, true, 3000).isReadingHistory)
    }
    @Test fun `actual production frozen window retains its text across five appended tokens`() {
        val original = (1..2000).joinToString("\n") { "line $it " + "y".repeat(20) }
        val tailStart = thinkingTailWindowStart(original, 8000)
        var anchor = ThinkingReadPlanner.updateAnchor(ThinkingReadPlanner.Anchor(), 100, 300, true, tailStart)
        val pinned = thinkingWindowSlice(original, anchor.frozenWindowStart!!, 8000, frozen = true)
        var growing = original
        repeat(5) {
            growing += "\nnew $it " + "z".repeat(20)
            anchor = ThinkingReadPlanner.updateAnchor(anchor, 100, 300, false, thinkingTailWindowStart(growing, 8000))
            val afterGrowth = thinkingWindowSlice(growing, anchor.frozenWindowStart!!, 8000, frozen = true)
            // Newline snapping can leave <8K initially; append may fill that spare tail.
            // The reading anchor/prefix must stay exact, not prohibit growth below it.
            assertTrue(afterGrowth.startsWith(pinned))
            assertEquals(pinned.lines().first(), afterGrowth.lines().first())
            assertTrue(afterGrowth.length <= 8000)
        }
        val released = ThinkingReadPlanner.updateAnchor(anchor, 300, 300, true, thinkingTailWindowStart(growing, 8000))
        assertNull(released.frozenWindowStart)
        assertNotEquals(pinned, thinkingWindowSlice(growing, thinkingTailWindowStart(growing, 8000), 8000))
    }
    @Test fun `full chunks exactly reconstruct 8001 through 100000 completed thinking`() {
        for (length in listOf(0, 1, 8000, 8001, 9000, 99999, 100000, 250000)) {
            val original = "x".repeat(length)
            val ranges = ThinkingReadPlanner.chunks(original)
            assertEquals(original, ranges.joinToString("") { original.substring(it.first, it.last + 1) })
            assertTrue(ranges.all { it.count() in 1..4000 })
        }
    }
    @Test fun `long lines and emoji surrogate pairs are never lost or split`() {
        val original = "x".repeat(3999) + "😀" + "🌏".repeat(9000) + "\r\n" + "last line"
        val ranges = ThinkingReadPlanner.chunks(original)
        val chunks = ranges.map { original.substring(it.first, it.last + 1) }
        assertEquals(original, chunks.joinToString(""))
        chunks.forEach {
            assertFalse(Character.isLowSurrogate(it.first()))
            assertFalse(Character.isHighSurrogate(it.last()))
            assertTrue(it.length <= 4000)
        }
    }
    @Test fun `inline windows do not start or finish inside emoji surrogate pairs`() {
        val original = "x".repeat(2000) + "😀" + "z".repeat(7999)
        val start = thinkingTailWindowStart(original, 8000)
        assertFalse(Character.isLowSurrogate(original[start]))
        val frozen = thinkingWindowSlice("x".repeat(7999) + "😀" + "tail", 0, 8000, frozen = true)
        assertFalse(Character.isHighSurrogate(frozen.last()))
    }
    @Test fun `clipboard route uses entire source length and a conservative binder budget`() {
        assertTrue(ThinkingReadPlanner.canCopyAsText(8001))
        assertTrue(ThinkingReadPlanner.canCopyAsText(100000))
        assertFalse(ThinkingReadPlanner.canCopyAsText(100001))
        assertFalse(ThinkingReadPlanner.canCopyAsText(2000000))
    }
    @Test fun `window thresholds stay compatible with the established inline limits`() {
        assertEquals(8000, ThinkingReadPlanner.WINDOW_CHARS)
        assertEquals(100000, ThinkingReadPlanner.INLINE_HARD_CAP)
    }
}
