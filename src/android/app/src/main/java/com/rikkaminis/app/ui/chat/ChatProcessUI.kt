package com.rikkaminis.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** An inspectable status, not a chevron-only affordance nor a second status bar. */
@Composable
internal fun ChatProcessEntry(
    status: ChatProcessStatus,
    isStreaming: Boolean,
    isExpanded: Boolean,
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
                delay(1000L)
            }
        }
    }
    val label = when {
        queueWaitingAhead > 0 -> stringResource(R.string.chat_queued_indicator, queueWaitingAhead)
        showWait -> stringResource(R.string.chat_waiting_network, seconds)
        else -> chatProcessLabel(status, isStreaming)
    }
    val tint by animateColorAsState(
        if (isExpanded) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        label = "process-entry-background",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 1.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(7.dp)).background(tint)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = 8.dp, top = 3.dp, end = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStreaming && queueWaitingAhead <= 0) {
            Box(Modifier.size(width = 23.dp, height = 15.dp), contentAlignment = Alignment.CenterStart) {
                BouncingDots(MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            color = if (status.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onToggle) {
            Text(stringResource(if (isExpanded) R.string.chat_process_collapse else R.string.chat_process_expand), fontSize = 12.sp)
            if (isExpanded) Icon(Icons.Default.ExpandLess, contentDescription = null, modifier = Modifier.size(17.dp))
        }
    }
}

internal sealed interface ChatProcessPage {
    data object List : ChatProcessPage
    data object Tool : ChatProcessPage
    data class Thinking(val content: String) : ChatProcessPage
}

/** One dialog for the list, tool output and reasoning. Details replace content, not their parent. */
@Composable
internal fun ChatProcessSheet(
    messageId: String,
    blocks: List<AssistantBlock>,
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
    // Both inner BackHandler and the dialog use the same owner. No chat-layer callback is inserted.
    BackHandler(page !is ChatProcessPage.List, onBack = backToList)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = ChatColors.secondaryBg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                when (val current = page) {
                    ChatProcessPage.List -> ChatProcessList(
                        blocks = blocks,
                        onDismiss = onDismiss,
                        onOpenTool = { onSelectTool(it); page = ChatProcessPage.Tool },
                        onOpenThinking = { page = ChatProcessPage.Thinking(it) },
                        toolActions = toolActions,
                    )
                    ChatProcessPage.Tool -> ToolDetailSheetContent(
                        toolBlocks = tools,
                        selectedToolId = selectedToolId ?: "",
                        onSelectTool = onSelectTool,
                        onDismiss = onDismiss,
                        onOpenTerminalWithCommand = onOpenTerminalWithCommand,
                        onOpenBrowserForUrl = onOpenBrowserForUrl,
                        onLeave = backToList,
                    )
                    is ChatProcessPage.Thinking -> ChatProcessThinkingReader(
                        content = current.content,
                        onBack = backToList,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatProcessList(
    blocks: List<AssistantBlock>,
    onDismiss: () -> Unit,
    onOpenTool: (String) -> Unit,
    onOpenThinking: (String) -> Unit,
    toolActions: (AssistantBlock) -> ToolPillActions?,
) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
        }
        Text(
            stringResource(R.string.chat_process_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
        items(blocks, key = { "${it.kind}:${it.id}" }) { block ->
            if (block.kind == "thinking") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) { onOpenThinking(block.content) }
                        .padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.token_usage_section_thinking),
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(stringResource(R.string.thinking_view_full), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
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

@Composable
internal fun ChatProcessThinkingReader(content: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        Text(stringResource(R.string.chat_deep_thinking), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = !copying, onClick = {
            scope.launch {
                copying = true
                try {
                    copyThinkingFullContent(context, content)
                    android.widget.Toast.makeText(
                        context,
                        if (ThinkingReadPlanner.canCopyAsText(content.length)) R.string.chat_process_copied else R.string.chat_process_copied_file,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, R.string.chat_process_copy_failed, android.widget.Toast.LENGTH_LONG).show()
                } finally { copying = false }
            }
        }) { Text(stringResource(R.string.chat_process_copy_all)) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Box(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
        ChatProcessThinkingText(content)
    }
}
