package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** [FULLSCAN-1119 R2 F-14] Screenshot files must never remain on shared storage. */
class A11yScreenshotStorageTest {
    @Test
    fun `screenshot directory is beneath app-private cache`() {
        val cache = Files.createTempDirectory("a11y-private-cache").toFile()
        val output = a11yScreenshotDirectory(cache)
        assertEquals(File(cache, "a11y_screenshots").canonicalFile, output.canonicalFile)
        assertTrue(output.path.startsWith(cache.canonicalPath + File.separator))
        assertFalse(output.path.contains("Android/data"))
    }

    @Test
    fun `legacy cleanup deletes links but preserves their external targets`() {
        val root = Files.createTempDirectory("a11y-legacy-cleanup").toFile()
        val external = File(root, "outside").apply { mkdirs() }
        val secret = File(external, "secret.png").apply { writeText("private") }
        val legacy = File(root, "legacy").apply { mkdirs() }
        val link = File(legacy, "outside")
        try {
            Files.createSymbolicLink(link.toPath(), external.toPath())
        } catch (_: UnsupportedOperationException) {
            return
        }
        deleteA11yScreenshotTreeNoFollow(legacy)
        assertFalse("legacy screenshot directory should be removed", legacy.exists())
        assertTrue("cleanup must not follow the symlink", secret.exists())
        assertEquals("private", secret.readText())
    }

    @Test
    fun `handler writes privately and cleans old shared location at initialization`() {
        val src = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/AccessibilityOffloadHandler.kt")
        val init = memberSlice(src, "init {")
        val shot = memberSlice(src, "private fun uiScreenshot(")
        assertTrue("startup must remove the legacy external screenshot tree", init.contains("getExternalFilesDir(\"a11y_screenshots\")"))
        assertTrue("startup legacy cleanup must not follow symbolic links", init.contains("deleteA11yScreenshotTreeNoFollow"))
        assertTrue("new screenshots must use cacheDir", shot.contains("a11yScreenshotDirectory(context.cacheDir)"))
        assertFalse("screenshot write path must not use external storage", shot.contains("context.getExternalFilesDir"))
        assertTrue("existing 24h cleanup must cover new private directory", shot.contains("pruneShareDir(dir)"))
        assertTrue("session-private screenshot path must be readable from the guest", shot.contains("resolvePathForSession(sessionId, guestDir, context)"))
        assertTrue("no-session host paths must explain retrieval", shot.contains("Use --inline for retrieval."))
        assertTrue("help text must describe private path, not externalFilesDir", src.contains("private session attachments"))
        assertFalse("stale external path description must be removed", src.contains("<externalFilesDir>/a11y_screenshots"))
    }

    private fun memberSlice(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("missing source anchor $marker", start >= 0)
        val open = src.indexOf('{', start)
        assertTrue("missing block for $marker", open >= 0)
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in open until src.length) {
            val c = src[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            if (c == '"') quoted = true
            else if (c == '{') depth++
            else if (c == '}' && --depth == 0) return src.substring(start, i + 1)
        }
        throw AssertionError("unclosed block $marker")
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(relative)
    }
}
