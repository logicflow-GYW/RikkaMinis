package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [browser-rss P4] Tests for the pure browser RSS attribution probe.
 *
 * Mirrors OffloadRssProbeTest: validates (1) [parseVmRssKb] as a pure
 * function and (2) [record]/[summary]/[reset] aggregation + LEAK-SUSPECT
 * flagging. No Android dependency — /proc is never touched here, we feed
 * synthetic deltas through the aggregator directly.
 */
class BrowserRssProbeTest {

    @Before
    fun setUp() {
        BrowserRssProbe.reset()
    }

    // ---- parseVmRssKb ----

    @Test
    fun `parseVmRss plain status text`() {
        val text = """
            Name:   com.rikkaminis.app
            State:  R (running)
            VmRSS:    123456 kB
            VmSize:   999 kB
        """.trimIndent()
        assertEquals(123456L, BrowserRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss missing VmRSS line returns zero`() {
        val text = """
            Name:   com.rikkaminis.app
            VmSize: 999 kB
        """.trimIndent()
        assertEquals(0L, BrowserRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss empty string returns zero`() {
        assertEquals(0L, BrowserRssProbe.parseVmRssKb(""))
    }

    @Test
    fun `parseVmRss malformed number returns zero`() {
        val text = "VmRSS:   not-a-number kB\n"
        assertEquals(0L, BrowserRssProbe.parseVmRssKb(text))
    }

    // ---- record / aggregation ----

    @Test
    fun `record accumulates per action count and total delta`() {
        BrowserRssProbe.record("navigate", 100_000, 110_000)    // +10k kB
        BrowserRssProbe.record("navigate", 110_000, 115_000)    // +5k kB
        BrowserRssProbe.record("screenshot", 120_000, 160_000)  // +40k kB

        val summary = BrowserRssProbe.summary()
        assertTrue("summary should mention navigate", summary.contains("navigate"))
        assertTrue("summary should mention screenshot", summary.contains("screenshot"))
    }

    @Test
    fun `record preserves negative delta`() {
        BrowserRssProbe.record("click", 100_000, 80_000) // -20k kB (renderer reclaimed)
        val summary = BrowserRssProbe.summary()
        assertTrue(summary.contains("cum=-19MB") || summary.contains("cum=-20MB"))
    }

    @Test
    fun `record zero delta when before equals after`() {
        BrowserRssProbe.record("get_text", 100_000, 100_000)
        val summary = BrowserRssProbe.summary()
        assertTrue(summary.contains("cum=0MB"))
        assertTrue(summary.contains("count=1"))
    }

    // ---- LEAK-SUSPECT ----

    @Test
    fun `cumulative growth crossing 1 GiB flags leak suspect`() {
        repeat(105) { i ->
            val before = 100_000L + i * 10_000L
            BrowserRssProbe.record("navigate", before, before + 10_000)
        }
        val summary = BrowserRssProbe.summary()
        assertTrue(
            "expected LEAK-SUSPECT marker on navigate, got:\n$summary",
            summary.contains("navigate") && summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `small cumulative growth does not flag leak suspect`() {
        repeat(5) { i ->
            val before = 100_000L + i * 1_000L
            BrowserRssProbe.record("get_readable", before, before + 1_000)
        }
        val summary = BrowserRssProbe.summary()
        assertFalse(
            "get_readable should NOT be LEAK-SUSPECT, got:\n$summary",
            summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `reset clears all aggregation`() {
        BrowserRssProbe.record("navigate", 100_000, 110_000)
        BrowserRssProbe.reset()
        val summary = BrowserRssProbe.summary()
        assertFalse(summary.contains("navigate"))
    }
}
