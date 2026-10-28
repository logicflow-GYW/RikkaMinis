package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FIX-1028 / B-12] Pre-restore snapshots: the ONE rollback point layout
 * (`SNAPSHOT_KEEP = 1`) ordered and pruned by the stamp in the file name.
 *
 * The failure this pins, in full: a restore after a clock rollback streams a
 * snapshot whose mtime looks OLDER than the file already on disk. mtime
 * ordering then (a) showed the wrong row as "newest" — and the fatal-restore
 * dialog's one-tap rollback takes `snapshotFiles.firstOrNull()` — and
 * (b) pruned the file that had just been written, deleting the rollback point
 * for the restore in progress. With KEEP=1 there was no second chance.
 *
 * The clock is simulated with the only lever a JVM test has: file names carry
 * the stamp, so a name "from the future" (or from before) is exactly what a
 * skewed clock writes.
 */
class ConfigBackupSnapshotRotationTest {

    private val prefix = "rikkaminis-snapshot-"

    private fun tempDir(): File =
        File.createTempFile("snapshots", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    private fun snapshot(dir: File, stamp: String, body: String = "{}"): File =
        File(dir, "$prefix$stamp.json").apply { writeText(body) }

    @Test
    fun `list orders by the name stamp even when mtime says the opposite`() {
        val dir = tempDir()
        val newer = snapshot(dir, "20261028-120000")
        val older = snapshot(dir, "20261027-120000")
        // A touch / a restore from another device can rewrite mtime freely.
        newer.setLastModified(0L)
        older.setLastModified(System.currentTimeMillis())

        assertEquals(
            listOf(newer.name, older.name),
            ConfigBackup.listSnapshots(dir).map { it.name },
        )
    }

    @Test
    fun `a fresh snapshot is never pruned by a snapshot whose name is newer`() {
        val dir = tempDir()
        // The device's clock was ahead when this one was written; now the clock
        // is back to normal, so the file we are ABOUT to write has the
        // older-looking name (world W7, or simply a clock that was corrected).
        val previous = snapshot(dir, "20300101-000000", "{\"old\":true}")

        val written = ConfigBackup.writeSnapshot(dir, "{\"fresh\":true}")

        assertTrue("the fresh snapshot must exist", written.isFile)
        assertTrue("the rollback point written first must survive", previous.isFile)
        assertEquals(
            "KEEP=1 counts the file just written, and the just-written file is never a victim",
            2,
            ConfigBackup.listSnapshots(dir).size,
        )
    }

    @Test
    fun `a snapshot whose name has no stamp is kept and sorted last`() {
        val dir = tempDir()
        val stampedOld = snapshot(dir, "20260101-000000")
        val foreign = snapshot(dir, "imported-from-old-build")

        val written = ConfigBackup.writeSnapshot(dir, "{\"fresh\":true}")

        // Age cannot be established for `foreign` ⇒ it is not a victim, even
        // though it sits past the keep window; it sorts last.
        assertEquals(
            listOf(written.name, foreign.name),
            ConfigBackup.listSnapshots(dir).map { it.name },
        )
        assertTrue("unstamped snapshot must never be rotated away", foreign.isFile)
        assertTrue("the stamped older one is the victim that gets reclaimed", !stampedOld.exists())
    }

    @Test
    fun `display time comes from the name so it cannot contradict the order`() {
        val dir = tempDir()
        val file = snapshot(dir, "20261028-120000")
        file.setLastModified(0L) // e.g. copied without preserving times
        val shown = ConfigBackup.snapshotDisplayMillis(file)
        assertNotNull(ConfigBackup.snapshotStampMillis(file))
        assertEquals(ConfigBackup.snapshotStampMillis(file), shown)
        // Unparseable name: falls back to mtime rather than inventing a time.
        val foreign = snapshot(dir, "whatever")
        foreign.setLastModified(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, ConfigBackup.snapshotDisplayMillis(foreign))
    }

    @Test
    fun `same-second snapshots keep the collision suffix and order newest first`() {
        val dir = tempDir()
        // allocateSnapshotFile mints `-2` for the second write inside one second.
        val first = snapshot(dir, "20261028-120000")
        val second = snapshot(dir, "20261028-120000-2")
        assertEquals(
            listOf(second.name, first.name),
            ConfigBackup.listSnapshots(dir).map { it.name },
        )
    }
}
