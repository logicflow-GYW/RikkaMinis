package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * [FIX-1028 / B-12] The rotation ordering key: name stamp in, "never delete the
 * one whose age is unknown / the one I just wrote" out.
 *
 * Worlds this pins (both are real, both were silent):
 *  - W7 clock rollback: the file written a moment ago carries an OLDER stamp
 *    than the one already on disk. mtime ordering then made rotation delete the
 *    fresh copy — the only rollback point for the restore that just ran.
 *  - W5 server without `getlastmodified`: every remote entry is
 *    `Instant.EPOCH`, so "newest first" degraded to listing order and rotation
 *    evicted an arbitrary copy.
 */
class BackupRotationTest {

    private val prefix = "rikkaminis-snapshot-"

    private fun stampOf(text: String): Long {
        val fmt = SimpleDateFormat(BackupRotation.STAMP_PATTERN, Locale.US)
        return fmt.parse(text)!!.time
    }

    private fun file(dir: File, name: String, text: String = "x"): File =
        File(dir, name).apply { writeText(text) }

    private fun tempDir(): File =
        File.createTempFile("rotation", "").let { f ->
            f.delete()
            f.mkdirs()
            f
        }

    // ── stamp parsing ─────────────────────────────────────────────────────

    @Test
    fun `canonical second-precision name parses to its encoded time`() {
        val expected = stampOf("20261028-120000")
        assertEquals(expected, BackupRotation.stampMillis("${prefix}20261028-120000.json", prefix))
    }

    @Test
    fun `collision suffix after the stamp still parses`() {
        assertEquals(
            stampOf("20261028-120000"),
            BackupRotation.stampMillis("${prefix}20261028-120000-2.json", prefix),
        )
        assertEquals(
            stampOf("20261028-120000"),
            BackupRotation.stampMillis("${prefix}20261028-120000-17.json", prefix),
        )
    }

    @Test
    fun `minute-precision local export name parses`() {
        assertEquals(
            stampOf("20260804-153000"),
            BackupRotation.stampMillis("rikkaminis-backup-20260804-1530.json", "rikkaminis-backup-"),
        )
    }

    @Test
    fun `foreign or malformed names have no stamp`() {
        assertNull(BackupRotation.stampMillis("${prefix}not-a-date.json", prefix))
        assertNull(BackupRotation.stampMillis("${prefix}20261308-000000.json", prefix)) // month 13
        assertNull(BackupRotation.stampMillis("${prefix}20261028-120000-junk.json", prefix))
        assertNull(BackupRotation.stampMillis("${prefix}20261028.json", prefix))
        assertNull(BackupRotation.stampMillis("other-prefix-20261028-120000.json", prefix))
        assertNull(BackupRotation.stampMillis("${prefix}20261028-120000.txt", prefix))
    }

    @Test
    fun `the stamp the writer mints is the stamp the reader parses`() {
        val now = stampOf("20261028-120000")
        val name = "$prefix${BackupRotation.stamp(now)}.json"
        assertEquals(now, BackupRotation.stampMillis(name, prefix))
    }

    // ── ordering ──────────────────────────────────────────────────────────

    @Test
    fun `newest first follows the name, not the mtime`() {
        val dir = tempDir()
        // Contradiction on purpose: the NEWER name carries the OLDER mtime
        // (this is exactly what a clock rollback produces).
        val newerName = file(dir, "${prefix}20261028-120000.json")
        val olderName = file(dir, "${prefix}20261027-120000.json")
        olderName.setLastModified(stampOf("20261101-000000"))
        newerName.setLastModified(stampOf("20260101-000000"))

        val ordered = BackupRotation.newestFirst(
            listOf(olderName, newerName),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
        )
        assertEquals(listOf(newerName.name, olderName.name), ordered.map { it.name })
    }

    @Test
    fun `unstamped entries sort last and never displace stamped ones`() {
        val dir = tempDir()
        val stamped = file(dir, "${prefix}20261001-120000.json")
        val foreign = file(dir, "${prefix}whatever.json")
        foreign.setLastModified(stampOf("20261231-000000"))

        val ordered = BackupRotation.newestFirst(
            listOf(foreign, stamped),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
        )
        assertEquals(listOf(stamped.name, foreign.name), ordered.map { it.name })
    }

    // ── deletion ──────────────────────────────────────────────────────────

    @Test
    fun `rotation deletes the oldest by NAME even when mtime says otherwise`() {
        val dir = tempDir()
        val newest = file(dir, "${prefix}20261028-120000.json")
        val oldest = file(dir, "${prefix}20261027-120000.json")
        oldest.setLastModified(stampOf("20261231-000000")) // looks newest by mtime
        newest.setLastModified(stampOf("20260101-000000"))

        val victims = BackupRotation.rotationVictims(
            items = listOf(newest, oldest),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
            keep = 1,
        )
        assertEquals(listOf(oldest.name), victims.map { it.name })
    }

    @Test
    fun `unstamped file is never a victim`() {
        val dir = tempDir()
        val foreign = file(dir, "${prefix}fetched-copy.json")
        val older = file(dir, "${prefix}20260101-120000.json")
        val newest = file(dir, "${prefix}20261028-120000.json")

        val victims = BackupRotation.rotationVictims(
            items = listOf(foreign, older, newest),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
            keep = 1,
        )
        assertEquals(listOf(older.name), victims.map { it.name })
    }

    @Test
    fun `protected file is never a victim even when it sorts last`() {
        val dir = tempDir()
        // The rolled-back-clock write: an OLD name on the file we just wrote.
        val justWritten = file(dir, "${prefix}20200101-000000.json")
        val existing = file(dir, "${prefix}20261028-120000.json")

        val victims = BackupRotation.rotationVictims(
            items = listOf(justWritten, existing),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
            keep = 1,
            protect = { it.name == justWritten.name },
        )
        assertTrue("nothing may be deleted when the fresh copy is the oldest-named", victims.isEmpty())
    }

    @Test
    fun `keep zero makes every stamped entry a victim`() {
        val dir = tempDir()
        val a = file(dir, "${prefix}20260101-120000.json")
        val b = file(dir, "${prefix}20261028-120000.json")
        val foreign = file(dir, "${prefix}foreign.json")
        val victims = BackupRotation.rotationVictims(
            items = listOf(a, b, foreign),
            name = { it.name },
            key = { BackupRotation.stampMillis(it.name, prefix) },
            keep = 0,
        )
        assertEquals(setOf(a.name, b.name), victims.map { it.name }.toSet())
    }

    @Test
    fun `writer names and reader key stay in sync for snapshots`() {
        // A writer that re-spelled the format would make every snapshot
        // "age unknown" — and an unknown age is never rotated away, so the
        // snapshot directory would grow forever with nothing deleting it.
        val now = System.currentTimeMillis()
        val name = ConfigBackup.snapshotFileName(now)
        val parsed = ConfigBackup.snapshotStampMillis(File(name))
        assertTrue(
            "snapshot writer's name must be readable by its own ordering key: $name",
            parsed != null && Math.abs(parsed - now) < 1000,
        )
        // Display falls back to mtime only for a name it cannot parse.
        assertEquals(parsed!!, ConfigBackup.snapshotDisplayMillis(File(name)))
    }
}
