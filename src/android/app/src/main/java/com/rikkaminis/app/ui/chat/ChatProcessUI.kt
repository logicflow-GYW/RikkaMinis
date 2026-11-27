package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R

@Composable
internal fun chatProcessLabel(status: ChatProcessStatus, isStreaming: Boolean): String {
    val action = when {
        isStreaming && status.running > 0 -> stringResource(R.string.chat_process_running, status.running)
        isStreaming && status.isThinkingNow -> stringResource(R.string.token_usage_section_thinking)
        status.calls > 0 && status.allSucceeded -> stringResource(R.string.chat_process_tools, status.calls)
        status.calls > 0 -> stringResource(R.string.chat_process_tool_calls, status.calls)
        else -> stringResource(R.string.token_usage_section_thinking)
    }
    return listOfNotNull(
        if (status.failed > 0) stringResource(R.string.chat_process_failed, status.failed) else null,
        action,
        if (status.cancelled > 0) stringResource(R.string.chat_process_cancelled, status.cancelled) else null,
        if (status.skipped > 0) stringResource(R.string.chat_process_deduplicated, status.skipped) else null,
    ).joinToString(" · ")
}

/** Substitute the content of the existing 36dp status band, never its footprint. */
@Composable
internal fun ChatProcessStatusBand(label: String, onOpen: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(TYPING_BAND_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            label,
            modifier = Modifier.clickable(role = Role.Button, onClick = onOpen).padding(vertical = 3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The transcript never expands for inspection; reuse the existing tool and thinking viewers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatProcessSheet(
    messageId: String,
    blocks: List<AssistantBlock>,
    onDismiss: () -> Unit,
    toolActions: (AssistantBlock) -> ToolPillActions?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var thinkingSnapshot by remember(messageId) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.78f)) {
            Text(
                stringResource(R.string.chat_process_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
                items(blocks, key = { "${it.kind}:${it.id}" }) { block ->
                    if (block.kind == "thinking") {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { thinkingSnapshot = block.content }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.token_usage_section_thinking),
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                        }
                    } else {
                        val actions = toolActions(block)
                        ToolCallPill(
                            block = block,
                            onRetry = actions?.onRetry,
                            onStop = actions?.onStop,
                            onOpenTerminalWithCommand = actions?.onOpenTerminalWithCommand ?: {},
                            onOpenDetail = actions?.onOpenDetail ?: {},
                            onRerunFromHere = actions?.onRerunFromHere,
                            onCopyDetails = actions?.onCopyDetails,
                        )
                    }
                }
            }
        }
    }
    thinkingSnapshot?.let { content ->
        ChatProcessThinkingDetail(content = content, onDismiss = { thinkingSnapshot = null })
    }
}
