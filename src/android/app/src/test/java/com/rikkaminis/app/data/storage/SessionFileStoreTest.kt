package com.rikkaminis.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val report = s.reclaimOrphans(emptySet())
        assertEquals(1, report.sessionDirs)
        assertEquals(1, report.mediaDirs)
        assertFalse(s.sessionDir(dead).exists())
        assertFalse(mediaLeaf.exists())
    }
}
