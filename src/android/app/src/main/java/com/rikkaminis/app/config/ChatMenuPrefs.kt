package com.rikkaminis.app.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the customizable chat actions: the top-right
 * "..." (overflow) menu AND the chat-history drawer footer.
 *
 * The user can hide / reorder the menu entries and pin / reorder the footer
 * actions from Settings → Appearance → "Chat Menu". All booleans and both
 * order strings live in the same SharedPreferences file AppearanceScreen
 * already uses (`appearance_prefs`), so:
 *  - ConfigBuiltins registers each as a config field → they are visible to
 *    minis-config AND walked into every local backup automatically.
 *  - ChatScreen reads them to filter + order the rendered menu and footer.
 *  - ChatMenuSettingsScreen writes them from the settings UI.
 *
 * The action pool has fourteen stable keys: the ten original "action /
 * session" menu entries, the two model-conditional toggles (Fast Mode,
 * Enhanced Cache) and two footer-only actions (Token Usage, Settings). Each
 * key carries TWO independent flags:
 *  - `visible` — appears in the top-right "..." menu;
 *  - `pinned`  — appears in the history-drawer footer.
 * Neither flag is derived from the other, and the two order strings
 * (`chatMenu.order` / `chatMenu.pinOrder`) are resolved independently.
 *
 * [chat-menu-model-toggles-1126] The model-conditional toggles USED to sit
 * outside the pool: they rendered whenever the active model supported them,
 * with their own inline Switch, and the "second hide layer" was judged
 * redundant. That conflated two different switches — "is the feature usable
 * on this model" (a runtime gate, still applied by
 * [ChatActionAvailability.isAvailable]) and "does the user want this entry on
 * their bar" (an entry preference). A user who turned every entry off still got
 * the Fast Mode ⚡ promoted to a lone top-bar button, and could not hide it
 * anywhere.
 * Both toggles are now ordinary pool members whose visibility / pin the user
 * controls; hiding one never disables the feature and never hides the nav-bar
 * ⚡ status badge. The DEBUG crash trigger is a developer tool, still
 * excluded.
 */
object ChatMenuPrefs {
    // Same SharedPreferences file as AppearanceScreen (PREF_APPEARANCE).
    const val PREFS = "appearance_prefs"

    // -- Entry stable keys (never localized; used as pref keys and order ids) --
    const val TERMINAL = "menu_terminal"
    const val BROWSER = "menu_browser"
    const val CHAT_FILES = "menu_chat_files"
    const val EXPORT = "menu_export"
    const val SLASH_COMMANDS = "menu_slash_commands"
    const val SESSION_SKILLS = "menu_session_skills"
    const val SESSION_MCPS = "menu_session_mcps"
    const val SESSION_MEMORY = "menu_session_memory"
    const val COMPACT = "menu_compact"
    const val THINKING = "menu_thinking"
    const val FAST_MODE = "menu_fast_mode"
    const val ENHANCED_CACHE = "menu_enhanced_cache"
    const val TOKEN_USAGE = "footer_token_usage"
    const val SETTINGS = "footer_settings"

    /**
     * Keys whose availability depends on the ACTIVE MODEL rather than on a
     * repository being present. They are ordinary pool members (the user can
     * hide / pin / reorder them) but their runtime gate lives in
     * [ChatActionAvailability.isAvailable], so an unsupported model never
     * leaves a dead
     * toggle on the bar.
     */
    val MODEL_CONDITIONAL_ENTRIES: List<String> = listOf(FAST_MODE, ENHANCED_CACHE)

    /** Default "..." menu order (twelve configurable menu entries only). */
    val DEFAULT_ORDER: List<String> = listOf(
        TERMINAL,
        BROWSER,
        CHAT_FILES,
        SESSION_SKILLS,
        SESSION_MCPS,
        SESSION_MEMORY,
        SLASH_COMMANDS,
        EXPORT,
        COMPACT,
        THINKING,
        FAST_MODE,
        ENHANCED_CACHE,
    )

