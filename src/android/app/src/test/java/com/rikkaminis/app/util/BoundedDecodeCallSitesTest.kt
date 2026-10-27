package com.rikkaminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit3-F-13] Pins the four decode call sites that used to run a bare
 * `BitmapFactory.decode*` on the Compose thread.
 *
 * The arithmetic itself is covered by [ImageDecodeSizingTest]; what can still
 * regress silently is the WIRING — someone re-inlining `decodeFile` in a
 * `remember {}` block compiles fine and shows no symptom on a small image,
 * because the failure mode is a transient 50-200 MB native allocation (bitmap
 * pixels live outside the Java heap, so ART's GC pressure never sees it) or a
 * `Canvas: trying to draw too large(...) bitmap` throw from a `RecordingCanvas`.
 *
 * So the production text is asserted directly, with the pre-fix text as the
 * negative control: every reverted site must make the probe fail. The negative
 * controls run as part of the test, which is what makes the pin falsifiable
 * rather than decorative.
 */
class BoundedDecodeCallSitesTest {

    private companion object {
        const val SHEET = "app/src/main/java/com/rikkaminis/app/webapp/AddToHomeSheet.kt"
        const val QUICK_TEST = "app/src/main/java/com/rikkaminis/app/ui/components/QuickTestSheet.kt"
    }

    @Test
    fun `AddToHomeSheet decodes icons through ImageDecoding and recycles both temporaries`() {
        val src = readRepoFile(SHEET)
        assertSheetShape(src)

        // Negative controls: each pre-fix line, restored verbatim.
        assertProbeRejects(
            src.replace(
                "ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap",
                "runCatching { BitmapFactory.decodeFile(path) }.getOrNull()",
            ),
            ::assertSheetShape,
        )
        assertProbeRejects(
            src.replace(
                "ImageDecoding.decodeBytesBounded(bytes, maxEdge)?.bitmap",
                "",
            ),
            ::assertSheetShape,
        )
        assertProbeRejects(
            src.replace(
                "ImageDecoding.decodeFileBounded(choice.path, size)?.bitmap",
                "runCatching { BitmapFactory.decodeFile(choice.path) }.getOrNull()",
            ),
            ::assertSheetShape,
        )
        // Dropping the recycles restores the native-memory leak (the pre-fix
        // code leaked both the full-size source and the 192x192 copy).
        assertProbeRejects(src.replace("if (scaled !== src) scaled.recycle()", ""), ::assertSheetShape)
        assertProbeRejects(src.replace("src.recycle()", ""), ::assertSheetShape)
    }

    @Test
    fun `QuickTestSheet decodes image replies through ImageDecoding`() {
        val src = readRepoFile(QUICK_TEST)
        assertQuickTestShape(src)
        assertProbeRejects(
            src.replace(
                "ImageDecoding.decodeBytesBounded(s.data, QUICKTEST_MAX_EDGE_PX)?.bitmap",
                "runCatching { BitmapFactory.decodeByteArray(s.data, 0, s.data.size) }.getOrNull()",
            ),
            ::assertQuickTestShape,
        )
    }

    @Test
    fun `the two sheets carry the bounds their layouts were sized for`() {
        val sheet = readRepoFile(SHEET)
        val quick = readRepoFile(QUICK_TEST)
        assertTrue(
            "the icon bound is derived from the 192x192 launcher icon",
            sheet.contains("private const val ICON_MAX_EDGE_PX = 192"),
        )
        assertTrue(
            "the image-reply bound is derived from the 220.dp preview row",
            quick.contains("private const val QUICKTEST_MAX_EDGE_PX = 1024"),
        )
        // Both previews are drawn at 36.dp, so the 192 px bound has ~5x of
        // density headroom — asserted so a future edit cannot quietly shrink
        // the bound below the size the layout actually shows.
        assertTrue(sheet.contains("Modifier.size(36.dp)"))
    }

    private fun assertSheetShape(src: String) {
        assertTrue(
            "the webpage icon preview must decode through ImageDecoding",
            src.contains("ImageDecoding.decodeFileBounded(path, ICON_MAX_EDGE_PX)?.bitmap"),
        )
        assertTrue(
            "loadBitmapFromUri must decode the picked image through ImageDecoding",
            src.contains("ImageDecoding.decodeBytesBounded(bytes, maxEdge)?.bitmap"),
        )
        assertTrue(
            "renderIconBitmap must decode through ImageDecoding for the webpage branch",
            src.contains("ImageDecoding.decodeFileBounded(choice.path, size)?.bitmap"),
        )
        assertTrue(
            "renderIconBitmap must decode through ImageDecoding for the gallery branch",
            src.contains("loadBitmapFromUri(context, choice.uri, maxEdge = size)"),
        )
        assertEquals(
            "both renderIconBitmap batches must recycle the scaled temporary",
            2,
            countOccurrences(src, "scaled.recycle()"),
        )
        assertEquals(
            "both renderIconBitmap batches must recycle the decoded source",
            2,
            countOccurrences(src, "src.recycle()"),
        )
        assertTrue(
            "an unsampled BitmapFactory.decode* must not come back into this file",
            !src.contains("BitmapFactory"),
        )
    }

    private fun assertQuickTestShape(src: String) {
        assertTrue(
            "the image reply preview must decode through ImageDecoding",
            src.contains("ImageDecoding.decodeBytesBounded(s.data, QUICKTEST_MAX_EDGE_PX)?.bitmap"),
        )
        assertTrue(
            "an unsampled BitmapFactory.decode* must not come back into this file",
            !src.contains("BitmapFactory"),
        )
    }

    private fun assertProbeRejects(mutated: String, probe: (String) -> Unit) {
        var rejected = false
        try {
            probe(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts the pre-fix shape", rejected)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works.
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
