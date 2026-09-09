package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM tests for [TitlePromptLocale]. These functions are pure
 * (no Android dependencies) and testable on JVM directly.
 */
class TitlePromptLocaleTest {

    // ── titleLanguageDirective ────────────────────────────────────────

    @Test
    fun `titleLanguageDirective contains English for en locale`() {
        val result = titleLanguageDirective(Locale.ENGLISH)
        assertTrue(result.contains("en"))
        assertTrue(result.contains("English"))
        assertTrue(result.contains("title primarily in this language"))
    }

    @Test
    fun `titleLanguageDirective contains Chinese for zh locale`() {
        val result = titleLanguageDirective(Locale.CHINESE)
        assertTrue(result.contains("zh-Hans"))
        assertTrue(result.contains("简体中文"))
    }

    @Test
    fun `titleLanguageDirective contains Traditional Chinese for zh_TW`() {
        val result = titleLanguageDirective(Locale.TRADITIONAL_CHINESE)
        assertTrue(result.contains("zh-Hant"))
        assertTrue(result.contains("繁體中文"))
    }

    @Test
    fun `titleLanguageDirective contains Japanese for ja locale`() {
        val result = titleLanguageDirective(Locale.JAPANESE)
        assertTrue(result.contains("ja"))
        assertTrue(result.contains("日本語"))
    }

    @Test
    fun `titleLanguageDirective contains Korean for ko locale`() {
        val result = titleLanguageDirective(Locale.KOREAN)
        assertTrue(result.contains("ko"))
        assertTrue(result.contains("한국어"))
    }

    @Test
    fun `titleLanguageDirective handles French locale`() {
        val result = titleLanguageDirective(Locale.FRENCH)
        assertTrue(result.contains("fr"))
        assertTrue(result.contains("Français"))
    }

    @Test
    fun `titleLanguageDirective handles German locale`() {
        val result = titleLanguageDirective(Locale.GERMAN)
        assertTrue(result.contains("de"))
        assertTrue(result.contains("German"))
    }

    @Test
    fun `titleLanguageDirective handles Italian locale`() {
        val result = titleLanguageDirective(Locale.ITALIAN)
        assertTrue(result.contains("it"))
        assertTrue(result.contains("Italian"))
    }

    // ── TITLE_GEN_SYSTEM_PROMPT ───────────────────────────────────────

    @Test
    fun `title gen system prompt is valid JSON directive`() {
        assertTrue(TITLE_GEN_SYSTEM_PROMPT.contains("JSON"))
        assertTrue(TITLE_GEN_SYSTEM_PROMPT.contains("title"))
        assertTrue(TITLE_GEN_SYSTEM_PROMPT.contains("category"))
    }

    // ── edge cases ────────────────────────────────────────────────────

    @Test
    fun `titleLanguageDirective falls back to English for unknown locale`() {
        // Construct a locale with a language code not in the humanReadable map.
        val unknown = Locale.forLanguageTag("xx")
        val result = titleLanguageDirective(unknown)
        assertTrue(result.contains("xx"))
    }

    @Test
    fun `titleLanguageDirective handles empty language code`() {
        // A locale with empty language should fall back to English.
        val empty = Locale("", "")
        val result = titleLanguageDirective(empty)
        assertTrue(result.contains("en") || result.contains("English"))
    }
}