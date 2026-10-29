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
    fun shouldWrite_throttlesInsideTheInterval() {
        val last = 1_000_000L
        // 9:59 inside the 10-minute window → wait.
        assertFalse(MirrorPlan.shouldWrite(last, last + 599_999L, true))
        // 10:00 exactly → go.
        assertTrue(MirrorPlan.shouldWrite(last, last + 600_000L, true))
    }

    @Test
    fun shouldWrite_clockRollbackIsNotTooRecent() {
        // now < last: "unknown age" must not be read as "just synced", or a
        // clock rollback would silence the mirror until the clock catches up.
        assertTrue(MirrorPlan.shouldWrite(5_000_000L, 1_000_000L, true))
    }

    @Test
    fun shouldWrite_intervalIsTheConstantTenMinutes() {
        // Literal: pins the constant itself, so bumping MIN_INTERVAL_MS is a
        // conscious test change, not a silent re-baseline.
        assertEquals(600_000L, MirrorPlan.MIN_INTERVAL_MS)
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
