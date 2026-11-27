package com.rikkaminis.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production catalog and default-layout contract; no duplicated implementation logic. */
class ChatActionCatalogDefaultsTest {
    @Test
    fun `all twelve configurable menu actions stay catalogued but default hidden`() {
        assertEquals(12, ChatMenuPrefs.DEFAULT_ORDER.size)
        assertEquals(
            ChatMenuPrefs.ALL_ENTRIES.toSet(),
            ChatActionCatalog.ALL.map { it.key }.toSet(),
        )
        for (key in ChatMenuPrefs.DEFAULT_ORDER) {
            val spec = ChatActionCatalog.spec(key) ?: error("catalog metadata missing for $key")
            assertFalse("$key metadata default", spec.defaultMenuVisible)
            assertFalse("$key preference default", ChatMenuPrefs.defaultVisible(key))
            assertEquals("$key pin metadata", ChatMenuPrefs.defaultPinned(key), spec.defaultPinned)
        }
    }

    @Test
    fun `footer only actions stay outside menu order and remain pinned only in footer`() {
        assertFalse(ChatMenuPrefs.TOKEN_USAGE in ChatMenuPrefs.DEFAULT_ORDER)
        assertFalse(ChatMenuPrefs.SETTINGS in ChatMenuPrefs.DEFAULT_ORDER)
        assertEquals(14, ChatMenuPrefs.DEFAULT_PIN_ORDER.size)
        for (key in listOf(ChatMenuPrefs.TOKEN_USAGE, ChatMenuPrefs.SETTINGS)) {
            val spec = ChatActionCatalog.spec(key) ?: error("catalog metadata missing for $key")
            assertFalse(spec.defaultMenuVisible)
            assertTrue(spec.defaultPinned)
            assertFalse(ChatMenuPrefs.defaultVisible(key))
            assertTrue(ChatMenuPrefs.defaultPinned(key))
        }
    }

    @Test
    fun `built in normalized layout matches the current user layout`() {
        assertEquals(
            listOf(
                "menu_terminal", "menu_browser", "menu_chat_files", "menu_session_skills",
                "menu_session_mcps", "menu_session_memory", "menu_slash_commands", "menu_export",
                "menu_compact", "menu_thinking", "menu_fast_mode", "menu_enhanced_cache",
            ),
            ChatMenuPrefs.DEFAULT_ORDER,
        )
        assertEquals(
            listOf(
                "menu_browser", "menu_terminal", "menu_session_skills", "menu_session_mcps",
                "menu_session_memory", "menu_chat_files", "menu_slash_commands", "menu_export",
                "menu_compact", "menu_thinking", "menu_fast_mode", "menu_enhanced_cache",
                "footer_token_usage", "footer_settings",
            ),
            ChatMenuPrefs.DEFAULT_PIN_ORDER,
        )
        assertEquals(
            listOf("attach_choose_photos", "attach_add_file", "attach_take_photo"),
            AttachActionCatalog.DEFAULT_ORDER,
        )
    }
}
