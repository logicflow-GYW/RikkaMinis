package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [meta-audit-1028 B06 F-13] — three defects on the Skills settings screens,
 * all of them invisible to the Kotlin compiler and to the existing gates:
 *
 *  1. **Local-file import had no busy gate.** The `GetContent` callback read up
 *     to `MAX_SKILL_IMPORT_BYTES` (and possibly unzipped it) on IO while the
 *     sheet stayed interactive; a second tap on "Choose File" started a second
 *     import that raced the first, and the loser surfaced the *content* error
 *     because it hit the already-inserted id. The URL / paste tabs already
 *     disable their Submit button on `isLoading` — the file tab never set it.
 *  2. **Four hardcoded English error strings** ("Failed to read file",
 *     "Invalid skill archive — …", "Invalid SKILL.md content", plus
 *     `"Error: ${e.message}"` on the two typed tabs) rendered English in all
 *     seven locales. They are resource lookups now.
 *  3. **The rename dialog dropped `skillRepository.update`'s Boolean** and
 *     closed unconditionally, so a `false` (the id is no longer in the
 *     repository) looked exactly like a successful rename that did nothing.
 *
 * The screens are Compose + repository (not JVM-instantiable), so all three
 * contracts are pinned by source-shape assertions — the approach this repo
 * already uses (`SkillFileViewerSaveShapeTest`, `SkillsMcpReloadOffMainTest`).
 * Expected values are hardcoded literals, never derived from the code under
 * test.
 */
