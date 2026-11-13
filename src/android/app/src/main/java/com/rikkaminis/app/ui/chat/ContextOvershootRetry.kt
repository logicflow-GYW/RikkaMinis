package com.rikkaminis.app.ui.chat

/**
 * [review1112-p1-5] Whether a provider's "context too large" failure should
 * shrink the history and retry the SAME turn, instead of being handed to the
 * fallback chain.
 *
 * ## Why this exists
 *
 * `LLMModel.contextWindowTokens` guesses 1M for an id it does not recognise,
 * and the comment defending that guess claimed the overshoot is harmless
 * because "the compaction self-heal (`isContextTooLargeError` halving retry in
 * AgentLoopEngine / CompactOrchestration) recovers from it".
 *
 * That claim was false for the main chat path. The halving retry lives inside
 * [com.rikkaminis.app.conversation.generateCompactSummaryWithSplitting] — it
 * only ever halves the text fed to the SUMMARISER. The chat turn itself had no
 * capacity recovery: a 400 came back, `ProviderError.isFallbackable` is `true`
 * for every provider error, so the turn went to the next group member, and
 * when the chain was exhausted the user got "Provider returned an error" with
 * nothing naming the real cause. Meanwhile the preflight budget
 * (`preflightContextBudget`) reads the SAME over-estimated window, so it
 * passes — the guess is invisible to the guard that is supposed to catch it.
 *
 * So the two ways to make the claim true are (a) delete the claim, or (b) give
 * the main path the recovery it names. This is (b): one bounded shrink-and-
 * retry per USER TURN. It cannot loop — [ContextOvershootRetry] is a one-shot
 * decision object, and `alreadyShrunk` latches after the first attempt.
 *
 * ## "Per user turn", not "per model call"
 *
 * `AgentLoopEngine.runAgentLoop` is entered once per user message, but its
 * `turnLoop@` iterates per MODEL call (tool rounds, length-wall continuations).
 * The latch therefore lives ABOVE the turn loop. It used to be declared inside
 * it, which made the bound "one shrink per model call": a `finish=length`
 * continuation `continue`s into a fresh turn, the latch reset, and `maxTokens`
 * was re-inflated by that turn's preflight — so a single user message could
 * 400-and-retry repeatedly. A caller that reuses one instance across user turns
 * must call [reset].
 *
 * ## The window is a guess — corroborate it absolutely too
 *
 * `shouldShrinkAndRetry`'s relative arm divides by the window, and the window is
 * a 1M heuristic whenever the model id is unrecognised. That arm alone is
 * therefore blind exactly when it is needed most (a real 128K window): a 100K
 * history looks like a tenth of the window. The absolute arm
 * ([ABSOLUTE_CORROBORATION_TOKENS]) and [parseReportedLimit] both exist to stop
 * a guessed window from overruling the endpoint's own number.
 *
 * ## Why it must NOT fire for every 400
 *
 * `isContextTooLargeError` matches on message substrings ("context length",
 * "too many tokens", …). A 400 that merely CONTAINS one of those phrases for
 * an unrelated reason (a bad `max_tokens`, a malformed tool schema echoed back)
 * would otherwise drop the user's history for nothing. The extra conditions
 * below keep the trigger narrow: a local estimate that agrees the request was
 * plausibly too big, and a history with enough turns that trimming can actually
 * free space.
 */
internal class ContextOvershootRetry {
    /** True once the shrink-and-retry has been spent for this turn. */
    var alreadyShrunk: Boolean = false
        private set

    /**
     * Decide whether to shrink and retry.
     *
     * @param isContextTooLarge the provider's failure was classified as a
     *   context-length error (`isContextTooLargeError`).
     * @param estimatedInput the local preflight estimate for the request that
     *   just failed. 0 means "no estimate" — the caller could not measure, so
     *   there is no corroboration and the retry is skipped.
     * @param window the effective context window used to build the request.
     * @param historyMessages how many messages the turn's history holds.
     * @return true when the caller should trim and re-send the same turn.
     */
    fun shouldShrinkAndRetry(
        isContextTooLarge: Boolean,
        estimatedInput: Long,
        window: Int,
        historyMessages: Int,
    ): Boolean {
        if (alreadyShrunk) return false
        if (!isContextTooLarge) return false
        if (window <= 0) return false
        if (estimatedInput <= 0) return false
        // Trimming keeps the newest complete turns; with fewer messages than the
        // keep window there is nothing to drop, so a retry would re-send the
        // same oversized request and burn a provider round trip.
        if (historyMessages <= MIN_MESSAGES_TO_TRIM) return false
        // Corroboration, TWO independent arms (either one suffices):
        //
        //  (a) relative — the estimate is at least half the window. The estimate
        //      is char-based and UNDER-counts long formed content, so the bar is
        //      half rather than "exceeds the window": a real overshoot can easily
        //      be 2× under-estimated.
        //  (b) absolute — the estimate is at least [ABSOLUTE_CORROBORATION_TOKENS].
        //
        // Arm (b) exists because arm (a) alone is only as trustworthy as the
        // window it divides by, and the window is frequently a GUESS (an
        // unrecognised model id lands on the 1M heuristic). With a real 128K
        // window mis-guessed as 1M, a 100K-token history gives
        // `100_000 * 2 < 1_000_000` — the guard concludes "nowhere near the
        // limit" and the self-heal never fires, which is the exact failure the
        // guess was supposed to be harmless about. Above 32K of estimated input,
        // a "context length" phrase is almost certainly not an unrelated match
        // no matter what the window claims.
        val relativeArm = estimatedInput * 2 >= window.toLong()
        val absoluteArm = estimatedInput >= ABSOLUTE_CORROBORATION_TOKENS
        if (!relativeArm && !absoluteArm) return false
        alreadyShrunk = true
        return true
    }

