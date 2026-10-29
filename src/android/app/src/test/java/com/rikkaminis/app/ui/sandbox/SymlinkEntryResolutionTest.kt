package com.rikkaminis.app.ui.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-11] The sandbox browser's symlink decisions, pinned on the JVM.
 *
 * Three arms, in increasing order of teeth:
 *
 *  1. **Table** — [shouldDeleteRecursively] must say NO for any symlink
 *     (`isDirectory` comes from the RESOLVED target, so a directory-shaped
 *     link reports `true` there; the lstat truth is what saves the target).
 *  2. **Resolution** — a link whose target is an absolute GUEST path (PRoot
 *     writes `/var/minis/…`, `/usr/bin/…`) must be resolved through the
 *     mapper, and an unmappable target must stay "unknown" (the link itself),
 *     never a fabricated `File`.
 *  3. **Real filesystem** — the same fixture twice: the fixed decision unlinks
 *     one name and leaves the target's files alone, while the pre-fix
 *     `deleteRecursively()` wipes them. The second half is a *negative
 *     control*: it proves the fixture would catch a regression back to the old
 *     call (measured in the report's §3 arm A: 2 files before, 0 after).
 *
 * Expectations are literals and the fixture is rebuilt per test, so a flipped
 * predicate shows up as a red arm rather than a silently adjusted one.
 */
class SymlinkEntryResolutionTest {

    // ── arm 1: never recurse through a symlink ───────────────────────────

    @Test
    fun `only a real directory is deleted recursively`() {
        // literal table — (isDirectory, isSymlink) -> recursive?
        assertEquals(true, shouldDeleteRecursively(isDirectory = true, isSymlink = false))
        assertEquals(false, shouldDeleteRecursively(isDirectory = true, isSymlink = true))
        assertEquals(false, shouldDeleteRecursively(isDirectory = false, isSymlink = true))
        assertEquals(false, shouldDeleteRecursively(isDirectory = false, isSymlink = false))
    }

    // ── arm 2: absolute guest targets go through the mapper ──────────────

    @Test
    fun `a relative target resolves against the link's own directory`() {
        withFixture { dir, link, _ghost ->
            val entry = resolveListedEntry(link, "target-dir", resolveGuestAbsolute = { null })
            assertEquals(File(dir, "target-dir").canonicalPath, entry.canonicalPath)
            assertTrue("relative target must read as a directory", entry.isDirectory)
        }
    }

    @Test
    fun `an absolute guest target resolves through the mapper`() {
        withFixture { _dir, link, _ghost ->
            val mapped = Files.createTempDirectory("symlink-mapped").toFile()
            try {
                File(mapped, "real.txt").writeText("x")
                val entry = resolveListedEntry(link, "/var/minis/workspace/shared", resolveGuestAbsolute = { mapped })
                assertEquals(mapped.canonicalPath, entry.canonicalPath)
                assertTrue("a mapped directory target must read as a directory", entry.isDirectory)
            } finally {
                mapped.deleteRecursively()
            }
        }
    }

    @Test
    fun `an unmappable absolute target stays unknown, never a fabricated file`() {
        withFixture { _dir, _link, ghost ->
            // Pre-fix this arm returned File("/var/minis-absent/workspace") —
            // on the host a nonexistent path, i.e. a 0-byte "file" the UI
            // offered to delete and could not open.
            val entry = resolveListedEntry(ghost, "/var/minis-absent/workspace", resolveGuestAbsolute = { null })
            assertSame("unknown target must keep the link as the entry", ghost, entry)
            assertFalse("an unknown link must never read as a directory", entry.isDirectory)
            assertEquals("an unknown link keeps its listed name", "link-ghost", entry.name)
        }
    }

    @Test
    fun `an unreadable target keeps the link`() {
        withFixture { _dir, link, _ghost ->
            assertSame(link, resolveListedEntry(link, null, resolveGuestAbsolute = { null }))
        }
    }

    // ── arm 3: the real filesystem ───────────────────────────────────────

    @Test
    fun `the fixed decision unlinks one name and leaves the target intact`() {
        withFixture { dir, link, _ghost ->
            val target = File(dir, "target-dir")
            assertEquals(2, countFiles(target))

            val entry = resolveListedEntry(link, "target-dir", resolveGuestAbsolute = { null })
            assertTrue("precondition: the entry reads as a directory", entry.isDirectory)

            // Same predicate the delete site uses, with the item-level truth.
            if (shouldDeleteRecursively(isDirectory = entry.isDirectory, isSymlink = true)) {
                link.deleteRecursively()
            } else {
                link.delete()
            }

            assertFalse("the link itself must be gone", link.exists())
            assertTrue("the link's target must survive", target.exists())
            assertEquals("the target's files must survive", 2, countFiles(target))
        }
    }

    @Test
    fun `negative control - deleting recursively wipes the target`() {
        withFixture { dir, link, _ghost ->
            val target = File(dir, "target-dir")
            assertEquals(2, countFiles(target))

            // The pre-fix call. If this ever stops wiping, the arm above stops
            // proving anything (Kotlin's walk would have stopped following
            // links) and this test goes red instead of silently passing.
            link.deleteRecursively()

            assertFalse(link.exists())
            assertEquals("deleteRecursively() must still reach the target", 0, countFiles(target))
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────

    private fun countFiles(dir: File): Int =
        dir.walkTopDown().count { it.isFile }

    /**
     * A directory holding `target-dir/{precious.txt,nested/deep.txt}` plus a
     * RELATIVE `link-to-dir` — the shape PRoot's `busybox ln -s` produces
     * inside the container.
     */
    private fun withFixture(block: (File, File, File) -> Unit) {
        val dir = Files.createTempDirectory("symlink-entry").toFile()
        try {
            val target = File(dir, "target-dir").apply { mkdirs() }
            File(target, "precious.txt").writeText("do not lose me")
            File(target, "nested").mkdirs()
            File(File(target, "nested"), "deep.txt").writeText("deep")
            val link = File(dir, "link-to-dir")
            Files.createSymbolicLink(link.toPath(), File("target-dir").toPath())
            // An absolute GUEST target that no host path can satisfy — the
            // shape that read as a 0-byte file before the fix.
            val ghost = File(dir, "link-ghost")
            Files.createSymbolicLink(ghost.toPath(), File("/var/minis-absent/workspace").toPath())
            block(dir, link, ghost)
        } finally {
            dir.deleteRecursively()
        }
    }
}
