package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.repository.RemoteCatalogState
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import kotlinx.coroutines.launch

private const val SHEET_TAG = "ManageModelsSheet"

/**
 * [model-catalog-split] ModalBottomSheet managing the model selection set of
 * one provider instance. Opened from the "Manage All Models" row on
 * ProviderDetailScreen — a one-time low-frequency action, so a half-screen
 * sheet (not a navigation destination) fits the job.
 *
 * Data source is the MERGED VIEW "remote catalog ∪ user selections", not the
 * persisted table:
 *  - rows from [RemoteCatalogState.models] (the provider's directory, held in
 *    ProviderRepository.remoteCatalogs after a refresh);
 *  - plus the user's selected entries that the remote catalog does not list
 *    (custom models, entries the vendor dropped from its directory) so a
 *    short/failed catalog response never hides curated selections.
 * Row check state == "the model is selected" (ModelEntry exists). Checking a
 * row calls [ProviderRepository.selectRemoteModel]; unchecking calls
 * [ProviderRepository.unselectEntry]. The old isHidden visibility-switch
 * semantics are gone — the persisted table is selection-only now, so there is
 * nothing to toggle visibility for.
 *
 * Opening the sheet auto-fetches the catalog exactly once (never-fetched
 * state only), so the user sees the provider's directory without hunting for
 * the refresh button; a loading bar and an error banner (list still showing
 * retained models) cover the async outcomes. Search filters the merged rows
 * on displayName + id; Select all / Deselect all operate on the FILTERED set.
 *
 * Search box has intentionally blank placeholder — nothing to hint at (user
 * may type a model name; the list + checkboxes are self-evident).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProviderModelsSheet(
    instanceId: String,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    // [da1025-F] Key on (instanceId, config), mirroring the selected remember
    // below: config changes when the provider is deleted, and keying on
    // instanceId alone kept the stale non-null snapshot here — the
    // provider-deleted auto-dismiss guard below never fired.
    val instance = remember(instanceId, config) { config.instances.firstOrNull { it.id == instanceId } }
    if (instance == null) {
        // Provider deleted while the sheet was open -> just close
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    // [model-catalog-split] Merged data source. `selected` is the persisted
    // selection-only table (keyed like [da1025-F] above); `state` is the
    // remote catalog for this instance observed off the repository flow so a
    // background refresh updates the rows reactively.
    val selected = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }
    val catalogs by providerRepository.remoteCatalogs.collectAsState()
    val state = catalogs[instanceId] ?: RemoteCatalogState()
    val rows = remember(state, selected) { buildCatalogRows(state.models, selected) }

    var query by remember { mutableStateOf("") }
    // [model-catalog-split] ONE query predicate drives both the visible row
    // filter and the select-all decision (decideSelectAllAction below), so the
    // button and the list can never disagree about what "the filtered set" is.
    val queryFilter: (LLMModel) -> Boolean = remember(query) {
        val q = query.trim()
        if (q.isBlank()) {
            { _: LLMModel -> true }
        } else {
            { m: LLMModel ->
                m.displayName.contains(q, ignoreCase = true) || m.id.contains(q, ignoreCase = true)
            }
        }
    }
    val filtered = remember(rows, queryFilter) { rows.filter { (model, _) -> queryFilter(model) } }

    val scope = rememberCoroutineScope()

    // [model-catalog-split] Auto-fetch on open, once, and only when the
    // catalog has never been fetched (fetchedAt == 0 && models empty). The
    // loading guard prevents concurrent re-fetches when the user closes and
    // reopens the sheet mid-flight; forceRefresh stays the API default
    // (true) — the sheet must actually hit the network.
    LaunchedEffect(instanceId) {
        val pre = providerRepository.remoteCatalog(instanceId)
        if (pre.models.isEmpty() && !pre.loading && pre.fetchedAt == 0L) {
            providerRepository.refreshRemoteCatalog(instanceId)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // [T-fix-sheet-bottom-clip] The height constraint must live on a wrapping
        // Column INSIDE the sheet content, NOT on the ModalBottomSheet modifier.
        // ModalBottomSheet internally applies wrapContentHeight() after the user
        // modifier, so fillMaxHeight(0.75f) on it gets overridden, the content
        // Column is measured at unconstrained height, and the Surface then clips
        // the bottom at 75% — "bottom suddenly cut off". Constraining the inner
        // Column keeps the LazyColumn's weight(1f) inside a bounded height, and
        // the sheet wraps to exactly that height. No clipping.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
        ) {
            // Header: merged count + Done button. The count is the ROW count of
            // the merged view (remote ∪ orphan selections) — not the persisted
            // table size, which used to be the whole hidden catalog.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.provider_detail_manage_models_all_header, rows.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.common_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // [model-catalog-split] Catalog fetch progress: a thin bar directly
            // under the header while the remote directory is in flight.
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            // [model-catalog-split] Error banner. A failed refresh RETAINS the
            // last successful catalog (RemoteCatalogState contract), so the
            // list below stays usable — the banner explains why the refresh
            // button "did nothing", it never blanks the sheet. Capped at 4
            // lines: gateway HTML dumps must not push the list off-screen.
            val catalogError = state.error
            if (catalogError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.manage_models_error_prefix, catalogError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // Select all / Deselect all — acts on the FILTERED set (rikkahub
            // parity): when any filtered row is unchecked the button selects
            // all unchecked ones (label shows how many); once every filtered
            // row is checked it becomes the deselect action. Selecting an
            // already-selected model is idempotent on the repository side, but
            // the button only ever targets the unchecked set, so the label
            // count and the effect always agree. The boolean decision is the
            // JVM-tested contract fn, not an inline count check.
            val selectAll = decideSelectAllAction(filtered, queryFilter)
            val uncheckedInFilter = filtered.count { it.second == null }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectAll && uncheckedInFilter > 0) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.manage_models_select_all_count, uncheckedInFilter),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                scope.launch {
                                    filtered.forEach { (model, entry) ->
                                        if (entry == null) {
                                            providerRepository.selectRemoteModel(instanceId, model)
                                            AppLogger.info(SHEET_TAG, "Select-all: selected ${model.id}")
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                } else if (filtered.isNotEmpty()) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.manage_models_deselect_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                scope.launch {
                                    filtered.forEach { (_, entry) ->
                                        if (entry != null) {
                                            providerRepository.unselectEntry(entry.id)
                                            AppLogger.info(SHEET_TAG, "Deselect-all: unselected ${entry.id}")
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            // Search field, blank placeholder, clear button when non-empty
            OutlinedTextField(
                value = query,
                onValueChange = { query = sanitizeSingleLineInput(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = {},
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.model_picker_search_clear))
                        }
                    }
                } else null,
            )

            // Section header separating the provider's directory from rows that
            // only exist because the user selected them (custom / dropped).
            if (state.models.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.manage_models_remote_section_header, state.models.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Blank WITH an error and no rows at all: the fetch failed
                    // and there is nothing retained — say so via the banner
                    // above instead of the misleading "no results" copy.
                    if (catalogError != null && rows.isEmpty()) {
                        Text(
                            text = stringResource(R.string.manage_models_error_prefix, catalogError),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.model_picker_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    itemsIndexed(filtered, key = { _, row -> row.first.id }) { index, row ->
                        CatalogRowItem(
                            model = row.first,
                            selectedEntry = row.second,
                            onClick = {
                                // [model-tier-selection] Click semantics:
                                // selected rows open the entry detail page;
                                // unchecked remote rows are toggled INTO the
                                // selection (the row checkbox is the direct
                                // surface, the row body is the fat-finger
                                // friendly one).
                                val entry = row.second
                                if (entry != null) {
                                    onModelEntryClick(entry.id)
                                } else {
                                    scope.launch {
                                        val id = providerRepository.selectRemoteModel(instanceId, row.first)
                                        AppLogger.info(SHEET_TAG, "Selected ${row.first.id} -> entry $id")
                                    }
                                }
                            },
                            onCheckedChange = { wantSelected ->
                                scope.launch {
                                    val entry = row.second
                                    if (wantSelected && entry == null) {
                                        val id = providerRepository.selectRemoteModel(instanceId, row.first)
                                        AppLogger.info(SHEET_TAG, "Selected ${row.first.id} -> entry $id")
                                    } else if (!wantSelected && entry != null) {
                                        providerRepository.unselectEntry(entry.id)
                                        AppLogger.info(SHEET_TAG, "Unselected ${row.first.id} (entry ${entry.id})")
                                    }
                                }
                            },
                        )
                        if (index < filtered.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogRowItem(
    model: LLMModel,
    selectedEntry: ModelEntry?,
    onClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.id != model.displayName && model.id.isNotBlank()) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // [model-catalog-split] Checkbox = "this model is in the user's
        // selection set". Replaces the old isHidden visibility Switch: the
        // persisted table holds selections only, so there is no separate
        // visible/hidden state left to toggle.
        androidx.compose.material3.Checkbox(
            checked = selectedEntry != null,
            onCheckedChange = onCheckedChange,
        )
    }
}

// ==== [model-catalog-split] Catalog row contract (JVM-tested) ====

/**
 * Merge the remote catalog with the user's selected entries into sheet rows.
 *
 * Dedup key is [LLMModel.id]: a selected model that the remote catalog lists
 * folds into exactly one row carrying its [ModelEntry] (row check state);
 * selections the catalog does NOT list (custom models, vendor-dropped ids)
 * are APPENDED after the remote rows so they stay reachable and a failed /
 * shortened catalog response never hides the user's curated models.
 *
 * Pure: no repository access, no Compose — JVM-tested in
 * [ModelCatalogSplitUiContractTest].
 */
