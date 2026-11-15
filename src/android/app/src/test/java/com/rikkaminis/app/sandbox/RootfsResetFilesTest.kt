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
        File(rootfs, "etc/obsolete.conf").apply { parentFile.mkdirs(); writeText("old") }

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
}
