package com.rikkaminis.app.data

/**
 * Pure-logic policy that decides, given a token estimate and the model's
 * context window, whether the agent loop should offload big tool results,
 * trigger a compact/summarize pass, or surface "context exhausted" to the UI.
 *
 * Mirrors iOS `ContextPolicy` (ContextPolicy.swift): 4-tier thresholds keyed
 * off the model's context window, with no summarization algorithm embedded —
 * the actual compact + offload execution lives in the agent loop, this struct
 * only answers "what state are we in?".
 *
 * Thresholds intentionally leave headroom (10k/20k/40k below the ceiling) so
 * one more agent turn can still fit before the user sees any disruption.
 */
data class ContextPolicy(
    /** Above this token count, next tool result should be written to disk. 0 disables. */
    val offloadThreshold: Int,
    /** After offload, shrink context toward this target (lower than threshold). */
    val offloadTarget: Int,
    /** Above this, trigger a compact/summarize pass. 0 disables. */
    val compactThreshold: Int,
    /** When true, the tier is too small for auto-compact; only surface .exhausted. */
    val exhaustedOnly: Boolean,
    /** Whether the "Compact now" button is offered in the UI. */
    val manualCompactAllowed: Boolean,
) {
    enum class CheckResult { OK, NEEDS_COMPACT, EXHAUSTED }

    /**
     * Classify the current turn's token pressure. Priority:
     *   1. [T-context-limit-enforce] At/past the hard window ceiling (ALL tiers,
     *      not just `exhaustedOnly`) → EXHAUSTED. This makes the group's
     *      `contextLimitTokens` a true "send blocked at or past this point"
     *      hard cap for large windows (128K/200K/1M) that previously never
     *      reached EXHAUSTED because `exhaustedOnly` was false and the compact
     *      warning (below) fired first — so context could grow unbounded past
     *      the user-visible "limit".
     *   2. If compact is available and tokens crossed [compactThreshold] (still
     *      below the ceiling) → NEEDS_COMPACT (advisory warn only).
     *   3. If this is a small-window tier (`exhaustedOnly`) and we're past the
     *      offload line or 90% of the window → EXHAUSTED.
     *   4. Otherwise OK.
     */
    fun check(estimatedTokens: Int, contextWindow: Int): CheckResult {
        if (contextWindow > 0 && estimatedTokens >= contextWindow) {
            return CheckResult.EXHAUSTED
        }
        if (compactThreshold > 0 && estimatedTokens >= compactThreshold) {
            return CheckResult.NEEDS_COMPACT
        }
        if (exhaustedOnly) {
            val exhaustLine = if (offloadThreshold > 0) offloadThreshold else (contextWindow * 9 / 10)
            if (estimatedTokens >= exhaustLine) return CheckResult.EXHAUSTED
        }
        return CheckResult.OK
    }

    /** Whether the next tool result should be offloaded to disk. */
    fun shouldOffload(estimatedTokens: Int): Boolean =
        offloadThreshold > 0 && estimatedTokens >= offloadThreshold

    /**
     * [T-adaptive-compact-reserve] Shift every trigger line *down* by
     * [reserveTokens], so the compact decision is taken while the conversation
     * still has room to grow.
     *
     * Why this exists: compaction is only *evaluated* at turn boundaries (the
     * sampler is the previous turn's API usage), so the gap between two samples
     * is one whole turn of growth. The bands between the lines are constants —
     * `offload → compact` and `compact → ceiling` are both 20K in the large
     * tiers — so a model that adds more than a band's width in a single turn
     * jumps from OK straight to EXHAUSTED and never sees the compact line at
     * all. Reserving `reserveTurns × perTurnGrowth` makes the compact line be
     * crossed *before* the ceiling no matter how big the single step is.
     *
     * The shift is bounded so the lowest active line never falls below half the
     * window: a reserve that strangled the budget would make every turn
     * re-compact (thrash) instead of buying headroom. Ordering between the
     * lines is preserved exactly — all of them move by the same amount.
     *
     * [reserveTokens] `<= 0` returns `this` unchanged, which is what makes the
     * cold-start behaviour (nothing measured yet) identical to the pre-fix one.
     *
     * `exhaustedOnly` tiers are exempt: they have no compact line, and their
     * `offloadThreshold` doubles as the *hard block* line in [check]. Shifting
     * it would take usable context away from the user every turn without buying
     * any compaction headroom — the opposite of the intent here.
     */
    fun reservedForGrowth(reserveTokens: Int, contextWindow: Int): ContextPolicy {
        if (reserveTokens <= 0 || contextWindow <= 0 || exhaustedOnly) return this
        val floor = contextWindow / 2
        val lowestActive = listOf(offloadThreshold, compactThreshold)
            .filter { it > 0 }
            .minOrNull() ?: return this
        val shift = minOf(reserveTokens, (lowestActive - floor).coerceAtLeast(0))
        if (shift <= 0) return this
        return copy(
            offloadThreshold = if (offloadThreshold > 0) offloadThreshold - shift else 0,
            offloadTarget = (offloadTarget - shift).coerceAtLeast(0),
            compactThreshold = if (compactThreshold > 0) compactThreshold - shift else 0,
        )
    }

    companion object {
        /** Latest (percent of window) each line may sit at, regardless of size. */
        private const val LATEST_OFFLOAD_PCT = 75
        private const val LATEST_OFFLOAD_TARGET_PCT = 65
        private const val LATEST_COMPACT_PCT = 85

        /**
         * Produce the policy for a given context window size. Four tiers:
         *   - `<32K`   → offload/compact disabled; UI tells user to start a new chat.
         *   - `32K–64K` → offload only; exhaust line = ctx − 10k.
         *   - `64K–128K` → offload + compact; headroom 10k for compact.
         *   - `≥128K`  → generous offload + compact; headroom 20k.
         *
         * [T-adaptive-compact-window] The two large tiers take the EARLIER of
         * the absolute-headroom line and a proportional line (75% / 85% of the
         * window). Absolute headroom is a constant; windows are not. The
         * constants above were calibrated on 128K windows, where `W − 20K` sits
         * at 84% — but the same formula puts a 200K window at 90% and a 1M
         * window at 98%. So the *bigger* the model's window, the *later* (in
         * relative terms) compaction fired, until on 1M-window models the
         * compact line was effectively never reached and context only surfaced
         * pressure at the hard ceiling — where the send is blocked outright.
         * Folding in a percentage keeps the fraction of the window at which
         * compaction happens roughly stable as models grow.
         *
         * Backward compatibility: in the `≥128K` tier the absolute term is
         * still the smaller one up to `W = 133K` (compact) / `160K` (offload),
         * so the 128K windows these constants were calibrated on are unchanged
         * byte for byte, and the whole `<64K` range is untouched. The
         * `64K–128K` tier's compact line (previously `W − 10K`, i.e. 84%–92%)
         * is now capped at 85%, which moves the top of that range slightly
         * earlier — same intent, never later.
         */
        fun forContextWindow(contextWindow: Int): ContextPolicy = when {
            contextWindow < 32_000 -> ContextPolicy(
                offloadThreshold = 0,
                offloadTarget = 0,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = false,
            )
            contextWindow < 64_000 -> ContextPolicy(
                offloadThreshold = contextWindow - 10_000,
                offloadTarget = contextWindow - 15_000,
                compactThreshold = 0,
                exhaustedOnly = true,
                manualCompactAllowed = true,
            )
            contextWindow < 128_000 -> ContextPolicy(
                offloadThreshold = earlierOf(contextWindow - 20_000, contextWindow, LATEST_OFFLOAD_PCT),
                offloadTarget = earlierOf(contextWindow - 30_000, contextWindow, LATEST_OFFLOAD_TARGET_PCT),
                compactThreshold = earlierOf(contextWindow - 10_000, contextWindow, LATEST_COMPACT_PCT),
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
            else -> ContextPolicy(
                offloadThreshold = earlierOf(contextWindow - 40_000, contextWindow, LATEST_OFFLOAD_PCT),
                offloadTarget = earlierOf(contextWindow - 60_000, contextWindow, LATEST_OFFLOAD_TARGET_PCT),
                compactThreshold = earlierOf(contextWindow - 20_000, contextWindow, LATEST_COMPACT_PCT),
                exhaustedOnly = false,
                manualCompactAllowed = true,
            )
        }

        /** The earlier (more conservative) of an absolute-headroom line and a percentage line. */
        private fun earlierOf(absoluteHeadroomLine: Int, contextWindow: Int, latestPct: Int): Int =
            minOf(absoluteHeadroomLine.toLong(), contextWindow.toLong() * latestPct / 100).toInt()
    }
}
