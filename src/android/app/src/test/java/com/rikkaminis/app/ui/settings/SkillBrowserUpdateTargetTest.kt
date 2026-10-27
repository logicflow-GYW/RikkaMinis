package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [dv22-B6 §1042-B-07] deep-audit-1042 B-07 P1 — the skills-browser import
 * must judge duplication BEFORE the full-fat import, so an installed skill is
 * refreshed in place (message "updated", sibling files re-downloaded) instead
 * of duplicated under slugify(name) with a lying "imported" HUD.
 *
 * Two layers:
 *  1. the pure decision ([resolveUpdateTargetId]) — real JVM execution with
 *     hardcoded literal expectations (never derived from the code under
 *     test, so a mutation cannot masquerade as PASS);
 *  2. the wiring shape — source-shape assertions independently implemented
 *     (not shared with the scan gate), the same approach
 *     [SkillFileViewerContractTest] uses for the viewer save path.
 *
 * The pre-fix defect chain: importFromGitHub never received the resolved id,
 * so importFromContent landed every import under slugify(name); a local
 * record under a different id (renamed / custom id) got a DUPLICATE row, an
 * already-installed skill got replaced in place while the HUD claimed
 * "imported", and `if (skill != null)` was always true so the resolved-id
 * refresh path below it was unreachable dead code.
 */
class SkillBrowserUpdateTargetTest {

    // -- ① pure decision: what the browser asks before importing --

    @Test
    fun sameNameDifferentId_judgedAsUpdate_notDuplicate() {
        // A local record under a custom id ("custom-123") carries the same
        // display name as the incoming catalogue skill. The slug pass misses
        // (no record has id == slug), the name pass hits: the incoming skill
        // must refresh "custom-123" — never add a second row under the slug.
        assertEquals(
            "custom-123",
            resolveUpdateTargetId(
                listOf("custom-123" to "Semantic Memory"),
                slug = "semantic-memory",
                incomingName = "Semantic Memory",
            ),
        )
    }

    @Test
    fun slugHit_refreshesExistingRecord() {
        // The common case: installed id equals the incoming name slug.
        // The refresh must land on that record (id pass hits).
        assertEquals(
            "semantic-memory",
            resolveUpdateTargetId(
                listOf("semantic-memory" to "Semantic Memory (local rename)"),
                slug = "semantic-memory",
                incomingName = "Semantic Memory",
            ),
        )
    }

    @Test
    fun idMatchWinsOverEarlierNameMatch() {
        // [rv1014-E2] two-pass lookup: a record matching BY NAME comes first
        // in the list, a later record matches BY ID — the id pass must win,
        // otherwise the browser silently replaces the wrong skill.
        assertEquals(
            "semantic-memory",
            resolveUpdateTargetId(
                listOf(
                    "custom-123" to "Semantic Memory",
                    "semantic-memory" to "Some Other Name",
                ),
                slug = "semantic-memory",
                incomingName = "Semantic Memory",
            ),
        )
    }

    @Test
    fun brandNewSkill_isAnImport_null() {
        // Nothing matches by slug or name: a fresh import, not an update.
        assertNull(
            resolveUpdateTargetId(
                listOf("web-search" to "Web Search"),
                slug = "tierflow-key-harvest",
                incomingName = "TierFlow Key Harvest",
            ),
        )
    }

    @Test
    fun pureCjkName_blankSlug_onlyTheNamePassCanMatch() {
        // The pre-fix fallback's only live success case: a pure-CJK name
        // slugs to blank, so the id pass can never hit — only the name pass
        // can find the manual-id record to refresh.
        assertEquals(
            "semantic-memory",
            resolveUpdateTargetId(
                listOf("semantic-memory" to "语义记忆"),
                slug = "",
                incomingName = "语义记忆",
            ),
        )
    }

    // -- ② wiring shape: the decision must actually drive the import --

    private fun screenCode(): String {
        val src = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/ui/settings/MinisSkillsBrowserScreen.kt")
        return stripComments(src)
    }

