package com.rikkaminis.app.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.SectionDesign
import com.rikkaminis.app.ui.components.SectionHeader
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddProvider: () -> Unit,
    onProviderClick: (String) -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val instances = config.instances
    val context = LocalContext.current

    // [perf-provider-list] Pre-compute per-instance display data once per
    // `instances` change. loadApiKey() hits EncryptedSharedPreferences
    // (synchronous encrypted I/O); naive inline calls inside the forEach
    // re-ran them for EVERY row on EVERY recomposition, stalling the frame
    // during navigation transitions. Caching here means the I/O happens once
    // per instances change, not once per row per recomposition.
    val providerRows: List<ProviderRowData> = remember(instances) {
        instances.map { instance ->
            val apiKey = providerRepository.loadApiKey(instance.id)
            val isConfigured = !apiKey.isNullOrBlank()
            ProviderRowData(
                instance = instance,
                modelCount = providerRepository.visibleEntries(instance.id).size,
                apiKey = apiKey,
                isConfigured = isConfigured,
            )
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    // [reorder-providers] Drag-to-reorder the provider list. Rendering
    // tolerates a duplicate-id config by hiding the extra rows (same
    // belt-and-suspenders as the Model Groups list): duplicate
    // LazyColumn/Reorderable keys would otherwise crash this screen on
    // scroll. The reorder path below is the deliberate asymmetry —
    // ProviderRepository.permuteById REFUSES a list holding duplicate ids, so
    // on a corrupted config drags just no-op until the duplicate is gone.
    val reorderableRows = remember(instances) { providerRows.distinctBy { it.instance.id } }
    val pinnedRows = reorderableRows.filter { it.instance.pinned }
    val groupedRows = reorderableRows.filter { !it.instance.pinned }.groupBy { it.instance.providerType }

    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // The list also contains headers and spacers. Only "inst:"-keyed rows
        // participate; anything else is a no-op, which reads better than a
        // snap-back.
        if (!fromKey.startsWith("inst:") || !toKey.startsWith("inst:")) {
            return@rememberReorderableLazyListState
        }
        val fromId = fromKey.removePrefix("inst:")
        val toId = toKey.removePrefix("inst:")
        val cur = providerRepository.config.value.instances
        val fromInst = cur.find { it.id == fromId } ?: return@rememberReorderableLazyListState
        val toInst = cur.find { it.id == toId } ?: return@rememberReorderableLazyListState
        // Same-section only: the UI buckets instances by providerType (plus a
        // pinned Favorites section floating on top), so a cross-section drag
        // has no meaning — the row would snap back into its own bucket on the
        // next recomposition anyway. Both pinned, or both unpinned of the same
        // providerType, participate; anything else is a no-op.
        val sameSection = if (fromInst.pinned) toInst.pinned
        else !toInst.pinned && fromInst.providerType == toInst.providerType
        if (!sameSection) return@rememberReorderableLazyListState
        // Members of the dragged row's section, in current flat order.
        val memberIds = cur.filter {
            if (fromInst.pinned) it.pinned
            else !it.pinned && it.providerType == fromInst.providerType
        }.map { it.id }
        val fromIdx = memberIds.indexOf(fromId)
        val toIdx = memberIds.indexOf(toId)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        val newMembers = memberIds.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        // Splice the re-ordered section back into the flat instance order.
        // Each section is derived by filtering this flat list, so the
        // relative order of everything outside the section must be preserved.
        val memberSet = memberIds.toSet()
        val newOrder = ArrayList<String>(cur.size)
        var memberPos = 0
        for (inst in cur) {
            if (inst.id in memberSet) newOrder.add(newMembers[memberPos++]) else newOrder.add(inst.id)
        }
        providerRepository.reorderInstances(newOrder)
    }

    val importScope = rememberCoroutineScope()
    // [T6-M4] Toast needs a Looper; the import now runs on IO, so post the
    // user-facing messages back to the main thread.
    val importToastHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // [T6-M4] The whole import (zip staging + extraction + file I/O) ran on
        // the activity-result callback thread (Main); a multi-MB provider bundle
        // froze the UI for hundreds of ms to seconds. Do the work on IO and post
        // the Toasts back to Main.
        //
        // [fix/audit0917-b8] Run it on the APPLICATION scope, not
        // rememberCoroutineScope: the repository is app-scoped and the writes
        // are already committed by the time a screen-scoped coroutine would be
        // cancelled, so backing out of the provider list mid-import left a
        // partially imported bundle with no summary toast. Fall back to the
        // composable scope if the cast ever fails, so the work still happens.
        val importRunner = (context.applicationContext as? MinisApp)?.applicationScope
            ?: importScope
        importRunner.launch {
            withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val name = ProviderImportZip.queryDisplayName(context, uri).orEmpty()
                val looksLikeZip = mime == "application/zip" ||
                    mime == "application/x-zip-compressed" ||
                    name.lowercase().endsWith(".zip")
                try {
                    if (looksLikeZip) {
                        val toastFailed = context.getString(R.string.import_zip_extract_failed)
                        val toastNoSupported = context.getString(R.string.import_zip_no_supported)
                        ProviderImportZip.importFromZip(
                            context = context,
                            uri = uri,
                            onImportSingle = { jsonStr -> providerRepository.importInstanceJSON(jsonStr) },
                            onExtractFailed = { importToastHandler.post { Toast.makeText(context, toastFailed, Toast.LENGTH_SHORT).show() } },
                            onNoSupported = { importToastHandler.post { Toast.makeText(context, toastNoSupported, Toast.LENGTH_SHORT).show() } },
                            onSummary = { ok, total ->
                                val msg = context.getString(R.string.import_zip_summary, ok, total)
                                importToastHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            },
                        )
                    } else {
                        // [fix/audit0917-b8] openInputStream returning null is a
                        // real I/O failure (revoked grant, provider gone), and
                        // the old `if (jsonStr != null)` fell through it with no
                        // toast at all — the user picked a file and nothing
                        // happened. Report it like any other read error.
                        val jsonStr = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                        if (jsonStr == null) {
                            val msg = context.getString(R.string.provider_import_read_error)
                            importToastHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                            return@withContext
                        }
                        val label = providerRepository.importInstanceJSON(jsonStr)
                        if (label != null) {
                            val toastMsg = context.getString(R.string.provider_import_success, label)
                            importToastHandler.post { Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show() }
                        } else {
                            val msg = context.getString(R.string.provider_import_invalid_file)
                            importToastHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        }
                    }
                } catch (e: Exception) {
                    val msg = context.getString(R.string.provider_import_read_error)
                    importToastHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_list_providers),
        onBack = null, // top-level page: rely on system back gesture / bottom nav
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.provider_list_add_provider))
            }
        },
        // [reorder-providers] The list below is a LazyColumn (reorderable rows
        // must be its direct children) — no nested verticalScroll here.
        scrollable = false,
    ) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(Modifier.height(SectionDesign.FirstSectionTopGap)) }

            if (instances.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VpnKey,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        )
                        Text(
                            text = stringResource(R.string.provider_list_no_providers_configured),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.provider_list_add_a_provider_to_get_started),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                // [P0-pinned-providers] Favorites section: pinned instances float
                // to the very top, separate from their providerType group, so the
                // providers the user reaches for most are always one tap away.
                //
                // [reorder-providers] Each row MUST be its own top-level
                // LazyColumn item: ReorderableLazyListState only observes direct
                // children of the list, so the old "one SettingsSection wrapping
                // every row" shape can't reorder. The iOS-style card panel is
                // repainted per row via cardRow(isFirst, isLast) +
                // SectionDividerInsetCard() between rows — the same helpers the
                // Model Groups list uses (ReorderableCardRow.kt). Drag only moves
                // a row within its own section (see the same-section guard in
                // reorderState): the screen buckets by providerType, so a
                // cross-section move would snap straight back.
                if (pinnedRows.isNotEmpty()) {
                    item("fav_header") {
                        SectionHeader(text = stringResource(R.string.provider_list_favorites))
                    }
                    itemsIndexed(
                        items = pinnedRows,
                        key = { _, row -> "inst:${row.instance.id}" },
                    ) { index, row ->
                        ReorderableItem(state = reorderState, key = "inst:${row.instance.id}") { _ ->
                            Column {
                                if (index != 0) SectionDividerInsetCard()
                                Box(
                                    modifier = Modifier.cardRow(
                                        isFirst = index == 0,
                                        isLast = index == pinnedRows.lastIndex,
                                    ),
                                ) {
                                    ProviderInstanceRow(
                                        instance = row.instance,
                                        modelCount = row.modelCount,
                                        apiKey = row.apiKey,
                                        isConfigured = row.isConfigured,
                                        pinned = row.instance.pinned,
                                        onTogglePinned = remember(row.instance.id) {
                                            { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
                                        },
                                        onClick = remember(row.instance.id) { { onProviderClick(row.instance.id) } },
                                        dragHandleModifier = with(this@ReorderableItem) { Modifier.draggableHandle() },
                                    )
                                }
                            }
                        }
                    }
                }
                groupedRows.forEach { (providerType, typeRows) ->
                    item("type_header_${providerType.name}") {
                        SectionHeader(text = providerType.displayName)
                    }
                    itemsIndexed(
                        items = typeRows,
                        key = { _, row -> "inst:${row.instance.id}" },
                    ) { index, row ->
                        ReorderableItem(state = reorderState, key = "inst:${row.instance.id}") { _ ->
                            Column {
                                if (index != 0) SectionDividerInsetCard()
                                Box(
                                    modifier = Modifier.cardRow(
                                        isFirst = index == 0,
                                        isLast = index == typeRows.lastIndex,
                                    ),
                                ) {
                                    ProviderInstanceRow(
                                        instance = row.instance,
                                        modelCount = row.modelCount,
                                        apiKey = row.apiKey,
                                        isConfigured = row.isConfigured,
                                        pinned = row.instance.pinned,
                                        onTogglePinned = remember(row.instance.id) {
                                            { providerRepository.setInstancePinned(row.instance.id, !row.instance.pinned) }
                                        },
                                        onClick = remember(row.instance.id) { { onProviderClick(row.instance.id) } },
                                        dragHandleModifier = with(this@ReorderableItem) { Modifier.draggableHandle() },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // [voice-removed] The runtime "Voice Services" shadow section was
            // removed along with the rest of the in-app voice UI. The underlying
            // voice provider engine still exists for agent-facing tools; it just
            // no longer surfaces as its own provider-list section here.
            item("bottom_gap") { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            onAddProvider()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_add_provider), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showMenu = false
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                ),
                            )
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.provider_list_import_provider), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ProviderInstanceRow(
    instance: ProviderInstance,
    modelCount: Int,
    apiKey: String?,
    isConfigured: Boolean,
    pinned: Boolean,
    onTogglePinned: () -> Unit,
    onClick: () -> Unit,
    // [reorder-providers] Built by the caller because
    // ReorderableItemScope.draggableHandle() is scope-bound; threading it in
    // keeps this row scope-agnostic (same pattern as GroupRow on the Model
    // Groups list). Only the handle starts a drag — the row itself stays
    // clickable and the star stays a tap, so neither gesture fights the drag.
    dragHandleModifier: Modifier = Modifier,
) {
    val isActive = isConfigured && instance.isEnabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (isActive) ChatColors.success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                    shape = CircleShape,
                ),
        )

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = instance.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_list_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    text = if (!apiKey.isNullOrBlank()) maskKey(apiKey) else stringResource(R.string.provider_list_no_api_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (modelCount > 0) {
                Text(
                    text = stringResource(R.string.provider_list_models_count, modelCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        if (!instance.isEnabled) {
            Text(
                text = stringResource(R.string.provider_list_disabled),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(50),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        // [P0-pinned-providers] Inline star toggles favorite directly — no overflow menu needed.
        IconButton(
            onClick = onTogglePinned,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (pinned) R.string.provider_unset_favorite
                    else R.string.provider_set_favorite,
                ),
                tint = if (pinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(20.dp),
            )
        }

        // [reorder-providers] Explicit drag handle (IconButton, not a bare Icon
        // — see [T198] on DragHandleButton). Content description reuses the
        // model-group "drag to reorder" string: same gesture, same meaning.
        DragHandleButton(handleModifier = dragHandleModifier)

    }
}

private fun maskKey(key: String): String {
    if (key.length <= 8) return "****"
    return key.take(6) + "..." + key.takeLast(4)
}

/** [perf-provider-list] Per-instance display data, pre-computed once per
 *  instances change so the per-row composition never re-runs
 *  EncryptedSharedPreferences reads (loadApiKey / OAuth isAuthenticated). */
private data class ProviderRowData(
    val instance: ProviderInstance,
    val modelCount: Int,
    val apiKey: String?,
    val isConfigured: Boolean,
)
