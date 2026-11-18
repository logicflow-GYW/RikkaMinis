package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/topbar-glass-flash-1118] The floor under the top-bar glass is
 * `1 - glassAlpha`, and that complement is the whole fix: at any moment the
 * pair (floor, glass) must add up to a fully opaque bar, otherwise the rows the
 * bar lifted under itself are painted raw for that frame.
 *
 * Pinned here (pure maths, no Compose) so a "simplification" to a constant 1f
 * or 0f reddens without needing a device to notice the flash.
 */
class TopBarGlassOcclusionTest {

    @Test
    fun `the floor is the exact complement of the glass`() {
        assertEquals(1f, TopBarGlassOcclusion.scrimAlpha(0f), DELTA)
        assertEquals(0.75f, TopBarGlassOcclusion.scrimAlpha(0.25f), DELTA)
        assertEquals(0.5f, TopBarGlassOcclusion.scrimAlpha(0.5f), DELTA)
        assertEquals(0f, TopBarGlassOcclusion.scrimAlpha(1f), DELTA)
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
            TopBarGlassOcclusion.scrimAlpha(0f),
            DELTA,
        )
    }

    @Test
    fun `floor plus glass always covers the bar`() {
        var alpha = 0f
        while (alpha <= 1f) {
            val sum = TopBarGlassOcclusion.scrimAlpha(alpha) + alpha
            assertEquals("floor+glass must reach opacity at alpha=$alpha", 1f, sum, DELTA)
            alpha += 0.05f
        }
    }

    @Test
    fun `out of range alphas are clamped instead of painting a negative floor`() {
        assertEquals(1f, TopBarGlassOcclusion.scrimAlpha(-0.4f), DELTA)
        assertEquals(0f, TopBarGlassOcclusion.scrimAlpha(1.4f), DELTA)
    }

    private companion object {
        const val DELTA = 1e-6f
    }
}
