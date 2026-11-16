package com.rikkaminis.app.sandbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class RootfsResetFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `reinstall removes rootfs files but keeps live L2S target`() {
        val files = temp.newFolder("files")
        val rootfs = File(files, "alpine-rootfs")
        val backing = File(rootfs, L2S_BACKING_DIR_NAME).apply { mkdirs() }
        val target = File(backing, ".l2s.model0001.0002").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val shared = temp.newFolder("shared")
        val externalLink = File(shared, "model-link")
        Files.createSymbolicLink(externalLink.toPath(), target.toPath())
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/obsolete.conf").writeText("old")

        clearRootfsForReinstall(rootfs)

        assertFalse(File(rootfs, "etc/obsolete.conf").exists())
        assertTrue(backing.isDirectory)
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(externalLink.toPath()))
    }

    @Test
    fun `fresh rootfs is created when no rootfs exists`() {
        val rootfs = File(temp.root, "missing/alpine-rootfs")

        clearRootfsForReinstall(rootfs)

        assertTrue(rootfs.isDirectory)
    }

    @Test
    fun `unsafe backing symlink refuses reset without touching its target`() {
        val rootfs = File(temp.root, "rootfs").apply { mkdirs() }
        val external = temp.newFolder("external")
        File(external, "user-data").writeText("keep")
        Files.createSymbolicLink(File(rootfs, L2S_BACKING_DIR_NAME).toPath(), external.toPath())
        File(rootfs, "ordinary").writeText("rootfs")

        try {
            clearRootfsForReinstall(rootfs)
            throw AssertionError("expected unsafe backing path to be refused")
        } catch (_: IOException) {
            assertEquals("keep", File(external, "user-data").readText())
            assertEquals("rootfs", File(rootfs, "ordinary").readText())
        }
    }

    @Test
    fun `reset does not follow unrelated symlinks out of rootfs`() {
        val rootfs = File(temp.root, "rootfs").apply { mkdirs() }
        val external = temp.newFolder("external")
        File(external, "user-data").writeText("keep")
        Files.createSymbolicLink(File(rootfs, "external-link").toPath(), external.toPath())

        clearRootfsForReinstall(rootfs)

        assertFalse(File(rootfs, "external-link").exists())
        assertEquals("keep", File(external, "user-data").readText())
    }

    @Test
    fun `only the extension's own backing files are recognised`() {
        // [incr1116-CD F-01] `safeResolveWithin` refuses anything that
        // canonicalises outside its base — correct for an escape, wrong for a
        // faked hard link, which resolves to the backing file by construction.
        // The predicate admits exactly one shape: a `.l2s.` leaf directly in a
        // `.l2s-backing` directory.
        val sep = File.separatorChar
        assertTrue(
            isL2sBackingTarget("/data/user/0/pkg/files/alpine-rootfs/.l2s-backing/.l2s.notes0001.0002"),
        )
        assertFalse("a normal file is never admitted", isL2sBackingTarget("/etc/passwd"))
        assertFalse(
            "the directory itself is not a file target",
            isL2sBackingTarget("/data/user/0/pkg/files/alpine-rootfs/.l2s-backing"),
        )
        assertFalse(
            "a nested path under the backing dir is not the shape link2symlink writes",
            isL2sBackingTarget("/rootfs/.l2s-backing/nested/.l2s.x0001.0002"),
        )
        assertFalse(
            "the prefix alone, in any other directory, is not enough",
            isL2sBackingTarget("/var/minis/workspace/.l2s.something"),
        )
        assertTrue(
            "Windows-style separators are not built here, but the check must not depend on the leaf hanging off '/'",
            isL2sBackingTarget("/rootfs${sep}.l2s-backing${sep}.l2s.a0001.0002"),
        )
    }

    @Test
    fun `a read-only directory does not abort the reset`() {
        // [incr1116-CD F-04] Trees that came out of an archive with `0555` were
        // a permanent reset failure: `Files.delete` throws where
        // `deleteRecursively()` returned false, the walk stopped half-way and the
        // install failed -- and nothing ever makes that directory writable again.
        // The walk now restores the owner-write bit and keeps going.
        val rootfs = temp.newFolder("rootfs-ro")
        val locked = File(rootfs, "rodir").apply { mkdirs() }
        File(locked, "payload.txt").writeText("x")
        locked.setWritable(false, false)
        try {
            clearRootfsForReinstall(rootfs)
            assertFalse("the read-only tree must still be gone", File(rootfs, "rodir").exists())
            assertTrue("the walk must finish and leave the root itself", rootfs.isDirectory)
        } finally {
            locked.setWritable(true, true)
            File(locked, "payload.txt").setWritable(true, true)
        }
    }
}
