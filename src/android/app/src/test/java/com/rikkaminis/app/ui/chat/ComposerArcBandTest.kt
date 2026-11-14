package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [composer-arc-1114] The list only reveals content inside the two corner
 * notches, so the notch geometry has to line up with the card exactly. If it is
 * off by the top inset, the reveal appears above or below the arc and the whole
 * effect reads as a square cut again.
 *
 * The expected numbers below are literals on purpose (evidence-discipline: a
 * test that derives them from the constants under test would follow those
 * constants anywhere and never fail).
 *
 * Fixture uses 1dp = 2px: radius 20dp = 40px, horizontal inset 16dp = 32px,
 * top inset 2dp = 4px, so the band is 44px.
 */
class ComposerArcBandTest {

    private fun band(widthPx: Float = 1000f, heightPx: Float = 500f): ComposerArcBand? =
        ComposerArcBand.of(
            widthPx = widthPx,
            heightPx = heightPx,
            radiusPx = 40f,
            horizontalInsetPx = 32f,
            topInsetPx = 4f,
        )

    @Test
    fun theCardTopSitsExactlyOneRadiusAboveTheClipsBottom() {
        // 500 - 40: the arc's deepest point is the clip's bottom edge, so the
        // card's top edge is one radius above it — not `band` above, which is
        // the same mistake the clip would make if it cut at the column edge.
        assertEquals(460f, band()!!.cardTop, 0f)
    }

    @Test
    fun theLeftNotchIsTheCardCornerSquare() {
        val notch = band()!!.leftNotch
        assertEquals(32f, notch.left, 0f)
        assertEquals(460f, notch.top, 0f)
        assertEquals(72f, notch.right, 0f)
        assertEquals(500f, notch.bottom, 0f)
    }

    @Test
    fun theRightNotchIsMirroredAgainstTheFarEdge() {
        val notch = band()!!.rightNotch
        assertEquals(928f, notch.left, 0f)
        assertEquals(460f, notch.top, 0f)
        assertEquals(968f, notch.right, 0f)
        assertEquals(500f, notch.bottom, 0f)
    }

    @Test
    fun theNotchReachesTheBottomOfTheClipSoTheArcTangentIsNotCutFlat() {
        val clip = band()!!
        assertEquals(500f, clip.leftNotch.bottom, 0f)
        assertEquals(500f, clip.rightNotch.bottom, 0f)
        assertEquals(clip.radius, clip.leftNotch.height, 0f)
        assertEquals(clip.radius, clip.rightNotch.width, 0f)
    }

    @Test
    fun anElementShorterThanTheBandHasNoNotchAtAll() {
        assertNull(band(heightPx = 44f))
        assertNull(band(heightPx = 0f))
    }

    @Test
    fun aWindowTooNarrowForBothCornersHasNoNotchAtAll() {
        assertNull(band(widthPx = 143f))
        // 144px is exactly two corners wide — the notches meet in the middle,
        // which is still a well-formed outline.
        assertEquals(72f, band(widthPx = 144f)!!.leftNotch.right, 0f)
    }

    @Test
    fun theOverlapOnlyCoversTheCornerBand() {
        // radius + top inset, i.e. exactly the depth the arc occupies.
        assertEquals(22f, ComposerArcOverlap.value, 0f)
        assertEquals(ComposerCardCornerRadius + ComposerCardTopInset, ComposerArcOverlap)
    }
}
