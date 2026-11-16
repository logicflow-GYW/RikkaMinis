package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.ChatTuningPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [composer-glass-1115] `ComposerGlass` is the last stop between a number in
 * SharedPreferences and the render path. Expectations below are literals on
 * purpose (evidence-discipline): deriving them from the constants under test
 * would let a changed range "pass" by moving the expectation with it.
 *
 * Why it matters: `ChatTuningPrefs.readClamped` clamps the panel's own writes,
 * but `minis-config`, a restored backup and a hand-edited prefs file all write
 * the RAW key. An out-of-range value does not crash — it renders a composer
 * that is either pure mush or a black slab, which is far harder to diagnose
 * than an exception.
 */
class ComposerGlassTest {

    @Test
    fun `density percent maps to the tint alpha haze blends over the blur`() {
        assertEquals(0f, ComposerGlass.tintAlpha(0), 0f)
        assertEquals(0.55f, ComposerGlass.tintAlpha(55), 1e-6f)
        assertEquals(1f, ComposerGlass.tintAlpha(100), 0f)
    }

    @Test
    fun `density outside 0-100 is clamped, not wrapped`() {
        // 0 is "no card colour": pure blur. A negative value clamps to the same end.
        assertEquals("a negative density must read as pure blur, not as alpha < 0", 0f, ComposerGlass.tintAlpha(-20), 0f)
        assertEquals("over 100 must read as the solid card, not as alpha > 1", 1f, ComposerGlass.tintAlpha(250), 0f)
    }

    @Test
    fun `the top bar takes a fixed density margin over the shared knob`() {
        // 30% is the shipped default; the bar adds 25 points because it has
        // three lines of small text and no scrim underneath it.
        assertEquals(0.55f, ComposerGlass.topBarTintAlpha(30), 1e-6f)
        assertEquals(0.25f, ComposerGlass.topBarTintAlpha(0), 1e-6f)
        // …and the margin must never push the tint past opaque: 100% stays 1f.
        assertEquals(1f, ComposerGlass.topBarTintAlpha(100), 0f)
        assertEquals(1f, ComposerGlass.topBarTintAlpha(250), 0f)
    }

    @Test
    fun `blur radius is clamped to the range the panel offers`() {
        assertEquals(16, ComposerGlass.blurDp(16))
        assertEquals("below the floor", 4, ComposerGlass.blurDp(1))
        assertEquals("above the ceiling", 40, ComposerGlass.blurDp(9_999))
        assertEquals("a negative radius must not reach RenderEffect", 4, ComposerGlass.blurDp(-30))
    }

    @Test
    fun `the shipped defaults sit inside the range the panel offers`() {
        val blur = ChatTuningPrefs.COMPOSER_GLASS_BLUR_DEFAULT
        val tint = ChatTuningPrefs.COMPOSER_GLASS_TINT_DEFAULT
        assertEquals(
            "a default radius outside the range would be silently re-clamped on every read",
            blur,
            ComposerGlass.blurDp(blur),
        )
        assertTrue("default density must be a percentage", tint in 0..100)
        assertFalse("0% would render the switch as a no-op", tint == 0)
        assertFalse("100% would erase the card colour entirely", tint == 100)
    }
}
