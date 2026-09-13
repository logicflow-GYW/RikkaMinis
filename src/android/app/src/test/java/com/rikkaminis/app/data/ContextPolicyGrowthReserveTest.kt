package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-adaptive-compact-window] + [T-adaptive-compact-reserve] JVM tests for the
 * two trigger-geometry fixes on [ContextPolicy].
 *
 * What went wrong before these changes (all three are structural, not tuning):
 *
 *  1. Headroom was an absolute constant, so the *fraction* of the window at
 *     which compaction fired depended on the window size: 84% on 128K, 90% on
 *     200K, 98% on 1M. Bigger models → later compaction.
 *  2. The band between the compact line and the hard ceiling is a constant
 *     (20K in the large tiers), so a single turn wider than the band jumps
 *     from OK straight to EXHAUSTED and the compact line is never observed.
 *  3. Nothing reserved room for the turns that happen between two samples.
 *
 * The tests below pin the fix AND pin the no-regression promise: on the windows
 * this policy was originally calibrated against (≤ ~133K) every threshold is
 * byte-identical to the old formula.
 */
class ContextPolicyGrowthReserveTest {

    // ── 1. proportional lines on large windows ────────────────────────────

    @Test
    fun `1M window compacts at 85 percent instead of 98`() {
        val p = ContextPolicy.forContextWindow(1_000_000)
        // Pre-fix: 1_000_000 - 20_000 = 980_000 (98% of the window).
        assertEquals(850_000, p.compactThreshold)
        // Pre-fix: 960_000 (96%).
        assertEquals(750_000, p.offloadThreshold)
        assertEquals(650_000, p.offloadTarget)
    }

    @Test
    fun `200K window compacts at 85 percent instead of 90`() {
        val p = ContextPolicy.forContextWindow(200_000)
        // Pre-fix: 180_000 / 160_000 / 140_000.
        assertEquals(170_000, p.compactThreshold)
        assertEquals(150_000, p.offloadThreshold)
        assertEquals(130_000, p.offloadTarget)
    }

    @Test
    fun `400K window compacts at 85 percent instead of 95`() {
        val p = ContextPolicy.forContextWindow(400_000)
        assertEquals(340_000, p.compactThreshold) // pre-fix: 380_000
        assertEquals(300_000, p.offloadThreshold) // pre-fix: 360_000
    }

    @Test
    fun `compact line never sits above 90 percent of the window`() {
        for (window in listOf(128_000, 133_000, 200_000, 400_000, 1_000_000, 2_000_000)) {
            val p = ContextPolicy.forContextWindow(window)
            if (p.compactThreshold == 0) continue
            val pct = p.compactThreshold * 100.0 / window
            assertTrue("compact line for $window is $pct% of the window", pct <= 90.0)
        }
    }

    // ── 2. no regression on the windows the old constants were tuned for ──

    @Test
    fun `128K window keeps its exact pre-fix thresholds`() {
        val p = ContextPolicy.forContextWindow(128_000)
        assertEquals(108_000, p.compactThreshold) // W - 20K
        assertEquals(88_000, p.offloadThreshold)  // W - 40K
        assertEquals(68_000, p.offloadTarget)     // W - 60K
    }

    @Test
    fun `64K-128K tier keeps its exact pre-fix thresholds at the low end`() {
        val p = ContextPolicy.forContextWindow(64_000)
        assertEquals(54_000, p.compactThreshold)  // W - 10K
        assertEquals(44_000, p.offloadThreshold)  // W - 20K
        assertEquals(34_000, p.offloadTarget)     // W - 30K
    }

    /** The pre-fix thresholds, reproduced verbatim so the diffs can be compared. */
    private data class Legacy(val offload: Int, val target: Int, val compact: Int)

    private fun legacyThresholds(w: Int): Legacy = when {
        w < 32_000 -> Legacy(0, 0, 0)
        w < 64_000 -> Legacy(w - 10_000, w - 15_000, 0)
        w < 128_000 -> Legacy(w - 20_000, w - 30_000, w - 10_000)
        else -> Legacy(w - 40_000, w - 60_000, w - 20_000)
    }

    @Test
    fun `no threshold ever moves later than the pre-fix formula`() {
        // The whole point of the change is "never later, sometimes earlier".
        // Anything that moved *later* would be a silent regression for the
        // windows the old constants were calibrated against.
        var window = 8_000
        while (window <= 2_000_000) {
            val old = legacyThresholds(window)
            val p = ContextPolicy.forContextWindow(window)
            assertTrue("offload moved later at $window", p.offloadThreshold <= old.offload)
            assertTrue("compact moved later at $window", p.compactThreshold <= old.compact)
            window += 991
        }
    }

    @Test
    fun `the large tier is byte-identical to the pre-fix formula up to 133K`() {
        // Above 128K the absolute term is still the smaller one until
        // W − 20K = 0.85W, i.e. W = 133_333. This is the range where "no
        // behaviour change at all" must hold exactly.
        for (window in listOf(128_000, 130_000, 133_000)) {
            val old = legacyThresholds(window)
            val p = ContextPolicy.forContextWindow(window)
            assertEquals(old.offload, p.offloadThreshold)
            assertEquals(old.target, p.offloadTarget)
            assertEquals(old.compact, p.compactThreshold)
        }
    }