internal fun buildCatalogRows(
    remoteModels: List<LLMModel>,
    selectedEntries: List<ModelEntry>,
): List<Pair<LLMModel, ModelEntry?>> {
    val selectedByModelId = selectedEntries.associateBy { it.baseModel.id }
    val remoteRows = remoteModels.map { it to selectedByModelId[it.id] }
    val orphanRows = selectedEntries
        .filter { entry -> remoteModels.none { it.id == entry.baseModel.id } }
        .map { it.baseModel to it }
    return remoteRows + orphanRows
}

/**
 * Select-all / deselect-all decision for the management sheet: within the
 * FILTERED row set, any unchecked row means "Select all"; only when every
 * filtered row is checked does the action become "Deselect all".
 *
 * An empty set (or a filter matching nothing) yields false — the unchecked
 * count is 0, so the decision falls to the deselect branch; the composable
 * hides both buttons for an empty filtered set anyway. Pure — JVM-tested
 * in [ModelCatalogSplitUiContractTest].
 */
internal fun decideSelectAllAction(
    rows: List<Pair<LLMModel, ModelEntry?>>,
    filter: (LLMModel) -> Boolean,
): Boolean = rows.any { filter(it.first) && it.second == null }

// ==== END [model-catalog-split] Catalog row contract ====
