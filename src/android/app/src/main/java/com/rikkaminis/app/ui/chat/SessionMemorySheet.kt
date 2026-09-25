package com.rikkaminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.rikkaminis.app.R
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.ui.settings.SettingsSection
import com.rikkaminis.app.ui.settings.SettingsValueRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.EditWindow
import com.rikkaminis.app.ui.components.MEMORY_EDIT_WINDOW_MARGIN_CHUNKS
import com.rikkaminis.app.ui.components.MemoryFileEditorContent
import com.rikkaminis.app.ui.components.MemoryFileViewerContent
import com.rikkaminis.app.ui.components.buildEditWindow
import com.rikkaminis.app.ui.components.chunkText
import com.rikkaminis.app.ui.components.spliceEditWindow

/**
 * Bottom sheet showing memory state for the current session.
 *
 * Three modes share the [StandardChatSheet] shell:
 *   - List: the default — Section 1 auto-injected + Section 2 op-log
 *   - Detail.AutoFile: read-only / editable view of GLOBAL.md or a daily log
 *   - Detail.Write: per-entry view of a memory_write op-log row, with
 *                   Edit / Save / Revoke
 *   - Detail.Get:   per-entry view of a memory_get op-log row
 *
 * The sheet header swaps its leading slot to a back-arrow when in any detail
 * mode; the trailing close X always dismisses the entire sheet, mirroring iOS
 * `NavigationStack` push behavior inside `SessionMemoryView`.
 */
