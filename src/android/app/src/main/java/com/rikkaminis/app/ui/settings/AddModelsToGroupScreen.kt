package com.rikkaminis.app.ui.settings

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.components.PickerModalityFilter
import com.rikkaminis.app.ui.components.modelEntryPickerItems
import com.rikkaminis.app.ui.components.rememberCollapsedInstanceIds
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelsToGroupScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId }

    if (group == null) {
        // [F-2 sweep1013] composition-phase onBack() — wrap like
        // ModelEntryDetailScreen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val existingIds = group.memberEntryIds.toSet()
    // [T-android-provider-voice] Voice-scoped picker: when the target group is
    // bound as the Voice Input/Output group, scope the shared picker by
    // modality (ASR = audio-in, TTS = audio-out). Modality filtering + System
    // virtual entry injection live inside modelEntryPickerItems (mirrors iOS
    // UnifiedModelPicker effectivePreferModality / candidateEntries).
    val modalityFilter = when (groupId) {
        config.voiceInputGroupId -> PickerModalityFilter.AUDIO_INPUT
        config.voiceOutputGroupId -> PickerModalityFilter.AUDIO_OUTPUT
        else -> null
    }
    val availableEntries = config.modelEntries.filter {
        !it.isHidden && it.id !in existingIds
    }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // [deep-audit-1039 B-08] Hold the Quick Test target as an ID, not as the
    // ModelEntry captured at click time: the sheet stays open across external
    // writes (minis-config / Debug RPC / backup restore), so a frozen object
    // kept feeding "Run again" the config the row had when it was tapped, and a
    // deleted model left the sheet open against a row that no longer exists.
    // The id is re-resolved against the LIVE list every composition below
    // (same shape as ModelEntryDetailScreen / EnvironmentVariablesScreen C16).
    var quickTestEntryId by remember { mutableStateOf<String?>(null) }
    val collapsedInstanceIds = rememberCollapsedInstanceIds(
        config = config,
        // T185: default-collapsed mirrors pre-refactor behaviour. The
        // shared picker auto-expands when search is non-empty so hits
        // remain visible regardless of section state.
        // [T-android-provider-voice] Voice scenario: DON'T collapse — a
        // multi-voice provider (Azure ~39, MiMo 9) would fold all-but-one
        // voice behind a disclosure (iOS d4e3198f seedCollapse fix).
        //
        // [deep-audit-v14 ISSUE-05] The state itself now lives in the shared
        // single source (ModelEntryPicker.rememberCollapsedInstanceIds), keyed
        // on the instance-id SET instead of on the whole config object: a
        // config re-emit that does not add/remove an instance (a Quick Test
        // persisting imageEndpointResolved, refreshModels landing, a DTO →
        // StateFlow emit) used to reset every open section.
        defaultExpanded = modalityFilter != null,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_group_detail_add_models), fontWeight = FontWeight.SemiBold) },
                actions = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                    MinisButton(
                        onClick = {
                            val updated = group.copy(
                                memberEntryIds = (group.memberEntryIds + selectedIds).toMutableList()
                            )
                            providerRepository.updateGroup(updated)
                            onBack()
                        },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.add_models_to_group_add_count, selectedIds.size))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // Resolved here (Composable context) — LazyListScope below can't call
        // stringResource. Localizes the injected System provider section label.
        val systemProviderLabel = stringResource(R.string.voice_provider_system)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            modelEntryPickerItems(
                instances = config.instances,
                availableEntries = availableEntries,
                selectedIds = selectedIds,
                onToggleSelection = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                searchQuery = searchQuery,
                collapsedInstanceIds = collapsedInstanceIds,
                emptyTextRes = R.string.add_models_to_group_all_in_group,
                emptySearchTextRes = R.string.add_models_to_group_no_match,
                searchPlaceholderRes = R.string.add_models_to_group_search_models,
                clearContentDescriptionRes = R.string.add_models_to_group_clear,
                // System virtual entries have no cloud endpoint to smoke-test.
                onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntryId = it.id },
                modalityFilter = modalityFilter,
                excludeIds = existingIds,
                systemProviderLabel = systemProviderLabel,
            )
        }
    }

    // [deep-audit-1039 B-08] Resolve the target from the LIVE entry list and
    // close the sheet through a LaunchedEffect (never during composition) when
    // the id stops resolving — a deleted model must dismiss the sheet instead of
    // leaving it testing a row that is gone.
    val quickTestEntry = liveQuickTestEntry(quickTestEntryId, config.modelEntries)
    LaunchedEffect(quickTestEntryId, config.modelEntries) {
        if (quickTestTargetVanished(quickTestEntryId, config.modelEntries)) quickTestEntryId = null
    }
    quickTestEntry?.let { entry ->
        com.rikkaminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntryId = null },
        )
    }
}

/**
 * [deep-audit-1039 B-08] Resolve a Quick Test target from the LIVE entry list.
 *
 * The picker rows hand out IDs; the sheet needs the current object. Resolving
 * on every composition is what keeps "Run again" on the model as it is now
 * rather than the copy the row held when the lightning bolt was tapped (the
 * same reason ModelEntryDetailScreen re-resolves its `entry` this way).
 *
 * Top-level pure function: the JVM test drives this decision directly instead
 * of re-implementing it (same seam as `sliderStepsFor` / `pendingConnectionFieldWrite`).
 */
internal fun liveQuickTestEntry(pendingId: String?, entries: List<ModelEntry>): ModelEntry? =
    pendingId?.let { id -> entries.find { it.id == id } }

/**
 * [deep-audit-1039 B-08] True when the sheet is open for an id that no longer
 * resolves. Kept separate from the resolution so the close effect keys on the
 * decision ("the target vanished") rather than on "the lookup returned null",
 * which is also true when the sheet was never opened.
 */
internal fun quickTestTargetVanished(pendingId: String?, entries: List<ModelEntry>): Boolean =
    pendingId != null && liveQuickTestEntry(pendingId, entries) == null
