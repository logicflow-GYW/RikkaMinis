package com.rikkaminis.app.ui.media

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv17-B08] The fullscreen video player must own BOTH halves of the
 * system-bar contract that its three siblings already implement:
 *
 *  1. a HOST-activity-window `DisposableEffect` that saves the bars, hides
 *     them for the lifetime of the player, and restores them on dispose
 *     (`ImageGalleryViewer` / `WebPreviewFullscreenScreen` /
 *     `WebPreviewBottomSheet` all carry one) — the Dialog window effect alone
 *     cannot cover the window the system falls back to;
 *  2. `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` on the Dialog's own window, which is
 *     what makes the `statusBarColor = TRANSPARENT` right below it take
 *     effect (without the flag Android paints its own opaque bar background,
 *     i.e. a grey band over the black video).
 *
 * `MinisFullscreenVideoPlayer` is a `@Composable` and its window handling is
 * real-Android-bound (a real `Dialog` window, a real insets controller), so
 * neither half is reachable from a plain JVM test. These tests therefore pin
 * the SHAPE of the real shipped source — the house pattern already used by
 * `ProviderEntryKeyAndPinTest` — with every expectation written as a literal
 * (evidence-discipline: never derive an expectation from the code under test,
 * or a mutation moves it too).
 *
 * Mutation notes: deleting the host effect, moving it AFTER `Dialog(`, swapping
 * the bare `addFlags` for a comment about the flag, or dropping the flag from
 * the argument list must each turn one of these tests red.
 */
class FullscreenVideoWindowContractTest {

    private val player =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/media/InlineMediaPlayer.kt"

    // ── 1. host window: save / hide / restore ─────────────────────────────

    @Test
    fun `the fullscreen player saves and hides the host window bars before its Dialog`() {
        val body = bodyOf(readRepoFile(player), "fun MinisFullscreenVideoPlayer(")
        val effect = body.indexOf("DisposableEffect(")
        val dialog = body.indexOf("Dialog(")
        assertTrue("the host-window effect is gone", effect >= 0)
        assertTrue("the fullscreen Dialog is gone", dialog >= 0)
        assertTrue(
            "the host-window effect must be installed BEFORE the Dialog opens",
            effect < dialog,
        )
        val host = body.substring(effect, dialog)
        assertTrue(
            "the effect must target the HOST activity window, not the dialog window",
            host.contains("(hostView.context as? android.app.Activity)?.window"),
        )
        assertTrue(
            "the host bars must be hidden (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)",
            host.contains(
                "androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE",
            ),
        )
        assertTrue(
            "the host bars must actually be hidden",
            host.contains("controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())"),
        )
    }

    @Test
    fun `the host window bars are restored on dispose`() {
        val body = bodyOf(readRepoFile(player), "fun MinisFullscreenVideoPlayer(")
        val effect = body.indexOf("DisposableEffect(")
        val dialog = body.indexOf("Dialog(")
        assertTrue("the host-window effect is gone", effect >= 0)
        assertTrue("the fullscreen Dialog is gone", dialog >= 0)
        assertTrue("the host-window effect must precede the Dialog", effect < dialog)
        val effText = body.substring(effect, dialog)
        assertTrue("the effect must restore in onDispose", effText.contains("onDispose {"))
        val dispose = effText.substringAfter("onDispose {")
        assertTrue(
            "the bars must be shown again",
            dispose.contains("controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())"),
        )
        // Restoring the *previous* appearance is the half that keeps a dark-mode
        // host from being left with light icons after the video closes.
        assertTrue(
            "the previous status-bar icon appearance must be restored",
            dispose.contains("controller.isAppearanceLightStatusBars = previousLightStatusBars"),
        )
        assertTrue(
            "the previous navigation-bar icon appearance must be restored",
            dispose.contains("controller.isAppearanceLightNavigationBars = previousLightNavigationBars"),
        )
    }

    @Test
    fun `the appearance is captured before it is overwritten`() {
        val body = bodyOf(readRepoFile(player), "fun MinisFullscreenVideoPlayer(")
        val effect = body.indexOf("DisposableEffect(")
        val dialog = body.indexOf("Dialog(")
        assertTrue("the host-window effect is gone", effect >= 0)
        assertTrue("the fullscreen Dialog is gone", dialog >= 0)
        assertTrue("the host-window effect must precede the Dialog", effect < dialog)
        val effText = body.substring(effect, dialog)
        val capture = effText.indexOf("val previousLightStatusBars = controller.isAppearanceLightStatusBars")
        val overwrite = effText.indexOf("controller.isAppearanceLightStatusBars = false")
        assertTrue("the previous appearance is no longer captured", capture >= 0)
        assertTrue("the immersive appearance is no longer applied", overwrite >= 0)
        assertTrue("the capture must happen before the overwrite", capture < overwrite)
    }

    // ── 2. dialog window: the draw-behind flag ────────────────────────────

    @Test
    fun `the fullscreen Dialog window declares it draws the system bar backgrounds`() {
        val body = bodyOf(readRepoFile(player), "private fun FullscreenVideoContent(")
        val flag =
            "window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)"
        assertTrue(
            "the Dialog window must call addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) -- " +
                "without it the transparent bar colors below are ignored",
            body.contains(flag),
        )
        // Bound to the decision point: the flag must land in the same window
        // config block that makes the bar colors transparent, before them.
        val flagAt = body.indexOf(flag)
        val transparentAt =
            body.indexOf("window.statusBarColor = android.graphics.Color.TRANSPARENT")
        assertTrue("the transparent bar color is gone", transparentAt >= 0)
        assertTrue("the flag must precede the transparent bar color", flagAt < transparentAt)
    }

    @Test
    fun `the flag is an argument of an addFlags call, not a bare mention`() {
        val src = readRepoFile(player)
        val at = src.indexOf("FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS")
        assertTrue("the flag is not mentioned at all", at >= 0)
        // Walk back from the constant to the innermost call that owns it: the
        // call form must be `(<window>).addFlags(`. A comment or a bare
        // constant reference (e.g. copied into a doc block) must not count.
        val open = src.lastIndexOf('(', at)
        assertTrue("the flag is not an argument list member", open > 0)
        val callee = src.substring(0, open)
        assertTrue(
            "the flag must be passed to addFlags( -- found: ${callee.takeLast(40)}",
            callee.endsWith(".addFlags"),
        )
    }

    // ── 3. the sibling contract still holds (symmetry, not a rewrite) ─────

    @Test
    fun `the sibling fullscreen preview keeps both halves of the same contract`() {
        val sibling =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewFullscreenScreen.kt"
        val src = readRepoFile(sibling)
        assertTrue(
            "the template fullscreen preview lost its host-window effect",
            src.contains("(view.context as? Activity)?.window"),
        )
        assertTrue(
            "the template fullscreen preview lost the draw-behind flag",
            src.contains("FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Balanced `{ ... }` of the first function whose declaration matches [marker]. */
    private fun bodyOf(src: String, marker: String): String {
        val m = src.indexOf(marker)
        require(m >= 0) { "marker not found: $marker" }
        val open = src.indexOf('{', m)
        require(open >= 0) { "no body after: $marker" }
        var depth = 0
        for (j in open until src.length) {
            if (src[j] == '{') depth++
            if (src[j] == '}') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
        }
        error("unbalanced body after: $marker")
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run
     * can start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