    /** Default editor/render order for the history-drawer footer pool. */
    val DEFAULT_PIN_ORDER: List<String> = listOf(
        BROWSER,
        TERMINAL,
        SESSION_SKILLS,
        SESSION_MCPS,
        SESSION_MEMORY,
        CHAT_FILES,
        SLASH_COMMANDS,
        EXPORT,
        COMPACT,
        THINKING,
        FAST_MODE,
        ENHANCED_CACHE,
        TOKEN_USAGE,
        SETTINGS,
    )

    val ALL_ENTRIES: List<String> = DEFAULT_ORDER + listOf(TOKEN_USAGE, SETTINGS)

    /** SharedPreferences key for the "..." menu display order. */
    const val ORDER_KEY = "chatMenu.order"

    /** SharedPreferences key for the attach ("+") menu display order. */
    const val ATTACH_ORDER_KEY = "chatMenu.attachOrder"

    /** SharedPreferences key for the footer pin order. */
    const val PIN_ORDER_KEY = "chatMenu.pinOrder"

    /** SharedPreferences key storing the persisted visibility of an entry. */
    fun visibilityKey(entryKey: String): String = "chatMenu.$entryKey.visible"

    /** SharedPreferences key storing the persisted attach visibility. */
    fun attachVisibilityKey(entryKey: String): String = "chatMenu.attach.$entryKey.visible"

    /** SharedPreferences key storing the persisted footer-pin of an entry. */
    fun pinKey(entryKey: String): String = "chatMenu.$entryKey.pinned"

    /** Config-registry path for an entry's visibility field. */
    fun visibilityPath(entryKey: String): String = "appearance.chatMenu.$entryKey"

    /** Config-registry path for an attach entry's visibility field. */
    fun attachVisibilityPath(entryKey: String): String = "appearance.chatMenuAttach.$entryKey"

    /** Config-registry path for an entry's pinned field. */
    fun pinPath(entryKey: String): String = "appearance.chatMenuPin.$entryKey"

    /** Config-registry path for the menu order field. */
    const val ORDER_PATH = "appearance.chatMenuOrder"

    /** Config-registry path for the attach menu order field. */
    const val ATTACH_ORDER_PATH = "appearance.chatMenuAttachOrder"

    /** Config-registry path for the footer pin order field. */
    const val PIN_ORDER_PATH = "appearance.chatMenuPinOrder"

    /**
     * Default visibility: every attach action is visible.
     */
    fun defaultAttachVisible(entryKey: String): Boolean = true

    /**
     * Default visibility: the built-in layout starts with every configurable
     * menu entry hidden. A hidden entry remains configurable; it is not a
     * runtime feature flag and can be enabled from Chat Menu settings.
     */
    fun defaultVisible(entryKey: String): Boolean = false

    /**
     * Adopt previously absent model-conditional entries using the current
     * built-in menu default. Never infer visibility from unrelated legacy
     * entries: doing so would re-inject Fast Mode / Enhanced Cache into the
     * hidden-by-default menu. Explicit user or restored values always win.
     * Idempotent and safe to call on every cold start.
     */
    fun adoptModelConditionalEntries(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var dirty = false
        for (key in MODEL_CONDITIONAL_ENTRIES) {
            if (prefs.contains(visibilityKey(key))) continue
            editor.putBoolean(visibilityKey(key), defaultVisible(key))
            dirty = true
        }
        if (dirty) editor.apply()
    }

    /**
     * Default pinning: Token Usage and Settings start in the footer; other
     * actions start unpinned.
     */
    fun defaultPinned(entryKey: String): Boolean =
        entryKey == TOKEN_USAGE || entryKey == SETTINGS

