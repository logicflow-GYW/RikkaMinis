package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/place-storm-residual-2nd-source] Regression test for the
 * same-size onPlaced report throttle.
 *
 * Log forensics (minis-2026-09-01__4_.log, AFTER the SIMPLE_FOLLOW clamp
 * guard landed): during IME/insets animations and during user drags over a
 * taller-than-viewport newest row (992x7723), onPlaced re-fires every frame
 * (60Hz) with an IDENTICAL size. Each fire previously emitted a full
 * PerfLongCtx line (string build + native-heap read + logcat IPC) — 608
 * lines in 17s, ~91% of them from a plain user drag. The logging itself was
 * a per-frame cost amplifier on the main thread.
 *
 * The throttle: report only when the size changed, or when more than
 * repeatReportGapMs elapsed since the last report of the SAME size (so a
 * genuinely re-placed identical-size row still surfaces eventually, e.g. a
 * long drag that pauses on one size for seconds).
 */
class PlacedReportThrottleTest {

    @Test
    fun `first placement always reports`() {
        // lastKey == null: the session's very first placed event must reach
        // the log — cold-open diagnostics (JankDiag summary, first-frame
        // timings) key off it.
        assertTrue(shouldReportPlaced(lastKey = null, lastAtMs = 0L, sizeKey = "992x185", nowMs = 1_000L))
    }

    @Test
    fun `identical size within gap is suppressed`() {
        // The storm configuration: same 992x7723 size firing every 16ms.
        // All fires within the 2s gap must be suppressed.
        assertFalse(
            shouldReportPlaced(
                lastKey = "992x7723", lastAtMs = 10_000L,
                sizeKey = "992x7723", nowMs = 10_016L,
            ),
        )
        assertFalse(
            shouldReportPlaced(
                lastKey = "992x7723", lastAtMs = 10_000L,
                sizeKey = "992x7723", nowMs = 11_000L,
            ),
        )
    }

    @Test
    fun `identical size after gap resurfaces`() {
        // A long drag that pauses on the same size for >2s: the row was
        // genuinely re-placed (not animation noise) — report it so the trace
        // shows the row is still being laid out.
        assertTrue(
            shouldReportPlaced(
                lastKey = "992x7723", lastAtMs = 10_000L,
                sizeKey = "992x7723", nowMs = 12_100L,
            ),
        )
    }

    @Test
    fun `size change always reports`() {
        // Real content growth changes the height — the interesting signal.
        // Must report regardless of how recent the last report was.
        assertTrue(
            shouldReportPlaced(
                lastKey = "992x185", lastAtMs = 10_000L,
                sizeKey = "992x192", nowMs = 10_016L,
            ),
        )
    }

    @Test
    fun `streaming growth cadence fully preserved`() {
        // A streaming turn grows the newest row continuously: sizes march
        // 185→192→230→548→… each placed event has a DIFFERENT key, so every
        // one reports — the pre-throttle diagnostic timeline for content
        // growth is bit-identical. Only the frozen-size loop collapses.
        var lastKey: String? = null
        var lastAt = 0L
        var reported = 0
        var suppressed = 0
        // 50 growth steps, each 1s apart, each with a new size
        for (step in 0 until 50) {
            val sizeKey = "992x${185 + step * 3}"
            val now = step * 1_000L
            if (shouldReportPlaced(lastKey, lastAt, sizeKey, now)) {
                reported++
                lastKey = sizeKey
                lastAt = now
            } else {
                suppressed++
            }
        }
        assertTrue(reported == 50)
        assertTrue(suppressed == 0)
    }

    @Test
    fun `the 2026-09-01 drag storm collapses to gap cadence`() {
        // Replay the exact storm shape: 60Hz fires of the SAME size for 6
        // seconds (user dragging the 7723px row). Pre-fix: 360+ log lines.
        // Post-fix: first fire + one resurface per 2s gap ≈ 4 lines.
        var lastKey: String? = null
        var lastAt = 0L
        var reported = 0
        // 6 seconds at 60Hz = 360 fires
        for (frame in 0 until 360) {
            val now = frame * 16L
            if (shouldReportPlaced(lastKey, lastAt, "992x7723", now)) {
                reported++
                lastKey = "992x7723"
                lastAt = now
            }
        }
        // Expected: frame 0, then resurfaces at ~2s, ~4s (next would be 6s —
        // exactly at the end). 2-4 lines, never 360.
        assertTrue("storm should collapse to a handful of lines, got $reported", reported <= 5)
        assertTrue(reported >= 2)
    }
}
