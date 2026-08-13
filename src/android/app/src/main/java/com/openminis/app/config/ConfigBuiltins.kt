package com.openminis.app.config

import android.content.Context
import com.openminis.app.config.collections.EnvVarsCollection
import com.openminis.app.config.collections.GroupsCollection
import com.openminis.app.config.collections.ModelsCollection
import com.openminis.app.config.collections.ProvidersCollection
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.config.fields.PrefsDoubleField
import com.openminis.app.config.fields.PrefsEnumField
import com.openminis.app.config.fields.PrefsIntCodedEnumField
import com.openminis.app.config.fields.PrefsIntField
import com.openminis.app.config.fields.PrefsLongField
import com.openminis.app.config.fields.PrefsStringField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in field registrations. Single place to add or remove a
 * setting from the agent-facing surface. Mirrors iOS
 * `ConfigRegistry+Builtins.swift`.
 */
internal object ConfigBuiltins {

    /**
     * [T8-2] Dependency-injected registration. The config layer must not
     * import service/UI classes — the two UI-coupled values are supplied
     * by the caller instead:
     *
     * @param isDynamicIslandCapable computed once by the caller (MinisApp)
     *   via the same service probe the UI uses; refines the
     *   `background.dynamicIsland` field to an UnavailableField when false.
     * @param sessionIdProvider live provider of the foregrounded chat
     *   session id (null when no chat is open). ConfigBuiltins reads it at
     *   field-read/write time, so it always reflects the current screen.
     */
    fun registerInto(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
        chatRepo: ChatRepository,
        isDynamicIslandCapable: Boolean,
        sessionIdProvider: () -> String?,
    ) {
        registerSelfMeta(r)
        registerSession(r, providerRepo, chatRepo, sessionIdProvider)
        registerAppearance(r, context)
        registerChat(r, context)
        registerBackground(r, context, isDynamicIslandCapable)
        registerLogs(r, context)
        registerProviderCollections(r, providerRepo, envVarRepo)
        registerDefaults(r, providerRepo)
        registerSoul(r, context)
        registerMemory(r, context)
    }

    // -- Memory — global default toggle for the persistent memory feature --
    //
    // [T-android-memory-enabled-minisconfig] `memory.enabled` mirrors the
    // Settings → Memory switch (MemoryManagementScreen, backed by
    // MemoryGlobalPrefs: SharedPreferences `minis_memory_prefs`, key
    // `memory.global.enabled`, default true). It is the global DEFAULT
    // applied to newly-created sessions: a new chat seeds its per-session
    // memoryEnabled from this value. It does NOT retroactively flip
    // already-open sessions — those carry their own per-session toggle,
    // changed via the SessionMemorySheet / `/memory`. Registering the same
    // prefs+key as MemoryGlobalPrefs keeps the CLI/agent and the Settings
    // switch reading/writing one source of truth. Topic auto-surfaces in
    // list-topics since topics derive from field-path prefixes.

