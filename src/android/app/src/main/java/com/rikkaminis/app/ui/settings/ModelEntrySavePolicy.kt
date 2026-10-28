package com.rikkaminis.app.ui.settings

/**
 * [meta-audit-1028 E5 / B06 F-07] "Did the write land?" decisions for the two
 * model editors (AddCustomModelScreen, ModelEntryDetailScreen).
 *
 * The defect this exists for: both editors called the repository and then
 * navigated back unconditionally, so a write the data layer REFUSED looked
 * exactly like one that landed — the screen popped, the edit was gone, and
 * nothing anywhere said why. The two refusals are different in kind and both
 * are easy to misread, which is why they get a named classification instead of
 * an inline `if` at each call site:
 *
 *  1. `ProviderRepository.updateEntry(entry)` returns `String?`. `null` means
 *     NOTHING was persisted, for either of two reasons that need different
 *     responses: the rename collided with another entry of the same instance
 *     (stay open, name the clash) or the row no longer exists at all (the
 *     editor has nothing to edit — close it).
 *  2. `ProviderRepository.addEntry(entry)` returns a non-null `String` even
 *     when it refused the write: the composite-key guard hands back the id of
 *     the entry that ALREADY owned the (providerInstanceId, modelId) key. Its
 *     return type therefore cannot express "rejected", and the only honest
 *     signal available to the caller is "the id it reported was already one of
 *     ours before the call" — a successfully added entry's canonical id is by
 *     construction not in that snapshot.
 *
 * Pure (no Compose / Android / repository) so the JVM tests pin the contract
 * with literal expectations instead of re-deriving it from the call sites.
 */

/** What a refused or accepted save means for the editor that issued it. */
internal enum class EntrySaveOutcome {
    /** The write landed; the caller may navigate back. */
    SAVED,

    /** Nothing written because another entry owns the model id — stay open and say so. */
    ID_TAKEN,

    /** Nothing written because the row is gone — there is nothing left to edit. */
    ENTRY_GONE,
}

/**
 * Classify `updateEntry`'s return for the editor.
 *
 * [entryStillExists] is the caller's re-read of the config for the row being
 * edited, needed because `updateEntry` answers `null` for both of its refusal
 * paths (rename collision and "index not found"). Collapsing them would show
 * "this id is already used" for a row somebody else deleted.
 */
internal fun classifyUpdateResult(
    persistedId: String?,
    entryStillExists: Boolean,
): EntrySaveOutcome = when {
    persistedId != null -> EntrySaveOutcome.SAVED
    entryStillExists -> EntrySaveOutcome.ID_TAKEN
    else -> EntrySaveOutcome.ENTRY_GONE
}

/**
 * True when `addEntry` refused the write as a duplicate: [persistedId] is one
 * of the entry ids that existed before the call, i.e. it names somebody
 * else's row rather than the one this call minted.
 */
internal fun addEntryRejected(persistedId: String, idsBefore: Set<String>): Boolean =
    persistedId in idsBefore
