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
 * Result of reconciling ONE selected entry against a fresh catalog row:
 * [changed] is false when the catalog does not list this id (leave the entry
 * alone — a vendor dropping a model must not delete the user's selection) or
 * when the fresh row equals the current baseModel; [merged] is the entry to
 * persist (entry with baseModel replaced when the catalog has fresher
 * metadata — identity/user-authored fields survive because only baseModel is
 * copied over).
 */
internal data class SelectionReconcileResult(
    val changed: Boolean,
    val merged: ModelEntry,
)

internal fun reconcileSelectedEntry(
    entry: ModelEntry,
    freshCatalogRow: LLMModel?,
): SelectionReconcileResult {
    if (freshCatalogRow == null) return SelectionReconcileResult(changed = false, merged = entry)
    val merged = entry.copy(baseModel = freshCatalogRow)
    return SelectionReconcileResult(changed = merged != entry, merged = merged)
}
