package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.config.AttachActionCatalog
import com.rikkaminis.app.config.ChatActionCatalog
import com.rikkaminis.app.config.ChatMenuPrefs

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Chat action customization — dedicated screen reached from
 * Settings → Appearance → Chat Menu.
 *
 * Two independent sections:
 *   (A) Top-right menu — the eight menu-renderable actions each have a
 *       visibility Switch; the order defines the "..." menu rendering. The
 *       two footer-only actions (Token Usage, Settings) are intentionally
 *       absent: the menu's rendering loop has no branch for them, so a
 *       switch would be a dead control.
 *   (B) History drawer footer — all ten actions have a pin Switch; the order
 *       defines the footer FlowRow rendering. SETTINGS, when pinned, is
 *       always anchored last (its row is non-draggable + shows a "fixed
 *       right" hint); the row is always listed so it can always be re-pinned.
 *
 * Each row has a drag handle (≡) to grab and reorder (long-press + drag,
 * live swap as you cross the half-row boundary). Order and visibility / pin
 * are persisted to appearance_prefs via ChatMenuPrefs, so they round-trip
 * through minis-config and every local backup automatically.
 */
@Composable
fun ChatMenuSettingsScreen(
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }
    val rowHeight = 56.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    // Section A: top-right menu order — only the eight menu-renderable
    // actions. TOKEN_USAGE / SETTINGS are footer-only: the "..." menu's
    // rendering loop (ChatScreen) has no branch for them, so a visibility
    // switch would be a dead control that silently does nothing AND could
    // resurrect the empty-"..."-button bug (menu visible while its content
    // list stays empty). Filtering here keeps the section and the rendering
    // loop in lock-step; writeOrder's sanitizeForWrite re-appends the two
    // footer-only keys at their default (trailing) position on save.
    var menuOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.resolveOrder(prefs)
                .filter { ChatActionCatalog.spec(it)?.defaultMenuVisible != false },
        )
    }
    // Section B: footer pin order (all 10 entries, with SETTINGS anchored)
    // via settingsPinOrder — the settings list must always contain all ten
    // rows so SETTINGS stays re-pinnable even after it was unpinned (an
    // anchorSettingsLast-filtered list would drop the row and leave no way
    // back).
    var footerOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.settingsPinOrder(prefs),
        )
    }
    // Section C: composer attach ("+") menu — the three attach actions each
    // have a visibility Switch; the order defines the "+" menu rendering.
    // Unlike sections A/B this list is a plain order over AttachActionCatalog.
    var attachOrder by remember {
        mutableStateOf(
            ChatMenuPrefs.resolveAttachOrder(prefs),
        )
    }
    var draggingEntry by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Tick to trigger Switch state re-read on external prefs changes
    var prefsTick by remember { mutableStateOf(0) }

    fun commitMenuOrder(newOrder: List<String>) {
        menuOrder = newOrder
        ChatMenuPrefs.writeOrder(prefs, newOrder)
    }

    fun commitFooterOrder(newOrder: List<String>) {
        footerOrder = newOrder
        ChatMenuPrefs.writePinOrder(prefs, newOrder)
    }

    fun commitAttachOrder(newOrder: List<String>) {
        attachOrder = newOrder
        ChatMenuPrefs.writeAttachOrder(prefs, newOrder)
    }

    SettingsScaffold(
        title = stringResource(R.string.appearance_section_chat_menu),
        onBack = onBack,
        navigation = {},
    ) {
        // Section 0: fixed composer/top-bar button visibility — high-frequency
        // fixed slots, each with a single Switch (few options, kept near the
        // top). Independent of the "..." menu pool below.
        SettingsSection(
            header = stringResource(R.string.appearance_chat_menu_section_topbar),
            footer = null,
        ) {
            // Read prefsTick to establish re-composition dependency when prefs
            // change (same technique DraggableActionRow uses) — otherwise
            // toggling the switch below updates the value but the switch's
            // checked state would not re-render after the callback fires.
            @Suppress("UNUSED_EXPRESSION")
            prefsTick
            SettingsSwitchRow(
                title = stringResource(R.string.input_history_title),
                subtitle = stringResource(R.string.input_history_topbar_description),
                checked = ChatMenuPrefs.isTopBarInputHistoryVisible(prefs),
                onCheckedChange = { newValue ->
                    ChatMenuPrefs.setTopBarInputHistoryVisible(prefs, newValue)
                    prefsTick++
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.composer_model_picker_button_title),
                subtitle = stringResource(R.string.composer_model_picker_button_description),
                checked = ChatMenuPrefs.isComposerModelPickerVisible(prefs),
                onCheckedChange = { newValue ->
                    ChatMenuPrefs.setComposerModelPickerVisible(prefs, newValue)
                    prefsTick++
                },
            )
        }

        // Section C: composer attach ("+") menu
        SettingsSection(
            header = stringResource(R.string.appearance_chat_menu_section_attach),
            footer = stringResource(R.string.appearance_chat_menu_footer_attach),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                attachOrder.forEachIndexed { index, entryKey ->
                    key(entryKey) {
                        AttachActionCatalog.spec(entryKey)?.let { spec ->
                            DraggableActionRow(
                                entryKey = entryKey,
                                icon = spec.icon,
                                title = stringResource(spec.titleRes),
                                index = index,
                                isLast = index == attachOrder.lastIndex,
                                draggable = true,
                                rowHeightPx = rowHeightPx,
                                order = attachOrder,
                                draggingEntry = draggingEntry,
                                dragOffset = dragOffset,
                                onDragStart = { draggingEntry = it; dragOffset = 0f },
                                onDragEnd = { draggingEntry = null; dragOffset = 0f },
                                onDragOffsetChange = { dragOffset = it },
                                onCommitOrder = ::commitAttachOrder,
                                prefsTick = prefsTick,
                            ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = stringResource(R.string.chat_menu_drag_handle),
                                    modifier = Modifier.padding(end = 4.dp).size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = ChatMenuPrefs.isAttachVisible(prefs, entryKey),
                                    onCheckedChange = { newValue ->
                                        ChatMenuPrefs.setAttachVisible(prefs, entryKey, newValue)
                                        prefsTick++
                                    },
                                )
                            }
                            }
                        }
                    }
                }
            }
        }
        // Section A: top-right "..." menu
        SettingsSection(
            header = stringResource(R.string.appearance_chat_menu_section_menu),
            footer = stringResource(R.string.appearance_chat_menu_footer),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                menuOrder.forEachIndexed { index, entryKey ->
                    key(entryKey) {
                        ChatActionCatalog.spec(entryKey)?.let { spec ->
                            DraggableActionRow(
                                entryKey = entryKey,
                                icon = spec.icon,
                                title = stringResource(spec.titleRes),
                                index = index,
                                isLast = index == menuOrder.lastIndex,
                                draggable = true,
                                rowHeightPx = rowHeightPx,
                                order = menuOrder,
                                draggingEntry = draggingEntry,
                                dragOffset = dragOffset,
                                onDragStart = { draggingEntry = it; dragOffset = 0f },
                                onDragEnd = { draggingEntry = null; dragOffset = 0f },
                                onDragOffsetChange = { dragOffset = it },
                                onCommitOrder = ::commitMenuOrder,
                                prefsTick = prefsTick,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.DragHandle,
                                    contentDescription = stringResource(R.string.chat_menu_drag_handle),
                                    modifier = Modifier.padding(end = 4.dp).size(28.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Switch(
                                    checked = ChatMenuPrefs.isVisible(prefs, entryKey),
                                    onCheckedChange = { newValue ->
                                        ChatMenuPrefs.setVisible(prefs, entryKey, newValue)
                                        prefsTick++
                                    },
                                )
                            }
                            }
                        }
                    }
                }
            }
        }

        // Section B: history-drawer footer
        SettingsSection(
            header = stringResource(R.string.appearance_chat_menu_section_footer),
            footer = null,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                footerOrder.forEachIndexed { index, entryKey ->
                    key(entryKey) {
                        val isSettings = entryKey == ChatMenuPrefs.SETTINGS
                        ChatActionCatalog.spec(entryKey)?.let { spec ->
                            DraggableActionRow(
                                entryKey = entryKey,
                                icon = spec.icon,
                                title = stringResource(spec.titleRes),
                                index = index,
                                isLast = index == footerOrder.lastIndex,
                                draggable = !isSettings,
                                rowHeightPx = rowHeightPx,
                                order = footerOrder,
                                draggingEntry = draggingEntry,
                                dragOffset = dragOffset,
                                onDragStart = { draggingEntry = it; dragOffset = 0f },
                                onDragEnd = { draggingEntry = null; dragOffset = 0f },
                                onDragOffsetChange = { dragOffset = it },
                                onCommitOrder = ::commitFooterOrder,
                                prefsTick = prefsTick,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSettings && ChatMenuPrefs.isPinned(prefs, entryKey)) {
                                    // SETTINGS pinned: show "fixed right" hint instead of drag handle
                                    Text(
                                        text = stringResource(R.string.chat_menu_settings_always_last),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                } else if (!isSettings) {
                                    Icon(
                                        Icons.Outlined.DragHandle,
                                        contentDescription = stringResource(R.string.chat_menu_drag_handle),
                                        modifier = Modifier.padding(end = 4.dp).size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    // SETTINGS unpinned: no hint, just space
                                    Spacer(Modifier.width(28.dp + 4.dp))
                                }
                                Switch(
                                    checked = ChatMenuPrefs.isPinned(prefs, entryKey),
                                    onCheckedChange = { newValue ->
                                        ChatMenuPrefs.setPinned(prefs, entryKey, newValue)
                                        prefsTick++
                                        // Re-anchor SETTINGS after pin state change:
                                        // always keep all ten rows listed (settingsPinOrder),
                                        // never let the SETTINGS row disappear.
                                        footerOrder = ChatMenuPrefs.settingsPinOrder(prefs)
                                    },
                                )
                            }
                            }
                        }
                    }
                }
            }
        }

    }
}

