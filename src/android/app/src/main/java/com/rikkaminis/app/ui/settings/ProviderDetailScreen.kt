package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.normalizeModalities
import com.rikkaminis.app.data.repository.ProviderRepository
import android.widget.Toast
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.MinisAlertDialog
import com.rikkaminis.app.ui.util.bringIntoViewOnFocus
import com.rikkaminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.MinisOutlinedButton
import com.rikkaminis.app.ui.components.MinisSmallButton
import com.rikkaminis.app.ui.components.MinisSmallOutlinedButton
import com.rikkaminis.app.ui.components.MinisSmallTextButton
import com.rikkaminis.app.ui.components.SectionTextField
import com.rikkaminis.app.provider.ModelsDevApi

private const val TAG = "ProviderDetail"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderDetailScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
    onAddCustomModel: () -> Unit = {},
    onConnectionClick: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val instance = config.instances.find { it.id == instanceId }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // [T-provider-export-key-warning] The exported JSON carries the API key in
    // plain Base64 (so it re-imports ready-to-use). Before opening the share
    // sheet, confirm so the user knows the file is sensitive.
    var showExportDialog by remember { mutableStateOf(false) }
    // T143: long-press → confirm delete on a single model entry. Every
    // visible entry supports the gesture — built-in and custom alike — because
    // [T-provider-no-static-seed] guarantees nothing is re-created from a
    // static seed after removal (voice-template seeds are preserved on refresh
    // but a deleted entry simply stays gone). Previously built-in entries
    // skipped the gesture (isCustom gate) — that silently made manually-added
    // models undeletable after a refresh reset their custom identity,
    // see ProviderRepository.replaceEntries [T-provider-custom-identity].
    // [deep-audit-1040 B-07] Hold the pending target's ID, not the
    // `ModelEntry` object captured when the row was long-pressed. With the
    // object, an external delete / model refresh left the confirm dialog
    // naming a model that no longer existed, and confirming it called
    // `removeEntry()` on a dead id. Same channel shape as Quick Test
    // ([deep-audit-1039 B-08]).
    var entryToDeleteId by remember { mutableStateOf<String?>(null) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (instance == null && !deleted) {
        // [F-2 sweep1013] composition-phase onBack() — wrap like
        // ModelEntryDetailScreen.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (instance == null) return
    // [deep-audit-1040 B-07] Write paths resolve the LATEST persisted instance
    // instead of pushing this frame's object back with `instance.copy(...)`.
    // `instance` (above) stays the source for RENDERING; reusing it for writes
    // meant a write that landed after an external edit (minis-config / Debug
    // RPC / backup restore) silently reverted the newer fields to the values
    // this frame had composed with. Mirrors the `currentInstance()` single
    // source established in ProviderConnectionScreen by [deep-audit-1039 B-04].
    // The `?: instanceFallback` arm only covers the window between an external
    // delete and this guard composing again.
    val instanceFallback = instance
    fun currentInstance(): ProviderInstance =
        config.instances.find { it.id == instanceId } ?: instanceFallback

    // [da1023-C] Bind label to (instance.id, attribute): the old keyless
    // remember kept the first instance's values after navigating between
    // detail pages, and a focus loss then wrote the STALE label back over
    // the new instance (external RPC rename + UI focus loss = silent revert).
    var label by remember(instance.id, instance.label) { mutableStateOf(instance.label) }
    val labelChanged = label != instance.label

    // [T-android-provider-apikey-save-stale] Hold the stored API key in Compose
    // state, NOT as a plain val that re-reads EncryptedSharedPreferences on every
    // recomposition. saveApiKey() writes with .apply() (async); a recomposition
    // right after Save (isEditingKey=false) would re-read prefs before the write
    // flushed and show the OLD key, making Save look like a no-op. Keeping it in
    // remember + updating it synchronously in onSave reflects the just-saved
    // value immediately, independent of the async flush.
    //
    // [perf-provider-detail] The initial loadApiKey() call is deferred to a
    // LaunchedEffect so EncryptedSharedPreferences I/O doesn't stall the
    // navigation-transition frame. Initial state is null; the key populates
    // asynchronously on the next frame — fast enough that no visible flash
    // occurs during the slide-in animation (~300 ms).
    //
    // [T-provider-connection-screen] Only the masked summary is needed on the
    // detail screen now — the actual key editing moved to
    // ProviderConnectionScreen. loadApiKey stays here (cheap, async) so the
    // "API & Connection" row can show "sk-…4242" without a sub-screen hop.
    var storedKey by remember { mutableStateOf<String?>(null) }
    // [deep-audit-1040 B-07] Also key on `config.revision`. `loadApiKey` has no
    // revision counter of its own, but every path that writes a key also bumps
    // the config revision (instancesUpdate RPC / import), so this is the
    // available "the stored credential may have changed" signal. Without it an
    // external write left the previous key masked on screen.
    LaunchedEffect(instanceId, config.revision) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }

    // [T-provider-detail-visible-models] Manage All Models opens as a
    // ModalBottomSheet (low-frequency one-time action). No navigation route —
    // the sheet lives inside this screen.
    var showManageModelsSheet by remember { mutableStateOf(false) }

    // [da1023-C] Same (instance.id, attribute) binding as label above: an
    // external RPC enable/disable while this screen is open used to leave the
    // stale toggle on screen, and the next save wrote the stale value back.
    var isEnabled by remember(instance.id, instance.isEnabled) { mutableStateOf(instance.isEnabled) }

    // saveBaseURLSettings(): single source of truth for persisting the three
    // [perf-provider-detail-models] Cache the entry list per (instanceId, config)
    // instead of re-filtering the whole modelEntries collection on EVERY
    // recomposition. With a 300+ model provider, the old `entriesFor` call ran
    // a full-list scan on each recomposition (every keystroke, every toggle),
    // O(n) of the catalog for a screen that already renders all of them.
    //
    // [T-provider-detail-visible-models] The detail screen now shows ONLY the
    // models the user has selected (isHidden == false) — mirrors rikkahub's
    // "pull everything, display the chosen few" model. The full catalog stays
    // in the repository (modelEntries) and is reachable via the "Manage All
    // Models" row that opens the ManageProviderModelsSheet; refresh keeps
    // downloading everything so nothing is lost, and refreshModels() preserves
    // each entry's isHidden/overrides/uuid across refreshes (ProviderRepository
    // L959-987). `visibleEntries` lives on the repo and filters the same
    // StateFlow-backed list, so toggling visibility in the manager screen
    // updates this list reactively with zero extra wiring.
    val entries = remember(instanceId, config) {
        providerRepository.visibleEntries(instanceId)
    }
    // Full catalog size, for the section header. Computed the same remembered
    // way so it doesn't rescan on every recomposition either. [da1026-C] The
    // refresh toast deliberately does NOT read this snapshot: it is keyed on
    // the config that existed before the fetch ran, so it would report the
    // pre-refresh count. The toast re-reads entriesFor() after the refresh.
    val allEntries = remember(instanceId, config) {
        providerRepository.entriesFor(instanceId)
    }
    var isRefreshing by remember { mutableStateOf(false) }

    val exportContext = androidx.compose.ui.platform.LocalContext.current

    SettingsScaffold(
        title = label,
        onBack = onBack,
        actions = {
            IconButton(onClick = { showExportDialog = true }) {
                Icon(Icons.Default.IosShare, contentDescription = stringResource(R.string.provider_detail_export))
            }
        },
    ) {
        // ─── Label ──────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.provider_detail_label)) {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fieldModifier = Modifier.bringIntoViewOnFocus(),
                    )
                    if (labelChanged) {
                        Spacer(modifier = Modifier.width(8.dp))
                        MinisSmallButton(onClick = {
                            val live = currentInstance()
                            providerRepository.updateInstance(live.copy(label = label))
                            AppLogger.info(TAG, "Updated label for ${live.id}: '$label'")
                        }) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }

        // ─── Status ─────────────────────────────────────────────────
        SettingsSection(header = stringResource(R.string.provider_detail_status)) {
            SettingsSwitchRow(
                title = stringResource(R.string.provider_detail_enabled),
                checked = isEnabled,
                onCheckedChange = {
                    isEnabled = it
                    val live = currentInstance()
                    providerRepository.updateInstance(live.copy(isEnabled = it))
                    AppLogger.info(TAG, "Set enabled=$it for ${live.id}")
                },
                showDivider = false,
            )
        }

        // ─── API & Connection ────────────────────────────────────────
        // [T-provider-connection-screen] All connection/credential controls
        // (API key, OAuth, manual bearer, custom base URL, API format, Azure,
        // image endpoint) live on the dedicated ProviderConnectionScreen —
        // opened from this row. This detail page stays focused on the daily
        // driver stuff: enable toggle + model picker. The summary line shows
        // the credential endpoint at a glance without the full card stack.
        SettingsSection(header = stringResource(R.string.provider_detail_api_connection)) {
            SettingsRow(
                title = stringResource(R.string.provider_detail_api_connection),
                subtitle = connectionSummary(instance, storedKey),
                onClick = onConnectionClick,
                showChevron = true,
                showDivider = false,
            )
        }

        // ─── Thinking Rules [T-android-thinking-rules-phase2 §3] ─────
        // [da1028-C] Pass the full entry list (entriesFor, not the visible-only
        // `entries`): builtInThinkingRulesForDisplay / firstModelId both read
        // `config.modelEntries` for this instance, so the section's `remember`
        // keys must observe that exact source or a catalog refresh leaves the
        // list + resolution preview on a stale snapshot.
        ThinkingRulesSection(
            instance = currentInstance(),
            providerRepository = providerRepository,
            modelEntries = allEntries,
        )

        // ─── Models ─────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.provider_detail_models_count_header, entries.size),
        ) {
            // [T-provider-detail-visible-models] "Manage All Models" opens the
            // full-catalog manager (search + visibility toggles). Placed as the
            // FIRST row of the section: the visible list is now the daily-driver
            // view, and this row is the escape hatch for browsing everything the
            // provider actually offers. Chevron signals "more inside". The
            // refresh affordance rides as this row's trailing icon (compact —
            // no separate full-width refresh row any more).
            SettingsRow(
                title = stringResource(R.string.provider_detail_manage_models),
                subtitle = stringResource(R.string.provider_detail_models_count_header, allEntries.size),
                onClick = { showManageModelsSheet = true },
                showChevron = true,
                showDivider = false,
                trailing = {
                    if (isRefreshing) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            isRefreshing = true
                            val toastContext = exportContext
                            scope.launch {
                                try {
                                    val result = providerRepository.refreshModels(instance)
                                    // [da1026-C] Count AFTER the refresh, never from a
                                    // captured snapshot: `allEntries` is a plain value
                                    // remembered against (instanceId, config), so the
                                    // closure below captured the PRE-refresh list and the
                                    // toast always reported the old number — a brand-new
                                    // provider's first successful refresh said
                                    // "Refreshed 0 models from API". entriesFor() is a
                                    // synchronous filter over the in-memory config
                                    // StateFlow (no Room, no IO), so reading it here on
                                    // the main dispatcher is safe and needs no withContext.
                                    val total = providerRepository.entriesFor(instance.id).size
                                    Toast.makeText(
                                        toastContext,
                                        refreshResultMessage(result, total, toastContext),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    AppLogger.info(TAG, "Refreshed models for ${instance.id}: $result")
                                } finally {
                                    isRefreshing = false
                                }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.provider_detail_refresh_models_list))
                        }
                    }
                },
            )

            // Model list follows. The Refresh / Manage controls now live in the
            // single header row above (trailing icon + chevron), so the list is
            // the visual bulk of this section.

            // [perf-provider-detail-models] Render the model list either as a
            // plain Column (small catalogs) or a height-capped LazyColumn
            // (large catalogs). Providers with big catalogs (OpenRouter: 300+)
            // previously did a full forEach inside the outer verticalScroll
            // Column — composing EVERY row up-front, each with its own
            // combinedClickable Box + SettingsRow + modality badge evaluation
            // — freezing the first frame and making every recomposition
            // (typing a label, toggling switches) rebuild the whole list.
            //
            // Small catalogs (<= 12 rows) render as a plain Column so a
            // LazyColumn is never placed inside the outer verticalScroll with
            // an unbounded height. A LazyColumn measured with infinity max
            // height constraints throws IllegalStateException ("Vertically
            // scrollable component was measured with an infinity maximum
            // height constraints") — heightIn(max = Dp.Unspecified) was exactly
            // that suicide path, and crashing the page was worse than the
            // perf win. 12 rows is far below any recycling payoff anyway.
            //
            // Large catalogs use a LazyColumn capped at 400.dp (~5-6 two-line
            // 72dp rows + breathing room): bounded height means the nested
            // scrollable is legal, and rows recycle on scroll so the composed
            // window stays tiny.
            if (entries.size <= 12) {
                Column {
                    entries.forEachIndexed { idx, entry ->
                        ProviderModelRow(
                            entry = entry,
                            showDivider = idx != entries.lastIndex,
                            onClick = { onModelEntryClick(entry.id) },
                            onLongClick = { entryToDeleteId = entry.id },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { idx, entry ->
                        ProviderModelRow(
                            entry = entry,
                            showDivider = idx != entries.lastIndex,
                            onClick = { onModelEntryClick(entry.id) },
                            onLongClick = { entryToDeleteId = entry.id },
                        )
                    }
                }
            }
        }

        // Pure-action rows render as standalone buttons — no Section/Card wrap.
        // Match iOS visual: button sits on the page background with horizontal
        // gutter padding only. The 20dp top padding mirrors SettingsSection's
        // top spacing so the rhythm against the cards above stays consistent.
        MinisOutlinedButton(
            onClick = onAddCustomModel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
        ) {
            Text(stringResource(R.string.provider_detail_add_custom_model))
        }

        // [T-android-delete-provider-button-height] The "Delete provider" button
        // uses the same default 48dp MinisButtonHeight as "Add custom model"
        // above it for visual consistency (no explicit .height override). The
        // destructive intent is conveyed by the error container color, not by a
        // taller button.
        MinisButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_delete_provider))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showDeleteDialog) {
        MinisAlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.provider_detail_delete_provider),
            text = stringResource(R.string.provider_detail_delete_provider_confirm, instance.label),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                deleted = true
                providerRepository.removeInstance(instanceId)
                AppLogger.info(TAG, "Deleted provider instance ${instance.id} (${instance.label})")
                showDeleteDialog = false
                onBack()
            },
        )
    }

    // [T-provider-export-key-warning] Confirm before export: the file includes
    // the plain API key. On confirm, hand off to the share sheet.
    if (showExportDialog) {
        MinisAlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = stringResource(R.string.provider_detail_export_confirm_title),
            text = stringResource(R.string.provider_detail_export_confirm_text),
            confirmText = stringResource(R.string.provider_detail_export_confirm_action),
            onConfirm = {
                showExportDialog = false
                val live = currentInstance()
                AppLogger.info(TAG, "Export instance ${live.id} (${live.label})")
                scope.launch {
                    // [deep-audit-1048 B-04] The export reports whether the
                    // chooser really started; a failed staging write / provider
                    // URI / handler used to be indistinguishable from success.
                    val launched = exportProviderInstance(exportContext, providerRepository, live)
                    if (!launched) {
                        Toast.makeText(
                            exportContext,
                            exportContext.getString(
                                R.string.file_share_failed_toast,
                                "${live.label}.json",
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    // T143: per-entry delete confirmation. removeEntry also strips the entry
    // from any modelGroups it belongs to (see ProviderRepository L304-306),
    // so the StateFlow update propagates the row removal everywhere.
    // [deep-audit-1040 B-07] Resolve the pending target against the live catalog
    // every frame, and close the dialog once it stops resolving (external
    // delete / restore) rather than confirming a delete against a dead id.
    // Scoped to `allEntries` (the full catalog, not the visible subset) so a
    // model that was merely hidden keeps its dialog — it still exists and
    // deleting it stays meaningful; same choice as Quick Test
    // ([deep-audit-1039 B-08] resolves against `config.modelEntries`).
    val entryToDelete = liveEntryToDelete(entryToDeleteId, allEntries)
    LaunchedEffect(entryToDeleteId, allEntries) {
        if (entryDeleteTargetVanished(entryToDeleteId, allEntries)) entryToDeleteId = null
    }
    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDeleteId = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(R.string.provider_detail_delete_model_confirm, e.model.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                AppLogger.info(TAG, "Deleted model entry ${e.id} (${e.model.displayName})")
                entryToDeleteId = null
            },
        )
    }

    // [T-provider-detail-visible-models] Manage All Models opens as a
    // ModalBottomSheet — low-frequency one-time action, half-screen sheet
    // instead of a full navigation destination.
    if (showManageModelsSheet) {
        ManageProviderModelsSheet(
            instanceId = instanceId,
            providerRepository = providerRepository,
            onDismiss = { showManageModelsSheet = false },
            onModelEntryClick = { entryId ->
                showManageModelsSheet = false
                onModelEntryClick(entryId)
            },
        )
    }
}

// ─── Credential blocks ─────────────────────────────────────────────────────────

@Composable
internal fun ApiKeyCredentialBlock(
    storedKey: String?,
    keyVisible: Boolean,
    onToggleVisibility: () -> Unit,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
) {
    if (isEditing) {
        SectionTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            singleLine = true,
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide" else "Show",
                    )
                }
            },
            fieldModifier = Modifier.bringIntoViewOnFocus(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // [T-android-settings-ui-md3] #4 + #12 Cancel is the SECONDARY action:
            // a neutral outlined pill (onSurfaceVariant content/border), forming
            // the standard MD3 outlined-vs-filled pair with the filled Save below.
            // Previously a primary-teal text button — indistinguishable from Save.
            MinisSmallOutlinedButton(
                onClick = onCancelEdit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            MinisSmallButton(onClick = onSave, enabled = editValue.isNotBlank()) {
                Text(stringResource(R.string.provider_detail_save_key))
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (storedKey != null) {
                if (keyVisible) storedKey else maskedKey(storedKey)
            } else {
                "" // key not loaded yet — LaunchedEffect is still reading prefs
            },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (keyVisible) "Hide" else "Show",
                )
            }
            MinisSmallTextButton(onClick = onBeginEdit) {
                Text(stringResource(R.string.common_edit))
            }
        }
    }
}

