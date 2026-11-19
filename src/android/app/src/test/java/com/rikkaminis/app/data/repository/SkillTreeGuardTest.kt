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
 * [fix/r3-data-1119 F-08] Deleting a skill must not follow a symlink.
 *
 * The skills directory is a shared mount the user and the agent can write
 * into, so a skill can legitimately contain a symlink (a `git clone` brings
 * one in, or a user makes one by hand). Both findings are that fact meeting
 * code that trusted the filesystem:
 *
 *  - F-08: `deleteRecursively()` followed a directory link and deleted the
 *    tree it pointed at — deleting a skill could wipe the user's real memory
 *    directory, irreversibly.
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
}
