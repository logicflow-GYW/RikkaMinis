package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-adaptive-compact-reserve] JVM tests for [ContextGrowthTracker] plus the
 * end-to-end trigger simulation that motivates it.
 *
 * The failure being fixed is a *sampling* failure, not a tuning failure: the
 * auto-compact decision runs once per turn with the previous turn's usage, and
 * the band between the compact line and the hard ceiling is a constant. A model
 * that adds more than one band's width per turn therefore goes OK → EXHAUSTED
 * with the compact line never observed — sending gets blocked and the user has
 * to compact by hand. The simulation below reproduces that with the pre-fix
 * policy (negative control) and shows the reserve preventing it.
 */
class ContextGrowthTrackerTest {

    // ── estimator behaviour ───────────────────────────────────────────────

    @Test
    fun `no reserve until a turn has been measured`() {
        val t = ContextGrowthTracker()
        assertEquals(0L, t.perTurnEstimate)
        assertEquals(0, t.reserveTokens(200_000))
    }

    @Test
    fun `reserve is the estimate times the reserved turns`() {
        val t = ContextGrowthTracker()
        t.sample(10_000)
        assertEquals(10_000L, t.perTurnEstimate)
        assertEquals(30_000, t.reserveTokens(1_000_000)) // 3 turns × 10K, well under the cap
    }

    @Test
    fun `a larger turn raises the estimate immediately`() {
        val t = ContextGrowthTracker()
        t.sample(10_000)
        t.sample(40_000)
        assertEquals(40_000L, t.perTurnEstimate)
    }

    @Test
    fun `the estimate decays back down after a spike`() {
        val t = ContextGrowthTracker()
        t.sample(80_000)
        assertEquals(80_000L, t.perTurnEstimate)
        repeat(10) { t.sample(1_000) }
        // 80_000 × 0.6^n falls under the 1_000 floor after nine calm turns.
        assertEquals(1_000L, t.perTurnEstimate)
    }

    @Test
    fun `shrink samples are ignored so a compact does not unwind the reserve`() {
        val t = ContextGrowthTracker()
        t.sample(30_000)
        t.sample(-120_000) // a compact/trim just removed a lot of context
        t.sample(0)
        assertEquals(30_000L, t.perTurnEstimate)
        assertEquals(1, t.samples) // the two non-positive deltas were not counted
    }

    @Test
    fun `reserve is capped at a third of the window`() {
        val t = ContextGrowthTracker()
        t.sample(500_000)
        assertEquals(200_000 / 3, t.reserveTokens(200_000))
    }

    @Test
    fun `reset clears the measurement`() {
        val t = ContextGrowthTracker()
        t.sample(25_000)
        t.reset()
        assertEquals(0L, t.perTurnEstimate)
        assertEquals(0, t.samples)
        assertEquals(0, t.reserveTokens(200_000))
    }

    // ── end-to-end trigger simulation ─────────────────────────────────────

    /** Which trigger geometry the simulated loop runs against. */
    private enum class Geometry {
        /** Pre-fix: absolute headroom only, no reserve. */
        LEGACY,
        /** Proportional clamp only (T-adaptive-compact-window). */
        TIER,
        /** Proportional clamp + growth reserve (both fixes). */
        ADAPTIVE,
    }

    /** The pre-fix thresholds, reproduced verbatim for the control run. */
    private fun legacyPolicy(w: Int): ContextPolicy = when {
        w < 32_000 -> ContextPolicy(0, 0, 0, exhaustedOnly = true, manualCompactAllowed = false)
        w < 64_000 -> ContextPolicy(w - 10_000, w - 15_000, 0, exhaustedOnly = true, manualCompactAllowed = true)
        w < 128_000 -> ContextPolicy(w - 20_000, w - 30_000, w - 10_000, exhaustedOnly = false, manualCompactAllowed = true)
        else -> ContextPolicy(w - 40_000, w - 60_000, w - 20_000, exhaustedOnly = false, manualCompactAllowed = true)
    }

