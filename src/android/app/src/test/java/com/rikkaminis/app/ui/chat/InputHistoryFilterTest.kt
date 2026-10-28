package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-C8/F-12] The "history input" sheet's row selection and preview.
 *
 * Two defects, both visible on the same screen:
 *  1. `role == "system"` rows (compact markers, model-switch dividers, injected
 *     notes — built by ChatViewModel:2323) were listed as if they were user
 *     input; they rendered as left-aligned bubbles that lead nowhere.
 *  2. The search and the preview read `content` only. An attachment-only prompt
 *     has `content == ""` — it rendered as "[...]" and typing the file name the
 *     user remembers matched nothing.
 *
 * Every expectation is a literal. Source:
 * `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/InputHistorySheet.kt`
 */
class InputHistoryFilterTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/InputHistorySheet.kt"

    // ── matching ───────────────────────────────────────────────────────────

    @Test
    fun `a blank query matches everything`() {
        assertTrue(inputHistoryMatches("", "", emptyList()))
        assertTrue(inputHistoryMatches("   ", "anything", listOf("a.png")))
    }

    @Test
    fun `content matches case-insensitively`() {
        assertTrue(inputHistoryMatches("deploy", "Please DEPLOY tonight", emptyList()))
    }

    @Test
    fun `an attachment name matches when the content does not`() {
        assertTrue(inputHistoryMatches("diagram", "", listOf("Diagram.PNG")))
    }

    @Test
    fun `a non-matching query fails`() {
        assertFalse(inputHistoryMatches("zzz", "hello", listOf("a.png")))
    }

    @Test
    fun `attachment-only rows are findable by a partial file name`() {
        assertTrue(inputHistoryMatches("rep", "", listOf("screenshot.png", "report-v3.pdf")))
    }

    // ── preview ────────────────────────────────────────────────────────────

    @Test
    fun `text wins over attachment names`() {
        assertEquals("look at this", inputHistoryPreview("  look at this \n", listOf("a.png")))
    }

    @Test
    fun `an attachment-only row shows its file names`() {
        assertEquals("a.png, b.pdf", inputHistoryPreview("", listOf("a.png", "b.pdf")))
    }

    @Test
    fun `a whitespace-only body falls back to the names`() {
        assertEquals("a.png", inputHistoryPreview("   \n ", listOf("a.png")))
    }

    @Test
    fun `blank names are dropped from the fallback`() {
        assertEquals("a.png", inputHistoryPreview("", listOf("  ", "a.png", "")))
    }

    @Test
    fun `a row with neither text nor names keeps the empty marker`() {
        assertEquals("[...]", inputHistoryPreview("", emptyList()))
        assertEquals("[...]", inputHistoryPreview("   ", listOf("")))
    }

    // ── source shape ───────────────────────────────────────────────────────

    @Test
    fun `the sheet drops system rows before filtering`() {
        val src = readRepoFile(sourcePath)
        assertTrue(src.contains("messages.filter { it.role != SYSTEM_ROLE }"))
        assertTrue(src.contains("internal const val SYSTEM_ROLE = \"system\""))
        assertTrue(src.contains("rows.filter { inputHistoryMatches(searchQuery, it.content, it.attachmentNames) }"))
        assertTrue(src.contains("text = inputHistoryPreview(msg.content, msg.attachmentNames),"))
        assertFalse("content-only search is the defect",
            src.contains("messages.filter { it.content.contains(searchQuery, ignoreCase = true) }"))
        assertFalse("the raw blank-content marker is the defect",
            src.contains("msg.content.trim().ifBlank { \"[...]\" }"))
    }

    @Test
    fun `the empty-state copy keys off the filtered rows`() {
        val src = readRepoFile(sourcePath)
        assertTrue(src.contains("if (rows.isEmpty()) R.string.input_history_empty"))
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir
     * (`src/android/app`), a sandbox JVM run can start anywhere under the
     * repo — no fixed relative path works for both, so walk up.
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