private fun maskedKey(key: String?): String {
    if (key == null) return ""
    if (key.length <= 10) return key.replace(Regex("."), "•")
    return key.take(6) + "..." + key.takeLast(4)
}

/**
 * [T-provider-connection-screen] One-line summary for the "API & Connection"
 * row: masked API key (or credential type label for OAuth / manual bearer)
 * plus the effective base URL. Keeps the detail screen glanceable — the full
 * editing surface lives on ProviderConnectionScreen.
 */
private fun connectionSummary(
    instance: com.rikkaminis.app.data.model.ProviderInstance,
    storedKey: String?,
): String {
    val credPart = when {
        storedKey.isNullOrEmpty() ->
            instance.credentialType.name.lowercase()
        else -> maskedKey(storedKey)
    }
    val endpoint = instance.customBaseURL?.takeIf { it.isNotBlank() }
        ?.let { instance.effectiveBaseURL }
        ?: instance.providerType.name
    // Show only the hostname (and port if non-standard) for readability.
    // Long URLs in the subtitle row are cramped and ugly; the hostname is
    // enough context at a glance. Fall back to a 40-char truncation.
    val hostname = try {
        java.net.URI(endpoint).let { uri ->
            if (uri.port > 0 && uri.port != 443 && uri.port != 80)
                "${uri.host}:${uri.port}"
            else uri.host
        } ?: endpoint.take(40)
    } catch (_: Exception) {
        endpoint.take(40)
    }
    return "$credPart · $hostname"
}

