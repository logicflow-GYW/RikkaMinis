package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.components.MinisOutlinedButton
import com.rikkaminis.app.ui.components.SectionDesign
import com.rikkaminis.app.ui.components.SectionFooter
import com.rikkaminis.app.ui.components.SectionHeader
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * [model-groups-simplify] Standalone Agent Loop Models screen.
 *
 * This used to be an inline section pinned to the bottom of
 * ModelGroupsScreen (T182/T185/T186). It was extracted back into its own
 * screen so the model-group list page stays a compact single list; the
 * entry point is now a one-line row on ModelGroupsScreen that navigates
 * to Routes.AGENT_LOOP_MODELS.
 *
 * Behaviour is otherwise unchanged: the curated agent-loop set is shown as
 * pinned model entries followed by pinned groups, both drag-reorderable,
 * with remove buttons and "Add Models" / "Add Groups" actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLoopModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModelsTap: () -> Unit = {},
    onAddGroupsTap: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()

    // Parent LazyListState shared with rememberReorderableLazyListState so
    // each pinned row (its own LazyColumn item) can carry a
    // Modifier.draggableHandle and reorder live. The reorder callback
    // resolves the dragged row by its key, permutes a local copy, then
    // commits via ProviderRepository.
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // Two reorder zones share the same lazyListState — entries
        // (key prefix "agent_entry:") and groups ("agent_group:").
        // Refuse cross-zone drags so a user can't accidentally drop an
        // entry into the groups zone (the data shape would reject it
        // anyway, but a no-op is friendlier than a snap-back).
        when {
            fromKey.startsWith("agent_entry:") && toKey.startsWith("agent_entry:") -> {
                val fromId = fromKey.removePrefix("agent_entry:")
                val toId = toKey.removePrefix("agent_entry:")
                val cur = providerRepository.config.value.agentLoopModelEntryIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopEntries(newOrder)
            }
            fromKey.startsWith("agent_group:") && toKey.startsWith("agent_group:") -> {
                val fromId = fromKey.removePrefix("agent_group:")
                val toId = toKey.removePrefix("agent_group:")
                val cur = providerRepository.config.value.agentLoopGroupIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopGroups(newOrder)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_loop_section_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }

            agentLoopModelsSectionItems(
                providerRepository = providerRepository,
                config = config,
                reorderState = reorderState,
                onAddModelsTap = onAddModelsTap,
                onAddGroupsTap = onAddGroupsTap,
            )
        }
    }
}

