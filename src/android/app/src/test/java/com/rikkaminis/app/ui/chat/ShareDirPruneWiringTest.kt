package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.ui.components.pruneShareDir
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [fix/da1028-c] — deep-audit-1028 ISSUE-05.
 *
 * `ui/components/pruneShareDir` (24h TTL, `isFile`, strict `<`) was wired into
 * the five `cacheDir/share*` producers, but three writers stage into dirs the
 * OS has NO reason to reclaim — `filesDir/camera-photos`,
 * `filesDir/photos-export` and `externalFilesDir/a11y_screenshots` (which
 * falls back to `cacheDir` but prefers the external one). Nothing ever
 * deleted them, so they grew for the life of the install; the a11y one is the
 * worst of the three because what accumulates is plaintext screenshots.
 *
 * Two halves, on purpose:
 *  - BEHAVIOUR: the real `pruneShareDir` is pure `java.io` and needs no
 *    Context, so it is run for real against temp dirs. Expected outcomes are
 *    hardcoded literals (durations and existence), never derived from the
 *    pruner's constants.
 *  - WIRING: the three call sites are inside Compose/handler code that needs a
 *    real Context, so they are pinned by source-shape assertions (the approach
 *    `SharedCacheExportPruneTest` uses). `ChatMutationMethods
 *    .pruneStaleAttachments` must DELEGATE rather than keep a second copy of
 *    the loop — two copies of the TTL can drift apart silently.
 *
 * Ordering matters and is asserted: prune-then-write is what keeps the freshly
 * staged file safe (the 24h cutoff always postdates it).
 */
class ShareDirPruneWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // -- behaviour: the real pruner, run for real -------------------------

    @Test
    fun `only files older than the ttl age out, and the dir stays`() {
        val hour = 60L * 60L * 1000L
        val dir = tmp.newFolder("camera-photos")
        val aged25h = aged(File(dir, "photo-100.jpg"), 25 * hour)
        val aged48hNamedNothing = aged(File(dir, "unrelated.tmp"), 48 * hour)
        val fresh = File(dir, "photo-200.jpg").also { it.writeText("jpg") }
        val justInside = aged(File(dir, "photo-300.jpg"), 24 * hour - 5 * 60L * 1000L)

        pruneShareDir(dir)

        assertFalse("25h-old capture must be removed", aged25h.exists())
        assertFalse(
            "the pruner filters by AGE only — a stray file in the dir ages out too",
            aged48hNamedNothing.exists(),
        )
        assertTrue("a capture written just now must survive", fresh.exists())
        assertTrue(
            "mtime 5 minutes inside the 24h window must survive (strict `<` cutoff)",
            justInside.exists(),
        )
        assertTrue("the staging dir itself must survive", dir.isDirectory)
    }

    @Test
    fun `subdirectories are never followed`() {
        val hour = 60L * 60L * 1000L
        val dir = tmp.newFolder("photos-export")
        val sub = File(dir, "nested").apply { mkdirs() }
        val inner = aged(File(sub, "old.jpg"), 72 * hour)
        assertTrue(sub.setLastModified(System.currentTimeMillis() - 72 * hour))

        pruneShareDir(dir)

        assertTrue("only files are pruned (`isFile` guard)", sub.isDirectory)
        assertTrue("and nothing inside a subdir is touched", inner.exists())
    }

    @Test
    fun `an empty or missing dir is a no-op`() {
        val empty = tmp.newFolder("empty")
        pruneShareDir(empty)
        assertTrue(empty.isDirectory)

        val missing = File(tmp.root, "never-created")
        pruneShareDir(missing)
        assertFalse(missing.exists())
    }

    // -- wiring: the three new call sites ---------------------------------

    private val cameraSrc: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreenHelpers.kt")
    }
    private val photosSrc: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt")
    }
    private val a11ySrc: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt")
    }
    private val mutationSrc: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/debug/ChatMutationMethods.kt")
    }
    private val prunerSrc: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/components/FullscreenImageViewer.kt")
    }

    @Test
    fun `camera-photos prunes before it stages the new capture`() {
        val fn = bracedBlock(cameraSrc, "internal fun createCameraOutputUri(")
        assertTrue(
            "filesDir/camera-photos is not cacheDir — nothing else can reclaim it",
            fn.contains("pruneShareDir(dir)"),
        )
        assertOrder(fn, "pruneShareDir(dir)", "createNewFile()")
    }

    @Test
    fun `photos-export prunes before it writes the export`() {
        val fn = bracedBlock(photosSrc, "private fun handleExport(")
        assertTrue(
            "filesDir/photos-export is not cacheDir — nothing else can reclaim it",
            fn.contains("pruneShareDir(outDir)"),
        )
        assertOrder(fn, "pruneShareDir(outDir)", "copyStream(src, outFile)")
    }

    @Test
    fun `a11y screenshots prune before they write the png`() {
        val fn = bracedBlock(a11ySrc, "private fun uiScreenshot(")
        assertTrue(
            "plaintext screenshots accumulating with no delete path is the worst of the three",
            fn.contains("pruneShareDir(dir)"),
        )
        assertOrder(fn, "pruneShareDir(dir)", "FileOutputStream(file)")
    }

    @Test
    fun `pruneStaleAttachments delegates instead of keeping a second copy of the loop`() {
        val fn = bracedBlock(mutationSrc, "private fun pruneStaleAttachments(")
        assertTrue(
            "the attachment prune must go through the one shared TTL implementation",
            fn.contains("pruneShareDir(dir)"),
        )
        for (anchor in listOf("listFiles()", "lastModified()")) {
            assertFalse(
                "`$anchor` means the hand-rolled copy came back — two TTL copies can drift",
                fn.contains(anchor),
            )
        }
    }

    /** Reverse anchor: the delegated-to definition must keep existing. */
    @Test
    fun `the single pruner definition is still a 24h strict-less-than file filter`() {
        assertTrue(
            "pruneShareDir must stay internal (still `internal fun pruneShareDir(dir: File)`)",
            codeOnly(prunerSrc).contains("internal fun pruneShareDir(dir: File)"),
        )
        val fn = bracedBlock(codeOnly(prunerSrc), "internal fun pruneShareDir(")
        assertTrue("the 24h TTL literal moved", fn.contains("24L * 60 * 60 * 1000"))
        assertTrue("the cutoff comparison changed", fn.contains("lastModified() < cutoff"))
        assertTrue("the isFile guard vanished (a subdir would now be pruned)", fn.contains("f.isFile"))
    }

    // -- helpers --

    private fun aged(file: File, ageMs: Long): File {
        file.writeText("payload")
        assertTrue(
            "could not backdate ${file.name}",
            file.setLastModified(System.currentTimeMillis() - ageMs),
        )
        return file
    }

    private fun assertOrder(haystack: String, first: String, second: String) {
        val a = haystack.indexOf(first)
        val b = haystack.indexOf(second)
        assertTrue("`$first` not found", a >= 0)
        assertTrue("`$second` not found", b >= 0)
        assertTrue(
            "`$first` must precede `$second` so the freshly staged file can never be " +
                "the thing that gets pruned",
            a < b,
        )
    }

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private fun bracedBlock(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("no block opened after: $marker", open >= 0)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced block after: $marker")
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
