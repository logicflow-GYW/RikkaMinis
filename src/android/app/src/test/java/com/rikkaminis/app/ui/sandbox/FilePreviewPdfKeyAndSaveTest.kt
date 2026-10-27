package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1047 B-01 / B-03] The two shapes in FilePreviewScreen.kt the
 * scan gate also covers, pinned from the JVM test side:
 *
 *  * B-01 — the SAF save-as callback used
 *    `openOutputStream(uri)?.use { … }` and then returned `true`. A null stream
 *    (a provider that refuses the write, or whose process was reaped) skipped
 *    the copy and the user still got "saved".
 *  * B-03 — `PdfPreview`'s page `produceState` was keyed on `idx` alone while
 *    `error` / `pageCount` / `renderer` were keyed on `item.file`. Compose
 *    reuses the slot across a file switch, so the previous document's page
 *    stayed on screen.
 *
 * These are SOURCE ANCHOR assertions (the same class of instrument as
 * `BoundedDecodeCallSitesTest`): the production file is Compose/Android code
 * that cannot be compiled off-device, so the gate carries the runtime half and
 * these tests carry "the shipped text is the fixed text", each with the
 * pre-fix shape restored as a negative control. Expectations are literals.
 *
 * The behavioural half of B-01 (both halves of the write must have happened)
 * lives in `SaveOutcomeTest`, which runs the real production function.
 */
class FilePreviewPdfKeyAndSaveTest {

    private companion object {
        const val SCREEN = "app/src/main/java/com/rikkaminis/app/ui/sandbox/FilePreviewScreen.kt"
    }

    @Test
    fun `PdfPreview keys its page producer on the file as well as the index`() {
        val src = readRepoFile(SCREEN)
        assertPdfKeyShape(src)

        // Negative control: the shipped pre-fix line, restored verbatim.
        assertProbeRejects(
            src.replace(
                "                        key1 = idx,\n                        key2 = item.file,\n",
                "                        key1 = idx,\n",
            ),
            ::assertPdfKeyShape,
        )
        // A bare `key1 = idx` on one line is the same defect, so it must be
        // rejected too (the gate keys off the argument list, not the layout).
        assertProbeRejects(
            src.replace(
                "                        initialValue = renderer.cached(idx),\n",
                "                        initialValue = null,\n",
            ).replace("                        key2 = item.file,\n", ""),
            ::assertPdfKeyShape,
        )
    }

    @Test
    fun `the page producer still reuses the renderer LRU before rendering`() {
        // The key fix must not become a re-render fix: `initialValue` has to
        // keep reading the cache, and the producer keeps its null guard.
        val src = readRepoFile(SCREEN)
        assertTrue(
            "the initial value must come from the renderer cache",
            src.contains("initialValue = renderer.cached(idx)"),
        )
        assertTrue(
            "a warm page must not be rendered again",
            src.contains("if (value == null) {"),
        )
        assertTrue(
            "rendering stays off the main thread",
            src.contains("value = withContext(Dispatchers.IO) { renderer.render(idx) }"),
        )
    }

    @Test
    fun `PdfPreview keeps its top level state keyed on the file`() {
        val src = readRepoFile(SCREEN)
        assertTrue(
            "error must be keyed on the file",
            src.contains("var error by remember(item.file) { mutableStateOf<String?>(null) }"),
        )
        assertTrue(
            "pageCount must be keyed on the file",
            src.contains("var pageCount by remember(item.file) { mutableStateOf<Int?>(null) }"),
        )
        assertTrue(
            "the renderer must be keyed on the file",
            src.contains("val renderer = remember(item.file) { PdfPageRenderer() }"),
        )
    }

    @Test
    fun `the SAF save-as callback refuses a null output stream`() {
        val src = readRepoFile(SCREEN)
        assertSaveAsShape(src)

        // Negative control: the pre-fix shape — a `?.use` chain and then `true`
        // whatever happened inside it.
        assertProbeRejects(
            src.replace(
                "                } ?: throw java.io.IOException(\"openOutputStream returned null\")\n",
                "\n",
            ),
            ::assertSaveAsShape,
        )
    }

    @Test
    fun `the SAF save-as callback keeps its IO hop and both toast branches`() {
        val src = readRepoFile(SCREEN)
        assertTrue(
            "the copy must stay off the Main thread",
            src.contains("scope.launch(Dispatchers.IO) {"),
        )
        assertTrue(
            "a failed save must keep its own toast, not the success one",
            src.contains("context.getString(if (ok) R.string.file_saved_toast else R.string.file_save_failed_toast)"),
        )
        assertTrue(
            "the copy is a stream copy, not a whole-file read",
            src.contains("item.file.inputStream().use { it.copyTo(out) }"),
        )
    }

    private fun assertPdfKeyShape(src: String) {
        val body = pdfPreviewBody(src)
        val call = produceStateCall(body)
            ?: throw AssertionError("PdfPreview must hold a page produceState")
        // `initialValue = renderer.cached(idx)` names the STARTING value, not a
        // key — the pre-fix code had it too. Only the key arguments count, or
        // the probe would accept the defect (this is exactly how the gate's
        // first draft looked green on the shipped file).
        val keys = call.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("initialValue") }
        assertTrue(
            "the page producer must be keyed on the file it renders (key set = $keys)",
            keys.any { it.contains("item.file") || it.contains("renderer") },
        )
        assertTrue(
            "the page index must stay in the key set too (key set = $keys)",
            keys.any { it.contains("idx") },
        )
    }

    private fun assertSaveAsShape(src: String) {
        // The `?.use` chain is still the right shape — the fix is that its null
        // result is no longer discarded. What must be there is the elvis exit
        // that turns "no stream" into the failure branch.
        assertTrue(
            "a null output stream must exit into the failure branch",
            Regex(
                """openOutputStream\(uri\)\?\.use\s*\{[\s\S]{0,400}?\}\s*\?:\s*throw\s+java\.io\.IOException"""
            ).containsMatchIn(src),
        )
        assertTrue(
            "the thrown failure must reach the same catch that reports it",
            src.contains("AppLogger.warning(\"FilePreview\", \"Save-As failed: ${'$'}{e.message}\")"),
        )
    }

    /** The `PdfPreview(item: FileItem)` body, cut at the next top-level `private fun`. */
    private fun pdfPreviewBody(src: String): String {
        val start = src.indexOf("private fun PdfPreview(item: FileItem) {")
        assertTrue("PdfPreview must exist", start >= 0)
        val rest = src.indexOf("\nprivate fun ", start + 1)
        val end = if (rest < 0) src.length else rest
        return src.substring(start, end)
    }

    /** The argument list of the first `produceState(` call in [body], or null. */
    private fun produceStateCall(body: String): String? {
        val at = body.indexOf("produceState")
        if (at < 0) return null
        var i = body.indexOf("(", at)
        while (i >= 0) {
            var depth = 0
            var j = i
            while (j < body.length) {
                when (body[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return body.substring(i + 1, j)
                    }
                }
                j++
            }
            i = body.indexOf("(", i + 1)
        }
        return null
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

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: "")
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
