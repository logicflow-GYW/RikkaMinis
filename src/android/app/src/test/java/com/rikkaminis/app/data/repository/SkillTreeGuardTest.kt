package com.rikkaminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [fix/r3-data-1119 F-08 / F-09] The restricted traversal for the skills tree.
 *
 * The skills directory is a shared mount the user and the agent can write
 * into, so a skill can legitimately contain a symlink (a `git clone` brings
 * one in, or a user makes one by hand). Both findings are that fact meeting
 * code that trusted the filesystem:
 *
 *  - F-08: `deleteRecursively()` followed a directory link and deleted the
 *    tree it pointed at — deleting a skill could wipe the user's real memory
 *    directory, irreversibly.
 *  - F-09: `walkTopDown()` followed links with no cycle detection — a
 *    `loop -> .` link walked forever (OOM), and a link to a file outside the
 *    skill root let the export pack a private file into a shareable archive.
 *
 * Every test runs against a JUnit temp directory. NOTHING here touches the
 * real `/var/minis/memory` or `/var/minis/skills`: the "outside" tree is a
 * sibling temp dir, and the assertions are on that sibling's files.
 */
class SkillTreeGuardTest {

    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        val base = Files.createTempDirectory("skill-tree-guard").toFile()
        root = File(base, "skills/demo-skill").apply { mkdirs() }
        outside = File(base, "memory").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        // Best effort: the "outside" tree is what F-08 must have left alone,
        // so a failed cleanup here would show up as a confusing leftover.
        SkillTreeGuard.deleteTree(root.parentFile)
        SkillTreeGuard.deleteTree(outside)
    }

    private fun write(file: File, text: String = "x") {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun symlink(link: File, target: File) {
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
    }

    // ── F-08: delete never follows a link ────────────────────────────────

    @Test
    fun `delete removes the skill but leaves a linked external tree intact`() {
        write(File(root, "SKILL.md"), "---\nname: demo\n---\nbody")
        write(File(root, "scripts/run.sh"), "#!/bin/sh")
        write(File(outside, "GLOBAL.md"), "user memory — must survive")
        write(File(outside, "nested/keep.txt"), "also must survive")
        symlink(File(root, "ref"), outside)

        assertTrue("deleteTree reports success", SkillTreeGuard.deleteTree(root))

        assertFalse("the skill dir is gone", root.exists())
        assertTrue("the external tree survived", outside.isDirectory)
        assertEquals("user memory — must survive", File(outside, "GLOBAL.md").readText())
        assertTrue("a nested external file survived", File(outside, "nested/keep.txt").isFile)
    }

    @Test
    fun `delete removes a link that points outside without touching its target`() {
        write(File(outside, "GLOBAL.md"), "keep me")
        symlink(File(root, "leak.txt"), File(outside, "GLOBAL.md"))

        assertTrue(SkillTreeGuard.deleteTree(root))

        assertFalse("the skill dir is gone", root.exists())
        assertTrue("the linked file itself survived", File(outside, "GLOBAL.md").isFile)
        assertEquals("keep me", File(outside, "GLOBAL.md").readText())
    }

    @Test
    fun `delete removes a normal directory tree completely (negative control)`() {
        write(File(root, "SKILL.md"), "s")
        write(File(root, "scripts/a.py"), "a")
        write(File(root, "scripts/nested/b.py"), "b")
        write(File(root, "references/doc.md"), "d")

        assertTrue(SkillTreeGuard.deleteTree(root))

        assertFalse("a normal skill tree is fully removed", root.exists())
    }

    @Test
    fun `delete of an absent tree reports success`() {
        assertTrue(SkillTreeGuard.deleteTree(File(root, "never-existed")))
    }

    // ── F-09: the walk terminates, stays in tree, and skips links ────────

    @Test
    fun `a symlink cycle terminates instead of hanging`() {
        write(File(root, "SKILL.md"), "s")
        write(File(root, "scripts/a.py"), "a")
        // The pre-fix shape: `ln -s . loop` — walkTopDown descends forever.
        symlink(File(root, "loop"), root)
        symlink(File(root, "scripts/back"), File(root, "scripts"))

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(listOf("SKILL.md", "scripts/a.py"), files.sorted())
    }

    @Test
    fun `the depth limit stops a pathologically deep tree`() {
        // The visited set only catches a cycle that reaches the same canonical
        // directory twice, which on Linux needs a bind mount. The depth limit
        // is the hard stop that needs no such premise — so a file BELOW the
        // limit is what proves the limit is enforced.
        write(File(root, "SKILL.md"), "s")
        var dir = root
        for (i in 0 until SkillTreeGuard.MAX_DEPTH + 5) {
            dir = File(dir, "d$i").apply { mkdirs() }
        }
        write(File(dir, "too-deep.txt"), "unreachable")

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals("only the shallow file is reachable", listOf("SKILL.md"), files)
    }

    @Test
    fun `a file just inside the depth limit is still reachable`() {
        write(File(root, "SKILL.md"), "s")
        var dir = root
        // MAX_DEPTH levels of nesting: the deepest directory is at depth
        // MAX_DEPTH, so its file is still listed.
        for (i in 0 until SkillTreeGuard.MAX_DEPTH) {
            dir = File(dir, "d$i").apply { mkdirs() }
        }
        write(File(dir, "deep.txt"), "reachable")

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(2, files.size)
        assertTrue(files.any { it.endsWith("deep.txt") })
    }

    @Test
    fun `a link to a file outside the skill root is never listed`() {
        write(File(root, "SKILL.md"), "s")
        write(File(outside, "GLOBAL.md"), "secret")
        symlink(File(root, "leak.txt"), File(outside, "GLOBAL.md"))

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(listOf("SKILL.md"), files)
        assertFalse("the linked name must not appear", files.any { it.contains("leak") })
    }

    @Test
    fun `a link to a directory outside the skill root is not descended into`() {
        write(File(root, "SKILL.md"), "s")
        write(File(outside, "GLOBAL.md"), "secret")
        write(File(outside, "nested/deep.txt"), "secret")
        symlink(File(root, "ref"), outside)

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(listOf("SKILL.md"), files)
    }

    @Test
    fun `a link inside the root is not followed and not listed`() {
        write(File(root, "SKILL.md"), "s")
        write(File(root, "scripts/a.py"), "a")
        // Two links INSIDE the root, so the prefix guard cannot catch either:
        // only "never follow / never list a link" keeps the listing honest.
        //  - the FILE link is the independent witness for that rule: with the
        //    symlink skip removed, `isFile` follows the link and the duplicate
        //    name appears in the listing (and in the exported zip);
        //  - the DIRECTORY link would be caught by the visited set as well,
        //    which is why the file link is what this test leans on.
        symlink(File(root, "link.py"), File(root, "scripts/a.py"))
        symlink(File(root, "linkdir"), File(root, "scripts"))

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(listOf("SKILL.md", "scripts/a.py"), files.sorted())
        assertFalse("a file link must not be listed", files.any { it == "link.py" })
        assertFalse("a directory link must not be descended into",
            files.any { it.startsWith("linkdir/") })
    }

    @Test
    fun `a normal deep tree is listed completely (negative control)`() {
        write(File(root, "SKILL.md"), "s")
        write(File(root, "scripts/a.py"), "a")
        write(File(root, "scripts/nested/b.py"), "b")
        write(File(root, "references/deep/dir/doc.md"), "d")

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(
            listOf(
                "SKILL.md",
                "references/deep/dir/doc.md",
                "scripts/a.py",
                "scripts/nested/b.py",
            ),
            files.sorted(),
        )
    }

    @Test
    fun `hidden entries are skipped`() {
        write(File(root, "SKILL.md"), "s")
        write(File(root, ".DS_Store"), "junk")
        write(File(root, "scripts/.swp"), "junk")

        val files = SkillTreeGuard.listFilesUnder(root)

        assertEquals(listOf("SKILL.md"), files)
    }

    @Test
    fun `a non-directory root lists nothing`() {
        val file = File(root, "SKILL.md").apply { parentFile?.mkdirs(); writeText("s") }

        assertEquals(emptyList<String>(), SkillTreeGuard.listFilesUnder(file))
        assertEquals(emptyList<String>(), SkillTreeGuard.listFilesUnder(File(root, "absent")))
    }

    // ── the containment predicate itself ─────────────────────────────────

    @Test
    fun `isWithinRoot does not treat a sibling with a shared prefix as inside`() {
        // The classic string-startsWith bug: /root/ab starts with /root/a.
        assertTrue(SkillTreeGuard.isWithinRoot("/root/a", "/root/a"))
        assertTrue(SkillTreeGuard.isWithinRoot("/root/a/b", "/root/a"))
        assertFalse(SkillTreeGuard.isWithinRoot("/root/ab", "/root/a"))
        assertFalse(SkillTreeGuard.isWithinRoot("/root", "/root/a"))
    }

    @Test
    fun `isInside resolves dot dot escapes`() {
        write(File(root, "SKILL.md"), "s")
        write(File(outside, "GLOBAL.md"), "secret")

        assertTrue(SkillTreeGuard.isInside(root, File(root, "SKILL.md")))
        assertFalse(
            "a `..` path that resolves outside the root is not inside it",
            SkillTreeGuard.isInside(root, File(root, "../memory/GLOBAL.md")),
        )
        assertFalse(
            "an absolute path outside the root is not inside it",
            SkillTreeGuard.isInside(root, File(outside, "GLOBAL.md")),
        )
    }
}
