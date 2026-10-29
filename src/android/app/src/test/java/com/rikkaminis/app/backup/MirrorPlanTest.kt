package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/auto-backup-external-mirror] JVM coverage for the mirror's decisions.
 * Every expected value below is a hard-coded literal — none of them is derived
 * from a MirrorPlan constant, so a change to a constant flips the test instead
 * of silently re-baselining it (evidence-discipline: a test whose expectation
 * follows the code under test verifies nothing).
 */
class MirrorPlanTest {

    // ------------------------------------------------------------------ diff

    @Test
    fun diff_classifiesAddedChangedUnchangedRemoved() {
        val before = listOf(
            MirrorPlan.Entry("memory/a.md", 10, 100),
            MirrorPlan.Entry("memory/b.txt", 20, 200),
            MirrorPlan.Entry("memory/c.md", 30, 300),
        )
        val now = listOf(
            MirrorPlan.Entry("memory/a.md", 12, 100), // size moved → changed
            MirrorPlan.Entry("memory/b.txt", 20, 200), // identical → unchanged
            MirrorPlan.Entry("memory/d.md", 40, 400),  // new → added
        )
        val d = MirrorPlan.diff(before, now)
        assertEquals(listOf(MirrorPlan.Entry("memory/d.md", 40, 400)), d.added)
        assertEquals(listOf(MirrorPlan.Entry("memory/a.md", 12, 100)), d.changed)
        assertEquals(listOf("memory/c.md"), d.removed)
        assertEquals(1, d.unchanged)
        assertTrue(d.hasChanges)
        assertEquals(2, d.toWrite.size) // added + changed
    }

    @Test
    fun diff_emptyManifestMeansEverythingIsAdded() {
        val now = listOf(MirrorPlan.Entry("skills/x.md", 1, 1))
        val d = MirrorPlan.diff(emptyList(), now)
        assertEquals(1, d.added.size)
        assertEquals(0, d.unchanged)
        assertTrue(d.hasChanges)
    }

    @Test
    fun diff_identicalSelectionHasNoChanges() {
        val entries = listOf(
            MirrorPlan.Entry("memory/a.md", 10, 100),
            MirrorPlan.Entry("shared/top.md", 5, 50),
        )
        val d = MirrorPlan.diff(entries, entries)
        assertFalse(d.hasChanges)
        assertEquals(2, d.unchanged)
    }

    // ------------------------------------------------------------ shouldWrite

    @Test
    fun shouldWrite_noChangesNeverWrites() {
        // Even a never-synced mirror does not write an identical tree: that
        // would move `syncedAt` forward and make a dead pipeline look healthy.
        assertFalse(MirrorPlan.shouldWrite(null, 1_000L, false))
        assertFalse(MirrorPlan.shouldWrite(5_000_000L, 5_050_000L, false))
    }

    @Test
    fun shouldWrite_firstSyncRunsImmediately() {
        assertTrue(MirrorPlan.shouldWrite(null, 1_000L, true))
    }

    @Test
    fun shouldWrite_coalescesInsideOneMinute() {
        val last = 1_000_000L
        // 0:59 inside the 60 s coalescing window → wait.
        assertFalse(MirrorPlan.shouldWrite(last, last + 59_999L, true))
        // 1:00 exactly → go. Ten minutes was the pre-2026-10-29 value and it
        // gated freshness, not cost: this test is what keeps it from creeping
        // back.
        assertTrue(MirrorPlan.shouldWrite(last, last + 60_000L, true))
    }

    @Test
    fun shouldWrite_clockRollbackIsNotTooRecent() {
        // now < last: "unknown age" must not be read as "just synced", or a
        // clock rollback would silence the mirror until the clock catches up.
        assertTrue(MirrorPlan.shouldWrite(5_000_000L, 1_000_000L, true))
    }

    @Test
    fun shouldWrite_intervalIsTheCoalesceWindow() {
        // Literal: pins the constant itself, so widening the window is a
        // conscious test change, not a silent re-baseline.
        assertEquals(60_000L, MirrorPlan.WRITE_COALESCE_MS)
    }

    // --------------------------------------------------------- shouldMintPoint

    @Test
    fun shouldMintPoint_noPointsYetMints() {
        assertTrue(MirrorPlan.shouldMintPoint(emptyList(), 1_000L))
    }

