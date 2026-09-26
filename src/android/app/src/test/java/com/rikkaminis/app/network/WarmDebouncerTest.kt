package com.rikkaminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* JVM unit tests for [WarmDebouncer] (absorb-network-pack). Fake clock —
 * no real time, no HTTP.
 */
class WarmDebouncerTest {

    private fun debouncer(startMs: Long): Pair<WarmDebouncer, () -> Long> {
        var now = startMs
        val d = WarmDebouncer { now }
        return d to { now }
    }

    @Test
    fun tryBegin_withinSuccessWindow_collapses() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        assertTrue(d.tryBegin("https://api.a.com:443"))
        now += WarmDebouncer.DEBOUNCE_MS - 1
        assertFalse("a warmup inside the 60s window must be dropped", d.tryBegin("https://api.a.com:443"))
    }

    @Test
    fun tryBegin_afterSuccessWindow_reArms() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        assertTrue(d.tryBegin("https://api.a.com:443"))
        now += WarmDebouncer.DEBOUNCE_MS
        assertTrue(d.tryBegin("https://api.a.com:443"))
    }

    @Test
    fun markFailure_reArmsAfterShortWindow_notFullWindow() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        assertTrue(d.tryBegin("https://api.a.com:443"))
        // Warmup fails 10s later: next warm() must be eligible after 8s
        // (short failure window), not after the full 60s success window.
        now += 10_000L
        d.markFailure("https://api.a.com:443")
        now += WarmDebouncer.FAILURE_DEBOUNCE_MS - 1
        assertFalse("within the 8s failure window: still debounced", d.tryBegin("https://api.a.com:443"))
        now += 1L
        assertTrue("after the 8s failure window: re-armed", d.tryBegin("https://api.a.com:443"))
    }

    @Test
    fun onNetworkChanged_clearsStamps_soAnyOriginReWarms() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        assertTrue(d.tryBegin("https://api.a.com:443"))
        assertTrue(d.tryBegin("https://api.b.com:443"))
        // Just-warmed: inside the success window a normal warm() would drop.
        now += 1_000L
        // Network transition clears the stamps...
        val targets = d.onNetworkChanged()
        assertEquals(listOf("https://api.b.com:443", "https://api.a.com:443"), targets)
        // ...so an immediate warm() is eligible again.
        assertTrue("after network change the stamp must not block", d.tryBegin("https://api.a.com:443"))
    }

    @Test
    fun onNetworkChanged_returnsRecentOrigins_newestFirst_bounded() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        // 5 distinct origins, one per hour: recent set must keep the newest 3.
        for (i in 1..5) {
            assertTrue(d.tryBegin("https://host$i.com:443"))
            now += 3_600_000L
        }
        val targets = d.onNetworkChanged()
        assertEquals(
            listOf("https://host5.com:443", "https://host4.com:443", "https://host3.com:443"),
            targets,
        )
    }

    @Test
    fun onNetworkChanged_emptyHistory_returnsEmpty() {
        val (d, _) = debouncer(1_000_000L)
        assertTrue(d.onNetworkChanged().isEmpty())
    }

    @Test
    fun tryBegin_deduplicatesRecentSet() {
        var now = 1_000_000L
        val d = WarmDebouncer { now }
        assertTrue(d.tryBegin("https://a.com:443"))
        now += WarmDebouncer.DEBOUNCE_MS
        assertTrue(d.tryBegin("https://b.com:443"))
        now += WarmDebouncer.DEBOUNCE_MS
        assertTrue(d.tryBegin("https://a.com:443"))
        val targets = d.onNetworkChanged()
        // a was re-warmed last → newest first, and b survives in the window.
        assertEquals("https://a.com:443", targets.first())
        assertTrue(targets.contains("https://b.com:443"))
    }
}
