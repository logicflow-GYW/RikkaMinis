package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-223] Coverage for `SkillRepository.mergeFrontmatter`.
 *
 * Before the fix `writeSkillMd` regenerated a 3-key frontmatter block on every
 * write, so any frontmatter-only key (`subagent: true`, `max_turns`,
 * `allowed_tools`) was destroyed by an ordinary rename — and since
 * `SubagentSkill.parseSubagentConfig` reads those keys off disk, the skill then
 * disappeared from the `spawn_agent` list.
 *
 * `SkillRepository` itself needs Android's Context/SQLite, so the pure merge
 * helper lives in its companion object and is exercised directly here (the same
 * split `SubagentSkill.extractFrontmatterBlock` and
 * `MCPRepository.deriveFallbackName` already use).
 */
class SkillFrontmatterMergeTest {

    private fun merge(
        existing: String?,
        name: String = "my-skill",
        description: String = "does things",
        version: String = "1.0.0",
        body: String = "Body text.",
    ) = SkillRepository.mergeFrontmatter(existing, name, description, version, body)

    @Test
    fun `frontmatter-only keys survive a rewrite`() {
        val existing = """
            ---
            name: my-skill
            description: does things
            version: 1.0.0
            subagent: true
            max_turns: 7
            allowed_tools: [file_read, file_write]
            ---
            Body text.
        """.trimIndent()

        val out = merge(existing, name = "renamed-skill")

        assertTrue("subagent key must survive", out.contains("subagent: true"))
        assertTrue("max_turns must survive", out.contains("max_turns: 7"))
        assertTrue("allowed_tools must survive", out.contains("allowed_tools: [file_read, file_write]"))
        assertTrue("name must be updated", out.contains("name: renamed-skill"))
        assertFalse("old name must be gone", out.contains("name: my-skill"))
        assertTrue("body preserved", out.endsWith("Body text."))
    }

    @Test
    fun `managed keys are replaced in place preserving order`() {
        val existing = """
            ---
            name: old
            subagent: true
            description: old desc
            version: 9.9.9
            ---
            B
        """.trimIndent()

        val out = merge(existing, name = "new", description = "new desc", version = "1.0.0")
        val fm = out.lines().takeWhile { it.trim() != "---" || out.lines().indexOf(it) == 0 }

        // subagent stays between name and description: position is preserved.
        val idxName = out.indexOf("name: new")
        val idxSub = out.indexOf("subagent: true")
        val idxDesc = out.indexOf("description: new desc")
        assertTrue("name before subagent", idxName in 1 until idxSub)
        assertTrue("subagent before description", idxSub < idxDesc)
        assertTrue("version updated", out.contains("version: 1.0.0"))
        assertFalse("old version gone", out.contains("9.9.9"))
        assertTrue(fm.isNotEmpty())
    }

    @Test
    fun `missing managed keys are appended`() {
        val existing = """
            ---
            name: my-skill
            subagent: true
            ---
            B
        """.trimIndent()

        val out = merge(existing, description = "added", version = "2.0.0")
        assertTrue(out.contains("description: added"))
        assertTrue(out.contains("version: 2.0.0"))
        assertTrue("unknown key still there", out.contains("subagent: true"))
    }

    @Test
    fun `no frontmatter falls back to a fresh three key block`() {
        val out = merge(
            existing = "Just a body, no frontmatter.",
            name = "n",
            description = "d",
            version = "1.0.0",
            body = "Just a body, no frontmatter.",
        )
        val expected = buildString {
            appendLine("---")
            appendLine("name: n")
            appendLine("description: d")
            appendLine("version: 1.0.0")
            appendLine("---")
            append("Just a body, no frontmatter.")
        }
        assertEquals(expected, out)
    }

    @Test
    fun `null and blank existing behave like no frontmatter`() {
        val a = merge(existing = null, name = "n", description = "d", version = "1.0.0", body = "b")
        val b = merge(existing = "", name = "n", description = "d", version = "1.0.0", body = "b")
        assertEquals(a, b)
        assertTrue(a.startsWith("---\nname: n"))
    }

    @Test
    fun `unterminated frontmatter is treated as absent rather than corrupting`() {
        val existing = "---\nname: x\nsubagent: true\n(no closing marker)\nbody"
        val out = merge(existing, name = "n", description = "d", version = "1.0.0", body = "b")
        // Must produce a well-formed block, not a half-merged one.
        assertEquals(2, out.lines().count { it.trim() == "---" })
        assertTrue(out.contains("name: n"))
    }

    @Test
    fun `comment lines inside frontmatter are preserved`() {
        val existing = """
            ---
            # authored by the user
            name: my-skill
            subagent: true
            ---
            B
        """.trimIndent()

        val out = merge(existing, name = "renamed")
        assertTrue("comment preserved", out.contains("# authored by the user"))
        assertTrue(out.contains("name: renamed"))
        assertTrue(out.contains("subagent: true"))
    }
}
