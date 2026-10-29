package com.rikkaminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R3-D / F-08] Stage 1's merge decision, extracted so it can be pinned
 * without Room.
 *
 * A backup may carry two providers with the same `(providerType, label)`.
 * `mergeImportInstanceJSON` matches on exactly that pair against the LIVE
 * instance list, which grows as the import runs — so the second entry of the
 * pair used to merge into the instance the first one had just produced,
 * overwriting its credentials/settings. The import then reported
 * `providersImported = 2` while one provider existed.
 *
 * The rule: an instance this run already claimed is not a merge target; the
 * entry takes the append path (label conflict rename) instead.
 */
class BackupProviderMergeTest {

    @Test
    fun `no live match means append, never merge`() {
        assertFalse(shouldMergeIntoExisting(matchedLiveInstanceId = null, claimedThisRun = emptySet()))
        assertFalse(
            shouldMergeIntoExisting(
                matchedLiveInstanceId = null,
                claimedThisRun = setOf("inst-1"),
            ),
        )
    }

    @Test
    fun `a pre-existing instance is a legitimate merge target`() {
        assertTrue(
            shouldMergeIntoExisting(
                matchedLiveInstanceId = "inst-existing",
                claimedThisRun = emptySet(),
            ),
        )
    }

    @Test
    fun `an instance this run already claimed is not`() {
        // The duplicate-label pair: entry 1 merged into / created `inst-a`, so
        // entry 2 must not merge into it.
        assertFalse(
            shouldMergeIntoExisting(
                matchedLiveInstanceId = "inst-a",
                claimedThisRun = setOf("inst-a"),
            ),
        )
        // …while a DIFFERENT live instance stays mergeable (the set only holds
        // what this run touched).
        assertTrue(
            shouldMergeIntoExisting(
                matchedLiveInstanceId = "inst-b",
                claimedThisRun = setOf("inst-a"),
            ),
        )
    }

    @Test
    fun `the third duplicate still appends`() {
        // After two same-label entries, the live match is still the first one
        // (labels are renamed for the appended copies), and it stays claimed.
        assertFalse(
            shouldMergeIntoExisting(
                matchedLiveInstanceId = "inst-a",
                claimedThisRun = setOf("inst-a", "inst-a2"),
            ),
        )
    }
}
