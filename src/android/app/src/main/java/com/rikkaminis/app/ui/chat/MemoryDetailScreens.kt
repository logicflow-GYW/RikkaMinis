package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.MinisTextButton

/**
 * Stateless detail body composables used by [SessionMemorySheet] when a row
 * is tapped. The sheet swaps these in for the list view, swaps the header's
 * leading slot to a back-arrow, and (for write-detail) provides Edit / Save /
 * Revoke toolbar items in the trailing slot.
 *
 * All editing state lives in the parent (the sheet) so a single Save button
 * in the header can read the latest buffer without passing references around.
 */

/**
 * Read-only / editable viewer for an auto-injected memory file (GLOBAL.md or
 * a daily log). Mirrors iOS `MemoryContentView`.
 *
 * When [isEditing] is true, [editedContent] is the current text and
 * [onEditedContentChange] is invoked on every keystroke. The parent owns
 * the buffer and renders the Save button.
 */
@Composable
fun MemoryFileViewerBody(
    initialContent: String,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val scrollState = rememberScrollState()
        if (isEditing) {
            BasicTextField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        } else {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                LazyRevealMemoryTextBody(initialContent = initialContent, scrollState = scrollState)
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------------------
// Lazy reveal for read-only memory file viewing.
//
// [T-android-memory-lazy-render] Memory files (GLOBAL.md / daily logs) are
// plain monospace text of up to ~140K chars. Laying out the whole file in a
// single Text inside a verticalScroll Column measures+composes everything on
// first frame, which janks opening the detail view. Here we chunk by lines and
// reveal a bounded window: the visible prefix is joined into ONE Text so the
// non-virtualizing verticalScroll only ever lays out what's been revealed.
// This mirrors ChatToolDetailUI's LazyRevealToolText (same thresholds) without
// sharing code across files.
// ---------------------------------------------------------------------------

private const val MEM_LAZY_CHUNK_LINES = 40
private const val MEM_LAZY_INITIAL_CHUNKS = 5            // ~200 lines
private const val MEM_LAZY_BATCH_CHUNKS = 5              // +200 lines per reveal
private const val MEM_LAZY_INITIAL_BYTE_CAP = 10 * 1024 // clamp first window to ~10KB

/** Split [text] into ordered 40-line chunks. Each chunk keeps its interior
 *  newlines; joining the revealed chunks with "\\n" preserves the original
 *  plain-text prefix, including a trailing newline when one is present. */
private fun memoryChunkByLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val out = ArrayList<String>((lines.size / MEM_LAZY_CHUNK_LINES) + 1)
    var i = 0
    while (i < lines.size) {
        val end = minOf(i + MEM_LAZY_CHUNK_LINES, lines.size)
        out.add(lines.subList(i, end).joinToString("\n"))
        i = end
    }
    return out
}

/** Initial reveal count: up to [MEM_LAZY_INITIAL_CHUNKS], further clamped so
 *  the first window stays under [MEM_LAZY_INITIAL_BYTE_CAP] (covers a few very
 *  long lines that fit in < 5 chunks but exceed 10KB). */
private fun memoryInitialRevealChunks(chunks: List<String>): Int {
    if (chunks.isEmpty()) return 0
    var count = 0
    var bytes = 0
    for (chunk in chunks.take(MEM_LAZY_INITIAL_CHUNKS)) {
        bytes += chunk.toByteArray(Charsets.UTF_8).size
        count++
        if (bytes >= MEM_LAZY_INITIAL_BYTE_CAP) break
    }
    return count.coerceAtLeast(1)
}

/**
 * Renders the read-only memory text with incremental reveal. The revealed
 * prefix is joined into ONE [Text] (see doc above for why), scaled to the
 * viewer's 12sp/18sp monospace style. Grows the window when the user scrolls
 * near the bottom of the currently-revealed content; scrollState is owned by
 * the caller and both the verticalScroll and this component observe it.
 */
@Composable
private fun LazyRevealMemoryTextBody(
    initialContent: String,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val chunks = remember(initialContent) { memoryChunkByLines(initialContent) }
    // Reset the reveal window whenever the underlying text changes.
    var revealed by remember(initialContent) { mutableStateOf(memoryInitialRevealChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(initialContent, revealed) {
        if (total == 0) {
            "(empty)"
        } else {
            chunks.take(revealed.coerceIn(1, total)).joinToString("\n")
        }
    }

    // Auto-grow the window when the user scrolls within ~600px of the bottom of
    // the currently-revealed content. derivedStateOf keeps the predicate from
    // recomposing on every scroll pixel; it only flips at the threshold.
    val nearBottom by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max > 0 && max != Int.MAX_VALUE && scrollState.value >= max - 600
        }
    }
    LaunchedEffect(nearBottom, revealed, total) {
        if (nearBottom && revealed < total) {
            revealed = (revealed + MEM_LAZY_BATCH_CHUNKS).coerceAtMost(total)
        }
    }

    Text(
        text = shownText,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = ChatColors.primaryText,
        lineHeight = 18.sp,
        modifier = modifier,
    )
}

@Composable
internal fun SavedToast(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(bottom = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.memory_save_toast),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Detail body for a memory_write op-log entry. Shows the original written
 * content plus the tool result in read mode; in edit mode swaps to a
 * BasicTextField over the written content. Mirrors iOS
 * `MemoryWriteDetailView`. Toolbar (Edit / Save / Revoke) is rendered by the
 * parent sheet.
 */
@Composable
fun MemoryWriteDetailBody(
    record: MemoryToolRecord,
    isEditing: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    showSavedToast: Boolean,
) {
    val written = record.writtenContent ?: ""

    Box(modifier = Modifier.fillMaxSize()) {
        if (isEditing) {
            BasicTextField(
                value = editedContent,
                onValueChange = onEditedContentChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = ChatColors.primaryText,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (written.isNotEmpty()) {
                    SectionHeader(text = stringResource(R.string.memory_section_written_content))
                    SelectionContainer {
                        Text(
                            text = written,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ChatColors.primaryText,
                            lineHeight = 18.sp,
                        )
                    }
                }

                SectionHeader(text = stringResource(R.string.memory_section_tool_result))
                SelectionContainer {
                    Text(
                        text = record.output,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ChatColors.secondaryText,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        if (showSavedToast) SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Detail body for a memory_get op-log entry. Mirrors iOS `MemoryGetDetailView`.
 */
@Composable
fun MemoryGetDetailBody(record: MemoryToolRecord) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val keywords = record.keywords
        if (!keywords.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ChatColors.secondaryText,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = keywords,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                )
            }
        }
        SelectionContainer {
            Text(
                text = record.output,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = ChatColors.primaryText,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        color = ChatColors.secondaryText,
    )
}

/**
 * Confirmation dialog for revoking a memory_write entry. Wording matches iOS
 * `MemoryWriteDetailView` so users on both platforms see the same prompt.
 */
@Composable
fun RevokeConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_revoke_dialog_title)) },
        text = { Text(stringResource(R.string.memory_revoke_dialog_message)) },
        confirmButton = {
            MinisTextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.memory_action_revoke),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Result-of-mutation dialog: shows the success/not-found/I-O-error message
 * from [MemoryRepository.EntryMutationResult].
 */
@Composable
fun MutationResultDialog(
    result: MemoryRepository.EntryMutationResult,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val msg = when (result) {
        is MemoryRepository.EntryMutationResult.Success ->
            ctx.getString(R.string.memory_revoke_result_removed, result.dateStr)
        is MemoryRepository.EntryMutationResult.NotFound ->
            ctx.getString(R.string.memory_revoke_result_not_found)
        is MemoryRepository.EntryMutationResult.IOError ->
            ctx.getString(R.string.memory_revoke_result_io_error, result.message)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(msg) },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