    /**
     * One agent loop, one turn per iteration: decide → (if it proceeds) the
     * turn grows the context by [perTurn] → feed that delta back as a sample.
     * Returns how the run ended.
     */
    private fun simulate(
        window: Int,
        startTokens: Int,
        perTurn: Int,
        turns: Int,
        geometry: Geometry,
    ): String {
        val tracker = ContextGrowthTracker()
        var tokens = startTokens
        for (turn in 0 until turns) {
            val base = when (geometry) {
                Geometry.LEGACY -> legacyPolicy(window)
                Geometry.TIER, Geometry.ADAPTIVE -> ContextPolicy.forContextWindow(window)
            }
            val policy = if (geometry == Geometry.ADAPTIVE) {
                base.reservedForGrowth(tracker.reserveTokens(window), window)
            } else {
                base
            }
            val decision = ContextCompactor.decide(
                estimatedTokens = tokens,
                contextWindow = window,
                policy = policy,
                tailTokens = 40_000,
                isCompacting = false,
            )
            when (decision) {
                ContextCompactor.Decision.AUTO_COMPACT -> return "AUTO_COMPACT@$tokens"
                ContextCompactor.Decision.EXHAUSTED -> return "EXHAUSTED@$tokens"
                else -> Unit
            }
            val before = tokens
            tokens += perTurn
            if (geometry == Geometry.ADAPTIVE) tracker.sample((tokens - before).toLong())
        }
        return "STILL_OK@$tokens"
    }

    // Failure mode 2: the band between the compact line and the ceiling is a
    // constant, so a single turn wider than the band steps clean over it.
    private val bandJumpWindow = 200_000
    private val bandJump = { geometry: Geometry ->
        simulate(window = bandJumpWindow, startTokens = 120_000, perTurn = 40_000, turns = 8, geometry = geometry)
    }

    @Test
    fun `negative control - the pre-fix policy jumps from OK past the compact line to the ceiling`() {
        // 200K window, 40K per turn: the compact band (180K..200K) is 20K wide,
        // so the run goes 160K (OK) → 200K (EXHAUSTED) and never sees it.
        assertEquals("EXHAUSTED@200000", bandJump(Geometry.LEGACY))
    }

    @Test
    fun `the proportional clamp alone does not fix the band jump`() {
        // Pinning why the reserve is needed as well: 85% of 200K is 170K, and
        // the run still steps from 160K (OK) straight to the ceiling.
        assertEquals("EXHAUSTED@200000", bandJump(Geometry.TIER))
    }

    @Test
    fun `the growth reserve turns the same run into a compact decision`() {
        val outcome = bandJump(Geometry.ADAPTIVE)
        assertTrue("expected an AUTO_COMPACT decision, got $outcome", outcome.startsWith("AUTO_COMPACT"))
    }

    // Failure mode 1: with absolute headroom, a bigger window compacts later
    // as a fraction of the window (84% → 90% → 98%).
    @Test
    fun `negative control - a 1M window under the pre-fix policy ends at the ceiling`() {
        // Compact line was 980K, i.e. 98% of the window: 880K (OK) → 1M (blocked).
        assertEquals(
            "EXHAUSTED@1000000",
            simulate(window = 1_000_000, startTokens = 400_000, perTurn = 120_000, turns = 8, geometry = Geometry.LEGACY),
        )
    }

    @Test
    fun `the proportional clamp fixes the large-window drift on its own`() {
        assertTrue(
            simulate(window = 1_000_000, startTokens = 400_000, perTurn = 120_000, turns = 8, geometry = Geometry.TIER)
                .startsWith("AUTO_COMPACT"),
        )
    }

    @Test
    fun `a slow run is not made to compact any earlier by the reserve`() {
        // 3K per turn is ordinary chat: the reserve stays 9K, which on a 200K
        // window moves the line from 170K to 161K — far above where twenty
        // ordinary turns land.
        assertEquals(
            "STILL_OK@80000",
            simulate(window = 200_000, startTokens = 20_000, perTurn = 3_000, turns = 20, geometry = Geometry.ADAPTIVE),
        )
    }
}