class SkillsImportRenameGuardTest {

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt")
    }

    /** Code view: comment lines dropped, inline `//` tails cut. */
    private fun code(): String = strip(screen)

    /** [start, end) slice of the RAW source, then comment-stripped. */
    private fun region(start: String, end: String): String {
        val i = screen.indexOf(start)
        assertTrue("anchor '$start' lost", i >= 0)
        val j = screen.indexOf(end, i)
        assertTrue("anchor '$end' lost after '$start'", j > i)
        return strip(screen.substring(i, j))
    }

    private fun strip(text: String): String = text.lines()
        .filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .map { it.substringBefore("//") }
        .joinToString("\n")

    private val sheet: String by lazy {
        region("private fun SkillImportSheet(", "// ─── Skill Detail Screen")
    }
    private val renameDialog: String by lazy {
        region("if (showEditNameDialog) {", "/** A grouped card section")
    }

    // ── ① the local-file import is single-flight ────────────────────────

    @Test
    fun fileTabRejectsASecondPickWhileImporting() {
        assertTrue(
            "the GetContent callback must bail out while an import is in flight; without " +
                "this a second pick races the first and reports 'invalid SKILL.md'",
            sheet.contains("if (isLoading) return@rememberLauncherForActivityResult"),
        )
    }

    @Test
    fun fileTabSetsAndClearsTheBusyFlag() {
        val c = code()
        val gate = c.indexOf("if (isLoading) return@rememberLauncherForActivityResult")
        val set = c.indexOf("isLoading = true", gate)
        val io = c.indexOf("withContext(Dispatchers.IO)", gate)
        val clear = c.indexOf("isLoading = false", gate)
        assertTrue("busy flag must be raised", set > gate)
        assertTrue("busy flag must be raised before the IO work", set < io)
        assertTrue("busy flag must be cleared on the Main side of the launch", clear > io)
    }

    @Test
    fun chooseFileButtonIsDisabledWhileImporting() {
        assertTrue(
            "the button that launches the picker must reflect the busy flag",
            sheet.contains("enabled = !isLoading,"),
        )
    }

    // ── ② no hardcoded English in the error paths ───────────────────────

    @Test
    fun noEnglishErrorLiteralsRemain() {
        val c = code()
        // literals that shipped pre-fix (each rendered as-is in every locale)
        val banned = listOf(
            "Failed to read file",
            "Invalid skill archive",
            "Invalid SKILL.md content",
            "errorText = \"",
        )
        banned.forEach { literal ->
            assertFalse("hardcoded English error text '$literal' is back", c.contains(literal))
        }
    }

    @Test
    fun errorPathsGoThroughResources() {
        val c = code()
        assertTrue(c.contains("R.string.skill_file_error_read"))
        assertTrue(c.contains("R.string.skill_file_error_archive"))
        // invalid content reuses the sheet's existing wording (paste tab)
        assertTrue(c.contains("R.string.skill_import_error_format"))
        // the dynamic detail keeps its frame localized
        assertTrue(c.contains("R.string.skill_file_error_detail"))
    }

    // ── ③ the rename consumes update()'s result ─────────────────────────

    @Test
    fun renameConsumesTheUpdateResult() {
        assertTrue(
            "the write must be bound and judged, not fired and forgotten",
            renameDialog.contains("val ok = withContext(Dispatchers.IO)"),
        )
        assertTrue(renameDialog.contains("skillRepository.update(skill.id, name = trimmed)"))
        assertTrue(
            "closing the dialog must be conditional on the write succeeding",
            renameDialog.contains("if (ok) showEditNameDialog = false"),
        )
        assertTrue(
            "a failed rename must surface the reason and stay open",
            renameDialog.contains("renameError = context.getString(") &&
                renameDialog.contains("R.string.skill_detail_error_update_failed"),
        )
    }

    @Test
    fun renameIsNotDismissedUnconditionally() {
        // The pre-fix shape was a bare `showEditNameDialog = false` on its own
        // line right after the launch — it closed the dialog before the write
        // was judged. Every dismissal must now be an explicit affordance or the
        // success branch, so a bare statement is a regression.
        val dismissals = renameDialog.lines()
            .filter { it.contains("showEditNameDialog = false") }
            .map { it.trim().trimEnd(',') }
        assertEquals("three explicit dismissal sites", 3, dismissals.size)
        val allowed = listOf(
            "onDismissRequest = { showEditNameDialog = false }",
            "if (ok) showEditNameDialog = false",
            "MinisTextButton(onClick = { showEditNameDialog = false })",
        )
        dismissals.forEach { line ->
            assertTrue(
                "dismissal is not gated by the write result: '$line'",
                allowed.any { line.startsWith(it) },
            )
        }
    }

    @Test
    fun renameSaveIsBusyGated() {
        assertTrue(
            "the write rewrites SKILL.md; a double tap must not fire two of them",
            renameDialog.contains("isRenaming"),
        )
        assertTrue(renameDialog.contains("!isRenaming"))
        assertTrue(renameDialog.contains("enabled = canSave"))
    }

    // ── ④ every locale actually carries the new keys ────────────────────

    @Test
    fun newKeysExistInEveryLocaleFile() {
        val res = File(repoRoot(), "app/src/main/res")
        val dirs = res.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.filter { File(it, "strings.xml").isFile }
            ?.map { it.name }
            ?.sorted()
            ?: fail("no values* dirs under ${res.path}")
        assertEquals(
            "the seven shipped locale files (values + 6 translations)",
            listOf("values", "values-de", "values-ja", "values-ko", "values-ru", "values-zh", "values-zh-rTW"),
            dirs,
        )
        val keys = listOf(
            "skill_file_error_read",
            "skill_file_error_archive",
            "skill_file_error_detail",
        )
        dirs.forEach { dir ->
            val text = File(res, "$dir/strings.xml").readText()
            keys.forEach { key ->
                assertTrue("$dir/strings.xml is missing $key", text.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun translationsAreNotEnglishFallbacks() {
        val res = File(repoRoot(), "app/src/main/res")
        val english = File(res, "values/strings.xml").readText()
        listOf("values-de", "values-ja", "values-ko", "values-ru", "values-zh", "values-zh-rTW")
            .forEach { dir ->
                val text = File(res, "$dir/strings.xml").readText()
                listOf("skill_file_error_read", "skill_file_error_archive", "skill_file_error_detail")
                    .forEach { key ->
                        val en = valueOf(english, key)
                        val tr = valueOf(text, key)
                        assertTrue("$dir/$key is empty", tr.isNotBlank())
                        assertTrue(
                            "$dir/$key is still the English text ('$en') — a copied key is a " +
                                "silent English fallback in that locale",
                            tr != en,
                        )
                    }
            }
    }

    private fun valueOf(xml: String, key: String): String {
        val m = Regex("<string name=\"$key\">(.*?)</string>").find(xml)
        assertTrue("key $key missing", m != null)
        return m!!.groupValues[1]
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)

    private fun repoRoot(): File {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: File? = File(cwd).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            if (File(candidate, "app/src/main/res").isDirectory) return candidate
            dir = candidate.parentFile
        }
        throw java.io.FileNotFoundException("app/src/main/res not found from user.dir=$cwd")
    }

    private fun readRepoFile(relative: String): String {
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: File? = File(cwd).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val f = File(candidate, relative)
            if (f.isFile) return f.readText()
            dir = candidate.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=$cwd")
    }
}
