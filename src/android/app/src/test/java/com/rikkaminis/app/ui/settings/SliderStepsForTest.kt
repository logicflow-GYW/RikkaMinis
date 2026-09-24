package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-slider-visible-snap] Pins [sliderStepsFor] under the user-facing rule
 * "every slider has visible snapping":
 *  - spans ≤ [MAX_FINE_SPAN] (64) keep exact 1-unit stepping — those ticks
 *    are ~5dp apart on a phone-width track, every unit a visible snap point;
 *  - wider spans snap to ≤ [WIDE_TARGET_SLOTS] slots whose width is an exact
 *    divisor of the span, so snapped values stay round;
 *  - a span with no usable divisor (prime-ish) falls back to ≈
 *    [WIDE_TARGET_SLOTS] NON-exact slots — the ticks stay visible;
 *  - NO span ≥ 2 ever produces 0 steps (0 = continuous = the thing this
 *    feature exists to remove).
 *
 * History: [fix/tuning-slider-density] collapsed tick counts for perf
 * (Material3 draws one tick per step on every drag frame). 2026-09-24 the
 * user-facing rule flipped to "all sliders snap": the old `span - 1` fine
 * stepping on 65..1024-unit spans drew sub-pixel ticks that read as a
 * continuous drag, so [MAX_FINE_SPAN] dropped 1024 → 64 and the no-divisor
 * fallback switched from a continuous slider to non-exact slots.
 */
class SliderStepsForTest {

    @Test
    fun `fine spans (up to 64) keep exact 1-unit stepping`() {
        assertEquals(7, sliderStepsFor(0, 8))
        assertEquals(2, sliderStepsFor(1, 4))
        assertEquals(63, sliderStepsFor(0, 64))
    }

    @Test
    fun `wider spans collapse onto nice divisor slots`() {
        val p = AgentRuntimeLimitsPrefs
        // 950 span / 38 slots = 25-turn steps (was 949 sub-pixel steps)
        assertEquals(
            37,
            sliderStepsFor(p.TURNS_MIN, p.TURNS_MAX),
        )
        // 345 span / 23 slots = 15-minute steps (was 344 sub-pixel steps)
        assertEquals(22, sliderStepsFor(p.DEADLINE_MIN_MIN, p.DEADLINE_MAX_MIN))
        // 30000 span / 60 slots = 500-token steps (unchanged)
        assertEquals(59, sliderStepsFor(p.COMPACT_TAIL_TOKENS_MIN, p.COMPACT_TAIL_TOKENS_MAX))
        // 3200 span / 64 slots = 50-px steps (unchanged)
        assertEquals(63, sliderStepsFor(p.IMAGE_EDGE_MIN, p.IMAGE_EDGE_MAX))
        // 1740 span / 58 slots = 30-s steps (unchanged)
        assertEquals(57, sliderStepsFor(p.SHELL_TIMEOUT_MIN_SEC, p.SHELL_TIMEOUT_MAX_SEC))
    }

    @Test
    fun `wide snapped values stay on exact multiples`() {
        val p = AgentRuntimeLimitsPrefs
        val min = p.COMPACT_TAIL_TOKENS_MIN
        val max = p.COMPACT_TAIL_TOKENS_MAX
        val steps = sliderStepsFor(min, max)
        val slots = steps + 1
        val stepWidth = (max - min) / slots
        assertEquals(500, stepWidth)
        // Simulate the value a drag returns at tick k: min + span * k / slots.
        for (k in 0..slots) {
            val value = min + (max - min) * k / slots
            assertEquals(min + stepWidth * k, value)
        }
    }

    @Test
    fun `prime span with no usable divisor falls back to non-exact slots`() {
        // 1031 is prime; its only divisors are 1 and itself, both outside the
        // usable width window [ceil(span/64), span/16]. It must NOT collapse
        // to a continuous slider — it gets ≈WIDE_TARGET_SLOTS non-exact
        // slots whose rounded values are distinct ints.
        assertTrue(isPrime(1031))
        val steps = sliderStepsFor(0, 1031)
        assertTrue("prime span must not be continuous", steps > 0)
        val slots = steps + 1
        assertTrue("slots $slots outside budget", slots in MIN_SLIDE_SLOTS..WIDE_TARGET_SLOTS)
        // Rounded slot values must be distinct ints (no duplicate snap stops).
        val seen = HashSet<Int>()
        for (k in 0..slots) {
            assertTrue(
                "duplicate rounded value at tick $k",
                seen.add(Math.round(1031.0 * k / slots).toInt()),
            )
        }
    }

    @Test
    fun `every span stays within the tick budget and never goes continuous`() {
        for (span in 2..40000) {
            val steps = sliderStepsFor(0, span)
            // The user-facing rule: every draggable span snaps.
            assertTrue("span $span: continuous slider (0 steps)", steps > 0)
            val slots = steps + 1
            if (span <= MAX_FINE_SPAN) {
                assertEquals("span $span must keep fine stepping", span - 1, steps)
            } else {
                assertTrue(
                    "span $span: $slots slots outside [MIN_SLIDE_SLOTS, WIDE_TARGET_SLOTS]",
                    slots in MIN_SLIDE_SLOTS..WIDE_TARGET_SLOTS,
                )
            }
        }
    }

    private fun isPrime(n: Int): Boolean {
        if (n < 2) return false
        var d = 2
        while (d * d <= n) {
            if (n % d == 0) return false
            d++
        }
        return true
    }
}
