package com.rikkaminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-B11-F10] JVM tests for the pinch geometry that
 * `MinisAccessibilityService.dispatchPinch` feeds into `StrokeDescription`.
 *
 * Why this is a pure-function test: the service cannot be constructed off a
 * device, and the defect was pure arithmetic —
 *
 *  - the offset was multiplied by `scale` AND the start/end pair was swapped
 *    when `scale > 1f`, so **every** pinch converged on the centre: `--scale
 *    2.0` (zoom in) actually moved the fingers together, i.e. zoomed OUT;
 *  - the coordinates were never clamped, so any centre within one offset of
 *    the screen edge produced a negative value, which
 *    `GestureDescription.StrokeDescription` rejects with
 *    `IllegalArgumentException` (the gesture never dispatched at all).
 *
 * Expectations below are literal pixel numbers on purpose: deriving them from
 * [PinchTracks.INITIAL_OFFSET] would let a changed constant move the
 * expectation with it and hide a re-inverted formula.
 */
class PinchTrackTest {

    private fun track(cx: Float, cy: Float, scale: Float, w: Float = 1080f, h: Float = 2400f) =
        PinchTracks.forPinch(cx = cx, cy = cy, scale = scale, screenWidth = w, screenHeight = h)

    @Test
    fun `scale 2 spreads the fingers apart (zoom in)`() {
        val t = track(cx = 540f, cy = 1200f, scale = 2f)
        // Fingers start 200 px either side of the centre and finish 400 px out.
        assertEquals(340f, t.leftStartX, 0.01f)
        assertEquals(140f, t.leftEndX, 0.01f)
        assertEquals(740f, t.rightStartX, 0.01f)
        assertEquals(940f, t.rightEndX, 0.01f)
        // The property that matters: the gap GROWS.
        val startGap = t.rightStartX - t.leftStartX
        val endGap = t.rightEndX - t.leftEndX
        assertTrue("zoom-in must widen the gap ($startGap → $endGap)", endGap > startGap)
        assertEquals(400f, startGap, 0.01f)
        assertEquals(800f, endGap, 0.01f)
    }

    @Test
    fun `scale 0_5 brings the fingers together (zoom out)`() {
        val t = track(cx = 540f, cy = 1200f, scale = 0.5f)
        assertEquals(340f, t.leftStartX, 0.01f)
        assertEquals(440f, t.leftEndX, 0.01f)
        assertEquals(740f, t.rightStartX, 0.01f)
        assertEquals(640f, t.rightEndX, 0.01f)
        val startGap = t.rightStartX - t.leftStartX
        val endGap = t.rightEndX - t.leftEndX
        assertTrue("zoom-out must narrow the gap ($startGap → $endGap)", endGap < startGap)
        assertEquals(200f, endGap, 0.01f)
    }

    @Test
    fun `the two scales move in opposite directions`() {
        // The original defect made both scales converge; this pins the polarity
        // in one assertion so a future "fix" cannot flip it back.
        val zoomIn = track(cx = 540f, cy = 1200f, scale = 2f)
        val zoomOut = track(cx = 540f, cy = 1200f, scale = 0.5f)
        assertTrue(zoomIn.leftEndX < zoomIn.leftStartX)
        assertTrue(zoomOut.leftEndX > zoomOut.leftStartX)
    }

    @Test
    fun `coordinates never leave the screen on a narrow device`() {
        // 720 px wide, centre in the middle: the old formula produced cx-400 =
        // -40 and StrokeDescription threw.
        val t = track(cx = 360f, cy = 800f, scale = 2f, w = 720f, h = 1600f)
        listOf(t.leftStartX, t.leftEndX, t.rightStartX, t.rightEndX).forEachIndexed { i, x ->
            assertTrue("x[$i]=$x must be within 0..720", x in 0f..720f)
        }
        assertEquals("clamped to the left edge", 0f, t.leftEndX, 0.01f)
        assertEquals("clamped to the right edge", 720f, t.rightEndX, 0.01f)
        // Even clamped, the pinch still travels outward.
        assertTrue(t.leftEndX < t.leftStartX)
    }

    @Test
    fun `off screen centre is clamped instead of throwing`() {
        val t = track(cx = 0f, cy = 0f, scale = 2f, w = 720f, h = 1600f)
        assertEquals(0f, t.leftStartX, 0.01f)
        assertEquals(0f, t.leftEndX, 0.01f)
        assertEquals(0f, t.startY, 0.01f)
        assertEquals(0f, t.endY, 0.01f)
    }

    @Test
    fun `scale 1 is a no-op stroke`() {
        val t = track(cx = 540f, cy = 1200f, scale = 1f)
        assertEquals(t.leftStartX, t.leftEndX, 0.01f)
        assertEquals(t.rightStartX, t.rightEndX, 0.01f)
    }

    @Test
    fun `tiny and huge scales are clamped to usable bounds`() {
        val tiny = track(cx = 540f, cy = 1200f, scale = 0.0001f)
        // finalOffset floors at 20 px each side, so the gap stays positive (40).
        assertEquals(40f, tiny.rightEndX - tiny.leftEndX, 0.01f)
        val huge = track(cx = 540f, cy = 1200f, scale = 1000f)
        // Clamped to MAX_SCALE = 20 → 4000 px each side, within the 1080 screen.
        assertTrue(huge.rightEndX <= 1080f)
    }

    @Test
    fun `NaN scale falls back to no movement`() {
        val t = track(cx = 540f, cy = 1200f, scale = Float.NaN)
        assertEquals(t.leftStartX, t.leftEndX, 0.01f)
    }

    @Test
    fun `vertical centre is clamped to the screen height`() {
        val t = track(cx = 540f, cy = 5000f, scale = 2f, h = 2400f)
        assertEquals(2400f, t.startY, 0.01f)
    }
}
