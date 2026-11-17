package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W1118-D5 F-01] The auto-discover branch of [SkillRepository.loadAll]
 * (`SKILL.md` on disk, no DB row yet) used to hand-build the `Skill(...)` and
 * omit `version`, so a skill whose frontmatter declared `version: 2.3.0` was
 * listed — and written into the `skills` table — as the constructor default
 * `"1.0.0"` for the rest of the process.
 *
 * The three other landing paths (importFromContent, rescanFromDisk,
 * updateFromURL) all pass `version = parsed.version`; this is the "one more
 * construction site" half of the four-layer field-sync family.
 *
 * [SkillRepository] needs an Android Context, so the value→row mapping is a
 * pure companion function ([SkillRepository.skillFromDiscoveredMd]) and the
 * expected version below is a hardcoded literal, never the production default
 * (a mutation must not be able to move the expectation).
 */
class SkillAutoDiscoverFieldsTest {

    private val raw = """
        ---
        name: Custom Tool
        description: does things
        version: 2.3.0
        ---
        body line one
        body line two
    """.trimIndent()

    private val parsed = SkillRepository.ParsedSkill(
        name = "Custom Tool",
        description = "does things",
        version = "2.3.0",
        body = "body line one\nbody line two",
        frontmatter = "---\nname: Custom Tool\ndescription: does things\nversion: 2.3.0\n---\n",
    )

    @Test
    fun `the parsed version reaches the row`() {
        val skill = SkillRepository.skillFromDiscoveredMd("custom-tool", parsed, raw)
        assertEquals("2.3.0", skill.version)
    }

    @Test
    fun `every other parsed field reaches the row too`() {
        val skill = SkillRepository.skillFromDiscoveredMd("custom-tool", parsed, raw)
        assertEquals("custom-tool", skill.id)
        assertEquals("Custom Tool", skill.name)
        assertEquals("does things", skill.description)
        assertEquals("body line one\nbody line two", skill.body)
        // A mid-session discovery must not lose frontmatter-only keys
        // (`subagent`, `max_turns`, …): the block is extracted from the SAME
        // raw text the parse consumed.
        assertTrue(skill.frontmatter.contains("version: 2.3.0"))
        assertTrue(skill.frontmatter.trimStart().startsWith("---"))
    }

    @Test
    fun `a skill first discovered on disk is not marked as a file import`() {
        val skill = SkillRepository.skillFromDiscoveredMd("custom-tool", parsed, raw)
        // Pre-existing behaviour, pinned so a refactor of the construction site
        // does not silently change which UI affordances the row gets.
        assertEquals(SkillRepository.ImportSource.SESSION, skill.importSource)
    }
}