    @Test
    fun shouldMintPoint_spacingIsTenMinutesRegardlessOfWriteWindow() {
        // The point spacing is the OTHER job the old 10-minute interval did.
        // Writes coalesce at 60 s; history must still span minutes.
        val lastPoint = 1_700_000_000_000L
        val stamp = BackupRotation.stamp(lastPoint)
        // 9:59 later → reuse the newest point, do not mint a 4th in 10 minutes.
        assertFalse(MirrorPlan.shouldMintPoint(listOf(stamp), lastPoint + 599_999L))
        // 10:00 exactly → mint.
        assertTrue(MirrorPlan.shouldMintPoint(listOf(stamp), lastPoint + 600_000L))
    }

    @Test
    fun shouldMintPoint_clockRollbackMints() {
        // Same polarity as shouldWrite: unknown age is not "recent enough".
        val stamp = BackupRotation.stamp(5_000_000L)
        assertTrue(MirrorPlan.shouldMintPoint(listOf(stamp), 1_000_000L))
    }

    @Test
    fun shouldMintPoint_unparseableNamesDoNotBlockMinting() {
        // A folder a user dropped in has no age; it must not hold history back
        // (the deletion side already refuses to make it a victim).
        assertTrue(MirrorPlan.shouldMintPoint(listOf("not-a-stamp"), 1_000L))
    }

    @Test
    fun shouldMintPoint_newestPointDecides() {
        val old = BackupRotation.stamp(1_000_000L)
        val recent = BackupRotation.stamp(1_700_000_000_000L)
        // Oldest is ancient, newest is recent: the NEWEST decides.
        assertFalse(
            MirrorPlan.shouldMintPoint(listOf(old, recent), 1_700_000_000_000L + 1_000L),
        )
    }

    // ------------------------------------------------------- outcomeKind/health

    @Test
    fun outcomeKind_mapsEveryRecordedLine() {
        assertEquals("ok", MirrorPlan.outcomeKind("ok 12w/3d/0a of 1810"))
        assertEquals("no-changes", MirrorPlan.outcomeKind("no-changes"))
        assertEquals("throttled", MirrorPlan.outcomeKind("throttled"))
        assertEquals("busy", MirrorPlan.outcomeKind("busy"))
        assertEquals("unavailable", MirrorPlan.outcomeKind("unavailable"))
        assertEquals("disabled", MirrorPlan.outcomeKind("disabled"))
        assertEquals("failed", MirrorPlan.outcomeKind("failed: disk full"))
        assertEquals("none", MirrorPlan.outcomeKind(null))
        assertEquals("unknown", MirrorPlan.outcomeKind("something else"))
    }

    @Test
    fun health_disabledShortCircuits() {
        assertTrue(
            MirrorPlan.classifyHealth(
                enabled = false,
                lastSyncMs = 0L,
                pending = 99,
                lastOutcome = "failed: x",
                nowMs = 1_000_000L,
            ) == MirrorPlan.Health.Disabled,
        )
    }

