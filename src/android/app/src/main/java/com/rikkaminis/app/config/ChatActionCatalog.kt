package com.rikkaminis.app.config

import androidx.compose.material.icons.Icons
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
 * [isChatActionAvailable] so a conditionally-unavailable repository never
 * accidentally deletes the user's persisted pin/visibility choice.
 */
object ChatActionCatalog {
    val ALL: List<ChatActionSpec> = listOf(
        ChatActionSpec(ChatMenuPrefs.TERMINAL, R.string.chat_menu_open_terminal, Icons.Outlined.Terminal, true, false),
        ChatActionSpec(ChatMenuPrefs.BROWSER, R.string.chat_menu_open_browser, Icons.Outlined.Language, true, false),
        ChatActionSpec(ChatMenuPrefs.CHAT_FILES, R.string.chat_menu_browse_chat_files, Icons.Outlined.Description, true, false),
        ChatActionSpec(ChatMenuPrefs.COMPACT, R.string.chat_menu_compact, Icons.Outlined.Compress, true, false),
        ChatActionSpec(ChatMenuPrefs.THINKING, R.string.chat_menu_thinking, Icons.Outlined.Lightbulb, true, false),
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

/**
 * Runtime availability of a chat action — mirrors the gates the "..." menu
 * applies while rendering. Conditional actions (Skills / MCPs / Memory) only
 * render when their backing repository is present (Memory additionally needs
 * the session's live [menuMemoryEnabled]); every other action is always
 * available. Availability only filters the RENDER — it never mutates the
 * user's persisted visibility / pin config, so a temporarily-unavailable
 * action reappears automatically once its condition returns.
 */
fun isChatActionAvailable(
    key: String,
    skillsAvailable: Boolean,
    mcpsAvailable: Boolean,
    memoryAvailable: Boolean,
): Boolean = when (key) {
    ChatMenuPrefs.SESSION_SKILLS -> skillsAvailable
    ChatMenuPrefs.SESSION_MCPS -> mcpsAvailable
    ChatMenuPrefs.SESSION_MEMORY -> memoryAvailable
    else -> true
}