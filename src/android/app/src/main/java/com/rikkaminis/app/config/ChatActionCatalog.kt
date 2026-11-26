package com.rikkaminis.app.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import com.rikkaminis.app.R

/**
 * Static metadata for a single action in the customizable chat action pool
 * (top-right "..." menu + history-drawer footer).
 *
 * The [key] is the same stable, non-localized id used as the SharedPreferences
 * key and the order-list id in [ChatMenuPrefs]. [titleRes] and [icon] are the
 * single source for both the settings list and the footer — ChatScreen,
 * ChatMenuSettingsScreen and ChatHistoryDrawer no longer maintain three
 * drifting icon/title maps.
 */
data class ChatActionSpec(
    val key: String,
    val titleRes: Int,
    val icon: ImageVector,
    val defaultMenuVisible: Boolean,
    val defaultPinned: Boolean,
)

/**
 * Catalog of every action in the customizable chat action pool. Runtime
 * availability is NOT part of the spec — it is computed separately by
 * [ChatActionAvailability.isAvailable] so a conditionally-unavailable entry never
 * accidentally deletes the user's persisted pin/visibility choice.
 */
object ChatActionCatalog {
    val ALL: List<ChatActionSpec> = listOf(
        ChatActionSpec(ChatMenuPrefs.TERMINAL, R.string.chat_menu_open_terminal, Icons.Outlined.Terminal, true, false),
        ChatActionSpec(ChatMenuPrefs.BROWSER, R.string.chat_menu_open_browser, Icons.Outlined.Language, true, false),
        ChatActionSpec(ChatMenuPrefs.CHAT_FILES, R.string.chat_menu_browse_chat_files, Icons.Outlined.Description, true, false),
        ChatActionSpec(ChatMenuPrefs.COMPACT, R.string.chat_menu_compact, Icons.Outlined.Compress, true, false),
        ChatActionSpec(ChatMenuPrefs.THINKING, R.string.chat_menu_thinking, Icons.Outlined.Lightbulb, true, false),
        // [chat-menu-model-toggles-1126] The two model-conditional toggles are
        // pool members like any other: the user decides whether they belong on
        // the "..." menu / drawer footer, and `ChatActionAvailability` decides
        // whether the ACTIVE model can use them. Bolt matches the nav-bar ⚡
        // status badge; Timer reads as the 1-hour cache TTL.
        ChatActionSpec(ChatMenuPrefs.FAST_MODE, R.string.chat_menu_fast_mode, Icons.Outlined.Bolt, true, false),
        ChatActionSpec(ChatMenuPrefs.ENHANCED_CACHE, R.string.chat_menu_enhanced_cache, Icons.Outlined.Timer, true, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_SKILLS, R.string.session_skills_title, Icons.Outlined.Build, true, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_MCPS, R.string.session_mcps_title, Icons.Outlined.Extension, true, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_MEMORY, R.string.session_memory_title, Icons.Outlined.Psychology, true, false),
        ChatActionSpec(ChatMenuPrefs.SLASH_COMMANDS, R.string.chat_menu_slash_commands, Icons.Outlined.Keyboard, true, false),
        ChatActionSpec(ChatMenuPrefs.EXPORT, R.string.sessionlist_export, Icons.Outlined.Share, true, false),
        ChatActionSpec(ChatMenuPrefs.TOKEN_USAGE, R.string.settings_token_usage, Icons.Outlined.DataUsage, false, true),
        ChatActionSpec(ChatMenuPrefs.SETTINGS, R.string.settings, Icons.Outlined.Settings, false, true),
    )

    fun spec(key: String): ChatActionSpec? = ALL.firstOrNull { it.key == key }
}
