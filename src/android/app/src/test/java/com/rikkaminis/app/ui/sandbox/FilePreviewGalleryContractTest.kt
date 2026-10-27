package com.rikkaminis.app.ui.sandbox

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image-preview loading contract at the FilePreviewScreen call site
 * ([deep-audit-1036 B-01]).
 *
 * The gallery producer used to start at an EMPTY list, which is
 * indistinguishable from "collected, nothing to show": the first frame
 * rendered ImageGalleryViewer with zero items, and its defensive
 * empty-items branch closed the preview before the off-main scan landed.
 * These pins read the real tree (Compose cannot compile in a sandbox JVM);
 * the behavioral arm lives in the probe `probe-b1/shape_probe.py`, which
 * re-implements the same checks without sharing this file's helpers.
 *
 * Source-shape assertions read the repo files (Gradle sets `user.dir` to the
 * module dir; a sandbox JVM run may start anywhere under the repo, so the
 * lookup walks up).
 */
class FilePreviewGalleryContractTest {

    // ── B-01: loading and loaded are distinguishable states ────────────

    @Test
    fun `image preview defers the viewer until the gallery scan lands`() {
        val branch = blockSlice(readRepoFile(FILE_PREVIEW), "if (item.isImageFile) {")
        assertTrue(
            "the gallery producer must start at null (loading), not an empty list:\n$branch",
            branch.contains("initialValue = null"),
        )
        assertFalse(
            "the gallery producer must not start at an empty list again:\n$branch",
            branch.contains("initialValue = emptyList"),
        )
        val viewerAt = branch.indexOf("ImageGalleryViewer(")
        val guardAt = branch.indexOf("== null")
        val placeholderAt = branch.indexOf("CircularProgressIndicator")
        assertTrue("the gallery branch lost its viewer call:\n$branch", viewerAt > 0)
        assertTrue(
            "the viewer must be gated by a null check on the loaded state:\n$branch",
            guardAt in 0 until viewerAt,
        )
        assertTrue(
            "the loading path must render a placeholder, not an empty viewer:\n$branch",
            placeholderAt in guardAt until viewerAt,
        )
        val returnAt = branch.indexOf("return", placeholderAt)
        assertTrue(
            "the loading path must return, not keep composing:\n$branch",
            returnAt in placeholderAt until viewerAt,
        )
    }

    @Test
    fun `the gallery scan still runs off the main thread`() {
        val branch = stripComments(blockSlice(readRepoFile(FILE_PREVIEW), "if (item.isImageFile) {"))
        val ioAt = branch.indexOf("withContext(Dispatchers.IO)")
        val collectAt = branch.indexOf("collectImageGallery(")
        assertTrue(
            "the scan left the off-main-thread contract:\n$branch",
            ioAt in 0 until collectAt,
        )
        assertFalse(
            "collectImageGallery must not run inside remember{} on the main thread again:\n$branch",
            Regex("remember\\s*\\{").containsMatchIn(branch),
        )
    }

    // ── B-01: the empty-items defensive branch is the viewer's own ─────

    @Test
    fun `the empty-items defensive branch is still the viewer's own`() {
        val src = readRepoFile(GALLERY)
        val emptyAt = src.indexOf("if (items.isEmpty())")
        assertTrue("the empty-items branch is gone:\n$src", emptyAt > 0)
        val dismissAt = src.indexOf("LaunchedEffect(Unit) { onDismiss() }")
        assertTrue(
            "the empty-items branch no longer dismisses (audit0917-b8 decision):\n$src",
            dismissAt > emptyAt,
        )
    }

    @Test
    fun `the viewer keeps exactly three call sites`() {
        // Dead literal: 1 definition + 3 call sites (FilePreviewScreen /
        // ChatScreen / ChatUserMessageUI). A fourth caller means this
        // contract review is due; a removed caller changes Chat-side semantics.
        val count = countInProductionSrc("ImageGalleryViewer(")
        assertTrue(
            "ImageGalleryViewer( occurrences in production src drifted (dead literal: 4), got $count",
            count == 4,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Source lines of the block starting at [marker], ending when its braces balance. */
    private fun blockSlice(src: String, marker: String): String {
        val lines = src.split("\n")
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        var depth = 0
        var end = start
        for (k in start until lines.size) {
            val s = stripStrings(lines[k])
            depth += s.count { it == '{' } - s.count { it == '}' }
            end = k
            if (depth == 0) break
        }
        assertTrue("unbalanced braces for $marker", depth == 0)
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Drops quoted spans so braces inside string literals do not unbalance the count. */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') { quote = c; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { nest--; i += 2 }
                    else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { nest++; i += 2 }
                    else i++
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Counts [needle] occurrences across every production .kt file. */
    private fun countInProductionSrc(needle: String): Int {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        var root: File? = null
        while (dir != null) {
            val f = File(dir, PROD_SRC)
            if (f.isDirectory) { root = f; break }
            dir = dir.parentFile
        }
        assertTrue(
            "production source tree not found from user.dir=${System.getProperty("user.dir")}",
            root != null,
        )
        var count = 0
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            count += f.readText().split(needle).size - 1
        }
        return count
    }

    private companion object {
        private const val FILE_PREVIEW =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
        private const val GALLERY =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/components/ImageGalleryViewer.kt"
        private const val PROD_SRC = "src/android/app/src/main/java"
    }
}
