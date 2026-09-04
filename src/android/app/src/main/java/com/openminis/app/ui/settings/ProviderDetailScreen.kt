package com.openminis.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import android.widget.Toast
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.util.bringIntoViewOnFocus
import com.openminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.theme.ChatColors

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
    var entryToDelete by remember { mutableStateOf<com.openminis.app.data.model.ModelEntry?>(null) }
    var deleted by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (instance == null && !deleted) { onBack(); return }
    if (instance == null) return
    val currentInstance = instance

    var label by remember { mutableStateOf(instance.label) }
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
    LaunchedEffect(instanceId) {
        storedKey = withContext(Dispatchers.IO) { providerRepository.loadApiKey(instanceId) }
    }

    // [T-provider-detail-visible-models] Manage All Models opens as a
    // ModalBottomSheet (low-frequency one-time action). No navigation route —
    // the sheet lives inside this screen.
    var showManageModelsSheet by remember { mutableStateOf(false) }

    var isEnabled by remember { mutableStateOf(instance.isEnabled) }

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
    // Full catalog size, for the section header + refresh toast. Computed the
    // same remembered way so it doesn't rescan on every recomposition either.
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
                            providerRepository.updateInstance(instance.copy(label = label))
                            AppLogger.info(TAG, "Updated label for ${instance.id}: '$label'")
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
                    providerRepository.updateInstance(instance.copy(isEnabled = it))
                    AppLogger.info(TAG, "Set enabled=$it for ${instance.id}")
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
        ThinkingRulesSection(instance = currentInstance, providerRepository = providerRepository)

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
                                    Toast.makeText(
                                        toastContext,
                                        refreshResultMessage(result, allEntries.size, toastContext),
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
                            onLongClick = { entryToDelete = entry },
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
                            onLongClick = { entryToDelete = entry },
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
                AppLogger.info(TAG, "Export instance ${instance.id} (${instance.label})")
                exportProviderInstance(exportContext, providerRepository, instance)
            },
        )
    }

    // T143: per-entry delete confirmation. removeEntry also strips the entry
    // from any modelGroups it belongs to (see ProviderRepository L304-306),
    // so the StateFlow update propagates the row removal everywhere.
    entryToDelete?.let { e ->
        MinisAlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = stringResource(R.string.provider_detail_delete_model),
            text = stringResource(R.string.provider_detail_delete_model_confirm, e.model.displayName),
            confirmText = stringResource(R.string.common_delete),
            isDestructive = true,
            onConfirm = {
                providerRepository.removeEntry(e.id)
                AppLogger.info(TAG, "Deleted model entry ${e.id} (${e.model.displayName})")
                entryToDelete = null
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
    instance: com.openminis.app.data.model.ProviderInstance,
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
 */
private fun exportProviderInstance(
    context: android.content.Context,
    providerRepository: ProviderRepository,
    instance: com.openminis.app.data.model.ProviderInstance,
) {
    val json = providerRepository.exportInstanceJSON(instance.id) ?: return
    val safeLabel = instance.label.ifBlank { "provider" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeLabel.json")
    runCatching { file.writeText(json) }.onFailure { return }

    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } catch (_: Throwable) {
        return
    }

    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "$safeLabel.json")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(sendIntent, "Export provider").apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
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
    entry: com.openminis.app.data.model.ModelEntry,
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
        val inputModalities = entry.model.inputModalities.orEmpty()
        val outputModalities = entry.model.outputModalities.orEmpty()
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
    result: com.openminis.app.data.repository.ModelRefreshResult,
    modelCount: Int,
    context: android.content.Context,
): String = when (result) {
    com.openminis.app.data.repository.ModelRefreshResult.SUCCESS_API ->
        context.getString(R.string.provider_detail_refresh_success_api, modelCount)
    com.openminis.app.data.repository.ModelRefreshResult.NO_KEY ->
        context.getString(R.string.provider_detail_refresh_no_key)
    com.openminis.app.data.repository.ModelRefreshResult.PRESERVED ->
        context.getString(R.string.provider_detail_refresh_preserved)
    com.openminis.app.data.repository.ModelRefreshResult.FAILURE ->
        context.getString(R.string.provider_detail_refresh_failed)
}
