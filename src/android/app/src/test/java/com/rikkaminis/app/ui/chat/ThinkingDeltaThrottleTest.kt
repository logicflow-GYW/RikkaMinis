package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-delta-main-thread-throttle] Regression tests for the
 * thinking-delta UI push throttle.
 *
 * WHY THIS EXISTS
 * ---------------
 * The `LLMStreamChunk.ThinkingDelta` branch of `AgentLoopEngine.runAgentLoop`
 * used to call `withContext(Dispatchers.Main) { host.updateAssistantMessage(...) }`
 * on EVERY delta with no gate, while its sibling `Text` branch is gated by
 * `textDeltaThrottleMs`. Device measurements (2026-09-20):
 *
 *   * 91.8% of all SSE deltas are thinking-only (59,403 of 64,744)
 *   * peak 1,052 deltas/s; 14,258 deltas in a single turn
 *   * main thread at 59.8% of one core, `Number Slow UI thread: 1811`
 *
 * The IME delivers keystrokes on that same main Looper, so every keypress
 * queued behind those posts — the reported input lag. It scales with
 * accumulated-text length, which is why long conversations feel worse.
 *
 * These tests pin the two properties the fix must not break:
 *   1. the FIRST delta of a turn still publishes immediately (block appears
 *      as soon as thinking starts — no new latency at the start of a turn)
 *   2. inside the throttle window the push is suppressed, so the per-delta
 *      main-thread work collapses
 *
 * The throttle decision itself is `nowMs - lastThinkingUiUpdateMs >=
 * textDeltaThrottleMs(len)`, mirroring the Text branch.
 */
class ThinkingDeltaThrottleTest {

    /** The exact predicate the ThinkingDelta branch uses. */
    private fun shouldPushThinking(
        nowMs: Long,
        lastThinkingUiUpdateMs: Long,
        thinkingLen: Int,
    ): Boolean = nowMs - lastThinkingUiUpdateMs >= textDeltaThrottleMs(thinkingLen)

    @Test
    fun `first thinking delta of a turn always publishes`() {
        // lastThinkingUiUpdateMs is reset to 0L at turn start (AgentLoopState),
        // and System.currentTimeMillis() is ~1.7e12, so the subtraction passes
        // every tier. This is what keeps "thinking appears immediately" true.
        for (len in listOf(1, 499, 500, 2_000, 32_000, 64_000, 128_000, 500_000)) {
            assertTrue(
                "len=$len must publish on the first delta",
                shouldPushThinking(nowMs = 1_767_000_000_000L, lastThinkingUiUpdateMs = 0L, thinkingLen = len),
            )
        }
    }

    @Test
    fun `deltas inside the window are suppressed`() {
        // A fast stream: 1 ms between deltas. Every tier is >= 150 ms, so all
        // but the first push are suppressed.
        val last = 1_000_000L
        for (dt in listOf(1L, 10L, 100L, 149L)) {
            assertFalse(
                "dt=$dt ms must be suppressed at len=100 (tier 150ms)",
                shouldPushThinking(nowMs = last + dt, lastThinkingUiUpdateMs = last, thinkingLen = 100),
            )
        }
    }

    @Test
    fun `push resumes once the tier elapses`() {
        val last = 1_000_000L
        // len=100 -> tier 150ms
        assertFalse(shouldPushThinking(last + 149, last, 100))
        assertTrue(shouldPushThinking(last + 150, last, 100))
        // len=40_000 -> tier 1000ms
        assertFalse(shouldPushThinking(last + 999, last, 40_000))
        assertTrue(shouldPushThinking(last + 1_000, last, 40_000))
    }

    @Test
    fun `tier grows with thinking block length`() {
        // The ladder must be monotonic non-decreasing, so a long reasoning
        // phase does not push more often than a short one.
        val lens = listOf(0, 499, 500, 1_999, 2_000, 31_999, 32_000, 63_999, 64_000, 127_999, 128_000, 999_999)
        var prev = 0L
        for (l in lens) {
            val t = textDeltaThrottleMs(l)
            assertTrue("tier must not decrease at len=$l (prev=$prev, got=$t)", t >= prev)
            prev = t
        }
    }

    @Test
    fun `fast stream collapses thousands of pushes to a handful`() {
        // Reproduce the measured worst case: 14,258 deltas at ~1,000/s
        // (1 ms apart), thinking block growing to ~65k chars. Count how many
        // pushes the throttle admits — this is the main-thread round-trip
        // count that the fix removes.
        val deltas = 14_258
        val finalLen = 65_612
        val perDeltaChars = (finalLen / deltas).coerceAtLeast(1)
        var last = 0L
        var pushes = 0
        var len = 0
        val t0 = 1_767_000_000_000L // realistic epoch (see the zero-sentinel test)
        for (i in 0 until deltas) {
            val nowMs = t0 + i.toLong() // 1 ms/delta => ~1000 deltas/s
            len += perDeltaChars
            if (shouldPushThinking(nowMs, last, len)) {
                last = nowMs
                pushes++
            }
        }
        // Pre-fix this was `deltas` (14,258). Post-fix it must be tiny.
        assertTrue("expected a large reduction, got $pushes pushes", pushes < 100)
        assertTrue("must still push at least once", pushes >= 1)
    }

    @Test
    fun `slow stream still pushes most deltas`() {
        // The opposite end: a model that emits one thinking delta every 2 s
        // must not be over-throttled — each delta should reach the UI.
        //
        // Time base is a realistic epoch, NOT 0: `lastThinkingUiUpdateMs` is
        // reset to 0L at turn start, so the first delta only passes because
        // `System.currentTimeMillis() - 0` is a huge positive number. Starting
        // the clock at 0 would suppress the first delta for a reason that
        // cannot happen in production (see the zero-sentinel test below).
        var last = 0L
        var pushes = 0
        var len = 0
        for (i in 0 until 20) {
            val nowMs = 1_767_000_000_000L + i.toLong() * 2_000L
            len += 10
            if (shouldPushThinking(nowMs, last, len)) {
                last = nowMs
                pushes++
            }
        }
        assertEquals(20, pushes)
    }

    @Test
    fun `zero sentinel does not overflow the subtraction`() {
        // Pins the AgentLoopState default. A `Long.MIN_VALUE` sentinel would
        // make `now - last` overflow negative and silently suppress the first
        // push for the whole turn (the LogRingBuffer.lastSnapshotAt trap).
        val nowMs = 1_767_000_000_000L
        assertTrue(shouldPushThinking(nowMs, 0L, 100))
        // Demonstrate the trap we are avoiding.
        val overflowed = nowMs - Long.MIN_VALUE
        assertTrue("MIN_VALUE sentinel overflows negative", overflowed < 0)
    }
}