@Composable
fun SessionMemorySheet(
    memoryRepository: MemoryRepository,
    toolRecords: List<MemoryToolRecord>,
    onDismiss: () -> Unit,
    onRevokeRecord: (MemoryToolRecord) -> MemoryRepository.EntryMutationResult,
    onSaveRecord: (MemoryToolRecord, String) -> MemoryRepository.EntryMutationResult,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<MemorySheetMode>(MemorySheetMode.List) }
    val autoItems = remember(memoryRepository, context) { buildAutoInjectedItems(context, memoryRepository) }

    // Editing state for the active detail screen. Lives at the sheet level so
    // a single Save button in the header can read the latest buffer without
    // threading callbacks through the body composable.
    // [fix-memory-editor-jump-to-top] TextFieldState replaces the legacy
    // String buffer: the field stores its cursor/selection in its own state
    // immediately, so a tap mid-file no longer scrolls back to the top
    // (issuetracker 235693496).
    var isEditing by remember(mode) { mutableStateOf(false) }
    val editedState = remember(mode) { TextFieldState() }
    // [fix-memory-editor-windowed-edit] Windowed AutoFile editing: the editor
    // only holds the chunk window the user was looking at (O(window) layout
    // cost instead of O(file) on 200KB+ daily logs), and Save splices it back
    // byte-exactly via spliceEditWindow. editedFullBase is the file text
    // captured at edit entry — re-reading the file at Save would invalidate
    // the window offsets.
    var editedWindow by remember { mutableStateOf<EditWindow?>(null) }
    var editedFullBase by remember { mutableStateOf("") }
    var savedToastVisible by remember { mutableStateOf(false) }

    // Per-mode dialog state for revoke flow.
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var mutationResult by remember { mutableStateOf<MemoryRepository.EntryMutationResult?>(null) }

    // Auto-dismiss the "Saved" pill after 1.5s (matches iOS).
    LaunchedEffect(savedToastVisible) {
        if (savedToastVisible) {
            delay(1500)
            savedToastVisible = false
        }
    }

    // [fix-memory-editor-windowed-edit] The LaunchedEffect buffer fill is
    // gone: AutoFile edit now fills the editor with the chunk WINDOW at Edit
    // tap (needs the viewer's visible range), and Write edit fills the full
    // written content in its own onEdit — both are in the onEdit callbacks
    // below.

    val title = when (val m = mode) {
        MemorySheetMode.List -> stringResource(R.string.session_memory_title)
        is MemorySheetMode.AutoFile -> m.name
        is MemorySheetMode.Write -> m.record.title
        is MemorySheetMode.Get -> m.record.title
    }

    StandardChatSheet(
        title = title,
        onDismiss = onDismiss,
        leadingAction = if (mode != MemorySheetMode.List) {
            {
                IconButton(onClick = {
                    if (isEditing) {
                        // Cancel edit, stay on detail.
                        isEditing = false
                    } else {
                        mode = MemorySheetMode.List
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.memory_action_back),
                    )
                }
            }
        } else null,
    ) {
        when (val m = mode) {
            MemorySheetMode.List -> ListBody(
                memoryRepository = memoryRepository,
                autoItems = autoItems,
                toolRecords = toolRecords,
                onAutoItemClick = { item ->
                    mode = MemorySheetMode.AutoFile(
                        name = item.fileName,
                        content = item.content,
                        editable = true,
                    )
                },
                onWriteClick = { rec -> mode = MemorySheetMode.Write(rec) },
                onGetClick = { rec -> mode = MemorySheetMode.Get(rec) },
            )

            is MemorySheetMode.AutoFile -> Column(modifier = Modifier.fillMaxSize()) {
                val autoChunks = remember(m.content) { chunkText(m.content) }
                val autoListState = remember(m.content) { LazyListState() }
                DetailToolbar(
                    showEdit = m.editable && !isEditing,
                    showSave = m.editable && isEditing,
                    showRevoke = false,
                    onEdit = {
                        // [fix-memory-editor-windowed-edit] Window = the
                        // chunks the user was looking at ± margin (clamped
                        // inside buildEditWindow). The editor holds only the
                        // window, so text layout stays O(window) on files
                        // that grow past 200KB.
                        val visible = autoListState.layoutInfo.visibleItemsInfo
                        val first = visible.firstOrNull()?.index ?: 0
                        val last = (visible.lastOrNull()?.index ?: first) +
                            MEMORY_EDIT_WINDOW_MARGIN_CHUNKS
                        editedFullBase = m.content
                        editedWindow = buildEditWindow(
                            autoChunks,
                            first - MEMORY_EDIT_WINDOW_MARGIN_CHUNKS,
                            last,
                        )
                        isEditing = true
                        editedState.edit { replace(0, length, editedWindow?.text ?: m.content) }
                    },
                    onSave = {
                        try {
                            val newText = editedState.text.toString()
                            val full = editedWindow
                                ?.let {
                                    spliceEditWindow(editedFullBase, it.startOffset, it.endOffset, newText)
                                }
                                ?: newText
                            memoryRepository.saveFile(m.name, full)
                            // SOUL.md drives [SoulStore.cachedMetadata] which
                            // backs the chat-bubble header name. The raw
                            // saveFile() path here bypasses SoulStore.save(),
                            // so refresh the cache manually to keep readers
                            // in sync after an in-sheet edit.
                            if (m.name == "SOUL.md") {
                                com.rikkaminis.app.agent.SoulStore.refreshCache(context)
                            }
                            mode = MemorySheetMode.AutoFile(m.name, full, m.editable)
                            isEditing = false
                            savedToastVisible = true
                        } catch (_: Exception) { /* fall through; UI toast omitted on failure */ }
                    },
                    onRevoke = {},
                )
                // [P3-shared-editor] Editing mode uses shared monospace
                // editor (same as MemoryFileEditScreen in Settings) holding
                // the windowed buffer. Read-only mode calls the virtualized
                // viewer directly with the hoisted list state (the old
                // MemoryFileViewerBody wrapper was dead edit-branch code).
                if (isEditing) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MemoryFileEditorContent(
                            state = editedState,
                            errorMessage = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (savedToastVisible) {
                            SavedToast(
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MemoryFileViewerContent(
                            text = m.content,
                            emptyText = stringResource(R.string.memory_file_empty),
                            listState = autoListState,
                        )
                        if (savedToastVisible) {
                            SavedToast(modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }

            is MemorySheetMode.Write -> Column(modifier = Modifier.fillMaxSize()) {
                val canEditOrRevoke = m.record.writtenContent != null
                DetailToolbar(
                    showEdit = canEditOrRevoke && !isEditing,
                    showSave = canEditOrRevoke && isEditing,
                    showRevoke = canEditOrRevoke && !isEditing,
                    onEdit = {
                        isEditing = true
                        editedState.edit { replace(0, length, m.record.writtenContent ?: "") }
                    },
                    onSave = {
                        val result = onSaveRecord(m.record, editedState.text.toString())
                        if (result is MemoryRepository.EntryMutationResult.Success) {
                            // Update the displayed record in-place so a follow-up
                            // revoke targets the new body.
                            mode = MemorySheetMode.Write(
                                m.record.copy(writtenContent = editedState.text.toString())
                            )
                            isEditing = false
                            savedToastVisible = true
                        } else {
                            mutationResult = result
                        }
                    },
                    onRevoke = { showRevokeConfirm = true },
                )
                MemoryWriteDetailBody(
                    record = m.record,
                    isEditing = isEditing,
                    state = editedState,
                    showSavedToast = savedToastVisible,
                )
            }

            is MemorySheetMode.Get -> MemoryGetDetailBody(record = m.record)
        }
    }

    if (showRevokeConfirm && mode is MemorySheetMode.Write) {
        val writeMode = mode as MemorySheetMode.Write
        RevokeConfirmDialog(
            onConfirm = {
                showRevokeConfirm = false
                val result = onRevokeRecord(writeMode.record)
                mutationResult = result
                // On success the row is removed from toolRecords by the
                // ViewModel; pop back to list so the user sees the list
                // re-render without the row.
                if (result is MemoryRepository.EntryMutationResult.Success) {
                    mode = MemorySheetMode.List
                }
            },
            onDismiss = { showRevokeConfirm = false },
        )
    }

    val resultSnapshot = mutationResult
    if (resultSnapshot != null) {
        MutationResultDialog(
            result = resultSnapshot,
            onDismiss = { mutationResult = null },
        )
    }
}

/**
 * Compact toolbar rendered inside the body (above the main content) for
 * detail screens. Sits inside the body slot so it doesn't compete with the
 * sheet's standard header — keeps the header visually identical across all
 * modes (back-arrow / title / close X).
 */
@Composable
private fun DetailToolbar(
    showEdit: Boolean,
    showSave: Boolean,
    showRevoke: Boolean,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onRevoke: () -> Unit,
) {
    if (!showEdit && !showSave && !showRevoke) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showEdit) {
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.memory_action_edit),
                )
            }
        }
        if (showSave) {
            MinisTextButton(onClick = onSave) {
                Text(stringResource(R.string.memory_action_save))
            }
        }
        if (showRevoke) {
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.memory_action_revoke),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ListBody(
    memoryRepository: MemoryRepository,
    autoItems: List<AutoItem>,
    toolRecords: List<MemoryToolRecord>,
    onAutoItemClick: (AutoItem) -> Unit,
    onWriteClick: (MemoryToolRecord) -> Unit,
    onGetClick: (MemoryToolRecord) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // ── Section 1: Auto-injected ──
        item {
            SettingsSection(
                header = stringResource(R.string.memory_section_auto_injected),
                footer = stringResource(R.string.memory_section_auto_injected_footer),
            ) {
                autoItems.forEachIndexed { index, item ->
                    SettingsValueRow(
                        title = item.name,
                        value = item.detail,
                        onClick = { onAutoItemClick(item) },
                        showDivider = index < autoItems.size - 1,
                    )
                }
            }
        }

        // ── Section 2: Tool Activity (only if non-empty) ──
        if (toolRecords.isNotEmpty()) {
            item {
                SettingsSection(
                    header = stringResource(R.string.memory_section_tool_activity),
                    footer = stringResource(R.string.memory_section_tool_activity_footer),
                ) {
                    toolRecords.forEachIndexed { index, record ->
                        MemoryToolRow(
                            record = record,
                            onClick = {
                                if (record.isWrite) onWriteClick(record) else onGetClick(record)
                            },
                            showDivider = index < toolRecords.size - 1,
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    @Suppress("UNUSED_EXPRESSION") memoryRepository
}

/**
 * Row for a single memory tool call. Distinct from [SettingsValueRow] because
 * the body needs three lines: title, op-type pill, and a monospace preview of
 * the keywords / content the tool wrote or read.
 */
@Composable
private fun MemoryToolRow(
    record: MemoryToolRecord,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    val opLabel = if (record.isWrite) "memory_write" else "memory_get"
    val opColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = opColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        opLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = opColor,
                    )
                }
                if (record.preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        record.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * Record of a memory tool call in the current session.
 */
data class MemoryToolRecord(
    val title: String,
    val isWrite: Boolean,
    val preview: String,
    val output: String,
    val writtenContent: String? = null,
    val keywords: String? = null,
)

/**
 * Sheet navigation state. Held by [SessionMemorySheet] so a back arrow in the
 * standard sheet header can pop without unmounting the bottom sheet host.
 */
private sealed class MemorySheetMode {
    data object List : MemorySheetMode()
    data class AutoFile(val name: String, val content: String, val editable: Boolean) : MemorySheetMode()
    data class Write(val record: MemoryToolRecord) : MemorySheetMode()
    data class Get(val record: MemoryToolRecord) : MemorySheetMode()
}

internal data class AutoItem(
    val name: String,
    val detail: String,
    /** On-disk filename (e.g. "GLOBAL.md" or "2026-04-26.md") used to load full content. */
    val fileName: String,
    /** Cached full content, snapshot at sheet open — keeps tap responsiveness fast. */
    val content: String,
)

private fun buildAutoInjectedItems(context: Context, memoryRepository: MemoryRepository): List<AutoItem> {
    val items = mutableListOf<AutoItem>()

    // SOUL.md — persona / identity. Lives in the same memory dir as
    // GLOBAL.md and is auto-injected into the system prompt by
    // SystemPromptBuilder.identitySection(). Surfaced here so the user
    // can see + edit the same file the model sees, mirroring GLOBAL.md.
    val soulContent = memoryRepository.readFile("SOUL.md")
    if (soulContent.isNotBlank()) {
        val lineCount = soulContent.lines().size
        items.add(AutoItem(
            name = "SOUL.md",
            detail = "$lineCount lines (full)",
            fileName = "SOUL.md",
            content = soulContent,
        ))
    } else {
        items.add(AutoItem(
            name = "SOUL.md",
            detail = context.getString(R.string.memory_file_empty),
            fileName = "SOUL.md",
            content = "",
        ))
    }

    // GLOBAL.md
    val globalContent = memoryRepository.loadGlobalMd()
    if (globalContent.isNotBlank()) {
        val lineCount = globalContent.lines().size
        items.add(AutoItem(
            name = "GLOBAL.md",
            detail = "$lineCount lines (full)",
            fileName = "GLOBAL.md",
            content = globalContent,
        ))
    } else {
        items.add(AutoItem(
            name = "GLOBAL.md",
            detail = context.getString(R.string.memory_file_empty),
            fileName = "GLOBAL.md",
            content = "",
        ))
    }

    // Today + yesterday
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dateFmt.format(Date())
    val yesterday = dateFmt.format(Date(Date().time - 86400_000L))

    for (dateStr in listOf(today, yesterday)) {
        val fileName = "$dateStr.md"
        val label = if (dateStr == today) context.getString(R.string.time_today) else context.getString(R.string.time_yesterday)
        val content = memoryRepository.readFile(fileName)
        if (content.isNotBlank()) {
            val lineCount = content.lines().size
            val injected = minOf(lineCount, 200)
            val detail = if (lineCount > 200) "$injected/$lineCount lines injected" else "$lineCount lines (full)"
            items.add(AutoItem(
                name = "$label — $fileName",
                detail = detail,
                fileName = fileName,
                content = content,
            ))
        }
    }

    return items
}
