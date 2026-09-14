package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-liveness-census] The census exists so that a silent branch can be
 * told apart from a dead ruler: it announces every branch the FIRST time it is
 * ever seen, then emits a window summary that still carries the ZERO counters
 * of the branches that never ran. These tests pin that contract — in
 * particular that a never-entered branch is reported rather than omitted.
 */
class RenderPathCensusTest {
    private fun census(every: Int = 50): Pair<RenderPathCensus, MutableList<String>> {
        val out = mutableListOf<String>()
        return RenderPathCensus({ out += it }, every) to out
    }

    @Test
    fun `a branch announces itself the first time it is seen`() {
        val (c, out) = census()
        c.record(RenderPathCensus.Branch.FROZEN_HIT)
        assertEquals(1, out.size)
        assertTrue(out[0].contains("first=frozen_hit"))
    }

    @Test
    fun `a branch announces itself only once`() {
        val (c, out) = census()
        repeat(5) { c.record(RenderPathCensus.Branch.LIVE_PARSE) }
        assertEquals(1, out.count { it.contains("first=") })
    }

    @Test
    fun `a branch that never runs is still reported as zero`() {
        val (c, out) = census(2)
        c.record(RenderPathCensus.Branch.ROW_LEDGER)
        c.record(RenderPathCensus.Branch.ROW_LEDGER)
        val window = out.last()
        assertTrue(window.contains("window"))
        assertTrue(window.contains("row_ledger=2"))
        assertTrue(window.contains("row_cold_build=0"))
        assertTrue(window.contains("live_parse=0"))
    }

    @Test
    fun `windows reset the counters and keep reporting`() {
        val (c, out) = census(2)
        c.record(RenderPathCensus.Branch.FROZEN_HIT)
        c.record(RenderPathCensus.Branch.FROZEN_HIT)
        val n = out.size
        c.record(RenderPathCensus.Branch.FROZEN_HIT)
        c.record(RenderPathCensus.Branch.FROZEN_HIT)
        assertEquals(n + 1, out.size)
        assertTrue(out.last().contains("frozen_hit=2"))
    }

    @Test
    fun `maxRows carries the largest row count of the window`() {
        val (c, out) = census(2)
        c.record(RenderPathCensus.Branch.ROW_RESEED, rows = 120)
        c.record(RenderPathCensus.Branch.ROW_RESEED, rows = 3400)
        assertTrue(out.last().contains("maxRows=3400"))
    }

    @Test
    fun `negative row counts never win the max`() {
        val (c, _) = census(10)
        c.record(RenderPathCensus.Branch.ROW_LEDGER, rows = 7)
        c.record(RenderPathCensus.Branch.ROW_LEDGER)
        assertTrue(c.snapshot().contains("maxRows=7"))
    }

    @Test
    fun `snapshot reports without emitting`() {
        val (c, out) = census()
        c.record(RenderPathCensus.Branch.FROZEN_MISS)
        val n = out.size
        assertTrue(c.snapshot().contains("frozen_miss=1"))
        assertEquals(n, out.size)
    }
}
