package com.rikkaminis.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [StorageBudget] — the DECIDE edge of the storage loop.
 *
 * Pins the three decisions that actually reach a user or the model:
 *  - the threshold shape (absolute floor vs partition-relative arm),
 *  - the notification cool-down (first crossing / growth / weekly / shrinking),
 *  - the prompt line (absent before a sample, "OVER" only past the budget).
 *
 * Everything here is pure: no Context, no StatFs, no filesystem walk. The
 * device-side walk is [StorageBudget.sample], exercised by the app itself.
 */
class StorageBudgetTest {

    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    private fun usage(
        app: Long,
        // 64 GiB /data ⇒ 5% = 3.2 GiB, so the 6 GiB floor wins — the common
        // phone case. Partition-relative behaviour is pinned separately.
        total: Long = 64L * gib,
        free: Long = 20L * gib,
        sampledAt: Long = 1_000L,
    ) = StorageBudget.Usage(
        appBytes = app,
        shellBytes = app / 2,
        freeBytes = free,
        totalBytes = total,
        sampledAt = sampledAt,
    )

    // ── threshold ────────────────────────────────────────────────────────

    @Test
    fun thresholdFloorsAtSixGibOnSmallPartitions() {
        // 128 GB /data → 5% = 6.4 GB, floor 6 GB → 6.4 GB wins.
        assertEquals(64L * gib / 10, StorageBudget.thresholdFor(128L * gib))
        // 64 GB /data → 5% = 3.2 GB → floor wins.
        assertEquals(6L * gib, StorageBudget.thresholdFor(64L * gib))
        // Unknown partition → floor (never 0, never NaN-ish behaviour).
        assertEquals(6L * gib, StorageBudget.thresholdFor(0L))
        assertEquals(6L * gib, StorageBudget.thresholdFor(-1L))
    }

    @Test
    fun overBudgetFlipsExactlyAtThreshold() {
        val u = usage(app = 6L * gib)
        assertTrue("exactly at the 6 GiB floor counts as over", u.overBudget)
        assertFalse(usage(app = 6L * gib - 1).overBudget)
    }

    // ── notification gate ────────────────────────────────────────────────

    @Test
    fun firstCrossingNotifies() {
        assertTrue(StorageBudget.shouldNotify(1_000L, 0L, 0L, usage(app = 8L * gib)))
    }

    @Test
    fun underBudgetNeverNotifies() {
        assertFalse(StorageBudget.shouldNotify(1_000L, 0L, 0L, usage(app = 2L * gib)))
        // Even when a stale "already notified" record exists.
        assertFalse(
            StorageBudget.shouldNotify(
                1_000L, 500L, 9L * gib, usage(app = 2L * gib),
            ),
        )
    }

    @Test
    fun staysQuietInsideTheWindowWithoutGrowth() {
        val now = 10L * 24 * 60 * 60 * 1000
        val lastAt = now - 24L * 60 * 60 * 1000 // yesterday
        // Grew by 10% (< 25%) and the weekly window has not elapsed.
        assertFalse(StorageBudget.shouldNotify(now, lastAt, 8L * gib, usage(app = 88L * gib / 10)))
    }

    @Test
    fun growthPastTheRatioRenotifiesEvenInsideTheWindow() {
        val now = 10L * 24 * 60 * 60 * 1000
        val lastAt = now - 24L * 60 * 60 * 1000
        // 8 GiB → 10 GiB = +25% exactly → re-notify.
        assertTrue(StorageBudget.shouldNotify(now, lastAt, 8L * gib, usage(app = 10L * gib)))
    }

    @Test
    fun weeklyWindowRenotifiesWithoutGrowth() {
        val week = 7L * 24 * 60 * 60 * 1000
        val now = 100L * week
        assertTrue(StorageBudget.shouldNotify(now, now - week, 8L * gib, usage(app = 8L * gib)))
        assertFalse(
            StorageBudget.shouldNotify(now, now - week + 1, 8L * gib, usage(app = 8L * gib)),
        )
    }

    // ── prompt line ──────────────────────────────────────────────────────

    @Test
    fun promptLineIsNullBeforeAnySample() {
        StorageBudget.setCachedForTest(null)
        assertNull(StorageBudget.promptLine())
    }

    @Test
    fun promptLineReportsFootprintWithoutShoutingWhenUnderBudget() {
        StorageBudget.setCachedForTest(usage(app = 2L * gib + 512 * mib))
        val line = StorageBudget.promptLine()
        assertNotNull(line)
        assertTrue(line!!.contains("2.5 GB"))
        assertTrue(line.contains("device free"))
        assertFalse("under budget must not carry the OVER warning", line.contains("OVER the"))
        StorageBudget.setCachedForTest(null)
    }

    @Test
    fun promptLineFlagsOverBudgetAndNamesTheCheapExit() {
        StorageBudget.setCachedForTest(usage(app = 14L * gib))
        val line = StorageBudget.promptLine()
        assertNotNull(line)
        assertTrue(line!!.contains("OVER the"))
        assertTrue("must point at the cheap cleanup tool", line.contains("minis-fastio rm -r"))
        StorageBudget.setCachedForTest(null)
    }

    // ── formatting ───────────────────────────────────────────────────────

    @Test
    fun formatterSwitchesUnitsAtBinaryBoundaries() {
        assertEquals("1.0 GB", StorageBudget.fmt(gib))
        assertEquals("0.5 GB", StorageBudget.fmt(gib / 2))
        assertEquals("512 MB", StorageBudget.fmt(512 * mib))
        assertEquals("900 KB", StorageBudget.fmt(900 * 1024))
        assertEquals("42 B", StorageBudget.fmt(42))
    }
}
