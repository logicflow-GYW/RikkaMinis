package com.rikkaminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [mirror-filetarget-1114] The external mirror writes plain files.
 *
 * What this pins is the class of defect that produced the 2026-11-14 incident:
 * the mirror ran through `MediaStore.Downloads`, MediaProvider rewrote
 * `text/plain` display names (`memory/x.md` → `x.md.txt`), every lookup by the
 * logical name missed, and so the mirror forked a new copy on every overwrite,
 * never read anything back (`previous/` archived nothing, ever), and reported
 * successful deletes for rows it never found. It looked healthy while serving
 * 16-day-old bytes.
 *
 * None of that is reproducible here — which is exactly the point. `FileTarget`
 * is the whole writer now, it is plain `java.io`/`java.nio`, and every one of
 * those three behaviours is a test below. A future refactor back to a
 * ContentResolver-based writer would have to delete this file to pass.
 */
class MirrorFileTargetTest {

    private val root: File = Files.createTempDirectory("mirror-filetarget").toFile()
    private val base = File(root, "backup")
    private val target = FileTarget(base)

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    // ------------------------------------------------- the three lost paths

    @Test
    fun overwriteReplacesUnderTheSameNameAndNeverForks() {
        assertTrue(target.write("mirror/current/memory/2026-11-14.md", "v1".toByteArray()))
        assertTrue(target.write("mirror/current/memory/2026-11-14.md", "v2-longer".toByteArray()))

        assertEquals(
            "the second write must land on the same path — a forked copy is what MediaProvider " +
                "did, and it left the mirror serving the first version forever",
            "v2-longer",
            target.read("mirror/current/memory/2026-11-14.md")?.toString(Charsets.UTF_8),
        )
        assertEquals(
            "exactly one file: `a.md`, `a.md.txt` and `a.md (1).txt` in one directory is the " +
                "symptom this whole change exists to remove",
            listOf("2026-11-14.md"),
            File(base, "mirror/current/memory").list()!!.sorted(),
        )
    }

    @Test
    fun readAnswersTheSamePathThatWasWritten() {
        // `read()` returning null is what silently disabled `previous/`: the
        // archive loop treats "no old bytes" as "nothing to preserve", so the
        // rollback history was empty from the first day and nothing said so.
        assertNull(target.read("mirror/current/memory/absent.md"))
        target.write("mirror/current/memory/present.md", "bytes".toByteArray())
        assertEquals("bytes", target.read("mirror/current/memory/present.md")?.toString(Charsets.UTF_8))
    }

    @Test
    fun deleteRemovesTheFileAndIsIdempotentOnlyWhenNothingIsThere() {
        target.write("mirror/current/shared/backlog.md", "x".toByteArray())
        assertTrue(target.delete("mirror/current/shared/backlog.md"))
        assertNull(target.read("mirror/current/shared/backlog.md"))
        assertFalse(
            "the file is gone from disk, not just from an index",
            File(base, "mirror/current/shared/backlog.md").exists(),
        )
        // Idempotent cleanup: deleting an absent path is success (the manifest
        // can lag a manual deletion the user made in a file manager).
        assertTrue(target.delete("mirror/current/shared/backlog.md"))
    }

    // ------------------------------------------------------- the write shape

    @Test
    fun writeLeavesNoTempFileBehind() {
        target.write("auto/rikkaminis-auto-20261114-000000.json", "{}".toByteArray())
        assertEquals(
            listOf("rikkaminis-auto-20261114-000000.json"),
            File(base, "auto").list()!!.sorted(),
        )
    }

    @Test
    fun writeCreatesMissingDirectories() {
        assertTrue(target.write("mirror/previous/20261114-200732/memory/deep/x.md", "d".toByteArray()))
        assertTrue(File(base, "mirror/previous/20261114-200732/memory/deep/x.md").isFile)
    }

    // ------------------------------------------------------ deleteTree safety

    @Test
    fun deleteTreeRemovesTheWholePoint() {
        target.write("mirror/previous/20261029-090000/memory/a.md", "1".toByteArray())
        target.write("mirror/previous/20261029-090000/memory/b.md", "2".toByteArray())
        assertTrue(target.deleteTree("mirror/previous/20261029-090000/"))
        assertFalse(
            "a rotated point is gone, not just emptied",
            File(base, "mirror/previous/20261029-090000").exists(),
        )
    }

    @Test
    fun deleteTreeNeverFollowsALinkOutOfTheMirror() {
        // The mirror's own tree holds no links, but it lives in the user's
        // Downloads. `File.deleteRecursively()` follows `isDirectory()`, which
        // follows links — so a single `ln -s` there would make the `previous/`
        // rotation delete files outside the mirror. Measured here the way the
        // shipped code runs, on a real filesystem, not by reading the source.
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("do not delete me")
        val victim = File(base, "mirror/previous/20261114-000000")
        File(victim, "memory").mkdirs()
        File(victim, "memory/a.md").writeText("payload")
        val link = File(victim, "link-to-outside")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertTrue(target.deleteTree("mirror/previous/20261114-000000/"))

        assertFalse("the point itself is gone", victim.exists())
        assertTrue("the link target was NOT emptied through the link", File(outside, "keep.txt").isFile)
        assertEquals("do not delete me", File(outside, "keep.txt").readText())
    }

    @Test
    fun deleteTreeOnAnAbsentPrefixIsSuccess() {
        assertTrue(target.deleteTree("mirror/previous/does-not-exist/"))
    }

    // ------------------------------------------------------------------ list

    @Test
    fun listReturnsPathsRelativeToBaseAndSkipsAbsentDirs() {
        target.write("auto/rikkaminis-auto-20261114-000000.json", "{}".toByteArray())
        assertEquals(
            listOf("auto/rikkaminis-auto-20261114-000000.json"),
            target.list("auto/"),
        )
        assertEquals(emptyList<String>(), target.list("nope/"))
    }
}
