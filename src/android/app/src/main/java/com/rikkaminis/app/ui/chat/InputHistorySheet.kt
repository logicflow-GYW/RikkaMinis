package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput

/** [FIX-C8/F-12] Rows this sheet must never render: compact markers, model-switch
 * dividers and injected system notes all arrive as `role = "system"`. */
internal const val SYSTEM_ROLE = "system"

/** [FIX-C8/F-12] Shown for a message with neither text nor attachment names —
 * the state an empty assistant turn can still reach. */
internal const val INPUT_HISTORY_EMPTY_PLACEHOLDER = "[...]"

/**
 * [FIX-C8/F-12] Does [content] or one of [attachmentNames] match [query]?
 *
 * A blank query matches everything (the sheet's "no filter" state). Matching
 * the attachment names is what makes an attachment-only message findable: the
 * user remembers `diagram.png`, not the (empty) text of the bubble.
 *
 * Pure so the matching rule is JVM-testable; the sheet itself is a @Composable.
 */
internal fun inputHistoryMatches(
    query: String,
    content: String,
    attachmentNames: List<String>,
): Boolean {
    if (query.isBlank()) return true
    if (content.contains(query, ignoreCase = true)) return true
    return attachmentNames.any { it.contains(query, ignoreCase = true) }
}

/**
 * [FIX-C8/F-12] Single-line preview for a history row.
 *
 * Text first (trimmed). With no text, the attachment file names stand in — an
 * attachment-only prompt used to render as "[...]", which made every such row
 * indistinguishable from every other one. "[...]" is now only what it always
 * meant: genuinely empty.
 */
internal fun inputHistoryPreview(
    content: String,
    attachmentNames: List<String>,
): String {
    val text = content.trim()
    if (text.isNotEmpty()) return text
    val named = attachmentNames.map { it.trim() }.filter { it.isNotEmpty() }
    if (named.isNotEmpty()) return named.joinToString(", ")
    return INPUT_HISTORY_EMPTY_PLACEHOLDER
}

/**
 * "历史输入"面板 — 参考 RikkaHub 聊天页右上角（与 New Chat 并排）的消息预览
 * 功能。列出当前会话里所有已加载的消息（用户输入与助手回复），顶栏搜索框可
 * 按内容过滤，点击任一条目即定位（滚动）到聊天列表中的对应消息并短暂高亮。
 *
 * 用户消息用主色气泡右对齐突出显示（方便一眼找回自己问过的话），助手消息用
 * 次级色气泡左对齐。搜索关键词同时匹配用户与助手消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputHistorySheet(
    messages: List<ChatMessage>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    // [FIX-C8/F-12] Two gaps in the old one-liner (`messages.filter { it.content
    // .contains(query) }`):
    //  1. `role == "system"` rows are *not* user input — they are the compact
    //     marker / model-switch divider / injected system notes. Rendered here
    //     they read as left-aligned assistant bubbles and are not clickable
    //     destinations of any use (the list only scrolls to a row id).
    //  2. An attachment-only message has `content == ""` (the files live in
    //     `attachmentNames` / `imageUris`), so it showed as "[...]" and could
    //     not be found by typing the file name the user remembers.
    val rows = remember(messages) { messages.filter { it.role != SYSTEM_ROLE } }
    val filtered = remember(rows, searchQuery) {
        rows.filter { inputHistoryMatches(searchQuery, it.content, it.attachmentNames) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.input_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.input_history_done))
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = sanitizeSingleLineInput(it) },
                placeholder = { Text(stringResource(R.string.input_history_search)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            if (rows.isEmpty()) R.string.input_history_empty
                            else R.string.input_history_no_match
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@ModalBottomSheet
            }

            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(
                    items = filtered,
                    key = { it.id },
                ) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isUser) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth(if (isUser) 0.85f else 0.9f)
                                .clickable { onSelect(msg.id) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    // [FIX-C8/F-12] Attachment-only messages name
                                    // their files instead of showing "[...]".
                                    text = inputHistoryPreview(msg.content, msg.attachmentNames),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}