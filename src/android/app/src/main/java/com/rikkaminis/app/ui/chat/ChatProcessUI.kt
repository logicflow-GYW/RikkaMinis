package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.DecorativeSpinner
import com.rikkaminis.app.ui.settings.thinkingCharCountVisibleEnabled
import com.rikkaminis.app.ui.settings.thinkingDurationVisibleEnabled
import com.rikkaminis.app.ui.theme.ChatColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Committed geometry only: progress text, stream flags and scroll offsets are not revisions. */
internal data class ProcessTailGeometry(val count: Int, val key: Any?, val height: Int?)

internal data class ProcessTailTarget(val index: Int, val offset: Int)

/** One opening has one owner. Reaching the bottom never changes ownership. */
internal class ProcessSheetFollow {
    var following by mutableStateOf(true)
        private set
    private var observed: ProcessTailGeometry? = null
    private var covered = false
    private var pending = false
    private var positioned: ProcessTailTarget? = null

    fun detach() {
        following = false
        pending = false
    }

    fun suspendForDetail() {
        covered = true
        pending = false
    }

    fun observe(tail: ProcessTailGeometry, detailCovered: Boolean, closing: Boolean) {
        val before = observed
        // A newly measured tail height belongs to the already observed append,
        // not another update caused by our own positioning.
        val changed = before == null || tail.count > before.count || tail.key != before.key ||
            (before.height != null && tail.height != null && tail.height > before.height)
        if (changed) positioned = null
        observed = tail
        if (!following || closing || detailCovered || covered != detailCovered) {
            pending = false
        } else if (changed && tail.count > 0) {
            pending = true
        }
        covered = detailCovered
        if (tail.count == 0) pending = false
    }

    fun target(
        tail: ProcessTailGeometry,
        canScrollForward: Boolean,
        busy: Boolean,
        detailCovered: Boolean,
        closing: Boolean,
        viewportEnd: Int,
        afterPadding: Int,
    ): ProcessTailTarget? {
        if (!following || detailCovered || closing) {
            pending = false
            return null
        }
        if (!pending || busy || tail.count <= 0) return null
        if (!canScrollForward) {
            pending = false
            return null
        }
        // scrollToItem clamps ordinary rows at the end. If the last row alone
        // exceeds the viewport, align its measured bottom plus content padding.
        val index = safeBottomScrollIndex(tail.count) ?: return null
        val offset = tail.height?.let { (it - viewportEnd + afterPadding).coerceAtLeast(0) } ?: 0
        val target = ProcessTailTarget(index, offset)
        return target.takeUnless { it == positioned }
    }

    fun positioned(target: ProcessTailTarget) {
        positioned = target
    }
}

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
 * The process list stays mounted beneath the native bottom detail sheets.
 * This host only owns which detail is above the list; each sheet owns its
 * own gestures and dismissal.
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
    val follow = remember(messageId) { ProcessSheetFollow() }
    val tools = remember(blocks) { blocks.filter { it.kind == "tool_use" } }
    val selected = tools.any { it.id == selectedToolId }
    LaunchedEffect(page, selectedToolId, selected) {
        if (page is ChatProcessPage.Tool && !selected) page = ChatProcessPage.List
    }
    val backToList = {
        page = ChatProcessPage.List
        onClearTool()
    }

    // Keep this window outside the detail switch so its list stays mounted.
    TopChatSheet(
        paneName = stringResource(R.string.chat_process_title),
        onDismissRequest = onDismiss,
        onHistoryScroll = follow::detach,
    ) { canFollow, closing ->
        key(messageId) {
            ChatProcessList(
                blocks = blocks,
                isStreaming = isStreaming,
                follow = follow,
                detailCovered = { page != ChatProcessPage.List },
                canFollow = canFollow,
                closing = closing,
                onOpenTool = { follow.suspendForDetail(); onSelectTool(it); page = ChatProcessPage.Tool },
                onOpenThinking = { follow.suspendForDetail(); page = ChatProcessPage.Thinking(it) },
                toolActions = toolActions,
            )
        }
    }

    // A separate native sheet is stacked ABOVE the still-mounted parent.
    // Its completed dismiss only removes this detail, never the process list.
    when (val current = page) {
        ChatProcessPage.List -> Unit
        ChatProcessPage.Tool -> if (selected) {
            ToolDetailSheet(
                toolBlocks = tools,
                selectedToolId = selectedToolId ?: "",
                onSelectTool = onSelectTool,
                onDismiss = backToList,
                onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                onOpenBrowserForUrl = onOpenBrowserForUrl,
            )
        }
        is ChatProcessPage.Thinking -> StandardChatSheet(
            title = null,
            onDismiss = backToList,
            showClose = false,
        ) {
            ChatProcessThinkingReader(content = current.content)
        }
    }
}