    /**
     * Re-arm the one-shot budget for the NEXT USER TURN.
     *
     * `runAgentLoop` constructs one instance per user message, so the in-tree
     * caller never needs this. It exists for the shape where a holder outlives
     * the turn (tests, and any future caller that keeps one instance across
     * messages): forgetting to call it turns "one shrink per message" into "one
     * shrink per run lifetime".
     */
    fun reset() {
        alreadyShrunk = false
    }

    companion object {
        /**
         * Below this many messages, `trimContextHistoryWindow` cannot drop a
         * complete turn (it keeps [MIN_CONTEXT_TURNS_TO_KEEP] = 6 newest
         * turns, i.e. at least 12 messages of a plain user/assistant exchange),
         * so a shrink-and-retry would be a no-op that still costs a provider
         * round trip.
         *
         * Deliberately a literal rather than `MIN_CONTEXT_TURNS_TO_KEEP * 2`:
         * the two constants answer different questions (turns to keep vs
         * messages needed to free one), and this file is unit-tested without
         * the Compose/Android world that the other constant lives in. If the
         * keep window changes, this number must be re-derived — the test
         * [ContextOvershootRetryTest] pins the boundary from both sides so a
         * silent drift shows up as a red test rather than a wasted retry.
         */
        const val MIN_MESSAGES_TO_TRIM = 12

        /**
         * Absolute corroboration floor for [shouldShrinkAndRetry]'s second arm.
         *
         * The relative arm ("estimate ≥ half the window") is only as trustworthy
         * as the window, and the window is a GUESS whenever the model id is not
         * recognised (`LLMModel.contextWindowTokens` → 1M). A real 128K model
         * mis-guessed as 1M makes a 100K-token history look like "a tenth of the
         * window" — the self-heal then declines to fire on the very request that
         * just 400'd.
         *
         * 32K is chosen as "larger than a plausible request from any model whose
         * window could be this small": mainstream small-window models sit at
         * 32K–128K, so an input at or above 32K tokens is a request that a
         * "context length" error is very unlikely to be lying about. It is a
         * LITERAL on purpose — a derived value would move with whatever constant
         * the drift was hiding behind.
         */
        const val ABSOLUTE_CORROBORATION_TOKENS = 32_000L

        /**
         * Server-reported input ceiling, parsed out of a "context too large"
         * failure, applied as a CEILING on the (possibly guessed) window.
         *
         * Providers phrase it in a handful of ways and all of them are stable
         * enough to parse; the point is not to be exhaustive but to stop a
         * guessed 1M window from overruling the endpoint's own number.
         *
         * Deliberately NOT authoritative upward: a parsed limit larger than
         * [window] is ignored, because the window also carries the user's group
         * `contextLimitTokens` clamp — the user's deliberate budget must not be
         * raised by a provider that happens to be more generous.
         */
        fun effectiveWindow(window: Int, reportedLimit: Int?): Int {
            if (reportedLimit == null || reportedLimit <= 0) return window
            if (window <= 0) return reportedLimit
            return minOf(window, reportedLimit)
        }

        /**
         * Best-effort parse of the provider's own input ceiling from an error
         * body. Returns null when the body carries no recognisable number.
         *
         * Shapes seen in the wild (all lowercase-compared):
         *  - "This model's maximum context length is 128000 tokens."
         *  - "maximum context length is 1048576 tokens. However, your messages
         *     resulted in 1100000 tokens"
         *  - "This model's maximum prompt length is 131072 tokens"
         *  - "the input length exceeds the context window of 65536 tokens"
         *  - "context_window_exceeded: limit 200000"
         *  - "max_tokens is too large: 200000. This model supports at most 128000"
         */
        fun parseReportedLimit(detail: String?): Int? {
            if (detail.isNullOrBlank()) return null
            val text = detail.lowercase()
            // A request's own token count is NOT a limit — the 400-teacher
            // parse in AgentLoopEngine already reads that ("at least N input
            // tokens"). Requiring a limit-ish word in front keeps the two apart.
            val patterns = listOf(
                Regex("""maximum context length is\s+(\d{3,})"""),
                Regex("""maximum (?:prompt|input) length is\s+(\d{3,})"""),
                Regex("""context window of\s+(\d{3,})"""),
                Regex("""context[ _]window[^0-9]{0,40}?(\d{3,})"""),
                Regex("""(?:at most|max(?:imum)? (?:of )?)\s*(\d{3,})\s*tokens"""),
                Regex("""limit[^0-9]{0,20}(\d{3,})"""),
            )
            for (p in patterns) {
                val v = p.find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (v != null && v >= 1_000) return v
            }
            return null
        }
    }
}
