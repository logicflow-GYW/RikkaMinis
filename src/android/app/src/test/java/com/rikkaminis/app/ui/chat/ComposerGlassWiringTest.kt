package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.ChatTuningPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [composer-glass-1115] The frosted-glass composer is a five-link chain, and
 * every link fails SILENTLY when it is unwired:
 *
 *   1. `rememberHazeState()` in ChatScreen — one state shared by both ends.
 *   2. `hazeSource(hazeState)` on the message list — the only thing that records
 *      pixels for the card to blur.
 *   3. `hazeEffect(state = …, style = …)` on the card, INSIDE its rounded clip.
 *   4. the list's drawing extent (`composerArcTail`) and its bottom padding must
 *      move together, or the last row slides behind the card.
 *   5. the Chat Tuning switch must gate ALL FOUR of the above: a switch that
 *      only greys out the card while the list still records is a per-frame cost
 *      for nothing; a switch that leaves the arc clip on is a flat tint.
 *
 * Break any link and the app still compiles, still runs and still renders a
 * card — it just isn't glass. That is exactly the class of defect this codebase
 * pins with source-shape tests instead of unit tests (same approach as
 * `ShareDirPruneWiringTest`): the real behaviour needs a device + a GPU, the
 * WIRING does not.
 *
 * The load-bearing assertion is #5's clip arm — re-adding the arc clip
 * unconditionally would clip away precisely the rows the glass samples while
 * every other assertion here still passes.
 */
class ComposerGlassWiringTest {

    private val screenSrc: String by lazy { readRepoFile("ChatScreen.kt") }

    private val composerSrc: String by lazy { readRepoFile("ChatInputArea.kt") }

    /** Whitespace-collapsed view: these chains wrap across lines by design. */
    private val screenFlat: String by lazy { screenSrc.replace(Regex("\\s+"), " ") }

    private val composerFlat: String by lazy { composerSrc.replace(Regex("\\s+"), " ") }

    @Test
    fun `the screen owns exactly one haze state and hands it to the composer`() {
        assertTrue(
            "the card samples the state the list records into — two states means the card blurs nothing",
            screenFlat.contains("val hazeState = rememberHazeState()"),
        )
        assertTrue(
            "the composer must receive that same state by name",
            screenFlat.contains("hazeState = hazeState,"),
        )
    }

    @Test
    fun `the message list records into that state, and only while the effect is on`() {
        assertTrue(
            "without hazeSource the recording is empty and the card has nothing to blur",
            screenFlat.contains("if (composerGlassOn) Modifier.hazeSource(hazeState) else Modifier"),
        )
    }

    @Test
    fun `the card draws the effect and keeps a flat fallback for API below 31`() {
        assertTrue(
            "the card is opaque without the effect",
            composerFlat.contains("hazeEffect(state = hazeState, style = composerGlassStyle)"),
        )
        assertTrue(
            "blur needs RenderEffect (Android 12+); the fallback keeps the card readable below it",
            composerFlat.contains("fallbackTint = HazeTint(inputBgColor)"),
        )
    }

    @Test
    fun `the effect sits inside the rounded clip and carries the hairline border`() {
        val clip = composerSrc.indexOf(".clip(RoundedCornerShape(ComposerCardCornerRadius))")
        val effect = composerSrc.indexOf(".hazeEffect(state = hazeState")
        assertTrue("card clip not found", clip >= 0)
        assertTrue("card effect not found", effect >= 0)
        assertTrue(
            "clip must come FIRST: applied after the effect it would not touch the drawn blur, " +
                "and the blur would square off the card's corners",
            clip < effect,
        )
        assertTrue(
            "without an edge the blur reads as a haze fading into the page, not as a surface",
            composerFlat.contains("border( 0.5.dp, ChatColors.inputBorder,"),
        )
    }

    @Test
    fun `the arc clip is the OFF branch, never an unconditional step`() {
        val onBranch = screenFlat.indexOf("if (composerGlassOn) Modifier else Modifier.clip(composerArcClipShape)")
        assertTrue(
            "composerArcClipShape clips exactly the rows the glass samples — it may only run with the effect OFF",
            onBranch >= 0,
        )
        assertFalse(
            "an unconditional .clip(composerArcClipShape) in the chain would make the effect look dead",
            screenFlat.contains(".composerArcTail( overlap = if (composerGlassOn) composerHeightDp else ComposerArcOverlap, ) .clip("),
        )
    }

    @Test
    fun `the measured composer height drives the drawing extent and the bottom padding together`() {
        assertTrue(
            "measurement is what makes the extent track a growing composer",
            screenFlat.contains("onSizeChanged { composerHeightPx = it.height }"),
        )
        assertTrue(
            "the list must be allowed to draw the full composer height under the card",
            screenFlat.contains("if (composerGlassOn) composerHeightDp else ComposerArcOverlap"),
        )
        assertTrue(
            "the viewport grew by composerHeightDp, so the clearance must grow by the same value — " +
                "otherwise the last row slides down behind the card",
            screenFlat.contains(") + if (composerGlassOn) composerHeightDp else ComposerArcOverlap,"),
        )
    }

    @Test
    fun `both knobs reach the card from Chat Tuning`() {
        assertTrue(
            "blur radius plumbing",
            screenFlat.contains("composerGlassBlurDp = tuning.composerGlassBlurDp,"),
        )
        assertTrue(
            "density plumbing",
            screenFlat.contains("composerGlassTintPct = tuning.composerGlassTintPct,"),
        )
        assertTrue(
            "the switch itself",
            screenFlat.contains("composerGlassEnabled = composerGlassOn,"),
        )
        assertTrue(
            "the composer must convert both numbers through the clamped helpers, not raw",
            composerFlat.contains("ComposerGlass.tintAlpha(composerGlassTintPct)"),
        )
        assertTrue(
            "the composer must convert both numbers through the clamped helpers, not raw",
            composerFlat.contains("ComposerGlass.blurDp(composerGlassBlurDp)"),
        )
    }

    @Test
    fun `the default is OFF, like rikkahub`() {
        assertEquals(
            "a default-on effect would bill every user for a per-frame list recording",
            false,
            ChatTuningPrefs.COMPOSER_GLASS_ENABLED_DEFAULT,
        )
        assertTrue(
            "the switch must exist in the tuning store the composer reads",
            screenFlat.contains("val composerGlassOn = tuning.composerGlassEnabled"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            for (relative in listOf(
                "src/android/app/src/main/java/com/rikkaminis/app/ui/chat",
                "app/src/main/java/com/rikkaminis/app/ui/chat",
            )) {
                val hit = File(dir, "$relative/$name")
                if (hit.isFile) return hit.readText()
            }
            dir = dir.parentFile
        }
        error("$name not found from user.dir=${System.getProperty("user.dir")}")
    }
}
