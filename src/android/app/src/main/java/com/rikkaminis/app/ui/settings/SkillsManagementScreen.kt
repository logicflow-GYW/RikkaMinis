package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.MinisTextButton

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.rikkaminis.app.ui.components.DialogTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.repository.SkillRepository
import com.rikkaminis.app.ui.markdown.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.rikkaminis.app.ui.theme.ChatColors

/**
 * Inline state for the three update actions on [SkillDetailScreen]
 * (T154). Drives the status row right under the buttons so the user
 * sees a spinner / stringResource(R.string.skill_detail_updated) / failure reason without leaving the
 * detail screen.
 */
private sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data class InProgress(val label: String) : UpdateStatus()
    data object Done : UpdateStatus()
    data class Failed(val reason: String) : UpdateStatus()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagementScreen(
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onSkillClick: (String) -> Unit = {},
    onMinisSkillsClick: () -> Unit = {},
) {
    val skills by skillRepository.skills.collectAsState()
    var showImportSheet by remember { mutableStateOf(false) }
    var deleteSkillId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    // [deep-audit-1039 B-05] Scope for the repository writes the list screen
    // triggers (delete confirm): they are disk/DB work and must not run on the
    // Main thread, same rule the import sheet below follows.
    val scope = rememberCoroutineScope()

    // Sort preference — persisted like the file browser's (SharedPreferences,
    // default name ascending). Mirrors iOS skillsList.sortKey/sortAscending.
    val sortPrefs = remember {
        context.getSharedPreferences("skills_prefs", android.content.Context.MODE_PRIVATE)
    }
    var sortByModified by remember { mutableStateOf(sortPrefs.getBoolean("sort_by_modified", false)) }
    var sortAscending by remember { mutableStateOf(sortPrefs.getBoolean("sort_ascending", true)) }

    val filteredSkills by remember(skills) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            val base = if (q.isEmpty()) skills
            else skills.filter {
                it.name.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            val sorted = if (sortByModified) base.sortedBy { it.updatedAt }
            else base.sortedBy { it.name.lowercase() }
            if (sortAscending) sorted else sorted.reversed()
        }
    }

    // T-skillscan: rescan on screen entry so a skill that was installed in
    // the active chat (e.g. via shell `git clone` into skillsDir, which
    // bypasses the SKILL.md file_write hook) is picked up the moment the
    // user opens this list, instead of requiring a process restart.
    // [fix/da1032-e] reloadFromDisk() re-scans the SQLite registry AND walks
    // skillsDir on disk (SkillRepository.loadAll: rawQuery + per-row
    // parseSkillMdOnDisk + listFiles). Doing that inline in LaunchedEffect ran
    // the whole scan on the Main dispatcher every time the screen appeared —
    // the Settings-screen jank/ANR shape [audit-1032 ISSUE-06]. Same treatment
    // the paste-import branch below already uses: keep the call, move its
    // thread. The StateFlow update inside is thread-safe, and nothing after
    // this effect reads the result synchronously.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            skillRepository.reloadFromDisk()
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.skill_title),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        // T75-part1 already moved Add off the FAB onto the top-bar action
        // slot; kept here for visual continuity with the rest of Settings.
        actions = {
            // Sort menu — same structure as the file browser's MoreMenu
            // (sort-key items with check marks, divider, direction toggle).
            Box {
                var sortMenuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.filebrowser_sort_by),
                    )
                }
                com.rikkaminis.app.ui.components.MinisMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_name)) },
                        leadingIcon = {
                            if (!sortByModified) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = false
                            sortPrefs.edit().putBoolean("sort_by_modified", false).apply()
                            sortMenuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.filebrowser_sort_modified)) },
                        leadingIcon = {
                            if (sortByModified) Icon(Icons.Filled.Check, contentDescription = null)
                            else Spacer(modifier = Modifier.size(24.dp))
                        },
                        onClick = {
                            sortByModified = true
                            sortPrefs.edit().putBoolean("sort_by_modified", true).apply()
                            sortMenuExpanded = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(
                                if (sortAscending) R.string.filebrowser_sort_ascending
                                else R.string.filebrowser_sort_descending
                            ))
                        },
                        leadingIcon = {
                            Icon(
                                if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            sortAscending = !sortAscending
                            sortPrefs.edit().putBoolean("sort_ascending", sortAscending).apply()
                            sortMenuExpanded = false
                        },
                    )
                }
            }
            IconButton(onClick = { showAddMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.skill_add))
            }
        },
    ) {
        // Search bar — filters by skill name + description. Sits above the
        // section so it's discoverable without scrolling on a long list.
        if (skills.isNotEmpty()) {
            DialogTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.skills_search_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp),
            )
        }

        SettingsSection(
            header = stringResource(R.string.skill_section_installed),
            footer = stringResource(R.string.skill_section_footer),
        ) {
            if (skills.isEmpty()) {
                // Centred empty-state inside the section card so the empty
                // state is visually owned by the card.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                    Text(
                        stringResource(R.string.skill_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.skill_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (filteredSkills.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_search_no_match, searchQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                )
            } else {
                filteredSkills.forEachIndexed { index, skill ->
                    val (badgeIcon, badgeColor) = sourceIconAndColor(skill.importSource)
                    SettingsRow(
                        title = skill.name,
                        subtitle = skill.description.takeIf { it.isNotEmpty() }?.let { stripMarkdown(it) },
                        showChevron = true,
                        showDivider = index < filteredSkills.size - 1,
                        onClick = { onSkillClick(skill.id) },
                        trailing = {
                            // Source badge + Switch. Wrapping in a Row keeps
                            // them visually grouped before the row's chevron.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    badgeIcon,
                                    contentDescription = null,
                                    tint = badgeColor,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = skill.isEnabled,
                                    // [deep-audit-1039 B-05] setEnabled is a
                                    // SQLite UPDATE (+ a StateFlow rewrite of
                                    // the list): off Main like every other
                                    // repository write on this screen. The row
                                    // re-renders through the `skills` flow the
                                    // screen already collects.
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                skillRepository.setEnabled(skill.id, enabled)
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAddMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenu = false },
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            showImportSheet = true
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAddMenu = false
                            onMinisSkillsClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.skill_minis_skills_modal), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (showImportSheet) {
        SkillImportSheet(
            skillRepository = skillRepository,
            onDismiss = { showImportSheet = false },
        )
    }

    if (deleteSkillId != null) {
        val skill = skills.find { it.id == deleteSkillId }
        AlertDialog(
            onDismissRequest = { deleteSkillId = null },
            title = { Text(stringResource(R.string.skill_delete_confirm_title, skill?.name ?: "skill")) },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // [deep-audit-1039 B-05] skillRepository.delete does two
                    // SQLite DELETEs plus a recursive directory walk — running
                    // it inline on the click (Main) froze the list for the
                    // whole delete. Same treatment the import sheet and the
                    // detail-page delete already use: keep the call, move its
                    // thread. The StateFlow update inside is thread-safe and
                    // the row disappears through the collectAsState above.
                    deleteSkillId?.let { id ->
                        scope.launch {
                            withContext(Dispatchers.IO) { skillRepository.delete(id) }
                        }
                    }
                    deleteSkillId = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteSkillId = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

// ─── Import Sheet (3 modes: URL / Paste / File) ─────────────────────────────

/**
 * [FIX-R22 B32a] Zip magic-byte sniff, shared by the import sheet
 * (SkillImportSheet) and the detail-page update launcher (SkillDetailScreen)
 * — the detail path used to lack any archive recognition, so picking a
 * .zip/.skill to refresh an existing skill piped the raw bytes through
 * readText() into garbage UTF-8 and surfaced as "Invalid SKILL.md".
 *
 * PK\x03\x04 = standard ZIP local file header; PK\x05\x06 is the
 * empty-archive EOCD; PK\x07\x08 is a spanned/split archive. Accept all
 * three so split exports from less-common zip tools don't fall into the
 * text path. URI may not preserve the filename extension (some
 * DocumentsProviders / share targets strip it), so magic-byte sniffing is
 * more reliable than `.zip` / `.skill` checks. Pure — JVM-covered.
 */
internal fun isZipArchiveBytes(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

// [deep-audit-1045 B-07] `readUriBytes` lived here and ran a bare
// `use { it.readBytes() }` — the picked bundle went into the heap whole before
// `isZipArchiveBytes` could sniff it, so the 32 MiB expanded-size guard in
// `SkillRepository.readZipEntries` never got a chance to matter. The bounded
// replacement is `readSafBytes` in SafImportRead.kt (one cap, queried SIZE as a
// cheap early reject, and a hard stop one byte past the cap).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillImportSheet(
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var urlText by remember { mutableStateOf("") }
    var pasteContent by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // File picker
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [F-13] The file tab had NO busy gate: loading a bundle reads up to
        // MAX_SKILL_IMPORT_BYTES and may unzip it on IO while the sheet stayed
        // fully interactive, so a second tap on "Choose File" started a second
        // import that raced the first. `importFromContent` then hit the
        // already-inserted id and the loser reported the *content* error
        // ("invalid SKILL.md") for what was really "that one is still
        // importing". Same single-flight flag the URL / paste rows disable
        // their submit button with; it now also paints the button label.
        if (isLoading) return@rememberLauncherForActivityResult
        isLoading = true
        // [T6-M5] Reading a skill bundle (scripts/ + references/ + assets/) and
        // unzipping it ran on the activity-result callback thread (Main), so a
        // large import froze the sheet for its whole duration. Read + parse on
        // IO and apply the outcome back on Main.
        scope.launch {
            // [F-13] Failure text is a resource lookup, not an English literal
            // (the four literals that used to live here showed English in every
            // locale). Resolved inside the IO block through `context.getString`
            // so the outcome stays a single value — the old `var failure: String?`
            // was a shared mutable cell written on IO and read on Main.
            val failure: String? = try {
                withContext(Dispatchers.IO) {
                    // [T-android-skill-import-zip] [FIX-R22 B32a] Byte read +
                    // zip-magic sniff now live in the shared helpers
                    // (readUriBytes / isZipArchiveBytes) so the detail-page
                    // update launcher below reuses the same recognition
                    // instead of a diverging copy.
                    //
                    // [deep-audit-1045 B-07] …and the read is bounded: the
                    // picked bundle used to go into the heap whole. A refused
                    // pick is reported as "too large", not as a read failure.
                    val read = readSafBytes(context.contentResolver, uri, MAX_SKILL_IMPORT_BYTES)
                    val bytes = (read as? SafRead.Ok)?.bytes
                    if (bytes == null) {
                        // safTooLargeText is the shared wording of the helper
                        // that produced the refusal (its own i18n follow-up is
                        // registered separately, outside this batch's file set).
                        return@withContext if (read is SafRead.TooLarge) {
                            safTooLargeText(MAX_SKILL_IMPORT_BYTES)
                        } else {
                            context.getString(R.string.skill_file_error_read)
                        }
                    }
                    if (isZipArchiveBytes(bytes)) {
                        val result = skillRepository.importFromArchive(
                            java.io.ByteArrayInputStream(bytes),
                        )
                        if (result == null) {
                            return@withContext context.getString(R.string.skill_file_error_archive)
                        }
                        return@withContext null
                    }
                    val content = String(bytes, Charsets.UTF_8)
                    val result = skillRepository.importFromContent(content, SkillRepository.ImportSource.FILE)
                    if (result == null) {
                        return@withContext context.getString(R.string.skill_import_error_format)
                    }
                    return@withContext null
                }
            } catch (e: Exception) {
                // The throwable's own message is data, not prose: only the
                // frame is localized.
                context.getString(
                    R.string.skill_file_error_detail,
                    e.message ?: e.javaClass.simpleName,
                )
            }
            // [F-13] `isLoading` is cleared on the MAIN side of the launch and
            // never inside the IO block: the sheet owns the flag, and a flag
            // flipped from a background thread is a cross-thread state write
            // (the read-modify-write shape this repo pins elsewhere).
            isLoading = false
            if (failure == null) onDismiss() else errorText = failure
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.skill_import_modal_title), style = MaterialTheme.typography.titleMedium)

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_url), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_paste), modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2; errorText = null }) {
                    Text(stringResource(R.string.skill_import_tab_file), modifier = Modifier.padding(12.dp))
                }
            }

            when (selectedTab) {
                0 -> {
                    Text(
                        stringResource(R.string.skill_import_url_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = urlText,
                        onValueChange = { urlText = it; errorText = null },
                        placeholder = stringResource(R.string.skill_import_url_placeholder),
                        singleLine = true,
                        isError = errorText != null,
                    )
                }
                1 -> {
                    Text(
                        stringResource(R.string.skill_import_paste_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    DialogTextField(
                        value = pasteContent,
                        onValueChange = { pasteContent = it; errorText = null },
                        modifier = Modifier.height(200.dp),
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        isError = errorText != null,
                    )
                }
                2 -> {
                    Text(
                        stringResource(R.string.skill_import_file_instruction),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MinisTextButton(
                        onClick = { fileLauncher.launch("*/*") },
                        // [F-13] Disabled while an import is in flight — the
                        // label doubles as the progress indicator the file tab
                        // never had (the other two tabs only disable Submit).
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (isLoading) stringResource(R.string.skill_import_in_progress)
                            else stringResource(R.string.skill_import_file_button),
                        )
                    }
                }
            }

            if (errorText != null) {
                Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (selectedTab < 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    MinisTextButton(
                        onClick = {
                            when (selectedTab) {
                                0 -> {
                                    if (urlText.isBlank()) { errorText = context.getString(R.string.skill_import_error_no_url); return@MinisTextButton }
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            // importFromGitHub fetches SKILL.md AND recurses through
                                            // the GitHub Contents API so bundled scripts/references
                                            // land on disk. The old downloadSkillFromUrl +
                                            // importFromContent path only grabbed SKILL.md, leaving
                                            // skills like douyin-downloader non-runnable because the
                                            // sibling python scripts never made it to local storage.
                                            val result = skillRepository.importFromGitHub(urlText.trim())
                                            if (result != null) onDismiss() else errorText = context.getString(R.string.skill_import_error_invalid)
                                        } catch (e: Exception) {
                                            // [F-13] localized frame; the
                                            // throwable message stays as data.
                                            errorText = context.getString(
                                                R.string.skill_file_error_detail,
                                                e.message ?: e.javaClass.simpleName,
                                            )
                                        }
                                        finally { isLoading = false }
                                    }
                                }
                                1 -> {
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            // [audit-0917] Mirror the URL branch: importFromContent does
                                            // disk IO, so it must not run on the Main thread (ANR), and
                                            // an exception must be caught, not propagated out of a click
                                            // handler (crash).
                                            // [fix/da1023-E] The comment asked for the mirror; the
                                            // code never had it — parse + SQLite write + SKILL.md write
                                            // all ran on Main (the URL tab above already wraps in
                                            // withContext(Dispatchers.IO)). Same wrap now; catch /
                                            // finally / error copy unchanged.
                                            val result = withContext(Dispatchers.IO) {
                                                skillRepository.importFromContent(pasteContent)
                                            }
                                            if (result != null) onDismiss()
                                            else errorText = context.getString(R.string.skill_import_error_format)
                                        } catch (e: Exception) {
                                            // [F-13] localized frame; the
                                            // throwable message stays as data.
                                            errorText = context.getString(
                                                R.string.skill_file_error_detail,
                                                e.message ?: e.javaClass.simpleName,
                                            )
                                        }
                                        finally { isLoading = false }
                                    }
                                }
                            }
                        },
                        enabled = when (selectedTab) { 0 -> urlText.isNotBlank() && !isLoading; 1 -> pasteContent.isNotBlank() && !isLoading; else -> false },
                    ) { Text(if (isLoading) stringResource(R.string.skill_import_in_progress) else stringResource(R.string.skill_import_submit)) }
                }
            }
        }
    }
}