/**
 * Export the provider instance as JSON and hand it to the system share sheet
 * via FileProvider. Mirrors iOS `ProviderShareSheet` — writes a <label>.json
 * file to app-scoped cache and emits ACTION_SEND with a content:// URI.
 *
 * [deep-audit-1048 B-04] Returns whether the chooser was actually started.
 * Every failure exit used to be a bare `return`, so a failed staging write, a
 * FileProvider misconfiguration or a missing handler all looked exactly like a
 * successful export (nothing on screen), and the caller had no way to tell.
 */
private suspend fun exportProviderInstance(
    context: android.content.Context,
    providerRepository: ProviderRepository,
    instance: com.rikkaminis.app.data.model.ProviderInstance,
): Boolean {
    val safeLabel = instance.label.ifBlank { "provider" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    // [da1024-G] exportInstanceJSON reads EncryptedSharedPreferences and
    // serializes the whole instance (hundreds of entries for a large catalog),
    // and the staging write is disk I/O — both used to run on the UI thread
    // from the dialog's onConfirm. Returns false when the export or the write
    // fails; the caller surfaces it instead of silently skipping the sheet.
    val file = withContext(Dispatchers.IO) {
        val json = providerRepository.exportInstanceJSON(instance.id) ?: return@withContext null
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        // Same 24h-TTL pruner the cache/share/ writers use. This file carries
        // the API key in plain Base64 (see the confirmation dialog), so a stale
        // copy must not outlive a day.
        com.rikkaminis.app.ui.components.pruneShareDir(dir)
        val f = java.io.File(dir, "$safeLabel.json")
        runCatching { f.writeText(json) }.map { f }.getOrNull()
    } ?: return false

    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (_: Throwable) {
        return false
    }

    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeLabel.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export provider").apply {
        // [deep-audit-1048 B-04] The chooser carries the flag itself, like the
        // markdown share path and the log share: `exportContext` is whatever
        // LocalContext hands us, and starting an activity from a non-Activity
        // context without NEW_TASK throws AndroidRuntimeException.
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // A device with no share target throws ActivityNotFoundException here — it
    // used to escape the coroutine scope and take the process with it.
    return runCatching { context.startActivity(chooser) }.isSuccess
}

/** Input modalities that get a (muted) capability badge in the model list. */
internal val modalityIconKeys = setOf("image", "pdf", "audio", "video")

/** Output modalities that get a (tinted, generate-style) badge in the model list. */
internal val modalityOutputIconKeys = setOf("image", "audio", "video")

/**
 * One model row in the provider detail list — shared by both the plain
 * Column path (small catalogs) and the LazyColumn path (large catalogs).
 * Both click and long-press live on a wrapping Box so SettingsRow's
 * signature stays untouched; a long-press on built-in entries is a no-op
 * (they reappear on the next model refresh anyway).
 */
@Composable
private fun ProviderModelRow(
    entry: com.rikkaminis.app.data.model.ModelEntry,
    showDivider: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // [T-android-hidden-model-visual-state] Dim hidden
            // entries so the list visually distinguishes them
            // from active models — the " • Hidden" subtitle
            // suffix alone wasn't enough for users to tell them
            // apart. alpha is visual-only, so the row stays
            // tappable to re-show the model from its detail
            // screen.
            .then(if (entry.isHidden) Modifier.alpha(0.45f) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        // [T-android-model-capability-output-tags] (XIN msg 38847)
        // The list previously read INPUT modalities only, so a
        // generator like gpt-image-2 (image_output) or an
        // audio_output model showed no capability badge at all.
        // Surface both directions — input badges are muted,
        // output badges use a tinted "generate"-style glyph so
        // they read distinctly. Mirrors iOS #669.
        // [dv22/B-03] Capability source is the effective model (models.dev
        // enrichment under the user's overrides) — the raw entry declares no
        // modalities for any built-in model. normalizeModalities strips the
        // `_input`/`_output` suffix and lowercases, so the icon-key match is no
        // longer suffix- and case-sensitive (`IMAGE_INPUT` used to match nothing).
        val capabilityModel = ModelsDevApi.effectiveModel(entry)
        val inputModalities = capabilityModel.inputModalities.normalizeModalities().orEmpty()
        val outputModalities = capabilityModel.outputModalities.normalizeModalities().orEmpty()
        val hasBadge = inputModalities.any { it in modalityIconKeys } ||
            outputModalities.any { it in modalityOutputIconKeys }
        SettingsRow(
            title = entry.model.displayName,
            subtitle = buildString {
                append(entry.model.id)
                if (entry.isHidden) append(" • Hidden")
            },
            // onClick = null so SettingsRow doesn't add a second
            // clickable that would swallow the long-press. The
            // wrapping Box owns both gestures.
            onClick = null,
            showChevron = true,
            showDivider = showDivider,
            // [T-android-settings-ui-md3] #8 two-line row (name
            // + id) uses the MD3 double-line height (72dp).
            minHeight = 72.dp,
            // #9 The capability-icon area has a FIXED width so
            // the trailing chevron lands at the same x on every
            // row, regardless of how many badges (0–4) a model
            // has — previously the chevron slid left/right per
            // row and the column looked ragged. Icons right-align
            // within the slot.
            trailing = {
                Box(
                    modifier = Modifier.width(72.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (hasBadge) {
                        ModalityIconsRow(inputModalities, outputModalities)
                    }
                }
            },
        )
    }
}

/**
 * [T-android-model-capability-output-tags] Capability badges for one model row.
 * Input modalities render in the muted onSurfaceVariant tint; output modalities
 * render in the primary tint with "generate"-style glyphs so a generator (e.g.
 * gpt-image-2 image_output) is visibly distinct from a model that merely accepts
 * that modality as input. Mirrors iOS ProviderInstanceDetailView.modalityIcons.
 */
@Composable
internal fun ModalityIconsRow(
    inputModalities: List<String>,
    outputModalities: List<String>,
) {
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val outputTint = MaterialTheme.colorScheme.primary
    val size = Modifier.size(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Input badges (muted).
        if ("image" in inputModalities) Icon(Icons.Default.Image, contentDescription = stringResource(R.string.modeldetail_image_input), tint = inputTint, modifier = size)
        if ("pdf" in inputModalities) Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = stringResource(R.string.modeldetail_pdf_input), tint = inputTint, modifier = size)
        if ("audio" in inputModalities) Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.modeldetail_audio_input), tint = inputTint, modifier = size)
        if ("video" in inputModalities) Icon(Icons.Default.Videocam, contentDescription = stringResource(R.string.modeldetail_video_input), tint = inputTint, modifier = size)
        // Output badges (tinted, generate-style glyphs).
        if ("image" in outputModalities) Icon(Icons.Default.AddPhotoAlternate, contentDescription = stringResource(R.string.modeldetail_image_output), tint = outputTint, modifier = size)
        if ("audio" in outputModalities) Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.modeldetail_audio_output), tint = outputTint, modifier = size)
        if ("video" in outputModalities) Icon(Icons.Default.MovieCreation, contentDescription = stringResource(R.string.modeldetail_video_output), tint = outputTint, modifier = size)
    }
}

