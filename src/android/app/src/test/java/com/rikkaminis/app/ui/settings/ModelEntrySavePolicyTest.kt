package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 E5 / B06 F-07] "Did the write land?" decisions for the two
 * model editors.
 *
 * The contract being pinned is the REPOSITORY's, restated as literals:
 *  - `updateEntry()` returns null on refusal — BOTH for "rename collided" and
 *    for "row not found", which is why the caller must disambiguate;
 *  - `addEntry()`'s duplicate refusal is only visible as "the id it reported
 *    was already an existing row's id".
 * Both editors used to ignore exactly these signals and pop the screen, so the
 * user's edit vanished behind a success-shaped navigation.
 *
 * Expectations are literals (never derived from the code under test), so a
 * mutation cannot ride along as PASS.
 */
class ModelEntrySavePolicyTest {

    // -- ① updateEntry: null means nothing was written, for two reasons --

    @Test
    fun classifyUpdate_persistedIdMeansSaved() {
        assertEquals(EntrySaveOutcome.SAVED, classifyUpdateResult("prov-1/gpt-4o", entryStillExists = true))
        // …even if the row then reads as gone: a non-null id is a write.
        assertEquals(EntrySaveOutcome.SAVED, classifyUpdateResult("prov-1/gpt-4o", entryStillExists = false))
        // The canonical-id form of an empty-but-valid id is still non-null.
        assertEquals(EntrySaveOutcome.SAVED, classifyUpdateResult("", entryStillExists = true))
    }

    @Test
    fun classifyUpdate_nullWithRowPresentIsIdCollision() {
        assertEquals(EntrySaveOutcome.ID_TAKEN, classifyUpdateResult(null, entryStillExists = true))
    }

    @Test
    fun classifyUpdate_nullWithRowGoneIsNotACollision() {
        // `updateEntry` also answers null when its index lookup misses. Reporting
        // "this id is already used" for a row somebody else deleted would be a
        // lie the user cannot act on; the editor should just close.
        assertEquals(EntrySaveOutcome.ENTRY_GONE, classifyUpdateResult(null, entryStillExists = false))
    }

    // -- ② addEntry rejected: the reported id names an existing row --

    @Test
    fun addEntryRejected_whenReportedIdAlreadyExisted() {
        val before = setOf("prov-1/gpt-4o", "prov-1/claude")
        // The composite-key guard hands back the SURVIVING entry's id.
        assertTrue(addEntryRejected("prov-1/gpt-4o", before))
        assertTrue(addEntryRejected("prov-1/claude", before))
    }

    @Test
    fun addEntryRejected_freshIdIsNotADuplicate() {
        val before = setOf("prov-1/gpt-4o", "prov-1/claude")
        assertFalse(addEntryRejected("prov-1/gpt-5", before))
        assertFalse(addEntryRejected("prov-2/gpt-4o", before))
    }

    @Test
    fun addEntryRejected_emptySnapshotIsNeverADuplicate() {
        assertFalse(addEntryRejected("prov-1/gpt-4o", emptySet()))
    }
}