    @Test
    fun screen_resolvesUpdateTargetBeforeImporting() {
        val code = screenCode()
        val resolveAt = code.indexOf("resolveUpdateTargetId(")
        val importAt = code.indexOf("skillRepository.importFromGitHub(")
        assertTrue("resolveUpdateTargetId call not found in the browser screen", resolveAt >= 0)
        assertTrue("importFromGitHub call not found in the browser screen", importAt >= 0)
        assertTrue(
            "the dedup (resolveUpdateTargetId) must run BEFORE the full-fat import — " +
                "after it, importFromContent has already landed a duplicate row under slugify(name)",
            resolveAt < importAt,
        )
    }

    @Test
    fun screen_importCallCarriesTheResolvedId() {
        val code = screenCode()
        // Derive the dedup variable name from the resolve statement so the
        // test pins the DECISION (the id travels to the repository), not one
        // spelling of it.
        val m = Regex("val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*resolveUpdateTargetId\\(").find(code)
        assertTrue("no `val <x> = resolveUpdateTargetId(` statement found", m != null)
        val dedupVar = m!!.groupValues[1]
        val importAt = code.indexOf("skillRepository.importFromGitHub(")
        assertTrue("importFromGitHub call not found", importAt >= 0)
        val argEnd = code.indexOf(')', importAt)
        assertTrue(argEnd > importAt)
        val importArgs = code.substring(importAt, argEnd)
        assertTrue(
            "the import call must pass 'preferredId = $dedupVar' — without the " +
                "passthrough the landing id falls back to slugify(name) and a " +
                "custom-id local record is duplicated instead of refreshed",
            "preferredId = $dedupVar" in importArgs,
        )
    }

    @Test
    fun screen_successMessageBranchesOnTheDedupResult() {
        val code = screenCode()
        val importAt = code.indexOf("skillRepository.importFromGitHub(")
        assertTrue("importFromGitHub call not found", importAt >= 0)
        // Scope to the success branch: from the import call to the fallback's
        // re-resolve (the fallback legitimately shows 'updated' too).
        val fallbackResolve = code.indexOf("resolveUpdateTargetId(", importAt)
        val segEnd = if (fallbackResolve >= 0) fallbackResolve else code.length
        val seg = code.substring(importAt, minOf(segEnd, importAt + 3000))
        val m = Regex("val\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*resolveUpdateTargetId\\(").find(code)
        val dedupVar = m!!.groupValues[1]
        assertTrue("if (skill != null) success branch not found after the import", "if (skill != null)" in seg)
        assertTrue(
            "the success HUD must branch: '$dedupVar != null' picks updated vs imported",
            "$dedupVar != null" in seg,
        )
        assertTrue("R.string.skills_browser_success_updated missing from the success branch",
            "R.string.skills_browser_success_updated" in seg)
        assertTrue("R.string.skills_browser_success_imported missing from the success branch",
            "R.string.skills_browser_success_imported" in seg)
    }

    @Test
    fun screen_rendererRebuildRestoresTheVisitedUrl() {
        // §1042-B-07 second half: the WebView factory re-runs on renderer
        // death (`key(rendererEpoch)`); the hardcoded root URL threw the user
        // back to the repository root mid-browse. The factory must load the
        // tracked currentUrl, with the root only as the blank fallback.
        val code = screenCode()
        assertTrue(
            "the factory must load currentUrl.ifBlank { \"https://github.com/OpenMinis/MinisSkills\" }",
            "loadUrl(currentUrl.ifBlank { \"https://github.com/OpenMinis/MinisSkills\" })" in code,
        )
        assertTrue(
            "the hardcoded root loadUrl(\"https://github.com/OpenMinis/MinisSkills\") must be gone",
            "loadUrl(\"https://github.com/OpenMinis/MinisSkills\")" !in code,
        )
    }

    // -- helpers (independently implemented, not shared with the scan gate) --

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 }
                    else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