    @Test
    fun health_failureOutranksStaleness() {
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = 1_000L,
            pending = 5,
            lastOutcome = "failed: no space left on device",
            nowMs = 100_000_000L,
        )
        assertEquals(
            MirrorPlan.Health.Failed("no space left on device"),
            h,
        )
    }

    @Test
    fun health_unavailableIsAFailureWithoutReason() {
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = 1_000L,
            pending = 5,
            lastOutcome = "unavailable",
            nowMs = 100_000_000L,
        )
        assertEquals(MirrorPlan.Health.Failed(null), h)
    }

    @Test
    fun health_neverSyncedIsNever() {
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = 0L,
            pending = 3,
            lastOutcome = null,
            nowMs = 100_000_000L,
        )
        assertEquals(MirrorPlan.Health.Never(3), h)
    }

    @Test
    fun health_pendingInsideTheCoalesceWindowIsHealthy() {
        // THE regression this design exists for: a non-zero pending count right
        // after a write is normal coalescing, not a warning. The old button sat
        // here and did nothing.
        val last = 1_700_000_000_000L
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = last,
            pending = 4,
            lastOutcome = "ok 4w/0d/0a of 1810",
            nowMs = last + 45_000L,
        )
        assertEquals(MirrorPlan.Health.Healthy(4), h)
    }

    @Test
    fun health_pendingBeyondThirtyMinutesIsStale() {
        val last = 1_700_000_000_000L
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = last,
            pending = 4,
            lastOutcome = "no-changes",
            nowMs = last + 30L * 60_000L,
        )
        assertEquals(MirrorPlan.Health.Stale(4, 30L), h)
    }

    @Test
    fun health_staleNeedsPendingFiles() {
        // A quiet install with nothing waiting is healthy at any age: there is
        // nothing the mirror is failing to do.
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = 1_000L,
            pending = 0,
            lastOutcome = "no-changes",
            nowMs = 100_000_000L,
        )
        assertEquals(MirrorPlan.Health.Healthy(0), h)
    }

    @Test
    fun health_unreadablePendingDoesNotClaimZero() {
        // pendingCount null = the target could not be read. The row must show
        // no count rather than a reassuring 0.
        val h = MirrorPlan.classifyHealth(
            enabled = true,
            lastSyncMs = 1_000L,
            pending = null,
            lastOutcome = "ok 1w/0d/0a of 1810",
            nowMs = 100_000_000L,
        )
        assertEquals(MirrorPlan.Health.Healthy(null), h)
    }

    @Test
    fun health_staleThresholdIsThirtyMinutes() {
        // Literal, same rule as the other constant pins.
        assertEquals(1_800_000L, MirrorPlan.STALE_AFTER_MS)
    }

    // -------------------------------------------------------- previousVictims

    @Test
    fun previousVictims_dropsOldestBeyondKeep() {
        val points = listOf(
            "20261029-090000",
            "20261029-100000",
            "20261029-110000",
            "20261029-120000",
            "20261029-130000",
        )
        val victims = MirrorPlan.previousVictims(points, 3)
        // Newest-first, like the input ordering the caller sees in the
        // manifest: the two oldest points are the ones that go.
        assertEquals(listOf("20261029-100000", "20261029-090000"), victims)
    }

    @Test
    fun previousVictims_foreignNamesAreNeverVictims() {
        // A directory a user dropped in must not be deleted by rotation — we
        // do not own it and we cannot recreate it.
        val points = listOf("notes", "20261029-090000", "20261029-100000")
        val victims = MirrorPlan.previousVictims(points, 1)
        assertFalse("notes" in victims)
        assertEquals(listOf("20261029-090000"), victims)
    }

    // -------------------------------------------------------------- manifest

    @Test
    fun manifest_roundTrips() {
        val m = MirrorPlan.Manifest(
            syncedAtMs = 1_730_000_000_000,
            entries = listOf(
                MirrorPlan.Entry("memory/a.md", 10, 100),
                MirrorPlan.Entry("shared/work/b/fix-tasks/t.md", 2048, 123_456_789),
            ),
            previousPoints = listOf("20261029-100000", "20261029-090000"),
        )
        val text = MirrorPlan.encodeManifest(m)
        val back = MirrorPlan.decodeManifest(text)
        assertEquals(m, back)
    }

    @Test
    fun manifest_anyDeviationIsNull() {
        assertNull(MirrorPlan.decodeManifest(null))
        assertNull(MirrorPlan.decodeManifest(""))
        assertNull(MirrorPlan.decodeManifest("v2\nsynced\t1\n")) // unknown version
        assertNull(MirrorPlan.decodeManifest("v1\nsynced\tabc\n")) // bad number
        assertNull(MirrorPlan.decodeManifest("v1\nfile\t1\t2\n")) // 3 parts, need 4
        assertNull(MirrorPlan.decodeManifest("v1\nfile\t1\t2\t\n")) // empty relPath
        assertNull(MirrorPlan.decodeManifest("v1\nsynced\t1\nbogus\tx\n")) // unknown record
        assertNull(MirrorPlan.decodeManifest("v1\nfile\t1\t2\ta.md")) // no synced record
    }

    @Test
    fun nextManifest_keepsNewestThree() {
        val existing = listOf(
            "20261029-090000", "20261029-100000", "20261029-110000",
            "20261029-120000", "20261029-130000",
        )
        val m = MirrorPlan.nextManifest(
            nowMs = 1_730_000_000_000,
            entries = emptyList(),
            existingPoints = existing,
            newPointName = "20261029-140000",
        )
        // Newest three of six survive; four oldest dropped.
        assertEquals(
            listOf("20261029-140000", "20261029-130000", "20261029-120000"),
            m.previousPoints,
        )
        assertEquals(1_730_000_000_000, m.syncedAtMs)
    }

    @Test
    fun nextManifest_noPointKeepsExisting() {
        val existing = listOf("20261029-100000", "20261029-090000", "20261029-080000", "20261029-070000")
        val m = MirrorPlan.nextManifest(1L, emptyList(), existing, newPointName = null)
        // No new point → no rotation either: a pure-addition sync must not
        // consume a previous/ slot.
        assertEquals(listOf("20261029-100000", "20261029-090000", "20261029-080000"), m.previousPoints)
    }
}
