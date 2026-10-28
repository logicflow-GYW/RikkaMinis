package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.DialogTextField
import com.rikkaminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.deeplink.DeepLinkCoordinator

/**
 * What a row paints before its value has been read out of the Keystore-backed
 * store, and the only thing a hidden row ever paints ([deep-audit-1041 B-03]).
 */
private const val MASKED_ENV_VAR_VALUE = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

/**
 * Environment Variables \u2014 adopts the SettingsScaffold/SettingsSection
 * toolkit (T80). One section stringResource(R.string.env_var_section_header) carrying every defined key/
 * value pair as a SettingsRow, with the visibility/copy/delete actions
 * in each row's trailing slot. Add affordance lives in the top-bar
 * `actions` slot (T75-part1 already moved it off the FAB).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentVariablesScreen(
    envVarRepository: EnvVarRepository,
    onBack: () -> Unit,
) {
    val entries by envVarRepository.entries.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editEntryId by remember { mutableStateOf<String?>(null) }
    var deleteEntryId by remember { mutableStateOf<String?>(null) }
    // iOS parity (AIChatView.swift L1410-1411): when a deep link arrives
    // with `create_key`, the env screen should open with a prefilled Add
    // sheet so the user only has to paste the value.
    var prefill by remember { mutableStateOf<DeepLinkCoordinator.EnvVarCreate?>(null) }
    val visibleKeys = remember { mutableStateOf(setOf<String>()) }
    val clipboardManager = LocalClipboardManager.current
    // [deep-audit-1041 B-03] `envVarRepository.getValue/add/update/delete` are
    // the only blocking entry points of this screen: values live in
    // Keystore-backed EncryptedSharedPreferences and every write also rewrites
    // env-vars.json. None of them may run on the Main dispatcher, so the click
    // callbacks below hand the repository call to this screen-scoped coroutine
    // (same shape as the Skills / MCP / Session-sheet settings screens) while the
    // Compose-owned state keeps updating on Main.
    val scope = rememberCoroutineScope()

    // [da1028-C] Reactively observe the pending create request instead of a
    // one-shot `LaunchedEffect(Unit)` + `.value` read. The old shape only ran
    // once per composition: a deep link arriving while this screen was already
    // up (warm start, Activity not recreated) never re-ran the effect and the
    // request was silently dropped. Keying the effect on the value re-runs it
    // when a request lands; `consume` → null re-runs it as a harmless no-op.
    val pendingCreate by DeepLinkCoordinator.pendingEnvVarCreate.collectAsState()
    LaunchedEffect(pendingCreate) {
        pendingCreate?.let {
            // The request is an ADD intent, so drop any stale edit target first —
            // otherwise the sheet opens in edit mode and `editEntry` wins the
            // key/value defaults, prefilling nothing and saving over that entry.
            editEntryId = null
            prefill = it
            showAddSheet = true
            DeepLinkCoordinator.consumePendingEnvVarCreate()
        }
    }

    val privacyEnabled by com.rikkaminis.app.data.EnvVarPrivacyStore.enabled.collectAsState()

    SettingsScaffold(
        title = stringResource(R.string.env_var_title),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        // T75-part1 moved Add off a FAB onto the top-bar action slot;
        // kept here for visual continuity.
        actions = {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.env_var_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.env_var_privacy_header),
            footer = stringResource(R.string.env_var_privacy_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.env_var_privacy_toggle),
                checked = privacyEnabled,
                onCheckedChange = { com.rikkaminis.app.data.EnvVarPrivacyStore.setEnabled(it) },
                showDivider = false,
            )
        }

        // Variables render grouped by their optional `group` label: each
        // named group gets its own section header (in first-appearance
        // order), and entries without a group collect under the generic
        // "Variables" section rendered last. Platform integrations
        // (GitHub/CF/HF …) are ordinary entries — a per-platform card
        // made multi-account setups (e.g. CF_API_TOKEN / _1 / _2)
        // harder to read, so groups and notes carry that context instead.
        val groupedEntries = remember(entries) {
            val groups = LinkedHashMap<String, MutableList<EnvVarRepository.EnvVarEntry>>()
            for (e in entries) {
                groups.getOrPut(e.group) { mutableListOf() }.add(e)
            }
            groups
        }

        if (entries.isEmpty()) {
            SettingsSection(
                header = stringResource(R.string.env_var_section_header),
                footer = stringResource(R.string.env_var_section_footer),
            ) {
                // Centred empty-state message inside the same card so the
                // section visually owns it (instead of an empty card +
                // separately-positioned text block).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.env_var_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.env_var_empty_action),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Named groups first, in order of first appearance.
            groupedEntries.forEach { (group, groupEntries) ->
                if (group.isNotEmpty()) {
                    SettingsSection(header = group) {
                        groupEntries.forEachIndexed { index, entry ->
                            // `key(entry.id)`: a row holds its revealed value in a
                            // remembered state (see EnvVarListRow), so a slot reused by
                            // the next entry must not paint the previous entry's secret
                            // for a frame after a delete/restore reorders the list.
                            key(entry.id) {
                                EnvVarListRow(
                                    entry = entry,
                                    envVarRepository = envVarRepository,
                                    visible = entry.key in visibleKeys.value,
                                    showDivider = index < groupEntries.size - 1,
                                    onToggleVisible = {
                                        visibleKeys.value =
                                            if (visibleKeys.value.contains(entry.key))
                                                visibleKeys.value - entry.key
                                            else
                                                visibleKeys.value + entry.key
                                    },
                                    onCopy = {
                                        // The decrypt is Keystore I/O; the clipboard write
                                        // itself stays on Main (Compose-owned handle).
                                        scope.launch {
                                            val v = withContext(Dispatchers.IO) {
                                                envVarRepository.getValue(entry.key) ?: ""
                                            }
                                            clipboardManager.setText(AnnotatedString("${entry.key}=$v"))
                                        }
                                    },
                                    onEdit = { editEntryId = entry.id },
                                    onDelete = { deleteEntryId = entry.id },
                                )
                            }
                        }
                    }
                }
            }
            // Uncategorized last, carrying the section footer.
            groupedEntries[""]?.let { uncategorized ->
                SettingsSection(
                    header = stringResource(R.string.env_var_section_header),
                    footer = stringResource(R.string.env_var_section_footer),
                ) {
                    uncategorized.forEachIndexed { index, entry ->
                        key(entry.id) {
                            EnvVarListRow(
                                entry = entry,
                                envVarRepository = envVarRepository,
                                visible = entry.key in visibleKeys.value,
                                showDivider = index < uncategorized.size - 1,
                                onToggleVisible = {
                                    visibleKeys.value =
                                        if (visibleKeys.value.contains(entry.key))
                                            visibleKeys.value - entry.key
                                        else
                                            visibleKeys.value + entry.key
                                },
                                onCopy = {
                                    scope.launch {
                                        val v = withContext(Dispatchers.IO) {
                                            envVarRepository.getValue(entry.key) ?: ""
                                        }
                                        clipboardManager.setText(AnnotatedString("${entry.key}=$v"))
                                    }
                                },
                                onEdit = { editEntryId = entry.id },
                                onDelete = { deleteEntryId = entry.id },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Add/Edit sheet
    if (showAddSheet || editEntryId != null) {
        // [da1034-B4] The edit target is resolved from the LIVE list below, so when
        // the entry disappears while the sheet is up (minis-config
        // `remove envvars.<KEY>` / backup restore), `editing` resolves to null while
        // `editEntryId` stays non-null — the sheet silently flips to new-variable
        // semantics (`isEditing == false`) and Save creates a brand-new entry under
        // the key the user believes they are editing. Close instead; same decision
        // as ThinkingRulesSection's editorRequest close (C15b) and
        // ManageProviderModelsSheet's auto-dismiss (C3). Effect (not composition):
        // state must not be written during composition.
        LaunchedEffect(editEntryId, entries) {
            if (editTargetVanished(entries, editEntryId)) {
                editEntryId = null
            }
        }
        val editing = editEntryId?.let { id -> entries.find { it.id == id } }
        EnvVarFormSheet(
            editEntry = editing,
            prefillKey = prefill?.key.orEmpty(),
            prefillValue = prefill?.value.orEmpty(),
            prefillNote = prefill?.note.orEmpty(),
            envVarRepository = envVarRepository,
            onDismiss = {
                showAddSheet = false
                editEntryId = null
                prefill = null
            },
        )
    }

    // Delete confirmation
    if (deleteEntryId != null) {
        val entry = entries.find { it.id == deleteEntryId }
        AlertDialog(
            onDismissRequest = { deleteEntryId = null },
            title = { Text(stringResource(R.string.env_var_delete_confirm_title, entry?.key ?: "variable")) },
            text = { Text(stringResource(R.string.env_var_delete_confirm_text)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    // [deep-audit-1041 B-03] The dialog closes on Main first (so it
                    // cannot flicker or absorb a second tap while the write is in
                    // flight), then the Keystore row + env-vars.json rewrite follows on
                    // IO — same shape as the MCP screen's delete confirm.
                    val id = deleteEntryId
                    deleteEntryId = null
                    if (id != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { envVarRepository.delete(id) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteEntryId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}


/**
 * [da1034-B4] Decision point of the edit-sheet disappearance recheck: the sheet is
 * opened for an id (`editEntryId`), never for an entry, so the target is re-resolved
 * from the live list on every composition. `true` means the id the sheet was opened
 * for is no longer in the list — an external delete (`minis-config remove
 * envvars.<KEY>`, backup restore, the row's own delete confirm) landed while the
 * sheet was open — and the caller closes the sheet instead of letting
 * `EnvVarFormSheet` reinterpret the same id as a new variable.
 *
 * `editEntryId == null` is the pure "Add" mode (`showAddSheet`) and is never a
 * disappearance. Kept as a pure function so the decision can be run for real in a
 * JVM test (the Compose side is only wiring).
 */
