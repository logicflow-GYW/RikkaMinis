package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisTextButton

import android.content.Context
import android.content.SharedPreferences
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ScreenLockPortrait
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import com.rikkaminis.app.ui.theme.ChatColors

// -- Preference Keys --
const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_THEME_MODE = "theme_mode"            // 0=System, 1=Light, 2=Dark
const val KEY_LAUNCH_SESSION = "launch_session"    // 0=Auto, 1=LastSession, 2=NewChat, 3=Safe
// iOS-aligned key names — match `@AppStorage("returnKeyBehavior")` and
// `@AppStorage("keepScreenAwakeDuringTasks")` in ContentView.swift so
// future cross-platform sync (if it ever lands) reads the same values.
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"  // Int 0=Newline (default), 1=Send
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"  // Boolean, default false
const val KEY_TOOL_PREVIEW = "tool_preview"        // Boolean, default false
// Whole floating tool-status bar visibility. When false the entire
// FloatingToolStatusBar (status text, spinner, thumbnail) is hidden and its
// height reserve is skipped. Independent of KEY_TOOL_PREVIEW (which only
// controls the thumbnail window ON the bar). Default ON keeps existing
// behavior. Key name mirrors iOS `appearance.show_tool_status_bar` for future
// cross-platform sync.
const val KEY_TOOL_STATUS_BAR = "appearance.show_tool_status_bar"  // Boolean, default true
// T-chat-title-pill: shows a sticky session-title pill above the chat list
// while the user scrolls back through history. Cross-platform key name
// (matches iOS @AppStorage("appearance.show_chat_title")) so future config
// sync reads the same value.
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"  // Boolean, default true
// [T-thinking-auto-expand-toggle] When true a NEW streaming thinking block
// auto-expands while the model reasons; when false (default) it stays
// collapsed until tapped. Default is collapsed — the historical auto-expand
// behavior was flipped to collapsed-by-default (commit c2666e72). Key name
// mirrors iOS `@AppStorage("chat.autoExpandThinking")` so future config sync
// reads the same value. Read at block-mount time in ThinkingBlock.
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"  // Boolean, default false (thinking starts collapsed)
const val KEY_FONT_CHAT_INPUT = "font_chat_input"  // Int scale level -2..3
const val KEY_FONT_MESSAGE = "font_message"        // Int scale level -2..3
const val KEY_FONT_APP_BASE = "font_app_base"      // Int scale level -2..3
const val KEY_LANGUAGE = "app_language"             // "" = system, "en", "zh", "ja", "ko", "fr", "de", "ru"

/** True when Enter (without Shift) should send the message. iOS calls this
 *  `returnKeyBehavior == 1`. Default 0 = Enter inserts a newline (matches
 *  iOS shipping default + most desktop chat clients). */
fun returnKeySendsMessage(context: Context): Boolean =
    getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

/** Default ON — pill shows up on scroll for everyone unless explicitly disabled. */
fun showChatTitleEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

/** [T-thinking-auto-expand-toggle] Default OFF = collapsed by default: a new
 *  streaming thinking block starts collapsed. ON = it opens expanded while the
 *  model reasons, then collapses when it ends. The user taps to expand either way. */
fun autoExpandThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, false)

/** Font scale levels matching iOS: XS(-2) Small(-1) Default(0) Medium(1) Large(2) XL(3) */
private val fontScaleLabels = listOf("XS", "Small", "Default", "Medium", "Large", "XL")
private val fontScaleValues = listOf(-2, -1, 0, 1, 2, 3)
private val fontScaleMultipliers = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

