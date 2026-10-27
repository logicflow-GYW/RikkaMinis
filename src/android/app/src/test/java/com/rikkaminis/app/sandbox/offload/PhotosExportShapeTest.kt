package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [deep-audit-1034 FIX-A3] Pins for the two halves of the photos fix.
 *
 * ISSUE-05: `android-photos export --size thumb|medium` on a VIDEO wrote the
 * untouched video bytes into a file named `…_thumb_clip.jpg` and reported
 * `"format": "jpeg"` — the name and the label both described a transcode that
 * never happened. The decision (`copiesSourceBytes` / `format` label / file
 * name) now lives in top-level pure helpers, so it is pinned by real execution
 * here instead of by a source grep. Every expectation below is a written
 * literal, read off an independent model of the spec
 * (`probe-a3/model.py`), never derived from the code under test.
 *
 * ISSUE-06: A13+ splits media access into READ_MEDIA_IMAGES / READ_MEDIA_VIDEO,
 * and this app only ever declared / requested / checked the images half, so a
 * device full of clips answered `android-photos stats` with `total_photos: 0`.
 * That half is wiring (manifest + two permission functions + a query), and
 * wiring is only observable in the source, so those assertions slice the exact
 * declarations that were changed (same technique as PhotosImportShapeTest).
 *
 * Repo root comes from -Dprobe.repo when set, else it walks up from user.dir.
 */
class PhotosExportShapeTest {

    private val photos =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"
    private val manifest = "src/android/app/src/main/AndroidManifest.xml"

    // ── ISSUE-05: is the copy verbatim, and what does `format` say? ────────

    @Test
    fun `a video is a verbatim copy at every --size`() {
        for (size in listOf("thumb", "medium", "original")) {
            assertTrue("video/$size must copy the source bytes", exportCopiesSourceBytes(size, "video"))
            assertEquals(
                "video/$size bytes were never re-encoded, so format must not claim jpeg",
                "original",
                exportFormatLabel(size, "video"),
            )
        }
    }

    @Test
    fun `a photo is a verbatim copy only at original`() {
        assertTrue(exportCopiesSourceBytes("original", "photo"))
        assertEquals("original", exportFormatLabel("original", "photo"))

        assertFalse(exportCopiesSourceBytes("thumb", "photo"))
        assertEquals("jpeg", exportFormatLabel("thumb", "photo"))

        assertFalse(exportCopiesSourceBytes("medium", "photo"))
        assertEquals("jpeg", exportFormatLabel("medium", "photo"))
    }

    // ── ISSUE-05: the output file name ────────────────────────────────────

    @Test
    fun `a video keeps its real container extension whatever the size suffix`() {
        assertEquals("42_thumb_clip.mp4", exportFileName(42L, "thumb", "video", "clip.mp4", "mp4"))
        assertEquals("42_medium_clip.mp4", exportFileName(42L, "medium", "video", "clip.mp4", "mp4"))
        assertEquals("42_clip.mp4", exportFileName(42L, "original", "video", "clip.mp4", "mp4"))
        assertEquals(
            "the suffix marks the request, the extension marks the bytes",
            "11_medium_holiday.video.mp4",
            exportFileName(11L, "medium", "video", "holiday.video.mp4", "mp4"),
        )
    }

    @Test
    fun `a video whose display name carries no extension still gets one`() {
        assertEquals("7_thumb_clip.mp4", exportFileName(7L, "thumb", "video", "clip", "mp4"))
        assertEquals("7_clip.mp4", exportFileName(7L, "original", "video", "clip", "mp4"))
    }

    @Test
    fun `non-mp4 containers and uppercase extensions survive untouched`() {
        assertEquals("9_thumb_clip.mov", exportFileName(9L, "thumb", "video", "clip.mov", "mov"))
        assertEquals("9_thumb_IMG.MP4", exportFileName(9L, "thumb", "video", "IMG.MP4", "MP4"))
    }

