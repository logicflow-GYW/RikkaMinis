package com.rikkaminis.app.data.repository

/**
 * [T-multi-api-key] One write to the encrypted credential store, lifted out of
 * [ProviderRepository.saveApiKeys] so the slot contract is unit-testable without
 * Android's encrypted prefs.
 */
internal sealed interface CredentialSlotOp {
    /** Write [value] into credential slot [index]. */
    data class Put(val index: Int, val value: String) : CredentialSlotOp

    /** Erase credential slot [index]. */
    data class Remove(val index: Int) : CredentialSlotOp
}

/**
 * Plan the store writes for a saved credential list. The whole contract is
 * visible in the returned ops, which is why this is a pure function:
 *
 *  - slot i ← `keys[i]`, dense and positional;
 *  - `null` means "this row has no secret" and becomes an explicit [CredentialSlotOp.Remove]
 *    (slot 0 excepted — it is the historical single-key slot, written through the
 *    legacy single-key path). The list is the single source of truth, so a row
 *    that is empty, or was just deleted in the same editing session, can never
 *    leave a live secret behind at its index — that is precisely how a deleted
 *    key used to come back to life wearing a new label;
 *  - every slot at or beyond the new size, up to [previousCount], is erased, so a
 *    shrunken list leaves no orphan plaintext in the encrypted store.
 */
internal fun planCredentialSlotWrites(
    keys: List<String?>,
    previousCount: Int,
): List<CredentialSlotOp> {
    val ops = mutableListOf<CredentialSlotOp>()
    keys.forEachIndexed { i, k ->
        when {
            k != null -> ops += CredentialSlotOp.Put(i, k)
            i > 0 -> ops += CredentialSlotOp.Remove(i)
        }
    }
    for (i in keys.size until maxOf(previousCount, keys.size)) {
        if (i > 0) ops += CredentialSlotOp.Remove(i)
    }
    return ops
}
