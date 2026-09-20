package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/context-exhausted-loop] Regression tests for the empty length-wall
 * retry gate ([shouldRetryEmptyLengthWall]).
 *
 * Field incident (2026-09-20, build 27eced19, `minis-2026-09-20.log`):
 * `input=405694` vs `window=200000` (203% over). Because
 * `dynamicMaxTokens` floors the per-request output budget at
 * `MIN_MAX_TOKENS=1024` (`ChatErrorHandling.kt:110`
 * `maxOf(remaining, MIN_MAX_TOKENS)` with `remaining = -205694`), every retry
 * re-sent the same over-budget input for a provably identical empty result:
 *
 *   11:53:40  chat stream offload -> :modelservice   (request 1)
 *   11:54:23  empty turn detected: turn=5 finishReason=length reasoningLen=1940
 *   11:54:23  chat stream offload -> :modelservice   (request 2)
 *   11:55:11  empty turn detected: turn=6 ... reasoningLen=3477
 *   11:55:11  chat stream offload -> :modelservice   (request 3)
 *   11:56:02  empty turn detected: turn=7 ... reasoningLen=3916
 *   11:56:02  runAgentLoop turn=7 finish=length ×3 empty output — giving up
 *
 * 2m22s of "thinking" before the user got an error. Each request was
 * immediately preceded by `[AutoCompactLoop] skipped: EXHAUSTED`, i.e. the
 * compaction layer had already concluded the context was over the hard
 * ceiling and refused to act — then the loop sent the request anyway.
 *
 * The gate is deliberately narrow: it fires only on an *empty length-wall*
 * turn, never at the turn entry. Over-window requests DO sometimes succeed
 * (the same log shows turn 4 dispatching tool calls at 404463 tokens), so
 * gating the turn entry would kill working runs.
 */
class EmptyLengthWallRetryGateTest {

    // ── the regression: exhausted ⇒ no retry ─────────────────────────────

    @Test
    fun `exhausted context refuses the retry even on the first hit`() {
        // The field case: hits=1 (first empty turn), context over the ceiling.
        // Pre-fix `hits < 3` returned true here and burned two more requests.
        assertFalse(shouldRetryEmptyLengthWall(hits = 1, contextExhausted = true))
    }

    @Test
    fun `exhausted context refuses every hit count`() {
        for (hits in 1..5) {
            assertFalse(
                "exhausted must refuse at hits=$hits",
                shouldRetryEmptyLengthWall(hits = hits, contextExhausted = true),
            )
        }
    }

    // ── the guard's own budget still works when NOT exhausted ────────────

    @Test
    fun `not exhausted keeps the legacy three-hit budget`() {
        // Retry while under the ceiling: the original behaviour must survive
        // byte for byte (a fresh turn genuinely can shape a new answer).
        assertTrue(shouldRetryEmptyLengthWall(hits = 1, contextExhausted = false))
        assertTrue(shouldRetryEmptyLengthWall(hits = 2, contextExhausted = false))
        // The ceiling itself is unchanged: hit 3 stops.
        assertFalse(shouldRetryEmptyLengthWall(hits = 3, contextExhausted = false))
        assertFalse(shouldRetryEmptyLengthWall(hits = 4, contextExhausted = false))
    }

    @Test
    fun `default maxHits matches the legacy literal`() {
        // The engine used an inline `3`; the constant must be that same 3 or
        // the ceiling silently moves.
        assertEquals(3, LENGTH_WALL_EMPTY_MAX_HITS)
        assertEquals(
            shouldRetryEmptyLengthWall(hits = 2, contextExhausted = false),
            shouldRetryEmptyLengthWall(hits = 2, contextExhausted = false, maxHits = LENGTH_WALL_EMPTY_MAX_HITS),
        )
    }

    @Test
    fun `maxHits is a parameter, not a hidden literal`() {
        // A caller that widens the budget must actually get more retries.
        assertTrue(shouldRetryEmptyLengthWall(hits = 3, contextExhausted = false, maxHits = 5))
        assertFalse(shouldRetryEmptyLengthWall(hits = 1, contextExhausted = false, maxHits = 1))
    }

    // ── truth table (both axes, exhaustively) ────────────────────────────

    @Test
    fun `truth table`() {
        // hits, exhausted -> expected
        val cases = listOf(
            Triple(1, false, true),
            Triple(2, false, true),
            Triple(3, false, false),
            Triple(1, true, false),
            Triple(2, true, false),
            Triple(3, true, false),
        )
        for ((hits, exhausted, expected) in cases) {
            assertEquals(
                "hits=$hits exhausted=$exhausted",
                expected,
                shouldRetryEmptyLengthWall(hits = hits, contextExhausted = exhausted),
            )
        }
    }
}