    @Test
    fun `sanitizing the stem is unchanged`() {
        // The handler sanitizes first and passes the STEM in; the name builder
        // never sees the raw display name.
        assertEquals("my_clip__1_.mp4", exportSafeName("my clip (1).mp4"))
        assertEquals("12_thumb_my_clip__1_.mp4", exportFileName(12L, "thumb", "video", "my_clip__1_.mp4", "mp4"))
        assertEquals("a_b_c.jpg", exportSafeName("a b/c.jpg"))
        assertEquals("___001.jpg", exportSafeName("\u7167\u7247 001.jpg"))
        assertEquals("no-extension", exportSafeName("no-extension"))
        assertEquals(64, exportSafeName("a".repeat(100) + ".jpg").length)
    }

    @Test
    fun `the extension helper falls back by media kind`() {
        assertEquals("mp4", exportExt("clip", "video"))
        assertEquals("jpg", exportExt("clip", "photo"))
        assertEquals("mp4", exportExt("clip.mp4", "video"))
        assertEquals("mov", exportExt("a.b.mov", "video"))
        assertEquals("MP4", exportExt("clip.MP4", "video"))
    }

    // ── ISSUE-05: the photo path is byte-for-byte the old behaviour ────────

    @Test
    fun `a photo thumb is still a forced-jpg name`() {
        // Pre-existing (and ugly) shape: the stem keeps its own extension and
        // the forced `.jpg` is appended, which is what shipped before this fix.
        // Pinned so "photo exports were not part of this change" is checked,
        // not merely claimed.
        assertEquals("5_thumb_IMG_0001.jpg.jpg", exportFileName(5L, "thumb", "photo", "IMG_0001.jpg", "jpg"))
        assertEquals("5_thumb_IMG_0001.png.jpg", exportFileName(5L, "thumb", "photo", "IMG_0001.png", "png"))
        assertEquals("6_medium_IMG_0002.jpeg.jpg", exportFileName(6L, "medium", "photo", "IMG_0002.jpeg", "jpeg"))
        assertEquals("5_IMG_0001.png", exportFileName(5L, "original", "photo", "IMG_0001.png", "png"))
        assertEquals("5_IMG_0001.jpg", exportFileName(5L, "original", "photo", "IMG_0001", "jpg"))
    }

    // ── ISSUE-05: wiring + the contracts that had to survive ──────────────

    @Test
    fun `handleExport routes naming and the format label through the shared core`() {
        val body = declarationSlice(readRepoFile(photos), "private fun handleExport(")
        assertTrue(body.contains("exportFileName(id, size, mediaType, safeName, ext)"))
        assertTrue(body.contains("exportFormatLabel(size, mediaType)"))
        assertTrue(body.contains("exportCopiesSourceBytes(size, mediaType)"))
        assertTrue(body.contains("exportSafeName(displayName)"))
        assertTrue(body.contains("exportExt(displayName, mediaType)"))
    }

    @Test
    fun `the lying Before-shapes are gone`() {
        val body = declarationSlice(readRepoFile(photos), "private fun handleExport(")
        assertFalse(
            "the forced .jpg suffix came back",
            body.contains("if (size == \"original\") \"\" else \".jpg\""),
        )
        assertFalse(
            "the format label is being decided inline again",
            body.contains("if (size == \"original\") \"original\" else \"jpeg\""),
        )
    }

    @Test
    fun `an ignored --size is announced instead of silently ignored`() {
        val body = declarationSlice(readRepoFile(photos), "private fun handleExport(")
        assertTrue(body.contains("if (mediaType == \"video\") {"))
        assertTrue(body.contains("size_note"))
        assertTrue(
            body.contains("video assets are always copied as-is; --size is ignored for videos"),
        )
    }

    @Test
    fun `handleExport keeps every contract it had before`() {
        val body = declarationSlice(readRepoFile(photos), "private fun handleExport(")
        // not_found branch, verbatim
        assertTrue(body.contains("JSONObject().put(\"error\", \"not_found\").put(\"id\", id)"))
        assertTrue(body.contains("No asset with id=\$id (already deleted, or not visible to this app)."))
        // the requested-size and media identity keys stay put
        for (key in listOf("\"id\"", "\"host_path\"", "\"size_bytes\"", "\"media_type\"", "\"export_size\"")) {
            assertTrue("export JSON lost $key", body.contains(".put($key,"))
        }
        // the photo re-encode path and the prune point are untouched
        assertTrue(body.contains("copyResized(src, outFile, maxEdge)"))
        assertTrue(body.contains("val maxEdge = if (size == \"thumb\") 256 else 1024"))
        assertTrue(body.contains("pruneShareDir(outDir)"))
        assertTrue(body.contains("File(context.filesDir, \"photos-export\")"))
        // no video ever reaches BitmapFactory
        assertTrue(body.contains("if (copiesSourceBytes) {"))
    }