// ─── Skill Detail Sheet ─────────────────────────────────────────────────────

// ─── Skill Detail Screen (full page, matching iOS) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailScreen(
    skillId: String,
    skillRepository: SkillRepository,
    onBack: () -> Unit,
    onFileClick: (String, String) -> Unit = { _, _ -> },
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    var deleted by remember { mutableStateOf(false) }

    if (skill == null && !deleted) {
        // [F-2 sweep1013] onBack() during composition re-fires on every
        // recomposition of this branch (double-pop risk) — wrap like
        // ModelEntryDetailScreen.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (skill == null) return // Already deleted, just stop rendering

    var showDeleteDialog by remember { mutableStateOf(false) }
    // T155: name editing now happens in a modal dialog instead of an inline
    // text field — matches the rest of the app's "edit a single string"
    // affordances and avoids the layout jiggle the inline OutlinedTextField
    // caused on a row that's otherwise quiet.
    var showEditNameDialog by remember { mutableStateOf(false) }
    // Keys [skill.id, skill.name]: this screen re-resolves `skill` from the
    // repository StateFlow, so the SAME composition can see skill.name change
    // (an agent / CLI edit to the SKILL.md frontmatter is re-synced into the
    // row by loadAll → SkillMetadataSync.reconcile, and the post-tool hook
    // fires reloadFromDisk while this screen is open). The dialog drafts from
    // skill.name at first composition, so an un-keyed remember handed the
    // dialog the pre-edit name: confirming the rename wrote the OLD name back
    // over the external edit (snapshot deadlock, [audit-1032 ISSUE-06]).
    // Cross-skill staleness is NOT the path here — each skill detail is its own
    // nav entry (composable(SKILL_DETAIL) per skillId), so it gets a fresh
    // composition; skill.id is keyed anyway as the "keys the source" anchor.
    var editName by remember(skill.id, skill.name) { mutableStateOf(skill.name) }
    // [F-13] The rename used to be fire-and-forget: the click closed the dialog
    // and dropped `skillRepository.update`'s Boolean, so a write that returned
    // false (the id is no longer in the repository — the skill was deleted or
    // reloaded out from under this detail screen) looked exactly like a
    // successful rename that silently did nothing. Two states fix that shape:
    // `isRenaming` is the busy gate (the write rewrites SKILL.md, so a
    // double-tap must not fire two of them) and `renameError` is shown inline
    // while the dialog STAYS OPEN so the user can retry or cancel.
    var isRenaming by remember(skill.id, skill.name) { mutableStateOf(false) }
    var renameError by remember(skill.id, skill.name) { mutableStateOf<String?>(null) }

    // T154: replace stub state. Each update action drives `updateStatus` which
    // surfaces inline feedback ("Updating…" / stringResource(R.string.skill_detail_updated) / "Failed: …") next to
    // the button row, clearing itself ~3 s after a terminal result so a slow
    // user can still read it without leaving a permanent banner.
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Idle) }
    // [T-android-skill-export] Guards the share button while the zip is being
    // built off the main thread, so a double-tap can't spawn two exports.
    var isExporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(updateStatus) {
        if (updateStatus is UpdateStatus.Done || updateStatus is UpdateStatus.Failed) {
            kotlinx.coroutines.delay(3000)
            updateStatus = UpdateStatus.Idle
        }
    }
    val fileUpdateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_reading))
        // [FIX-R22 B32a] Reading + parsing + the repository write all do disk
        // IO; they used to run inline on the activity-result callback thread
        // (Main), so a large update froze the screen — same fix as
        // SkillImportSheet [T6-M5]. Read + parse on IO, apply the outcome
        // back on Main.
        scope.launch {
            var failureRes: Int? = null
            var failureMsg: String? = null
            val ok = withContext(Dispatchers.IO) {
                try {
                    val read = readSafBytes(context.contentResolver, uri, MAX_SKILL_IMPORT_BYTES)
                    val bytes = (read as? SafRead.Ok)?.bytes
                    when {
                        bytes == null -> {
                            // [deep-audit-1045 B-07] Bounded now; an oversized
                            // bundle gets its own wording.
                            // [F-13] Same family as the import sheet: the
                            // refusal wording stays in the shared helper
                            // (its own i18n follow-up is registered
                            // separately); everything else is a resource.
                            if (read is SafRead.TooLarge) {
                                failureMsg = safTooLargeText(MAX_SKILL_IMPORT_BYTES)
                            } else {
                                failureRes = R.string.skill_file_error_read
                            }
                            false
                        }
                        // [FIX-R22 B32a] A .zip/.skill bundle used to be piped
                        // through readText() into garbage UTF-8 and surfaced as
                        // "Invalid SKILL.md". Route archives through the shared
                        // magic-byte sniff + importFromArchive with the
                        // existing skill's id as preferredId — it replaces the
                        // record in place and extracts bundled siblings
                        // (scripts/, references/) alongside it.
                        isZipArchiveBytes(bytes) -> {
                            val result = skillRepository.importFromArchive(
                                java.io.ByteArrayInputStream(bytes),
                                preferredId = skill.id,
                            )
                            if (result != null) {
                                true
                            } else {
                                failureRes = R.string.skill_file_error_archive
                                false
                            }
                        }
                        else -> {
                            val content = String(bytes, Charsets.UTF_8)
                            if (content.isBlank()) {
                                failureRes = R.string.skill_detail_error_empty
                                false
                            } else {
                                // Re-parse the supplied SKILL.md so we can push
                                // name + description + body into the existing
                                // skill record without creating a new entry.
                                val parsed = skillRepository.parseSkillMdPublic(content)
                                if (parsed == null) {
                                    failureRes = R.string.skill_detail_error_invalid
                                    false
                                } else {
                                    skillRepository.update(
                                        id = skill.id,
                                        name = parsed.name,
                                        description = parsed.description,
                                        body = parsed.body,
                                        // [fix/audit-1015-b4-3] Carry the parsed
                                        // version: the update() signature supports
                                        // it but it was never passed, so a file
                                        // update kept the old version string.
                                        version = parsed.version,
                                        // [fix/da1023-E] Same contract as
                                        // updateFromURL: the picked document's
                                        // frontmatter travels with the managed
                                        // fields, so `subagent` / `max_turns` /
                                        // `allowed_tools` land instead of being
                                        // shadowed by whatever was on disk.
                                        frontmatter = parsed.frontmatter.ifBlank { null },
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Only the frame is localized; the throwable's message is
                    // data.
                    failureMsg = context.getString(
                        R.string.skill_file_error_detail,
                        e.message ?: e.javaClass.simpleName,
                    )
                    false
                }
            }
            updateStatus = if (ok) {
                UpdateStatus.Done
            } else if (failureRes != null) {
                UpdateStatus.Failed(context.getString(failureRes!!))
            } else {
                UpdateStatus.Failed(failureMsg ?: context.getString(R.string.skill_detail_error_update_failed))
            }
        }
    }

    val sourceLabel = when (skill.importSource) {
        SkillRepository.ImportSource.URL -> stringResource(R.string.skill_import_tab_url)
        SkillRepository.ImportSource.FILE -> stringResource(R.string.skill_detail_source_file)
        SkillRepository.ImportSource.BUNDLED -> stringResource(R.string.skill_detail_source_bundled)
        SkillRepository.ImportSource.SESSION -> stringResource(R.string.skill_detail_source_session)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(skill.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // [T-android-skill-export] Export the skill as a .zip and
                    // hand it to the system share sheet — the round-trip
                    // partner of the existing zip import, and the Android
                    // equivalent of iOS's top-bar share button.
                    IconButton(
                        enabled = !isExporting,
                        onClick = {
                            isExporting = true
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) {
                                    skillRepository.exportSkillToZip(skill.id)
                                }
                                isExporting = false
                                if (zip == null) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.skill_export_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    shareSkillZip(context, zip)
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.IosShare,
                            contentDescription = stringResource(R.string.skill_export_share),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Meta Section ──
            DetailSection {
                // Name (tap pencil → edit dialog, T155)
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Edit, contentDescription = "Edit name",
                        modifier = Modifier.size(14.dp).clickable {
                            editName = skill.name
                            showEditNameDialog = true
                        },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                DetailDivider()

                // Version
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_version), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(skill.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider()

                // Last Modified
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_modified), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DetailDivider()

                // Source
                DetailRow {
                    Text(stringResource(R.string.skill_detail_label_source), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
                    Spacer(Modifier.weight(1f))
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Update Actions Section ──
            // T154: every row now drives `updateStatus`. The repository APIs
            // already exist (updateFromURL / update); the
            // previous TODOs and the "always show Done" flag were stubs.
            val isBusy = updateStatus is UpdateStatus.InProgress
            DetailSection {
                if (skill.importSource == SkillRepository.ImportSource.URL) {
                    DetailRow(clickable = !isBusy, onClick = {
                        if (isBusy) return@DetailRow
                        updateStatus = UpdateStatus.InProgress(context.getString(R.string.skill_detail_status_downloading))
                        scope.launch {
                            updateStatus = when (val r = skillRepository.updateFromURL(skill.id)) {
                                is SkillRepository.UpdateResult.Success -> UpdateStatus.Done
                                // T152: SKILL.md updated but sibling-file recursion
                                // failed (e.g. GitHub API 403 rate limit). Surface
                                // the actual cause so the user knows scripts/ may
                                // be incomplete instead of a silent green check.
                                is SkillRepository.UpdateResult.PartialSuccess -> UpdateStatus.Failed("Updated, but: ${r.reason}")
                                is SkillRepository.UpdateResult.Failure -> UpdateStatus.Failed(r.reason)
                            }
                        }
                    }) {
                        SettingsActionIcon(Icons.Default.Refresh, SettingsIconBlue)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.skill_detail_update_url), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(relativeTime(skill.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DetailDivider()
                }

                DetailRow(clickable = !isBusy, onClick = {
                    if (isBusy) return@DetailRow
                    fileUpdateLauncher.launch("*/*")
                }) {
                    SettingsActionIcon(Icons.Outlined.Description, SettingsIconBlue)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.skill_detail_update_file), color = MaterialTheme.colorScheme.primary)
                }

                // Inline status row — only rendered while there's something to
                // say. Holds for ~3 s on Done/Failed before LaunchedEffect
                // clears it back to Idle.
                when (val s = updateStatus) {
                    is UpdateStatus.InProgress -> {
                        DetailDivider()
                        DetailRow {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(s.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    is UpdateStatus.Done -> {
                        DetailDivider()
                        DetailRow {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ChatColors.success, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.skill_detail_updated), style = MaterialTheme.typography.bodySmall, color = ChatColors.success)
                        }
                    }
                    is UpdateStatus.Failed -> {
                        DetailDivider()
                        DetailRow {
                            Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(s.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    UpdateStatus.Idle -> Unit
                }
            }

            // ── Description Section ──
            val previewLines = skill.body.lineSequence().filter { it.isNotBlank() }.take(5).toList()
            val hasMore = skill.body.lines().count { it.isNotBlank() } > 5
            val preview = previewLines.joinToString("\n") + if (hasMore) "\n…" else ""

            if (preview.isNotEmpty()) {
                DetailSection(header = "Description") {
                    Column(modifier = Modifier.padding(14.dp)) {
                        MarkdownText(
                            markdown = preview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ── Files Section ──
            // Re-list whenever the skill record changes (rescan/update bumps
            // updatedAt, so newly-extracted scripts appear without leaving the
            // screen).
            // [deep-audit-1039 B-05] listSkillFiles is a synchronous
            // walkTopDown() over the skill's directory — it used to run inside
            // this remember, i.e. on the composition thread of every
            // recomposition keyed on (skill.id, skill.updatedAt). Same shape
            // and same fix as the sibling-file read below ([dv14-B5]): the walk
            // rides a LaunchedEffect + withContext(Dispatchers.IO), and the
            // rendered list derives from the loaded base (empty until the walk
            // lands, which renders as an empty Files section for a few frames).
            var loadedSkillFiles by remember(skill.id, skill.updatedAt) {
                mutableStateOf<List<String>?>(null)
            }
            LaunchedEffect(skill.id, skill.updatedAt) {
                loadedSkillFiles = withContext(Dispatchers.IO) {
                    skillRepository.listSkillFiles(skill.id).ifEmpty { listOf("SKILL.md") }
                }
            }
            val skillFiles = loadedSkillFiles ?: emptyList()
            DetailSection(header = "Files") {
                skillFiles.forEachIndexed { index, relativePath ->
                    if (index > 0) DetailDivider()
                    DetailRow(clickable = true, onClick = { onFileClick(skill.id, relativePath) }) {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ── Delete ──
            Spacer(Modifier.height(16.dp))
            MinisButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.skill_detail_delete))
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.skill_delete_confirm_title, skill.name)) },
            text = { Text(stringResource(R.string.skill_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleted = true
                    // [deep-audit-1039 B-05] delete = two SQLite DELETEs plus a
                    // recursive directory walk; it ran inline on the click
                    // (Main) here. Same treatment as the list-screen confirm and
                    // the import sheet: move the thread, keep the sequence
                    // (`deleted` first so the row stops re-resolving, then the
                    // repository call, then the pop).
                    scope.launch {
                        withContext(Dispatchers.IO) { skillRepository.delete(skill.id) }
                        showDeleteDialog = false
                        onBack()
                    }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // T155: rename-skill dialog. Save is disabled while the user has cleared
    // the name or hasn't actually changed it — both would no-op the
    // repository call, so removing the affordance keeps the UX honest.
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.skill_detail_edit_name_title)) },
            text = {
                Column {
                    // ime-ok: host is an AlertDialog (independent Window,
                    // decorFitsSystemWindows=true -> SOFT_INPUT_ADJUST_UNSPECIFIED),
                    // so the platform pans/resizes to keep this single-line field
                    // visible; the bare Scaffold's missing IME inset does not apply.
                    // Not user-reported; re-verify on device before changing.
                    DialogTextField(
                        value = editName,
                        onValueChange = { editName = it; renameError = null },
                        singleLine = true,
                    )
                    if (renameError != null) {
                        Text(
                            text = renameError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                val trimmed = editName.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != skill.name && !isRenaming
                MinisTextButton(
                    onClick = {
                        // [deep-audit-1039 B-05] update() re-reads the on-disk
                        // SKILL.md frontmatter, writes the DB row and rewrites
                        // SKILL.md — all of it ran on the Main thread on this
                        // click. Wrap like the update-from-file launcher above
                        // ([FIX-R22 B32a]); the refreshed row arrives through the
                        // StateFlow the screen already collects.
                        // [F-13] …and the result decides the exit: a `false`
                        // (id gone from the repository) keeps the dialog open
                        // with the reason instead of closing as if it worked.
                        if (canSave) {
                            isRenaming = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    skillRepository.update(skill.id, name = trimmed)
                                }
                                isRenaming = false
                                if (ok) showEditNameDialog = false
                                else renameError = context.getString(R.string.skill_detail_error_update_failed)
                            }
                        }
                    },
                    enabled = canSave,
                ) { Text(stringResource(R.string.skill_file_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showEditNameDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** A grouped card section matching iOS List section style. */
@Composable
private fun DetailSection(
    header: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailRow(
    clickable: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun DetailDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

/**
 * [T-android-skill-export] Hand an exported skill zip to the system share
 * sheet. The file already lives under `cacheDir/share/` — a root declared in
 * `file_provider_paths.xml`, which FileProvider requires; a file outside a
 * declared root makes getUriForFile throw and the user only sees a failure.
 *
 * The zip is deliberately NOT deleted here. Consumers (Save to Files, Drive, a
 * chat app) read the URI asynchronously after the chooser closes, so deleting
 * on dismissal races them — the same trap iOS documented. SkillRepository
 * sweeps exports older than 24h before each new export instead.
 */
private fun shareSkillZip(context: android.content.Context, zip: java.io.File) {
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", zip,
        )
    } catch (t: Throwable) {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, zip.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(
        sendIntent,
        context.getString(R.string.skill_export_share),
    ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(chooser) }.onFailure {
        Toast.makeText(context, context.getString(R.string.skill_export_failed), Toast.LENGTH_SHORT).show()
    }
}

/** [T-android-skill-icon-circular] The same iOS-system-palette values the main
 *  Settings rows use (SettingsScreen): blue for navigational/remote actions,
 *  green for local filesystem operations. Read from the chat palette so they
 *  follow the active theme. */
private val SettingsIconBlue: Color
    @Composable
    @ReadOnlyComposable
    get() = ChatColors.link

/**
 * [T-android-skill-icon-circular] A colored circular badge with a white glyph
 * centered inside, for the skill detail ACTION rows (Update from URL / Update
 * from file).
 *
 * Spec is copied VERBATIM from the main Settings page rows
 * (SettingsScreen's row icon: a 30dp circle filled with `iconColor`, holding a
 * 16dp white glyph, followed by a 14dp gap) — keep the two surfaces identical;
 * don't retune one without the other. Mirrors iOS `SettingsActionIcon`
 * (d431cfc7), which copied its own Settings spec the same way.
 *
 * Before this, the action rows used a bare 18dp tinted glyph with no container,
 * so they read as a different visual language from every other settings surface.
 */
@Composable
private fun SettingsActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color = iconColor, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Strip Markdown syntax for display in compact UI surfaces (subtitle rows
 * etc.) where a single-line plain-text preview is wanted. Handles fenced
 * code blocks, inline code, links, headings, blockquotes, bold, italic,
 * and collapses any remaining whitespace/newlines into single spaces.
 * Output is trimmed and capped at 120 characters.
 */
private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("```[\\s\\S]*?```"), "")           // fenced code blocks
        .replace(Regex("`([^`]+)`"), "$1")                  // inline code
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^)]+\\)"), "$1") // [text](url) → text
        .replace(Regex("^#+ ", RegexOption.MULTILINE), "") // headings
        .replace(Regex("^> ", RegexOption.MULTILINE), "")  // blockquote markers
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")       // **bold**
        .replace(Regex("__([^_]+)__"), "$1")                // __bold__
        .replace(Regex("\\*([^*]+)\\*"), "$1")             // *italic*
        .replace(Regex("_([^_]+)_"), "$1")                  // _italic_
        .replace(Regex("\\s+"), " ")                       // collapse whitespace + newlines
        .trim()
        .take(120)
}

/** Format millis as relative time string (matching iOS). */
private fun relativeTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 30 -> "$days days ago"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}

// ─── Skill File Viewer Screen (full page, matching iOS) ─────────────────────

// [dv14-B5] Pure single source for what the file viewer shows and what the
// Edit button seeds: SKILL.md renders the in-memory frontmatter merge (the
// lambda runs only in that case — the disk copy is never read for SKILL.md);
// a sibling file renders the asynchronously loaded disk base (blank until the
// read lands). Extracted so the isSkillMd × loaded matrix is JVM-testable.
internal fun skillFileViewerBase(
    isSkillMd: Boolean,
    mergedFrontmatter: () -> String,
    loadedDiskBase: String?,
): String =
    if (isSkillMd) mergedFrontmatter() else loadedDiskBase ?: ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillFileViewerScreen(
    skillId: String,
    relativePath: String = "SKILL.md",
    skillRepository: SkillRepository,
    onBack: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    if (skill == null) {
        // [F-2 sweep1013] composition-phase onBack() — wrap like
        // ModelEntryDetailScreen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
    val fileName = relativePath.substringAfterLast('/')

    // [dv14-B5] The sibling-file disk read moved off the composition thread,
    // mirroring MemoryFileEditScreen ([deep-audit-1029 ISSUE-03]): the read
    // rides a `withContext(Dispatchers.IO)` inside a `LaunchedEffect`, and
    // `initialContent` derives from the loaded base. Keyed on the path (not
    // updatedAt) so an out-of-band edit never blanks the base for the frames
    // before the re-read lands; a skill/path switch does reset it, and
    // `isLoaded` re-arms in the same breath.
    var loadedDiskBase by remember(skillId, relativePath) { mutableStateOf<String?>(null) }

    // For SKILL.md, reconstruct from the in-memory record (so frontmatter
    // edits stay in sync with DB metadata). For sibling files the disk read
    // used to run inside this remember — i.e. synchronously on the
    // composition thread of every recomposition ([dv14-B5]) — so the remember
    // now derives from the asynchronously loaded base (blank until the read
    // lands, which renders harmlessly in the read-only viewer).
    val initialContent = remember(skillId, relativePath, skill.updatedAt, loadedDiskBase) {
        skillFileViewerBase(
            isSkillMd = isSkillMd,
            mergedFrontmatter = {
                // [fix/da1023-E] Reconstruct through the repository's single merge
                // exit instead of hand-splicing a 3-key block here. The splice was
                // the READ half of the same bug as the write-side polarity
                // inversion: it showed only name / version / description, so the
                // block an edit handed back carried none of the extended keys and
                // the editor could neither add nor delete one (and it rendered a
                // literal "null" version for a skill whose row had none). Passing
                // skill.frontmatter — the block loadAll read from disk and
                // rescanFromDisk refreshes — through mergeFrontmatter makes the
                // screen render byte-for-byte what writeSkillMd would write.
                SkillRepository.mergeFrontmatter(
                    existing = skill.frontmatter.ifBlank { null },
                    name = skill.name,
                    description = skill.description,
                    version = skill.version ?: "1.0.0",
                    body = skill.body,
                )
            },
            loadedDiskBase = loadedDiskBase,
        )
    }

    var isEditing by remember { mutableStateOf(false) }
    // Key [skill.updatedAt] added [fix/da1032-e]: initialContent re-derives
    // from skill.updatedAt, but this remember didn't — so an out-of-band edit
    // (agent file_write / shell sed while this screen is open) bumped
    // updatedAt, initialContent recomputed fresh, and editContent kept the
    // pre-edit snapshot forever (editor snapshot deadlock, [audit-1032
    // ISSUE-06]); a same-path sibling edit also leaked into a freshly opened
    // editor. key1/key2 line literals are pinned by
    // skill_frontmatter_roundtrip_check.py / skill_file_save_result_check.py
    // — keys were appended, prefixes untouched.
    // [dv17-4] Key [loadedDiskBase] appended for the same reason one level up
    // the chain: `initialContent` re-derives from it too (the async
    // sibling-file read [dv14-B5] moved off the composition thread), yet this
    // remember didn't carry it — so an out-of-band edit landing while the
    // editor was OPEN left `initialContent` showing NEW_TEXT while
    // `editContent` stayed frozen on the pre-read snapshot, and Save wrote
    // that stale text back over the external write (silent rollback). The two
    // seed-derived remembers now carry the same key set;
    // skill_import_offmain_check.py asserts they stay aligned. `loadedDiskBase`
    // itself deliberately keeps its (skillId, relativePath) keys (not
    // updatedAt) — see the note at its declaration.
    var editContent by remember(skillId, relativePath, skill.updatedAt, loadedDiskBase) { mutableStateOf(initialContent) }

    // [dv14-B5] `isLoaded` gates the Edit button: a tap before the read lands
    // would open the editor against a blank base and a Save could overwrite
    // the real file with "" (the seed comes from `initialContent` below).
    // Deliberately a single flag (no state machine); keyed on the same triple
    // as the content remembers so any key change re-arms the gate.
    var isLoaded by remember(skillId, relativePath, skill.updatedAt) { mutableStateOf(isSkillMd) }
    LaunchedEffect(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) return@LaunchedEffect
        loadedDiskBase = withContext(Dispatchers.IO) {
            skillRepository.readSkillFile(skillId, relativePath) ?: ""
        }
        isLoaded = true
    }

    // [fix/da1021-issue06] Save failures used to be silently discarded: the
    // return values of importFromContent / writeSkillFile were dropped and the
    // screen unconditionally popped, destroying the user's edits (a broken
    // SKILL.md frontmatter made importFromContent return null without writing
    // anything). Consume the result, run the write off the main thread, and
    // surface a snackbar on failure instead of leaving the screen.
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val invalidFormatMsg = stringResource(R.string.skill_detail_error_invalid)
    val updateFailedMsg = stringResource(R.string.skill_detail_error_update_failed)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (isEditing) {
                        MinisTextButton(onClick = {
                            if (isSaving) return@MinisTextButton
                            isSaving = true
                            val contentToSave = editContent
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    if (isSkillMd) {
                                        // SKILL.md edits go through importFromContent so
                                        // YAML frontmatter changes flow back into DB metadata.
                                        // [fix/audit-1015-b4-2] Pin the preferred id to the
                                        // skill being edited: without it a renamed SKILL.md
                                        // saved here resolved to a fresh slugify id and was
                                        // added as a duplicate instead of refreshing the
                                        // existing entry.
                                        skillRepository.importFromContent(
                                            contentToSave,
                                            skill.importSource,
                                            preferredId = skillId,
                                        ) != null
                                    } else {
                                        skillRepository.writeSkillFile(skillId, relativePath, contentToSave)
                                    }
                                }
                                isSaving = false
                                if (ok) {
                                    isEditing = false
                                    onBack()
                                } else {
                                    snackbarHostState.showSnackbar(
                                        if (isSkillMd) invalidFormatMsg else updateFailedMsg,
                                    )
                                }
                            }
                        }) { Text(stringResource(R.string.skill_file_save)) }
                    } else {
                        // [dv14-B5] Gated on the async read: `initialContent`
                        // is the loaded disk base, so entering before the read
                        // lands would edit an empty base.
                        MinisTextButton(
                            enabled = isLoaded,
                            onClick = {
                                editContent = initialContent
                                isEditing = true
                            },
                        ) { Text(stringResource(R.string.skill_file_edit)) }
                    }
                },
            )
        },
    ) { padding ->
        if (isEditing) {
            DialogTextField(
                value = editContent,
                onValueChange = { editContent = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // [T-android-ime-occlusion-0920] Bare Scaffold does not
                    // consume WindowInsets.ime (systemBars only) and
                    // edge-to-edge + adjustResize no longer resizes for the
                    // keyboard, so this full-height editor's own "scroll caret
                    // into view" logic could park the caret behind the IME.
                    .imePadding()
                    .padding(horizontal = 8.dp),
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            // Render markdown sibling files (references/, docs/) with the
            // shared renderer; SKILL.md stays raw because its frontmatter
            // (--- / name: / version:) would be distorted by markdown
            // parsing, and it is the agent-facing contract where what you
            // see should equal what the agent reads. Editing remains raw
            // for every file type.
            val renderAsMarkdown =
                relativePath.endsWith(".md", ignoreCase = true) && !isSkillMd
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    if (renderAsMarkdown) {
                        MarkdownText(
                            markdown = initialContent,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            initialContent,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
