package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisTextButton

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.ui.components.EditWindow
import com.rikkaminis.app.ui.components.MEMORY_EDIT_WINDOW_MARGIN_CHUNKS
import com.rikkaminis.app.ui.components.MemoryFileEditorContent
import com.rikkaminis.app.ui.components.MemoryFileViewerContent
import com.rikkaminis.app.ui.components.buildEditWindow
import com.rikkaminis.app.ui.components.chunkText
import com.rikkaminis.app.ui.components.spliceEditWindow
import java.util.Date
import kotlinx.coroutines.launch

/**
 * [FIX-6 / F-224] Reserved file name that lives in the memory directory but is
 * NOT a user-manageable daily log. Mirrors the filter in
 * `MemoryRepository.listAllFiles()` (the data-layer owner of the same rule) —
 * this copy is the UI-side guard so a future listing change can't silently
 * re-arm the delete button on the user's persona file.
 */
private const val SOUL_FILE_NAME = com.rikkaminis.app.agent.SoulStore.FILE_NAME

/**
 * Settings-level memory file management.
 * Lists GLOBAL.md + daily logs in grouped card style.
 * Tapping a file navigates to a full-page viewer (read-only by default,
 * with an Edit action to switch into the text editor).
 * GLOBAL.md cannot be deleted.
 * Mirrors iOS MemoryManagementView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
    onFileClick: (fileName: String, isGlobal: Boolean) -> Unit = { _, _ -> },
) {
    var files by remember { mutableStateOf<List<MemoryRepository.MemoryFileInfo>>(emptyList()) }
    var deleteFileName by remember { mutableStateOf<String?>(null) }
    // [Mem-list] File list "View more": files are fully listed but when there
    // are many (daily logs accrete one file/day), cap the visible rows and let
    // the user expand — prevents the section growing unboundedly tall.
    var showAllFiles by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // [T-memory-global-toggle-settings-ui-android] Global default for
    // newly-created sessions. Stored separately from per-session
    // memoryEnabled (which lives in the sessions DB row) so toggling
    // here never retroactively rewrites existing chats. Read once on
    // entry; the Switch's onCheckedChange writes back synchronously
    // and updates the local state mirror.
    var globalMemoryOn by remember {
        mutableStateOf(com.rikkaminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    }

    // [FIX-6 / F-241] Re-read the snapshot when `memory.global.enabled` is
    // written OUTSIDE this screen — `minis-config set memory.enabled …`
    // (ConfigBuiltins registers the same prefs+key) or a backup-restore.
    // Without it the Switch keeps rendering the value captured at open time.
    DisposableEffect(context) {
        // Literals rather than MemoryGlobalPrefs.PREFS / .KEY_GLOBAL_ENABLED
        // because both are private and that file is outside this batch's
        // ownership (see FIX-6 REPORT.md — flagging it as the single-source
        // follow-up). ConfigBuiltins already spells the same pair literally in
        // its registerMemory(), so this matches the existing convention.
        val prefs = context.applicationContext.getSharedPreferences(
            "minis_memory_prefs",
            android.content.Context.MODE_PRIVATE,
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "memory.global.enabled") {
                    globalMemoryOn = com.rikkaminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        files = memoryRepository.listAllFiles()
    }

    // top-level page: rely on system back gesture / bottom nav (no back arrow)
    SettingsScaffold(title = stringResource(R.string.memory_title), onBack = null) {
        // Always-visible global toggle — sits above the file list so the
        // user finds it whether or not any memory files exist yet.
        SettingsSection(
            header = stringResource(R.string.settings_memory_global_header),
            footer = stringResource(R.string.settings_memory_global_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_memory_global_enabled_title),
                subtitle = stringResource(R.string.settings_memory_global_enabled_subtitle),
                checked = globalMemoryOn,
                onCheckedChange = { newValue ->
                    globalMemoryOn = newValue
                    com.rikkaminis.app.data.MemoryGlobalPrefs.setGlobalEnabled(context, newValue)
                },
                showDivider = false,
            )
        }

        if (files.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.memory_empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SettingsSection(
                header = stringResource(R.string.memory_section_files),
                footer = stringResource(R.string.memory_section_footer),
            ) {
                // [Mem-list] Cap visible file rows at 20 to keep the section from
                // growing unboundedly tall as daily logs accrete; a "View more"
                // button discloses the full list.
                val fileCap = 20
                val visibleFiles = if (showAllFiles) files else files.take(fileCap)
                visibleFiles.forEachIndexed { index, file ->
                    MemoryFileRow(
                        file = file,
                        onClick = { onFileClick(file.name, file.isGlobal) },
                        // [FIX-6 / F-224] SOUL.md lives in this same directory
                        // (SoulStore.fileLocation) but is NOT a daily log: it
                        // holds the user's persona, and deleting it makes
                        // SoulStore.ensureExists re-seed DEFAULT_CONTENT on the
                        // next launch — a silent reset with no undo. The
                        // repository now excludes it from listAllFiles() too;
                        // this is the belt-and-braces guard on the UI side.
                        onDelete = if (!file.isGlobal && file.name != SOUL_FILE_NAME) {
                            { deleteFileName = file.name }
                        } else {
                            null
                        },
                    )
                    if (index < visibleFiles.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
                if (files.size > fileCap) {
                    TextButton(
                        onClick = { showAllFiles = !showAllFiles },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (showAllFiles) R.string.memory_section_view_less
                                else R.string.memory_section_view_more
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Delete confirmation
    if (deleteFileName != null) {
        AlertDialog(
            onDismissRequest = { deleteFileName = null },
            title = { Text(stringResource(R.string.memory_delete_confirm_title, deleteFileName!!)) },
            text = { Text(stringResource(R.string.memory_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteFileName?.let {
                        memoryRepository.deleteFile(it)
                        files = memoryRepository.listAllFiles()
                    }
                    deleteFileName = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteFileName = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }


}

@Composable
private fun MemoryFileRow(
    file: MemoryRepository.MemoryFileInfo,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (file.fileSize.isNotBlank()) {
                        Text(
                            file.fileSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                Text(
                    file.modifiedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.preview.isNotBlank()) {
                Text(
                    file.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}


/**
 * Full-page memory file viewer/editor, matching iOS MemoryFileEditView.
 * Monospaced text.
 *
 * [T-android-memory-file-jank] Opens READ-ONLY by default; the user taps Edit
 * to switch to the text field. This is the fix for the reported jank: memory
 * files grow past 200KB, and the editor path handed the whole string to one
 * text field, which Compose re-measures in full on every scroll frame
 * (measured: 60KB -> 26ms/frame, 110KB -> 200ms/frame with the rendered-frame
 * count collapsing ~6x). The read-only path renders a virtualized LazyColumn
 * ([MemoryFileViewerContent]) so cost is O(viewport) instead of O(file).
 *
 * Opening read-only is also the honest default for these files: GLOBAL.md is
 * read-mostly, and daily logs are written by the agent, so "browse then
 * optionally edit" matches how the file is actually used.
 *
 * [T-global-memory-save-always-visible] Once editing, the Save button stays
 * ALWAYS visible. Previously Save was gated on a `hasChanges` flag that only
 * flipped true inside the field's onValueChange. Programmatic content changes
 * (paste, IME commit, state restore) don't always route through onValueChange,
 * so Save could fail to appear after a paste until the user typed another key
 * — the exact symptom reported on iOS/macOS (XIN msg 41384). Keeping Save
 * permanently visible removes the dependency entirely; saveFile is idempotent
 * so a no-op Save on unchanged content is harmless.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    // [fix-memory-editor-windowed-edit] Windowed editing on 200KB+ files:
    // fullBase is the file text captured at entry (source of truth for the
    // read-only viewer AND for splice-back); the editor state only holds the
    // chunk window built at Edit tap from the viewer's visible range, so text
    // layout stays O(window) instead of O(file). readFile is non-suspend so
    // it can run directly in remember; remember(fileName) reloads on switch.
    var fullBase by remember(fileName) { mutableStateOf(memoryRepository.readFile(fileName)) }
    val chunks = remember(fullBase) { chunkText(fullBase) }
    val listState = remember(fileName) { LazyListState() }
    val state = remember(fileName) { TextFieldState() }
    var editWindow by remember(fileName) { mutableStateOf<EditWindow?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // [T-android-memory-file-jank] false = virtualized read-only viewer.
    var isEditing by remember(fileName) { mutableStateOf(false) }
    val context = LocalContext.current
    // [T-memory-save-toast-feedback] Confirm Save actually committed by
    // flashing a toast — previously the Save tap silently closed nothing,
    // showed no state change, and the user had no signal that the edit
    // landed (user-reported confusion). Reuses the existing
    // memory_save_toast string already wired for the per-chat memory
    // detail editor's SavedToast so the wording stays consistent.
    val savedToastText = stringResource(R.string.memory_save_toast)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-android-memory-file-jank] Edit <-> Save swap. In
                    // read-only mode we show the pencil; while editing we show
                    // Save (always visible — see the KDoc note above).
                    if (!isEditing) {
                        IconButton(onClick = {
                            // [fix-memory-editor-windowed-edit] Window = the
                            // chunks the user was looking at ± margin
                            // (clamped inside buildEditWindow). The editor
                            // holds only the window, so text layout stays
                            // O(window) on files that grow past 200KB.
                            val visible = listState.layoutInfo.visibleItemsInfo
                            val first = visible.firstOrNull()?.index ?: 0
                            val last = (visible.lastOrNull()?.index ?: first) +
                                MEMORY_EDIT_WINDOW_MARGIN_CHUNKS
                            editWindow = buildEditWindow(
                                chunks,
                                first - MEMORY_EDIT_WINDOW_MARGIN_CHUNKS,
                                last,
                            )
                            state.edit { replace(0, length, editWindow?.text ?: fullBase) }
                            isEditing = true
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = stringResource(R.string.memory_action_edit),
                            )
                        }
                    } else {
                        // [T-global-memory-save-always-visible] Always render Save —
                        // no hasChanges gate (see KDoc above).
                        MinisTextButton(onClick = {
                            try {
                                // [fix-memory-editor-windowed-edit] Splice the
                                // edited window back into the full file
                                // (byte-exact, offsets captured at edit
                                // entry), then refresh the viewer's source.
                                val newText = state.text.toString()
                                val full = editWindow
                                    ?.let {
                                        spliceEditWindow(fullBase, it.startOffset, it.endOffset, newText)
                                    }
                                    ?: newText
                                memoryRepository.saveFile(fileName, full)
                                fullBase = full
                                saveError = null
                                android.widget.Toast.makeText(
                                    context,
                                    savedToastText,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                isEditing = false
                            } catch (e: Exception) {
                                saveError = e.message
                            }
                        }) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // [T-android-ime-occlusion-0920] Bare Scaffold does NOT consume
                // WindowInsets.ime (contentWindowInsets = systemBars only), and
                // edge-to-edge + adjustResize no longer resizes the window for
                // the keyboard. Without this the full-height BasicTextField's
                // own "scroll caret into view" logic scrolls the caret behind
                // the IME. Host-layer fix — do NOT push this into the leaf
                // editor (it would double up inside ModalBottomSheet).
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // [T-android-memory-file-jank] Read-only mode renders the
            // virtualized viewer (hoisted list state feeds the edit window);
            // edit mode keeps the shared monospace editor holding the window.
            // The IME padding above stays on the host Column so the caret
            // scroll-into-view inside the editor behaves as before.
            if (isEditing) {
                // [P3-shared-editor] Shared monospace editor, also used by
                // SessionMemorySheet auto-file detail.
                MemoryFileEditorContent(
                    state = state,
                    errorMessage = saveError,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // No emptyText here: an empty-state label would flash "Empty"
                // on every open of a non-empty file.
                MemoryFileViewerContent(
                    text = fullBase,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )
            }

            // Footer text
            if (isGlobal) {
                Text(
                    stringResource(R.string.memory_global_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
