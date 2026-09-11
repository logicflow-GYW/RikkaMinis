package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput

private const val SHEET_TAG = "ManageModelsSheet"

/**
 * [T-provider-detail-visible-models] ModalBottomSheet listing the FULL catalog
 * of a provider with per-row visibility switches. Opened from the
 * "Manage All Models" row on ProviderDetailScreen — a one-time low-frequency
 * action, so a half-screen sheet (not a navigation destination) fits the job.
 *
 * Search box has intentionally blank placeholder — nothing to hint at
 * (user may type a model name; the list + switches are self-evident).
 * Switch ON = model shown on the detail screen; OFF = hidden. All rows start
 * OFF for a fresh catalog (see ProviderRepository refreshModels default).
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
    val instance = remember(instanceId) { config.instances.firstOrNull { it.id == instanceId } }
    if (instance == null) {
        // Provider deleted while the sheet was open -> just close
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val allEntries = remember(instanceId, config) { providerRepository.entriesFor(instanceId) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, allEntries) {
        if (query.isBlank()) allEntries
        else allEntries.filter {
            it.model.displayName.contains(query.trim(), ignoreCase = true) ||
                it.model.id.contains(query.trim(), ignoreCase = true)
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
        // Header: title + count, Done button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.provider_detail_manage_models_all_header, allEntries.size),
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

        // Search field, blank placeholder, clear button when non-empty
        OutlinedTextField(
            value = query,
            onValueChange = { query = sanitizeSingleLineInput(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = {},
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.model_picker_search_clear))
                    }
                }
            } else null,
        )

        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.model_picker_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                itemsIndexed(filtered, key = { _, e -> e.uuid }) { index, entry ->
                    ModelVisibilityRow(
                        entry = entry,
                        visible = !entry.isHidden,
                        onClick = { onModelEntryClick(entry.uuid) },
                        onToggle = {
                            providerRepository.updateEntry(entry.copy(isHidden = !entry.isHidden))
                            AppLogger.info(SHEET_TAG, "Toggled ${entry.model.displayName} hidden=${entry.isHidden}")
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
private fun ModelVisibilityRow(
    entry: ModelEntry,
    visible: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
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
                text = entry.model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.model.id != entry.model.displayName && entry.model.id.isNotBlank()) {
                Text(
                    text = entry.model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = visible,
            onCheckedChange = { onToggle() },
        )
    }
}
