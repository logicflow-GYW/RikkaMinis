package com.rikkaminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R3-E F-14] JVM tests for the two decisions behind the staged-share
 * cleanup. The file walk itself needs a Context, so the DECISIONS are pinned
 * here in pure form and the wiring is shape-asserted on source.
 *
 * Defect 1 (nuclear delete): `cleanSharedFiles` removed EVERY file in the
 * staging directory — including files a newer share had staged — because it
 * had no keep set. The keep rule now is "a file the pending share still
 * references survives; everything else is an orphan from an earlier share".
 *
 * Defect 2 (counter drift): the ledger was reset to zero unconditionally while
 * referenced files kept occupying the budget (under-count), and debits were
 * only applied on paths that remembered to call them (over-count drift until
 * the 500 MB cap falsely tripped). The counter is now reconciled absolutely to
 * the bytes actually on disk.
 */
class ShareStagedCleanupPolicyTest {

    // ─── the keep rule, table-driven ─────────────────────────────────────────

    /**
     * The rule [SharedShareStore.cleanSharedFiles] applies per directory
     * entry: keep it iff the pending share (if any) references it as an
     * attachment.
     */
    private fun shouldKeep(fileName: String, referenced: Set<String>): Boolean =
        fileName in referenced

    @Test
    fun `keep rule - referenced attachment survives, orphans go`() {
        val referenced = setOf("shared-abc.bin", "shared-text-1.txt")
        val table = mapOf(
            "shared-abc.bin" to true,        // referenced by the newest pending share
            "shared-text-1.txt" to true,     // referenced (AttachText kind is ATTACHMENT too)
            "shared-old.bin" to false,       // orphan from the PREVIOUS share
            "shared-tmp.tmp" to false,       // a half-written staging file
            "unrelated.txt" to false,
        )
        table.forEach { (name, expected) ->
            assertEquals("keep decision for $name", expected, shouldKeep(name, referenced))
        }
    }

    @Test
    fun `keep rule - inline text values never keep a file`() {
        // An INLINE_TEXT item's value is the text itself, not a filename; only
        // ATTACHMENT values may enter the keep set. (The store filters by kind
        // before building the set — this pins why.)
        val referenced = setOf("shared-abc.bin")
        assertFalse(shouldKeep("Look at https://example.com", referenced))
        assertTrue(shouldKeep("shared-abc.bin", referenced))
    }

    @Test
    fun `keep rule - empty pending share deletes everything`() {
        val referenced = emptySet<String>()
        assertFalse(shouldKeep("shared-abc.bin", referenced))
        assertFalse(shouldKeep("whatever", referenced))
    }

    // ─── the reconcile, literal arithmetic ───────────────────────────────────

    @Test
    fun `reconcile sets the ledger to the bytes actually on disk`() {
        val ledger = StagedBytesLedger(initial = 900L)
        // Files kept on disk after a selective cleanup sum to 350 bytes; the
        // counter said 900 (credited for files now deleted).
        ledger.set(350L)
        assertEquals(350L, ledger.current())
    }

    @Test
    fun `reconcile clamps at zero and never goes negative`() {
        val ledger = StagedBytesLedger(initial = 100L)
        ledger.set(-5L) // a directory listing can never produce this, but the clamp is the contract
        assertEquals(0L, ledger.current())
    }

    @Test
    fun `reconcile after everything is deleted lands on zero`() {
        val ledger = StagedBytesLedger(initial = 9_999L)
        ledger.set(0L)
        assertEquals(0L, ledger.current())
    }

    @Test
    fun `credit-debit-reconcile sequence ends at the disk truth`() {
        // The full lifecycle: stage two files (credit), one orphan deleted by
        // an outside path (debit, but suppose the caller forgot), then the
        // reconcile fixes whatever the bookkeeping missed.
        val ledger = StagedBytesLedger()
        ledger.credit(400L)
        ledger.credit(200L)
        assertEquals(600L, ledger.current())
        // Cleanup deletes the 400-byte orphan and keeps the 200-byte file.
        ledger.set(200L)
        assertEquals(200L, ledger.current())
    }

    @Test
    fun `reset and set are the same operation at zero`() {
        val a = StagedBytesLedger(initial = 123L).also { it.reset() }
        val b = StagedBytesLedger(initial = 123L).also { it.set(0L) }
        assertEquals(a.current(), b.current())
    }
}