private data class LanguageOption(val code: String, val flag: String, val label: String)
// "System" label is resolved at call-site via stringResource so it follows
// the user's chosen UI language. The remaining entries are language self-names
// and stay as literals \u2014 Chinese is always "\u7B80\u4F53\u4E2D\u6587", regardless of UI locale.
private val languageOptions = listOf(
    LanguageOption("", "\uD83C\uDF10", ""),
    LanguageOption("en", "\uD83C\uDDFA\uD83C\uDDF8", "English"),
    LanguageOption("zh", "\uD83C\uDDE8\uD83C\uDDF3", "简体中文"),
    LanguageOption("ja", "\uD83C\uDDEF\uD83C\uDDF5", "日本語"),
    LanguageOption("ko", "\uD83C\uDDF0\uD83C\uDDF7", "한국어"),
    LanguageOption("fr", "\uD83C\uDDEB\uD83C\uDDF7", "Français"),
    LanguageOption("de", "\uD83C\uDDE9\uD83C\uDDEA", "Deutsch"),
    // ru: flag \uD83C\uDDF7\uD83C\uDDFA (RU), self-name \u0420\u0443\u0441\u0441\u043A\u0438\u0439 (Russkiy)
    LanguageOption("ru", "\uD83C\uDDF7\uD83C\uDDFA", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
)

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

fun fontScaleForLevel(level: Int): Float {
    val idx = fontScaleValues.indexOf(level)
    return if (idx < 0) 1.0f else fontScaleMultipliers[idx]
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onChatMenuClick: () -> Unit = {},
    onThemeChanged: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var launchSession by remember { mutableIntStateOf(prefs.getInt(KEY_LAUNCH_SESSION, 0)) }
    var returnKeyBehavior by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, false)) }
    var toolPreview by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_PREVIEW, false)) }
    var toolStatusBar by remember { mutableStateOf(prefs.getBoolean(KEY_TOOL_STATUS_BAR, true)) }
    var autoExpandThinking by remember { mutableStateOf(prefs.getBoolean(KEY_AUTO_EXPAND_THINKING, false)) }
    var showChatTitle by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_CHAT_TITLE, true)) }
    var chatInputLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var messageLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var selectedLanguage by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, "") ?: "") }

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    // [T-backup-import-refresh] OnSharedPreferenceChangeListener drives the
    // Compose state back from SharedPreferences after a write outside this
    // composable (minis-config `set`, backup-restore). Without this, the
    // remember{} snapshot is stale forever and the UI shows the old value
    // even though the underlying prefs have changed.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_THEME_MODE -> themeMode = prefs.getInt(key, 0)
                KEY_LAUNCH_SESSION -> launchSession = prefs.getInt(key, 0)
                KEY_RETURN_KEY_BEHAVIOR -> returnKeyBehavior = prefs.getInt(key, 0)
                KEY_KEEP_SCREEN_AWAKE -> keepScreenAwake = prefs.getBoolean(key, false)
                KEY_TOOL_PREVIEW -> toolPreview = prefs.getBoolean(key, false)
                KEY_TOOL_STATUS_BAR -> toolStatusBar = prefs.getBoolean(key, true)
                KEY_AUTO_EXPAND_THINKING -> autoExpandThinking = prefs.getBoolean(key, false)
                KEY_SHOW_CHAT_TITLE -> showChatTitle = prefs.getBoolean(key, true)
                KEY_FONT_CHAT_INPUT -> chatInputLevel = prefs.getInt(key, 0)
                KEY_FONT_MESSAGE -> messageLevel = prefs.getInt(key, 0)
                KEY_FONT_APP_BASE -> appBaseLevel = prefs.getInt(key, 0)
                KEY_LANGUAGE -> selectedLanguage = prefs.getString(key, "") ?: ""
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val tilePurple = Color(0xFF5856D6)
    val tileBlue = ChatColors.link
    val tileOrange = Color(0xFFFF9500)
    val tileGreen = ChatColors.success
    val tileTeal = Color(0xFF5AC8FA)

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.appearance_title), onBack = null) {

        // -- Theme --
        // Each row carries its own leading icon + tile colour, mirroring the
        // Provider / Permissions screens. Earlier only the first row had an
        // icon and rows 2–3 fell through to a 24dp Spacer, which read as a
        // visual hiccup at the section boundary.
        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            data class ThemeRow(val label: String, val icon: ImageVector, val tint: Color)
            val themeRows = listOf(
                ThemeRow(stringResource(R.string.appearance_theme_system), Icons.Outlined.BrightnessAuto, tilePurple),
                ThemeRow(stringResource(R.string.appearance_theme_light), Icons.Outlined.LightMode, tileOrange),
                ThemeRow(stringResource(R.string.appearance_theme_dark), Icons.Outlined.DarkMode, tilePurple),
            )
            themeRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = themeMode == idx,
                    onSelect = {
                        themeMode = idx
                        prefs.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < themeRows.size - 1,
                )
            }
        }

        // -- Launch Session --
        // Same per-row icon treatment as the Theme section. Bolt = Auto
        // (system picks), History = Last Session (revisit), ChatBubble =
        // New Chat (compose), Home = Home screen.
        SettingsSection(
            header = stringResource(R.string.appearance_section_launch),
            footer = stringResource(R.string.appearance_launch_footer),
        ) {
            data class LaunchRow(val label: String, val icon: ImageVector, val tint: Color)
            val launchRows = listOf(
                LaunchRow(stringResource(R.string.appearance_launch_auto), Icons.Outlined.Bolt, tileBlue),
                LaunchRow(stringResource(R.string.appearance_launch_last), Icons.Outlined.History, tileTeal),
                LaunchRow(stringResource(R.string.appearance_launch_new), Icons.Outlined.ChatBubbleOutline, tileGreen),
                LaunchRow(stringResource(R.string.appearance_launch_safe), Icons.Outlined.Shield, tileBlue),
            )
            launchRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = launchSession == idx,
                    onSelect = {
                        launchSession = idx
                        prefs.edit().putInt(KEY_LAUNCH_SESSION, idx).apply()
                    },
                    leading = {
                        androidx.compose.material3.Icon(
                            row.icon,
                            contentDescription = null,
                            tint = row.tint,
                        )
                    },
                    showDivider = idx < launchRows.size - 1,
                )
            }
        }

        // -- Return Key (mirrors iOS AppearanceSettingsView stringResource(R.string.appearance_section_return_key) section) --
        // 0=Newline (default), 1=Send. Hardware Shift+Enter always inserts a
        // newline regardless of this setting — matches iOS behavior and
        // overrides the read in ChatScreen's onKeyEvent handler.
        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
            footer = stringResource(R.string.appearance_return_key_footer),
        ) {
            data class ReturnRow(val label: String, val value: Int)
            val returnRows = listOf(
                ReturnRow(stringResource(R.string.appearance_return_key_newline), 0),
                ReturnRow(stringResource(R.string.appearance_return_key_send), 1),
            )
            returnRows.forEachIndexed { idx, row ->
                SettingsChoiceRow(
                    title = row.label,
                    selected = returnKeyBehavior == row.value,
                    onSelect = {
                        returnKeyBehavior = row.value
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, row.value).apply()
                    },
                    leading = {
                        if (idx == 0) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                                tint = tilePurple,
                            )
                        } else {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                tint = tileGreen,
                            )
                        }
                    },
                    showDivider = idx < returnRows.size - 1,
                )
            }
        }

        // -- Keep Screen Awake --
        // Holds FLAG_KEEP_SCREEN_ON on the activity window while any session
        // has an active task (mirrors iOS UIApplication.isIdleTimerDisabled
        // pattern in KeepScreenAwakeController). Default off — battery cost
        // is real and most users don't need it.
        SettingsSection(
            header = stringResource(R.string.appearance_section_keep_awake),
            footer = stringResource(R.string.appearance_keep_awake_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ScreenLockPortrait,
                iconColor = tileGreen,
                title = stringResource(R.string.appearance_keep_awake_title),
                checked = keepScreenAwake,
                onCheckedChange = {
                    keepScreenAwake = it
                    prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Tool Status Bar --
        SettingsSection(
            header = stringResource(R.string.appearance_section_tool_preview),
            footer = stringResource(R.string.appearance_tool_preview_footer),
        ) {
            // Whole-bar visibility. Independent of the preview-window toggle
            // below (which only hides the thumbnail ON this bar). When this is
            // off the entire floating status bar (text + spinner + thumbnail)
            // is hidden and reserves no space.
            SettingsSwitchRow(
                icon = Icons.Outlined.Construction,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_status_bar_title),
                checked = toolStatusBar,
                onCheckedChange = {
                    toolStatusBar = it
                    prefs.edit().putBoolean(KEY_TOOL_STATUS_BAR, it).apply()
                },
                showDivider = true,
            )
            SettingsSwitchRow(
                icon = Icons.Outlined.Visibility,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_tool_preview_title),
                checked = toolPreview,
                onCheckedChange = {
                    toolPreview = it
                    prefs.edit().putBoolean(KEY_TOOL_PREVIEW, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-thinking-auto-expand-toggle] -- Deep Thinking --
        // Whether a NEW streaming thinking block opens expanded while the model
        // reasons (ON) or stays collapsed (OFF, default). Only affects the
        // streaming auto-expand; manual taps always work either way. Mirrors iOS
        // AppearanceSettingsView "Deep Thinking" section.
        SettingsSection(
            header = stringResource(R.string.appearance_section_deep_thinking),
            footer = stringResource(R.string.appearance_auto_expand_thinking_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.Psychology,
                iconColor = tilePurple,
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpandThinking,
                onCheckedChange = {
                    autoExpandThinking = it
                    prefs.edit().putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = false,
            )
        }

        // -- Chat Title (T-chat-title-pill) --
        // Sticky session-title pill that appears at the top of the chat
        // when the user scrolls back through history. Default ON; toggle
        // also reachable via `minis-config set appearance.show_chat_title`.
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_title),
            footer = stringResource(R.string.appearance_show_chat_title_footer),
        ) {
            SettingsSwitchRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                iconColor = tileBlue,
                title = stringResource(R.string.appearance_show_chat_title),
                subtitle = stringResource(R.string.appearance_show_chat_title_subtitle),
                checked = showChatTitle,
                onCheckedChange = {
                    showChatTitle = it
                    prefs.edit().putBoolean(KEY_SHOW_CHAT_TITLE, it).apply()
                },
                showDivider = false,
            )
        }

        // [T-chat-menu-customize] -- Chat Menu --
        // One entry point to the dedicated Chat Menu screen where the user can
        // hide unused entries and drag-reorder the rest. Hiding an entry never
        // touches its underlying data or feature — it just removes it from the
        // chat "..." menu (re-enable any time). Prefs live in appearance_prefs,
        // so they round-trip through minis-config and every local backup.
        SettingsSection(
            header = stringResource(R.string.appearance_section_chat_menu),
        ) {
            SettingsRow(
                icon = Icons.Outlined.MoreVert,
                iconColor = tileTeal,
                title = stringResource(R.string.appearance_section_chat_menu),
                subtitle = stringResource(R.string.appearance_chat_menu_summary),
                onClick = onChatMenuClick,
                showChevron = true,
                showDivider = false,
            )
        }

        // -- Font Size --
        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
            footer = stringResource(R.string.appearance_font_size_footer),
        ) {
            SettingsRow(
                icon = Icons.Outlined.FormatSize,
                iconColor = tileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // -- Language --
        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
            footer = stringResource(R.string.appearance_language_footer),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.label,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val lm = context.getSystemService(LocaleManager::class.java)
                            lm?.applicationLocales = if (lang.code.isEmpty()) {
                                LocaleList.getEmptyLocaleList()
                            } else {
                                LocaleList.forLanguageTags(lang.code)
                            }
                        } else {
                            // T-n01-andmenu-l10n: pre-Tiramisu has no
                            // LocaleManager. The new language is read
                            // from SharedPreferences by
                            // [LocaleWrap.wrap] on the next
                            // attachBaseContext call, so recreate the
                            // Activity so its base Configuration picks
                            // up the change immediately.
                            (context as? android.app.Activity)?.recreate()
                        }
                    },
                    leading = {
                        Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    },
                    showDivider = idx < languageOptions.size - 1,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val currentLabel = fontScaleLabels.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}
