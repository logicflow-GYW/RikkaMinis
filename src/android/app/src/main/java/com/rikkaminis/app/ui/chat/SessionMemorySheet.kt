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
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Log tag for the memory sheet's IO/error paths. */
private const val MEMORY_SHEET_TAG = "SessionMemorySheet"

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
    // [deep-audit-1023 ISSUE-07] `buildAutoInjectedItems` reads GLOBAL.md /
    // SOUL.md / the two newest daily logs off disk and caches the full text in
    // each AutoItem. Its `remember` only keyed on (memoryRepository, context) —
    // both stable for the life of the sheet — so the snapshot was computed once
    // per sheet open and never recomputed, even though three paths inside this
    // same sheet rewrite exactly those files (AutoFile save, Write-record save,
    // Write revoke). After a save the list still showed the OLD line count and
    // re-opening the file fed the OLD cached content into the editor, so the
    // next save spliced stale text over the file. Bump the tick on every
    // successful mutation and the snapshot rebuilds.
    var autoItemsRefreshTick by remember { mutableStateOf(0) }
    // [meta-audit-1028 F-08] The snapshot is now built in TWO phases, because
    // the old single-phase form ran four `File.readText()` calls inside this
    // `remember` — i.e. on the composition thread of the frame that opened the
    // sheet. The volume is not small and is unbounded: on the reference device
    // (2026-10-28) GLOBAL.md 34 KB + today 93 KB + yesterday 137 KB = 264 KB of
    // UTF-8 decoded before the first frame could be drawn, and both daily logs
    // grow all day.
    //   phase 1 — [autoFiles]: one withContext(Dispatchers.IO) read of all four
    //             files, keyed on the same refresh tick (so every in-sheet
    //             mutation still triggers a rebuild);
    //   phase 2 — the item assembly stays in `remember` keyed on the tick +
    //             the landed snapshot; it is a pure function of those bytes, so
    //             composition never touches disk.
    // Null = the IO pass has not landed yet (first frame); the list renders
    // empty for one frame instead of blocking.
    var autoFiles by remember { mutableStateOf<AutoInjectedFiles?>(null) }
    LaunchedEffect(memoryRepository, autoItemsRefreshTick) {
        autoFiles = withContext(Dispatchers.IO) {
            readAutoInjectedFiles(
                readFile = memoryRepository::readFile,
                readGlobalMd = memoryRepository::loadGlobalMd,
            )
        }
    }
    val autoItems = remember(memoryRepository, context, autoItemsRefreshTick, autoFiles) {
        buildAutoInjectedItems(context, autoFiles)
    }

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
    // [meta-audit-1028 F-08] Save-failure channel for the AutoFile editor. The
    // catch below was `catch (_: Exception) { /* UI toast omitted on failure */ }`
    // and the editor was handed a hard-coded `errorMessage = null`, so a failed
    // `saveFile` (read-only dir, ENOSPC, SOUL rename-aside failure) left the
    // user in edit mode with the old text on disk and no signal at all — the
    // next read of the file showed the pre-edit body as if the save had never
    // been attempted. Reset per detail mode, cleared on a new Edit and on
    // success.
    var editErrorMessage by remember(mode) { mutableStateOf<String?>(null) }

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
                        editErrorMessage = null
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
                            editErrorMessage = null
                            isEditing = false
                            autoItemsRefreshTick++
                            savedToastVisible = true
                        } catch (e: Exception) {
                            // [meta-audit-1028 F-08] Fail VISIBLY: log with the
                            // stack (the old arm dropped the exception entirely,
                            // so a broken save left nothing in the log either)
                            // and hand the reason to the editor, which renders
                            // it in `errorMessage` below the field. Staying in
                            // edit mode is deliberate — the user's buffer is
                            // still the only copy of their text.
                            android.util.Log.w(
                                MEMORY_SHEET_TAG,
                                "memory file save failed (${m.name}): ${e.message}",
                                e,
                            )
                            editErrorMessage = e.message ?: e.toString()
                        }
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
                            errorMessage = editErrorMessage,
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
                            autoItemsRefreshTick++
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
                    autoItemsRefreshTick++
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
    // [meta-audit-1028 F-09] The pill names the op itself. It used to be
    // derived from `isWrite` (`if (record.isWrite) "memory_write" else
    // "memory_get"`), which mislabels the rollup: `isWrite` is the routing
    // discriminator for Write/Get detail, not a write-vs-read flag.
    val opLabel = record.toolName
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
    /**
     * [meta-audit-1028 F-09] The op's own name (`memory_write` /
     * `memory_get` / `memory_rollup`) — the label the row pill shows.
     * [isWrite] cannot do this job: it is a ROUTING discriminator (Write vs
     * Get detail body), so the rollup record is `isWrite = false` by design
     * and used to be labelled `memory_get`, i.e. a write-shaped tool shown as
     * a read-only search.
     */
    val toolName: String,
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

/**
 * [deep-audit-1023 ISSUE-07] Pure half of [buildAutoInjectedItems]: content ->
 * AutoItem for the always-present persona/config files (SOUL.md / GLOBAL.md).
 * No Context, no IO, so the "the item really reflects the bytes that are on
 * disk now" rule is unit-testable instead of only observable by eye in the
 * sheet — the sheet used to cache the whole list forever, and the refresh tick
 * is only worth anything if the rebuilt item carries the new text/count.
 */
