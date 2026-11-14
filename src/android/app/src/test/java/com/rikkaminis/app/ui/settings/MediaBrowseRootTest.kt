package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [RC-1114 U-08] Which directory the storage detail page browses to for a
 * session's media.
 *
 * The entry this protects: the Media row displayed bytes and did nothing, so
 * the only way to reclaim a single large attachment was "Clear session", which
 * also deletes the workspace. The rule under test is "never strand a file":
 * one leaf ⇒ land on it; several ⇒ root at the shared parent, because the
 * browser clamps Back to the directory it opened at.
 */
class MediaBrowseRootTest {

    private fun mediaRoot(): File =
        File(Files.createTempDirectory("rc1114-u08").toFile(), "media").apply { mkdirs() }

    private fun leaf(parent: File, name: String): File = File(parent, name).apply { mkdirs() }

    @Test
    fun oneLeaf_rootsAtTheLeafItself() {
        val root = mediaRoot()
        val a = leaf(root, "2026-11-14")
        assertEquals(a, mediaBrowseRoot(listOf(a), root))
    }

    @Test
    fun severalLeaves_rootAtTheSharedParent_notAnArbitraryPick() {
        // Picking any single leaf would make the others unreachable: the
        // browser clamps "back" to the directory it opened at.
        val root = mediaRoot()
        val a = leaf(root, "2026-11-13")
        val b = leaf(root, "2026-11-14")
        assertEquals(root, mediaBrowseRoot(listOf(a, b), root))
    }

    @Test
    fun zeroLeaves_stillRootAtTheMediaRoot() {
        // The row is only offered when bytes were measured, so an empty list
        // means the tree moved between the scan and the tap — showing the media
        // root beats a dialog that says "no files" while the size says
        // otherwise.
        val root = mediaRoot()
        assertEquals(root, mediaBrowseRoot(emptyList(), root))
    }

    @Test
    fun theChosenRootAlwaysContainsEveryLeaf() {
        // The property that matters, asserted directly for both shapes.
        val root = mediaRoot()
        val a = leaf(root, "2026-11-13")
        val b = leaf(root, "2026-11-14")
        for (dirs in listOf(listOf(a), listOf(a, b))) {
            val chosen = mediaBrowseRoot(dirs, root)
            for (leafDir in dirs) {
                assertTrue(
                    "chosen root ${chosen.path} must contain ${leafDir.path}",
                    leafDir.path == chosen.path || leafDir.path.startsWith(chosen.path + File.separator),
                )
            }
        }
    }
}
