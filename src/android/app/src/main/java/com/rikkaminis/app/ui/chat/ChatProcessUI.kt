package com.rikkaminis.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.DecorativeSpinner
import com.rikkaminis.app.ui.settings.thinkingCharCountVisibleEnabled
import com.rikkaminis.app.ui.settings.thinkingDurationVisibleEnabled
import com.rikkaminis.app.ui.theme.ChatColors
import kotlin.math.roundToInt

/** Downward fling speed that closes a drawer even if it has barely moved. */
private const val FLING_DISMISS_VELOCITY = 900f

@Composable
private fun chatProcessLabel(status: ChatProcessStatus, isStreaming: Boolean): String {
    val action = when {
        isStreaming && status.running > 0 -> stringResource(R.string.chat_process_running, status.running)
        isStreaming && status.isThinkingNow -> stringResource(R.string.token_usage_section_thinking)
        status.calls > 0 && status.allSucceeded -> stringResource(R.string.chat_process_tools, status.calls)
        status.calls > 0 -> stringResource(R.string.chat_process_tool_calls, status.calls)
        else -> stringResource(R.string.token_usage_section_thinking)
    }
    return listOfNotNull(
        action,
        if (status.failed > 0) stringResource(R.string.chat_process_failed, status.failed) else null,
        if (status.cancelled > 0) stringResource(R.string.chat_process_cancelled, status.cancelled) else null,
        if (status.skipped > 0) stringResource(R.string.chat_process_deduplicated, status.skipped) else null,
    ).joinToString(" · ")
}

/**
 * The one status line of an assistant turn.
 *
 * It is plain text on the transcript background — no capsule, no border, no
 * chevron, no trailing "details" affordance — so it reads as part of the
 * message rather than as a control sitting above it. The whole line is the
 * tap target. Alignment matches [AssistantHeader]: zero horizontal padding, so
 * the text starts on the same vertical line as the ✦ identity glyph.
 */
