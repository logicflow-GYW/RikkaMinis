package com.rikkaminis.app.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rikkaminis.app.config.AttachActionCatalog
import com.rikkaminis.app.config.ChatMenuPrefs

/**
 * Live snapshot of the chat action configuration (top-right menu + drawer
 * footer), re-read on every appearance_prefs change so edits from the Chat
 * Menu settings screen, minis-config writes and backup restores apply without
 * restarting the chat screen.
 *
 * The SharedPreferences listener fires on any key change; the snapshot is
 * rebuilt from scratch (cheap: a few dozen booleans + two string splits) and
 * the remember(prefs, tick) key forces downstream recomposition.
 */
@Composable
fun rememberChatActionState(context: Context): ChatActionState {
    val prefs = ChatMenuPrefs.prefs(context)
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(prefs, tick) {
        ChatActionState(prefs)
    }
}

/**
 * Immutable snapshot of the resolved chat action orders and per-key
 * visibility / pin state at composition time. Built by reading the prefs
 * once; cheap enough to discard and rebuild on every prefs change.
 */
class ChatActionState internal constructor(prefs: SharedPreferences) {
    val menuOrder: List<String> = ChatMenuPrefs.resolveOrder(prefs)
    val footerOrder: List<String> = ChatMenuPrefs.resolvePinnedOrder(prefs)
    val attachOrder: List<String> = ChatMenuPrefs.resolveAttachOrder(prefs)
    private val visible: Map<String, Boolean> =
        ChatMenuPrefs.ALL_ENTRIES.associateWith { ChatMenuPrefs.isVisible(prefs, it) }
    private val pinned: Map<String, Boolean> =
        ChatMenuPrefs.ALL_ENTRIES.associateWith { ChatMenuPrefs.isPinned(prefs, it) }
    private val attachVisible: Map<String, Boolean> =
        AttachActionCatalog.DEFAULT_ORDER.associateWith { ChatMenuPrefs.isAttachVisible(prefs, it) }
    /** Whether the "Input History" top-bar icon is shown. Defaults to true. */
    val topBarInputHistoryVisible: Boolean = ChatMenuPrefs.isTopBarInputHistoryVisible(prefs)

    /** Whether the composer's circular model-picker button is shown. Defaults to true. */
    val composerModelPickerVisible: Boolean = ChatMenuPrefs.isComposerModelPickerVisible(prefs)

    fun isVisible(key: String): Boolean =
        visible[key] ?: ChatMenuPrefs.defaultVisible(key)

    fun isPinned(key: String): Boolean =
        pinned[key] ?: ChatMenuPrefs.defaultPinned(key)

    fun isAttachVisible(key: String): Boolean =
        attachVisible[key] ?: ChatMenuPrefs.defaultAttachVisible(key)

    /** The attach keys currently shown in the "+" menu, in display order. */
    val visibleAttachOrder: List<String> =
        attachOrder.filter(::isAttachVisible)
}