/**
 * [model-groups-simplify] Extracted from ModelGroupsScreen into its own
 * standalone screen (Routes.AGENT_LOOP_MODELS).
 * Mirrors iOS `AgentLoopModelsSection` from
 * `src/ios/Views/Providers/AgentLoopModelsView.swift` L1-72. Renders
 * the user's curated agent-loop usable set: pinned groups (rendered
 * first, since picking a group implicitly pins all its members) then
 * pinned individual entries. Each row carries a × icon to remove the
 * pin; the section footer hosts two add-sheet trigger buttons.
 *
 * The picker layouts behind those triggers live in
 * `AgentLoopAddSheets.kt` and use `LazyColumn` so a user with a 369-
 * entry OpenRouter instance doesn't freeze Compose the way the pre-
 * T182 `AgentLoopModelsScreen` did when it materialised every entry
 * in a single `Column`.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.agentLoopModelsSectionItems(
    providerRepository: ProviderRepository,
    config: com.rikkaminis.app.data.model.ProviderConfig,
    reorderState: ReorderableLazyListState,
    onAddModelsTap: () -> Unit,
    onAddGroupsTap: () -> Unit,
) {
    // [T-android-agentloop-dup-key-crash] distinctBy id (belt-and-suspenders
    // alongside the data-layer sink dedup). A config that's ALREADY corrupted
    // with a duplicate id (persisted before the sink fix) would otherwise yield
    // two rows sharing the same LazyColumn/Reorderable key and crash on scroll
    // (IllegalArgumentException: Key "..." was already used). Deduping here
    // makes the screen render so the user can even reach a state where the
    // persisted list gets rewritten clean. totalRows / absIndex below derive
    // from these deduped lists, so the row math stays consistent.
    val pinnedGroups = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .distinctBy { it.id }
    val pinnedEntries = config.agentLoopModelEntryIds
        .mapNotNull { eid -> config.modelEntries.find { it.id == eid } }
        .distinctBy { it.id }
    val isEmpty = pinnedGroups.isEmpty() && pinnedEntries.isEmpty()

    // T313 — agent-loop section. Reorder rows MUST stay as top-level
    // LazyColumn items (ReorderableLazyListState only sees direct child
    // items), so the card visual is built per-row via SectionDesign
    // tokens: every row is wrapped with cardRow(isFirst, isLast) which
    // applies horizontal insets, surface fill, and rounded corners only
    // on the first/last row. SectionDivider is emitted as its own item
    // between rows so the inner card reads as one continuous panel.
    item("agent_loop_section_spacer") {
        Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
    }
    item("agent_loop_section_header") {
        SectionHeader(text = stringResource(R.string.agent_loop_section_title))
    }

    if (isEmpty) {
        item("agent_loop_section_empty") {
            Box(modifier = Modifier.cardRow(isFirst = true, isLast = true)) {
                Text(
                    text = stringResource(R.string.agent_loop_section_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    } else {
        val totalRows = pinnedGroups.size + pinnedEntries.size
        itemsIndexed(pinnedGroups, key = { _, g -> "agent_group:${g.id}" }) { index, group ->
            ReorderableItem(
                state = reorderState,
                key = "agent_group:${group.id}",
            ) { _ ->
                val resolvedCount = group.memberEntryIds.count { mid ->
                    config.modelEntries.any { it.id == mid }
                }
                val subtitle = if (resolvedCount == 1) {
                    stringResource(R.string.agent_loop_models_model_count_singular)
                } else {
                    stringResource(R.string.agent_loop_models_model_count_plural, resolvedCount)
                }
                Column {
                    if (index != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = index == 0,
                            isLast = index == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = group.name,
                            subtitle = subtitle,
                            badge = stringResource(R.string.agent_loop_section_group_badge),
                            onRemove = { providerRepository.removeAgentLoopGroup(group.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }

        itemsIndexed(pinnedEntries, key = { _, e -> "agent_entry:${e.id}" }) { index, entry ->
            ReorderableItem(
                state = reorderState,
                key = "agent_entry:${entry.id}",
            ) { _ ->
                val instanceLabel = config.instances
                    .find { it.id == entry.providerInstanceId }
                    ?.label
                val absIndex = pinnedGroups.size + index
                Column {
                    if (absIndex != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = absIndex == 0,
                            isLast = absIndex == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = entry.model.displayName,
                            subtitle = instanceLabel,
                            badge = null,
                            onRemove = { providerRepository.removeAgentLoopEntry(entry.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }
    }

    item("agent_loop_section_add_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisOutlinedButton(
                onClick = onAddModelsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_models))
            }
            MinisOutlinedButton(
                onClick = onAddGroupsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_groups))
            }
        }
    }

    item("agent_loop_section_footer") {
        SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
    }

    item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
}

/** Single row inside the agent-loop section. Drag handle on the left
 *  (T186), title + optional subtitle + optional "Group" badge in the
 *  middle, × to unpin on the right. The drag-handle modifier is built
 *  by the parent (since ReorderableItemScope.draggableHandle() is
 *  scope-bound) and threaded in here to keep the row composable
 *  scope-agnostic. */
@Composable
private fun AgentLoopRow(
    title: String,
    subtitle: String?,
    badge: String?,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // [reorder-unify] Shared with the Model Groups list; see
        // DragHandleButton in ReorderableCardRow.kt for why the handle has to
        // be an IconButton (T198) rather than a bare Icon.
        DragHandleButton(handleModifier = dragHandleModifier)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgeLabel(badge, MaterialTheme.colorScheme.primary)
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.agent_loop_section_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
