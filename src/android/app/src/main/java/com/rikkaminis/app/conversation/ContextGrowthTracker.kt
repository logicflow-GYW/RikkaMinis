package com.rikkaminis.app.conversation

/**
 * [T-adaptive-compact-reserve] Measures how much context this conversation adds
 * per agent-loop turn, so the auto-compact trigger can reserve headroom instead
 * of assuming a single turn can only add a few thousand tokens.
 *
 * The problem it solves: the compaction decision samples the context size once
 * per turn (the previous turn's API usage), and the trigger bands are constants
 * (20K wide in the large-window tiers). A model that emits long answers plus
 * several large tool results can add more than a band's width in one turn, so
 * the sampler jumps from "OK" straight past the compact line to the hard
 * ceiling — where sending is blocked and the only way out is a manual
 * /compact. The faster and the more verbose the model, the more often the
 * compact line is never observed at all. Reserving `reserveTurns ×
 * perTurnGrowth` moves the line back so it is always crossed first.
 *
 * Estimator choice: a *decay-max* (peak-hold) rather than a plain average,
 * because what matters is the upper end of recent growth, not its mean —
 * reserving for the average still lets a typical-then-large turn pair jump the
 * band. A spike decays geometrically, so the reserve relaxes back down (and
 * with it the early-compaction cost) within a handful of calm turns.
 *
 * Pure logic, no Android dependencies — JVM-testable.
 */
class ContextGrowthTracker(
    private val decay: Double = DEFAULT_DECAY,
    private val reserveTurns: Int = DEFAULT_RESERVE_TURNS,
) {
    /** Current per-turn growth estimate in tokens (peak-hold with decay). */
    var perTurnEstimate: Long = 0L
        private set

    /** Number of accepted (positive) samples. 0 = nothing measured yet. */
    var samples: Int = 0
        private set

    /**
     * Feed one observed turn-to-turn delta.
     *
     * Non-positive deltas are ignored on purpose: a shrink means a compact or a
     * trim ran, not that the conversation stopped growing, and letting it drag
     * the estimate down would unwind the reserve exactly when the session has
     * proven it grows fast.
     */
    fun sample(deltaTokens: Long) {
        if (deltaTokens <= 0) return
        perTurnEstimate = if (samples == 0) {
            deltaTokens
        } else {
            maxOf(deltaTokens, (perTurnEstimate * decay).toLong())
        }
        if (samples < Int.MAX_VALUE) samples++
    }

    /**
     * Headroom to reserve for the next [reserveTurns] turns, in tokens.
     *
     * Returns 0 until at least one turn has been measured, which is what keeps
     * the cold-start decision identical to the pre-fix behaviour. Capped at
     * `contextWindow / [MAX_RESERVE_WINDOW_FRACTION]` so a single pathological
     * turn cannot reserve an unreasonable share of the window.
     */
    fun reserveTokens(contextWindow: Int): Int {
        if (samples <= 0 || perTurnEstimate <= 0 || contextWindow <= 0) return 0
        val raw = perTurnEstimate * reserveTurns
        val cap = (contextWindow / MAX_RESERVE_WINDOW_FRACTION).toLong()
        return minOf(raw, cap).toInt()
    }

    /** Drop all measurements (session switch / explicit reset). */
    fun reset() {
        perTurnEstimate = 0L
        samples = 0
    }

    companion object {
        /** Multiplier applied to the previous estimate on every new sample. */
        const val DEFAULT_DECAY = 0.6

        /** How many future turns of growth the reserve must cover. */
        const val DEFAULT_RESERVE_TURNS = 3

        /** The reserve never exceeds `window / this` (denominator). */
        const val MAX_RESERVE_WINDOW_FRACTION = 3
    }
}
