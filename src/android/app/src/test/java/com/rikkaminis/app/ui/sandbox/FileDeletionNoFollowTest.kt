package com.rikkaminis.app.ui.sandbox

import com.rikkaminis.app.sandbox.internalGuestTmpDeleteTreeNoFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [review-1114 A] Deleting a directory must never reach THROUGH a link.
 *
 * `File.deleteRecursively()` walks with `File.listFiles()`, which follows
 * links, so a *plain* directory containing `link -> outside` had its delete
 * recurse into `outside` and wipe files that were never in the subtree the
 * user picked. The top-level link case was already handled by
 * [shouldDeleteRecursively] (a directory-shaped link is unlinked by name); the
 * nested case was not, and it became reachable the day the trash affordance
 * was enabled for directories.
 *
 * Both arms below pair a counter with the delete: a count and a removal that
 * disagree are two bugs, not one.
 */
class FileDeletionNoFollowTest {

    private fun item(f: File, dir: Boolean, link: Boolean = false) =
        FileItem(
            file = f,
            name = f.name,
            isDirectory = dir,
            isSymlink = link,
            size = if (dir) 0L else f.length(),
        )

    private fun tmp(): File = Files.createTempDirectory("review1114-a").toFile()

    /**
     * The shape from the report: victim holds one real file and one link whose
     * target holds two files. Deleting victim removes 2 entries from disk
     * (own.txt, link) and 0 from outside — so the dialog must say 2, not 4.
     */
    @Test
    fun aNestedSymlinkIsCountedAsOneEntry_notItsTargetsContents() {
        val base = tmp()
        val outside = File(base, "outside").apply {
            mkdirs()
            File(this, "keep1.txt").writeText("1")
            File(this, "keep2.txt").writeText("2")
        }
        val victim = File(base, "victim").apply {
            mkdirs()
            File(this, "own.txt").writeText("own")
        }
        val link = File(victim, "link")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false // filesystem without symlink support: nothing to assert
        }
        if (created) {
            // 2 = own.txt + link. The old listFiles() walk answered 4 here
            // because it also counted keep1/keep2 through the link — which was
            // exactly what the old delete did to them.
            assertEquals(2, countEntriesUnder(item(victim, dir = true)))
        }
        assertTrue(outside.exists())
        assertEquals(2, outside.listFiles()!!.size)
        base.deleteRecursively()
    }

    /**
     * The behavioural half: after the real delete, the victim is gone, the
     * link name is gone, and the target tree is untouched.
     */
    @Test
    fun deletingADirectoryNeverReachesThroughANestedSymlink() {
        val base = tmp()
        val outside = File(base, "outside").apply {
            mkdirs()
            File(this, "keep1.txt").writeText("1")
            File(this, "keep2.txt").writeText("2")
        }
        val victim = File(base, "victim").apply {
            mkdirs()
            File(this, "own.txt").writeText("own")
        }
        val link = File(victim, "link")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (created) {
            assertTrue(internalGuestTmpDeleteTreeNoFollow(victim.toPath()))
            assertFalse(victim.exists())
            assertFalse(link.exists()) // the NAME is unlinked
            assertTrue(outside.exists()) // … and the target survives whole
            assertEquals(2, outside.listFiles()!!.size)
            assertTrue(File(outside, "keep1.txt").exists())
            assertTrue(File(outside, "keep2.txt").exists())
        }
        base.deleteRecursively()
    }

    /**
     * Guard arm: the symlink-free shape must keep working (a fix that simply
     * refused every directory would pass the two arms above).
     */
    @Test
    fun aPlainDirectoryIsStillDeletedEntirely() {
        val base = tmp()
        val root = File(base, "root").apply { mkdirs() }
        File(root, "a.txt").writeText("1")
        File(File(root, "sub").apply { mkdirs() }, "b.txt").writeText("2")
        assertEquals(3, countEntriesUnder(item(root, dir = true)))
        assertTrue(internalGuestTmpDeleteTreeNoFollow(root.toPath()))
        assertFalse(root.exists())
        base.deleteRecursively()
    }

    /**
     * Wiring arm — the behavioural tests above prove the WALKER is safe; this
     * proves the browser actually uses it.
     *
     * Without it the fix is one refactor away from silently reverting:
     * `deleteItem` is a 60-line coroutine that cannot be compiled on the JVM
     * (ViewModel + Compose), so nothing else in this suite would notice
     * `internalGuestTmpDeleteTreeNoFollow(...)` being swapped back for
     * `deleteRecursively()`. Structural, same technique as
     * `MarkdownMathSingleSourceShapeTest` in this module.
     */
    @Test
    fun theBrowserDeletesThroughTheNoFollowWalker() {
        val src = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FileBrowserViewModel.kt")
        val deleteItem = functionBody(src, "deleteItem")
        assertFalse(
            "deleteItem must not call deleteRecursively(): it follows a nested symlink " +
                "out of the chosen subtree. Body was:\n$deleteItem",
            deleteItem.contains("deleteRecursively()"),
        )
        assertTrue(
            "deleteItem must route the recursive arm through the no-follow walker; body was:\n$deleteItem",
            deleteItem.contains("internalGuestTmpDeleteTreeNoFollow("),
        )
        // The read-only refusals must stay in the same function as the removal
        // (the scanner anchors on that pair, and a guard whose write moved is a
        // guard nobody can verify).
        assertTrue("deleteItem lost its read-only mount guard", deleteItem.contains("isLinuxPathUnderReadOnlyMount("))
        assertTrue(
            "deleteItem lost its read-only shared-folder guard",
            deleteItem.contains("isLinuxPathUnderReadOnlySharedDir("),
        )
    }

    /** The counter must walk the same way as the removal it predicts. */
    @Test
    fun theCounterDoesNotWalkThroughLinksEither() {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FileDeletionSummary.kt",
        )
        val body = functionBody(src, "countEntriesUnder")
        assertFalse(
            "countEntriesUnder must not use listFiles(): it follows links, which is the bug",
            body.contains("listFiles("),
        )
        assertTrue(
            "countEntriesUnder must use walkFileTree (no FOLLOW_LINKS)",
            body.contains("walkFileTree("),
        )
        assertFalse(
            "countEntriesUnder must not opt into FOLLOW_LINKS",
            body.contains("FOLLOW_LINKS"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }

    /**
     * Braces-matched body of `fun <name>(` — NOT `substring(index)` to EOF.
     * The difference matters: an open-ended slice would pass this test no
     * matter what later functions contain (a shape this codebase has already
     * been bitten by once).
     */
    private fun functionBody(src: String, name: String): String {
        val start = src.indexOf("fun $name(")
        assertTrue("no definition of fun $name( in the file", start >= 0)
        val open = src.indexOf('{', src.indexOf(')', start))
        assertTrue("no body after fun $name(", open >= 0)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(src.substring(open, i + 1))
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in fun $name(")
    }

    /**
     * Drop line and block comments.
     *
     * Mandatory before a "this call must not appear in the body" assertion: the
     * fix DOCUMENTS the bug it prevents (`// never deleteRecursively() here`),
     * and a raw text search reads its own explanation as the offence. Comment
     * stripping also makes the assertion grade code, not prose.
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && i + 1 < src.length && src[i + 1] == '/') {
                    inBlock = false; i++
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i++ }
                    else if (c == '"') inString = false
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> inLine = true
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> { inBlock = true; i++ }
                c == '"' -> { inString = true; sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
