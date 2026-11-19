package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FULLSCAN-1119 F-17] `PdfPageRenderer.open` opened a `ParcelFileDescriptor`
 * and then constructed `PdfRenderer(pfd)`:
 *
 * ```
 * val pfd = ParcelFileDescriptor.open(file, MODE_READ_ONLY)
 * val pdf = PdfRenderer(pfd)          // ← throws on non-PDF / encrypted files
 * descriptor = pfd                    // ← never reached on failure
 * ```
 *
 * The constructor is the ONLY step that can fail after the fd exists, and the
 * descriptor was stored after it, so the failed path owned the fd for nobody:
 * one descriptor leaked per attempt, and repeatedly opening an unreadable PDF
 * exhausts the process table. This is the missing arm of the earlier
 * `PdfPageRenderer` audit (D10 F-02 covered the close paths, not this one).
 *
 * Source anchoring, not runtime: `PdfPageRenderer` is a private class inside a
 * Compose screen (`androidx.compose`, `android.graphics.pdf`) and cannot be
 * instantiated off-device — the same instrument the existing
 * `FilePreviewPdfKeyAndSaveTest` uses for this file. Every assertion carries a
 * negative control that restores the pre-fix shape.
 */
class FilePreviewPdfOpenFailureTest {

    private companion object {
        const val SCREEN = "app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
    }

    private fun assertOpensBehindOwnershipGuard(src: String) {
        assertTrue(
            "the renderer must be constructed under the close-on-failure guard " +
                "(the fd must not be owned by nobody when the constructor throws)",
            src.contains("closeOnOpenFailure(pfd) { PdfRenderer(pfd) }"),
        )
        assertFalse(
            "the unguarded construction is the leak",
            src.contains("val pdf = PdfRenderer(pfd)"),
        )
    }

    @Test
    fun `PdfRenderer is constructed behind the close-on-failure guard`() {
        val src = readRepoFile(SCREEN)
        assertOpensBehindOwnershipGuard(src)

        // Negative control: the shipped pre-fix line, restored verbatim.
        assertProbeRejects(
            src.replace("closeOnOpenFailure(pfd) { PdfRenderer(pfd) }", "PdfRenderer(pfd)"),
            ::assertOpensBehindOwnershipGuard,
        )
    }

    @Test
    fun `the guard closes the resource and rethrows the original failure`() {
        val src = readRepoFile(SCREEN)
        val body = guardBody(src)

        assertTrue("closes the resource: $body", body.contains("runCatching { resource.close() }"))
        assertTrue("propagates the original failure: $body", body.contains("throw t"))
        assertTrue("wraps the open step: $body", body.contains("open()"))

        // The close must be best-effort: a throwing close must not replace the
        // open failure (the reported error would change meaning).
        assertProbeRejects(
            body.replace("runCatching { resource.close() }", "resource.close()"),
            { probe: String -> assertTrue(probe.contains("runCatching { resource.close() }")) },
        )
        // And a swallowed failure (no rethrow) must be rejected too.
        assertProbeRejects(
            body.replace("throw t", "return open()"),
            { probe: String -> assertTrue(probe.contains("throw t")) },
        )
    }

    @Test
    fun `the success path still stores descriptor and renderer together`() {
        val src = readRepoFile(SCREEN)
        val open = bodyOf(src, "suspend fun open(file: File, width: Int): Int")

        // Ownership note for the runtime: PdfRenderer.close() closes the
        // descriptor it was built from, and closeLocked() closes both —
        // storing them together keeps the existing close timing.
        assertTrue("descriptor is still stored", open.contains("descriptor = pfd"))
        assertTrue("renderer is still stored", open.contains("renderer = pdf"))
        assertTrue("page count is still published", open.contains("pageCount = minOf(pdf.pageCount, MAX_PREVIEW_PAGES)"))
        assertFalse(
            "no early close on the success path: the descriptor must outlive the call",
            open.contains("pfd.close()"),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }

    /** Fail the probe when [mutated] still satisfies the assertion. */
    private fun assertProbeRejects(mutated: String, assertion: (String) -> Unit) {
        val satisfied = try {
            assertion(mutated)
            true
        } catch (_: AssertionError) {
            false
        }
        assertFalse("negative control was NOT rejected — the assertion is blind to it", satisfied)
    }

    /** Braces-matched body of the declaration that starts with [decl]. */
    private fun guardBody(src: String): String {
        // `closeOnOpenFailure` is an expression-bodied inline fun whose body is
        // a `try/catch`, so the FIRST `{` after its header belongs to the
        // `try`. Slice to the next top-level declaration instead of
        // brace-matching from that brace.
        val start = src.indexOf("internal inline fun <T : Any> closeOnOpenFailure(")
        assertTrue("the guard must exist", start >= 0)
        val end = src.indexOf("private class PdfPageRenderer", start)
        assertTrue("PdfPageRenderer must follow the guard", end > start)
        return src.substring(start, end)
    }

    /** Braces-matched body of the declaration that starts with [decl]. */
    private fun bodyOf(src: String, decl: String): String {
        val at = src.indexOf(decl)
        assertTrue("declaration not found: $decl", at >= 0)
        val bodyStart = src.indexOf('{', at)
        assertTrue("no body for: $decl", bodyStart > at)
        var depth = 0
        var i = bodyStart
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(bodyStart, i + 1)
                }
                '"' -> {
                    // Skip string literals so a brace inside them cannot move
                    // the depth. Raw/escaped strings are not used in these two.
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        throw AssertionError("unbalanced body for: $decl")
    }
}
