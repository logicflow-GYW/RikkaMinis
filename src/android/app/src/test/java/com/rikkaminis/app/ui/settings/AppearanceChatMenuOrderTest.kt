package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-order contract for the single Chat Menu entry point in Appearance. */
class AppearanceChatMenuOrderTest {
    @Test
    fun `Chat Menu is third after Theme and Glass and before Launch`() {
        val source = findRepoFile("AppearanceScreen.kt").readText()
        val sections = listOf(
            "appearance_section_theme",
            "appearance_section_glass",
            "appearance_section_chat_menu",
            "appearance_section_launch",
            "appearance_section_return_key",
            "appearance_section_keep_awake",
            "appearance_section_tool_preview",
            "appearance_section_deep_thinking",
            "appearance_section_tool_steps",
            "appearance_section_thinking_header",
            "appearance_section_chat_title",
            "appearance_section_font_size",
            "appearance_section_language",
        )
        val positions = sections.map { name ->
            source.indexOf("header = stringResource(R.string.$name)").also {
                assertTrue("missing section $name", it >= 0)
            }
        }
        assertEquals("all expected section headers occur once", sections.size,
            sections.sumOf { name -> Regex("header = stringResource\\(R\\.string\\.$name\\)").findAll(source).count() })
        assertEquals("all unaffected sections retain relative order", positions.sorted(), positions)
        assertTrue("menu must be after Glass", positions[2] > positions[1])
        assertTrue("menu must be before Launch", positions[2] < positions[3])
        assertEquals("only one menu entry point remains", 1,
            Regex("onClick = onChatMenuClick").findAll(source).count())
    }

    private fun findRepoFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val hit = File(dir, "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/$name")
            if (hit.isFile) return hit
            dir = dir.parentFile
        }
        error("$name not found from user.dir=${System.getProperty("user.dir")}")
    }
}
