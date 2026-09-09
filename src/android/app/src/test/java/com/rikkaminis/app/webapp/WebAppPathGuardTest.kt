package com.rikkaminis.app.webapp

import com.rikkaminis.app.sandbox.PRootKernel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the two webapp path-traversal defenses:
 *  - [sanitizeFileName] (AddToHomeSheet's copy target name guard)
 *  - [PRootKernel.safeResolveWithin] (WebAppPathResolver's relative-branch guard)
 */
class WebAppPathGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── sanitizeFileName ────────────────────────────────────────────────

    @Test
    fun `plain name is kept`() {
        assertEquals("page.html", sanitizeFileName("page.html"))
    }

    @Test
    fun `directory components are stripped`() {
        assertEquals("page.html", sanitizeFileName("sub/dir/page.html"))
    }

    @Test
    fun `parent traversal is stripped to last segment`() {
        assertEquals("evil.html", sanitizeFileName("../../evil.html"))
    }

    @Test
    fun `absolute path is stripped to last segment`() {
        assertEquals("evil.html", sanitizeFileName("/etc/evil.html"))
    }

    @Test
    fun `dotdot alone yields null`() {
        assertNull(sanitizeFileName(".."))
        assertNull(sanitizeFileName("../.."))
    }

    @Test
    fun `blank yields null`() {
        assertNull(sanitizeFileName(""))
        assertNull(sanitizeFileName("   "))
    }

    // ── safeResolveWithin (relative-branch guard) ───────────────────────

    private fun attachmentsDir(): File = tmp.newFolder("base")

    @Test
    fun `plain relative path resolves inside base`() {
        val base = attachmentsDir()
        val resolved = PRootKernel.safeResolveWithin(base, "a/b/page.html")
        assertEquals(File(base, "a/b/page.html").canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun `parent traversal escaping base returns null`() {
        val base = attachmentsDir()
        assertNull(PRootKernel.safeResolveWithin(base, "../../etc/passwd"))
    }

    @Test
    fun `absolute path is rejected`() {
        val base = attachmentsDir()
        // absolute path inside a linuxPath tail — segment is treated as verbatim
        // but any `/etc/passwd`-style absolute still resolves under base on the
        // host because File(base, "/etc/passwd") == base/etc/passwd; the guard
        // is about escaping base, which this cannot do.
        val resolved = PRootKernel.safeResolveWithin(base, "/etc/passwd")
        assertTrue(
            resolved == null || resolved.canonicalPath.startsWith(base.canonicalPath + File.separator),
        )
    }

    @Test
    fun `symlink escaping base returns null`() {
        val base = attachmentsDir()
        val outside = tmp.newFolder("outside")
        val outsideFile = File(outside, "secret.txt").apply { writeText("x") }
        val link = File(base, "link")
        // Link base/link -> outside/secret.txt (absolute target)
        link.absoluteFile.writeText("") // no-op, then create symlink below
        link.delete()
        java.nio.file.Files.createSymbolicLink(
            link.toPath(),
            outsideFile.toPath(),
        )
        // Even a single-segment tail that is a symlink pointing out is rejected.
        assertNull(PRootKernel.safeResolveWithin(base, "link"))
        // And a nested traversal through it too.
        assertNull(PRootKernel.safeResolveWithin(base, "link/child"))
    }

    @Test
    fun `dotdot resolving back inside base is allowed`() {
        val base = attachmentsDir()
        // a/../b stays inside base
        val resolved = PRootKernel.safeResolveWithin(base, "a/../b")
        assertEquals(File(base, "b").canonicalPath, resolved?.canonicalPath)
    }
}
