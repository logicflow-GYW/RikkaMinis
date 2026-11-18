package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/topbar-occlusion-1118c] The floor under the top-bar glass, pinned as a
 * **coverage** contract rather than the additive one the first fix asserted.
 *
 * The pair (floor, glass) is composited with Compose's default **SrcOver**, not
 * by adding alphas:
 *
 *     coverage(a) = 1 - (1 - a) * (1 - floorAlpha(a))
 *
 * where `a` is the glass alpha (`topBarGlassAlpha`, the scroll-driven fade) and
 * `floorAlpha` is [TopBarGlassOcclusion.floorAlpha]. The bar is "occluded" for a
 * frame only when that fraction reaches 1. Everything below is a literal
 * expectation: `expected` is never derived from the implementation, because a
 * test whose expectation is the implementation's own expression proves nothing
 * about it — which is exactly how the previous version stayed green while
 * `floorAlpha = 1 - a` leaked a quarter of the raw rows at `a = 0.5`.
 */
class TopBarGlassOcclusionTest {

    @Test
    fun `the floor is opaque at every glass alpha`() {
        assertEquals(1f, TopBarGlassOcclusion.floorAlpha(0f), DELTA)
        assertEquals(1f, TopBarGlassOcclusion.floorAlpha(0.25f), DELTA)
        assertEquals(1f, TopBarGlassOcclusion.floorAlpha(0.5f), DELTA)
        assertEquals(1f, TopBarGlassOcclusion.floorAlpha(1f), DELTA)
    }

    @Test
    fun `the first frame of a chat open is fully occluded`() {
        // `animateFloatAsState` seeds itself with its target, and on a freshly
        // created list that target is 0 (canScrollBackward is false until the
        // list is measured and scrolled to the bottom). If the floor were not
        // opaque there, the lifted rows would be visible — the reported flash.
        assertEquals(
            "a zero glass alpha must leave an opaque bar, not a hole",
            1f,
            coverage(0f),
            DELTA,
        )
    }

    @Test
    fun `the pair covers the bar across the whole fade window`() {
        // Mid-fade is where the old complement floor was worst: floor 0.5 under
        // glass 0.5 leaves 0.25 of the bar raw. The threshold below is the
        // contract of this fix, and the literal `1f` is the value it must hold at.
        var alpha = 0f
        while (alpha <= 1f) {
            val coverage = coverage(alpha)
            assertTrue(
                "the bar must stay covered (>= $MIN_COVERAGE) at glassAlpha=$alpha",
                coverage >= MIN_COVERAGE,
            )
            assertEquals(
                "coverage must be exactly 1f at glassAlpha=$alpha — the floor is " +
                    "opaque on its own, so SrcOver leaves nothing uncovered",
                1f,
                coverage,
                DELTA,
            )
            alpha += STEP
        }
    }

    @Test
    fun `the floor does not trade off against the glass`() {
        // The glass is allowed to be translucent; the floor is not. If the floor
        // followed the glass (the old `1 - a` complement) the composite would
        // peak below 1 and the raw rows would come back on the frames the glass
        // is half up.
        assertEquals(1f, coverage(0.5f), DELTA)
        assertEquals(
            "floor alpha must not depend on the glass alpha",
            1f,
            TopBarGlassOcclusion.floorAlpha(0.5f),
            DELTA,
        )
    }

    /** SrcOver composite of the floor under the glass, for the `a` given. */
    private fun coverage(glassAlpha: Float): Float =
        1f - (1f - glassAlpha) * (1f - TopBarGlassOcclusion.floorAlpha(glassAlpha))

    private companion object {
        const val DELTA = 1e-6f
        const val STEP = 0.05f
        const val MIN_COVERAGE = 0.99f
    }
}
