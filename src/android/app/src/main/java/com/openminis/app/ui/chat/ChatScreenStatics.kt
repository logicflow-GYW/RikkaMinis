package com.openminis.app.ui.chat

// [refactor/split-chatscreen] Batch 1: top-level chat-screen statics moved
// VERBATIM from ChatScreen.kt (was lines 297-425): semantic status colors,
// attachment pick limit, bottom-sentinel scroll helpers, the list scrollbar
// modifier, and the ScrollFollowKey signature data class.
// Visibility: SLASH_PICKER_FIXED_HEIGHT / verticalScrollbar private -> internal
// (their only remaining caller, ChatInputArea, now lives in its own file).

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.theme.ChatColors

// iOS ChatColors equivalent — semantic status colors read from the chat
// palette so they follow the active light/dark theme.
internal val ToolCheckColor: Color
    @Composable
    @ReadOnlyComposable
    get() = ChatColors.success
internal val ToolErrorColor: Color
    @Composable
    @ReadOnlyComposable
    get() = ChatColors.error
// iOS .yellow / .pink have no chat-palette slot; keep fixed.
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
internal val ToolMemoryAccent = Color(0xFFFF2D55) // iOS .pink
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
internal const val ATTACHMENT_PICK_LIMIT = 50
// [forward-stable] Bottom sentinel row key — the single scroll target for
// every "go to bottom" request in the forward (non-reverse) chat list.
internal const val ScrollBottomKey = "__scroll_bottom__"

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 */
internal val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
// [forward-stable] True when the bottom sentinel row (the last index) is
// currently measured inside the viewport — the authoritative "user is at the
// very bottom" signal for the follow state machine. The sentinel is a 5dp
// spacer so it is only measurable at the true end of the transcript.
internal fun isBottomSentinelVisible(
    layoutInfo: androidx.compose.foundation.lazy.LazyListLayoutInfo,
): Boolean {
    val total = layoutInfo.totalItemsCount
    return total > 0 && layoutInfo.visibleItemsInfo.any { it.index == total - 1 }
}

/**
 * [fix/chat-sentinel-crash-on-import] Resolve the bottom-sentinel scroll index
 * safely. `requestScrollToItem` throws `IllegalArgumentException("Index should
 * be non-negative (-1)")` when handed a negative index; a cold-open
 * InitialOpen request can fire before the LazyColumn has measured anything
 * (layoutInfo.totalItemsCount == 0), so `totalItemsCount - 1` is -1. Returning
 * null means "nothing to scroll yet" — the next committed row revision will
 * raise the real bottom request once rows exist.
 */
internal fun safeBottomScrollIndex(totalItems: Int): Int? =
    if (totalItems > 0) totalItems - 1 else null

internal fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
internal data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)
