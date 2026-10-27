package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.provider.thinking.ThinkingRule
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-thinking-rules-phase2 §3 / parity with iOS ThinkingRulesSection.swift]
 * Provider-detail "Thinking Rules" section.
 *
 * Behaviour, correct from the start (avoiding the two mistakes iOS shipped then fixed):
 *  1. Built-in rules are collapsed behind a single "Default rules (N)" summary row with a
 *     lock icon — never flat-listed. Tapping one duplicates it as an editable custom rule.
 *  2. Only rules RELEVANT to this provider are shown (relevance-filtered upstream by
 *     [ProviderRepository.builtInThinkingRulesForDisplay]).
 *  3. UI scoping is one-shot: Chat-Completions instances get the full editable list;
 *     Responses-API / Gemini / Anthropic get an explanatory notice (never a hidden
 *     section — silently dropping reads as "feature missing").
 *
 * Reorder is via up/down affordances rather than drag: this section lives inside the
 * provider screen's plain Column (not a LazyColumn), so a drag handle would fight the
 * outer scroll. Up/down is the robust equivalent and keeps sort_order = priority.
 */
@Composable
fun ThinkingRulesSection(
    instance: ProviderInstance,
    providerRepository: ProviderRepository,
    // [da1028-C] This instance's full model entry list (`entriesFor(instanceId)`,
    // NOT the visible-only subset). Both derived values below are computed from
    // the repo's own `config.modelEntries` — passing the list in is what lets the
    // `remember` keys observe a catalog refresh. Default keeps the old call sites
    // compiling unchanged.
    modelEntries: List<ModelEntry> = emptyList(),
) {
    // --- UI scoping: official-protocol providers get a notice, not a list ---
    if (!instance.supportsCustomThinkingRules) {
        SettingsSection(header = stringResource(R.string.thinking_rules_section)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.thinking_rules_official_protocol_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // reloadKey bumps to force a re-read after any mutation (rules live in Room).
    var reloadKey by remember { mutableStateOf(0) }
    var defaultsExpanded by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableStateOf<ThinkingRuleEditorRequest?>(null) }

    // [da1030-C ISSUE-05] reloadKey only covers mutations made ON this screen;
    // the custom-rule cache is also rewritten from outside it (backup restore,
    // repository load, worker restore, minis-config). Without a key that moves
    // on those writes this screen keeps the list it read when it opened and
    // writes it back on the next edit/delete — reverting the external write.
    // The resolver's revision moves on every cache mutation, so it is the
    // honest dependency for "re-read the rules"; keying on it (a Long, already
    // in memory) is cheaper and less brittle than replaying every writer.
    val rulesRevision by ThinkingRuleResolver.customRulesRevision.collectAsState()

    // [deep-audit-1043 B-02] The two Room-backed derivations used to be read
    // from `remember` initializers — i.e. inside the composition, on the main
    // thread. `thinkingRulesWithIds` is `= runBlocking { providerDao.load… }`,
    // so a screen that only shows rules still held the main thread across a
    // SQLite round trip on every recomposition whose keys moved — including
    // every edit made on this screen (each mutation bumps `reloadKey`). While
    // an unrelated writer (backup import, model catalog sync) holds the DB
    // write lock, that wait is the whole frame budget.
    //
    // The dependency set is unchanged — `(instance.id, reloadKey, rulesRevision)`
    // — only WHERE the read runs: `remember` holds the last result, and a
    // `LaunchedEffect` keyed on the same triple refills it from Dispatchers.IO.
    // Keeping the same three keys is what preserves the [da1030-C ISSUE-05]
    // contract: a rule written OUTSIDE this screen still re-reads.
    // Keyed on `instance.id` (NOT on reloadKey/revision): switching provider
    // must drop the previous provider's rows immediately, while a plain re-key
    // keeps the last result visible until the new one lands (no flicker, and no
    // rodearound where a click at t+1 frame writes a foreign rule id).
    var customRulesAndIds by remember(instance.id) {
        mutableStateOf(emptyList<ThinkingRule>() to emptyList<String>())
    }
    // [deep-audit-1043 B-02] Until the first (async) load lands, the columns are
    // legitimately empty — NOT "this provider has no rules". The dialog-close
    // effect below would otherwise read that empty first frame as "the edited
    // rule vanished" and dismiss an editor the user just opened; same guard
    // shape as SkillsManagementScreen's loaded flag.
    var rulesLoaded by remember(instance.id) { mutableStateOf(false) }
    LaunchedEffect(instance.id, reloadKey, rulesRevision) {
        customRulesAndIds = withContext(Dispatchers.IO) {
            providerRepository.thinkingRulesWithIds(instance.id)
        }
        rulesLoaded = true
    }
    val customRules = customRulesAndIds.first
    // The persisted ids parallel the custom rules in stored order (for edit/delete/reorder).
    val customIds = customRulesAndIds.second
    // [deep-audit-1043 B-02] Same move for the built-in preview: it calls
    // `ensureConfigLoaded()`, which on a cold cache synchronously loads the
    // config AND warms the rule cache. Keys untouched.
    var builtIns by remember(instance.id) { mutableStateOf(emptyList<ThinkingRule>()) }
    LaunchedEffect(instance.id, instance.customBaseURL, instance.appendV1Suffix, modelEntries, reloadKey) {
        builtIns = withContext(Dispatchers.IO) {
            providerRepository.builtInThinkingRulesForDisplay(instance.id)
        }
    }
    var sampleModelId by remember(instance.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, modelEntries, reloadKey) {
        sampleModelId = withContext(Dispatchers.IO) {
            providerRepository.firstModelId(instance.id)
        }
    }

    // [deep-audit-1043 B-02] Rule writes are `runBlocking { … }` DAO transactions
    // plus a full cache reload — they must not run on the main thread either.
    // The scope is bound to this composable, so a write in flight when the
    // screen leaves is cancelled with it.
    val rulesScope = rememberCoroutineScope()
    /** Run one rule mutation off the main thread, then re-key the loader. */
    fun mutateRules(block: suspend () -> Unit) {
        rulesScope.launch {
            withContext(Dispatchers.IO) { block() }
            reloadKey++
        }
    }

    SettingsSection(
        header = stringResource(R.string.thinking_rules_section),
        footer = stringResource(R.string.thinking_rules_footer),
    ) {
        // --- Custom rules (editable, reorderable) ---
        customRules.forEachIndexed { idx, rule ->
            val ruleId = customIds.getOrNull(idx)
            ThinkingRuleRow(
                title = rule.label,
                subtitle = ruleScopeSummary(rule) + " · " + wireFormatSummary(rule.wireFormat),
                locked = false,
                onClick = {
                    if (ruleId != null) {
                        editorRequest = ThinkingRuleEditorRequest(existingId = ruleId, seed = rule, isNew = false)
                    }
                },
                onMoveUp = if (idx > 0 && ruleId != null) {
                    {
                        val order = customIds.toMutableList()
                        order.add(idx - 1, order.removeAt(idx))
                        mutateRules { providerRepository.reorderThinkingRules(instance.id, order) }
                    }
                } else null,
                onMoveDown = if (idx < customRules.lastIndex && ruleId != null) {
                    {
                        val order = customIds.toMutableList()
                        order.add(idx + 1, order.removeAt(idx))
                        mutateRules { providerRepository.reorderThinkingRules(instance.id, order) }
                    }
                } else null,
                onDelete = if (ruleId != null) {
                    {
                        mutateRules { providerRepository.deleteThinkingRule(instance.id, ruleId) }
                    }
                } else null,
            )
        }

        // --- Default (built-in) rules, collapsed ---
        SettingsRow(
            title = stringResource(R.string.thinking_rules_default_rules_count, builtIns.size),
            icon = Icons.Default.Lock,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { defaultsExpanded = !defaultsExpanded },
            showChevron = false,
            trailing = {
                Icon(
                    if (defaultsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            showDivider = defaultsExpanded || customRules.isNotEmpty(),
        )
        if (defaultsExpanded) {
            builtIns.forEachIndexed { idx, rule ->
                ThinkingRuleRow(
                    title = rule.label,
                    subtitle = ruleScopeSummary(rule) + " · " + wireFormatSummary(rule.wireFormat),
                    locked = true,
                    // Tapping a built-in seeds a NEW custom rule (duplicate-to-override).
                    onClick = {
                        editorRequest = ThinkingRuleEditorRequest(
                            existingId = null,
                            seed = rule.copy(
                                kind = ThinkingRule.Kind.CUSTOM,
                                label = "Copy of ${rule.label}",
                            ),
                            isNew = true,
                        )
                    },
                    showDivider = idx < builtIns.lastIndex,
                )
            }
        }

        // --- Add rule ---
        SettingsRow(
            title = stringResource(R.string.thinking_rules_add_rule),
            icon = Icons.Default.Add,
            iconColor = MaterialTheme.colorScheme.primary,
            onClick = {
                editorRequest = ThinkingRuleEditorRequest(
                    existingId = null,
                    seed = ThinkingRule(
                        kind = ThinkingRule.Kind.CUSTOM,
                        scope = ThinkingRule.Scope.AllModels,
                        wireFormat = com.rikkaminis.app.provider.thinking.ThinkingWireFormat.ReasoningEffort(null),
                        label = "",
                    ),
                    isNew = true,
                )
            },
            showDivider = false,
        )
    }

    // --- Trace line: which rule the sample model would hit ---
    // [deep-audit-1043 B-02] `sampleModelId` is now a delegated `var`, and
    // `!= null` does not smart-cast one — read into a local val so the call
    // below type-checks.
    val sampleModelForPreview = sampleModelId
    if (sampleModelForPreview != null) {
        val hit = resolveHitDescription(customRules, builtIns, sampleModelForPreview)
        if (hit != null) {
            Text(
                text = hit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp),
            )
        }
    }

    // --- Editor dialog (hoisted here, not inside a row — Dialog has its own window) ---
    // [da1033-I07] The dialog must edit the rule as it is NOW, not the snapshot taken
    // when its row was tapped. `customRules` re-reads on `customRulesRevision` (see
    // C12 above), so a rule written OUTSIDE this screen — minis-config, Debug RPC,
    // backup restore, worker restore — is already in the list; but `editorRequest`
    // still carried the opening copy, and Save wrote that old copy back over the
    // external edit (or resurrected a rule the external writer had deleted).
    // Same shape as MCPIntegrationsScreen's `val liveServer = servers.find { … }`.
    // `customIds` and `customRules` are parallel columns, so both are read here.
    val editorLiveIndex = editorRequest?.existingId?.let { customIds.indexOf(it) } ?: -1
    // Deleted while the dialog was open, or its id vanished from the live column:
    // close instead of re-creating it on Save. State must not be written during
    // composition, so this runs as an effect.
    LaunchedEffect(editorRequest?.existingId, editorLiveIndex, rulesLoaded) {
        // [da1033-I07] For an id that is no longer in the live list there is nothing
        // left to edit — drop the request rather than saving the stale snapshot back.
        // [deep-audit-1043 B-02] `customIds` is empty until the first async load
        // lands, so an unguarded `index < 0` would close an editor the user just
        // opened. Only a LOADED column can say the rule is really gone.
        val req = editorRequest
        if (rulesLoaded && req?.existingId != null && editorLiveIndex < 0) editorRequest = null
    }
    editorRequest?.let { req ->
        // New rules (the "Add rule" and "Copy of …" entries, existingId == null)
        // seed from the request itself; only an edit re-reads the live rule.
        val liveSeed = if (req.existingId != null && !req.isNew && editorLiveIndex >= 0) {
            customRules[editorLiveIndex]
        } else {
            req.seed
        }
        ThinkingRuleEditorDialog(
            request = req.copy(seed = liveSeed),
            onDismiss = { editorRequest = null },
            onSave = { rule ->
                // Dismiss immediately (the dialog is UI state); the write and
                // the reload-key bump follow on the IO thread.
                editorRequest = null
                mutateRules { providerRepository.saveThinkingRule(instance.id, rule, id = req.existingId) }
            },
        )
    }
}

/** Compact rule row with a lock badge for built-ins and up/down/delete affordances for custom. */
@Composable
private fun ThinkingRuleRow(
    title: String,
    subtitle: String,
    locked: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.ifEmpty { "(unnamed rule)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onMoveUp != null) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.thinking_rules_move_up),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onMoveUp),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onMoveDown != null) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.thinking_rules_move_down),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onMoveDown),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onDelete != null) {
                Text(
                    text = stringResource(R.string.common_delete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = 4.dp),
                )
            }
        }
        if (showDivider) SettingsRowInsetDivider()
    }
}

@Composable
private fun SettingsRowInsetDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 14.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
