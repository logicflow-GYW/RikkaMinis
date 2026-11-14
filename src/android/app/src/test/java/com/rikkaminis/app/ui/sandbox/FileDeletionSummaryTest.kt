package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.sandbox.internalGuestTmpDeleteTreeNoFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [RC-1114 U-06] The delete-confirmation count.
 *
 * The entry this protects: the file browser offered NO delete affordance for
 * directories, while the view model already had a recursive branch — so a user
 * could create a folder here and never remove it. Opening that path requires
 * the dialog to state what leaves the disk, and the count must agree with the
 * delete in every shape the delete distinguishes (recursive vs unlink).
 */
class FileDeletionSummaryTest {

    private fun item(f: File, dir: Boolean, link: Boolean = false) =
        FileItem(
            file = f,
            name = f.name,
            isDirectory = dir,
            isSymlink = link,
            size = if (dir) 0L else f.length(),
        )

    private fun tmp(): File = Files.createTempDirectory("rc1114-u06").toFile()

    @Test
    fun aFile_countsZero() {
        val dir = tmp()
        val f = File(dir, "a.txt").apply { writeText("x") }
        assertEquals(0, countEntriesUnder(item(f, dir = false)))
        dir.deleteRecursively()
    }

    @Test
    fun aSymlinkToADirectory_countsZero_becauseTheNameIsUnlinked() {
        // [R4-V F-11] A directory-shaped link is unlinked by NAME; walking
        // through it would both miscount and (if the count drove the delete)
        // wipe the target. The counter must use the same predicate as the
        // delete for exactly this reason.
        val dir = tmp()
        val target = File(dir, "target").apply { mkdirs(); File(this, "x.txt").writeText("x") }
        val link = File(dir, "link")
        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false // filesystem without symlink support: nothing to assert
        }
        if (created) {
            assertEquals(0, countEntriesUnder(item(link, dir = true, link = true)))
        }
        assertTrue(target.exists()) // the count must never touch the target
        dir.deleteRecursively()
    }

    @Test
    fun anEmptyDirectory_countsZero_andTheDialogFallsBackToThePlainMessage() {
        val dir = tmp()
        val d = File(dir, "empty").apply { mkdirs() }
        assertEquals(0, countEntriesUnder(item(d, dir = true)))
        dir.deleteRecursively()
    }

    @Test
    fun aFlatDirectory_countsEveryChild_notTheDirectoryItself() {
        val dir = tmp()
        val d = File(dir, "flat").apply { mkdirs() }
        File(d, "a").writeText("1")
        File(d, "b").writeText("2")
        File(d, "c").writeText("3")
        // 3, not 4: the dialog says "N items inside", and the directory itself
        // is what the user already named in the title.
        assertEquals(3, countEntriesUnder(item(d, dir = true)))
        dir.deleteRecursively()
    }

    @Test
    fun nestedDirectories_countTheirContentsRecursively() {
        val dir = tmp()
        val root = File(dir, "root").apply { mkdirs() }
        val sub = File(root, "sub").apply { mkdirs() }
        File(root, "top.txt").writeText("1")
        File(sub, "deep.txt").writeText("2")
        val deeper = File(sub, "deeper").apply { mkdirs() }
        File(deeper, "deepest.txt").writeText("3")
        // top.txt + sub + deep.txt + deeper + deepest.txt = 5
        assertEquals(5, countEntriesUnder(item(root, dir = true)))
        dir.deleteRecursively()
    }

    @Test
    fun theCountMatchesWhatTheNoFollowDeleteActuallyRemoves() {
        // The real contract: the number the user approved equals the number of
        // entries that disappear. Measured, not assumed.
        //
        // [review-1114 A] The contrast arm calls the SAME walker the view model
        // now calls. It used to call `root.deleteRecursively()` as the ground
        // truth — that made the assertion a comparison between the counter and
        // the very implementation the counter was supposed to police, so a
        // walk that followed nested links would have agreed with a delete that
        // followed nested links and this test would have stayed green.
        val dir = tmp()
        val root = File(dir, "root").apply { mkdirs() }
        File(root, "a.txt").writeText("1")
        File(root, "sub").mkdirs()
        File(File(root, "sub"), "b.txt").writeText("2")
        val counted = countEntriesUnder(item(root, dir = true))
        val before = root.walkTopDown().count() - 1 // exclude root itself
        assertEquals(before, counted)
        assertTrue(internalGuestTmpDeleteTreeNoFollow(root.toPath()))
        assertTrue(!root.exists())
        dir.deleteRecursively()
    }
}
