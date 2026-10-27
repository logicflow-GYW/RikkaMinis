package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1021-issue06] JVM shape probes for the SkillFileViewerScreen save
 * path. The screen is Compose + SkillRepository (not JVM-instantiable), so
 * the fix contract is pinned by source-shape assertions — the same approach
 * SkillRestorePolicyTest uses for its editor call sites (BUG-04 #2).
 *
 * Expected values are hardcoded literals (never derived from the code under
 * test) so a mutation of the production code cannot masquerade as PASS.
 */
class SkillFileViewerSaveShapeTest {

    private val screen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt")
    }

    private val segment: String by lazy {
        screen.substringAfter("fun SkillFileViewerScreen(")
    }

    // -- ① the audit-1015-b4-2 anchor survives the fix --

    @Test
    fun skillEditor_anchorCommentPreserved_withPinnedPreferredId() {
        val anchor = "SKILL.md edits go through importFromContent"
        assertEquals("anchor comment must appear exactly once", 1, screen.split(anchor).size - 1)
        val edit = screen
            .substringAfter(anchor)
            .substringBefore("} else {")
        assertTrue(
            "SKILL.md edit save must pin preferredId to the edited skill (BUG-04 #2)",
            edit.contains("preferredId = skillId"),
        )
    }

    // -- ② save failures surface a visible error (snackbar), not silence --

    @Test
    fun saveFailure_showsSnackbarInsteadOfSilentPop() {
        assertTrue(
            "screen must host a SnackbarHost for save errors",
            segment.contains("SnackbarHost("),
        )
        assertTrue(
            "failure branch must call snackbarHostState.showSnackbar(",
            segment.contains("snackbarHostState.showSnackbar("),
        )
        assertTrue(
            "SKILL.md format failures must surface skill_detail_error_invalid",
            segment.contains("skill_detail_error_invalid"),
        )
    }

    // -- ③ no unconditional pop: onBack() only behind the if (ok) guard --

    @Test
    fun saveExit_isGuardedByOkResult() {
        val assigns = segment.split("isEditing = false").size - 1
        assertEquals(
            "isEditing = false must occur exactly once in the screen (guarded exit only)",
            1,
            assigns,
        )
        val idx = segment.indexOf("isEditing = false")
        val before = segment.substring(maxOf(0, idx - 120), idx)
        assertTrue(
            "the single isEditing = false must sit behind an 'if (ok)' guard",
            before.contains("if (ok)"),
        )
    }

    // -- ④ the save result is consumed and runs off the main thread --

    @Test
    fun saveResult_consumedAndRunsOnIo() {
        val call = segment.substringAfter("skillRepository.importFromContent(")
        val close = call.indexOf(")")
        val after = call.substring(close + 1).trimStart()
        assertTrue(
            "importFromContent result must be consumed with ') != null'",
            after.startsWith("!= null"),
        )
        assertTrue(
            "save must run inside 'val ok = withContext(Dispatchers.IO)'",
            segment.contains("val ok = withContext(Dispatchers.IO)"),
        )
    }

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
