package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-context-limit-enforce] JVM tests for [ContextPolicy].
 *
 * The critical regression covered here: for large windows (128K/200K/1M, the
 * tiers used by group `contextLimitTokens` on modern Claude/Gemini models) the
 * old `check()` returned EXHAUSTED only when `exhaustedOnly` was true — which
 * is false in these tiers — so "exceeded the limit" was **advisory only** and
 * context could grow unbounded past the group's hard cap. These tests pin the
 * new behaviour: every tier reports EXHAUSTED at or past the hard window
 * ceiling, and stays at NEEDS_COMPACT / OK below it.
 */
class ContextPolicyTest {

    // --- OPTIONAL-BEFORE-HARD-CAP: large-window tiers keep their early warning --
    @Test
    fun `128K tier warns NEEDS_COMPACT at the compact line but not EXHAUSTED`() {
        val p = ContextPolicy.forContextWindow(128_000)
        // compactThreshold = 128K - 20K = 108K
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(120_000, 128_000))
    }

    @Test
    fun `128K tier is OK below the compact line`() {
        val p = ContextPolicy.forContextWindow(128_000)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(100_000, 128_000))
    }

    // --- HARD CAP (the fix): every tier reports EXHAUSTED at the window ceiling --
    @Test
    fun `128K tier reports EXHAUSTED at exactly the window ceiling`() {
        val p = ContextPolicy.forContextWindow(128_000)
        // Before the fix, exhaustedOnly=false meant this returned OK/NEEDS_COMPACT
        // and context kept growing past the cap. Now it must hard-stop.
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(128_000, 128_000))
    }

    @Test
    fun `128K tier reports EXHAUSTED past the window ceiling`() {
        val p = ContextPolicy.forContextWindow(128_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(200_000, 128_000))
    }

    @Test
    fun `200K tier reports EXHAUSTED at its ceiling`() {
        val p = ContextPolicy.forContextWindow(200_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(200_000, 200_000))
    }

    @Test
    fun `1M tier reports EXHAUSTED at its ceiling`() {
        val p = ContextPolicy.forContextWindow(1_000_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(1_000_000, 1_000_000))
    }

    @Test
    fun `EXHAUSTED at the hard ceiling takes priority over NEEDS_COMPACT`() {
        // The whole point of the hard cap: past the window ceiling the send
        // MUST be blocked, even though the compact line was also crossed.
        // Otherwise a user who ignored the compact warning keeps growing past
        // the group's "limit" forever (the old advisory-only behaviour).
        val p = ContextPolicy.forContextWindow(1_000_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(1_000_000, 1_000_000))
    }

    @Test
    fun `just under the hard ceiling stays NEEDS_COMPACT for large tiers`() {
        // 128K tier, compact line = 108K, ceiling = 128K. At 120K (< 128K) the
        // user should still be warned-but-allowed; EXHAUSTED only fires at/≥128K.
        val p = ContextPolicy.forContextWindow(128_000)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(120_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(128_000, 128_000))
    }

    // --- SMALL-WINDOW tiers keep their ORIGINAL earlier hard-stop (regression guard)
    @Test
    fun `32K tier still stops at the conservative offload line (exhaustedOnly)`() {
        // exhaustedOnly=true for <32K; exhaust line = window*9/10 when offload is 0.
        val p = ContextPolicy.forContextWindow(30_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(27_000, 30_000)) // 90%
    }

    @Test
    fun `32K tier is OK while well under the ceiling`() {
        val p = ContextPolicy.forContextWindow(30_000)
        assertEquals(ContextPolicy.CheckResult.OK, p.check(10_000, 30_000))
    }

    @Test
    fun `64K window still stops at its earlier exhaustedOnly line AND at the hard ceiling`() {
        // 60K window → 32K–64K tier: exhaustedOnly=true, offloadThreshold = 60K-10K = 50K.
        val p = ContextPolicy.forContextWindow(60_000)
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(50_000, 60_000)) // earlier line
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(60_000, 60_000)) // hard ceiling
    }

    @Test
    fun `77K window is advisory NEEDS_COMPACT below ceiling, EXHAUSTED at ceiling`() {
        // 77K → 64K–128K tier: exhaustedOnly=false. Compact line = 77K-10K = 67K.
        val p = ContextPolicy.forContextWindow(77_000)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, p.check(70_000, 77_000)) // warn
        assertEquals(ContextPolicy.CheckResult.EXHAUSTED, p.check(77_000, 77_000))      // hard cap
    }

    // --- policy threshold shapes stay sane per tier ---
    @Test
    fun `policy thresholds keep headroom below the window`() {
        for (window in listOf(32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000)) {
            val p = ContextPolicy.forContextWindow(window)
            if (p.offloadThreshold > 0) {
                assertTrue("offload below window for $window", p.offloadThreshold < window)
            }
            if (p.compactThreshold > 0) {
                assertTrue("compact below window for $window", p.compactThreshold < window)
            }
        }
    }
}