    @Test
    fun `small tiers are untouched`() {
        val tiny = ContextPolicy.forContextWindow(30_000)
        assertEquals(0, tiny.offloadThreshold)
        assertEquals(0, tiny.compactThreshold)
        assertTrue(tiny.exhaustedOnly)
        val mid = ContextPolicy.forContextWindow(60_000)
        assertEquals(50_000, mid.offloadThreshold)
        assertEquals(0, mid.compactThreshold)
        assertTrue(mid.exhaustedOnly)
    }

    // ── 3. shape invariants across the whole range ────────────────────────

    @Test
    fun `threshold ordering holds for every window size`() {
        var window = 8_000
        while (window <= 2_000_000) {
            val p = ContextPolicy.forContextWindow(window)
            assertTrue("offload < window for $window", p.offloadThreshold < window)
            assertTrue("compact < window for $window", p.compactThreshold < window)
            assertTrue("offloadTarget >= 0 for $window", p.offloadTarget >= 0)
            if (p.offloadThreshold > 0 && p.offloadTarget > 0) {
                assertTrue(
                    "offloadTarget <= offloadThreshold for $window",
                    p.offloadTarget <= p.offloadThreshold,
                )
            }
            if (p.compactThreshold > 0 && p.offloadThreshold > 0) {
                assertTrue(
                    "offload must fire before the compact line for $window",
                    p.offloadThreshold < p.compactThreshold,
                )
            }
            window += 997
        }
    }

    // ── 4. reservedForGrowth ──────────────────────────────────────────────

    @Test
    fun `zero or negative reserve is the identity`() {
        val p = ContextPolicy.forContextWindow(200_000)
        assertEquals(p, p.reservedForGrowth(0, 200_000))
        assertEquals(p, p.reservedForGrowth(-5_000, 200_000))
        assertEquals(p, p.reservedForGrowth(50_000, 0))
    }

    @Test
    fun `reserve shifts every line down by the same amount`() {
        val base = ContextPolicy.forContextWindow(200_000)
        val shifted = base.reservedForGrowth(30_000, 200_000)
        assertEquals(base.compactThreshold - 30_000, shifted.compactThreshold)
        assertEquals(base.offloadThreshold - 30_000, shifted.offloadThreshold)
        assertEquals(base.offloadTarget - 30_000, shifted.offloadTarget)
        assertEquals(base.exhaustedOnly, shifted.exhaustedOnly)
        assertEquals(base.manualCompactAllowed, shifted.manualCompactAllowed)
    }

    @Test
    fun `reserve never pulls the lowest line below half the window`() {
        for (window in listOf(64_000, 128_000, 200_000, 1_000_000)) {
            val base = ContextPolicy.forContextWindow(window)
            // Ask for far more than the cap allows.
            val shifted = base.reservedForGrowth(window, window)
            val floor = window / 2
            assertTrue("offload line for $window fell below the floor", shifted.offloadThreshold >= floor)
            assertTrue("compact line for $window fell below the floor", shifted.compactThreshold >= floor)
            assertTrue("offloadTarget for $window went negative", shifted.offloadTarget >= 0)
            assertTrue(
                "ordering broken by the shift at $window",
                shifted.offloadTarget <= shifted.offloadThreshold &&
                    shifted.offloadThreshold < shifted.compactThreshold,
            )
        }
    }

    @Test
    fun `reserve leaves exhausted-only tiers alone`() {
        // In the 32K–64K tier `offloadThreshold` IS the hard-block line (check()
        // returns EXHAUSTED at it), so shifting it would block sends earlier
        // than before instead of buying compaction headroom.
        for (window in listOf(20_000, 40_000, 60_000)) {
            val base = ContextPolicy.forContextWindow(window)
            assertTrue("expected an exhausted-only tier at $window", base.exhaustedOnly)
            assertEquals(base, base.reservedForGrowth(window, window))
            assertEquals(
                "the hard-block line moved at $window",
                base.offloadThreshold,
                base.reservedForGrowth(window, window).offloadThreshold,
            )
        }
    }

    @Test
    fun `reserve is monotonic in the reserve size`() {
        val base = ContextPolicy.forContextWindow(200_000)
        var previous = base.compactThreshold
        var reserve = 0
        while (reserve <= 200_000) {
            val line = base.reservedForGrowth(reserve, 200_000).compactThreshold
            assertTrue("compact line must not move back up as the reserve grows", line <= previous)
            previous = line
            reserve += 5_000
        }
    }

    @Test
    fun `a huge reserve cannot disable offload`() {
        for (window in listOf(64_000, 128_000, 200_000, 1_000_000)) {
            val shifted = ContextPolicy.forContextWindow(window).reservedForGrowth(window, window)
            assertTrue("offload must stay enabled at $window", shifted.offloadThreshold > 0)
        }
    }

    // ── 5. the two fixes compose ──────────────────────────────────────────

    @Test
    fun `proportional and reserved policies compose without losing ordering`() {
        for (window in listOf(133_000, 200_000, 400_000, 1_000_000)) {
            val p = ContextPolicy.forContextWindow(window).reservedForGrowth(window / 3, window)
            assertTrue(p.offloadTarget >= 0)
            assertTrue("target <= offload for $window", p.offloadTarget <= p.offloadThreshold)
            assertTrue("offload < compact for $window", p.offloadThreshold < p.compactThreshold)
            assertTrue("compact < window for $window", p.compactThreshold < window)
        }
    }
}