/** Maps a [ModelRefreshResult] to a user-facing toast message. Uses
 *  `context.getString` so all strings come from resources (i18n). Added
 *  [provider-mgmt-opt]. */
private fun refreshResultMessage(
    result: com.rikkaminis.app.data.repository.ModelRefreshResult,
    modelCount: Int,
    context: android.content.Context,
): String = when (result) {
    com.rikkaminis.app.data.repository.ModelRefreshResult.SUCCESS_API ->
        context.getString(R.string.provider_detail_refresh_success_api, modelCount)
    com.rikkaminis.app.data.repository.ModelRefreshResult.NO_KEY ->
        context.getString(R.string.provider_detail_refresh_no_key)
    com.rikkaminis.app.data.repository.ModelRefreshResult.PRESERVED ->
        context.getString(R.string.provider_detail_refresh_preserved)
    com.rikkaminis.app.data.repository.ModelRefreshResult.FAILURE ->
        context.getString(R.string.provider_detail_refresh_failed)
}

// ─── [deep-audit-1040 B-07] Pending-delete resolution ────────────────────────

/**
 * Resolve a pending model-entry delete against the LIVE catalog.
 *
 * Pure on purpose: the decision is the part worth exercising in a JVM test, and
 * the Compose side above is only wiring. Same channel shape as the Quick Test
 * resolver in [com.rikkaminis.app.ui.settings.liveQuickTestEntry]
 * ([deep-audit-1039 B-08]).
 *
 * (Registered debt: this is the third near-identical "resolve a pending id
 * against the live list" helper in `ui/settings` — see backlog §1040-B1.)
 */
internal fun liveEntryToDelete(pendingId: String?, entries: List<ModelEntry>): ModelEntry? =
    pendingId?.let { id -> entries.find { it.id == id } }

/**
 * True when the delete confirm dialog is open for an id that no longer
 * resolves — i.e. the target was deleted (or restored away) externally while
 * the dialog was up, so the dialog must close instead of confirming a delete
 * against a dead id.
 *
 * Kept separate from [liveEntryToDelete] so the close effect keys on the
 * DECISION ("the target vanished") rather than on "the lookup returned null" —
 * the latter is also true when the dialog was never opened.
 */
internal fun entryDeleteTargetVanished(pendingId: String?, entries: List<ModelEntry>): Boolean =
    pendingId != null && liveEntryToDelete(pendingId, entries) == null
