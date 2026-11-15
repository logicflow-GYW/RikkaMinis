package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [composer-glass-1115] The frosted-glass composer is a three-link chain, and
 * every link fails SILENTLY when it is unwired:
 *
 *   1. `rememberHazeState()` in ChatScreen — one state shared by both ends.
 *   2. `hazeSource(hazeState)` on the message list — the ONLY thing that
 *      records pixels for the card to blur.
 *   3. `hazeEffect(state = hazeState, …)` on the card, INSIDE its rounded clip.
 *
 * Break any link and the app still compiles, still runs, and still renders a
 * card — it is just a flat tint instead of a blur, which nobody can tell from a
 * screenshot diff because both are "a rounded rectangle". That is exactly the
 * class of defect this codebase pins with source-shape tests instead of unit
 * tests (same approach as `ShareDirPruneWiringTest` / `MirrorPublicBaseWiring
 * Test`): the real behaviour needs a device + a GPU, the WIRING does not.
 *
 * The `composerArcClipShape` assertion is the load-bearing one: the arc clip
 * (composer-arc-1114) clips the list to "everything above the card", i.e. it
 * removes precisely the rows the glass is supposed to sample. Re-adding it
 * would leave the effect visually dead while every other assertion here still
 * passes.
 */
class ComposerGlassWiringTest {

    private val screenSrc: String by lazy { readRepoFile(".../ChatScreen.kt") }

    private val composerSrc: String by lazy { readRepoFile(".../ChatInputArea.kt") }

    @Test
    fun `the screen owns exactly one haze state and hands it to the composer`() {
        assertTrue(
            "the card samples the state the list records into — two states means the card blurs nothing",
            screenSrc.contains("val hazeState = rememberHazeState()"),
        )
        assertTrue(
            "the composer must receive that same state by name",
            screenSrc.contains("hazeState = hazeState,"),
        )
    }

    @Test
    fun `the message list is the haze source`() {
        assertTrue(
            "without hazeSource the recording is empty and the card has nothing to blur",
            screenSrc.contains(".hazeSource(hazeState)"),
        )
    }

    @Test
    fun `the card draws the effect and keeps a flat fallback for API below 31`() {
        assertTrue(
            "the card is opaque without the effect",
            composerSrc.contains("hazeEffect(state = hazeState"),
        )
        assertTrue(
            "blur needs RenderEffect (Android 12+); the fallback keeps the card readable below it",
            composerSrc.contains("fallbackTint = HazeTint("),
        )
    }

    @Test
    fun `the effect sits inside the rounded clip`() {
        val clip = composerSrc.indexOf(".clip(RoundedCornerShape(ComposerCardCornerRadius))")
        val effect = composerSrc.indexOf(".hazeEffect(state = hazeState")
        assertTrue("card clip not found", clip >= 0)
        assertTrue("card effect not found", effect >= 0)
        assertTrue(
            "clip must come FIRST: applied after the effect it would not touch the drawn blur, " +
                "and the blur would square off the card's corners",
            clip < effect,
        )
    }

    @Test
    fun `the list runs under the card instead of being clipped away from it`() {
        assertFalse(
            "composerArcClipShape clips exactly the rows the glass samples — the effect goes flat",
            screenSrc.contains(".clip(composerArcClipShape)"),
        )
        assertTrue(
            "the list still has to be MEASURED taller than its slot, or only the old 22dp band is behind the card",
            screenSrc.contains(".composerArcTail(overlap = composerHeightDp)"),
        )
    }

    @Test
    fun `the measured composer height feeds both the extension and the bottom padding`() {
        assertTrue(
            "measurement is what makes the tail track a growing composer",
            screenSrc.contains("onSizeChanged { composerHeightPx = it.height }"),
        )
        assertTrue(
            "the viewport grew by the composer height, so the clearance must grow by the same value — " +
                "otherwise the last row slides down behind the card",
            // Whitespace-normalised: the expression wraps across lines, and a
            // line-break-sensitive assertion would break on reformatting
            // without any behaviour change.
            screenSrc.replace(Regex("\\s+"), " ").contains(") + composerHeightDp,"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(suffix: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val hit = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/ui/chat")
            if (hit.isDirectory) {
                return File(hit, suffix.removePrefix(".../")).readText()
            }
            val direct = File(dir, suffix.removePrefix(".../"))
            if (direct.isFile) return direct.readText()
            dir = dir.parentFile
        }
        error("$suffix not found from user.dir=${System.getProperty("user.dir")}")
    }
}