    // -- Read helpers (used by ChatScreen, drawer and settings) --

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isVisible(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(visibilityKey(entryKey), defaultVisible(entryKey))

    fun isPinned(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(pinKey(entryKey), defaultPinned(entryKey))

    fun setVisible(prefs: SharedPreferences, entryKey: String, visible: Boolean) {
        prefs.edit().putBoolean(visibilityKey(entryKey), visible).apply()
    }

    fun attachPrefs(context: Context): SharedPreferences = prefs(context)

    fun isAttachVisible(prefs: SharedPreferences, entryKey: String): Boolean =
        prefs.getBoolean(attachVisibilityKey(entryKey), defaultAttachVisible(entryKey))

    fun setAttachVisible(prefs: SharedPreferences, entryKey: String, visible: Boolean) {
        prefs.edit().putBoolean(attachVisibilityKey(entryKey), visible).apply()
    }

    /** Resolve the persisted attach ("+") menu order over the three attach keys. */
    fun resolveAttachOrder(prefs: SharedPreferences): List<String> =
        normalizeOrder(prefs.getString(ATTACH_ORDER_KEY, null), AttachActionCatalog.DEFAULT_ORDER)

    /** Persist a new attach menu order (comma-separated, sanitized). */
    fun writeAttachOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit()
            .putString(ATTACH_ORDER_KEY, sanitizeForWrite(order, AttachActionCatalog.DEFAULT_ORDER).joinToString(","))
            .apply()
    }

    fun setPinned(prefs: SharedPreferences, entryKey: String, pinned: Boolean) {
        prefs.edit().putBoolean(pinKey(entryKey), pinned).apply()
    }

    // -- Order normalization (pure; unit-testable without Android) --

    /**
     * Normalize a persisted comma-separated order against [known] entries:
     * split + trim, drop empty parts and unknown keys, keep the FIRST
     * occurrence of each key (dedupe, preserving order), then append every
     * missing known key in [known] order so a stale backup never hides a
     * brand-new entry.
     */
    fun normalizeOrder(raw: String?, known: List<String>): List<String> {
        val ordered = ArrayList<String>()
        val seen = HashSet<String>()
        raw?.split(",")?.forEach { part ->
            val key = part.trim()
            if (key.isNotEmpty() && key in known && seen.add(key)) ordered.add(key)
        }
        for (key in known) {
            if (seen.add(key)) ordered.add(key)
        }
        return ordered
    }

    /**
     * Sanitize a UI-supplied order before persisting: drop unknown keys,
     * dedupe (keep first), then append missing known keys in [known] order so
     * a write can never produce an empty / damaged order. Accepts a list that
     * does NOT contain SETTINGS (an unpinned footer is valid).
     */
    fun sanitizeForWrite(order: List<String>, known: List<String>): List<String> {
        val ordered = ArrayList<String>()
        val seen = HashSet<String>()
        for (key in order) {
            if (key in known && seen.add(key)) ordered.add(key)
        }
        for (key in known) {
            if (seen.add(key)) ordered.add(key)
        }
        return ordered
    }

    /** Resolve the persisted "..." menu order (twelve menu entries only). */
    fun resolveOrder(prefs: SharedPreferences): List<String> =
        normalizeOrder(prefs.getString(ORDER_KEY, null), DEFAULT_ORDER)

    /**
     * Resolve the persisted footer pin order over all fourteen entries — unpinned
     * entries are INCLUDED so the settings UI can show the full editable
     * list. Render-time filtering happens in [resolvePinnedOrder].
     */
    fun resolvePinOrder(prefs: SharedPreferences): List<String> =
        normalizeOrder(prefs.getString(PIN_ORDER_KEY, null), DEFAULT_PIN_ORDER)

    /**
     * Move SETTINGS to the very end whenever it is pinned; when unpinned it is
     * filtered out and never re-injected (an unpinned footer may contain no
     * settings button at all).
     */
    fun anchorSettingsLast(order: List<String>, settingsPinned: Boolean): List<String> {
        val without = order.filterNot { it == SETTINGS }
        return if (settingsPinned) without + SETTINGS else without
    }

    /**
     * The order shown in the Chat Menu settings screen's footer section: all
     * fourteen entries with SETTINGS anchored last regardless of its pin state.
     * Unlike [anchorSettingsLast] (render semantics — an unpinned SETTINGS is
     * dropped), the settings list must always keep the SETTINGS row visible so
     * an unpinned settings button can always be re-pinned from the UI; a row
     * that vanishes on unpin would leave no way back.
     */
    fun settingsPinOrder(prefs: SharedPreferences): List<String> =
        resolvePinOrder(prefs).filterNot { it == SETTINGS } + SETTINGS

    /**
     * The order actually rendered in the footer: pinned entries in resolved
     * order, SETTINGS anchored last when pinned. Empty list = nothing pinned
     * (the caller then hides the footer bar and its divider entirely).
     */
    fun resolvePinnedOrder(prefs: SharedPreferences): List<String> =
        anchorSettingsLast(
            resolvePinOrder(prefs).filter { isPinned(prefs, it) },
            isPinned(prefs, SETTINGS),
        )

    // -- Top-bar pinned-button visibility (independent of "..." menu entries) --

    /**
     * Key for the "Input History" top-bar icon (the list-bullet button
     * alongside New Chat). Unlike the fourteen [ALL_ENTRIES] items it does
     * NOT participate in the overflow menu or footer — it has a fixed top-bar
     * slot. The preference only controls whether the icon is rendered at all.
     * Default: false (hidden).
     */
    const val TOP_BAR_INPUT_HISTORY = "topBar.inputHistory"
    // internal (was private): ChatMenuSettingsScreen's prefs-change listener
    // must match on this exact key (it does NOT start with "chatMenu.", so the
    // "chatMenu." prefix guard alone never fired for external minis-config /
    // backup-restore writes). Single source — no second string copy in the UI.
    internal const val TOP_BAR_INPUT_HISTORY_PREF = "topBar.inputHistory.visible"

    fun isTopBarInputHistoryVisible(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(TOP_BAR_INPUT_HISTORY_PREF, false)

    fun setTopBarInputHistoryVisible(prefs: SharedPreferences, visible: Boolean) {
        prefs.edit().putBoolean(TOP_BAR_INPUT_HISTORY_PREF, visible).apply()
    }

    // -- Composer model-picker button visibility --

    /**
     * Key for the composer's circular model-picker button (the up-arrow circle
     * at the left edge of the input row). Like Input History this is a fixed
     * slot with its own boolean, NOT part of the action pool: the model name +
     * status dot stay visible in the top-bar subtitle (tap it to open the same
     * picker), so hiding this button only removes the redundant composer
     * trigger. Default: true (visible).
     */
    const val COMPOSER_MODEL_PICKER_BUTTON = "composer.modelPickerButton"
    // internal (was private): same reason as TOP_BAR_INPUT_HISTORY_PREF — the
    // settings screen's listener matches on this key too; single source.
    internal const val COMPOSER_MODEL_PICKER_BUTTON_PREF = "composer.modelPickerButton.visible"

    fun isComposerModelPickerVisible(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(COMPOSER_MODEL_PICKER_BUTTON_PREF, true)

    fun setComposerModelPickerVisible(prefs: SharedPreferences, visible: Boolean) {
        prefs.edit().putBoolean(COMPOSER_MODEL_PICKER_BUTTON_PREF, visible).apply()
    }

    // -- Persist helpers --

    /** Persist a new "..." menu order (comma-separated, sanitized). */
    fun writeOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit()
            .putString(ORDER_KEY, sanitizeForWrite(order, DEFAULT_ORDER).joinToString(","))
            .apply()
    }

    /** Persist a new footer pin order (comma-separated, sanitized). */
    fun writePinOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit()
            .putString(PIN_ORDER_KEY, sanitizeForWrite(order, DEFAULT_PIN_ORDER).joinToString(","))
            .apply()
    }
}
