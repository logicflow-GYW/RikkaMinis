package com.rikkaminis.app.agent

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D8-F06] `SoulStore.writeAtomically` must not leave `SOUL.md.tmp`
 * behind on its FALLBACK path.
 *
 * The happy path is `.tmp` + rename. When `renameTo` fails the code falls back
 * to `target.writeText(text)` and then deletes the tmp — write first, delete
 * second. If that write throws (disk full / permission, i.e. the usual reason
 * a rename just failed) the delete was skipped, and nothing else ever removes
 * a stale `<name>.tmp` — the very residue `[audit-0917]` added a cleanup for on
 * the *write* side of this same function.
 *
 * Reproduction without Root/mocks: point the write at a path that is a
 * **directory**. `rename(tmp → dir)` fails (EISDIR), and `dir.writeText(…)`
 * throws `FileNotFoundException` — so the fallback's failure path runs for
 * real, on a real filesystem, through `saveRawTo` (the production entry the
 * Settings writers use).
 */
class SoulStoreAtomicFallbackTest {

    private val tempDir: File = Files.createTempDirectory("soul-atomic").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `a failing fallback write leaves no tmp sibling`() {
        val target = File(tempDir, "SOUL.md")
        assertTrue("fixture: target must be a directory so rename fails", target.mkdirs())

        var thrown: Throwable? = null
        try {
            SoulStore.saveRawTo(target, "new body\n")
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue("the save must fail loudly, got $thrown", thrown is FileNotFoundException)
        assertFalse(
            "a stale SOUL.md.tmp was left behind: ${tempDir.list()?.joinToString()}",
            File(tempDir, "SOUL.md.tmp").exists(),
        )
        assertTrue("the directory itself must not be replaced", target.isDirectory)
    }

    @Test
    fun `a successful atomic write leaves no tmp sibling either`() {
        val target = File(tempDir, "SOUL.md")
        SoulStore.saveRawTo(target, "---\nname: RikkaMinis\n---\nbody\n")
        assertEquals("---\nname: RikkaMinis\n---\nbody\n", target.readText())
        assertFalse(File(tempDir, "SOUL.md.tmp").exists())
    }
}