    private fun registerMemory(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("minis_memory_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "memory.enabled",
                displayName = "Memory enabled (global default)",
                description = "Default for whether NEW chat sessions start with memory on. When on, a new session auto-injects GLOBAL.md + recent daily logs into the system prompt and exposes the memory_get / memory_write tools. When off, new sessions get neither. Already-open sessions keep their own per-session setting (toggle in the session memory sheet) and are not changed by this.",
                prefs = prefs,
                key = "memory.global.enabled",
                defaultValue = true,
            )
        )
    }

    // -- Master switch surface (read-only via the registry; UI toggles it) --

    private fun registerSelfMeta(r: ConfigRegistry) {
        r.register(
            ClosureField(
                path = "permissions.minisConfig.enabled",
                displayName = "Allow minis-config",
                description = "Master switch. Read-only here — toggle via Settings → Permissions.",
                valueSchema = ConfigSchema.Bool,
                access = ConfigAccess.READONLY,
                risk = ConfigRisk.DESTRUCTIVE,
                revertable = false,
                reader = { ConfigValue.Bool(MinisConfigPermissionStore.isEnabled) },
                writer = { _ ->
                    throw ConfigError.PermissionDenied(
                        "Master switch — toggle via Settings → Permissions only"
                    )
                },
            )
        )
    }

    // -- Session (current chat) --
    //
    // Mirrors iOS `session.*` keys in ConfigRegistry+Builtins.swift. The
    // "current session" is whatever ChatScreen is composed with right now,
    // tracked via `sessionIdProvider()`. Reads return empty
    // / writes throw "No active session" when no chat is foregrounded.

    private fun registerSession(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        chatRepo: ChatRepository,
        sessionIdProvider: () -> String?,
    ) {
        r.register(
            ClosureField(
                path = "session.thinkingLevel",
                displayName = "Thinking level (current session)",
                description = "off / low / medium / high / xhigh. Applied to the active chat.",
                valueSchema = ConfigSchema.StrEnum(
                    listOf("off", "low", "medium", "high", "xhigh")
                ),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) ConfigValue.Null
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        val raw = session?.thinkingOverride
                        val token = if (raw == null) "off" else thinkingLevelToToken(
                            runCatching { ThinkingLevel.valueOf(raw) }.getOrDefault(ThinkingLevel.OFF)
                        )
                        ConfigValue.Str(token)
                    }
                },
                writer = { v ->
                    val token = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val level = thinkingLevelFromToken(token)
                        ?: throw ConfigError.InvalidValue("Unknown thinking level: $token")
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    runBlocking { chatRepo.dao.updateThinkingOverride(sid, level.name) }
                },
            )
        )
        r.register(
            ClosureField(
                path = "session.primaryModel",
                displayName = "Primary model (current session)",
                description = "Set as `entry:<uuid>` or `group:<id>` (use `models` / `groups` topics to discover ids). Empty clears the binding.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val sid = sessionIdProvider()
                    if (sid == null) ConfigValue.Str("")
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        ConfigValue.Str(formatBinding(session?.modelBinding))
                    }
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val sid = sessionIdProvider()
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    if (s.isEmpty()) {
                        // Clear the binding — fall back to default group/entry on next load.
                        runBlocking { chatRepo.dao.updateSessionBinding(sid, "", "") }
                        return@ClosureField
                    }
                    val cfg = providerRepo.config.value
                    val (bindingJson, modelId) = when {
                        s.startsWith("entry:") -> {
                            val uuid = s.removePrefix("entry:")
                            if (uuid.isEmpty()) {
                                throw ConfigError.InvalidValue("entry uuid is empty")
                            }
                            val entry = cfg.modelEntries.find { it.id == uuid }
                                ?: throw ConfigError.InvalidValue("Unknown model entry uuid: $uuid")
                            """{"type":"entry","entryId":"$uuid"}""" to entry.baseModel.id
                        }
                        s.startsWith("group:") -> {
                            val gid = s.removePrefix("group:")
                            if (gid.isEmpty()) {
                                throw ConfigError.InvalidValue("group id is empty")
                            }
                            val group = cfg.modelGroups.find { it.id == gid }
                                ?: throw ConfigError.InvalidValue("Unknown group id: $gid")
                            val firstEntryId = group.memberEntryIds.firstOrNull()
                                ?: throw ConfigError.InvalidValue("Group $gid has no member entries")
                            val firstEntry = cfg.modelEntries.find { it.id == firstEntryId }
                                ?: throw ConfigError.InvalidValue("Group $gid references missing entry $firstEntryId")
                            """{"type":"group","groupId":"$gid"}""" to firstEntry.baseModel.id
                        }
                        else -> throw ConfigError.InvalidValue(
                            "Expected `entry:<uuid>` or `group:<id>`, got '$s'"
                        )
                    }
                    runBlocking {
                        chatRepo.dao.updateSessionBinding(sid, bindingJson, modelId)
                    }
                },
            )
        )
        // T311: iOS `session.subModel` is not registered on Android — the
        // platform has no per-session sub/fallback-model concept (no
        // `subModelSource` field in `model_binding` JSON, no agent-loop
        // fallback path). Reported up at the spec level rather than
        // adding a no-op key that silently throws on every write.
    }

    /** iOS uses lowercase tokens (`off`/`low`/…); Android `ThinkingLevel.name`
     *  is uppercase. Centralised so reader/writer round-trip identical strings. */
    private fun thinkingLevelToToken(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF -> "off"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] GPT-5.6 higher tiers.
        ThinkingLevel.MAX -> "max"
        ThinkingLevel.ULTRA -> "ultra"
    }

    private fun thinkingLevelFromToken(token: String): ThinkingLevel? = when (token) {
        "off" -> ThinkingLevel.OFF
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        else -> null
    }

    /** Convert the `model_binding` JSON the chat layer writes into the
     *  `entry:<uuid>` / `group:<id>` form the iOS minis-config surface uses.
     *  Returns empty string when the session has no explicit binding (the
     *  default group/entry fallback applies on next load). */
    private fun formatBinding(bindingJson: String?): String {
        if (bindingJson.isNullOrEmpty()) return ""
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "entry" -> {
                    val id = obj.optString("entryId")
                    if (id.isEmpty()) "" else "entry:$id"
                }
                "group" -> {
                    val id = obj.optString("groupId")
                    if (id.isEmpty()) "" else "group:$id"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // -- Appearance & display --

    private fun registerAppearance(r: ConfigRegistry, context: Context) {
        // Source-of-truth lives in `appearance_prefs` (see
        // ui/settings/AppearanceScreen.kt PREF_APPEARANCE).
        val prefs = context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.theme",
                displayName = "Theme",
                description = "Light / Dark / System color scheme.",
                prefs = prefs,
                key = "theme_mode",
                cases = listOf("system", "light", "dark"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.launchSession",
                displayName = "Launch behaviour",
                description = "What the app opens on cold start: auto, the last session, a new chat, or the home screen.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_LAUNCH_SESSION,
                cases = listOf("auto", "lastSession", "newChat", "home"),
                defaultIndex = 0,
            )
        )
        // T-chat-title-pill: sticky session-title pill on the chat screen.
        // Default ON; key string deliberately matches iOS so a future
        // cross-platform settings sync round-trips identically.
        r.register(
            PrefsBoolField(
                path = "appearance.show_chat_title",
                displayName = "Show chat title pill",
                description = "Show a sticky title pill at the top of the chat while scrolling through history.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE,
                defaultValue = true,
            )
        )
        r.register(
            PrefsStringField(
                path = "appearance.language",
                displayName = "App language",
                // T311: aligned with iOS appearance.language help wording.
                description = "BCP-47 code (zh-Hans, en, ja, …) or empty for system.",
                prefs = prefs,
                key = "app_language",
                defaultValue = "",
                maxLength = 16,
            )
        )
    }

    // -- Chat / input --

    private fun registerChat(r: ConfigRegistry, context: Context) {
        // [T-backup-dead-key-fix] These two fields previously wrote to a
        // `minis_settings` file with keys `return_key_behavior` /
        // `keep_screen_awake_during_tasks` that NOTHING reads: AppearanceScreen
        // and the runtime read `appearance_prefs` with the iOS-aligned keys
        // `returnKeyBehavior` / `keepScreenAwakeDuringTasks`. A minis-config
        // write or a backup-restore therefore landed in a dead file and never
        // took effect. Repoint to the same SharedPreferences file+keys the UI
        // uses, via the shared constants so the two can't drift apart again.
        val prefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "chat.returnKey",
                displayName = "Return key behavior",
                description = "What the on-screen Return key does in the chat box.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR,
                cases = listOf("newline", "send"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.keepScreenAwake",
                displayName = "Keep screen awake during tasks",
                description = "Prevents auto-lock while the agent is busy.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE,
                defaultValue = false,
            )
        )
        // (No autoFocusAfterReply entry here — it lives in
        // PREF_APPEARANCE alongside the visible AppearanceScreen toggle.
        // Registered further down in this method where the
        // appearance-prefs section starts.)
        // T311: chat.toolPreview / inputFontSize / messageFontSize live in
        // `appearance_prefs` — same SharedPreferences AppearanceScreen.kt
        // reads/writes, so flipping via minis-config flows back into the
        // settings UI and into ChatScreen's `OnSharedPreferenceChangeListener`
        // (Compose recomposes immediately, no manual cache invalidation
        // needed on Android — there is no analogue to iOS
        // FontSettings.messageBaseScale .Published cache).
        val appearancePrefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
                displayName = "Tool preview",
                description = "Show live preview during agent tool execution.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_TOOL_PREVIEW,
                defaultValue = false,
            )
        )
        // Whole floating tool-status bar visibility (independent of the
        // thumbnail preview above). Default ON keeps existing behavior.
        r.register(
            PrefsBoolField(
                path = "appearance.showToolStatusBar",
                displayName = "Tool status bar",
                description = "When ON, the floating bar above the composer while the agent calls tools is shown (status text, spinner, and thumbnail preview). Turn OFF to hide it entirely.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_TOOL_STATUS_BAR,
                defaultValue = true,
            )
        )
        // [T-keyboard-auto-pop default flip] On by default — iOS gates
        // the existing post-stream auto-focus behind this. Android
        // currently does not auto-focus on stream end (it only
        // auto-focuses on brand-new sessions starting with "__new__"),
        // so on this platform the setting is registered for parity.
        // When a future Android change adds stream-end auto-focus, it
        // should read this same pref key.
        r.register(
            PrefsBoolField(
                path = "chat.autoFocusAfterReply",
                displayName = "Auto-focus input after reply",
                description = "When ON, the keyboard pops up automatically after the model finishes a reply so the input is ready for a follow-up. Turn OFF if you prefer to read the response without an unexpected keyboard.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY,
                defaultValue = true,
            )
        )
        r.register(fontScaleField(
            path = "chat.inputFontSize",
            displayName = "Input font size",
            description = "Font size for the chat composer text editor.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT,
        ))
        r.register(fontScaleField(
            path = "chat.messageFontSize",
            displayName = "Message font size",
            description = "Base font size for assistant / user message bodies (markdown). Setting this invalidates rendered markdown caches.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_MESSAGE,
        ))
        // The three below were settable in AppearanceScreen but never
        // registered, so they were invisible to minis-config AND silently
        // absent from every backup (ConfigBackup walks the registry).
        r.register(fontScaleField(
            path = "appearance.appFontSize",
            displayName = "App font size",
            description = "Base font size for app chrome outside message bodies (lists, settings, labels).",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_APP_BASE,
        ))
        r.register(
            PrefsBoolField(
                path = "chat.autoExpandThinking",
                displayName = "Auto-expand thinking",
                description = "When ON, a model's reasoning block starts expanded instead of collapsed.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING,
                defaultValue = true,
            )
        )
        // Chat action customization (Settings → Appearance → Chat Menu).
        // The action pool spans the top-right "..." menu AND the history-drawer
        // footer. Each stable key contributes two independent bool fields:
        //   appearance.chatMenu.<key>      — "..." menu visibility
        //   appearance.chatMenuPin.<key>   — footer pin
        // plus two comma-separated order fields. All live in appearance_prefs so
        // they round-trip through minis-config AND every local backup
        // automatically (ConfigBackup walks the registry). Defaults keep the
        // eight original menu entries visible and pin Token Usage + Settings to
        // the footer — identical to the pre-customization UI.
        for (entryKey in ChatMenuPrefs.ALL_ENTRIES) {
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.visibilityPath(entryKey),
                    displayName = "Chat menu: show $entryKey",
                    description = "When ON, the \"$entryKey\" entry is shown in the chat \"...\" menu.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.visibilityKey(entryKey),
                    defaultValue = ChatMenuPrefs.defaultVisible(entryKey),
                )
            )
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.pinPath(entryKey),
                    displayName = "Chat footer: pin $entryKey",
                    description = "When ON, the \"$entryKey\" action is pinned to the history-drawer footer.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.pinKey(entryKey),
                    defaultValue = ChatMenuPrefs.defaultPinned(entryKey),
                )
            )
        }
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.ORDER_PATH,
                displayName = "Chat menu order",
                description = "Comma-separated stable keys defining the display order of customizable chat \"...\" menu entries. Unknown keys are ignored; missing keys fall back to their default position.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.ORDER_KEY,
                defaultValue = ChatMenuPrefs.DEFAULT_ORDER.joinToString(","),
                maxLength = 512,
            )
        )
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.PIN_ORDER_PATH,
                displayName = "Chat footer pin order",
                description = "Comma-separated stable keys defining the display order of actions pinned to the history-drawer footer. Unknown keys are ignored; missing pinned keys fall back to their default position. Settings is always anchored last when pinned.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.PIN_ORDER_KEY,
                defaultValue = ChatMenuPrefs.DEFAULT_ORDER.joinToString(",") +
                    "," + ChatMenuPrefs.TOKEN_USAGE + "," + ChatMenuPrefs.SETTINGS,
                maxLength = 512,
            )
        )
        // Attach ("+") menu customization (Settings → Appearance → Chat Menu).
        // Independent domain from the "..." menu pool: three attach actions each
        // get one visibility bool plus one order string, all in appearance_prefs
        // so they round-trip through minis-config and local backups too.
        for (entryKey in AttachActionCatalog.DEFAULT_ORDER) {
            r.register(
                PrefsBoolField(
                    path = ChatMenuPrefs.attachVisibilityPath(entryKey),
                    displayName = "Chat attach: show $entryKey",
                    description = "When ON, the \"$entryKey\" attach action is shown in the composer \"+\" menu.",
                    prefs = appearancePrefs,
                    key = ChatMenuPrefs.attachVisibilityKey(entryKey),
                    defaultValue = true,
                )
            )
        }
        r.register(
            PrefsStringField(
                path = ChatMenuPrefs.ATTACH_ORDER_PATH,
                displayName = "Chat attach order",
                description = "Comma-separated stable keys defining the display order of composer attach \"+\" menu actions. Unknown keys are ignored; missing keys fall back to their default position.",
                prefs = appearancePrefs,
                key = ChatMenuPrefs.ATTACH_ORDER_KEY,
                defaultValue = AttachActionCatalog.DEFAULT_ORDER.joinToString(","),
                maxLength = 256,
            )
        )
        // Top-bar pinned-button visibility (independent of the "..." menu pool).
        r.register(
            PrefsBoolField(
                path = "appearance.topBar.inputHistory",
                displayName = "Top bar: show Input History button",
                description = "When ON, a list-bullet icon appears in the top bar alongside New Chat, " +
                    "giving one-tap access to a searchable history of all messages in the session. " +
                    "Hiding it does not disable the feature — it can still be re-enabled here.",
                prefs = appearancePrefs,
                key = "topBar.inputHistory.visible",
                defaultValue = true,
            )
        )
        // Composer model-picker button visibility (independent fixed slot).
        r.register(
            PrefsBoolField(
                path = "appearance.composer.modelPickerButton",
                displayName = "Composer: show model-picker button",
                description = "When ON, the up-arrow circle button appears at the left of the input row " +
                    "for one-tap access to the model picker. Hiding it does not disable the feature — " +
                    "the model name in the top-right subtitle stays tappable and opens the same picker.",
                prefs = appearancePrefs,
                key = "composer.modelPickerButton.visible",
                defaultValue = true,
            )
        )
    }

    /** Mirrors iOS `fontScaleField` — exposes the integer scale level
     *  (-2..3 in AppearanceScreen.kt) as a string token (xSmall / small /
     *  default / medium / large / extraLarge) so reads round-trip the same
     *  vocabulary as `chat.inputFontSize` / `appearance.appFontSize` on
     *  iOS. Centralised so both Android axes share one factory. */
    private fun fontScaleField(
        path: String,
        displayName: String,
        description: String,
        prefs: android.content.SharedPreferences,
        key: String,
    ): ConfigField {
        val cases = listOf("xSmall", "small", "default", "medium", "large", "extraLarge")
        return ClosureField(
            path = path,
            displayName = displayName,
            description = description,
            valueSchema = ConfigSchema.StrEnum(cases),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val level = prefs.getInt(key, 0)
                val token = when (level) {
                    -2 -> "xSmall"
                    -1 -> "small"
                    0 -> "default"
                    1 -> "medium"
                    2 -> "large"
                    3 -> "extraLarge"
                    else -> "default"
                }
                ConfigValue.Str(token)
            },
            writer = { v ->
                val token = (v as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                val level = when (token) {
                    "xSmall" -> -2
                    "small" -> -1
                    "default" -> 0
                    "medium" -> 1
                    "large" -> 2
                    "extraLarge" -> 3
                    else -> throw ConfigError.InvalidValue("Unknown font scale: $token")
                }
                prefs.edit().putInt(key, level).apply()
            },
        )
    }

    // -- Background --

    private fun registerBackground(r: ConfigRegistry, context: Context, isDynamicIslandCapable: Boolean) {
        val prefs = context.getSharedPreferences("background_settings", Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "background.enhanced",
                displayName = "Enhanced background execution",
                description = "Keep agent tasks running when the app is backgrounded.",
                prefs = prefs,
                key = "enhanced_background_execution",
                defaultValue = false,
                risk = ConfigRisk.SENSITIVE,
            )
        )
        r.register(
            PrefsBoolField(
                path = "background.notifications",
                displayName = "Background notifications",
                description = "Post a system notification when long-running tasks complete.",
                prefs = prefs,
                key = "background_notifications_enabled",
                defaultValue = true,
            )
        )
        // [T-android-config-feature-unavailable] Live Updates / "dynamic
        // island". Settings → Background renders this toggle DISABLED with an
        // "unsupported" footer unless the device is capable, so minis-config
        // must not be a side door around that gate: on an incapable device the
        // path stays registered (help/list still describe it) but every read
        // and write is refused with `feature_unavailable`.
        //
        // Capability = Android 16+ AND the per-app Live-Updates grant, probed
        // via the same capability probe the UI uses. The grant can be
        // revoked at runtime, so the probe runs at REGISTRATION time here and
        // the registry is rebuilt on the paths that re-register — matching how
        // the UI re-probes on resume. Mirrors iOS gating background.liveActivity
        // on isLiveActivitySupported (dba46d42).
        //
        // Writes go through the same SharedPreferences file+key the repository
        // reads (background_settings / dynamicIslandEnabled), so a change made
        // here is picked up by AgentForegroundService's overlay/promoted
        // mutual-exclusion logic exactly like a UI toggle.
        val dynamicIslandField = PrefsBoolField(
            path = "background.dynamicIsland",
            displayName = "Live Updates (dynamic island)",
            description = "Show agent progress in the Android 16 Live Updates status chip.",
            prefs = prefs,
            key = "dynamicIslandEnabled",
            defaultValue = false,
        )
        r.register(
            if (isDynamicIslandCapable) {
                dynamicIslandField
            } else {
                UnavailableField(
                    dynamicIslandField,
                    "Live Updates requires Android 16 or newer plus the per-app " +
                        "Live Updates permission; this device doesn't support it.",
                )
            }
        )
    }

    // -- Logs --

    private fun registerLogs(r: ConfigRegistry, context: Context) {
        // T311: route through AppLogger so a write actually starts/stops the
        // capture pipeline. The previous PrefsBoolField wrote a sibling
        // `logging`/`logging_enabled` key that AppLogger never reads
        // (AppLogger uses `logging_prefs`/`logging_enabled`), so toggling via
        // minis-config did nothing. Default mirrors AppLogger's own default
        // (false — opt-in), aligning with iOS.
        r.register(
            ClosureField(
                path = "logs.enabled",
                displayName = "Logging enabled",
                description = "Capture stdout/stderr to daily log files for diagnostics.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                reader = {
                    ConfigValue.Bool(com.openminis.app.logging.AppLogger.isEnabled(context))
                },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.logging.AppLogger.setEnabled(context, b)
                },
            )
        )
    }

    // -- Provider / Models / Groups / EnvVars collections + aggregates --

    private fun registerProviderCollections(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
    ) {
        r.register(ProvidersCollection(providerRepo, envVarRepo))
        r.register(ModelsCollection(providerRepo))
        r.register(GroupsCollection(providerRepo))
        r.register(EnvVarsCollection(envVarRepo))

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Aggregate read-only summary so `minis-config get providers`
        // returns a useful list of configured instances. Credentials
        // (apiKey / oauthToken) are deliberately omitted.
        r.register(
            ReadOnlyField(
                path = "providers",
                displayName = "LLM provider instances (summary)",
                description = "Read-only list of configured providers (id, label, type, credential type, enabled, base URL). Credentials are not included. Use `minis-config add providers <json>` to create a new provider (payload mirrors a provider-export `config` block, with optional `apiKey` as literal or `\$\$ENV_VAR`).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val instances = providerRepo.config.value.instances
                    ConfigValue.Arr(
                        instances.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "label" to ConfigValue.Str(inst.label),
                                    "providerType" to ConfigValue.Str(inst.providerType.name),
                                    "credentialType" to ConfigValue.Str(inst.credentialType.name),
                                    "isEnabled" to ConfigValue.Bool(inst.isEnabled),
                                    "customBaseURL" to (inst.customBaseURL?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                    "appendV1Suffix" to ConfigValue.Bool(inst.appendV1Suffix),
                                    "useResponsesAPI" to ConfigValue.Bool(inst.useResponsesAPI),
                                    "azureMode" to ConfigValue.Bool(inst.azureMode),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get models`
        // returns every model entry along with its provider context.
        r.register(
            ReadOnlyField(
                path = "models",
                displayName = "Model entries (summary)",
                description = "Read-only list of every model entry (entry_id, display_name, model_id, provider).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val providersById = HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelEntries.map { entry ->
                            val inst = providersById[entry.providerInstanceId]
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "entry_id" to ConfigValue.Str(entry.id),
                                    "display_name" to ConfigValue.Str(entry.model.displayName),
                                    "model_id" to ConfigValue.Str(entry.baseModel.id),
                                    "provider_id" to ConfigValue.Str(entry.providerInstanceId),
                                    "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                    "provider_type" to (inst?.let { ConfigValue.Str(it.providerType.name) } ?: ConfigValue.Null),
                                    "is_custom" to ConfigValue.Bool(entry.isCustom),
                                    "is_hidden" to ConfigValue.Bool(entry.isHidden),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get envvars`
        // returns every env var key with its non-secret metadata.
        r.register(
            ReadOnlyField(
                path = "envvars",
                displayName = "Environment variables (summary)",
                description = "Read-only list of env var keys with note and createdAt. Values are never exposed.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        envVarRepo.entries.value.map { e ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "key" to ConfigValue.Str(e.key),
                                    "note" to ConfigValue.Str(e.note),
                                    "created_at" to ConfigValue.Str(isoFormatter.format(Date(e.createdAt))),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Privacy Mode toggle. Read/write so the user can ask the
        // agent to flip it without diving into Settings ("turn off
        // privacy mode for the next command"). Routed through
        // EnvVarPrivacyStore.setEnabled so the StateFlow fires and
        // the Compose Switch stays in sync. Default ON; risk
        // SENSITIVE because turning it OFF means the next shell
        // execute can leak unmasked secrets to the model.
        r.register(
            ClosureField(
                path = "envvars.privacyMode",
                displayName = "Privacy Mode",
                description = "When ON, env-var values that appear in shell-execute output are masked before reaching the model. Turning OFF lets the model see raw values — only do this if the user explicitly asked.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Bool(com.openminis.app.data.EnvVarPrivacyStore.isEnabled) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.data.EnvVarPrivacyStore.setEnabled(b)
                },
            )
        )

        // Aggregate read-only summary so `minis-config get groups`
        // returns every model group with its expanded entries.
        r.register(
            ReadOnlyField(
                path = "groups",
                displayName = "Model groups (summary)",
                description = "Read-only list of model groups with entries expanded.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val entriesById =
                        HashMap<String, com.openminis.app.data.model.ModelEntry>(cfg.modelEntries.size)
                    for (e in cfg.modelEntries) entriesById[e.id] = e
                    val providersById =
                        HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelGroups.map { g ->
                            val entries = g.memberEntryIds.map { entryId ->
                                val e = entriesById[entryId]
                                if (e == null) {
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "missing" to ConfigValue.Bool(true),
                                        )
                                    )
                                } else {
                                    val inst = providersById[e.providerInstanceId]
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "display_name" to ConfigValue.Str(e.model.displayName),
                                            "model_id" to ConfigValue.Str(e.baseModel.id),
                                            "provider_id" to ConfigValue.Str(e.providerInstanceId),
                                            "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                        )
                                    )
                                }
                            }
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "strategy" to ConfigValue.Str(g.strategy.name),
                                    "fallback_strategy" to ConfigValue.Str(g.fallbackStrategy.name),
                                    "entries" to ConfigValue.Arr(entries),
                                )
                            )
                        }
                    )
                },
            )
        )
    }

    // -- Defaults — agent loop entries / groups, default primary/sub group --

    private fun registerDefaults(r: ConfigRegistry, repo: ProviderRepository) {
        r.register(
            ClosureField(
                path = "defaults.primaryGroup",
                displayName = "Default primary group",
                description = "Group used by new sessions. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.defaultPrimaryGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultPrimaryGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultPrimaryGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.subGroup",
                displayName = "Default sub group",
                description = "Secondary group for fallback. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = { ConfigValue.Str(repo.defaultSubGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultSubGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultSubGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopEntries",
                displayName = "Agent loop model entries",
                description = "Model entries available via minis-model-use. Replace with full list to set; use .append/.remove for single-element ops.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Filter out ids that no
                    // longer match a real model entry (legacy bare-UUID / deleted
                    // rows). Surfacing them confuses minis-model-use and the
                    // round-trip set/append flow; skip silently.
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopModelEntryIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown ids
                    // instead of hard-failing the whole write. `.append` reads the
                    // existing array (which may carry legacy bare-UUID dirt) and
                    // re-writes the full list; a hard fail rejected the valid new
                    // entry along with the historical dirt. Filtering cleans the
                    // dirt and lets the valid entries through; one diagnostic line
                    // per skipped id leaves a trace without blocking the op.
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopEntries id: $bad"
                        )
                    }
                    repo.setAgentLoopEntryIds(cleanIds)
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopGroups",
                displayName = "Agent loop groups",
                description = "Whole groups exposed via minis-model-use.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Skip ids that no
                    // longer match a real group (deleted / legacy bare UUID).
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopGroupIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown
                    // group ids (same rationale as agentLoopEntries above).
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopGroups id: $bad"
                        )
                    }
                    repo.setAgentLoopGroupIds(cleanIds)
                },
            )
        )
    }

    // -- Soul (SOUL.md personality) --
    //
    // [T-soul-md-minisconfig] Exposes the editable subset of SOUL.md
    // (name / style / lang / body) as `soul.*` config paths. The on-disk
    // YAML emoji field is intentionally NOT registered — matches the
    // iOS rollback where ✨ is locked as the identity icon.
    //
    // Writes go through the standard PendingConfigChange / user-confirm
    // gate (registered with [ConfigRisk.SENSITIVE]); only the *approved*
    // writer touches disk. Each writer reads the current SOUL.md so
    // unrelated fields (incl. the on-disk `emoji` value that survives
    // for round-trip) are preserved when one field changes.

    private fun registerSoul(r: ConfigRegistry, context: Context) {
        // Length cap is language-aware now (Chinese ≤ 800 chars OR
        // English ≤ 500 words); see [com.openminis.app.agent.SoulStore.isOverLimit].
        // No schema-level maxLength — a fixed char count would be wrong
        // for either language. The writer below rejects over-limit with a
        // precise [ConfigError.InvalidValue].

        // Re-read SOUL.md every call so concurrent writes from Settings
        // UI don't race with minis-config writes. Parse falls back to
        // the canonical default content if the file was somehow deleted
        // between launches — same behavior as the Settings page.
        fun loadCurrent(): com.openminis.app.agent.SoulFile =
            com.openminis.app.agent.SoulStore.load(context)
                ?: com.openminis.app.agent.SoulMDParser.parse(
                    com.openminis.app.agent.SoulStore.DEFAULT_CONTENT
                )

        fun saveCurrent(file: com.openminis.app.agent.SoulFile) {
            com.openminis.app.agent.SoulStore.save(context, file)
        }

        r.register(
            ClosureField(
                path = "soul.name",
                displayName = "Soul name",
                description = "The agent's name as shown in chat bubble headers and as the system-prompt identity. Trimmed; empty falls back to \"Minis\".",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.name)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val name = raw.trim().ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.name
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(name = name)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.style",
                displayName = "Soul style",
                description = "Short style descriptor used as a hint in the system prompt (e.g. \"Warm, direct, opinionated\").",
                valueSchema = ConfigSchema.Str(maxLength = 256),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.style)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val style = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(style = style)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.lang",
                displayName = "Soul language",
                description = "Preferred response language: auto / zh / en. Older SOUL.md files missing the field are upgraded on first write.",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    val cur = loadCurrent()
                    // Older files may be missing the lang frontmatter
                    // key — parser already substitutes DEFAULT.lang in
                    // that case, so we just round-trip it.
                    ConfigValue.Str(cur.metadata.lang.ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.lang
                    })
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val lang = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(lang = lang)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.body",
                displayName = "Soul personality prompt",
                description = "Personality / voice instructions injected as a block in the system prompt. Length cap is language-aware: Chinese ≤ ${com.openminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT} chars OR English ≤ ${com.openminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT} words (rule picked by the majority language). The writer also rejects prompt-injection patterns (\"ignore previous instructions\" etc.) — keep this to genuine character / tone guidance.",
                // No schema maxLength because either character or word
                // count is wrong for the *other* language. Both checks
                // (length + injection) run in the writer below.
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                reader = {
                    ConfigValue.Str(loadCurrent().body)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    if (com.openminis.app.agent.SystemPromptBuilder
                            .containsInjectionPattern(raw)
                    ) {
                        throw ConfigError.InvalidValue(
                            "Body contains a prompt-injection pattern (\"ignore/disregard/forget … previous/prior instructions\"). SOUL.md is for personality, not instructions to the model."
                        )
                    }
                    val check = com.openminis.app.agent.SoulStore.isOverLimit(raw)
                    when (check) {
                        is com.openminis.app.agent.SoulBodyLimitCheck.Ok -> Unit
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitChinese ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.chars} Chinese chars exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} chars, or rewrite mostly in English to use the ≤ ${com.openminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT}-word cap."
                            )
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitEnglish ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.words} English words exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} words, or rewrite mostly in Chinese to use the ≤ ${com.openminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT}-char cap."
                            )
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(body = raw))
                },
            )
        )
    }
}
