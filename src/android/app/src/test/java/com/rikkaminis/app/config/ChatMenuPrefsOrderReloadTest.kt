package com.rikkaminis.app.config

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavior tests for [ChatMenuPrefs] order re-reads after an EXTERNAL prefs
 * write (da1021-D): the ChatMenuSettingsScreen listener now re-reads
 * resolveOrder / settingsPinOrder / resolveAttachOrder when
 * `chatMenu.order` / `chatMenu.pinOrder` / `chatMenu.attachOrder` change
 * outside the screen (minis-config / backup-restore), so these tests pin the
 * exact semantics an external restore must reproduce — the same list a fresh
 * read returns.
 *
 * Why fake SharedPreferences: same rationale as [ChatMenuPrefsTest] —
 * configuration-critical logic must be verifiable in a JVM-only test.
 * ALL expected values are hardcoded literals, never derived from
 * [ChatMenuPrefs] constants (a constant change must not disguise a mutation
 * as a pass).
 */
class ChatMenuPrefsOrderReloadTest {

    // ── Minimal fake SharedPreferences for JVM unit tests ────────────────────
    // In-memory map + editor; exactly the surface ChatMenuPrefs touches.

    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = null
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

        private class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { pending.clear() }
            override fun commit(): Boolean { prefs.map.putAll(pending); return true }
            override fun apply() { prefs.map.putAll(pending) }
        }
    }

    // ── resolveOrder: external write of chatMenu.order re-reads the new order ──

    @Test
    fun `resolveOrder after external write returns written order first then missing keys appended`() {
        val prefs = FakePrefs()
        prefs.map["chatMenu.order"] = "menu_thinking,menu_terminal"
        assertEquals(
            listOf(
                "menu_thinking",
                "menu_terminal",
                "menu_browser",
                "menu_chat_files",
                "menu_compact",
                "menu_fast_mode",
                "menu_enhanced_cache",
                "menu_session_skills",
                "menu_session_mcps",
                "menu_session_memory",
                "menu_slash_commands",
                "menu_export",
                "footer_token_usage",
                "footer_settings",
            ),
            ChatMenuPrefs.resolveOrder(prefs),
        )
    }

    @Test
    fun `resolveOrder writeOrder round-trip keeps the written prefix order`() {
        val prefs = FakePrefs()
        ChatMenuPrefs.writeOrder(prefs, listOf("menu_export", "menu_terminal"))
        assertEquals(
            listOf(
                "menu_export",
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
                "footer_token_usage",
                "footer_settings",
            ),
            ChatMenuPrefs.resolveOrder(prefs),
        )
    }

    @Test
    fun `resolveOrder on empty prefs yields the full default sequence`() {
        assertEquals(
            listOf(
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
                "footer_token_usage",
                "footer_settings",
            ),
            ChatMenuPrefs.resolveOrder(FakePrefs()),
        )
    }

    // ── settingsPinOrder: external restore of chatMenu.pinOrder ──────────────

    @Test
    fun `settingsPinOrder after external write anchors SETTINGS last and keeps written order`() {
        val prefs = FakePrefs()
        prefs.map["chatMenu.pinOrder"] = "footer_settings,menu_terminal,footer_token_usage"
        // resolvePinOrder = written order + missing keys appended in
        // ALL_ENTRIES order; settingsPinOrder drops SETTINGS and re-appends
        // it at the very end.
        assertEquals(
            listOf(
                "menu_terminal",
                "footer_token_usage",
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
                "footer_settings",
            ),
            ChatMenuPrefs.settingsPinOrder(prefs),
        )
    }

    @Test
    fun `settingsPinOrder after unpinned SETTINGS restore still keeps the row last`() {
        val prefs = FakePrefs()
        prefs.map["chatMenu.pinOrder"] = "menu_terminal,footer_token_usage"
        assertEquals(
            listOf(
                "menu_terminal",
                "footer_token_usage",
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
                "footer_settings",
            ),
            ChatMenuPrefs.settingsPinOrder(prefs),
        )
    }

    // ── resolveAttachOrder: external write of chatMenu.attachOrder ───────────

    @Test
    fun `resolveAttachOrder after external write returns written order then missing appended`() {
        val prefs = FakePrefs()
        prefs.map["chatMenu.attachOrder"] = "attach_take_photo,attach_add_file"
        assertEquals(
            listOf("attach_take_photo", "attach_add_file", "attach_choose_photos"),
            ChatMenuPrefs.resolveAttachOrder(prefs),
        )
    }

    @Test
    fun `resolveAttachOrder writeAttachOrder round-trip is identity`() {
        val prefs = FakePrefs()
        ChatMenuPrefs.writeAttachOrder(prefs, listOf("attach_choose_photos", "attach_take_photo"))
        assertEquals(
            listOf("attach_choose_photos", "attach_take_photo", "attach_add_file"),
            ChatMenuPrefs.resolveAttachOrder(prefs),
        )
    }

    // ── da1021-D arm4: the two fixed keys must stay non-private and stable ───
    // Compiling these references from the test source set proves the constants
    // are visible beyond ChatMenuPrefs (the settings screen listener reads
    // them); the literal expectations pin the exact key strings the
    // minis-config ConfigBuiltins registry binds.

    @Test
    fun `top bar input history pref key is the registered config key`() {
        assertEquals("topBar.inputHistory.visible", ChatMenuPrefs.TOP_BAR_INPUT_HISTORY_PREF)
    }

    @Test
    fun `composer model picker pref key is the registered config key`() {
        assertEquals("composer.modelPickerButton.visible", ChatMenuPrefs.COMPOSER_MODEL_PICKER_BUTTON_PREF)
    }
}
