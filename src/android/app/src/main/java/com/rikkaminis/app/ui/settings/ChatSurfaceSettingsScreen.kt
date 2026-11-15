package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.data.ChatTuningPrefs

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * [composer-glass-1115] Chat appearance — the sub-page behind
 * Settings → Appearance → Chat appearance.
 *
 * Why a sub-page and not a row on the Appearance page: the switch changes the
 * look of the whole chat surface (composer card + top bar), it is a secondary
 * option, and the main Appearance page is kept to one switch per primary
 * decision. It is also the natural home for the chat-surface options that do
 * not exist yet (a chat wallpaper / gradient would land here, not on the main
 * page).
 *
 * What is NOT here: the blur radius and the glass density. Those are numbers
 * you tune while looking at the result, so they live in Appearance → Chat
 * Tuning next to the other composer knobs — this page is the on/off decision
 * only, and its footer says where the numbers are.
 *
 * The switch covers BOTH surfaces on purpose (rikkahub shares one
 * `chatInputHaze` between its input bar and its top-bar capsule): one look,
 * one switch, one cost.
 */
@Composable
internal fun ChatSurfaceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var glassEnabled by remember { mutableStateOf(ChatTuningPrefs.composerGlassEnabled(context)) }

    // External writes (`minis-config set chat.composerGlassEnabled`, a restored
    // backup) must move the switch too — the same refresh contract every other
    // settings screen in this package honours.
    DisposableEffect(context) {
        val prefs = ChatTuningPrefs.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ChatTuningPrefs.KEY_COMPOSER_GLASS_ENABLED) {
                glassEnabled = ChatTuningPrefs.composerGlassEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    SettingsScaffold(
        title = stringResource(R.string.chat_surface_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.chat_surface_section_glass),
                footer = stringResource(R.string.chat_surface_glass_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    iconColor = Color(0xFF5AC8FA),
                    title = stringResource(R.string.chat_surface_glass_title),
                    subtitle = stringResource(R.string.chat_surface_glass_subtitle),
                    checked = glassEnabled,
                    onCheckedChange = {
                        glassEnabled = it
                        ChatTuningPrefs.prefs(context).edit()
                            .putBoolean(ChatTuningPrefs.KEY_COMPOSER_GLASS_ENABLED, it)
                            .apply()
                    },
                    showDivider = false,
                )
            }
        }
    }
}
