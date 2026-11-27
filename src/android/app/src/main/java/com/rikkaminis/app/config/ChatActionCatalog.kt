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
        ChatActionSpec(ChatMenuPrefs.TERMINAL, R.string.chat_menu_open_terminal, Icons.Outlined.Terminal, false, false),
        ChatActionSpec(ChatMenuPrefs.BROWSER, R.string.chat_menu_open_browser, Icons.Outlined.Language, false, false),
        ChatActionSpec(ChatMenuPrefs.CHAT_FILES, R.string.chat_menu_browse_chat_files, Icons.Outlined.Description, false, false),
        ChatActionSpec(ChatMenuPrefs.COMPACT, R.string.chat_menu_compact, Icons.Outlined.Compress, false, false),
        ChatActionSpec(ChatMenuPrefs.THINKING, R.string.chat_menu_thinking, Icons.Outlined.Lightbulb, false, false),
        // [chat-menu-model-toggles-1126] Menu visibility defaults to hidden;
        // runtime availability remains independent and does not remove the
        // action from the settings catalog or disable its underlying feature.
        ChatActionSpec(ChatMenuPrefs.FAST_MODE, R.string.chat_menu_fast_mode, Icons.Outlined.Bolt, false, false),
        ChatActionSpec(ChatMenuPrefs.ENHANCED_CACHE, R.string.chat_menu_enhanced_cache, Icons.Outlined.Timer, false, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_SKILLS, R.string.session_skills_title, Icons.Outlined.Build, false, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_MCPS, R.string.session_mcps_title, Icons.Outlined.Extension, false, false),
        ChatActionSpec(ChatMenuPrefs.SESSION_MEMORY, R.string.session_memory_title, Icons.Outlined.Psychology, false, false),
        ChatActionSpec(ChatMenuPrefs.SLASH_COMMANDS, R.string.chat_menu_slash_commands, Icons.Outlined.Keyboard, false, false),
        ChatActionSpec(ChatMenuPrefs.EXPORT, R.string.sessionlist_export, Icons.Outlined.Share, false, false),
        ChatActionSpec(ChatMenuPrefs.TOKEN_USAGE, R.string.settings_token_usage, Icons.Outlined.DataUsage, false, true),
        ChatActionSpec(ChatMenuPrefs.SETTINGS, R.string.settings, Icons.Outlined.Settings, false, true),
    )

    fun spec(key: String): ChatActionSpec? = ALL.firstOrNull { it.key == key }
}
