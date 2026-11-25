package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production policy only; these are not GPU/Compose render tests. Literal expectations catch mutations. */
class GlassSurfacePolicyTest {
    @Test fun `a safe source and Android 12 are both necessary for recording`() {
        assertTrue(GlassSurfacePolicy.canRecord(true, 31, true))
        assertTrue(GlassSurfacePolicy.canRecord(true, 35, true))
        assertFalse(GlassSurfacePolicy.canRecord(true, 30, true))
        assertFalse(GlassSurfacePolicy.canRecord(true, 35, false))
    }

    @Test fun `off never records even on a capable device`() {
        assertFalse(GlassSurfacePolicy.canRecord(false, 35, true))
        assertFalse(GlassSurfacePolicy.canRecord(false, 30, false))
    }

    @Test fun `top bars only blur when real content has scrolled underneath`() {
        assertTrue(GlassSurfacePolicy.showTopBar(true, true))
        assertFalse(GlassSurfacePolicy.showTopBar(true, false))
        assertFalse(GlassSurfacePolicy.showTopBar(false, true))
        assertFalse(GlassSurfacePolicy.showTopBar(false, false))
    }

    @Test fun `popup without a ready local source is explicitly solid`() {
        assertFalse(GlassSurfacePolicy.menuGlass(false, false, false))
        assertFalse(GlassSurfacePolicy.menuGlass(false, true, false))
        assertFalse(GlassSurfacePolicy.menuGlass(true, false, false))
        assertTrue(GlassSurfacePolicy.menuGlass(true, true, false))
    }

    @Test fun `popup custom colour is not silently replaced by glass`() {
        assertFalse(GlassSurfacePolicy.menuGlass(true, true, true))
    }

    @Test fun `shared density bounds match input and keep small labels readable`() {
        assertEquals(0f, GlassSurfacePolicy.tintAlpha(-40), 0f)
        assertEquals(0.3f, GlassSurfacePolicy.tintAlpha(30), 1e-6f)
        assertEquals(1f, GlassSurfacePolicy.tintAlpha(140), 0f)
        assertEquals(0.25f, GlassSurfacePolicy.topBarTintAlpha(0), 0f)
        assertEquals(0.55f, GlassSurfacePolicy.topBarTintAlpha(30), 1e-6f)
        assertEquals(1f, GlassSurfacePolicy.topBarTintAlpha(100), 0f)
    }

    @Test fun `shared blur cannot escape existing preference bounds`() {
        assertEquals(4, GlassSurfacePolicy.blurDp(-1))
        assertEquals(12, GlassSurfacePolicy.blurDp(12))
        assertEquals(40, GlassSurfacePolicy.blurDp(10000))
    }

    @Test fun `preview scroll follows consumed distance not overscroll`() {
        assertEquals(20f, GlassSurfacePolicy.scrollOffset(0f, -20f), 0f)
        assertEquals(12f, GlassSurfacePolicy.scrollOffset(20f, 8f), 0f)
        assertEquals(0f, GlassSurfacePolicy.scrollOffset(12f, 12f), 0f)
        assertEquals(0f, GlassSurfacePolicy.scrollOffset(0f, 5f), 0f)
        assertEquals(0f, GlassSurfacePolicy.scrollOffset(0f, 0f), 0f)
    }

    @Test fun `focus hidden behind the opaque toolbar scrolls into the visible region`() {
        assertEquals(-44f, GlassSurfacePolicy.focusScrollDistance(20f, 40f, 400f, 64f), 0f)
        assertEquals(0f, GlassSurfacePolicy.focusScrollDistance(64f, 40f, 400f, 64f), 0f)
    }

    @Test fun `keyboard bottom remains the end of the visible region`() {
        assertEquals(20f, GlassSurfacePolicy.focusScrollDistance(380f, 40f, 400f, 64f), 0f)
        assertEquals(0f, GlassSurfacePolicy.focusScrollDistance(320f, 40f, 400f, 64f), 0f)
    }

    @Test fun `focus larger than the viewport does not oscillate between edges`() {
        assertEquals(0f, GlassSurfacePolicy.focusScrollDistance(20f, 500f, 400f, 64f), 0f)
    }

    @Test fun `focus zero inset preserves the platform minimum edge rule`() {
        assertEquals(-20f, GlassSurfacePolicy.focusScrollDistance(-20f, 40f, 400f, 0f), 0f)
        assertEquals(20f, GlassSurfacePolicy.focusScrollDistance(380f, 40f, 400f, 0f), 0f)
        assertEquals(0f, GlassSurfacePolicy.focusScrollDistance(20f, 40f, 400f, 0f), 0f)
    }
}
