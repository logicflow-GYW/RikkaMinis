package com.rikkaminis.app.data.repository

import com.rikkaminis.app.agent.SoulStore
import com.rikkaminis.app.workspace.MemoryRollupEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * JVM tests for [MemoryRepository]'s file listing / search filters.
 *
 * Guards the rollup-file exclusion: MEMORY-ROLLUP.md is an auto-generated
 * distilled index owned by memory_rollup, not a user-editable daily log. It
 * must never surface in listAllFiles() (so the user can't delete/edit it and
 * break the rollup idempotency anchor) nor in getMemory()'s daily-log scan
 * (so its distilled entries don't duplicate the original logs and waste the
 * search budget).
 */
class MemoryRepositoryTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "mem-repo-${UUID.randomUUID().toString().take(8)}")
    private val memoryDir = File(tempDir, "minis-global/memory")

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun listAllFiles_excludesRollupFile() {
        memoryDir.mkdirs()
        File(memoryDir, "GLOBAL.md").writeText("## global\npersistent prefs\n")
        File(memoryDir, "2026-08-25.md").writeText("<!-- 2026-08-25 10:00:00 -->\n## entry\ncontent\n")
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText("## Rollup 2026-08-24\n### 经验与知识点\n- 蒸馏条目\n")

        val repo = MemoryRepository(memoryDir)
        val names = repo.listAllFiles().map { it.name }

        assertTrue("GLOBAL.md must always be listed", names.contains("GLOBAL.md"))
        assertTrue("daily log must be listed", names.contains("2026-08-25.md"))
        assertFalse(
            "MEMORY-ROLLUP.md must not appear in the file list (user could delete/edit it)",
            names.contains(MemoryRollupEngine.ROLLUP_FILE),
        )
    }

    @Test
    fun getMemory_excludesRollupFile_fromSearch() {
        memoryDir.mkdirs()
        // Original daily log carries a distinctive keyword.
        File(memoryDir, "2026-08-25.md").writeText("<!-- 2026-08-25 10:00:00 -->\n## 经验\n根因特别关键词甲\n")
        // The rollup file also contains that same distilled keyword.
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText(
            "## Rollup 2026-08-24\n### 经验与知识点\n- **经验**：根因特别关键词甲\n",
        )

        val repo = MemoryRepository(memoryDir)
        val result = repo.getMemory("特别关键词甲", "daily")

        // The keyword is present via the daily log; the rollup file must not
        // be surfaced as a separate "file" in the output.
        assertTrue("search must find the daily log match", result.contains("2026-08-25.md"))
        assertFalse(
            "MEMORY-ROLLUP.md must not be searched as a daily log",
            result.contains(MemoryRollupEngine.ROLLUP_FILE),
        )
    }

    @Test
    fun getMemory_fullDump_excludesRollupFile() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-25.md").writeText("## 日志正文\n一行内容\n")
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText("## Rollup 2026-08-24\n- 一条蒸馏\n")

        val repo = MemoryRepository(memoryDir)
        val result = repo.getMemory("", "daily")

        assertTrue(result.contains("2026-08-25.md"))
        assertFalse(result.contains(MemoryRollupEngine.ROLLUP_FILE))
    }

    @Test
    fun listAllFiles_sortsGlobalFirst_thenDailyLogsDescending() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-24.md").writeText("## older\n")
        File(memoryDir, "2026-08-25.md").writeText("## newer\n")
        File(memoryDir, "GLOBAL.md").writeText("## global\n")

        val names = MemoryRepository(memoryDir).listAllFiles().map { it.name }
        assertEquals(listOf("GLOBAL.md", "2026-08-25.md", "2026-08-24.md"), names)
    }

    // ── [fix/send-prompt-bloat] loadRollupFragment byte-cap behaviour ──────

    @Test
    fun loadRollupFragment_missingFile_returnsNull() {
        val repo = MemoryRepository(memoryDir)
        assertNull(repo.loadRollupFragment())
    }

    @Test
    fun loadRollupFragment_emptyFile_returnsNull() {
        memoryDir.mkdirs()
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText("")
        val repo = MemoryRepository(memoryDir)
        assertNull(repo.loadRollupFragment())
    }

    @Test
    fun loadRollupFragment_smallFile_passesThroughVerbatim() {
        memoryDir.mkdirs()
        val small = "## Rollup 2026-08-12\n- rule A\n"
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText(small)
        val repo = MemoryRepository(memoryDir)
        assertEquals(small, repo.loadRollupFragment())
    }

    @Test
    fun loadRollupFragment_oversize_keepsTailAndMarksTruncation() {
        memoryDir.mkdirs()
        val big = StringBuilder("## Rollup OLD\n")
            .append("x".repeat(20_000))
            .append("\n## Rollup NEW\n- newest rule Y\n")
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText(big.toString())
        val repo = MemoryRepository(memoryDir)
        val capped = repo.loadRollupFragment()
        assertNotNull(capped)
        assertTrue(capped!!.length < big.length)
        assertTrue(capped.contains("older rollup rules truncated"))
        assertTrue("tail (newest rules) must be kept", capped.contains("newest rule Y"))
        assertFalse("oldest head should be dropped", capped.contains("## Rollup OLD"))
    }

    @Test
    fun loadRollupFragment_oversizeCjk_noReplacementChar() {
        memoryDir.mkdirs()
        // "房" is 3 bytes; a trailing ASCII byte forces the cut point to land
        // mid-code-point, which a naive byte-array slice would decode as U+FFFD.
        val misaligned = ("房".repeat(20_000)) + "X"
        File(memoryDir, MemoryRollupEngine.ROLLUP_FILE).writeText(misaligned)
        val repo = MemoryRepository(memoryDir)
        val capped = repo.loadRollupFragment()
        assertNotNull(capped)
        assertFalse("UTF-8 boundary must not produce U+FFFD", capped!!.contains('\uFFFD'))
    }

    // -- [FIX-6 / F-224] persona-file (SOUL.md) guards -------------------
    //
    // SOUL.md lives in this same directory (SoulStore.fileLocation →
    // <filesDir>/minis-global/memory/SOUL.md) and holds the user's persona.
    // Before the fix it was listed as a daily log, so the settings screen
    // offered a working Delete button next to it; deleting it made
    // SoulStore.ensureExists re-seed DEFAULT_CONTENT on the next launch — a
    // silent persona reset with no prompt, no backup and no undo.
    //
    // These tests reference the real SoulStore.FILE_NAME (the single source of
    // truth), so they stay honest if the filename ever changes.

    @Test
    fun listAllFiles_excludesSoulFile() {
        memoryDir.mkdirs()
        File(memoryDir, "GLOBAL.md").writeText("## global\npersistent prefs\n")
        File(memoryDir, "2026-08-25.md").writeText("<!-- 2026-08-25 10:00:00 -->\n## entry\ncontent\n")
        File(memoryDir, SoulStore.FILE_NAME).writeText("---\nname: RikkaMinis\n---\npersona body\n")

        val repo = MemoryRepository(memoryDir)
        val names = repo.listAllFiles().map { it.name }

        assertFalse(
            "SOUL.md must not appear in the daily-log list (it would offer a Delete button)",
            names.contains(SoulStore.FILE_NAME),
        )
        // Control arm: the fix must not over-filter — real logs still show up.
        assertTrue("daily log must still be listed", names.contains("2026-08-25.md"))
        assertTrue("GLOBAL.md must still be listed", names.contains("GLOBAL.md"))
    }

    @Test
    fun getMemory_excludesSoulFile_fromSearch() {
        memoryDir.mkdirs()
        File(memoryDir, "2026-08-25.md").writeText("<!-- 2026-08-25 10:00:00 -->\n## entry\nunique-log-marker\n")
        File(memoryDir, SoulStore.FILE_NAME).writeText("## persona\nunique-soul-marker\n")

        val repo = MemoryRepository(memoryDir)
        val result = repo.getMemory("", "daily")

        assertFalse(
            "SOUL.md must not be searched as a daily log",
            result.contains("unique-soul-marker"),
        )
        assertTrue("real daily logs must still be searched", result.contains("unique-log-marker"))
    }

    @Test
    fun getMemory_fullDump_excludesSoulFile() {
        memoryDir.mkdirs()
        File(memoryDir, SoulStore.FILE_NAME).writeText("## persona\nunique-soul-marker\n")

        val repo = MemoryRepository(memoryDir)
        val result = repo.getMemory("", "daily")

        assertFalse(result.contains("unique-soul-marker"))
    }

    @Test
    fun deleteFile_refusesSoulFile() {
        memoryDir.mkdirs()
        val soul = File(memoryDir, SoulStore.FILE_NAME)
        soul.writeText("---\nname: RikkaMinis\n---\npersona body\n")

        val repo = MemoryRepository(memoryDir)
        val deleted = repo.deleteFile(SoulStore.FILE_NAME)

        assertFalse("deleteFile must refuse the persona file", deleted)
        assertTrue("SOUL.md must survive the delete attempt", soul.exists())
    }

    @Test
    fun deleteFile_stillDeletesDailyLog() {
        // Control arm for the guard above: it must refuse SOUL.md specifically,
        // not silently break deletion of ordinary logs.
        memoryDir.mkdirs()
        val log = File(memoryDir, "2026-08-25.md")
        log.writeText("<!-- 2026-08-25 10:00:00 -->\n## entry\ncontent\n")

        val repo = MemoryRepository(memoryDir)
        val deleted = repo.deleteFile("2026-08-25.md")

        assertTrue("ordinary daily logs must still be deletable", deleted)
        assertFalse("the daily log file must be gone", log.exists())
    }
}
