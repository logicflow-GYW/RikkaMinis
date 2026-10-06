package com.rikkaminis.app.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.model.DEFAULT_GROUP_CONTEXT_LIMIT_TOKENS
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.ui.components.SectionCard
import com.rikkaminis.app.ui.components.SectionDesign
import com.rikkaminis.app.ui.components.SectionFooter
import com.rikkaminis.app.ui.components.SectionHeader
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    /** [model-groups-simplify] Navigate to the standalone Agent Loop
     *  Models screen (Routes.AGENT_LOOP_MODELS). The inline
     *  AgentLoopModelsSection (T182/T185/T186) moved out of this page
     *  so the model-group list stays compact. */
    onAgentLoopClick: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    val groups = config.modelGroups
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    val context = androidx.compose.ui.platform.LocalContext.current

    // [feat/compact-model-pin-1005 → ui/runtime-page-adjust-1006] Pinned
    // compaction model ("" = follow the session chain). Moved here from the
    // Runtime Limits panel: it is a model-ROUTING role choice ("which model
    // serves the compaction role"), the same family as the agent-loop set on
    // this page — not a runtime budget knob. Unlike the runtime page's batched
    // Save it persists IMMEDIATELY (AgentRuntimeLimitsPrefs.save's null
    // params leave every other knob untouched), matching this page's
    // edit-then-live semantics.
    var compactModelEntryId by remember {
        mutableStateOf(AgentRuntimeLimitsPrefs.compactModelEntryId())
    }
    // Choices = every model entry in the provider config (provider label for
    // grouping, display name for the row); recomputed whenever the config
    // changes, so a model added/renamed in this session is reflected live.
    val compactModelChoices = remember(config) {
        config.modelEntries.mapNotNull { entry ->
            val inst = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            CompactModelChoice(
                entryId = entry.id,
                displayName = entry.model.displayName,
                providerLabel = inst.label.ifEmpty { entry.model.provider },
            )
        }
    }

    val lazyListState = rememberLazyListState()

    // [reorder-groups] Defend against an already-corrupted config holding two
    // groups with the same id: duplicate LazyColumn/Reorderable keys throw
    // ("Key ... was already used"), which would crash this screen on scroll and
    // leave the user no way in to fix the data. Same belt-and-suspenders as the
    // agent-loop section. Row index math below derives from this list, so
    // first/last card rounding stays consistent with what's rendered.
    //
    // Note the deliberate asymmetry with the reorder path: rendering tolerates
    // duplicates by hiding them, but ProviderRepository.permuteById REFUSES to
    // reorder a list holding duplicate ids (it can't be done unambiguously).
    // So on a corrupted config the screen still opens and stays usable — drags
    // just no-op until the duplicate is gone.
    val reorderableGroups = remember(groups) { groups.distinctBy { it.id } }

    // Drag-to-reorder the group list. Order here is presentation-only — which
    // group is the default primary/sub is tracked by explicit ids
    // (defaultPrimaryGroupId / defaultSubGroupId), NOT by list position — so a
    // reorder never changes routing behaviour. Contrast with the agent-loop
    // list, where order IS the priority order.
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // The list also contains non-group items (headers, the agent-loop entry
        // row, spacers). Only "group:"-keyed rows participate; anything else is
        // a no-op, which reads better than a snap-back.
        if (!fromKey.startsWith("group:") || !toKey.startsWith("group:")) {
            return@rememberReorderableLazyListState
        }
        val fromId = fromKey.removePrefix("group:")
        val toId = toKey.removePrefix("group:")
        val cur = providerRepository.config.value.modelGroups.map { it.id }
        val fromIdx = cur.indexOf(fromId)
        val toIdx = cur.indexOf(toId)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        providerRepository.reorderGroups(newOrder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_groups_model_groups)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.model_groups_new_group))
                    }
                },
            )
        },
    ) { padding ->
        // T313: page background uses SectionDesign.screenBackgroundColor()
        // (= surfaceContainerLow), so the section cards painted in `surface`
        // visibly stand out as iOS-style inset-grouped panels.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }

            if (groups.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.model_groups_no_model_groups),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.model_groups_groups_let_you_combine_models_for_fallba),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // [reorder-groups] Section 1 (Groups), drag-reorderable.
                //
                // Each group row MUST be its own top-level LazyColumn item:
                // ReorderableLazyListState only observes direct children of the
                // list, so the pre-existing "one item wrapping SectionCard"
                // shape can't reorder. The iOS-style card panel is instead
                // repainted per row via cardRow(isFirst, isLast) +
                // SectionDividerInsetCard() between rows — the same helpers the
                // agent-loop section uses (now shared in ReorderableCardRow.kt).
                item("groups_section_header") {
                    SectionHeader(text = stringResource(R.string.agent_loop_models_groups))
                }
                itemsIndexed(
                    items = reorderableGroups,
                    key = { _, g -> "group:${g.id}" },
                ) { index, group ->
                    ReorderableItem(state = reorderState, key = "group:${group.id}") { _ ->
                        // [remove-group-swipe-delete] Delete now lives only in the
                        // group detail screen (⋮ menu): one destructive action, one
                        // entry point. The row-level swipe was redundant with it and
                        // dangerous in a reorderable list — a horizontal fling while
                        // scrolling removed a group with no confirmation step.
                        Column {
                            if (index != 0) SectionDividerInsetCard()
                            Box(
                                modifier = Modifier.cardRow(
                                    isFirst = index == 0,
                                    isLast = index == reorderableGroups.lastIndex,
                                ),
                            ) {
                                GroupRow(
                                    group = group,
                                    config = config,
                                    onClick = { onGroupClick(group.id) },
                                    onSetPrimary = { providerRepository.defaultPrimaryGroupId = group.id },
                                    onClearPrimary = { providerRepository.defaultPrimaryGroupId = null },
                                    // Drag only from the explicit handle. The row
                                    // itself is clickable, so a whole-row drag
                                    // would fight the tap.
                                    dragHandleModifier = Modifier.then(
                                        with(this@ReorderableItem) { Modifier.draggableHandle() },
                                    ),
                                )
                            }
                        }
                    }
                }

                // [model-groups-simplify] The old "Defaults" section (two
                // full-width dropdowns for Default Primary / Default Sub,
                // plus the voice bindings) is gone. Setting a default is now
                // a per-row ⋮ menu action on each group row below.
            }

            // [model-groups-simplify] Agent Loop section collapsed into a
            // single entry row that navigates to the standalone
            // AgentLoopModelsScreen (Routes.AGENT_LOOP_MODELS). Rendered
            // whether or not groups exist.
            item("agent_loop_entry_spacer") {
                Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
            }
            item("agent_loop_entry_header") {
                SectionHeader(text = stringResource(R.string.agent_loop_section_entry_title))
            }
            item("agent_loop_entry_row") {
                SectionCard {
                    AgentLoopEntryRow(onClick = onAgentLoopClick)
                }
            }
            item("agent_loop_entry_footer") {
                SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
            }

            // [feat/compact-model-pin-1005 → ui/runtime-page-adjust-1006]
            // Pinned compaction model — moved from the Runtime Limits panel.
            // Selection persists immediately (single-key save at
            // AgentRuntimeLimitsPrefs), so no Save button or dirty tracking
            // here — same edit-then-live pattern as group edits on this page.
            item("compaction_model_entry_spacer") {
                Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
            }
            item("compaction_model_entry_row") {
                SectionCard {
                    CompactModelPickerRow(
                        title = stringResource(R.string.runtime_limits_compact_model),
                        subtitle = stringResource(R.string.runtime_limits_compact_model_desc),
                        pinnedEntryId = compactModelEntryId,
                        available = compactModelChoices,
                        onPick = {
                            compactModelEntryId = it
                            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = it)
                        },
                        onClear = {
                            compactModelEntryId = ""
                            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
                        },
                        showDivider = false,
                    )
                }
            }
            item("compaction_model_entry_footer") {
                SectionFooter(text = stringResource(R.string.model_groups_compaction_footer))
            }

            item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
        }
    }

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewGroupDialog = false
                newGroupName = ""
            },
            title = { Text(stringResource(R.string.model_groups_new_group)) },
            text = {
                Column {
                    Text(
                        "Enter a name for the new model group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // ime-ok: host is an AlertDialog (independent Window,
                    // decorFitsSystemWindows=true -> SOFT_INPUT_ADJUST_UNSPECIFIED),
                    // so the platform pans/resizes to keep this single-line field
                    // visible; the bare Scaffold's missing IME inset does not apply.
                    // Not user-reported; re-verify on device before changing.
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = sanitizeSingleLineInput(it) },
                        label = { Text(stringResource(R.string.model_groups_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            val newGroup = ModelGroup(
                                name = newGroupName.trim(),
                                // [T-context-new-group-default] Start user-created
                                // groups with a sane context limit (128K) instead of
                                // null/unlimited, so they get context-pressure
                                // signals like the default group does. Users can
                                // raise or clear it in the group detail screen.
                                contextLimitTokens = DEFAULT_GROUP_CONTEXT_LIMIT_TOKENS,
                            )
                            providerRepository.addGroup(newGroup)
                            // Auto-set as primary default if it's the first group.
                            // `config` here is the collectAsState snapshot captured
                            // BEFORE this click — addGroup already updated
                            // _config.value, so `config.modelGroups` still reflects
                            // the pre-add list. "First group" therefore means the
                            // pre-add list is EMPTY, not size == 1 (the old check
                            // off-by-one'd: it never promoted the true first group,
                            // and wrongly promoted the SECOND group when a prior
                            // primary had been cleared).
                            if (config.modelGroups.isEmpty() && config.defaultPrimaryGroupId == null) {
                                providerRepository.defaultPrimaryGroupId = newGroup.id
                            }
                            newGroupName = ""
                            showNewGroupDialog = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.model_groups_create))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    showNewGroupDialog = false
                    newGroupName = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentLoopEntryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Article,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.agent_loop_section_entry_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.agent_loop_section_entry_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}


/**
 * [T-android-modelgroup-modality-icons] One modality marker shown after a Model
 * Group title. `kind` is the modality string ("image"/"audio"/"video"/"pdf"/
 * "text") and `isOutput` distinguishes a generation output from an accepted
 * input. Ports iOS ModelGroupsView.GroupRow.topModalities /
 * modalityIcon (commits 7caa580a + e0a7cd5f).
 */
private data class GroupModalityMarker(val kind: String, val isOutput: Boolean)

/**
 * Distinctiveness ranking, most distinctive first — mirrors iOS
 * `modalityPriority`. `text` input is intentionally absent (universal noise);
 * `text` output ranks last (implied for every model, only surfaces when a group
 * has nothing more distinctive). Transcription (audio-in) outranks other inputs
 * so Whisper-style groups get flagged even though their output is plain text.
 */
private val GROUP_MODALITY_PRIORITY: List<GroupModalityMarker> = listOf(
    GroupModalityMarker("video", isOutput = true),
    GroupModalityMarker("image", isOutput = true),
    GroupModalityMarker("audio", isOutput = true),
    GroupModalityMarker("audio", isOutput = false),
    GroupModalityMarker("video", isOutput = false),
    GroupModalityMarker("image", isOutput = false),
    GroupModalityMarker("pdf", isOutput = false),
    GroupModalityMarker("text", isOutput = true),
)

/**
 * The group's top-2 most distinctive modalities across every member model's
 * inputs AND outputs, in priority order. Aggregates raw model modality lists
 * (Android convention — no capability inference); a null/empty outputModalities
 * counts as text output (iOS treats text-out as universal). Mirrors iOS
 * `GroupRow.topModalities`.
 */
private fun groupTopModalities(
    group: ModelGroup,
    config: com.rikkaminis.app.data.model.ProviderConfig,
): List<GroupModalityMarker> {
    val inputs = mutableSetOf<String>()
    val outputs = mutableSetOf<String>()
    for (entryId in group.memberEntryIds) {
        val model = config.modelEntries.find { it.id == entryId }?.model ?: continue
        model.inputModalities.orEmpty().forEach { inputs.add(it.lowercase()) }
        val out = model.outputModalities.orEmpty()
        if (out.isEmpty()) outputs.add("text") else out.forEach { outputs.add(it.lowercase()) }
    }
    return GROUP_MODALITY_PRIORITY.filter { marker ->
        if (marker.isOutput) marker.kind in outputs else marker.kind in inputs
    }.take(2)
}

/**
 * Icon + tint + a11y label for one [GroupModalityMarker], reusing the glyph
 * convention from ProviderDetailScreen.ModalityIconsRow: output/generation
 * modalities use the primary tint + "generate"-style glyph; input modalities
 * use the muted onSurfaceVariant tint. Renders nothing for unknown combos.
 */
@Composable
private fun GroupModalityIcon(marker: GroupModalityMarker) {
    val outputTint = MaterialTheme.colorScheme.primary
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Modifier.size(14.dp)
    val (vector, labelRes, tint) = when {
        marker.isOutput && marker.kind == "video" -> Triple(Icons.Default.MovieCreation, R.string.modeldetail_video_output, outputTint)
        marker.isOutput && marker.kind == "image" -> Triple(Icons.Default.AddPhotoAlternate, R.string.modeldetail_image_output, outputTint)
        marker.isOutput && marker.kind == "audio" -> Triple(Icons.Default.VolumeUp, R.string.modelgroup_speech_output, outputTint)
        marker.isOutput && marker.kind == "text" -> Triple(Icons.AutoMirrored.Filled.Article, R.string.modelgroup_text_generation, outputTint)
        marker.kind == "audio" -> Triple(Icons.Default.Mic, R.string.modelgroup_speech_transcription, inputTint)
        marker.kind == "video" -> Triple(Icons.Default.Videocam, R.string.modeldetail_video_input, inputTint)
        marker.kind == "image" -> Triple(Icons.Default.Image, R.string.modeldetail_image_input, inputTint)
        marker.kind == "pdf" -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, R.string.modeldetail_pdf_input, inputTint)
        else -> return
    }
    Icon(imageVector = vector, contentDescription = stringResource(labelRes), modifier = size, tint = tint)
}

/** A single group row inside the Groups section card. Built as a plain
 *  Composable (not a ListItem) so it inherits the card's surface color
 *  cleanly without ListItem's container override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupRow(
    group: ModelGroup,
    config: com.rikkaminis.app.data.model.ProviderConfig,
    onClick: () -> Unit,
    onSetPrimary: () -> Unit,
    onClearPrimary: () -> Unit,
    /** [reorder-groups] Built by the caller from
     *  `ReorderableItemScope.draggableHandle()` (scope-bound), so this row
     *  stays scope-agnostic. Defaults to a no-op modifier, which renders an
     *  inert handle — fine for previews/non-reorderable hosts. */
    dragHandleModifier: Modifier = Modifier,
) {
    val memberNames = group.memberEntryIds.mapNotNull { entryId ->
        config.modelEntries.find { it.id == entryId }?.model?.displayName
    }
    val preview = if (memberNames.size <= 3) {
        memberNames.joinToString(", ")
    } else {
        memberNames.take(3).joinToString(", ") + " +${memberNames.size - 3}"
    }
    val isPrimary = config.defaultPrimaryGroupId == group.id
    val strategyLabel = when (group.strategy) {
        RoutingStrategy.fallback -> stringResource(R.string.model_group_detail_fallback)
        RoutingStrategy.loadBalance -> stringResource(R.string.model_group_detail_load_balance)
        RoutingStrategy.cheapestFirst -> stringResource(R.string.model_group_detail_cheapest_first)
    }
    // [T-disabled-provider-via-group-android] Count members whose provider
    // instance is currently enabled — that's what the runtime resolver
    // will actually consider. When every member sits behind a disabled
    // provider, surface a warning so the user understands why the group
    // appears empty in chat.
    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val totalMembers = group.memberEntryIds.size
    val enabledMembers = group.memberEntryIds.count { entryId ->
        config.modelEntries.find { it.id == entryId }?.providerInstanceId in enabledInstanceIds
    }
    val allDisabled = totalMembers > 0 && enabledMembers == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // [reorder-groups] Left padding tightened from RowHorizontalPadding
            // to 8dp because the drag handle now occupies that gutter (its
            // 36dp IconButton carries its own optical inset). Trailing padding
            // is unchanged.
            .padding(
                start = 8.dp,
                end = SectionDesign.RowHorizontalPadding,
                top = SectionDesign.RowVerticalPadding,
                bottom = SectionDesign.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandleButton(handleModifier = dragHandleModifier)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-modelgroup-modality-icons] Mark the group's top-2
                // most distinctive modalities (inputs + outputs) right after the
                // title, in priority order, for at-a-glance "what is this group
                // for". Ports iOS ModelGroupsView (7caa580a + e0a7cd5f).
                groupTopModalities(group, config).forEach { marker ->
                    GroupModalityIcon(marker)
                }
                // [model-groups-redesign] Default-primary is now shown by the
                // star icon at the row's end; the old "Primary"/"Sub" text
                // badges are gone (Sub was a hidden title-generation model).
                if (allDisabled) {
                    BadgeLabel(
                        stringResource(R.string.model_group_no_usable_models_badge),
                        MaterialTheme.colorScheme.error,
                    )
                }
            }
            // [T-disabled-provider-via-group-android] Surface "M of N
            // disabled" when any member's provider is off so the user
            // doesn't have to drill into the detail page to learn that
            // the group isn't fully usable. Total count stays prominent
            // because it's still the source of truth for what's in the
            // group; the parenthetical reports disabled count.
            val disabledCount = totalMembers - enabledMembers
            val subtitleText = if (disabledCount > 0) {
                "$strategyLabel · $totalMembers models ($disabledCount disabled)"
            } else {
                "$strategyLabel · $totalMembers models"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (allDisabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        // [model-groups-redesign] Star toggles default-primary directly
        // (one tap, no ⋮ menu) — the "Set as Default Sub" concept was
        // removed entirely (it was a title-generation sub-model, invisible
        // in chat and misread as a fallback hop).
        IconButton(
            onClick = {
                if (isPrimary) onClearPrimary() else onSetPrimary()
            },
        ) {
            Icon(
                imageVector = if (isPrimary) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (isPrimary) R.string.model_groups_clear_default_primary
                    else R.string.model_groups_set_default_primary
                ),
                tint = if (isPrimary) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── [feat/compact-model-pin-1005] Compaction model picker ────────────────

/** One pickable model entry for the compaction-model dialog. */
internal data class CompactModelChoice(
    val entryId: String,
    val displayName: String,
    val providerLabel: String,
)

/**
 * Settings row + dialog for the pinned compaction model. Same visual
 * language as [LimitsSliderRow] (title/subtitle left, value right); the
 * whole row is clickable and opens the chooser (no separate button —
 * row-level click target matches the rest of the settings surfaces).
 * The pinned VALUE is persisted IMMEDIATELY by the callers ([ui/runtime-page-
 * adjust-1006]) via AgentRuntimeLimitsPrefs.save(compactModelEntryId=...) —
 * no batched Save on this page.
 *
 * A stale pin (entry deleted / provider removed) keeps its id and shows a
 * ⚠ label instead of auto-clearing — user intent is preserved; at chain
 * build the stale pin degrades to follow-the-session (INFO log).
 */
@Composable
private fun CompactModelPickerRow(
    title: String,
    subtitle: String,
    pinnedEntryId: String,
    available: List<CompactModelChoice>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    showDivider: Boolean = true,
) {
    var showDialog by remember { mutableStateOf(false) }
    val pinned = available.firstOrNull { it.entryId == pinnedEntryId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }
            Text(
                text = when {
                    pinnedEntryId.isBlank() ->
                        stringResource(R.string.runtime_limits_compact_model_follow)
                    pinned != null -> pinned.displayName
                    // Stale pin: keep showing it (intent preserved), mark it.
                    else -> "⚠ " + stringResource(R.string.runtime_limits_compact_model_stale)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            )
        }
    }
    if (showDialog) {
        CompactModelPickerDialog(
            pinnedEntryId = pinnedEntryId,
            available = available,
            onPick = {
                onPick(it)
                showDialog = false
            },
            onClear = {
                onClear()
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun CompactModelPickerDialog(
    pinnedEntryId: String,
    available: List<CompactModelChoice>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        available
    } else {
        available.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.providerLabel.contains(query, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_limits_compact_model_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    item(key = "follow") {
                        val selected = pinnedEntryId.isBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClear() }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.runtime_limits_compact_model_follow),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.runtime_limits_compact_model_follow_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.entryId }) { choice ->
                        val selected = choice.entryId == pinnedEntryId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(choice.entryId) }
                                .padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = choice.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = choice.providerLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