internal fun autoItemFor(
    fileName: String,
    displayName: String,
    content: String,
    emptyLabel: String,
): AutoItem {
    if (content.isNotBlank()) {
        val lineCount = content.lines().size
        return AutoItem(
            name = displayName,
            detail = "$lineCount lines (full)",
            fileName = fileName,
            content = content,
        )
    }
    return AutoItem(
        name = displayName,
        detail = emptyLabel,
        fileName = fileName,
        content = "",
    )
}

/**
 * [deep-audit-1023 ISSUE-07] Pure half of [buildAutoInjectedItems] for the
 * daily logs. Returns null when the log has no content — an empty day is not
 * an "auto-injected" item at all. The injection cap comes from [maxInjectLines]
 * (the caller passes [AgentRuntimeLimitsPrefs.memoryInjectLines] via the
 * default arg), so the sheet tracks the user-tunable injection budget instead
 * of a hard-coded cap; a stale snapshot used to under-report the log the
 * user had just grown.
 */
internal fun dailyAutoItemFor(
    fileName: String,
    displayName: String,
    content: String,
    maxInjectLines: Int = AgentRuntimeLimitsPrefs.memoryInjectLines(),
): AutoItem? {
    if (content.isBlank()) return null
    val lineCount = content.lines().size
    val injected = minOf(lineCount, maxInjectLines)
    val detail = if (lineCount > maxInjectLines) "$injected/$lineCount lines injected" else "$lineCount lines (full)"
    return AutoItem(
        name = displayName,
        detail = detail,
        fileName = fileName,
        content = content,
    )
}

/**
 * [meta-audit-1028 F-08] The disk half of the sheet snapshot: every byte the
 * auto-injected section needs, read in ONE off-main pass.
 *
 * Deliberately takes the two repository reads as functions instead of a
 * [MemoryRepository]: the read SET and the today/yesterday derivation are the
 * parts that break silently (a wrong date window shows yesterday's log as
 * "today", an added file is never surfaced), so they are unit-testable on the
 * JVM with a fake map.
 */
internal data class AutoInjectedFiles(
    val soulMd: String,
    val globalMd: String,
    val todayName: String,
    val todayContent: String,
    val yesterdayName: String,
    val yesterdayContent: String,
    val maxInjectLines: Int,
)

/**
 * [meta-audit-1028 F-08] Read the four snapshot files. Must be called from
 * [Dispatchers.IO] — see [AutoInjectedFiles].
 */
internal fun readAutoInjectedFiles(
    readFile: (String) -> String,
    readGlobalMd: () -> String,
    nowMillis: Long = System.currentTimeMillis(),
    maxInjectLines: Int = AgentRuntimeLimitsPrefs.memoryInjectLines(),
): AutoInjectedFiles {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val today = dateFmt.format(Date(nowMillis))
    val yesterday = dateFmt.format(Date(nowMillis - 86400_000L))
    return AutoInjectedFiles(
        soulMd = readFile("SOUL.md"),
        globalMd = readGlobalMd(),
        todayName = today,
        todayContent = readFile("$today.md"),
        yesterdayName = yesterday,
        yesterdayContent = readFile("$yesterday.md"),
        maxInjectLines = maxInjectLines,
    )
}

/**
 * [meta-audit-1028 F-08] Item assembly — a PURE function of [files] plus the
 * string resources. [files] is null until the IO pass publishes its snapshot
 * (first frame), which renders as an empty section rather than a blocked
 * composition thread.
 */
private fun buildAutoInjectedItems(context: Context, files: AutoInjectedFiles?): List<AutoItem> {
    if (files == null) return emptyList()
    val items = mutableListOf<AutoItem>()
    val emptyLabel = context.getString(R.string.memory_file_empty)

    // SOUL.md — persona / identity. Lives in the same memory dir as
    // GLOBAL.md and is auto-injected into the system prompt by
    // SystemPromptBuilder.identitySection(). Surfaced here so the user
    // can see + edit the same file the model sees, mirroring GLOBAL.md.
    items.add(
        autoItemFor(
            fileName = "SOUL.md",
            displayName = "SOUL.md",
            content = files.soulMd,
            emptyLabel = emptyLabel,
        )
    )

    // GLOBAL.md
    items.add(
        autoItemFor(
            fileName = "GLOBAL.md",
            displayName = "GLOBAL.md",
            content = files.globalMd,
            emptyLabel = emptyLabel,
        )
    )

    // Today + yesterday. Empty days are skipped (not injected). The cap comes
    // from the snapshot, which read it on the IO pass.
    val todayLabel = context.getString(R.string.time_today)
    dailyAutoItemFor(
        fileName = "${files.todayName}.md",
        displayName = "$todayLabel — ${files.todayName}.md",
        content = files.todayContent,
        maxInjectLines = files.maxInjectLines,
    )?.let { items.add(it) }

    val yesterdayLabel = context.getString(R.string.time_yesterday)
    dailyAutoItemFor(
        fileName = "${files.yesterdayName}.md",
        displayName = "$yesterdayLabel — ${files.yesterdayName}.md",
        content = files.yesterdayContent,
        maxInjectLines = files.maxInjectLines,
    )?.let { items.add(it) }

    return items
}
