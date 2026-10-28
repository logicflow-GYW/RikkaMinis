package com.rikkaminis.app.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [1028B-2 / backlog L9096] `MemoryRepository.saveFile` owns two write classes
 * for one directory.
 *
 * `SOUL.md` has a second owner with stronger rules: `SoulStore` writes it
 * atomically (`.tmp` + rename) and keeps a pre-overwrite backup. But the chat
 * memory sheet and Settings → Memory's editor both save through
 * `MemoryRepository.saveFile(name, content)`, which used to be a bare
 * `File(memoryDir, name).writeText(content)` — no backup, no atomicity, on the
 * one file whose loss is unrecoverable (the persona).
 *
 * The fix is an explicit early return inside `saveFile` for that single name;
 * every other file keeps the plain write. Both halves are pinned here, because
 * the real risk of this change is the *other* half: `saveFile` is the generic
 * write point for daily logs / GLOBAL.md / MEMORY-ROLLUP.md and `ConfigBackup`
 * restores through it by name, so a "clever" shared implementation would
 * silently change restores.
 *
 * All expectations are literals — nothing is derived from the constants the
 * code under test uses to decide its own behaviour.
 */
class MemorySaveFileSoulForwardingTest {

    private val tempDir: File = Files.createTempDirectory("mem-savefile-soul").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun repo() = MemoryRepository(tempDir)

    private fun recoveredIn(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.name.startsWith("SOUL.md.recovered-") }
            ?.sortedBy { it.name }
            ?: emptyList()

    // ── SOUL.md: gets SoulStore's protection ─────────────────────────────

    @Test
    fun `saveFile keeps the previous SOUL md before overwriting it`() {
        val repo = repo()
        val oldText = "---\nname: \"Rikka\"\n---\n\n旧人格 body\r\n"
        repo.saveFile("SOUL.md", oldText)
        assertEquals("first ever write has nothing to back up", 0, recoveredIn(tempDir).size)

        // What the sheet does: splice the edited window back in, save the whole
        // text again.
        val newText = oldText.replace("旧人格", "新人格")
        repo.saveFile("SOUL.md", newText)

        val recovered = recoveredIn(tempDir)
        assertEquals("the overwrite must leave exactly one backup", 1, recovered.size)
        assertEquals(
            "and the backup must be the pre-edit text, byte for byte",
            oldText,
            recovered[0].readText(),
        )
        assertEquals("while the live file carries the edit", newText, File(tempDir, "SOUL.md").readText())
    }

    @Test
    fun `saveFile on SOUL md goes through the atomic write path`() {
        val repo = repo()
        repo.saveFile("SOUL.md", "---\nname: \"A\"\n---\n\nfirst\n")
        val target = File(tempDir, "SOUL.md")

        // A rename-based write replaces the *directory entry*: the inode behind
        // the final name must change. A bare writeText keeps the same inode and
        // truncates in place, which is exactly the window this fix closes.
        val before = fileKey(target)
        assumeTrue("fileKey unsupported on this filesystem — atomicity witness skipped", before != null)

        repo.saveFile("SOUL.md", "---\nname: \"A\"\n---\n\nsecond\n")

        val after = fileKey(target)
        assertNotEquals("the final name must point at a new inode after the write", before, after)
        assertFalse("the atomic path must not leave its .tmp sibling behind", File(tempDir, "SOUL.md.tmp").exists())
        assertEquals("---\nname: \"A\"\n---\n\nsecond\n", target.readText())
    }

    @Test
    fun `saveFile on identical SOUL md text makes no backup`() {
        val repo = repo()
        val text = "---\nname: \"A\"\n---\n\nsame\n"
        repo.saveFile("SOUL.md", text)
        repo.saveFile("SOUL.md", text)

        assertEquals("an identical rewrite must not stack backups", 0, recoveredIn(tempDir).size)
        assertEquals(text, File(tempDir, "SOUL.md").readText())
    }

    // ── every other file: unchanged ──────────────────────────────────────

    @Test
    fun `saveFile on a daily log keeps the plain in-place write`() {
        val repo = repo()
        val name = "2026-10-28.md"
        repo.saveFile(name, "<!-- 2026-10-28 10:00:00 -->\nold entry\n")

        repo.saveFile(name, "<!-- 2026-10-28 10:00:00 -->\nnew entry\n")

        val target = File(tempDir, name)
        assertEquals("<!-- 2026-10-28 10:00:00 -->\nnew entry\n", target.readText())
        assertEquals("daily logs must not grow persona backups", 0, recoveredIn(tempDir).size)
    }

    @Test
    fun `saveFile on GLOBAL md is still a plain overwrite`() {
        val repo = repo()
        repo.saveFile("GLOBAL.md", "# global\n- rule one\n")
        repo.saveFile("GLOBAL.md", "# global\n- rule one\n- rule two\n")

        assertEquals("# global\n- rule one\n- rule two\n", File(tempDir, "GLOBAL.md").readText())
        assertEquals(
            "only SOUL.md is redirected — no other file may grow a persona backup",
            0,
            tempDir.listFiles()?.count { it.name.contains(".recovered-") } ?: 0,
        )
    }

    private fun fileKey(file: File): Any? =
        runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        }.getOrNull()
}