    // ── ISSUE-06: permission surface ──────────────────────────────────────

    @Test
    fun `the manifest declares the video half of the A13+ media pair`() {
        val xml = readRepoFile(manifest)
        assertEquals(
            "exactly one READ_MEDIA_VIDEO grant",
            1,
            Regex("""<uses-permission[^>]*READ_MEDIA_VIDEO""").findAll(xml).count(),
        )
        assertTrue(xml.contains("<uses-permission android:name=\"android.permission.READ_MEDIA_VIDEO\" />"))
        assertTrue(xml.contains("<uses-permission android:name=\"android.permission.READ_MEDIA_IMAGES\" />"))
        // the legacy grant keeps its API-32 cap, and no cap was bolted onto the pair
        assertTrue(
            xml.contains(
                "<uses-permission android:name=\"android.permission.READ_EXTERNAL_STORAGE\" android:maxSdkVersion=\"32\" />",
            ),
        )
        assertFalse("the granular grants must not be maxSdkVersion-capped", xml.contains("READ_MEDIA_VIDEO\" android:"))
    }

    @Test
    fun `the media gate accepts either granular class`() {
        val gate = declarationSlice(readRepoFile(photos), "private fun hasMediaPermission(")
        assertTrue(gate.contains("Manifest.permission.READ_MEDIA_IMAGES"))
        assertTrue(gate.contains("Manifest.permission.READ_MEDIA_VIDEO"))
        assertTrue(gate.contains("Manifest.permission.READ_EXTERNAL_STORAGE"))
        // the A14 partial branch keeps its exact semantics
        assertTrue(gate.contains("\"android.permission.READ_MEDIA_VISUAL_USER_SELECTED\""))
        assertTrue(gate.contains("if (Build.VERSION.SDK_INT >= 34) {"))
    }

    @Test
    fun `the permission request asks for both granular classes`() {
        val flow = declarationSlice(readRepoFile(photos), "private fun ensureMediaPermission(")
        assertTrue(flow.contains("add(Manifest.permission.READ_MEDIA_IMAGES)"))
        assertTrue(flow.contains("add(Manifest.permission.READ_MEDIA_VIDEO)"))
        assertTrue(flow.contains("add(\"android.permission.READ_MEDIA_VISUAL_USER_SELECTED\")"))
        // refusal envelope contract
        assertTrue(flow.contains("OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult("))
        assertTrue(flow.contains("77,"))
    }

    @Test
    fun `stats counts videos in their own fields`() {
        val body = declarationSlice(readRepoFile(photos), "private fun photoStats(")
        assertTrue(body.contains("MediaStore.Images.Media.EXTERNAL_CONTENT_URI"))
        assertTrue(body.contains("MediaStore.Video.Media.EXTERNAL_CONTENT_URI"))
        assertTrue(body.contains(".put(\"total_videos\", videoCount)"))
        assertTrue(body.contains(".put(\"total_video_size_mb\", String.format(\"%.1f\", videoSize / 1e6))"))
        // the images-only pair keeps its meaning: merging video bytes into
        // total_size_mb would break the average against total_photos
        assertTrue(body.contains(".put(\"total_size_mb\", String.format(\"%.1f\", totalSize / 1e6))"))
        assertTrue(body.contains(".put(\"total_photos\", count)"))
        assertFalse("video bytes must not be folded into the photo total", body.contains("totalSize + videoSize"))
        assertFalse(body.contains("totalSize += videoSize"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, photos).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String {
        val f = File(repoRoot(), relative)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    /** Source lines of the declaration at [marker] up to the next member. */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") || t.startsWith("    public fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