@Composable
internal fun ChatProcessEntry(
    status: ChatProcessStatus,
    isStreaming: Boolean,
    showWait: Boolean,
    queueWaitingAhead: Int,
    awaitingResponseSinceMs: Long,
    onToggle: () -> Unit,
) {
    var seconds by remember(awaitingResponseSinceMs) { mutableStateOf(0L) }
    LaunchedEffect(showWait, awaitingResponseSinceMs) {
        if (showWait && awaitingResponseSinceMs > 0L) {
            while (true) {
                seconds = (System.currentTimeMillis() - awaitingResponseSinceMs) / 1000L
                kotlinx.coroutines.delay(1000L)
            }
        }
    }
    val label = when {
        queueWaitingAhead > 0 -> stringResource(R.string.chat_queued_indicator, queueWaitingAhead)
        showWait -> stringResource(R.string.chat_waiting_network, seconds)
        else -> chatProcessLabel(status, isStreaming)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label first so the text starts on the SAME vertical line as the ✦
        // identity glyph above it. The live dots keep their existing trigger
        // and animation and simply sit after the text; leading them (the old
        // layout) reserved 23dp before the label and pushed it right of the
        // symbol for the entire stream.
        Text(
            label,
            // weight(1f, fill = false): the dots are measured FIRST (they are
            // the unweighted child), and the label only gets what is left over.
            // Without this a long label would eat the row and push the dots out
            // of the layout entirely.
            modifier = Modifier.weight(1f, fill = false),
            fontSize = 12.sp,
            color = if (status.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isStreaming && queueWaitingAhead <= 0) {
            Spacer(Modifier.width(5.dp))
            Box(Modifier.size(width = 20.dp, height = 15.dp), contentAlignment = Alignment.CenterStart) {
                BouncingDots(MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal sealed interface ChatProcessPage {
    data object List : ChatProcessPage
    data object Tool : ChatProcessPage
    data class Thinking(val content: String) : ChatProcessPage
}

/**
 * Bottom drawer that holds the process list, and — stacked on top of it — the
 * tool output and the reasoning reader.
 *
 * Shape contract (user-aligned 2026-11-27):
 *  • A drawer, not a full-screen page: rounded top corners, a drag handle, and
 *    a scrim. Dragging the handle down past the threshold, tapping the scrim,
 *    or the system back gesture closes exactly ONE level.
 *  • No close button and no back arrow anywhere: the gesture IS the exit.
 *  • The list opens at the newest entry, not at the top.
 */
@Composable
internal fun ChatProcessSheet(
    messageId: String,
    blocks: List<AssistantBlock>,
    isStreaming: Boolean,
    onDismiss: () -> Unit,
    toolActions: (AssistantBlock) -> ToolPillActions?,
    selectedToolId: String?,
    onSelectTool: (String) -> Unit,
    onClearTool: () -> Unit,
    onOpenTerminalWithCommand: (String) -> Unit,
    onOpenBrowserForUrl: (String) -> Unit,
) {
    var page by remember(messageId) { mutableStateOf<ChatProcessPage>(ChatProcessPage.List) }
    val tools = remember(blocks) { blocks.filter { it.kind == "tool_use" } }
    val selected = tools.any { it.id == selectedToolId }
    LaunchedEffect(page, selectedToolId, selected) {
        if (page is ChatProcessPage.Tool && !selected) page = ChatProcessPage.List
    }
    val backToList = {
        page = ChatProcessPage.List
        onClearTool()
    }
    // One BackHandler for the whole stack: it pops one level, never both.
    BackHandler(page !is ChatProcessPage.List, onBack = backToList)
    Dialog(
        // Deliberately page-aware, not `onDismiss`: a plain Dialog can swallow
        // the back press and dismiss the whole window before the BackHandler
        // inside it sees the event. Routing it through the same one-level pop
        // makes the guarantee hold whichever of the two wins.
        onDismissRequest = { if (page is ChatProcessPage.List) onDismiss() else backToList() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            when (val current = page) {
                ChatProcessPage.List -> ProcessDrawer(
                    heightFraction = 0.88f,
                    onDismiss = onDismiss,
                ) {
                    ChatProcessList(
                        blocks = blocks,
                        isStreaming = isStreaming,
                        onOpenTool = { onSelectTool(it); page = ChatProcessPage.Tool },
                        onOpenThinking = { page = ChatProcessPage.Thinking(it) },
                        toolActions = toolActions,
                    )
                }
                ChatProcessPage.Tool -> ProcessDrawer(
                    heightFraction = 0.94f,
                    onDismiss = backToList,
                ) {
                    ToolDetailSheetContent(
                        toolBlocks = tools,
                        selectedToolId = selectedToolId ?: "",
                        onSelectTool = onSelectTool,
                        onDismiss = onDismiss,
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = onOpenBrowserForUrl,
                    )
                }
                is ChatProcessPage.Thinking -> ProcessDrawer(
                    heightFraction = 0.94f,
                    onDismiss = backToList,
                ) {
                    ChatProcessThinkingReader(content = current.content)
                }
            }
        }
    }
}

/**
 * Bottom sheet shell: scrim + rounded panel + drag handle.
 *
 * Swipe-down-to-dismiss works from ANYWHERE on the panel, not just the handle,
 * and it is implemented as a nested-scroll participant rather than a raw
 * `pointerInput` on the body: a pointerInput drag detector and the body's own
 * LazyColumn/verticalScroll would both claim the same pointers and fight. As a
 * nested-scroll parent the drawer only receives the delta the child did NOT
 * consume — i.e. exactly the over-scroll that happens when the list is already
 * at its top — so scrolling inside the sheet keeps working and dragging past
 * the top peels the sheet down. The handle keeps its own detector because a
 * sheet whose content is shorter than the panel has no scrollable child at all.
 */
@Composable
private fun ProcessDrawer(
    heightFraction: Float,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var dragOffset by remember { mutableStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { 88.dp.toPx() }
    val settle = remember { Animatable(0f) }
    // The connection object is remembered across recompositions, so it must not
    // capture a stale onDismiss: on the Tool page that callback is `backToList`,
    // which is rebuilt every time the page state changes.
    val dismissNow by rememberUpdatedState(onDismiss)
    val nestedScroll = remember(thresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Dragging back up while the sheet is already displaced must
                // move the SHEET, not the list — otherwise the panel stays
                // half-open and the content scrolls underneath it.
                if (available.y < 0f && dragOffset > 0f) {
                    val consumed = (-available.y).coerceAtMost(dragOffset)
                    dragOffset -= consumed
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0f) return Offset.Zero
                dragOffset = (dragOffset + available.y).coerceAtLeast(0f)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragOffset <= 0f) return Velocity.Zero
                if (dragOffset > thresholdPx || available.y > FLING_DISMISS_VELOCITY) {
                    dismissNow()
                } else {
                    // Below the threshold: spring back instead of snapping, so
                    // a short drag reads as "released", not as a jump.
                    settle.snapTo(dragOffset)
                    // `block` is the 4th parameter of animateTo; passing the
                    // lambda positionally would land in animationSpec and not
                    // compile.
                    settle.animateTo(0f, block = { dragOffset = value })
                    dragOffset = 0f
                }
                return Velocity.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(role = Role.Button, onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(heightFraction)
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(ChatColors.secondaryBg)
                .nestedScroll(nestedScroll)
                .systemBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (dragOffset > thresholdPx) dismissNow()
                                dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                            onVerticalDrag = { _, dy ->
                                dragOffset = (dragOffset + dy).coerceAtLeast(0f)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            // Bounded height for the page: a scrollable child measured against
            // an infinite max height (which is what an unweighted Column gives
            // it) crashes the way the nested list did before. weight(1f) hands
            // the page exactly the leftover space instead.
            Column(Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}

@Composable
private fun ChatProcessList(
    blocks: List<AssistantBlock>,
    isStreaming: Boolean,
    onOpenTool: (String) -> Unit,
    onOpenThinking: (String) -> Unit,
    toolActions: (AssistantBlock) -> ToolPillActions?,
) = Column(Modifier.fillMaxSize()) {
    // Centred title, and nothing on the right edge: the call/failure counts
    // already live on the transcript entry, repeating them here would say the
    // same thing twice in two places.
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.chat_process_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    // Only the TRAILING block of a live turn is "live" — the transcript's
    // ThinkingBlock uses the same rule. Without it every reasoning row of a
    // streaming turn would spin at once.
    val liveBlockId = if (isStreaming) blocks.lastOrNull { it.kind != "info" }?.id else null
    val listState = rememberLazyListState()
    // Open at the newest entry — the reader wants "what just happened", not
    // the first tool of a 199-call turn.
    // Once, on open. Keyed on the list, a streaming turn would re-run this on
    // every appended block and yank a reader who had scrolled up back down.
    LaunchedEffect(Unit) {
        if (blocks.isNotEmpty()) listState.scrollToItem(blocks.lastIndex)
    }
    LazyColumn(
        state = listState,
        // weight(1f), not fillMaxSize: a non-weighted child is measured with
        // the parent's FULL height, so the list would run the title's height
        // past the bottom edge of the drawer.
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 20.dp),
    ) {
        items(blocks, key = { "${it.kind}:${it.id}" }) { block ->
            if (block.kind == "thinking") {
                ProcessThinkingRow(
                    block = block,
                    isLive = block.id == liveBlockId,
                    onClick = { onOpenThinking(block.content) },
                )
            } else {
                val actions = toolActions(block)
                ToolCallPill(
                    block = block,
                    onRetry = actions?.onRetry,
                    onStop = actions?.onStop,
                    onOpenTerminalWithCommand = actions?.onOpenTerminalWithCommand ?: {},
                    onOpenDetail = onOpenTool,
                    onRerunFromHere = actions?.onRerunFromHere,
                    onCopyDetails = actions?.onCopyDetails,
                )
            }
        }
    }
}

/**
 * Reasoning entry inside the drawer.
 *
 * Same card as the transcript's [ThinkingBlock] header — tinted background,
 * 12dp corners, Psychology glyph, the "Deep thinking" label and the optional
 * duration / char-count extras — but with no expand chevron, no "view full
 * content" link and no trailing arrow: tapping the card opens the reader.
 *
 * The extras are not this screen's decision: both are governed by the existing
 * Appearance toggles ([thinkingDurationVisibleEnabled],
 * [thinkingCharCountVisibleEnabled]) so turning them off hides them here too.
 */
@Composable
private fun ProcessThinkingRow(block: AssistantBlock, isLive: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val showDuration = remember { thinkingDurationVisibleEnabled(context) }
    val showCharCount = remember { thinkingCharCountVisibleEnabled(context) }
    val thinkingBlue = ChatColors.thinking
    val extras = thinkingHeaderExtras(
        showDuration = showDuration,
        showCharCount = showCharCount,
        // Same rule as the transcript card: no elapsed stamp while the block is
        // still being written (there is no settled duration to show yet).
        isStreaming = isLive,
        durationMs = block.durationMs,
        charCount = block.content.length,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(thinkingBlue.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .border(0.5.dp, thinkingBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLive && block.toolStatus != ToolBlockStatus.SUCCESS) {
            // Same substitution the transcript card makes while live: spinner
            // instead of the glyph, and the model's own newest action phrase
            // instead of the static label. Frozen blocks fall back to the label.
            DecorativeSpinner(modifier = Modifier.size(13.dp), color = thinkingBlue, strokeWidth = 1.5.dp)
        } else {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                tint = thinkingBlue,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        val liveTitle = remember(block.content.length, isLive) {
            if (isLive) extractThinkingTitle(block.content) else null
        }
        Text(
            text = liveTitle ?: stringResource(R.string.chat_deep_thinking),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = thinkingBlue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        extras.duration?.let { label ->
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = thinkingBlue.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(4.dp))
        }
        extras.charCount?.let { label ->
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = thinkingBlue.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * Reasoning reader. Title only — no back arrow (the drawer gesture goes back
 * one level) and no copy button (long-press selection is the platform
 * behaviour, and a second affordance for it is noise).
 */
@Composable
internal fun ChatProcessThinkingReader(content: String) = Column(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.chat_deep_thinking),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Box(
        Modifier.fillMaxWidth().weight(1f)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
    ) {
        ChatProcessThinkingText(content)
    }
}
