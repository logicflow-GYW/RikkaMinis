package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.deeplink.DeepLinkCoordinator

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

    LaunchedEffect(Unit) {
        DeepLinkCoordinator.consumePendingEnvVarCreate()?.let {
            prefill = it
            showAddSheet = true
        }
    }

    val privacyEnabled by com.openminis.app.data.EnvVarPrivacyStore.enabled.collectAsState()

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
                onCheckedChange = { com.openminis.app.data.EnvVarPrivacyStore.setEnabled(it) },
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
                            EnvVarListRow(
                                entry = entry,
                                envVarRepository = envVarRepository,
                                visible = entry.key in visibleKeys.value,
                                showDivider = index < groupEntries.size - 1,
                                onToggleVisible = {
                                    visibleKeys.value = if (visibleKeys.value.contains(entry.key))
                                        visibleKeys.value - entry.key
                                    else
                                        visibleKeys.value + entry.key
                                },
                                onCopy = {
                                    val v = envVarRepository.getValue(entry.key) ?: ""
                                    clipboardManager.setText(AnnotatedString("${entry.key}=$v"))
                                },
                                onEdit = { editEntryId = entry.id },
                                onDelete = { deleteEntryId = entry.id },
                            )
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
                        EnvVarListRow(
                            entry = entry,
                            envVarRepository = envVarRepository,
                            visible = entry.key in visibleKeys.value,
                            showDivider = index < uncategorized.size - 1,
                            onToggleVisible = {
                                visibleKeys.value = if (visibleKeys.value.contains(entry.key))
                                    visibleKeys.value - entry.key
                                else
                                    visibleKeys.value + entry.key
                            },
                            onCopy = {
                                val v = envVarRepository.getValue(entry.key) ?: ""
                                clipboardManager.setText(AnnotatedString("${entry.key}=$v"))
                            },
                            onEdit = { editEntryId = entry.id },
                            onDelete = { deleteEntryId = entry.id },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    // Add/Edit sheet
    if (showAddSheet || editEntryId != null) {
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
                    deleteEntryId?.let { envVarRepository.delete(it) }
                    deleteEntryId = null
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
    val displayValue = if (visible) {
        envVarRepository.getValue(entry.key) ?: ""
    } else {
        "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
    }
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
    var keyText by remember { mutableStateOf(editEntry?.key ?: prefillKey) }
    var valueText by remember {
        mutableStateOf(editEntry?.let { envVarRepository.getValue(it.key) } ?: prefillValue)
    }
    var noteText by remember { mutableStateOf(editEntry?.note ?: prefillNote) }
    var groupText by remember { mutableStateOf(editEntry?.group ?: "") }

    val isEditing = editEntry != null
    val normalizedKey = keyText.trim().uppercase()
    val isValid = envVarRepository.isValidKey(normalizedKey)
    val isDuplicate = envVarRepository.isDuplicateKey(normalizedKey, excludeId = editEntry?.id)
    val canSave = isValid && !isDuplicate && keyText.isNotBlank()

    val errorText = when {
        keyText.isNotBlank() && !isValid -> stringResource(R.string.env_var_error_invalid_key)
        isDuplicate -> stringResource(R.string.env_var_error_duplicate)
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
                singleLine = false,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )

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
                        val success = if (isEditing) {
                            envVarRepository.update(editEntry!!.id, keyText, valueText, noteText, groupText)
                        } else {
                            envVarRepository.add(keyText, valueText, noteText, groupText)
                        }
                        if (success) onDismiss()
                    },
                    enabled = canSave,
                ) {
                    Text(if (isEditing) stringResource(R.string.common_save) else stringResource(R.string.env_var_add))
                }
            }
        }
    }
}
