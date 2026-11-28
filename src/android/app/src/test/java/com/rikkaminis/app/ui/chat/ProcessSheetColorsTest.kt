package com.rikkaminis.app.ui.chat

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Production source contracts only; not a Compose rendering or low-brightness visual test. */
class ProcessSheetColorsTest {
    private fun app(): File {
        val explicit = System.getProperty("minis.test.sourceRoot")
        var dir: File? = explicit?.let(::File) ?: File(System.getProperty("user.dir"))
        while (dir != null) {
            for (prefix in listOf("src/android/app", "app")) {
                val candidate = File(dir, "$prefix/src/main/java/com/rikkaminis/app")
                if (File(candidate, "ui/chat/TopChatSheet.kt").isFile) return candidate
            }
            if (explicit != null) break
            dir = dir.parentFile
        }
        error("Missing production app sources")
    }

    private fun source(path: String): String = File(app(), "$path.kt").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)^\\s*//[^\\n]*"), "")

    @Test fun parent_uses_app_dark_low_and_original_light_background() {
        val top = source("ui/chat/TopChatSheet")
        assertTrue(Regex("""val panelColor = if \(ChatColors\.isDark\) MaterialTheme\.colorScheme\.surfaceContainerLow else ChatColors\.background""").containsMatchIn(top))
        assertTrue(top.contains("color = panelColor,"))
        assertFalse(top.contains("isSystemInDarkTheme"))
    }

    @Test fun thinking_alone_uses_app_dark_high_and_original_light_background() {
        val process = source("ui/chat/ChatProcessUI")
        val start = process.indexOf("is ChatProcessPage.Thinking -> StandardChatSheet(")
        assertTrue(start >= 0)
        val call = process.substring(start).substringBefore(") {")
        assertTrue(call.contains("containerColor = if (ChatColors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else ChatColors.background,"))
        assertTrue(call.contains("title = null,"))
        assertTrue(call.contains("showClose = false,"))
        assertEquals(1, Regex("\\bStandardChatSheet\\(").findAll(process).count())
        assertFalse(process.contains("isSystemInDarkTheme"))
    }

    @Test fun standard_preserves_default_and_trailing_content_parameter() {
        val sheet = source("ui/chat/StandardChatSheet")
        assertTrue(Regex("""containerColor: Color = ChatColors\.background,\s*content: @Composable \(\) -> Unit,""").containsMatchIn(sheet))
        assertTrue(sheet.contains("containerColor = containerColor,"))
        assertTrue(sheet.contains("heightFraction: Float? = 0.9f,"))
    }

    @Test fun theme_and_palette_share_the_app_theme_selection_and_existing_ramp() {
        val theme = source("ui/theme/Theme")
        val dark = theme.substringAfter("private val DarkColorScheme = darkColorScheme(")
            .substringBefore("\n)")
        assertTrue(dark.contains("surfaceContainerLow = NeutralDarkGroupedCard,"))
        assertTrue(dark.contains("surfaceContainerHigh = NeutralDarkGroupedCardElevated,"))
        assertTrue(theme.contains("NeutralDarkGroupedCard = Color(0xFF1C1C1E)"))
        assertTrue(theme.contains("NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)"))
        assertTrue(theme.contains("val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme"))
        assertTrue(theme.contains("val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette"))
        assertTrue(theme.contains("LocalChatPalette provides chatPalette"))
        val palette = source("ui/theme/ChatColors").substringAfter("val DarkChatPalette = ChatPalette(")
        assertTrue(palette.contains("isDark = true,"))
        assertTrue(palette.contains("background = Color(0xFF000000),"))
    }

    @Test fun other_standard_callers_keep_default_without_a_color_override() {
        val callers = listOf("ui/chat/SessionMemorySheet", "ui/chat/SessionSkillsSheet",
            "ui/chat/SessionMcpsSheet", "ui/chat/TokenUsageSheet", "ui/chat/ChatMiscViews",
            "ui/browser/BrowserSheet")
        for (caller in callers) {
            val text = source(caller)
            assertEquals(caller, 1, Regex("\\bStandardChatSheet\\(").findAll(text).count())
            assertFalse(caller, text.contains("containerColor ="))
        }
    }
}