internal fun editTargetVanished(
    entries: List<EnvVarRepository.EnvVarEntry>,
    editEntryId: String?,
): Boolean {
    if (editEntryId == null) return false
    return entries.none { it.id == editEntryId }
}


@Composable
private fun EnvVarListRow(
    entry: EnvVarRepository.EnvVarEntry,
    envVarRepository: EnvVarRepository,
    visible: Boolean,
    showDivider: Boolean,
    onToggleVisible: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // [deep-audit-1041 B-03] This read decrypts through Keystore-backed
    // EncryptedSharedPreferences. Inline (the old shape) it ran on the
    // composition thread — on Main and inside the frame — on every
    // recomposition of the row. It now runs in a produceState effect on
    // Dispatchers.IO, keyed on the entry and the visibility toggle; the mask
    // is what the first frame paints, so a row can never show another entry's
    // value while a read is in flight.
    val revealedValue by produceState(
        initialValue = MASKED_ENV_VAR_VALUE,
        entry.key,
        visible,
    ) {
        // Read only while the row is revealed: a hidden row must never decrypt,
        // and the pre-read placeholder is the mask either way.
        value = if (visible) {
            withContext(Dispatchers.IO) { envVarRepository.getValue(entry.key) ?: "" }
        } else {
            MASKED_ENV_VAR_VALUE
        }
    }
    // The gate is applied to the PAINTED value, not inside the effect: the
    // effect is asynchronous, so hiding a row would otherwise paint the secret
    // for one more frame (the producer's new value lands after the recomposition
    // that flipped `visible`).
    val displayValue = if (visible) revealedValue else MASKED_ENV_VAR_VALUE
    // iOS parity (EnvironmentVariablesView.swift:139-151): show
    // value on one line and the optional note on a second.
    // SettingsRow only has one subtitle slot, so concatenate
    // with a newline; both render in the same secondary style.
    val subtitleText = if (entry.note.isNotEmpty()) {
        "$displayValue\n${entry.note}"
    } else {
        displayValue
    }
    SettingsRow(
        title = entry.key,
        subtitle = subtitleText,
        showChevron = false,
        showDivider = showDivider,
        onClick = onEdit,
        trailing = {
            Row {
                IconButton(onClick = onToggleVisible) {
                    Icon(
                        if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = stringResource(R.string.env_var_toggle_visibility),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.common_copy),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvVarFormSheet(
    editEntry: EnvVarRepository.EnvVarEntry?,
    envVarRepository: EnvVarRepository,
    onDismiss: () -> Unit,
    prefillKey: String = "",
    prefillValue: String = "",
    prefillNote: String = "",
) {
    // skipPartiallyExpanded: without it the sheet opens at the half detent
    // and the user must drag it up to reach the value/note fields and the
    // save button. Open fully instead — the form is a single screenful.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // [deep-audit-1041 B-03] The save button's repository call is Keystore +
    // disk I/O; it is launched from this sheet-scoped coroutine.
    val scope = rememberCoroutineScope()
    // [da1028-C] Every field is keyed on the entry it was seeded from (plus the
    // deep-link prefill): a keyless `remember` kept the FIRST entry's text after
    // `editEntry` switched identity (another entry updated / backup restore /
    // approval write), so pressing Save wrote the stale text back over the
    // external write. Same family as 1027-E's group/mount editors.
    //
    // `valueText` keys on the LIVE secret (`currentSecretValue`) rather than a
    // snapshot, which is what makes an external write visible at all. As of
    // [deep-audit-1041 B-03] that live secret is produced by an IO effect keyed
    // on (entry id, entry key, prefill): re-read per entry identity rather than
    // on every recomposition, so the box no longer re-seeds itself under the
    // user's fingers whenever an unrelated recomposition lands (the trade-off
    // that used to be recorded here). The alternative — keying on `editEntry`
    // alone — cannot see a value-only change at all, since the entry object is
    // not rebuilt when only the secret moves.
    // [deep-audit-1041 B-03] The live secret is a Keystore decrypt; it used to run
    // inline, on the composition thread, on every composition of the sheet. Same
    // value and the same `valueText` key as before — only the dispatcher moves:
    // a produceState effect reads it on Dispatchers.IO, keyed on the entry it
    // belongs to plus the deep-link prefill.
    // [meta-audit-1028 D4 F-03] Declared BEFORE the effect above so the effect can
    // publish into it; it says whether the value box's content can be attributed to the
    // user at all. Edit mode starts PENDING (the box is showing "" until the Keystore
    // read lands); add mode is LOADED from the first frame (nothing is stored).
    var secretLoad by remember(editEntry?.id, editEntry?.key) {
        mutableStateOf(
            if (editEntry == null) SecretLoadState.LOADED else SecretLoadState.PENDING,
        )
    }
    val currentSecretValue by produceState(
        initialValue = prefillValue,
        editEntry?.id,
        editEntry?.key,
        prefillValue,
    ) {
        val entry = editEntry
        if (entry == null) {
            // Add mode: no stored secret to lose, and the deep-link prefill IS the value
            // the user is about to save.
            value = prefillValue
            secretLoad = SecretLoadState.LOADED
            return@produceState
        }
        // [meta-audit-1028 D4 F-03] The Save gate below needs more than the value: an
        // empty box means three different things (still loading / nothing stored /
        // unreadable), and only one of them is safe to write back. The value alone
        // cannot say — in edit mode it starts as "" either way.
        val read = withContext(Dispatchers.IO) {
            runCatching { envVarRepository.getValue(entry.key) }
        }
        read.fold(
            onSuccess = { stored ->
                value = stored ?: ""
                secretLoad = SecretLoadState.LOADED
            },
            // Fail closed. The old code let the exception escape, which left the box
            // empty AND Save enabled — a note-only edit then wrote "" over a secret the
            // user could not see. An unreadable secret now blocks Save and says why.
            onFailure = { secretLoad = SecretLoadState.UNREADABLE },
        )
    }
    var keyText by remember(editEntry?.id, editEntry?.key, prefillKey) {
        mutableStateOf(editEntry?.key ?: prefillKey)
    }
    var valueText by remember(editEntry?.id, currentSecretValue, prefillValue) {
        mutableStateOf(currentSecretValue)
    }
    var noteText by remember(editEntry?.id, editEntry?.note, prefillNote) {
        mutableStateOf(editEntry?.note ?: prefillNote)
    }
    var groupText by remember(editEntry?.id, editEntry?.group) {
        mutableStateOf(editEntry?.group ?: "")
    }

    val isEditing = editEntry != null
    val normalizedKey = keyText.trim().uppercase()
    val isValid = envVarRepository.isValidKey(normalizedKey)
    val isDuplicate = envVarRepository.isDuplicateKey(normalizedKey, excludeId = editEntry?.id)
    // [meta-audit-1028 D4 F-03] Save used to depend on the key alone, so a tap during
    // the Keystore read wrote the not-yet-loaded value ("" in edit mode) into the
    // encrypted store — the variable survived with an empty secret and every consumer
    // of it started failing auth. The load state is now part of the gate.
    val canSave = canSaveWithSecretField(
        state = secretLoad,
        otherFieldsValid = isValid && !isDuplicate && keyText.isNotBlank(),
    )

    val errorText = when {
        keyText.isNotBlank() && !isValid -> stringResource(R.string.env_var_error_invalid_key)
        isDuplicate -> stringResource(R.string.env_var_error_duplicate)
        else -> null
    }
    val valueError = when (secretLoad) {
        SecretLoadState.UNREADABLE -> stringResource(R.string.settings_secret_unreadable)
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrolling + imePadding: when the keyboard opens over the
                // value/note fields the form can exceed the sheet height on
                // small screens — scroll instead of clipping the save row,
                // and let the sheet ride above the IME.
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isEditing) stringResource(R.string.env_var_form_edit_title) else stringResource(R.string.env_var_form_add_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.env_var_field_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = keyText,
                onValueChange = { keyText = it.uppercase() },
                singleLine = true,
                isError = errorText != null,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            if (errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.env_var_field_value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = valueText,
                onValueChange = { valueText = it },
                // While the Keystore read is in flight the box is not showing what is on
                // disk; say so instead of showing a blank that reads as "no value".
                placeholder = if (secretLoad == SecretLoadState.PENDING) {
                    stringResource(R.string.common_loading)
                } else {
                    null
                },
                singleLine = false,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            if (valueError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = valueError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // iOS parity: optional human-readable note explaining what the
            // variable is for. Stored alongside the metadata, surfaced in the
            // list row below the value.
            Text(
                text = stringResource(R.string.env_var_field_note),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = stringResource(R.string.env_var_field_note_placeholder),
                singleLine = true,
            )

            // Optional grouping label: entries sharing a label render
            // together under one section header in the list.
            Text(
                text = stringResource(R.string.env_var_field_group),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            DialogTextField(
                value = groupText,
                onValueChange = { groupText = it },
                placeholder = stringResource(R.string.env_var_field_group_placeholder),
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                MinisTextButton(
                    onClick = {
                        // [meta-audit-1028 D4 F-03] Belt and braces behind the disabled
                        // button: `update()` writes the value unconditionally, so a save
                        // whose box does not show what is on disk would erase it.
                        if (secretLoad != SecretLoadState.LOADED) return@MinisTextButton
                        // [deep-audit-1041 B-03] The write touches Keystore and
                        // rewrites env-vars.json. Capture the Compose-owned values on
                        // Main, run the repository call on IO, and dismiss only on
                        // success (the dismiss itself stays on Main).
                        val editingId = editEntry?.id
                        val saveKey = keyText
                        val saveValue = valueText
                        val saveNote = noteText
                        val saveGroup = groupText
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                if (editingId != null) {
                                    envVarRepository.update(editingId, saveKey, saveValue, saveNote, saveGroup)
                                } else {
                                    envVarRepository.add(saveKey, saveValue, saveNote, saveGroup)
                                }
                            }
                            if (success) onDismiss()
                        }
                    },
                    enabled = canSave,
                ) {
                    Text(if (isEditing) stringResource(R.string.common_save) else stringResource(R.string.env_var_add))
                }
            }
        }
    }
}
