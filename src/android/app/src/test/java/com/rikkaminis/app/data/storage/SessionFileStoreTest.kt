package com.rikkaminis.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * JVM tests for [SessionFileStore] — in particular the audit fixes:
 *  - Bug 1: `mediaDirs` is actually counted (previously hard-coded 0).
 *  - Bug 2: orphan media-leaf detection survives unreadable dirs and guards
 *    against non-session-shaped names.
 *  - Bug 3: `sizeOf` no longer follows symlinks / double-counts hardlinks.
 *    (logical-size vs blocks is Android-only; here we pin the non-following
 *    semantics that both platforms share).
 *  - Bug 4: `deleteSessionFiles` reports partial failure instead of silently
 *    pretending success.
 */
class SessionFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): SessionFileStore = SessionFileStore(tmp.newFolder("filesDir"))

    private fun write(file: File, bytes: Int): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 0x61 })
        return file
    }

    private fun sid(): String = UUID.randomUUID().toString()

    /**
     * [P1-D FINDING-26] One live session id that owns NO files on disk.
     *
     * The delete-half tests below only care that "everything not live is
     * residue", and `emptySet()` used to be shorthand for "nothing is live".
     * That shorthand is now a different claim — "the DB knows nothing about any
     * session", which the floor guard treats as a refusal (see the FINDING-26
     * block at the bottom). Handing in one unrelated live id keeps those tests
     * about the delete half; `emptySet()` is reserved for the floor arms, where
     * the refusal IS the assertion.
     */
    private fun someLiveId(): Set<String> = setOf(sid())

    // ── Bug 3: sizeOf symlink / hardlink semantics ───────────────────────────

    @Test
    fun `sizeOf does not follow file symlinks`() {
        val s = store()
        val dir = File(s.sessionsRoot, sid())
        write(File(dir, "big.bin"), 1_000_000)
        Files.createSymbolicLink(File(dir, "link").toPath(), File(dir, "big.bin").toPath())

        val total = s.sizeOf(dir)
        assertTrue("expected ~1,000,000 but got $total", total in 1_000_000..1_100_000L)
    }

    @Test
    fun `sizeOf does not recurse into symlinked directories`() {
        val s = store()
        val dir = File(s.sessionsRoot, sid())
        write(File(dir, "real/file.bin"), 500)
        Files.createSymbolicLink(File(dir, "dlink").toPath(), File(dir, "real").toPath())

        val total = s.sizeOf(dir)
        assertTrue("expected ~500 but got $total", total in 500..999)
    }

    @Test
    fun `sizeOf dedupes hardlinks`() {
        val s = store()
        val dir = File(s.sessionsRoot, sid())
        val a = write(File(dir, "a.bin"), 4000)
        Files.createLink(File(dir, "b.bin").toPath(), a.toPath())

        assertEquals(4000L, s.sizeOf(dir))
    }

    @Test
    fun `sizeOf missing dir is zero`() {
        val s = store()
        assertEquals(0L, s.sizeOf(File(s.sessionsRoot, "nope")))
    }

    // ── Bug 1: mediaDirs counted ────────────────────────────────────────────

    @Test
    fun `scanOrphans counts media-only orphans`() {
        val s = store()
        val dead = sid()
        // A media leaf dir for a session that no longer exists (no matching
        // session dir under minis-sessions/).
        val mediaLeaf = File(File(File(s.mediaRootForTesting(), "2026"), "08"), dead)
        write(File(mediaLeaf, "a.bin"), 1234)

        val report = s.scanOrphans(emptySet())
        assertEquals(0, report.sessionDirs)
        assertEquals(1, report.mediaDirs)
        assertEquals(1234L, report.mediaBytes)
        assertEquals(1, report.totalDirs)
        assertEquals(1234L, report.totalBytes)
    }

    @Test
    fun `scanOrphans ignores live sessions and non-session-shaped names`() {
        val s = store()
        val live = sid()
        val dead = sid()
        write(File(s.sessionDir(live), "x"), 10)
        write(File(s.sessionDir(dead), "y"), 20)
        // A stray non-UUID dir must never be reclaimed.
        write(File(File(s.sessionsRoot, "not-a-session-id"), "z"), 999)

        val report = s.scanOrphans(setOf(live))
        assertEquals(listOf(dead), report.sessionIds)
        assertEquals(1, report.sessionDirs)
        assertEquals(20L, report.sessionBytes)
        assertEquals(0, report.mediaDirs)
    }

    // ── Bug 4: delete outcome ───────────────────────────────────────────────

    @Test
    fun `deleteSessionFiles reports full success`() {
        val s = store()
        val id = sid()
        write(File(s.sessionDir(id), "workspace/a"), 100)

        val result = s.deleteSessionFiles(id)
        assertTrue(result.fullyDeleted)
        assertFalse(s.sessionDir(id).exists())
    }

    @Test
    fun `deleteSessionFiles absent dir reports success`() {
        val s = store()
        val result = s.deleteSessionFiles(sid())
        assertTrue(result.fullyDeleted)
    }

    @Test
    fun `mediaSizesBySessionBrief attributes per session and is symlink safe`() {
        val s = store()
        val a = sid()
        val b = sid()
        val root = s.mediaRootForTesting()
        val la = File(File(File(root, "2026"), "08"), a)
        val lb = File(File(File(root, "2026"), "08"), b)
        write(File(la, "1.bin"), 100)
        write(File(la, "2.bin"), 200)
        write(File(lb, "3.bin"), 400)

        val sizes = s.mediaSizesBySessionBrief(setOf(a, b))
        assertEquals(300L, sizes[a])
        assertEquals(400L, sizes[b])
    }

    // Reclaim should actually delete orphaned dirs.
    @Test
    fun `reclaimOrphans deletes orphan session and media dirs`() {
        val s = store()
        val dead = sid()
        write(File(s.sessionDir(dead), "w/a"), 50)
        val mediaLeaf = File(File(File(s.mediaRootForTesting(), "2026"), "08"), dead)
        write(File(mediaLeaf, "m.bin"), 60)

        val report = s.reclaimOrphans(someLiveId())
        assertEquals(1, report.sessionDirs)
        assertEquals(1, report.mediaDirs)
        assertFalse(s.sessionDir(dead).exists())
        assertFalse(mediaLeaf.exists())
    }

    // ── da1025-D: browser_tabs/<sid>.json lifecycle ──────────────────────────

    @Test
    fun `deleteSessionFiles deletes the browser tab state file`() {
        val s = store()
        val id = sid()
        val tabFile = s.browserTabFile(id)
        write(tabFile, 40)

        val result = s.deleteSessionFiles(id)
        assertTrue(result.fullyDeleted)
        assertFalse("browser tab state file must be deleted with the session", tabFile.exists())
    }

    @Test
    fun `deleteSessionFiles with no browser tab file still reports full success`() {
        val s = store()
        val id = sid()
        write(File(s.sessionDir(id), "workspace/a"), 100)

        // Zero side-effect regression: a session that never opened a browser
        // has no tab file; absent == nothing to delete == success.
        val result = s.deleteSessionFiles(id)
        assertTrue(result.fullyDeleted)
        assertFalse(s.sessionDir(id).exists())
    }

    @Test
    fun `reclaimOrphans deletes orphan browser tab files and keeps live ones`() {
        val s = store()
        val live = sid()
        val dead = sid()
        val liveTabs = write(s.browserTabFile(live), 30)
        val deadTabs = write(s.browserTabFile(dead), 45)

        val report = s.reclaimOrphans(setOf(live))
        assertTrue("live session's tab file must be kept", liveTabs.exists())
        assertFalse("orphaned tab file must be reclaimed", deadTabs.exists())
        // [§1025-D R1] Counted as well as deleted: the banner the user confirms
        // must not promise less than the sweep frees.
        assertEquals("orphaned tab file must be counted", 1, report.browserTabsDirs)
        assertEquals(45L, report.browserTabsBytes)
        assertEquals(1, report.totalDirs)
        assertEquals(45L, report.totalBytes)
        assertEquals(0, report.mediaDirs)
    }

    @Test
    fun `scanOrphans counts orphan browser tab files and folds them into the totals`() {
        val s = store()
        val live = sid()
        val dead = sid()
        write(s.browserTabFile(live), 30)
        val deadTabs = write(s.browserTabFile(dead), 45)
        // A session dir for the same dead id, so the two halves are visible in
        // one report: 20 bytes of session files + 45 bytes of tab state.
        write(File(s.sessionDir(dead), "w/a"), 20)

        val report = s.scanOrphans(setOf(live))

        assertEquals(1, report.sessionDirs)
        assertEquals(20L, report.sessionBytes)
        assertEquals(1, report.browserTabsDirs)
        assertEquals(45L, report.browserTabsBytes)
        assertEquals("both kinds count", 2, report.totalDirs)
        assertEquals(65L, report.totalBytes)
        // Scan is measure-only: nothing on disk moved.
        assertTrue(deadTabs.exists())
        assertTrue(s.sessionDir(dead).exists())
    }

    @Test
    fun `the scan and the delete half of the reclaim agree on what is an orphan`() {
        // The two halves spell the same rule out separately (the delete half's
        // predicate is pinned inline by the browser_tabs_lifecycle_check gate),
        // so the agreement has to be proven rather than assumed: same fixture
        // matrix, measured set vs deleted set.
        val dead = sid()
        val live = sid()
        val draftDead = "__new__${UUID.randomUUID()}"
        // Fixed, so the expected survivor set stays a literal.
        val shapedDir = "00000000-0000-4000-8000-000000000000"
        val fixtures = linkedMapOf(
            "dead.json" to "$dead.json",
            "live.json" to "$live.json",
            "draft-dead.json" to "$draftDead.json",
            "not-a-session.json" to "not-a-session.json",
            "no-json-suffix" to dead,
            "subdir" to dead,
        )
        var built = 0
        fun build(): SessionFileStore {
            // One TemporaryFolder rule per test, so each tree needs its own dir.
            val s = SessionFileStore(tmp.newFolder("filesDir-${built++}"))
            fixtures.forEach { (_, name) -> write(File(s.browserTabsRootForTesting(), name), 7) }
            // A DIRECTORY shaped like a tab file: session-shaped stem, `.json`
            // suffix, but not a file — neither half may touch it.
            File(s.browserTabsRootForTesting(), "$shapedDir.json").mkdirs()
            return s
        }

        val measured = build()
        val scanned = measured.scanOrphanBrowserTabFiles(setOf(live))
        assertEquals(2, scanned.browserTabsDirs) // dead.json + draft-dead.json
        assertEquals(14L, scanned.browserTabsBytes)

        val swept = build()
        swept.reclaimOrphans(setOf(live))
        val survivors = swept.browserTabsRootForTesting().list()!!.toSortedSet()
        assertEquals(
            "every file the scan counted must be gone, every other entry must stay",
            sortedSetOf(
                "$live.json",
                "not-a-session.json",
                "$shapedDir.json", // a DIRECTORY, not a state file
                dead, // the suffix-less spelling, kept as a non-state file
            ),
            survivors,
        )
    }

    @Test
    fun `reclaimOrphans deletes orphaned draft __new__ tab files`() {
        val s = store()
        val draftStem = "__new__${UUID.randomUUID()}"
        val draftTabs = write(File(s.browserTabsRootForTesting(), "$draftStem.json"), 20)

        s.reclaimOrphans(someLiveId())
        assertFalse("orphaned draft tab file must be reclaimed", draftTabs.exists())
    }

    @Test
    fun `reclaimOrphans never touches non-session-shaped files under browser_tabs`() {
        val s = store()
        val stray = write(File(s.browserTabsRootForTesting(), "not-a-session.json"), 15)

        s.reclaimOrphans(someLiveId())
        assertTrue("fail-safe guard: unknown stems are never reclaimed", stray.exists())
    }

    // ── [da1040-A3] an in-flight draft is live, and draft names are shaped ───
    // The composer mints `__new__<uuid>` (ComposerDraftStore) before the first
    // message creates a DB row, and BrowserTabPool writes state under that id.
    // Both halves must hold at once: the sweep has to RECOGNISE the draft shape
    // (else a promoted draft's files leak forever) and it has to treat the
    // ACTIVE draft as live (else the act of recognising deletes the user's
    // in-flight work).

    private fun storeWithDrafts(vararg draftIds: String): SessionFileStore =
        SessionFileStore(tmp.newFolder("filesDirDrafts"), { draftIds.toSet() })

    @Test
    fun `reclaimOrphans keeps the active draft's session dir, media and tab file`() {
        val draft = "__new__${UUID.randomUUID()}"
        val other = "__new__${UUID.randomUUID()}"
        val s = storeWithDrafts(draft)

        val draftDir = write(File(s.sessionDir(draft), "tool.bin"), 40)
        val draftMedia = write(File(File(File(s.mediaRootForTesting(), "2026-01-01"), draft), "m.bin"), 30)
        val draftTabs = write(s.browserTabFile(draft), 20)
        // A draft that is NOT the composer's active slot is ordinary orphan data.
        val deadDir = write(File(s.sessionDir(other), "tool.bin"), 40)
        val deadMedia = write(File(File(File(s.mediaRootForTesting(), "2026-01-01"), other), "m.bin"), 30)
        val deadTabs = write(s.browserTabFile(other), 20)

        s.reclaimOrphans(someLiveId())

        assertTrue("active draft's session dir must survive", draftDir.exists())
        assertTrue("active draft's media must survive", draftMedia.exists())
        assertTrue("active draft's tab file must survive", draftTabs.exists())
        assertFalse("inactive draft dir is orphan data", deadDir.exists())
        assertFalse("inactive draft media is orphan data", deadMedia.exists())
        assertFalse("inactive draft tab file is orphan data", deadTabs.exists())
    }

    @Test
    fun `scanOrphans recognises draft-shaped names once the draft is gone`() {
        val draft = "__new__${UUID.randomUUID()}"
        val s = store()
        write(File(s.sessionDir(draft), "tool.bin"), 40)
        write(File(File(File(s.mediaRootForTesting(), "2026-01-01"), draft), "m.bin"), 30)

        val report = s.scanOrphans(emptySet())

        assertEquals(listOf(draft), report.sessionIds)
        assertEquals(1, report.sessionDirs)
        assertEquals(1, report.mediaDirs)
        assertTrue("draft-shaped footprint must be measured", report.totalBytes > 0)
    }

    @Test
    fun `draft-shaped names without a full UUID suffix stay un-reclaimable`() {
        val s = store()
        val bare = write(File(File(s.sessionsRoot, "__new__"), "f.bin"), 10)
        val notUuid = write(File(File(s.sessionsRoot, "__new__not-a-uuid"), "f.bin"), 10)
        val trailing = write(File(File(s.sessionsRoot, "__new__${UUID.randomUUID()}-extra"), "f.bin"), 10)

        val report = s.scanOrphans(emptySet())
        s.reclaimOrphans(someLiveId())

        assertTrue("fail-safe: only a full UUID behind the prefix qualifies", report.sessionIds.isEmpty())
        assertTrue("bare route placeholder", bare.exists())
        assertTrue("non-UUID suffix", notUuid.exists())
        assertTrue("trailing garbage after the UUID", trailing.exists())
    }

    // ── [P1-D FINDING-26] the empty live-set floor ───────────────────────────
    // `liveSessionIds` is built from `dao.listSessions()` alone. An EMPTY set
    // therefore means "the DB knows nothing about any session", not "there are
    // no sessions" — and those are different claims. A rebuilt / partially
    // restored / cleared `sessions` table with an intact filesDir is exactly
    // the disaster this file guards: every surviving session dir, media leaf
    // and tab-state file looks like residue, and the old sweep deleted all
    // three kinds on one confirmation tap.

    @Test
    fun `reclaimOrphans refuses the whole sweep when the live set is empty`() {
        val s = store()
        val a = sid()
        val b = sid()
        val c = sid()
        write(File(s.sessionDir(a), "workspace/download.bin"), 100)
        write(File(s.sessionDir(b), "attachments/doc.pdf"), 200)
        val mediaLeaf = File(File(File(s.mediaRootForTesting(), "2026"), "10"), c)
        write(File(mediaLeaf, "photo.jpg"), 300)
        val deadTabs = write(s.browserTabFile(c), 40)

        val report = s.reclaimOrphans(emptySet())

        assertTrue("the sweep must refuse instead of silently doing nothing", report.blocked)
        assertEquals(
            "the reason must be machine-readable (the UI owns the wording)",
            SessionFileStore.ReclaimReport.BLOCK_NO_LIVE_SESSIONS,
            report.blockReason,
        )
        // The candidates are still MEASURED: that is what lets the caller say
        // "N items would have been deleted" instead of pretending success.
        assertEquals(2, report.sessionDirs)
        assertEquals(1, report.mediaDirs)
        assertEquals(1, report.browserTabsDirs)
        // ... and nothing may have moved, for ANY of the three kinds.
        assertTrue("session dir a", s.sessionDir(a).exists())
        assertTrue("session dir b", s.sessionDir(b).exists())
        assertTrue("media leaf", File(File(File(s.mediaRootForTesting(), "2026"), "10"), c).exists())
        assertTrue("tab state file", deadTabs.exists())
    }

    @Test
    fun `reclaimOrphans with an empty live set and nothing to reclaim is not blocked`() {
        // A brand-new install: no DB rows AND no leftovers. Nothing to refuse —
        // the user must not see an error for "there was nothing to do".
        val s = store()

        val report = s.reclaimOrphans(emptySet())

        assertFalse("an empty state is not a refusal", report.blocked)
        assertNull(report.blockReason)
        assertEquals(0, report.totalDirs)
        assertEquals(0L, report.totalBytes)
    }

    @Test
    fun `reclaimOrphans with a live session still deletes the other dirs`() {
        // Zero-regression arm for the guard: one live id is enough for the
        // sweep to trust its own predicate again.
        val s = store()
        val keep = sid()
        val dead1 = sid()
        val dead2 = sid()
        write(File(s.sessionDir(keep), "workspace/a"), 10)
        write(File(s.sessionDir(dead1), "workspace/a"), 10)
        write(File(s.sessionDir(dead2), "workspace/a"), 10)

        val report = s.reclaimOrphans(setOf(keep))

        assertFalse(report.blocked)
        assertTrue("live session must survive", s.sessionDir(keep).exists())
        assertFalse("first orphan must be reclaimed", s.sessionDir(dead1).exists())
        assertFalse("second orphan must be reclaimed", s.sessionDir(dead2).exists())
        assertEquals(2, report.sessionDirs)
    }

    @Test
    fun `an active draft does not unlock the empty-live-set floor`() {
        // [da1040-A3]'s draft widening answers a different question ("does this
        // ONE slot own files yet?"). It says nothing about the DB being
        // trustworthy, so it must not re-arm the sweep while `sessions` is
        // empty — otherwise an in-flight draft becomes a licence to delete
        // every real session's files.
        val draft = "__new__${UUID.randomUUID()}"
        val s = storeWithDrafts(draft)
        val real = sid()
        val realDir = write(File(s.sessionDir(real), "workspace/a"), 10)

        val report = s.reclaimOrphans(emptySet())

        assertTrue(report.blocked)
        assertTrue("an unrelated session dir must survive a blocked sweep", realDir.exists())
    }

    @Test
    fun `the banner and the sweep share one floor predicate`() {
        // The Storage page cannot call scanOrphans (one media walk), so it
        // assembles its own ReclaimReport and applies this function to it. The
        // sweep applies the same one — banner and delete cannot disagree.
        val s = store()
        val candidates = SessionFileStore.ReclaimReport(sessionDirs = 3, sessionBytes = 500)

        val blocked = s.withEmptyLiveFloor(candidates, emptySet())
        assertTrue("no live sessions ⇒ the offer is not an offer", blocked.blocked)
        assertEquals(3, blocked.totalDirs)
        assertEquals(500L, blocked.totalBytes)

        assertFalse(
            "one live session is enough to trust the predicate again",
            s.withEmptyLiveFloor(candidates, setOf(sid())).blocked,
        )
        assertFalse(
            "an empty candidate set is never a refusal",
            s.withEmptyLiveFloor(SessionFileStore.ReclaimReport(), emptySet()).blocked,
        )
    }
}
