package com.rikkaminis.app.data.repository

import com.rikkaminis.app.backup.ROLLUP_BACKUP_FILE
import com.rikkaminis.app.backup.memoryBackupEntries
import com.rikkaminis.app.backup.readMemoryFileForBackup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [FIX-E / ISSUE-09] Distilled memory (MEMORY-ROLLUP.md) is part of a backup.
 *
 * `listAllFiles()` excludes the rollup on purpose (settings list + `memory_get`
 * must not surface it), so packing `memoryFiles` from that listing alone meant
 * the one artifact holding every distilled rule never left the device: after a
 * transfer the daily logs were there and everything folded out of them was
 * gone. The same "not a user-editable daily log" class also has to be refused
 * by [MemoryRepository.deleteFile], alongside GLOBAL.md / SOUL.md.
 *
 * The literal "MEMORY-ROLLUP.md" here is the ground truth from the on-disk
 * contract (MemoryRollupEngine.ROLLUP_FILE) — deliberately not read back from
 * the code under test.
 */
class BackupMemoryRollupTest {

    private val tempDir = File(
        System.getProperty("java.io.tmpdir"),
        "mem-rollup-${UUID.randomUUID().toString().take(8)}"
    )
    private val memoryDir = File(tempDir, "minis-global/memory")

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun rollupBackupNameMatchesOnDiskContract() {
        assertEquals("MEMORY-ROLLUP.md", ROLLUP_BACKUP_FILE)
    }

    @Test
    fun memoryBackupEntries_includesRollupWhenPresent() {
        val files = mapOf(
            "GLOBAL.md" to "## global\n",
            "2026-10-16.md" to "<!-- 2026-10-16 09:00:00 -->\n## entry\n",
            "MEMORY-ROLLUP.md" to "## Rollup 2026-10-15\n- distilled rule\n",
        )
        val out = memoryBackupEntries(listOf("GLOBAL.md", "2026-10-16.md")) { files[it] }
        assertEquals(listOf("GLOBAL.md", "2026-10-16.md", "MEMORY-ROLLUP.md"), out.map { it.first })
        assertEquals("## Rollup 2026-10-15\n- distilled rule\n", out.last().second)
    }

    @Test
    fun memoryBackupEntries_omitsRollupWhenAbsentOrEmpty() {
        // Absent (reader returns null) — no entry, no error.
        val absent = memoryBackupEntries(listOf("GLOBAL.md")) { name ->
            if (name == "GLOBAL.md") "## global\n" else null
        }
        assertEquals(listOf("GLOBAL.md"), absent.map { it.first })

        // Present but empty — nothing to preserve, so no empty entry either.
        val empty = memoryBackupEntries(listOf("GLOBAL.md")) { name ->
            if (name == "GLOBAL.md") "## global\n" else ""
        }
        assertEquals(listOf("GLOBAL.md"), empty.map { it.first })
    }

    @Test
    fun memoryBackupEntries_keepsListedEntryWithEmptyContent() {
        // Pre-existing behaviour, pinned: a *listed* file that reads back empty
        // still rides the payload (the listing is the source of truth there).
        val out = memoryBackupEntries(listOf("GLOBAL.md")) { "" }
        assertEquals(listOf("GLOBAL.md"), out.map { it.first })
        assertEquals("", out.single().second)
    }

    @Test
    fun memoryBackupEntries_againstRealRepositoryCarriesRollup() {
        memoryDir.mkdirs()
        File(memoryDir, "GLOBAL.md").writeText("## global\npersistent prefs\n")
        File(memoryDir, "2026-10-16.md").writeText("<!-- 2026-10-16 09:00:00 -->\n## entry\ncontent\n")
        File(memoryDir, "MEMORY-ROLLUP.md").writeText("## Rollup 2026-10-15\n### rules\n- 蒸馏条目\n")
        val repo = MemoryRepository(memoryDir)

        // The listing the settings UI sees stays rollup-free …
        assertFalse(repo.listAllFiles().any { it.name == "MEMORY-ROLLUP.md" })

        // … while the backup payload built from it does carry the rollup.
        val out = memoryBackupEntries(repo.listAllFiles().map { it.name }) { repo.readFile(it) }
        assertTrue(out.any { it.first == "MEMORY-ROLLUP.md" })
        assertEquals(
            "## Rollup 2026-10-15\n### rules\n- 蒸馏条目\n",
            out.first { it.first == "MEMORY-ROLLUP.md" }.second,
        )
    }

    @Test
    fun readMemoryFileForBackup_distinguishesAbsentFromEmpty() {
        // [FIX-R3-D / F-05] listAllFiles() lists GLOBAL.md even when the device
        // never created it. The backup reader must answer "not there" (null ⇒
        // no entry) rather than readFile's folded "" (which the restore side's
        // guard would have to treat as "the backup knows nothing"). A file that
        // exists but is empty still rides as "" — its emptiness is the truth.
        val dir = File(tempDir, "read-for-backup").apply { mkdirs() }
        assertEquals(null, readMemoryFileForBackup(dir, "GLOBAL.md"))

        File(dir, "GLOBAL.md").writeText("## global\nreal content\n")
        assertEquals("## global\nreal content\n", readMemoryFileForBackup(dir, "GLOBAL.md"))

        File(dir, "EMPTY.md").writeText("")
        assertEquals("", readMemoryFileForBackup(dir, "EMPTY.md"))

        // No directory at all (memoryDirectory() failed): nothing is claimed.
        assertEquals(null, readMemoryFileForBackup(null, "GLOBAL.md"))
        // A directory in the file's place: not a file ⇒ null.
        File(dir, "weird.md").mkdirs()
        assertEquals(null, readMemoryFileForBackup(dir, "weird.md"))
    }

    @Test
    fun restoreWritesRollupBackAndKeepsItOutOfTheListing() {
        memoryDir.mkdirs()
        val repo = MemoryRepository(memoryDir)
        // Restore path: ConfigBackup Stage 6 writes payload entries by name.
        repo.saveFile("MEMORY-ROLLUP.md", "## Rollup 2026-10-15\n- distilled\n")
        assertEquals("## Rollup 2026-10-15\n- distilled\n", repo.readFile("MEMORY-ROLLUP.md"))
        // UI/memory_get semantics unchanged: still hidden from the listing.
        assertFalse(repo.listAllFiles().any { it.name == "MEMORY-ROLLUP.md" })
    }

    @Test
    fun deleteFile_refusesRollupFile() {
        memoryDir.mkdirs()
        val rollup = File(memoryDir, "MEMORY-ROLLUP.md")
        rollup.writeText("## Rollup 2026-10-15\n- distilled\n")
        val repo = MemoryRepository(memoryDir)

        assertFalse("deleteFile must refuse the rollup product file", repo.deleteFile("MEMORY-ROLLUP.md"))
        assertTrue("rollup must survive the refused delete", rollup.exists())
        assertEquals("## Rollup 2026-10-15\n- distilled\n", rollup.readText())

        // Guard is scoped to the protected names: a daily log still deletes.
        val daily = File(memoryDir, "2026-10-16.md")
        daily.writeText("<!-- 2026-10-16 09:00:00 -->\n## entry\n")
        assertTrue(repo.deleteFile("2026-10-16.md"))
        assertFalse(daily.exists())
    }
}
