package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/tuning-slider-density] Pins [sliderStepsFor]:
 *  - every pre-existing tuning row keeps its original 1-unit stepping
 *    (all shipped spans were ≤ 1024 before this fix);
 *  - wider spans collapse onto ≤ [WIDE_TARGET_SLOTS] slots whose width is an
 *    exact divisor of the span, so snapped values stay round;
 *  - spans with no usable divisor fall back to a continuous slider (0)
 *    instead of drawing tens of thousands of ticks per frame.
 *
 * Why this exists: Material3 draws one tick shape per `steps` value on every
 * drag frame and linearly scans them per pointer move — the 2000..32000
 * token row used to be ~30 000 circles/frame.
 */
class SliderStepsForTest {

    @Test
    fun `fine spans keep original 1-unit stepping`() {
        assertEquals(7, sliderStepsFor(0, 8))
        assertEquals(2, sliderStepsFor(1, 4))
        assertEquals(344, sliderStepsFor(15, 360))
        val turns = AgentRuntimeLimitsPrefs
        assertEquals(
            turns.TURNS_MAX - turns.TURNS_MIN - 1,
            sliderStepsFor(turns.TURNS_MIN, turns.TURNS_MAX),
        )
    }

    @Test
    fun `wide spans snap to round divisor widths`() {
        val p = AgentRuntimeLimitsPrefs
        // 30000 span / 60 slots = 500-token steps
        assertEquals(59, sliderStepsFor(p.COMPACT_TAIL_TOKENS_MIN, p.COMPACT_TAIL_TOKENS_MAX))
        // 3200 span / 64 slots = 50-px steps
        assertEquals(63, sliderStepsFor(p.IMAGE_EDGE_MIN, p.IMAGE_EDGE_MAX))
        // 1740 span / 58 slots = 30-s steps
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
    fun `prime span with no usable divisor falls back to continuous`() {
        // 1031 is prime; its only divisors are 1 and itself, both outside the
        // usable width window [ceil(span/64), span/16].
        assertTrue(isPrime(1031))
        assertEquals(0, sliderStepsFor(0, 1031))
    }

    @Test
    fun `every span stays within the tick budget`() {
        for (span in 1..40000) {
            val steps = sliderStepsFor(0, span)
            assertTrue("negative steps for span $span", steps >= 0)
            if (steps == 0) continue
            val slots = steps + 1
            assertEquals("span $span: slots must divide the span", 0, span % slots)
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
