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
 * retry per turn. It cannot loop — [ContextOvershootRetry] is a one-shot
 * decision object, and `alreadyShrunk` latches after the first attempt.
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
        // Corroboration: the local estimate has to be a meaningful fraction of
        // the window. The estimate is char-based and UNDER-counts long formed
        // content, so it is compared against a low bar (half the window) rather
        // than requiring it to exceed the window itself — a real overshoot can
        // easily be 2× under-estimated. Below half, the request was nowhere
        // near the limit and the phrase match is almost certainly unrelated.
        if (estimatedInput * 2 < window.toLong()) return false
        alreadyShrunk = true
        return true
    }

    /** Reset for the next turn (a new turn gets its own one-shot budget). */
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
    }
}
