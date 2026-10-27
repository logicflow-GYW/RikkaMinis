package com.rikkaminis.app.ui.chat

// [T-android-split-chat] ModelPickerSheet (+ its private helpers fuzzyMatch /
// providerDotColor) extracted verbatim from ChatScreen.kt. Imports pruned to
// the symbols this file uses (2026-10-27 dead-import sweep);
// ModelPickerSheet flipped private->internal so ChatScreen can call it.

import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import com.rikkaminis.app.R
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.ui.components.sanitizeSingleLineInput
import com.rikkaminis.app.ui.components.providerDotColor
import com.rikkaminis.app.ui.components.rememberCollapsedInstanceIds
import com.rikkaminis.app.ui.settings.SystemVoiceMemberPresentation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.theme.ChatColors
import com.rikkaminis.app.ui.components.MinisTextButton
import com.rikkaminis.app.provider.ModelsDevApi

// ── [S3-model-picker-rank] begin — pure ranking helpers ──────────────────
// Extracted verbatim by the JVM harness (scripts/tests), so everything
// between these two markers must stay Android-free and dependency-free
// (String/List only). Do not import anything here.

/**
 * How closely [text] matches [query]. Higher [tier] = stronger match;
 * [position] is where the match starts (earlier wins ties within a tier).
 *
 * Ladder (GH#272 — the picker used to be a pure boolean filter, so
 * `gpt-4o` and `my-custom-gpt-wrapper` ranked equal and the list order was
 * whatever the config happened to be in):
 *
 *   4 exact          query == text
 *   3 prefix         text starts with query
 *   2 word prefix    match starts right after a separator (- _ / . : space)
 *   1 substring      plain containment
 *   0 scattered      every query char appears in order, no contiguous run
 *
 * The two top rungs are deliberately separate from [TIER_WORD_PREFIX]:
 * "starts with the query" is a much stronger intent signal than "contains
 * the query as one word of a long hyphenated name".
 */
internal data class MatchRank(val tier: Int, val position: Int) : Comparable<MatchRank> {
    override fun compareTo(other: MatchRank): Int {
        if (tier != other.tier) return tier - other.tier
        // Same rung: the earlier the match starts, the more relevant.
        return other.position - position
    }
}

internal const val TIER_EXACT = 4
internal const val TIER_PREFIX = 3
internal const val TIER_WORD_PREFIX = 2
internal const val TIER_SUBSTRING = 1
internal const val TIER_SCATTERED = 0

/** Characters that make the following char the start of a word. */
internal const val WORD_SEPARATORS = "-_/.:"

/**
 * Score [text] against [query], or null when it does not match at all.
 *
 * A blank query returns null rather than "matches everything": both call
 * sites below branch on `searchText.isEmpty()` and take the unfiltered
 * path, so a blank query never reaches here — returning null makes a
 * future caller that forgets the branch fail loudly (empty list) instead
 * of silently ranking every row at tier 4.
 *
 * Case-insensitive. Matches iOS SessionModelPicker.fuzzyMatch on the
 * *accept/reject* decision (substring, else all query chars in order); the
 * tier ladder is this repo's own addition.
 */
internal fun matchScore(text: String, query: String): MatchRank? {
    if (query.isEmpty()) return null
    val t = text.lowercase()
    val q = query.lowercase()
    if (t == q) return MatchRank(TIER_EXACT, 0)
    val idx = t.indexOf(q)
    if (idx == 0) return MatchRank(TIER_PREFIX, 0)
    if (idx > 0) {
        val prev = t[idx - 1]
        val tier =
            if (prev.isWhitespace() || prev in WORD_SEPARATORS) TIER_WORD_PREFIX
            else TIER_SUBSTRING
        return MatchRank(tier, idx)
    }
    var cursor = 0
    for (ch in q) {
        val found = t.indexOf(ch, cursor)
        if (found < 0) return null
        cursor = found + 1
    }
    return MatchRank(TIER_SCATTERED, 0)
}

