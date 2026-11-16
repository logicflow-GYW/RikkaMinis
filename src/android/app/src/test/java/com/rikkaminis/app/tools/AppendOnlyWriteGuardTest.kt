package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/append-only-write-guard-1116] Regression tests for the two guards that
 * stop `file_write` from destroying a ledger or a memory file.
 *
 * The defect being pinned: six incidents between 2026-10-17 and 2026-11-16
 * replaced all of `/var/minis/shared/backlog.md` with a few hundred bytes,
 * because `file_write`'s default is `append = false`. Every recovery needed a
 * snapshot that happened to exist. The expectation tables below are written by
 * hand — never derived from the implementation — so a change to the guard
 * cannot silently update its own oracle.
 */
class AppendOnlyWriteGuardTest {

    private val ledger = "/var/minis/shared/backlog.md"
    private val dailyLog = "/var/minis/memory/2026-11-16.md"
    private val globalMemory = "/var/minis/memory/GLOBAL.md"
    private val rollup = "/var/minis/memory/MEMORY-ROLLUP.md"

    // ---------------------------------------------------------------- targets

    @Test
    fun ledgerIsAppendOnly() {
        assertTrue(AppendOnlyWriteGuard.isAppendOnly(ledger))
    }

    @Test
    fun memoryDirectoryIsAppendOnly() {
        assertTrue(AppendOnlyWriteGuard.isAppendOnly(dailyLog))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly(globalMemory))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly(rollup))
    }

    @Test
    fun ordinaryPathsAreNotAppendOnly() {
        assertFalse(AppendOnlyWriteGuard.isAppendOnly("/var/minis/workspace/notes.md"))
        assertFalse(AppendOnlyWriteGuard.isAppendOnly("/var/minis/shared/report-1116.md"))
        assertFalse(AppendOnlyWriteGuard.isAppendOnly("/root/test.txt"))
        // Sibling with the same basename is a different file.
        assertFalse(AppendOnlyWriteGuard.isAppendOnly("/var/minis/workspace/backlog.md"))
    }

    @Test
    fun normalizationCannotLaunderAnAppendOnlyPath() {
        // Every one of these resolves to the ledger.
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/shared//backlog.md"))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/shared/./backlog.md"))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/shared/x/../backlog.md"))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/workspace/../shared/backlog.md"))
        // Same for memory.
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/memory/./2026-11-16.md"))
        assertTrue(AppendOnlyWriteGuard.isAppendOnly("/var/minis/workspace/x/../../memory/GLOBAL.md"))
    }

    @Test
    fun unnormalizablePathIsNotTreatedAsAppendOnly() {
        // A relative path that climbs past its root is rejected by the path
        // resolver later; the guard must not guess.
        assertFalse(AppendOnlyWriteGuard.isAppendOnly("../../memory/GLOBAL.md"))
    }

    // --------------------------------------------------------------- decisions

    @Test
    fun ordinaryTargetIsAlwaysAllowed() {
        val decision = AppendOnlyWriteGuard.decide(
            path = "/var/minis/workspace/notes.md",
            append = false,
            overwrite = false,
            overwriteReason = "",
            exists = true,
            currentBytes = 1_000_000,
            incomingBytes = 10,
        )
        assertEquals(AppendOnlyWriteGuard.Decision.Allow, decision)
    }

    @Test
    fun creatingAnAppendOnlyFileIsAllowed() {
        // Nothing exists yet, so nothing can be lost.
        val decision = AppendOnlyWriteGuard.decide(
            path = ledger,
            append = false,
            overwrite = false,
            overwriteReason = "",
            exists = false,
            currentBytes = 0,
            incomingBytes = 400,
        )
        assertEquals(AppendOnlyWriteGuard.Decision.Allow, decision)
    }

    @Test
    fun appendingToAnAppendOnlyFileIsAllowed() {
        val decision = AppendOnlyWriteGuard.decide(
            path = ledger,
            append = true,
            overwrite = false,
            overwriteReason = "",
            exists = true,
            currentBytes = 2_099_442,
            incomingBytes = 900,
        )
        assertEquals(AppendOnlyWriteGuard.Decision.Allow, decision)
    }

    @Test
    fun overwritingTheLedgerWithoutAppendIsRefused() {
        val decision = AppendOnlyWriteGuard.decide(
            path = ledger,
            append = false,
            overwrite = false,
            overwriteReason = "",
            exists = true,
            currentBytes = 2_099_442,
            incomingBytes = 1_256,
        )
        assertTrue(decision is AppendOnlyWriteGuard.Decision.Refuse)
        decision as AppendOnlyWriteGuard.Decision.Refuse
        assertEquals(AppendOnlyWriteGuard.RefusalReason.NEEDS_APPEND, decision.reason)
        // The refusal must carry the damage numbers: they are what makes the
        // message land as "you are about to lose 2 MB", not "some rule".
        assertEquals(2_099_442L, decision.currentBytes)
        assertEquals(1_256L, decision.incomingBytes)
    }

    @Test
    fun overwritingAMemoryFileWithoutAppendIsRefused() {
        val decision = AppendOnlyWriteGuard.decide(
            path = dailyLog,
            append = false,
            overwrite = false,
            overwriteReason = "",
            exists = true,
            currentBytes = 50_176,
            incomingBytes = 120,
        )
        assertTrue(decision is AppendOnlyWriteGuard.Decision.Refuse)
        assertEquals(
            AppendOnlyWriteGuard.RefusalReason.NEEDS_APPEND,
            (decision as AppendOnlyWriteGuard.Decision.Refuse).reason,
        )
    }

    @Test
    fun overwriteWithoutReasonIsStillRefused() {
        val decision = AppendOnlyWriteGuard.decide(
            path = ledger,
            append = false,
            overwrite = true,
            overwriteReason = "",
            exists = true,
            currentBytes = 2_099_442,
            incomingBytes = 1_256,
        )
        assertTrue(decision is AppendOnlyWriteGuard.Decision.Refuse)
        assertEquals(
            AppendOnlyWriteGuard.RefusalReason.OVERWRITE_NOT_JUSTIFIED,
            (decision as AppendOnlyWriteGuard.Decision.Refuse).reason,
        )
    }

    @Test
    fun overwriteWithAReasonIsTheOneAllowedReplacePath() {
        val decision = AppendOnlyWriteGuard.decide(
            path = ledger,
            append = false,
            overwrite = true,
            overwriteReason = "restored from the 11:46 mirror snapshot after a corrupt append",
            exists = true,
            currentBytes = 2_099_442,
            incomingBytes = 2_089_443,
        )
        assertEquals(AppendOnlyWriteGuard.Decision.Allow, decision)
    }

    @Test
    fun refusalMessageNamesBothWaysOut() {
        val message = AppendOnlyWriteGuard.refusalMessage(
            ledger,
            AppendOnlyWriteGuard.Decision.Refuse(
                AppendOnlyWriteGuard.RefusalReason.NEEDS_APPEND,
                currentBytes = 2_099_442,
                incomingBytes = 1_256,
            ),
        )
        // The two escape routes must be discoverable from the error alone.
        assertTrue(message.contains("append=true"))
        assertTrue(message.contains("overwrite=true"))
        assertTrue(message.contains("overwrite_reason"))
        // And the damage must be quantified.
        assertTrue(message.contains("2099442"))
        assertTrue(message.contains("1256"))
    }

    // --------------------------------------------------------------- snapshots

    @Test
    fun appendingNeverSnapshots() {
        assertFalse(
            WriteSnapshotPlan.shouldSnapshot(
                append = true,
                appendOnly = true,
                exists = true,
                currentBytes = 2_099_442,
                incomingBytes = 900,
            ),
        )
    }

    @Test
    fun newFileNeverSnapshots() {
        assertFalse(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = false,
                currentBytes = 0,
                incomingBytes = 400,
            ),
        )
    }

    @Test
    fun replacingAnAppendOnlyFileAlwaysSnapshots() {
        // Small shrink, but this is a ledger: copy it anyway.
        assertTrue(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = true,
                exists = true,
                currentBytes = 9_000,
                incomingBytes = 8_000,
            ),
        )
    }

    @Test
    fun ordinaryFileShrinkingHardSnapshots() {
        assertTrue(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = true,
                currentBytes = 2_099_442,
                incomingBytes = 1_256,
            ),
        )
    }

    @Test
    fun ordinaryFileShrinkingModestlyDoesNotSnapshot() {
        assertFalse(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = true,
                currentBytes = 100_000,
                incomingBytes = 60_000,
            ),
        )
    }

    @Test
    fun smallFileCannotTriggerASnapshot() {
        // Below MIN_TARGET_BYTES a heavy shrink is normal editing.
        assertFalse(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = true,
                currentBytes = 8_191,
                incomingBytes = 10,
            ),
        )
        assertTrue(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = true,
                currentBytes = 8_192,
                incomingBytes = 10,
            ),
        )
    }

    @Test
    fun growingAnOrdinaryFileDoesNotSnapshot() {
        assertFalse(
            WriteSnapshotPlan.shouldSnapshot(
                append = false,
                appendOnly = false,
                exists = true,
                currentBytes = 100_000,
                incomingBytes = 120_000,
            ),
        )
    }

    @Test
    fun snapshotPathIsASiblingWithAStamp() {
        assertEquals(
            "/var/minis/shared/backlog.md.bak-20261116-150312-123",
            WriteSnapshotPlan.snapshotPath("/var/minis/shared/backlog.md", "20261116-150312-123"),
        )
    }
}
