package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry

/**
 * [model-catalog-split] Pure decision core for the two selection-only
 * invariants, extracted from [ProviderRepository] so they are JVM-testable
 * without an Android Context / SharedPreferences runtime.
 *
 *  1. [decideSelectionOnlyKeep] — which legacy rows survive the one-shot
 *     migration to a selection-only `modelEntries` table.
 *  2. [reconcileSelectedEntry] — how one selected entry merges a fresh
 *     remote-catalog row (metadata refresh; user-authored state preserved;
     * absent-from-catalog entries untouched).
 */

/**
 * Decide whether a legacy [ModelEntry] survives the selection-only migration.
 *
 * KEEP when ANY of:
 *  - [ModelEntry.isCustom] (user-authored, never a catalog copy),
 *  - `!isHidden` (explicitly selected under the old model),
 *  - its `baseModel.id` is in [voiceTemplateModelIds] for its instance
 *    (seeds are re-added by the voice reconcile anyway — keeping them avoids
 *    a delete-then-readd cycle in the same startup),
 *  - [providerInstanceIdIsSystemVoice] (virtual System voice entries, never
 *    persisted — defensive).
 *
 * Everything else is a disposable catalog copy the user never selected.
 */
internal fun decideSelectionOnlyKeep(
    entry: ModelEntry,
    voiceTemplateModelIds: Set<String>,
    providerInstanceIdIsSystemVoice: Boolean,
): Boolean =
    entry.isCustom ||
        !entry.isHidden ||
        entry.baseModel.id in voiceTemplateModelIds ||
        providerInstanceIdIsSystemVoice

/**
 * [review1112-p1-2] Whether one IMPORTED row (a backup's `models[]` element)
 * may be written into the selection-only entry table.
 *
 * This is the restore-side twin of [decideSelectionOnlyKeep], and it exists
 * because the rule was applied on only ONE of the two restore paths: the
 * OpenMinis compat converter drops hidden non-custom rows
 * (`decideLegacyHiddenImportKeep`), while the native backup importer
 * (`ProviderRepository.parseImportedModelEntries`) wrote `isHidden` through
 * verbatim. A backup taken on the pre-split build carries one hidden row per
 * catalog model (the old refresh inserted every remote row that way), so
 * restoring it re-minted exactly the rows the one-shot migration deletes —
 * and the migration's `prefs` gate has already flipped, so it never runs
 * again.
 *
 * The result is the same split-brain the selection-only migration removed:
 * the row is invisible to every `!isHidden` reader (model picker, group sheet,
 * agent-loop sheet) while the Manage sheet — which reads the full set — still
 * shows it as selected, and the next backup silently drops it.
 *
 * A hidden CUSTOM row is kept: the user authored it, and the migration keeps
 * custom entries too. A non-hidden row is always kept (it is a selection).
 */
internal fun decideImportedRowKeep(isHidden: Boolean, isCustom: Boolean): Boolean =
    !isHidden || isCustom

/**
 * Result of reconciling ONE selected entry against a fresh catalog row:
 * [changed] is false when the catalog does not list this id (leave the entry
 * alone — a vendor dropping a model must not delete the user's selection) or
 * when the merge is a no-op; [merged] is the entry to persist (entry with
 * baseModel MERGED — identity/user-authored fields survive because only
 * baseModel is touched).
 */
internal data class SelectionReconcileResult(
    val changed: Boolean,
    val merged: ModelEntry,
)

