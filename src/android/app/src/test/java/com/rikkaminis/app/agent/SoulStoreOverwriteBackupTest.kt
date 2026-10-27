package com.rikkaminis.app.agent

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P0 F-4 / world W3] SOUL.md overwrite guard.
 *
 * The bug this pins: a SOUL.md that is *on disk but unreadable* is
 * indistinguishable from a missing one at every write call site —
 * `ConfigBuiltins.loadCurrent()` and `SoulSettingsScreen` both do
 * `SoulStore.load(context) ?: parse(DEFAULT_CONTENT)`, and the five writers
 * then `copy(oneField = …)` and save. Changing a single field therefore
 * rewrote the whole file with the app default body and the user's persona was
 * gone, with no prompt, no backup and no undo.
 *
 * The fix lives in `SoulStore.saveTo` — before the renaming write lands, the
 * bytes on disk are copied to a `SOUL.md.recovered-<stamp>` sibling when they
 * differ from the new bytes. These tests run the production bodies on a real
 * filesystem (the seam is `saveTo(target, …)` / `loadFrom(target)`, both
 * Context-free), so nothing here is a re-implementation of the logic under
 * test. Expectation values are written as literals: nothing is derived from
 * the constants the production code uses to make its own decisions.
 */
class SoulStoreOverwriteBackupTest {

    private val tempDir: File = Files.createTempDirectory("soul-overwrite").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /** `ContextWrapper(null)` + an overridden `filesDir`, mirroring SoulStoreWriteVersionTest. */
    private class StubContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private fun soulFile(body: String, name: String = "RikkaMinis"): SoulFile =
        SoulFile(SoulMetadata.DEFAULT.copy(name = name), body)

    private fun backupsIn(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.name.startsWith("SOUL.md.recovered-") }
            ?.sortedBy { it.name }
            ?: emptyList()

    // ── 1. no target → nothing to back up ────────────────────────────────

    @Test
    fun `first ever save on a missing target writes the file and makes no backup`() {
        val target = File(tempDir, "SOUL.md")
        assertFalse(target.exists())

        SoulStore.saveTo(target, soulFile("first body"))

        assertEquals(1, tempDir.listFiles()?.count { it.name == "SOUL.md" } ?: 0)
        assertEquals("no previous bytes existed to protect", 0, backupsIn(tempDir).size)
        assertEquals("first body", SoulStore.loadFrom(target).let { (it as SoulStore.ReadState.Loaded).file.body })
    }

    // ── 2. identical content → no backup ─────────────────────────────────

    @Test
    fun `rewriting identical bytes makes no backup`() {
        val target = File(tempDir, "SOUL.md")
        SoulStore.saveTo(target, soulFile("same body"))
        assertEquals(0, backupsIn(tempDir).size)

        // Second save of the same content: byte-identical, so a backup would
        // be pure stack growth with nothing to recover.
        SoulStore.saveTo(target, soulFile("same body"))

        assertEquals("an identical rewrite must not stack backups", 0, backupsIn(tempDir).size)
    }

    // ── 3. different content → exactly one, byte-exact backup ────────────

    @Test
    fun `overwriting different bytes keeps a byte-exact copy of the old file`() {
        val target = File(tempDir, "SOUL.md")
        // Deliberately includes CRLF, a CJK body and a BOM-free odd byte run:
        // the backup must be a byte copy, not a read-text/write-text round trip.
        val oldBytes = byteArrayOf(0x2D, 0x2D, 0x2D, 0x0D, 0x0A) +
            "name: \"X\"\r\n---\r\n\r\n我的正文 with CRLF\r\n".toByteArray(Charsets.UTF_8)
        target.writeBytes(oldBytes)

        SoulStore.saveTo(target, soulFile("new body"))

        val backups = backupsIn(tempDir)
        assertEquals(1, backups.size)
        assertTrue(
            "backup must hold the pre-overwrite bytes exactly: ${backups[0].absolutePath}",
            oldBytes.contentEquals(backups[0].readBytes()),
        )
        assertTrue("the target itself must carry the new content", target.readText().contains("new body"))
    }

    // ── 4. rotation: exactly 3 newest backups survive ────────────────────

    @Test
    fun `five consecutive content changes leave exactly three newest backups`() {
        val target = File(tempDir, "SOUL.md")
        var stamp = 1_700_000_000_000L
        val clock = { stamp.also { stamp += 1_000L } }

        // Save 1 creates the file (no backup). Saves 2..5 each back up the
        // content they are about to replace → 4 backups, pruned to 3.
        for (i in 1..5) {
            SoulStore.saveTo(target, soulFile("body-$i"), clock)
        }

        val names = backupsIn(tempDir).map { it.name }
        assertEquals("rotation must keep exactly 3", 3, names.size)
        assertEquals(
            // save #2 stamped 1700000000000 (the body-1 copy) and was pruned;
            // saves #3/#4/#5 stamped …001000 / …002000 / …003000.
            listOf(
                "SOUL.md.recovered-1700000001000",
                "SOUL.md.recovered-1700000002000",
                "SOUL.md.recovered-1700000003000",
            ),
            names,
        )
        // The surviving three are the newest: they hold the content replaced by
        // saves 3, 4 and 5, i.e. body-2 / body-3 / body-4. The body-1 copy (the
        // oldest) is the one that was pruned.
        val bodies = backupsIn(tempDir).map { SoulMDParser.parse(it.readText()).body }
        assertEquals(listOf("body-2", "body-3", "body-4"), bodies)
    }

    // ── 5. unreadable target → save still succeeds, backup reports it ────

