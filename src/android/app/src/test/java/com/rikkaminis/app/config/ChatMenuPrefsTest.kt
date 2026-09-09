package com.rikkaminis.app.config

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract tests for [ChatMenuPrefs]: the pure normalization / anchor / sanitize
 * logic plus the round-trip behavior of persist helpers against a fake prefs store.
 *
 * Why fake SharedPreferences instead of Android test infra: ChatMenuPrefs is
 * configuration-critical (minis-config writes, backup restores, stale-order
 * upgrades), so every edge case must be testable in a JVM-only unit test without
 * Robolectric. A minimal fake impl keeps the tests fast and the logic directly
 * verifiable.
 */
class ChatMenuPrefsTest {

    // ── Minimal fake SharedPreferences for JVM unit tests ────────────────────

    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        private val listeners = HashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = HashMap(map)

        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getInt(key: String, defValue: Int): Int =
            map[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long =
            map[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float =
            map[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in map

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }

        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = null

        private class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                pending[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                prefs.map.keys.forEach { pending[it] = null }
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                pending.forEach { (k, v) ->
                    if (v == null) prefs.map.remove(k) else prefs.map[k] = v
                }
                pending.clear()
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = this
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `original eight actions default to visible`() {
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.TERMINAL))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.BROWSER))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.CHAT_FILES))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SESSION_SKILLS))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SESSION_MCPS))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SESSION_MEMORY))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SLASH_COMMANDS))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.EXPORT))
    }

    @Test
    fun `COMPACT and THINKING default to visible`() {
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.COMPACT))
        assertTrue(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.THINKING))
    }

    @Test
    fun `COMPACT and THINKING default to unpinned`() {
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.COMPACT))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.THINKING))
    }

    @Test
    fun `TOKEN_USAGE and SETTINGS default to hidden from menu`() {
        assertFalse(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.TOKEN_USAGE))
        assertFalse(ChatMenuPrefs.defaultVisible(ChatMenuPrefs.SETTINGS))
    }

    @Test
    fun `TOKEN_USAGE and SETTINGS default to pinned in footer`() {
        assertTrue(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.TOKEN_USAGE))
        assertTrue(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SETTINGS))
    }

    @Test
    fun `original eight actions default to unpinned`() {
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.TERMINAL))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.BROWSER))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.CHAT_FILES))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SESSION_SKILLS))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SESSION_MCPS))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SESSION_MEMORY))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.SLASH_COMMANDS))
        assertFalse(ChatMenuPrefs.defaultPinned(ChatMenuPrefs.EXPORT))
    }

    @Test
    fun `legacy ten-item order resolves to twelve by appending missing entries`() {
        val legacy = ChatMenuPrefs.DEFAULT_ORDER.joinToString(",")
        val result = ChatMenuPrefs.normalizeOrder(legacy, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(12, result.size)
        assertTrue(ChatMenuPrefs.TOKEN_USAGE in result)
        assertTrue(ChatMenuPrefs.SETTINGS in result)
        // Original ten retain their order at the front
        assertEquals(ChatMenuPrefs.DEFAULT_ORDER.subList(0, 10), result.subList(0, 10))
    }

    @Test
    fun `normalizeOrder drops unknown keys`() {
        val raw = "menu_terminal,unknown_key,menu_browser"
        val result = ChatMenuPrefs.normalizeOrder(raw, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(listOf(ChatMenuPrefs.TERMINAL, ChatMenuPrefs.BROWSER), result.subList(0, 2))
        assertFalse("unknown_key" in result)
    }

    @Test
    fun `normalizeOrder deduplicates keeping first occurrence`() {
        val raw = "menu_terminal,menu_browser,menu_terminal,menu_browser"
        val result = ChatMenuPrefs.normalizeOrder(raw, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(2, result.take(2).filter { it == ChatMenuPrefs.TERMINAL }.size + result.take(2).filter { it == ChatMenuPrefs.BROWSER }.size)
        assertEquals(ChatMenuPrefs.TERMINAL, result[0])
        assertEquals(ChatMenuPrefs.BROWSER, result[1])
    }

    @Test
    fun `normalizeOrder appends missing known keys in known order`() {
        val raw = "menu_export,menu_terminal"
        val result = ChatMenuPrefs.normalizeOrder(raw, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(12, result.size)
        assertEquals(ChatMenuPrefs.EXPORT, result[0])
        assertEquals(ChatMenuPrefs.TERMINAL, result[1])
        // The twelve missing entries follow in their DEFAULT_ORDER relative positions
    }

    @Test
    fun `resolveOrder on empty prefs yields all twelve entries in default order`() {
        val prefs = FakePrefs()
        val result = ChatMenuPrefs.resolveOrder(prefs)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES, result)
    }

    @Test
    fun `resolvePinOrder on empty prefs yields all twelve entries in default order`() {
        val prefs = FakePrefs()
        val result = ChatMenuPrefs.resolvePinOrder(prefs)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES, result)
    }

    @Test
    fun `anchorSettingsLast moves SETTINGS to end when pinned`() {
        val order = listOf(ChatMenuPrefs.TERMINAL, ChatMenuPrefs.SETTINGS, ChatMenuPrefs.BROWSER)
        val result = ChatMenuPrefs.anchorSettingsLast(order, settingsPinned = true)
        assertEquals(ChatMenuPrefs.SETTINGS, result.last())
        assertEquals(listOf(ChatMenuPrefs.TERMINAL, ChatMenuPrefs.BROWSER, ChatMenuPrefs.SETTINGS), result)
    }

    @Test
    fun `anchorSettingsLast removes SETTINGS when unpinned`() {
        val order = listOf(ChatMenuPrefs.TERMINAL, ChatMenuPrefs.SETTINGS, ChatMenuPrefs.BROWSER)
        val result = ChatMenuPrefs.anchorSettingsLast(order, settingsPinned = false)
        assertFalse(ChatMenuPrefs.SETTINGS in result)
        assertEquals(listOf(ChatMenuPrefs.TERMINAL, ChatMenuPrefs.BROWSER), result)
    }

    @Test
    fun `settingsPinOrder always lists all twelve entries with SETTINGS last`() {
        val prefs = FakePrefs()
        // Even with everything unpinned and a scrambled persisted pin order,
        // the settings list keeps all twelve rows and anchors SETTINGS at the end.
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.TOKEN_USAGE, false)
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.SETTINGS, false)
        ChatMenuPrefs.writePinOrder(prefs, listOf(ChatMenuPrefs.SETTINGS, ChatMenuPrefs.TERMINAL))
        val result = ChatMenuPrefs.settingsPinOrder(prefs)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES.size, result.size)
        assertEquals(ChatMenuPrefs.TERMINAL, result[0])
        assertEquals(ChatMenuPrefs.SETTINGS, result.last())
        // SETTINGS appears exactly once (no dupes from the anchor dance)
        assertEquals(1, result.count { it == ChatMenuPrefs.SETTINGS })
    }

    @Test
    fun `settingsPinOrder keeps SETTINGS reachable after unpin`() {
        // Regression: the settings screen's footer section used to run the pin
        // order through anchorSettingsLast, which drops an unpinned SETTINGS —
        // the row vanished the moment the user toggled it off, leaving no UI
        // path back. settingsPinOrder must keep the row listed so the switch
        // always stays reachable.
        val prefs = FakePrefs()
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.SETTINGS, false)
        val result = ChatMenuPrefs.settingsPinOrder(prefs)
        assertTrue(ChatMenuPrefs.SETTINGS in result)
        assertEquals(ChatMenuPrefs.SETTINGS, result.last())
    }

    @Test
    fun `settingsPinOrder does not depend on pin flags`() {
        // The settings list shows all twelve rows regardless of which entries are
        // currently pinned — pin state only drives the Switch checked value.
        val prefs = FakePrefs()
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.TERMINAL, true)
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.TOKEN_USAGE, false)
        val result = ChatMenuPrefs.settingsPinOrder(prefs)
        assertEquals(ChatMenuPrefs.ALL_ENTRIES.size, result.size)
        assertTrue(ChatMenuPrefs.TERMINAL in result)
        assertTrue(ChatMenuPrefs.TOKEN_USAGE in result)
    }

    @Test
    fun `TOKEN_USAGE can appear before or after SETTINGS when both pinned`() {
        val prefs = FakePrefs()
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.TOKEN_USAGE, true)
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.SETTINGS, true)
        ChatMenuPrefs.writePinOrder(prefs, listOf(ChatMenuPrefs.TOKEN_USAGE, ChatMenuPrefs.SETTINGS))
        val result1 = ChatMenuPrefs.resolvePinnedOrder(prefs)
        assertEquals(ChatMenuPrefs.TOKEN_USAGE, result1[0])
        assertEquals(ChatMenuPrefs.SETTINGS, result1.last())

        ChatMenuPrefs.writePinOrder(prefs, listOf(ChatMenuPrefs.SETTINGS, ChatMenuPrefs.TOKEN_USAGE))
        val result2 = ChatMenuPrefs.resolvePinnedOrder(prefs)
        assertEquals(ChatMenuPrefs.TOKEN_USAGE, result2[0])
        assertEquals(ChatMenuPrefs.SETTINGS, result2.last()) // SETTINGS always anchored last
    }

    @Test
    fun `writeOrder and readback roundtrip preserves order`() {
        val prefs = FakePrefs()
        val custom = listOf(ChatMenuPrefs.EXPORT, ChatMenuPrefs.TERMINAL, ChatMenuPrefs.BROWSER)
        ChatMenuPrefs.writeOrder(prefs, custom)
        val result = ChatMenuPrefs.resolveOrder(prefs)
        // sanitizeForWrite appends missing keys, so result has all 10
        assertEquals(custom.subList(0, 3), result.subList(0, 3))
    }

    @Test
    fun `writePinOrder and readback roundtrip preserves order`() {
        val prefs = FakePrefs()
        val custom = listOf(ChatMenuPrefs.TOKEN_USAGE, ChatMenuPrefs.TERMINAL, ChatMenuPrefs.SETTINGS)
        ChatMenuPrefs.writePinOrder(prefs, custom)
        val result = ChatMenuPrefs.resolvePinOrder(prefs)
        assertEquals(custom.subList(0, 3), result.subList(0, 3))
    }

    @Test
    fun `resolvePinnedOrder returns empty list when nothing is pinned`() {
        val prefs = FakePrefs()
        // Explicitly unpin the defaults
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.TOKEN_USAGE, false)
        ChatMenuPrefs.setPinned(prefs, ChatMenuPrefs.SETTINGS, false)
        val result = ChatMenuPrefs.resolvePinnedOrder(prefs)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `isChatActionAvailable gates conditional actions correctly`() {
        assertFalse(isChatActionAvailable(ChatMenuPrefs.SESSION_SKILLS, skillsAvailable = false, mcpsAvailable = true, memoryAvailable = true))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_SKILLS, skillsAvailable = true, mcpsAvailable = true, memoryAvailable = true))
        assertFalse(isChatActionAvailable(ChatMenuPrefs.SESSION_MCPS, skillsAvailable = true, mcpsAvailable = false, memoryAvailable = true))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_MCPS, skillsAvailable = true, mcpsAvailable = true, memoryAvailable = true))
        assertFalse(isChatActionAvailable(ChatMenuPrefs.SESSION_MEMORY, skillsAvailable = true, mcpsAvailable = true, memoryAvailable = false))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SESSION_MEMORY, skillsAvailable = true, mcpsAvailable = true, memoryAvailable = true))
        // All other actions are always available
        assertTrue(isChatActionAvailable(ChatMenuPrefs.TERMINAL, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.EXPORT, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.TOKEN_USAGE, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.SETTINGS, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
        // COMPACT and THINKING are unconditional — always available regardless
        // of backing repos, so the persisted user choice survives.
        assertTrue(isChatActionAvailable(ChatMenuPrefs.COMPACT, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
        assertTrue(isChatActionAvailable(ChatMenuPrefs.THINKING, skillsAvailable = false, mcpsAvailable = false, memoryAvailable = false))
    }

    @Test
    fun `sanitizeForWrite filters unknown and deduplicates`() {
        val dirty = listOf("unknown", ChatMenuPrefs.TERMINAL, ChatMenuPrefs.TERMINAL, "another_unknown")
        val result = ChatMenuPrefs.sanitizeForWrite(dirty, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(1, result.filter { it == ChatMenuPrefs.TERMINAL }.size)
        assertFalse("unknown" in result)
    }

    @Test
    fun `sanitizeForWrite appends missing known keys`() {
        val partial = listOf(ChatMenuPrefs.TERMINAL)
        val result = ChatMenuPrefs.sanitizeForWrite(partial, ChatMenuPrefs.ALL_ENTRIES)
        assertEquals(12, result.size)
        assertEquals(ChatMenuPrefs.TERMINAL, result[0])
    }

    // ── Attach ("+") menu domain ──────────────────────────────────────────────

    @Test
    fun `attach actions default to visible`() {
        assertTrue(ChatMenuPrefs.defaultAttachVisible(AttachActionCatalog.CHOOSE_PHOTOS))
        assertTrue(ChatMenuPrefs.defaultAttachVisible(AttachActionCatalog.ADD_FILE))
        assertTrue(ChatMenuPrefs.defaultAttachVisible(AttachActionCatalog.TAKE_PHOTO))
    }

    @Test
    fun `attach visibility roundtrip`() {
        val prefs = FakePrefs()
        ChatMenuPrefs.setAttachVisible(prefs, AttachActionCatalog.TAKE_PHOTO, false)
        assertFalse(ChatMenuPrefs.isAttachVisible(prefs, AttachActionCatalog.TAKE_PHOTO))
        assertTrue(ChatMenuPrefs.isAttachVisible(prefs, AttachActionCatalog.CHOOSE_PHOTOS))
        assertTrue(ChatMenuPrefs.isAttachVisible(prefs, AttachActionCatalog.ADD_FILE))
    }

    @Test
    fun `resolveAttachOrder on empty prefs yields default attach order`() {
        val prefs = FakePrefs()
        assertEquals(AttachActionCatalog.DEFAULT_ORDER, ChatMenuPrefs.resolveAttachOrder(prefs))
    }

    @Test
    fun `writeAttachOrder roundtrip preserves order`() {
        val prefs = FakePrefs()
        val custom = listOf(
            AttachActionCatalog.ADD_FILE,
            AttachActionCatalog.TAKE_PHOTO,
            AttachActionCatalog.CHOOSE_PHOTOS,
        )
        ChatMenuPrefs.writeAttachOrder(prefs, custom)
        assertEquals(custom, ChatMenuPrefs.resolveAttachOrder(prefs))
    }

    @Test
    fun `attach order ignores unknown keys and appends missing`() {
        val prefs = FakePrefs()
        ChatMenuPrefs.writeAttachOrder(prefs, listOf("unknown", AttachActionCatalog.TAKE_PHOTO))
        val result = ChatMenuPrefs.resolveAttachOrder(prefs)
        // Unknown dropped, TAKE_PHOTO first, the other two appended in default order
        assertEquals(AttachActionCatalog.TAKE_PHOTO, result[0])
        assertEquals(AttachActionCatalog.DEFAULT_ORDER.size, result.size)
        assertTrue(AttachActionCatalog.CHOOSE_PHOTOS in result)
        assertTrue(AttachActionCatalog.ADD_FILE in result)
    }

    @Test
    fun `attach keys never collide with menu pool keys`() {
        // Domain isolation: attach keys must not appear in the "..." menu pool,
        // and menu-pool keys must not appear in the attach default order.
        for (key in AttachActionCatalog.DEFAULT_ORDER) {
            assertFalse(key in ChatMenuPrefs.ALL_ENTRIES)
            assertFalse(key in ChatMenuPrefs.DEFAULT_ORDER)
        }
    }

    @Test
    fun `composer model picker button defaults visible and roundtrips`() {
        val prefs = FakePrefs()
        assertTrue(ChatMenuPrefs.isComposerModelPickerVisible(prefs))
        ChatMenuPrefs.setComposerModelPickerVisible(prefs, false)
        assertFalse(ChatMenuPrefs.isComposerModelPickerVisible(prefs))
        ChatMenuPrefs.setComposerModelPickerVisible(prefs, true)
        assertTrue(ChatMenuPrefs.isComposerModelPickerVisible(prefs))
    }
}
