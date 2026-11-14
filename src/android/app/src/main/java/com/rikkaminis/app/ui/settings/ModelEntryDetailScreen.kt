package com.rikkaminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.model.inferContextWindowTokens
import com.rikkaminis.app.data.model.normalizeModalityName
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.ui.chat.localizedName
import com.rikkaminis.app.ui.components.RowLabel
import com.rikkaminis.app.ui.components.SectionDropdown
import com.rikkaminis.app.ui.components.SectionTextField
import com.rikkaminis.app.R
import com.rikkaminis.app.ui.components.MinisButton
import com.rikkaminis.app.ui.components.MinisTextButton

// [selection-only-ui-writer-1112] Log tag for the selection-set mutations
// this screen performs (remove from selection).
private const val TAG = "ModelEntryDetail"

/**
 * Detail / edit screen for a single ModelEntry. T210: brought to iOS
 * parity with five inset-grouped sections — Identity, Capabilities,
 * Selection, Input Modality, Output Modality (+ optional Reset). The
 * data layer (`ModelOverrides.contextWindow`, `supportsReasoning`,
 * `inputModalities`, `outputModalities`) was already in place; pre-T210
 * the UI only exposed displayName / maxOutputTokens / hidden, which
 * meant Android users had no way to override the things iOS lets them
 * tweak (context window, thinking flag, per-modality enablement).
 *
 * [selection-only-ui-writer-1112] The section that used to be "Visibility"
 * is now "Selection": since the selection-only migration the entry table
 * holds SELECTIONS only, so there is no separate visible/hidden state left
 * to toggle. The section offers the one membership operation that remains
 * — remove the model from the selection set — through `unselectEntry`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
    val instance = config.instances.find { it.id == instanceId }
    val context = LocalContext.current

    // [selection-only-ui-writer-1112] Set when the user removed this entry
    // from the selection set. The write makes the row disappear, and the
    // `entry == null` branch below would fire its own onBack() — two pops
    // for one tap. Mirrors ProviderDetailScreen's `deleted` flag.
    //
    // Declared BEFORE the null check: Kotlin resolves local declarations in
    // source order, so a `remember` declared after the early-return branch
    // is not in scope inside it (CI #43215338745: `Unresolved reference
    // 'removedFromSelection'`). Keyed on entryId (not entry.id) because
    // `entry` is null in that branch.
    var removedFromSelection by remember(entryId) { mutableStateOf(false) }

    if (entry == null) {
        // [S6-composition-back] onBack() during composition is a Compose
        // anti-pattern (re-fires on every recomposition of this branch) —
        // wrap in LaunchedEffect like ModelGroupDetailScreen/MountDetailScreen.
        // [selection-only-ui-writer-1112] The removal path already popped the
        // screen itself; popping again here would double-pop.
        if (!removedFromSelection) {
            LaunchedEffect(Unit) { onBack() }
        }
        return
    }

    val baseModel = entry.baseModel
    val overrides = entry.overrides

    // [meta-audit-1028 E5 / B06 F-07] `updateEntry` answers "did the write
    // land?" with String? — null means it REFUSED (a rename collided with
    // another entry of the same instance, or the id is blank) and nothing was
    // persisted. The old code called it and then `onBack()` unconditionally,
    // so a refused rename looked exactly like a saved one: the screen popped
    // and the user's edit was gone with no signal at all. Stay put and say so.
    var saveError by remember(entry.id) { mutableStateOf<String?>(null) }

    // [da1026-C] Every editable field below is keyed on (entry identity, the
    // exact sources its initial value derives from). A keyless `remember`
    // froze the FIRST composition's values for the lifetime of the screen: an
    // external write while this page was open (minis-config / backup restore /
    // provider RPC) left the old snapshot rendered, and Save then wrote those
    // stale values back over the external change. Keys stay field-level
    // (never the whole `entry` object) so an unrelated external edit cannot
    // discard what the user is currently typing.
    var modelId by remember(entry.id, baseModel.id) { mutableStateOf(baseModel.id) }
    var displayName by remember(entry.id, baseModel.displayName, overrides.displayName) {
        mutableStateOf(overrides.displayName ?: baseModel.displayName)
    }
    var maxOutputTokensText by remember(entry.id, overrides.maxOutputTokens) {
        mutableStateOf(overrides.maxOutputTokens?.toString() ?: "")
    }
    var contextWindowText by remember(
        entry.id,
        baseModel.contextWindow,
        baseModel.id,
        overrides.contextWindow,
    ) {
        mutableStateOf(
            (overrides.contextWindow ?: baseModel.contextWindow?.takeIf { it > 0 })
                ?.toString()
                // [T-context-window-sources] No real value anywhere (no user
                // override, no metadata from models.dev / catalog) → the
                // runtime will fall back to the id-heuristic guess. Pre-fill
                // that guess so the user SEES it (a 1M model silently landing
                // on 128K wastes paid context) and can correct it before it
                // ever caps offload/compaction judgment.
                ?: inferContextWindowTokens(baseModel).toString()
        )
    }
    var thinkingEnabled by remember(entry.id, baseModel.supportsReasoning, overrides.supportsReasoning) {
        mutableStateOf(overrides.supportsReasoning ?: baseModel.supportsReasoning ?: false)
    }
    var showQuickTest by remember(entry.id) { mutableStateOf(false) }

    // Modality state — derive from override-or-base so the toggles reflect
    // what the model actually supports today, then let the user flip from
    // there. A null override means "inherit baseModel" (data-layer
    // contract); on save we only persist a non-null list when it diverges
    // from the inherited set.
    // Defensive normalization: pre-T213 SharedPreferences may hold suffixed strings
    // (`image_input`) saved before the parse-boundary fix, which would silently fail
    // the bare-name `"image" in effectiveInput` checks below.
    // [dv22/B-03] Capability base = models.dev enrichment under the RAW base,
    // WITHOUT the user's overrides (the derivation below applies those first, and
    // so does the save-time diff). Only the modality fields are taken from the
    // enriched model: displayName / supportsReasoning / contextWindow must keep
    // comparing against the base the entry actually inherits, otherwise a no-op
    // save would materialize a registry value as a user override.
    val capabilityBase = enrichCapabilityBase(baseModel)
    val effectiveInput = (overrides.inputModalities ?: capabilityBase.inputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    val effectiveOutput = (overrides.outputModalities ?: capabilityBase.outputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    var imageInput by remember(entry.id, effectiveInput) { mutableStateOf("image" in effectiveInput) }
    var pdfInput by remember(entry.id, effectiveInput) { mutableStateOf("pdf" in effectiveInput) }
    var audioInput by remember(entry.id, effectiveInput) { mutableStateOf("audio" in effectiveInput) }
    var videoInput by remember(entry.id, effectiveInput) { mutableStateOf("video" in effectiveInput) }
    var imageOutput by remember(entry.id, effectiveOutput) { mutableStateOf("image" in effectiveOutput) }
    var audioOutput by remember(entry.id, effectiveOutput) { mutableStateOf("audio" in effectiveOutput) }
    // [RC-1114 U-18] Video output used to have no switch: the screen carried
    // the dimension through untouched, so a model whose base did not declare
    // "video" could never be marked as producing video even though the list
    // badges and the group sheet both render that capability. Seed from the
    // same normalized effective set the other output switches use.
    var videoOutput by remember(entry.id, effectiveOutput) { mutableStateOf("video" in effectiveOutput) }
    // [RC-1114 U-18] Ceiling for this model's thinking intensity. Null means
    // "inherit the catalog default" — the same contract as every other
    // ModelOverrides field, so an untouched picker must persist null rather
    // than freezing today's resolved level.
    var maxThinkingLevel by remember(entry.id, overrides.maxThinkingLevel) {
        mutableStateOf(overrides.maxThinkingLevel)
    }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        // [T-android-modeldetail-savecancel-ios-parity] iOS modal pattern
        // (ProviderInstanceDetailView toolbar): Cancel is a plain leading
        // text action, the title is centered, and Save is the single
        // emphasized trailing action — the filled pill is the MD3 analog of
        // iOS's semibold Save. No back arrow: Cancel and system back both
        // discard + pop.
        onBack = null,
        centerTitle = true,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    // [da1023-B] Derivation lives in ModelEntryOverrideBuilder.kt
                    // (pure, JVM-tested). The inline version had four defects:
                    // switches alone rebuilt the lists so "text" was dropped
                    // (and output "video"), the diff comparison ran against the
                    // RAW base list while the switches were normalized (suffixed
                    // bases forced a write on a no-op save), and the positional
                    // `ModelOverrides(...)` constructor lost maxThinkingLevel —
                    // `previous.copy(...)` keeps every field this screen does
                    // not own.
                    val newOverrides = buildModelEntryOverrides(
                        // Same capability base the switches were seeded from, so
                        // "user touched nothing" stays a no-op instead of writing
                        // a modality override that freezes today's registry value.
                        baseModel = capabilityBase,
                        previous = overrides,
                        displayName = displayName,
                        maxOutputTokensText = maxOutputTokensText,
                        contextWindowText = contextWindowText,
                        thinkingEnabled = thinkingEnabled,
                        imageInput = imageInput,
                        pdfInput = pdfInput,
                        audioInput = audioInput,
                        videoInput = videoInput,
                        imageOutput = imageOutput,
                        audioOutput = audioOutput,
                        // [RC-1114 U-18] Both dimensions the data layer already
                        // carried but the screen could not edit.
                        videoOutput = videoOutput,
                        maxThinkingLevel = maxThinkingLevel,
                    )
                    // [selection-only-ui-writer-1112] The save path must NOT
                    // carry `isHidden` at all. Since the selection-only
                    // migration the entry table holds SELECTIONS only — a row
                    // with `isHidden = true` is a legacy catalog copy that the
                    // migration deletes and that the backup importer drops
                    // (`decideLegacyHiddenImportKeep` = `!isHidden || isCustom`).
                    // Persisting `copy(isHidden = true)` from this screen
                    // therefore minted a row that (a) disappears from every
                    // `!isHidden` reader (model picker, onboarding, group
                    // sheet, agent-loop sheet) while (b) still showing as
                    // selected in the Manage sheet (which reads the full set),
                    // and (c) was silently dropped on the next backup
                    // restore — the user's selected model lost.
                    // "Unselect" is the only remaining operation and it goes
                    // through `unselectEntry` (the same path the config
                    // setter and the management sheet use), never through a
                    // hidden flag.
                    val updated = if (entry.isCustom) {
                        // [da1022-E] trim before persisting; the composite
                        // entry key is minted from this id, so a whitespace
                        // edge would mint a dangling key. Blank input is
                        // blocked by the Save button's enabled gate below.
                        entry.copy(baseModel = baseModel.copy(id = modelId.trim()), overrides = newOverrides)
                    } else {
                        entry.copy(overrides = newOverrides)
                    }
                    val persistedId = providerRepository.updateEntry(updated)
                    // [meta-audit-1028 E5 / B06 F-07] `null` covers two
                    // different refusals; they need different responses.
                    val stillThere = providerRepository.config.value.modelEntries
                        .any { it.id == entry.id && it.providerInstanceId == instanceId }
                    when (classifyUpdateResult(persistedId, stillThere)) {
                        EntrySaveOutcome.SAVED -> onBack()
                        EntrySaveOutcome.ID_TAKEN -> {
                            // Keep the editor open with the in-flight edits
                            // intact and name the reason — popping here is
                            // exactly what made the loss silent.
                            saveError = context.getString(
                                R.string.modeldetail_error_model_id_exists,
                                modelId.trim(),
                            )
                        }
                        EntrySaveOutcome.ENTRY_GONE -> onBack()
                    }
                },
                // [da1022-E] fail-closed: block saving a blank/whitespace
                // model id (same gate as AddCustomModelScreen's
                // enabled = modelId.isNotBlank()).
                enabled = !entry.isCustom || modelId.isNotBlank(),
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        // ── Identity ────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = if (entry.isCustom) stringResource(R.string.modeldetail_identity_footer_custom)
                     else stringResource(R.string.modeldetail_identity_footer_builtin),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    // Typing is the user acting on the refusal, so clear it
                    // here; the next Save re-evaluates against fresh state.
                    onValueChange = {
                        if (entry.isCustom) {
                            modelId = it
                            saveError = null
                        }
                    },
                    singleLine = true,
                    readOnly = !entry.isCustom,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_display_name))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.model_entry_provider),
                value = instance?.label ?: instanceId,
                showDivider = false,
            )
            // [meta-audit-1028 E5 / B06 F-07] Refusal is surfaced in the
            // section that owns the field which caused it (the Model ID), not
            // as a toast: the user must still be looking at what to change.
            // (Local capture: a `by`-delegated property cannot smart-cast.)
            saveError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }

        // ── Capabilities ────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
            footer = stringResource(R.string.modeldetail_capabilities_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_context_window))
                SectionTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.contextWindow?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
                SectionTextField(
                    value = maxOutputTokensText,
                    onValueChange = { maxOutputTokensText = it.filter { c -> c.isDigit() } },
                    placeholder = baseModel.maxOutputTokens?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Inline warning when the value exceeds the provider-reported
                // limit. The save still goes through so power users can probe
                // a higher cap; we just flag it.
                val enteredTokens = maxOutputTokensText.trim().toIntOrNull()
                val baseLimit = baseModel.maxOutputTokens
                if (enteredTokens != null && baseLimit != null && enteredTokens > baseLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it },
            )
            // [RC-1114 U-18] Ceiling picker. `ModelOverrides.maxThinkingLevel`
            // has been the top-priority source in the resolution chain since
            // the thinking-level arch work, but no Android surface could write
            // it — only backup import / config restore / the agent RPC could.
            // A model that reaches MAX in the catalog therefore could not be
            // capped below it by hand. Null = "inherit the catalog default",
            // so an untouched picker writes nothing.
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_max_thinking_level))
                // `itemLabel` is a plain `(T) -> String`, not a composable
                // lambda, so the "inherit" caption is resolved here (one
                // stringResource call in composition scope) instead of inside
                // the lambda.
                val inheritLabel = stringResource(R.string.modeldetail_max_thinking_inherit)
                SectionDropdown(
                    selected = maxThinkingLevel,
                    // OFF is excluded because the switch above already carries
                    // "thinking off" semantics, and AUTO is excluded because it
                    // is not an intensity at all (it means "no effort opinion")
                    // — capping a model at "no opinion" would be a contradictory
                    // instruction, so the ceiling list is the real intensity
                    // ladder plus "inherit".
                    items = listOf<ThinkingLevel?>(null) +
                        ThinkingLevel.entries.filter {
                            it != ThinkingLevel.OFF && it != ThinkingLevel.AUTO
                        },
                    onSelect = { maxThinkingLevel = it },
                    itemLabel = { level -> level?.localizedName(context) ?: inheritLabel },
                )
            }
        }

        // ── Selection ──────────────────────────────────────────────────
        // [selection-only-ui-writer-1112] Since the selection-only migration
        // the entry table holds SELECTIONS only — there is no separate
        // "visible vs hidden" state left to toggle (the old Visibility switch
        // wrote `copy(isHidden = true)`, which minted a row that every
        // `!isHidden` reader dropped — model picker, onboarding, group sheet,
        // agent-loop sheet — while the Manage sheet still showed it as
        // selected, and which the backup importer then discarded on the next
        // restore). The only remaining membership operation is "remove from
        // selection", and it goes through `unselectEntry` — the same path the
        // config setter and the management sheet use.
        SettingsSection(
            header = stringResource(R.string.model_entry_selection),
            footer = stringResource(R.string.model_entry_unselect_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.model_entry_unselect),
                titleColor = MaterialTheme.colorScheme.error,
                showChevron = false,
                showDivider = false,
                onClick = {
                    // Set the guard BEFORE the write: the write makes `entry`
                    // null, and the null-entry branch would otherwise fire its
                    // own onBack() — two pops for one tap (the same double-pop
                    // shape ProviderDetailScreen guards with its `deleted` flag).
                    removedFromSelection = true
                    providerRepository.unselectEntry(entry.id)
                    AppLogger.info(TAG, "Removed model entry ${entry.id} from the selection set")
                    onBack()
                },
            )
        }

        // ── Input Modality ──────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_input_modality),
            footer = stringResource(R.string.modeldetail_input_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = imageInput,
                onCheckedChange = { imageInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_pdf_input),
                checked = pdfInput,
                onCheckedChange = { pdfInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_input),
                checked = audioInput,
                onCheckedChange = { audioInput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_input),
                checked = videoInput,
                onCheckedChange = { videoInput = it },
                showDivider = false,
            )
        }

        // ── Output Modality ─────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_output_modality),
            footer = stringResource(R.string.modeldetail_output_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_output),
                checked = imageOutput,
                onCheckedChange = { imageOutput = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_output),
                checked = audioOutput,
                onCheckedChange = { audioOutput = it },
            )
            // [RC-1114 U-18] The video-output dimension the data layer already
            // carried. Without this switch a model could only ever inherit
            // "video" from its catalog row — never be marked as producing
            // video by the user, even though the model list badge and the
            // group sheet both render that capability.
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_output),
                checked = videoOutput,
                onCheckedChange = { videoOutput = it },
                showDivider = false,
            )
        }

        // ── Quick Test ──
        SettingsSection(
            footer = stringResource(R.string.quicktest_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = Icons.Outlined.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }

        // ── Reset (only for built-in models that have been customized) ──
        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
                footer = stringResource(R.string.model_entry_restores_the_provider_reported_display_n),
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        // [selection-only-ui-writer-1112] No `isHidden` here
                        // either: the Reset clears the user's overrides, and
                        // "hidden" is not an override — it is a membership
                        // flag the selection-only table no longer carries.
                        providerRepository.updateEntry(entry.copy(overrides = ModelOverrides()))
                        onBack()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.rikkaminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}

/**
 * [dv22/B-03] The raw base model with only its modality fields replaced by the
 * models.dev-registry values. Every other field is left untouched on purpose:
 * the entry-edit screen compares displayName / supportsReasoning / contextWindow
 * against what the entry inherits, and enrichment must not turn "unchanged" into
 * a written override there.
 */
private fun enrichCapabilityBase(baseModel: LLMModel): LLMModel {
    val enriched = ModelsDevApi.enrichModel(baseModel)
    if (enriched === baseModel) return baseModel
    return baseModel.copy(
        inputModalities = enriched.inputModalities ?: baseModel.inputModalities,
        outputModalities = enriched.outputModalities ?: baseModel.outputModalities,
    )
}
