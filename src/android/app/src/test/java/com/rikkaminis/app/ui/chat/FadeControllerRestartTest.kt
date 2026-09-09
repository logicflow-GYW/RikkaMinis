package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-fade] Regression coverage for the FadeController /
 * FadeFrameDriver restart race that left freshly-streamed word ranges stuck
 * at α=0 (invisible) until the composable was recreated by scrolling away
 * and back.
 *
 * The old driver keyed its LaunchedEffect on the `hasActiveRanges` BOOLEAN.
 * In a fast stream: frame N's tick drains the last range and breaks out of
 * the withFrameNanos loop (the effect's coroutine dies, but the composition
 * still holds key=true); a new ingest on frame N+1 re-reads true and the key
 * compares equal — no restart — so the new ranges never get ticked and stay
 * at α=0 forever. The monotonic `generation` counter fixes the lost wakeup:
 * any ingest that adds ranges bumps it, so the effect key ALWAYS changes and
 * the driver always restarts to drain the pending ranges.
 */
class FadeControllerRestartTest {

    @Test
    fun `generation bumps when new words are ingested`() {
        val c = FadeController()
        val g0 = c.generation.value
        c.ingest("hello world")
        assertTrue("ingest that adds words must bump generation", c.generation.value > g0)
    }

    @Test
    fun `ingesting identical text does not bump`() {
        val c = FadeController()
        c.ingest("same")
        val g1 = c.generation.value
        c.ingest("same")
        assertEquals("no-op ingest must not bump generation", g1, c.generation.value)
    }

    @Test
    fun `hard reset does not bump - it cancels work`() {
        val c = FadeController()
        c.ingest("hello world")
        val g1 = c.generation.value
        c.ingest("brand new text that diverges") // does NOT start with "hello" → reset branch
        assertEquals("cancel-only reset must not bump (driver notices emptiness itself)",
            g1, c.generation.value)
    }

    @Test
    fun `draining all ranges leaves no active work`() {
        val c = FadeController()
        c.ingest("a b c")
        assertTrue(c.hasActiveRanges)
        // FADE_DURATION_MS=350; ticks with far-future nanos finish everything
        val anyLeft = c.tick(System.nanoTime() + 1_000_000_000L)
        assertFalse("after a full-timeline tick no ranges remain", anyLeft)
        assertFalse(c.hasActiveRanges)
        assertEquals("completed ranges leave zero alphas", 0, c.alphas.size)
    }

    @Test
    fun `brand-new range is ticked to alpha 0 then drains opaque - the stuck-invisible shape`() {
        val c = FadeController()
        c.ingest("hello world")
        assertTrue(c.hasActiveRanges)
        assertTrue("new words must be recorded for fade", c.alphas.isEmpty())
        // First tick at t=0: all active ranges get an alpha entry (0 for the
        // unscattered first word). The race's failure mode was these ranges
        // never receiving ANY tick — alphas stayed empty and the overlay
        // rendered them at the default α=0 with no one to advance them.
        c.tick(System.nanoTime())
        assertTrue(
            "a tick must register alpha entries for active ranges (not leave them absent)",
            c.alphas.isNotEmpty(),
        )
        // Far-future tick drains everything → no active ranges remain.
        val anyLeft = c.tick(System.nanoTime() + 1_000_000_000L)
        assertFalse("after the full animation all ranges must drain", anyLeft)
        assertFalse(c.hasActiveRanges)
        assertEquals("drained ranges leave zero alpha entries", 0, c.alphas.size)
    }

    @Test
    fun `sequential ingests accumulate then drain - the exact race shape`() {
        val c = FadeController()
        c.ingest("alpha beta")
        val g1 = c.generation.value
        // Fast stream: second batch arrives BEFORE the first is drained.
        c.ingest("alpha beta gamma delta")
        assertTrue("second ingest must bump the generation again", c.generation.value > g1)
        // Drain everything with a far-future tick (single drain pass).
        val anyLeft = c.tick(System.nanoTime() + 1_000_000_000L)
        assertFalse(anyLeft)
        assertFalse(c.hasActiveRanges)
    }

    @Test
    fun `drained-then-refilled range set changes generation`() {
        // The exact user-visible race: ranges drain to zero (driver loop
        // exits), then a fresh batch arrives. The generation MUST have
        // changed so the effect restarts.
        val c = FadeController()
        c.ingest("first batch")
        c.tick(System.nanoTime() + 1_000_000_000L) // drain everything
        assertFalse(c.hasActiveRanges)
        val gDrained = c.generation.value
        // Prefix-continuing append (NOT a hard reset) so the new suffix
        // becomes new fade ranges and bumps the generation.
        c.ingest("first batch of tokens")
        assertTrue(
            "refill after drain must bump generation (this is the lost-wakeup fix)",
            c.generation.value > gDrained,
        )
    }
}