/**
 * A single action row in the customization list: icon + title on the left,
 * [trailing] content on the right (drag handle + Switch, composed by caller).
 * Long-press anywhere on the row to lift it, then drag vertically — crossing
 * the half-row boundary swaps the entry with its neighbour, with the offset
 * corrected live so the gesture stays 1:1 with the finger.
 */
@Composable
private fun DraggableActionRow(
    entryKey: String,
    icon: ImageVector,
    title: String,
    index: Int,
    isLast: Boolean,
    draggable: Boolean,
    rowHeightPx: Float,
    order: List<String>,
    draggingEntry: String?,
    dragOffset: Float,
    onDragStart: (String) -> Unit,
    onDragEnd: () -> Unit,
    onDragOffsetChange: (Float) -> Unit,
    onCommitOrder: (List<String>) -> Unit,
    prefsTick: Int,
    trailing: @Composable () -> Unit,
) {
    val currentOrder by rememberUpdatedState(order)
    // pointerInput's coroutine freezes captured locals at launch, so dragging
    // state must be read through rememberUpdatedState — otherwise onDrag keeps
    // seeing the initial null / 0f and the reorder logic never fires.
    val currentDraggingEntry by rememberUpdatedState(draggingEntry)
    val currentDragOffset by rememberUpdatedState(dragOffset)
    val isDragging = draggingEntry == entryKey

    // Read prefsTick to establish re-composition dependency when prefs change
    @Suppress("UNUSED_EXPRESSION")
    prefsTick

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .offset { IntOffset(0, if (isDragging) dragOffset.roundToInt() else 0) }
            .then(
                if (draggable) {
                    Modifier.pointerInput(entryKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart(entryKey) },
                            onDrag = { change, amount ->
                                change.consume()
                                val newOffset = currentDragOffset + amount.y
                                onDragOffsetChange(newOffset)
                                val currentIndex = currentOrder.indexOf(currentDraggingEntry)
                                if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                val delta = (newOffset / rowHeightPx).roundToInt()
                                if (delta != 0) {
                                    val targetIndex =
                                        (currentIndex + delta).coerceIn(0, currentOrder.lastIndex)
                                    if (targetIndex != currentIndex) {
                                        val next = currentOrder.toMutableList()
                                        val tmp = next[currentIndex]
                                        next[currentIndex] = next[targetIndex]
                                        next[targetIndex] = tmp
                                        onDragOffsetChange(newOffset - (targetIndex - currentIndex) * rowHeightPx)
                                        onCommitOrder(next)
                                    }
                                }
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        SettingsRow(
            icon = icon,
            iconColor = MaterialTheme.colorScheme.primary,
            title = title,
            onClick = null,
            showChevron = false,
            showDivider = !isLast,
            trailing = trailing,
        )
    }
}
