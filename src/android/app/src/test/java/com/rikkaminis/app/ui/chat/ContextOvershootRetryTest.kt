package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Just under half AND under the absolute floor: rejected.
        assertFalse(r.shouldShrinkAndRetry(true, 20_000, 1_000_000, 40))
        // Exactly half: accepted (the comparison is `<`, not `<=`).
        val r2 = ContextOvershootRetry()
        assertTrue(r2.shouldShrinkAndRetry(true, 500_000, 1_000_000, 40))
    }

    // ── [incr1113 F-03] the ABSOLUTE arm ───────────────────────────────

    /**
     * The regression anchor for this batch: a guessed 1M window must not make
     * the self-heal blind to a real 128K one. `100_000 * 2 < 1_000_000` used to
     * be read as "nowhere near the limit" — the exact shape of the field
     * failure (128K endpoint, 100K history, one 400, whole fallback chain).
     */
    @Test
    fun aBigInputCorroboratesEvenWhenTheGuessedWindowSaysOtherwise() {
        val r = ContextOvershootRetry()
        assertTrue(
            "100K of input against a guessed 1M window must still shrink — the " +
                "window is a heuristic for unrecognised ids, not a measurement",
            r.shouldShrinkAndRetry(true, 100_000, 1_000_000, 20),
        )
    }

    @Test
    fun theAbsoluteFloorIsThirtyTwoThousandTokens() {
        // Just below the floor with a huge (guessed) window: rejected.
        val below = ContextOvershootRetry()
        assertFalse(below.shouldShrinkAndRetry(true, 31_999, 1_000_000, 20))
        // At the floor: accepted.
        val at = ContextOvershootRetry()
        assertTrue(at.shouldShrinkAndRetry(true, 32_000, 1_000_000, 20))
    }

    @Test
    fun anHonestSmallWindowStillShrinksOnTheRelativeArm() {
        val r = ContextOvershootRetry()
        assertTrue(r.shouldShrinkAndRetry(true, 100_000, 128_000, 20))
        // …and a small input against a small window is still rejected: the
        // absolute arm is a floor for BIG inputs, not a licence for any 400.
        val r2 = ContextOvershootRetry()
        assertFalse(r2.shouldShrinkAndRetry(true, 10_000, 128_000, 20))
    }

    @Test
    fun theLatchStillWinsOverTheAbsoluteArm() {
        val r = ContextOvershootRetry()
        assertTrue(r.shouldShrinkAndRetry(true, 100_000, 1_000_000, 20))
        assertFalse("one shrink per user turn, absolute arm or not",
            r.shouldShrinkAndRetry(true, 100_000, 1_000_000, 20))
    }

    // ── [incr1113 F-03] window calibration from the provider's own text ──

    @Test
    fun aReportedLimitCapsTheGuessedWindow() {
        assertEquals(128_000, ContextOvershootRetry.effectiveWindow(1_000_000, 128_000))
        assertEquals(128_000, ContextOvershootRetry.effectiveWindow(128_000, 128_000))
    }

    @Test
    fun aReportedLimitNeverRaisesTheWindow() {
        // The window also carries the user's group `contextLimitTokens` clamp —
        // a generous provider must not override the user's deliberate budget.
        assertEquals(64_000, ContextOvershootRetry.effectiveWindow(64_000, 200_000))
    }

    @Test
    fun aMissingOrBogusReportedLimitLeavesTheWindowAlone() {
        assertEquals(1_000_000, ContextOvershootRetry.effectiveWindow(1_000_000, null))
        assertEquals(1_000_000, ContextOvershootRetry.effectiveWindow(1_000_000, 0))
        assertEquals(1_000_000, ContextOvershootRetry.effectiveWindow(1_000_000, -1))
    }

    @Test
    fun theReportedLimitIsParsedFromTheShapesProvidersActuallyUse() {
        assertEquals(
            128_000,
            ContextOvershootRetry.parseReportedLimit(
                "This model's maximum context length is 128000 tokens.",
            ),
        )
        assertEquals(
            1_048_576,
            ContextOvershootRetry.parseReportedLimit(
                "maximum context length is 1048576 tokens. However, your messages resulted in 1100000 tokens",
            ),
        )
        assertEquals(
            131_072,
            ContextOvershootRetry.parseReportedLimit("This model's maximum prompt length is 131072 tokens"),
        )
        assertEquals(
            65_536,
            ContextOvershootRetry.parseReportedLimit("the input length exceeds the context window of 65536 tokens"),
        )
        assertEquals(
            200_000,
            ContextOvershootRetry.parseReportedLimit("context_window_exceeded: limit 200000"),
        )
    }

    @Test
    fun aBodyWithoutALimitParsesToNull() {
        assertNull(ContextOvershootRetry.parseReportedLimit(null))
        assertNull(ContextOvershootRetry.parseReportedLimit(""))
        assertNull(ContextOvershootRetry.parseReportedLimit("your prompt contains at least 920577 input tokens"))
        assertNull(ContextOvershootRetry.parseReportedLimit("invalid api key"))
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