@Composable
private fun ChatProcessList(
    blocks: List<AssistantBlock>,
    isStreaming: Boolean,
    follow: ProcessSheetFollow,
    detailCovered: () -> Boolean,
    canFollow: () -> Boolean,
    closing: () -> Boolean,
    onOpenTool: (String) -> Unit,
    onOpenThinking: (String) -> Unit,
    toolActions: (AssistantBlock) -> ToolPillActions?,
) {
    // Only the TRAILING block of a live turn is "live" — the transcript's
    // ThinkingBlock uses the same rule. Without it every reasoning row of a
    // streaming turn would spin at once.
    val liveBlockId = if (isStreaming) blocks.lastOrNull { it.kind != "info" }?.id else null
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = blocks.lastIndex.coerceAtLeast(0))
    val latestBlocks by rememberUpdatedState(blocks)
    val latestCovered by rememberUpdatedState(detailCovered)
    val latestCanFollow by rememberUpdatedState(canFollow)
    val latestClosing by rememberUpdatedState(closing)
    LaunchedEffect(listState, follow) {
        fun committedTail(): ProcessTailGeometry? {
            val layout = listState.layoutInfo
            if (layout.totalItemsCount == 0) return ProcessTailGeometry(0, null, null)
            val last = latestBlocks.lastOrNull() ?: return null
            val tailKey = "${last.kind}:${last.id}"
            // Do not pair a new data batch with the previous committed measure.
            if (layout.totalItemsCount != latestBlocks.size) return null
            val measured = layout.visibleItemsInfo.lastOrNull { it.index == layout.totalItemsCount - 1 }
            if (measured != null && measured.key != tailKey) return null
            return ProcessTailGeometry(layout.totalItemsCount, tailKey, measured?.size)
        }
        fun currentTarget(): ProcessTailTarget? {
            val tail = committedTail() ?: return null
            val covered = latestCovered()
            val isClosing = latestClosing()
            follow.observe(tail, covered, isClosing)
            return follow.target(
                tail, listState.canScrollForward,
                listState.isScrollInProgress || !latestCanFollow(), covered, isClosing,
                listState.layoutInfo.viewportEndOffset, listState.layoutInfo.afterContentPadding,
            )
        }
        snapshotFlow {
            // Data/geometry changes and idle transitions wake us; offsets and
            // decorative content do not. No stream flag gates the last batch.
            listOf(committedTail(), listState.canScrollForward, listState.isScrollInProgress,
                latestCanFollow(), latestCovered(), latestClosing(), follow.following)
        }.collect {
            if (currentTarget() != null) {
                // A user-priority mutation can cancel this child scroll without
                // killing the observer. Re-read every gate and target at commit.
                coroutineScope {
                    launch {
                        val target = currentTarget() ?: return@launch
                        listState.scrollToItem(target.index, target.offset)
                        follow.positioned(target)
                    }
                }
            }
        }
    }
    LazyColumn(
        state = listState,
        // The parent sheet bounds this body and arbitrates leftover drag deltas.
        modifier = Modifier.fillMaxSize(),
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

/** Selectable reasoning body; the standard sheet owns the handle and gestures. */
@Composable
internal fun ChatProcessThinkingReader(content: String) {
    Box(
        Modifier.fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
    ) {
        ChatProcessThinkingText(content)
    }
}
