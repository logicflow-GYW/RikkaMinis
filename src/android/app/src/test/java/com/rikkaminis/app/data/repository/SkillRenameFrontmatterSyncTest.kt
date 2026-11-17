package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W1118-D5 F-06] `renameSkill` rewrites the `name:` line of the on-disk
 * SKILL.md and the `name` of the DB row / StateFlow entry, but copied only
 * `name` + `updatedAt` into the in-memory [SkillRepository.Skill] — so the
 * `frontmatter` block still carried `name: Old Name`. Anything that hands a
 * skill's frontmatter onwards without re-reading disk (export, writeSkillMd's
 * merge base) then resurrected the old name. [SkillRepository.update] and
 * [SkillRepository.rescanFromDisk] both refresh the block; this path did not.
 *
 * The rewrite itself ([SkillRepository.renameFrontmatterName]) is unchanged and
 * already JVM-tested; what is pinned here is the row the repository keeps.
 */
class SkillRenameFrontmatterSyncTest {

    private val now = 1_700_000_000_000L

    private fun current(name: String = "Old Name") = SkillRepository.Skill(
        id = "demo",
        name = name,
        version = "1.0.0",
        frontmatter = "---\nname: $name\ndescription: d\n---\n",
    )

    @Test
    fun `the in memory frontmatter follows the renamed document`() {
        val before = current()
        // Use the production rewrite, not a hand-written document: what the
        // repository writes to disk is what its memory copy must mirror.
        val renamed = requireNotNull(
            SkillRepository.renameFrontmatterName(before.frontmatter, "New Name"),
        )
        val after = SkillRepository.skillAfterRename(before, "New Name", renamed, now)

        assertEquals("New Name", after.name)
        assertTrue("frontmatter kept the old name: ${after.frontmatter}", after.frontmatter.contains("name: New Name"))
        assertTrue(!after.frontmatter.contains("name: Old Name"))
        assertEquals(now, after.updatedAt)
    }

    @Test
    fun `extended frontmatter keys survive the rename`() {
        val before = SkillRepository.Skill(
            id = "demo",
            name = "Old Name",
            frontmatter = "---\nname: Old Name\nsubagent: true\nmax_turns: 7\n---\n",
        )
        val renamed = requireNotNull(SkillRepository.renameFrontmatterName(before.frontmatter, "New Name"))
        val after = SkillRepository.skillAfterRename(before, "New Name", renamed, now)

        assertTrue(after.frontmatter.contains("subagent: true"))
        assertTrue(after.frontmatter.contains("max_turns: 7"))
        assertTrue(after.frontmatter.contains("name: New Name"))
    }

    @Test
    fun `nothing to rewrite keeps the previous block`() {
        // renameFrontmatterName returns null when the document has no
        // frontmatter or no `name:` line — the file is left untouched, so the
        // memory copy must not be blanked either.
        val before = current()
        val after = SkillRepository.skillAfterRename(before, "New Name", null, now)
        assertEquals(before.frontmatter, after.frontmatter)
        assertEquals("New Name", after.name)
    }

    @Test
    fun `a document without a frontmatter block does not wipe the old one`() {
        val before = current()
        val after = SkillRepository.skillAfterRename(before, "New Name", "# New Name\n\nbody\n", now)
        assertEquals(before.frontmatter, after.frontmatter)
    }
}
