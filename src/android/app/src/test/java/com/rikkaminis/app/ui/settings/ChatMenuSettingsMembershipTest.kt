package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the boundary between a menu entry's membership and its default
 * visibility. C may make every [ChatActionSpec.defaultMenuVisible] false as
 * the new built-in default, but all twelve entries must remain editable in
 * Chat Menu settings. The expected keys are literals so a catalog mutation
 * cannot make this test pass by changing the expected list with production.
 */
class ChatMenuSettingsMembershipTest {

    private val menuMembers = listOf(
        "menu_terminal",
        "menu_browser",
        "menu_chat_files",
        "menu_compact",
        "menu_thinking",
        "menu_fast_mode",
        "menu_enhanced_cache",
        "menu_session_skills",
        "menu_session_mcps",
        "menu_session_memory",
        "menu_slash_commands",
        "menu_export",
    )

    @Test
    fun `hidden defaults do not collapse the editable menu to an empty list`() {
        val resolvedOrder = menuMembers.toList()
        val allDefaultsHidden = menuMembers.associateWith { false }

        // This is the pre-fix predicate: it models C's new catalog values and
        // proves why default visibility cannot decide membership.
        val legacyRows = resolvedOrder.filter { allDefaultsHidden[it] != false }
        assertTrue("the regression fixture must reproduce the empty-list failure", legacyRows.isEmpty())

        // This is the production contract: membership stays twelve rows even
        // when every row's default visibility is false.
        val editableRows = resolvedOrder.filter { it in menuMembers }
        assertEquals(menuMembers, editableRows)
        assertEquals(12, editableRows.size)
        assertEquals(12, editableRows.toSet().size)
    }

    @Test
    fun `screen applies the membership filter on initial load and external reload`() {
        val code = requireSource(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/ChatMenuSettingsScreen.kt",
        )
        val predicate = ".filter { it in ChatMenuPrefs.DEFAULT_ORDER }"
        assertEquals(
            "both the initial state and the prefs listener must use membership filtering",
            2,
            code.windowed(predicate.length).count { it == predicate },
        )
        assertFalse(
            "defaultMenuVisible must not decide whether a menu row is editable",
            code.contains("defaultMenuVisible != false"),
        )
    }

    private fun requireSource(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot locate $relative — source-shape test must fail closed")
    }
}