/**
 * [field-identity P2-E] Merge a fresh catalog row ONTO the entry's current
 * `baseModel` field by field, instead of replacing the whole row.
 *
 * `baseModel` carries two roles at once (identity + capability carrier), and
 * a wholesale `entry.copy(baseModel = fresh)` treats a catalog row as if it
 * were always COMPLETE. It is not: a relay that answers `/v1/models` with
 * `{id}` only (or drops `input_modalities` on a bad day) made an already
 * correct entry DEGRADE — the model silently lost its context window /
 * reasoning flag / image input, and the user's screen said so.
 *
 * Rule: **the fresh row wins wherever it says something; a null (or blank
 * display name) falls back to what the entry already had.** This mirrors
 * [mergeRegistryRow]'s "registry only fills a gap" direction, applied one
 * layer up: the endpoint is authoritative when it speaks, silence is not an
 * assertion.
 *
 * **`emptyList()` is ALSO silence — there is exactly ONE reading of "no
 * modalities" here, and it is "no opinion".** Two independent facts force it:
 *
 *  1. `List<String>?.normalizeModalities()` folds an empty list back to
 *     `null` (`takeIf { it.isNotEmpty() }`), and EVERY consumer
 *     (`imageInputDecision`, `acceptsImageInput`, `capabilityPromptFragment`,
 *     `directionalModalities`) goes through it — so an `emptyList()` stored on
 *     a model reads as `null` anyway;
 *  2. the cross-process hop decodes a MISSING key into `emptyList()`
 *     (`jsonStrList(arr) = if (arr == null) emptyList() else …` in
 *     `ModelExecutionService`), so "the endpoint said nothing" arrives here as
 *     an empty list, not as `null`.
 *
 * Reading an empty list as an affirmative "text only" declaration would
 * therefore turn EVERY model that crossed the worker boundary into
 * `DECLARED_TEXT_ONLY`. The only affirmative form is a NON-empty list — e.g.
 * `["text"]`. (An earlier revision of this KDoc claimed the opposite; the
 * claim was unreachable in production and wrong at the boundary. See
 * LRN-20261112-FLDROLE for the identity-overload family it belongs to.)
 *
 * A `displayName` equal to the row's own `id` is ALSO silence, not a name:
 * every parser defaults the missing name to the id (`optString("name", id)`,
 * `ModelsDevApi`'s `name ?: id`), so "the endpoint calls it `m1`" is
 * indistinguishable from "the endpoint said nothing". Keeping the entry's
 * existing name in that case is what stops a sparse refresh from replacing
 * `Pretty Name` with a raw id.
 *
 * `id` and `provider` come from the fresh row: the caller matched them by id,
 * so a different id here would mean the caller passed the wrong row.
 *
 * ponytail: the fresh row is taken as ENDPOINT testimony, but nothing marks
 * which of its fields the endpoint actually reported — `*ModelsApi` runs
 * `enrichModels` BEFORE this merge, so a models.dev value can arrive here
 * wearing the endpoint's authority and overwrite a real value the entry already
 * held (observed shape: a 960K gateway row became 200K after a refresh
 * because the endpoint was silent and the registry spoke). Not fixed here:
 * the honest fix is a per-field provenance stamp on [LLMModel] (four-layer
 * field addition) or reconciling against the UN-enriched row, both wider than
 * this branch.
 * 天花板: any endpoint that reports a SUBSET of the fields it used to report
 * will have the missing ones replaced by registry values on the next refresh.
 * 升级触发: a user reports "after refreshing, the window shrank / a capability
 * disappeared" — then add provenance (or reconcile pre-enrichment), not
 * another guard here.
 */
internal fun mergeCatalogRow(existing: LLMModel, fresh: LLMModel): LLMModel = fresh.copy(
    displayName = fresh.displayName.takeIf { it.isNotBlank() && it != fresh.id }
        ?: existing.displayName,
    contextWindow = fresh.contextWindow ?: existing.contextWindow,
    maxOutputTokens = fresh.maxOutputTokens ?: existing.maxOutputTokens,
    supportsReasoning = fresh.supportsReasoning ?: existing.supportsReasoning,
    interleavedReasoningField = fresh.interleavedReasoningField ?: existing.interleavedReasoningField,
    reasoningEffortValues = fresh.reasoningEffortValues ?: existing.reasoningEffortValues,
    declaresNoEffortTiers = fresh.declaresNoEffortTiers ?: existing.declaresNoEffortTiers,
    // [INCR-1113 F-01] `emptyList()` is silence, not an assertion — see the
    // KDoc above. Folding it to `null` here means a row that crosses the
    // worker boundary (where a missing key decodes to `emptyList()`) can never
    // wipe a capability the entry already carries.
    inputModalities = fresh.inputModalities?.takeIf { it.isNotEmpty() } ?: existing.inputModalities,
    outputModalities = fresh.outputModalities?.takeIf { it.isNotEmpty() } ?: existing.outputModalities,
)

internal fun reconcileSelectedEntry(
    entry: ModelEntry,
    freshCatalogRow: LLMModel?,
): SelectionReconcileResult {
    if (freshCatalogRow == null) return SelectionReconcileResult(changed = false, merged = entry)
    val merged = entry.copy(baseModel = mergeCatalogRow(entry.baseModel, freshCatalogRow))
    return SelectionReconcileResult(changed = merged != entry, merged = merged)
}
