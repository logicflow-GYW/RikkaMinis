package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.ProviderCredentialMeta

/**
 * [T-multi-api-key] One editable row of `CredentialListEditor`, lifted out of the
 * composable so the slot math is unit-testable (Compose state is not).
 *
 * ## Why [sourceSlot] exists
 *
 * `draft == null` means "keep whatever secret is stored in [sourceSlot]" — that
 * is the load-bearing state for pre-existing rows: the UI shows a masked summary
 * of the stored secret, and a draft only appears once the user types a
 * replacement. Without it, pressing Save after opening the editor would demand
 * retyping every key (a data-loss trap).
 *
 * The trap that *this* field closes: the secret store is keyed by INDEX, while
 * the rows are a list the user can shrink. With a positional reading of the
 * drafts (`draft == null` → "keep slot i, where i is the row's current position"),
 * deleting row 0 of three made row 0 (formerly "K2") keep slot 0 — i.e. it kept
 * the secret of the credential that was just deleted — while the tail slot was
 * cleaned, destroying a live credential's secret. Remembering each row's ORIGINAL
 * position keeps every row attached to its own secret across deletions:
 *
 *   rows [K1,i=0] [K2,i=1] [K3,i=2], delete row 0
 *   → row "K2" still reads slot 1, row "K3" still reads slot 2
 *   → save writes [secret1, secret2] densely, cleanup erases slot 2
 *
 * [ProviderCredentialMeta.id] is the model's identity anchor; [sourceSlot] is its
 * positional twin and is only ever used to resolve a secret — never as identity.
 */
internal data class CredentialSlotRow(
    val meta: ProviderCredentialMeta,
    val draft: String?,
    /** Original list position at load time, or null for a row the user just added. */
    val sourceSlot: Int?,
)

/**
 * The secret this row currently stands for: the user's draft if they typed one,
 * otherwise the stored secret at the row's OWN original slot.
 *
 * Used for both display and save so the two can never disagree (showing a
 * neighbour's secret while saving another is exactly how the deletion bug hid).
 */
internal fun CredentialSlotRow.effectiveSecret(storedKeys: Map<Int, String?>): String? =
    draft ?: sourceSlot?.let { storedKeys[it] }

/**
 * Materialize the secrets to WRITE, in row order.
 *
 * The result is positional and dense (`slots[rowIndex]`), with `null` meaning
 * "this row has no secret" — the repository writes those as an explicit clear, so
 * the list is the single source of truth and a shrunk list cannot leave a deleted
 * key sitting in a slot the rotation loop still walks.
 */
internal fun planCredentialSlots(
    rows: List<CredentialSlotRow>,
    storedKeys: Map<Int, String?>,
): List<String?> = rows.map { it.effectiveSecret(storedKeys) }
