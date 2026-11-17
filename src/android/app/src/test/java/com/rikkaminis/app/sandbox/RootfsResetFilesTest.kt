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
    fun `an unsearchable rootfs keeps the L2S backing`() {
        // [C1.1] `Files.exists` answers false for EACCES exactly as it does for
        // ENOENT, and the reset decided `preserveBacking` from that one answer.
        // With `chmod 000` on the rootfs itself, the pre-flight lstat of
        // `<rootfs>/.l2s-backing` fails with EACCES -> "no backing" -> the walk
        // deletes the whole tree, i.e. every faked-hard-link target the user
        // owns. An unanswerable question must never be read as "absent".
        val files = temp.newFolder("files-nosearch")
        val rootfs = File(files, "alpine-rootfs").apply { mkdirs() }
        val backing = File(rootfs, L2S_BACKING_DIR_NAME).apply { mkdirs() }
        // Two probes on purpose. The plain name is the one that can red in a
        // sandbox whose PRoot hides `.l2s.*` leaves from readdir (there the
        // pre-fix delete of the backing fails on ENOTEMPTY and the bug shows up
        // only as "the contents are gone"); the `.l2s.` name is the real shape
        // link2symlink writes and is what CI enforces byte-for-byte.
        val keptPlain = File(backing, "kept-model.bin").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val target = File(backing, ".l2s.model0001.0002").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        File(rootfs, "ordinary.conf").writeText("rootfs")
        assertTrue(rootfs.setReadable(false, false))
        assertTrue(rootfs.setExecutable(false, false))
        org.junit.Assume.assumeFalse("mode bits are not enforced for root", rootfs.canExecute())
        try {
            clearRootfsForReinstall(rootfs)

            // Restore the bits before asking questions: "exists() == false" on a
            // child of an unsearchable directory is unreadable, not absent.
            rootfs.setReadable(true, true)
            rootfs.setExecutable(true, true)
            assertTrue("the rootfs directory itself must survive", rootfs.isDirectory)
            assertTrue("the backing directory must survive", backing.isDirectory)
            assertArrayEquals(
                "every faked-hard-link target must survive",
                byteArrayOf(7, 8, 9),
                Files.readAllBytes(target.toPath()),
            )
            assertArrayEquals(
                "nothing inside the backing directory may be deleted",
                byteArrayOf(4, 5, 6),
                Files.readAllBytes(keptPlain.toPath()),
            )
            assertFalse("ordinary content is still removed", File(rootfs, "ordinary.conf").exists())
        } finally {
            rootfs.setReadable(true, true)
            rootfs.setExecutable(true, true)
            rootfs.setWritable(true, true)
        }
    }

    @Test
    fun `an unreadable rootfs keeps its own directory and its parent's mode`() {
        // [C1.2] With `0111` the backing probe succeeds (search needs `x` only),
        // the walk still fails on the rootfs itself, and the subtree delete used
        // to hand the scope root straight to `deleteRestoringOwnerWrite`: the
        // delete of the root fails (the backing is still inside), the retry
        // chmods `rootfsDir.parent` -- the app's private files directory, which
        // holds everything the app owns, not just this rootfs.
        val files = temp.newFolder("files-parent-scope")
        val rootfs = File(files, "alpine-rootfs").apply { mkdirs() }
        val backing = File(rootfs, L2S_BACKING_DIR_NAME).apply { mkdirs() }
        val target = File(backing, ".l2s.notes0001.0002").apply { writeText("links") }
        // 0555 on the host directory: traversable, so every assertion below
        // still runs, and a `u+rwx` grant is visible as a mode change.
        assertTrue(files.setWritable(false, false))
        val parentModeBefore = Files.getPosixFilePermissions(files.toPath())
        assertTrue(rootfs.setReadable(false, false))
        org.junit.Assume.assumeFalse("mode bits are not enforced for root", rootfs.canRead())
        try {
            clearRootfsForReinstall(rootfs)

            assertEquals(
                "nothing above the rootfs may be chmod-ed",
                parentModeBefore,
                Files.getPosixFilePermissions(files.toPath()),
            )
            assertTrue("the rootfs directory itself must survive", rootfs.isDirectory)
            assertTrue("the backing directory must survive", backing.isDirectory)
            assertEquals("links", target.readText())
        } finally {
            files.setWritable(true, true)
            rootfs.setReadable(true, true)
            rootfs.setExecutable(true, true)
            rootfs.setWritable(true, true)
        }
    }

    @Test
    fun `an unreadable rootfs without a backing still keeps its own directory`() {
        // [C1.2] The other half: with no `.l2s-backing` the old subtree delete
        // reached `Files.delete(root)` and simply removed the scope root. The
        // reset contract (see the `postVisitDirectory` note) is that the
        // directory survives -- the caller extracts the new rootfs into it.
        // `0111` and not `000` on purpose: `000` also makes the backing probe
        // fail, so the C1.1 re-probe restores the root and the walk never fails
        // -- this arm has to reach `visitFileFailed(root)` to mean anything.
        val files = temp.newFolder("files-no-backing")
        val rootfs = File(files, "alpine-rootfs").apply { mkdirs() }
        File(rootfs, "ordinary.conf").writeText("rootfs")
        assertTrue(rootfs.setReadable(false, false))
        org.junit.Assume.assumeFalse("mode bits are not enforced for root", rootfs.canRead())
        try {
            clearRootfsForReinstall(rootfs)

            rootfs.setReadable(true, true)
            assertTrue("the rootfs directory itself must survive", rootfs.isDirectory)
            assertFalse("its contents are still removed", File(rootfs, "ordinary.conf").exists())
        } finally {
            rootfs.setReadable(true, true)
            rootfs.setExecutable(true, true)
            rootfs.setWritable(true, true)
        }
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

    @Test
    fun `a non-empty unreadable directory is removed instead of being left behind`() {
        // [C-F01] `chmod 000` on a NON-empty directory: the walk cannot open it,
        // so visitFileFailed fires -- and the 1116-CD F-04 pass restored only
        // `u+w`, which unlinks an empty directory and nothing more.
        // `Files.delete` then threw DirectoryNotEmptyException, `runCatching`
        // swallowed it, and the reset reported success with the whole subtree
        // still on disk. Restoring `u+rwx` and walking the subtree ourselves is
        // the difference between "reset happened" and "reset said it happened".
        val rootfs = temp.newFolder("rootfs-noread")
        val locked = File(rootfs, "noread").apply { mkdirs() }
        File(locked, "payload.txt").writeText("old")
        File(locked, "nested").mkdirs()
        File(locked, "nested/deep.txt").writeText("deep")
        assertTrue(File(locked, "payload.txt").setWritable(false, false))
        assertTrue(locked.setReadable(false, false))
        assertTrue(locked.setExecutable(false, false))
        // A root JVM ignores these bits, so the test would pass for the wrong
        // reason there -- skip rather than report a false green.
        org.junit.Assume.assumeFalse("mode bits are not enforced for root", locked.canRead())
        try {
            clearRootfsForReinstall(rootfs)

            assertFalse("the unreadable subtree must be gone", locked.exists())
            assertTrue("the walk must still leave the root itself", rootfs.isDirectory)
        } finally {
            locked.setReadable(true, true)
            locked.setExecutable(true, true)
            locked.setWritable(true, true)
            File(locked, "nested/deep.txt").setWritable(true, true)
            File(locked, "payload.txt").setWritable(true, true)
        }
    }

    @Test
    fun `unlinking a symlink in a read-only directory never chmods its target`() {        // The reset restores owner access before retrying a delete, and chmod
        // FOLLOWS a symlink: without the guard in restoreOwnerAccess, cleaning
        // up a link that lives inside a read-only directory would rewrite the
        // mode of whatever the link points at, outside the rootfs. A link never
        // needs its own bits -- it is unlinked through its parent.
        val rootfs = temp.newFolder("rootfs-ro-link")
        val external = temp.newFolder("external")
        val keep = File(external, "user-data").apply { writeText("keep") }
        assertTrue(keep.setWritable(false, false))
        val before = Files.getPosixFilePermissions(keep.toPath())
        val locked = File(rootfs, "rodir").apply { mkdirs() }
        Files.createSymbolicLink(File(locked, "escape").toPath(), keep.toPath())
        assertTrue(locked.setWritable(false, false))
        try {
            clearRootfsForReinstall(rootfs)

            assertFalse("the read-only directory must be gone", File(rootfs, "rodir").exists())
            assertEquals("the link target must be untouched", "keep", keep.readText())
            assertEquals(
                "the link target's mode must be untouched",
                before,
                Files.getPosixFilePermissions(keep.toPath()),
            )
        } finally {
            locked.setWritable(true, true)
            keep.setWritable(true, true)
        }
    }
}
