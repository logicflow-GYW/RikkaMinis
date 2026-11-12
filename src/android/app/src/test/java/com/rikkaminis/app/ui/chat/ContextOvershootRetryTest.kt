package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [review1112-p1-5] The one-shot shrink-and-retry decision for a provider
 * "context too large" failure on the MAIN chat path.
 *
 * Why this test exists: `LLMModel.contextWindowTokens` guesses 1M for unknown
 * ids and its comment justified that by pointing at a "compaction self-heal"
 * that only ever ran inside the summariser. The claim is only true once the
 * chat turn itself recovers — so the recovery has to be pinned, and pinned on
 * the conditions that make it SAFE (it must not fire for every 400, and it must
 * not loop).
 *
 * Literals throughout: the thresholds below are written as numbers, not
 * derived from the constants under test.
 */
class ContextOvershootRetryTest {

    // ── the happy path ─────────────────────────────────────────────────

    @Test
    fun aCorroboratedOvershootShrinksAndRetries() {
        val r = ContextOvershootRetry()
        assertTrue(
            r.shouldShrinkAndRetry(
                isContextTooLarge = true,
                estimatedInput = 900_000,
                window = 1_000_000,
                historyMessages = 40,
            ),
        )
    }

    // ── it must fire at most ONCE per turn (no loop) ────────────────────

    @Test
    fun theSecondCallInTheSameTurnDoesNotRetryAgain() {
        val r = ContextOvershootRetry()
        val args = { r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 40) }
        assertTrue(args())
        assertFalse("a second shrink-and-retry in one turn would loop", args())
        assertFalse(args())
    }

    @Test
    fun aNewTurnGetsItsOwnBudget() {
        val r = ContextOvershootRetry()
        assertTrue(r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 40))
        r.reset()
        assertTrue(
            "the next turn could not recover — the one-shot latch was not reset",
            r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 40),
        )
    }

    // ── it must NOT fire for an unrelated 400 ──────────────────────────

    @Test
    fun aNonContextErrorNeverShrinks() {
        val r = ContextOvershootRetry()
        assertFalse(r.shouldShrinkAndRetry(false, 900_000, 1_000_000, 40))
    }

    @Test
    fun anUncorroboratedEstimateNeverShrinks() {
        val r = ContextOvershootRetry()
        assertFalse(
            "a 400 whose text merely contains 'context length' must not drop " +
                "history when the request was nowhere near the window",
            r.shouldShrinkAndRetry(
                isContextTooLarge = true,
                estimatedInput = 10_000,
                window = 1_000_000,
                historyMessages = 40,
            ),
        )
    }

    @Test
    fun theCorroborationBoundaryIsHalfTheWindow() {
        val r = ContextOvershootRetry()
        // Just under half: rejected.
        assertFalse(r.shouldShrinkAndRetry(true, 499_999, 1_000_000, 40))
        // Exactly half: accepted (the comparison is `<`, not `<=`).
        val r2 = ContextOvershootRetry()
        assertTrue(r2.shouldShrinkAndRetry(true, 500_000, 1_000_000, 40))
    }

    @Test
    fun noEstimateNeverShrinks() {
        val r = ContextOvershootRetry()
        assertFalse(r.shouldShrinkAndRetry(true, 0, 1_000_000, 40))
        assertFalse(r.shouldShrinkAndRetry(true, -1, 1_000_000, 40))
    }

    @Test
    fun noWindowNeverShrinks() {
        val r = ContextOvershootRetry()
        assertFalse(r.shouldShrinkAndRetry(true, 900_000, 0, 40))
        assertFalse(r.shouldShrinkAndRetry(true, 900_000, -1, 40))
    }

    // ── trimming must actually be able to free space ───────────────────

    @Test
    fun aHistoryTooShortToTrimNeverShrinks() {
        val r = ContextOvershootRetry()
        assertFalse(
            "with 6 messages the trimmer keeps all of them (it keeps the 6 " +
                "newest turns) — the retry would re-send the same request",
            r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 6),
        )
    }

    @Test
    fun theTrimBoundaryIsTwelveMessages() {
        val r = ContextOvershootRetry()
        assertFalse(r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 12))
        val r2 = ContextOvershootRetry()
        assertTrue(r2.shouldShrinkAndRetry(true, 900_000, 1_000_000, 13))
    }

    // ── a rejected attempt must not consume the one-shot budget ────────

    @Test
    fun aRejectedAttemptLeavesTheBudgetIntact() {
        val r = ContextOvershootRetry()
        // Rejected for an unrelated reason (estimate too low)…
        assertFalse(r.shouldShrinkAndRetry(true, 1_000, 1_000_000, 40))
        // …must not burn the one shot: a later corroborated failure still works.
        assertTrue(r.shouldShrinkAndRetry(true, 900_000, 1_000_000, 40))
    }
}
