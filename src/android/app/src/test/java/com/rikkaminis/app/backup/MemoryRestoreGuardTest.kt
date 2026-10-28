package com.rikkaminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FIX-1028B / L9033] The memoryFiles restore guard.
 *
 * Real `java.io.File` in a temp dir, real `org.json` array, the production
 * decision function and the production Stage 6 body — nothing is re-implemented
 * here. Every expected value below is a literal (a millisecond epoch, a file's
 * text, a skipped line): deriving them from the code under test is how a
 * mutation gets to look green.
 *
 * The world being pinned: memory is append-only user data (GLOBAL.md, one
 * `YYYY-MM-DD.md` per day, MEMORY-ROLLUP.md), while importing a backup used to
 * be a blind whole-file overwrite. Restoring yesterday's backup therefore
 * truncated today's daily log back to yesterday, and reported success.
 */
class MemoryRestoreGuardTest {

    private val backupStamp = 1_700_000_000_000L

    private fun tempDir(): File =
        File.createTempFile("memory-guard", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    private fun fileAt(dir: File, name: String, content: String, mtime: Long): File =
        File(dir, name).apply {
            writeText(content)
            // setLastModified can be refused (read-only fs / coarse timestamp)
            // — assert it took, otherwise the fixture silently tests nothing.
            assertTrue("fixture: could not set mtime on $name", setLastModified(mtime))
        }

    private fun section(vararg entries: Pair<String, String>): JSONArray =
        JSONArray().apply {
            for ((name, content) in entries) {
                put(JSONObject().apply {
                    put("name", name)
                    put("content", content)
                })
            }
        }

    private fun apply(
        dir: File,
        array: JSONArray,
        createdAt: Long = backupStamp,
        saveFile: ((String, String) -> Unit)? = null,
    ): Pair<Int, MutableList<String>> {
        val skipped = ArrayList<String>()
        val sink = saveFile ?: { name: String, content: String ->
            File(dir, name).writeText(content)
        }
        val written = restoreMemoryFiles(
            section = array,
            backupCreatedAt = createdAt,
            localFile = { name -> File(dir, name) },
            saveFile = { name, content -> sink(name, content) },
            skipped = skipped,
        )
        return written to skipped
    }

    // -- decideMemoryRestore: the four outcomes plus the two undecidables --

    @Test
    fun absentLocalCopyIsRestored() {
        val d = decideMemoryRestore("GLOBAL.md", localExists = false, localLastModified = 0L, backupCreatedAt = backupStamp)
        assertEquals(MemoryRestoreDecision.Restore, d)
    }

    @Test
    fun olderLocalCopyIsOverwritten() {
        val d = decideMemoryRestore("GLOBAL.md", localExists = true, localLastModified = backupStamp - 1L, backupCreatedAt = backupStamp)
        assertEquals(MemoryRestoreDecision.Restore, d)
    }

    @Test
    fun sameMillisecondCountsAsTheExportsOwnRead() {
        val d = decideMemoryRestore("GLOBAL.md", localExists = true, localLastModified = backupStamp, backupCreatedAt = backupStamp)
        assertEquals(MemoryRestoreDecision.Restore, d)
    }

    @Test
    fun newerLocalCopyIsKeptWithAVisibleReason() {
        val d = decideMemoryRestore("2026-10-28.md", localExists = true, localLastModified = backupStamp + 1L, backupCreatedAt = backupStamp)
        assertEquals(
            MemoryRestoreDecision.KeepLocal("memory file \"2026-10-28.md\": local copy is newer than the backup, kept"),
            d,
        )
    }

    @Test
    fun unreadableLocalMtimeFailsClosed() {
        val d = decideMemoryRestore("GLOBAL.md", localExists = true, localLastModified = 0L, backupCreatedAt = backupStamp)
        assertEquals(
            MemoryRestoreDecision.KeepLocal("memory file \"GLOBAL.md\": local copy's write time is unreadable, kept"),
            d,
        )
    }

    @Test
    fun payloadWithoutTimestampFailsClosed() {
        val d = decideMemoryRestore("GLOBAL.md", localExists = true, localLastModified = backupStamp - 1L, backupCreatedAt = 0L)
        assertEquals(
            MemoryRestoreDecision.KeepLocal("memory file \"GLOBAL.md\": backup carries no timestamp to order against, kept"),
            d,
        )
    }

    // -- restoreMemoryFiles: the Stage 6 body, end to end --

    @Test
    fun newerLogSurvivesAnOlderBackupWhileUntouchedFilesAreRestored() {
        val dir = tempDir()
        fileAt(dir, "GLOBAL.md", "local-global-old", backupStamp - 5_000L)
        fileAt(dir, "2026-10-28.md", "local-today-NEW", backupStamp + 60_000L)
        // 2026-10-27.md deliberately absent locally: the backup is its only owner.

        val (written, skipped) = apply(
            dir,
            section(
                "GLOBAL.md" to "backup-global",
                "2026-10-28.md" to "backup-today-STALE",
                "2026-10-27.md" to "backup-yesterday",
            ),
        )

        assertEquals(2, written)
        assertEquals(listOf("memory file \"2026-10-28.md\": local copy is newer than the backup, kept"), skipped)
        assertEquals("backup-global", File(dir, "GLOBAL.md").readText())
        assertEquals("local-today-NEW", File(dir, "2026-10-28.md").readText())
        assertEquals("backup-yesterday", File(dir, "2026-10-27.md").readText())
    }

    @Test
    fun keptFilesAreNotCountedAsImported() {
        val dir = tempDir()
        fileAt(dir, "2026-10-28.md", "local-today-NEW", backupStamp + 60_000L)

        val (written, skipped) = apply(dir, section("2026-10-28.md" to "backup-today-STALE"))

        // "1 memory file restored" would be a lie: nothing was written.
        assertEquals(0, written)
        assertEquals(1, skipped.size)
        assertEquals("local-today-NEW", File(dir, "2026-10-28.md").readText())
    }

    @Test
    fun unstatableLocalCopyIsKeptNotBlindlyOverwritten() {
        val dir = tempDir()
        val real = fileAt(dir, "GLOBAL.md", "local-global-live", backupStamp - 5_000L)
        // A File that admits to existing but refuses to report a timestamp is
        // the shape of a stat that failed under us (ENOENT race / unreadable
        // inode). The guard must treat "cannot tell" as "do not touch".
        val statless = object : File(real.path) {
            override fun exists(): Boolean = true
            override fun lastModified(): Long = 0L
        }
        val skipped = ArrayList<String>()
        val written = restoreMemoryFiles(
            section = section("GLOBAL.md" to "backup-global"),
            backupCreatedAt = backupStamp,
            localFile = { statless },
            saveFile = { name, content -> File(dir, name).writeText(content) },
            skipped = skipped,
        )

        assertEquals(0, written)
        assertEquals(listOf("memory file \"GLOBAL.md\": local copy's write time is unreadable, kept"), skipped)
        assertEquals("local-global-live", File(dir, "GLOBAL.md").readText())
    }

    @Test
    fun writeFailureIsReportedNotThrown() {
        // The write path is still the old try/catch: an IO failure on one file
        // becomes a skipped line and the loop continues, never a fatal import.
        // (An absent local copy means the backup owns the content, so the write
        // is attempted — here it fails on purpose.)
        val dir = tempDir()
        val skipped = ArrayList<String>()
        val written = restoreMemoryFiles(
            section = section("2026-10-28.md" to "backup-today-STALE"),
            backupCreatedAt = backupStamp,
            localFile = { File(dir, "2026-10-28.md") },
            saveFile = { _, _ -> throw IllegalStateException("must not be reached for an absent file") },
            skipped = skipped,
        )
        // Absent local copy ⇒ the backup IS the owner, so the write is attempted
        // (and here it fails on purpose). What must not happen is a crash.
        assertEquals(0, written)
        assertEquals(listOf("memory file \"2026-10-28.md\": must not be reached for an absent file"), skipped)
    }

    @Test
    fun payloadWithoutCreatedAtKeepsEveryExistingFile() {
        val dir = tempDir()
        fileAt(dir, "GLOBAL.md", "local-global", backupStamp - 5_000L)
        fileAt(dir, "2026-10-28.md", "local-today", backupStamp - 5_000L)

        val (written, skipped) = apply(
            dir,
            section("GLOBAL.md" to "backup-global", "2026-10-28.md" to "backup-today"),
            createdAt = 0L,
        )

        assertEquals(0, written)
        assertEquals(2, skipped.size)
        assertTrue(skipped[0].endsWith("backup carries no timestamp to order against, kept"))
        assertEquals("local-global", File(dir, "GLOBAL.md").readText())
        assertEquals("local-today", File(dir, "2026-10-28.md").readText())
    }

    @Test
    fun wholeFileIsRestoredWhenLocalCopyIsOlderEvenIfItIsLonger() {
        // The guard is about time, not size: a locally *shorter* file that is
        // also older is still the export's own content.
        val dir = tempDir()
        fileAt(dir, "2026-10-26.md", "short", backupStamp - 1_000L)

        val (written, skipped) = apply(
            dir,
            section("2026-10-26.md" to "a much longer backup body\nwith several entries\n"),
        )

        assertEquals(1, written)
        assertTrue(skipped.isEmpty())
        assertEquals("a much longer backup body\nwith several entries\n", File(dir, "2026-10-26.md").readText())
    }

    // -- Behaviour that must NOT change (pre-existing contract) --

    @Test
    fun unsafeAndNamelessEntriesAreStillRejected() {
        val dir = tempDir()
        val array = JSONArray().apply {
            put(JSONObject().apply { put("content", "x") })                       // no name
            put(JSONObject().apply { put("name", "../escape.md"); put("content", "x") })
            put(JSONObject().apply { put("name", "sub/dir.md"); put("content", "x") })
        }

        val (written, skipped) = apply(dir, array)

        assertEquals(0, written)
        assertEquals(
            listOf(
                "memory file #1: missing name",
                "memory file \"../escape.md\": unsafe name, skipped",
                "memory file \"sub/dir.md\": unsafe name, skipped",
            ),
            skipped,
        )
        assertFalse(File(dir, "../escape.md").exists())
    }

    @Test
    fun blankNameIsTrimmedBeforeValidation() {
        val dir = tempDir()
        val (written, skipped) = apply(dir, section("  GLOBAL.md  " to "backup-global"))
        assertEquals(1, written)
        assertTrue(skipped.isEmpty())
        assertEquals("backup-global", File(dir, "GLOBAL.md").readText())
    }
}
