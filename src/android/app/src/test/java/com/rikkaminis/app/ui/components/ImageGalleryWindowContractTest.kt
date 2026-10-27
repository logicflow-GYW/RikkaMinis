package com.rikkaminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv14-B1] JVM-pure test for the gallery Dialog window flag contract
 * (`galleryDialogWindowFlags`, ImageGalleryViewer.kt).
 *
 * Bug context (ISSUE-01): the OUTER `DisposableEffect(Unit)` took its window
 * from `findDialogWindow(view)` where `view` is the host composition's view —
 * its parent chain (ContentFrameLayout → FitWindowsLinearLayout → DecorView)
 * never contains a DialogWindowProvider, so `window` was null on every entry
 * and the hide/restore block was a silent no-op. The fix restores
 * `(view.context as? android.app.Activity)?.window` for the host window, and
 * the Dialog's own window now applies
 * FLAG_LAYOUT_NO_LIMITS | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS via
 * [galleryDialogWindowFlags] — without the latter the dialog leaves a grey
 * band under the status bar even with statusBarColor = TRANSPARENT.
 *
 * The window-source decision itself needs a real View hierarchy (no Android
 * SDK in the JVM here) and is pinned by the
 * webview_holder_url_source_check gate (W6a/W6b) plus a shape-assertion
 * script instead.
 *
 * Expected values are dead literals, NOT derived from the same
 * WindowManager.LayoutParams constants the production function composes —
 * a constant change must not silently drag the expectation with it.
 */
class ImageGalleryWindowContractTest {

    // FLAG_LAYOUT_NO_LIMITS = 0x00000200 — NOT 0x00000100 (that is
    // FLAG_LAYOUT_IN_SCREEN; the first CI run red-flagged exactly this:
    // expected -2147483392 (0x80000100, wrong memory) vs was -2147483136
    // (0x80000200, real android.jar). Dead literals stay, values from CI
    // ground truth.
    private val flagLayoutNoLimits: Int = 0x00000200
    // FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS = 0x80000000 (Int.MIN_VALUE as Int)
    private val flagDrawsSystemBarBackgrounds: Int = 0x80000000.toInt()

    @Test fun dialogWindowFlagsIncludeLayoutNoLimits() {
        val flags = galleryDialogWindowFlags()
        assertTrue(
            "gallery dialog window must extend under the display cutout (FLAG_LAYOUT_NO_LIMITS)",
            (flags and flagLayoutNoLimits) != 0,
        )
    }

    @Test fun dialogWindowFlagsIncludeDrawsSystemBarBackgrounds() {
        val flags = galleryDialogWindowFlags()
        assertTrue(
            "gallery dialog window must paint its own system-bar background (no grey band)",
            (flags and flagDrawsSystemBarBackgrounds) != 0,
        )
    }

    @Test fun dialogWindowFlagsComposeToTheExactExpectedValue() {
        // 0x00000200 or 0x80000000 == 0x80000200 — dead literal, verified
        // against CI ground truth (was:<-2147483136>).
        assertEquals(0x80000200.toInt(), galleryDialogWindowFlags())
    }

    // ── [dv21-b4-B06] bounded decode on BOTH viewer load paths ─────────────
    //
    // Source-shape assertions on the decision points, not on call spelling:
    //  - the fullscreen path (Copy / Share / Save) resolves Coil's process
    //    singleton instead of building a private ImageLoader, so `minis://`
    //    models keep their fetcher and rewritten files keep their mtime keyer;
    //  - both paths bound the decode with the shared display ceiling — without
    //    it a 3000x17920 screenshot allocates ~215 MB of ARGB_8888 (the
    //    DisplayBitmapLimits crash).
    //
    // The files are read from the repo root found by walking up from
    // `user.dir`, because Gradle runs unit tests with the module directory as
    // the working directory.

    private val fullscreenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt"
    private val galleryRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ImageGalleryViewer.kt"
    private val minisAppRel = "src/android/app/src/main/java/com/rikkaminis/app/MinisApp.kt"

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

    private fun stripKotlinComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0 && line.substring(0, i).count { it == '"' } % 2 == 0) {
                    line.substring(0, i)
                } else {
                    line
                }
            }

    @Test fun `the fullscreen path resolves the app singleton instead of building a loader`() {
        val src = stripKotlinComments(readRepoFile(fullscreenRel))
        assertTrue(
            "the fullscreen viewer must resolve Coil's process loader (fetcher + mtime keyer)",
            "Coil.imageLoader(" in src,
        )
        assertFalse(
            "no per-call ImageLoader(context) construction (leaked executor, no components)",
            Regex("ImageLoader\\s*\\(\\s*context").containsMatchIn(src),
        )
    }

    @Test fun `the fullscreen load request carries the shared display ceiling`() {
        val src = stripKotlinComments(readRepoFile(fullscreenRel))
        assertTrue("Copy/Share/Save must bound the decode", ".limitDisplaySize()" in src)
        assertTrue("...and the bound must sit on the request", "ImageRequest.Builder(" in src)
    }

    @Test fun `the gallery page request carries the shared display ceiling`() {
        val src = stripKotlinComments(readRepoFile(galleryRel))
        assertTrue("the zoom surface must bound the decode", ".limitDisplaySize()" in src)
        assertTrue("...inside an ImageRequest", "ImageRequest.Builder(" in src)
    }

    @Test fun `the app loader registers the minis fetcher and the mtime keyer`() {
        val app = stripKotlinComments(readRepoFile(minisAppRel))
        val loader = app.substringAfter("override fun newImageLoader()")
        assertTrue(
            "minis:// models need a fetcher or every attachment URI fails to decode",
            "MinisImageFetcher.Factory()" in loader,
        )
        assertTrue(
            "in-place rewrites need the mtime keyer or the stale bitmap comes back",
            "MinisImageFetcher.MtimeKeyer()" in loader,
        )
    }
}
