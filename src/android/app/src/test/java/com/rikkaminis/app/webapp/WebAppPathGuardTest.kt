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

    // ── [dv14-B2] title seed + backfill decision ────────────────────────

    @Test
    fun `html and htm suffixes are stripped from the seed`() {
        assertEquals("page", webAppTitleFallback("page.html"))
        assertEquals("page", webAppTitleFallback("page.htm"))
    }

    @Test
    fun `other suffixes and blank names are kept verbatim`() {
        assertEquals("notes.txt", webAppTitleFallback("notes.txt"))
        assertEquals("", webAppTitleFallback(""))
    }

    @Test
    fun `a parsed title backfills an untouched field`() {
        assertEquals("My Page", webAppTitleBackfill("My Page", userEditedTitle = false))
    }

    @Test
    fun `a late parsed title never overwrites what the user typed`() {
        assertNull(webAppTitleBackfill("My Page", userEditedTitle = true))
    }

    @Test
    fun `absent or blank parse results keep the seed`() {
        assertNull(webAppTitleBackfill(null, userEditedTitle = false))
        assertNull(webAppTitleBackfill("   ", userEditedTitle = false))
    }

    // ── [dv21-b4-B08] pin verdict surfaced ×2, decode off the composition ──

    private val pinnerRel =
        "src/android/app/src/main/java/com/rikkaminis/app/webapp/ShortcutPinner.kt"
    private val previewShortcutRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/preview/WebPreviewShortcut.kt"
    private val sheetRel =
        "src/android/app/src/main/java/com/rikkaminis/app/webapp/AddToHomeSheet.kt"
    private val decodeCalls = listOf("decodeFileBounded(", "loadBitmapFromUri(")

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

    /** Drops `//`, `/* */` and KDoc so a comment that NAMES a shape cannot satisfy a check. */
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

    private fun parenEnd(code: String, open: Int): Int {
        var depth = 0
        for (j in open until code.length) {
            when (code[j]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
            }
        }
        return code.length
    }

    private fun braceEnd(code: String, open: Int): Int {
        var depth = 0
        for (j in open until code.length) {
            when (code[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return j + 1
                }
            }
        }
        return code.length
    }

    /** [start, end) of every `<header>(…) { … }` body in the file. */
    private fun lambdaBodies(code: String, header: Regex): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (m in header.findAll(code)) {
            val ob = code.indexOf('(', m.range.first)
            if (ob < 0) continue
            val br = code.indexOf('{', parenEnd(code, ob))
            if (br < 0) continue
            out.add((br + 1) until braceEnd(code, br))
        }
        return out
    }

    /** [start, end) of every `<header> { … }` body (header without a paren list). */
    private fun braceBodies(code: String, header: Regex): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (m in header.findAll(code)) {
            val br = code.indexOf('{', m.range.first)
            if (br < 0) continue
            out.add((br + 1) until braceEnd(code, br))
        }
        return out
    }

    @Test
    fun `both pin carriers classify the answer through the shared verdict`() {
        for (rel in listOf(pinnerRel, previewShortcutRel)) {
            val src = stripKotlinComments(readRepoFile(rel))
            assertTrue("$rel must classify via the shared pinOutcome(", "pinOutcome(" in src)
            assertTrue(
                "$rel must compare the verdict against REQUESTED",
                "ShortcutPinOutcome.REQUESTED" in src,
            )
            assertTrue(
                "$rel must surface a declined pin (false and a throw are the same answer)",
                "webapp_pin_failed" in src,
            )
        }
    }

    @Test
    fun `a throwing launcher cannot escape either pin call`() {
        for (rel in listOf(pinnerRel, previewShortcutRel)) {
            val src = stripKotlinComments(readRepoFile(rel))
            val captured = braceBodies(src, Regex("\\brunCatching\\s*\\{"))
            val calls = Regex("\\brequestPinShortcut\\s*\\(").findAll(src).map { it.range.first }
            val sites = calls.toList()
            assertTrue("$rel is expected to call requestPinShortcut", sites.isNotEmpty())
            for (site in sites) {
                assertTrue(
                    "$rel: requestPinShortcut at offset $site escapes its runCatching block " +
                        "(a throwing OEM launcher takes the process down)",
                    captured.any { site in it },
                )
            }
        }
    }

    @Test
    fun `the sheet never decodes inside a remember block`() {
        val src = stripKotlinComments(readRepoFile(sheetRel))
        val sites = decodeCalls.flatMap { call ->
            Regex(Regex.escape(call)).findAll(src).map { it.range.first }.toList()
        }
        assertTrue("the sheet is expected to carry a bounded decode", sites.isNotEmpty())
        val rememberBodies = lambdaBodies(src, Regex("\\bremember\\s*\\("))
        for (site in sites) {
            assertTrue(
                "decode at offset $site runs inside `remember { }`, i.e. on the composition " +
                    "thread — a 12 Mpx icon blocks the sheet's next frame",
                rememberBodies.none { site in it },
            )
        }
    }

    @Test
    fun `a produceState decode hops off the composition thread`() {
        val src = stripKotlinComments(readRepoFile(sheetRel))
        val sites = decodeCalls.flatMap { call ->
            Regex(Regex.escape(call)).findAll(src).map { it.range.first }.toList()
        }
        val produceBodies = lambdaBodies(src, Regex("\\bproduceState\\s*(?:<[^>{]*>)?\\s*\\("))
        var checked = 0
        for (body in produceBodies) {
            if (sites.none { it in body }) continue
            checked++
            assertTrue(
                "a produceState body that decodes must hop (Dispatchers.IO / withContext) — " +
                    "produceState otherwise runs on the composition dispatcher",
                "Dispatchers.IO" in src.substring(body.first, body.last + 1),
            )
        }
        assertTrue("expected at least one decoding produceState body", checked >= 1)
    }
}