    @Test
    fun `an unreadable target never blocks the save`() {
        val target = File(tempDir, "SOUL.md")
        target.writeText("---\nname: \"X\"\n---\n\nold body")

        // The runner is root, so chmod 000 would still read. A throwing reader
        // is the honest simulation of EACCES / EIO / a racing external editor.
        val throwingReader: (File) -> ByteArray? = { throw IOException("simulated EACCES") }

        val outcome = SoulStore.backupBeforeOverwrite(target, "anything", { 1L }, throwingReader)
        assertEquals(SoulStore.BackupOutcome.Unreadable, outcome)

        // ... and the full write path proceeds: no exception, new content lands,
        // nothing was "backed up" because nothing could be read.
        SoulStore.saveTo(target, soulFile("new body"), { 2L }, throwingReader)
        assertTrue(target.readText().contains("new body"))
        assertEquals(0, backupsIn(tempDir).size)
    }

    @Test
    fun `a target that is not a regular file reports unreadable instead of throwing`() {
        val target = File(tempDir, "SOUL.md")
        assertTrue(target.mkdirs())

        val outcome = SoulStore.backupBeforeOverwrite(target, "anything")

        assertEquals(SoulStore.BackupOutcome.Unreadable, outcome)
        assertEquals(0, backupsIn(tempDir).size)
    }

    // ── 6. readState tells the three worlds apart ────────────────────────

    @Test
    fun `readState distinguishes missing loaded and unreadable`() {
        val dir = File(tempDir, "minis-global/memory")
        assertTrue(dir.mkdirs())
        val context: Context = StubContext(tempDir)
        val target = SoulStore.fileLocation(context)
        assertEquals(File(dir, "SOUL.md"), target)

        assertEquals(SoulStore.ReadState.Missing, SoulStore.readState(context))
        assertNull("load() keeps its null contract", SoulStore.load(context))

        target.writeText("---\nname: \"Alice\"\n---\n\nhello")
        val loaded = SoulStore.readState(context)
        assertTrue(loaded is SoulStore.ReadState.Loaded)
        assertEquals("Alice", (loaded as SoulStore.ReadState.Loaded).file.metadata.name)
        assertEquals("hello", loaded.file.body)
        assertEquals("Alice", SoulStore.load(context)?.metadata?.name)

        // A path that exists but cannot be read as text: swap the file for a
        // directory of the same name.
        assertTrue(target.delete())
        assertTrue(target.mkdirs())
        val unreadable = SoulStore.readState(context)
        assertTrue("a directory at SOUL.md must not read as Loaded", unreadable is SoulStore.ReadState.Unreadable)
        assertTrue((unreadable as SoulStore.ReadState.Unreadable).message.isNotEmpty())
        assertNull("load() must keep returning null in this world", SoulStore.load(context))
    }

    // ── 7. atomicity does not regress ────────────────────────────────────

    @Test
    fun `a concurrent reader never sees a truncated SOUL md`() {
        val target = File(tempDir, "SOUL.md")
        val allowed = LinkedHashSet<String>()
        val filler = "x".repeat(64 * 1024)

        val first = soulFile("${filler}\nversion-0")
        allowed.add(SoulMDParser.serialize(first))
        target.parentFile?.mkdirs()
        target.writeText(SoulMDParser.serialize(first))

        val failure = AtomicReference<Throwable?>(null)
        val done = AtomicReference(false)
        val reader = Thread {
            try {
                while (!done.get()) {
                    val text = target.readText()
                    if (text !in allowed) {
                        throw AssertionError(
                            "reader saw ${text.length} bytes that are not any complete version " +
                                "(old=${allowed.first().length})",
                        )
                    }
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        reader.start()
        try {
            for (i in 1..20) {
                val next = soulFile("${filler}\nversion-$i")
                allowed.add(SoulMDParser.serialize(next))
                SoulStore.saveTo(target, next)
            }
        } finally {
            done.set(true)
            reader.join(10_000)
        }
        failure.get()?.let { throw it }

        assertTrue(target.readText().endsWith("version-20\n"))
        assertFalse(
            "the atomic write must not leave its .tmp sibling behind",
            File(tempDir, "SOUL.md.tmp").exists(),
        )
        assertEquals("20 content changes must rotate down to 4 backups", 3, backupsIn(tempDir).size)
    }

    // ── 8. the W3 scenario, end to end ───────────────────────────────────

    @Test
    fun `world W3 - editing one field over an unreadable file keeps the old body recoverable`() {
        val dir = File(tempDir, "minis-global/memory")
        assertTrue(dir.mkdirs())
        val context: Context = StubContext(tempDir)
        val target = SoulStore.fileLocation(context)
        val oldText = "---\nname: \"X\"\nstyle: \"\"\nlang: \"auto\"\n---\n\n我的正文"
        target.writeText(oldText)

        // What every write point does when load() cannot read the file: fall
        // back to the default content, change ONE field, save.
        val fallback = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        val edited = fallback.copy(metadata = fallback.metadata.copy(name = "Y"))
        SoulStore.save(context, edited)

        // The overwrite still happens (that is the contract the task kept),
        // but the user's persona is now on disk instead of gone.
        assertEquals("Y", SoulStore.load(context)?.metadata?.name)
        assertEquals(
            "the new file carries the default body, not the user's",
            fallback.body,
            SoulStore.load(context)?.body,
        )
        val backups = backupsIn(dir)
        assertEquals(1, backups.size)
        assertEquals("the pre-overwrite bytes must be recoverable verbatim", oldText, backups[0].readText())
        assertTrue(backups[0].readText().contains("我的正文"))
    }
}