/**
 * Filter + rank [items] in one pass: keeps only matches, best first.
 *
 * [textOf] returns every string an item should be matched against (e.g.
 * display name *and* model id); an item takes its best rank across them.
 *
 * Stability matters here and is load-bearing: `sortedWith` is a stable
 * sort, so equal ranks keep their incoming order — which is how the
 * pinned / non-pinned split and the configured provider order survive a
 * search. Do not swap in an unstable sort.
 *
 * Cost: O(n log n) per list (one rank per item, then one sort), versus the
 * O(n) filter it replaces. Lists here are one provider's models (tens).
 */
internal fun <T> rankMatches(items: List<T>, query: String, textOf: (T) -> List<String>): List<T> =
    items
        .mapNotNull { item ->
            val rank = textOf(item).mapNotNull { matchScore(it, query) }.maxOrNull()
            rank?.let { item to it }
        }
        .sortedWith(compareByDescending { it.second })
        .map { it.first }

// ── [S3-model-picker-rank] end ───────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    /** [T-android-modelpicker-group-edit] "Edit" affordance on the Model Groups
     *  section header — dismisses the sheet and navigates to the Model Groups
     *  management screen. Null hides the button (callers without a route). */
    onEditGroups: (() -> Unit)? = null,
) {
    val openTime = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        AppLogger.info("ModelPicker", "[ModelPicker] open triggered")
        withFrameNanos { frameTime ->
            val ms = (frameTime - openTime) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] first frame rendered: ${"%.1f".format(ms)}ms (total since trigger)")
        }
    }

    var searchText by remember { mutableStateOf("") }
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    // Note: the non-text-output "may not work as an Agent" confirmation lives
    // in ChatScreen's callback wrappers (ee828dba), NOT here — the sheet stays
    // a dumb list and the caller owns selection policy.
    // [dv28/B-07] Single source. This sheet kept a private copy of the collapse
    // state and seeded it from `config.instances.filter { it.isEnabled }`, i.e.
    // a key derived from a MUTABLE per-instance display flag: flipping any
    // provider's enable switch changed the key, re-seeded the set and folded
    // every section the user had just arranged. `rememberCollapsedInstanceIds`
    // keys on the instance-ID set (identity, immune to an unrelated config
    // write) and is what the other two surfaces that render the same list use.
    // A disabled instance merely sits in the seed unused — `allInstancesWithEntries`
    // below filters those out, so the collapse set never gets read for them.
    var collapsedInstanceIds by rememberCollapsedInstanceIds(config)

    // Filtered groups — [S3-model-picker-rank] ranked, not just filtered.
    val filteredGroups = remember(groups, searchText) {
        if (searchText.isEmpty()) groups
        else {
            val t0 = System.nanoTime()
            val result = rankMatches(groups, searchText) { listOf(it.name) }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] filter groups: ${result.size}/${groups.size}, ${"%.1f".format(ms)}ms")
            result
        }
    }

    // Filtered entries by instance
    // [fix-audit-p3-3] Key on config.revision instead of the whole data-class
    // object: ProviderConfig.equals walks every inner list, so any unrelated
    // field change (e.g. a model-entry tweak) re-ran this whole filter and
    // re-logged every provider on every recomposition of the sheet. revision
    // is bumped on every publish (T273), so it is the cheapest reliable
    // change signal — and it recomputes exactly when config actually changes.
    val allInstancesWithEntries = remember(config.revision, searchText) {
        val t0 = System.nanoTime()
        var totalCount = 0
        val result = config.instances
            .filter { it.isEnabled }
            .map { instance ->
                val pt = System.nanoTime()
                val entries = config.modelEntries.filter {
                    it.providerInstanceId == instance.id && !it.isHidden
                }
                val filtered = if (searchText.isEmpty()) entries
                else rankMatches(entries, searchText) {
                    // Rank on display name and id, keeping the best of the
                    // two (a query hitting the id still beats a scattered
                    // hit on the display name).
                    listOf(it.model.displayName, it.model.id)
                }
                val pms = (System.nanoTime() - pt) / 1_000_000.0
                if (filtered.isNotEmpty()) {
                    totalCount += filtered.size
                    AppLogger.info("ModelPicker", "[ModelPicker] provider \"${instance.label.ifEmpty { instance.providerType.displayName }}\" models loaded: ${filtered.size} items, ${"%.1f".format(pms)}ms")
                }
                instance to filtered
            }
            .filter { it.second.isNotEmpty() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        AppLogger.info("ModelPicker", "[ModelPicker] all providers loaded: total $totalCount items, ${"%.1f".format(ms)}ms")
        result
    }

    // [P0-pinned-providers] Split providers into pinned (Favorites) and the rest,
    // so the picker can float pinned ones up right below the Model Groups card —
    // mirroring the pin ordering already used in Settings → Providers.
    val pinnedInstancesWithEntries = remember(allInstancesWithEntries) {
        allInstancesWithEntries.filter { it.first.pinned }
    }
    val otherInstancesWithEntries = remember(allInstancesWithEntries) {
        allInstancesWithEntries.filter { !it.first.pinned }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Match the slim drag handle used by StandardChatSheet (6dp top / 4dp
        // bottom) so the title sits flush with the indicator instead of the
        // Material default's ~44dp whitespace gap above it.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = ChatColors.secondaryText.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // ── Title bar (iOS: "Choose Model" + Done) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.model_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.model_picker_done))
                    }
                }
            }

            // ── Search bar (iOS: capsule rounded) ──
            // T250: cap minHeight at 48dp so the field doesn't read as
            // visually heavy against the section cards below it (Material3
            // default is 56dp; ~14% drop matches the spec target of ~15%).
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = sanitizeSingleLineInput(it) },
                placeholder = { Text(stringResource(R.string.model_picker_search_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp),
                singleLine = true,
                shape = RoundedCornerShape(50),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.model_picker_search_clear), modifier = Modifier.size(18.dp))
                        }
                    }
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                // ── Model Groups (grouped section card with embedded header) ──
                if (filteredGroups.isNotEmpty()) {
                    // Section card: header + rows live in the same surface so
                    // the visual unit is unambiguous. Header uses the
                    // titleSmall + onSurfaceVariant pair so it reads as a
                    // section label rather than another row.
                    item {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(14.dp),
                                ),
                        ) {
                            // [T-android-modelpicker-group-edit] Section header
                            // row: label on the left, an "Edit" button on the
                            // right that jumps to the Model Groups management
                            // screen (mirrors the iOS picker's group-section edit
                            // affordance). Button hidden when no route is wired.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.model_picker_groups_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                                )
                                if (onEditGroups != null) {
                                    // Text button (not a pencil icon) matching the
                                    // sheet's other text actions like "Done".
                                    MinisTextButton(
                                        onClick = onEditGroups,
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Text(stringResource(R.string.model_picker_groups_edit))
                                    }
                                }
                            }
                            filteredGroups.forEachIndexed { index, group ->
                                val isSelected = group.id == selectedGroupId
                                val isDefault = group.id == defaultPrimaryGroupId
                                val isExpanded = expandedGroupIds.contains(group.id)
                                val strategyLabel = when (group.strategy) {
                                    RoutingStrategy.fallback -> "FB"
                                    RoutingStrategy.loadBalance -> "LB"
                                    RoutingStrategy.cheapestFirst -> "CF"
                                }
                                // Resolve entry: when this group is selected, prefer the active entry
                                // so the header reflects the ACTUALLY-USED model (which may differ
                                // from the first member due to cheapestFirst selection, loadBalance
                                // rotation, or fallback recovery). Fall back to the first member
                                // only when the group is NOT selected (preview), or when the active
                                // entry cannot be resolved.
                                // [dv25/B-04] The decision (including the fallback above)
                                // lives in `pickerHeaderEntry` — see its KDoc for the two
                                // spellings this replaced on this very line: a persisted-only
                                // `modelEntries.find { it.id == activeEntryId }` (a System
                                // sentinel active entry resolved to null), and an
                                // `… } else null ?: group.memberEntryIds…` whose elvis bound to
                                // the else branch alone, so the SELECTED group — the branch that
                                // matters — had no fallback at all.
                                val resolvedEntry = pickerHeaderEntry(
                                    isSelected = isSelected,
                                    activeEntryId = activeEntryId,
                                    memberEntryIds = group.memberEntryIds,
                                ) { entryId ->
                                    // [dv24/B-02] persisted entry first, synthesized
                                    // System voice entry second — one source
                                    // (SystemVoiceEntries.resolveIn). The System
                                    // sentinels are never persisted, so a lookup
                                    // against `config.modelEntries` alone resolved
                                    // a default Voice Input/Output group to nothing.
                                    SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
                                }
                                // Count of resolved members for display
                                val resolvedCount = group.memberEntryIds.count { entryId ->
                                    // [dv24/B-02] same single source — a System voice
                                    // member IS a usable member of the group
                                    SystemVoiceEntries.resolveIn(config.modelEntries, entryId) != null
                                }

                                // Header sits above the first row, so the
                                // first-row corners are no longer rounded
                                // (only the bottom row of the last group is).
                                val groupShape = when {
                                    index == filteredGroups.size - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(groupShape)
                                        .clickable { onSelectGroup(group.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) ChatColors.success
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                group.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            // iOS: ⊕ FB / ⊕ LB badge with icon
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            ) {
                                                Icon(
                                                    if (group.strategy == RoutingStrategy.fallback)
                                                        Icons.Default.ArrowCircleDown
                                                    else Icons.Default.AccountTree,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(9.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                                Spacer(Modifier.width(2.dp))
                                                Text(
                                                    strategyLabel,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                )
                                            }
                                        }
                                        // iOS: "→ ModelName" resolved entry
                                        if (resolvedEntry != null) {
                                            Text(
                                                "→ ${resolvedEntry.model.displayName}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        } else if (resolvedCount > 0) {
                                            Text(
                                                pluralStringResource(R.plurals.model_picker_models_count, resolvedCount, resolvedCount),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                        } else {
                                            Text(
                                                stringResource(R.string.model_picker_models_count_unlinked, group.memberEntryIds.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }

                                    // iOS parity: "Default" badge — filled capsule
                                    if (isDefault) {
                                        Text(
                                            stringResource(R.string.model_picker_default_badge),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ChatColors.link,
                                            modifier = Modifier
                                                .background(
                                                    ChatColors.link.copy(alpha = 0.1f),
                                                    RoundedCornerShape(50),
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }

                                    // iOS: expand/collapse chevron button.
                                    // T295: bumped from surfaceContainerHigh
                                    // (which on light theme = #F7F7FA, almost
                                    // identical to the white card it sits on)
                                    // to secondaryContainer + onSecondaryContainer
                                    // tint, so the chevron actually reads as a
                                    // tonal button instead of a flat ghost
                                    // icon. Same shape + size as before.
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                CircleShape,
                                            )
                                            .clip(CircleShape)
                                            .clickable {
                                                expandedGroupIds = if (isExpanded) {
                                                    expandedGroupIds - group.id
                                                } else {
                                                    expandedGroupIds + group.id
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }

                                // Expanded group member entries
                                if (isExpanded) {
                                    // Resolve actual entries from memberEntryIds
                                    val resolvedMembers = group.memberEntryIds.mapNotNull { entryId ->
                                        // [dv24/B-02] single source (see above)
                                        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
                                    }
                                    // Fallback: show active entry ONLY if this group is selected
                                    // [dv25/B-04] the fallback lookup is the same single source
                                    // as `resolvedMembers` above — the pre-fix `modelEntries`-only
                                    // spelling dropped a System sentinel active entry and the rows
                                    // rendered 「No linked models」.
                                    val displayMembers = pickerDisplayMembers(
                                        resolvedMembers = resolvedMembers,
                                        isSelected = isSelected,
                                        activeEntryId = activeEntryId,
                                    ) { entryId ->
                                        SystemVoiceEntries.resolveIn(config.modelEntries, entryId)
                                    }
                                    if (displayMembers.isEmpty()) {
                                        Text(
                                            stringResource(R.string.model_picker_no_linked_models),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
                                        )
                                    }
                                    displayMembers.forEach { entry ->
                                            val isActive = activeEntryId == entry.id
                                            val instance = config.instances.find { it.id == entry.providerInstanceId }
                                            // [dv28/B-07] Single source. A System voice
                                            // member has NO provider instance (the device is
                                            // its provider), so the old `if (instance != null)`
                                            // branch dropped the provider name entirely and
                                            // every row of a default Voice group showed a bare
                                            // model id — while the group detail screen, which
                                            // asks the same question through
                                            // SystemVoiceMemberPresentation, showed
                                            // 「System · <model>」. The DECISION (what the
                                            // secondary line says, and when it is empty) lives
                                            // in the shared presentation helper; only the
                                            // rendering stays here.
                                            val memberSubtitle = SystemVoiceMemberPresentation.memberSubtitle(
                                                instanceLabel = instance?.label?.takeUnless { it.isEmpty() }
                                                    ?: instance?.providerType?.displayName,
                                                isSystemEntry = SystemVoiceEntries.isSystemEntryId(entry.id),
                                                systemLabel = stringResource(R.string.voice_provider_system),
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onSelectGroupEntry(group.id, entry.id) }
                                                    .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    if (isActive) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (isActive) ChatColors.link
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                    modifier = Modifier.size(17.dp),
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(providerDotColor(instance?.providerType), CircleShape),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
                                                    Row {
                                                        if (memberSubtitle.isNotEmpty()) {
                                                            Text(
                                                                memberSubtitle,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                fontWeight = FontWeight.Medium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                            )
                                                            Text(
                                                                " · ",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                            )
                                                        }
                                                        Text(
                                                            entry.model.id,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        )
                                                    }
                                                }
                                                if (isActive) {
                                                    Text(
                                                        stringResource(R.string.model_picker_active_badge),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = ChatColors.success,
                                                        modifier = Modifier
                                                            .background(
                                                                ChatColors.success.copy(alpha = 0.1f),
                                                                RoundedCornerShape(8.dp),
                                                            )
                                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                    }
                                }

                                // Inset hairline between groups, matches MinisMenuDivider rhythm.
                                if (index < filteredGroups.size - 1) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    )
                                }
                            }
                        }
                    }

                    if (searchText.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.model_picker_groups_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                // T236: hint sits between Model Groups card and
                                // first Provider card — top 8 hugs the group
                                // card, bottom 12 separates from the next card.
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                // ── Individual Models by Provider (one section card per provider) ──
                // Each provider becomes a single grouped card containing: an
                // embedded header row with the collapse chevron, then either
                // the collapsed summary row or the expanded entry list. Cards
                // are visually separated from each other by a 12dp gap, and
                // sit on a higher tonal surface so the boundary between
                // providers is unmistakable even on the dark sheet background.
                if (allInstancesWithEntries.isNotEmpty()) {
                    // [P0-pinned-providers] Favorite (pinned) providers float to a
                    // dedicated "Favorites" section immediately under the Model
                    // Groups card and above all remaining providers. The label
                    // reuses the existing Settings translation (Favorites / 常用).
                    if (pinnedInstancesWithEntries.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.provider_list_favorites),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 2.dp),
                            )
                        }
                    }
                    // Pinned providers first, then everything else — each group
                    // keeps its original relative order.
                    // [deep-audit-1041 B-04] Force every provider section open
                    // while a search is running: the collapse set seeds to "all
                    // collapsed", so a section that matches the query rendered
                    // its single collapsed summary row and hid the rest of the
                    // hits behind a chevron the user had to find and tap. The
                    // gate is derived at the read site (never written back into
                    // `collapsedInstanceIds`), so clearing the query restores the
                    // user's own expand/collapse choices untouched — same
                    // decision as the shared ModelEntryPicker, which the
                    // model-group and agent-loop pickers already route through.
                    val orderedProviderSections = pinnedInstancesWithEntries + otherInstancesWithEntries
                    orderedProviderSections.forEachIndexed { sectionIndex, (instance, entries) ->
                        val isCollapsed = searchText.isEmpty() && collapsedInstanceIds.contains(instance.id)
                        // When both Favorites and the rest are present, mark the
                        // boundary with an "All Providers" section label.
                        if (sectionIndex == pinnedInstancesWithEntries.size &&
                            pinnedInstancesWithEntries.isNotEmpty() &&
                            otherInstancesWithEntries.isNotEmpty()
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.model_picker_all_providers),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = "section_${instance.id}") {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(14.dp),
                                    ),
                            ) {
                                // Header row, embedded in the card.
                                Row(
                                    // T236: tightened provider header padding
                                    // (top=10/bottom=8) so the gap to the first
                                    // model row reads as ~8dp rather than the
                                    // earlier 12dp+row-padding stack.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        instance.label.ifEmpty { instance.providerType.displayName },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // T295: same tonal upgrade as the model-
                                    // group chevron above — surfaceContainer-
                                    // Highest read as flat against the card
                                    // surface. Use secondaryContainer for a
                                    // visible pill shape.
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                CircleShape,
                                            )
                                            .clip(CircleShape)
                                            .clickable {
                                                collapsedInstanceIds = if (isCollapsed) {
                                                    collapsedInstanceIds - instance.id
                                                } else {
                                                    collapsedInstanceIds + instance.id
                                                }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (isCollapsed) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        )
                                    }
                                }

                                if (isCollapsed) {
                                    // Collapsed summary — show selected or first entry + model count.
                                    val selectedEntry = entries.firstOrNull { it.id == activeEntryId && selectedGroupId == null }
                                    val displayEntry = selectedEntry ?: entries.firstOrNull()
                                    if (displayEntry != null) {
                                        val dotColor = providerDotColor(instance.providerType)
                                        Row(
                                            // T236: collapsed summary row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // so tap target stays comfortable.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                                .clickable { onSelectEntry(displayEntry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (selectedEntry != null) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (selectedEntry != null) ChatColors.link
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    displayEntry.model.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                // [T-android-provider-voice] Modality chips
                                                // (iOS entryRow badges) — without them a
                                                // voice seed serving as the collapsed
                                                // representative is indistinguishable from
                                                // a chat model.
                                                com.rikkaminis.app.ui.components.modalityBadges(ModelsDevApi.effectiveModel(displayEntry)).forEach { badge ->
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        badge,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier
                                                            .background(
                                                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                                                RoundedCornerShape(3.dp),
                                                            )
                                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                            Text(
                                                pluralStringResource(R.plurals.model_picker_models_count, entries.size, entries.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                } else {
                                    entries.forEachIndexed { index, entry ->
                                        val isSelected = activeEntryId == entry.id && selectedGroupId == null
                                        val dotColor = providerDotColor(instance.providerType)
                                        // Last row clips its own bottom so the
                                        // ripple respects the card corners.
                                        val rowShape = if (index == entries.size - 1) {
                                            RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                                        } else {
                                            RoundedCornerShape(0.dp)
                                        }
                                        Row(
                                            // T236: expanded entry row —
                                            // vertical 14→8 + heightIn(min=48dp)
                                            // for accessible tap target.
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(rowShape)
                                                .clickable { onSelectEntry(entry.id) }
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) ChatColors.link
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .background(dotColor, CircleShape),
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    entry.model.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        entry.model.id,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                    )
                                                    // [T-android-provider-voice] Modality
                                                    // chips (iOS entryRow badges).
                                                    com.rikkaminis.app.ui.components.modalityBadges(ModelsDevApi.effectiveModel(entry)).forEach { badge ->
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            badge,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier
                                                                .background(
                                                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                                                    RoundedCornerShape(3.dp),
                                                                )
                                                                .padding(horizontal = 4.dp, vertical = 1.dp),
                                                        )
                                                    }
                                                }
                                            }
                                            if (selectedGroupId != null && activeEntryId == entry.id) {
                                                Text(
                                                    stringResource(R.string.model_picker_active_badge),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.success,
                                                    modifier = Modifier
                                                        .background(
                                                            ChatColors.success.copy(alpha = 0.1f),
                                                            RoundedCornerShape(8.dp),
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        // Inset hairline between entries.
                                        if (index < entries.size - 1) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Empty / No Results ──
                if (filteredGroups.isEmpty() && allInstancesWithEntries.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                if (searchText.isNotEmpty()) Icons.Default.Search else Icons.Default.Memory,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(if (searchText.isNotEmpty()) R.string.model_picker_no_results else R.string.model_picker_no_models),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchText.isEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_configure_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.model_picker_try_different_search),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

// providerDotColor is shared from com.rikkaminis.app.ui.components.ModelEntryPicker (single source).
