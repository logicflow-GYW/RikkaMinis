package com.rikkaminis.app.tools

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentSkillTest {

    /**
     * Minimal skill representation for test purposes.
     * Implements [SkillInfo] so it can be passed to SubagentSkill methods.
     */
    private data class TestSkill(
        override val name: String = "test-skill",
        override val description: String = "A test skill",
        override val body: String = "",
    ) : SkillInfo

    private fun makeSkill(body: String) = TestSkill(body = body)

    private fun makeTool(name: String) = AgentToolDefinition(
        name = name,
        description = "tool $name",
        parameters = emptyMap(),
    )

    // ── parseSubagentConfig ──────────────────────────────────────────────

    @Test
    fun `regular skill without frontmatter is not a subagent`() {
        val skill = makeSkill("Just some instructions.\n\nDo things.")
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertFalse(config.isSubagent)
        assertEquals(12, config.maxTurns)
        assertEquals(4096, config.maxOutputTokens)
        assertNull(config.allowedTools)
    }

    @Test
    fun `skill with subagent true is a subagent`() {
        val skill = makeSkill(
            """
            ---
            name: Researcher
            description: Deep research
            subagent: true
            ---
            You are a researcher. Do research.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(12, config.maxTurns)
        assertNull(config.allowedTools)
    }

    @Test
    fun `subagent with custom budget overrides defaults`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            max_turns: 6
            max_output_tokens: 2048
            ---
            You are a focused agent.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(6, config.maxTurns)
        assertEquals(2048, config.maxOutputTokens)
    }

    @Test
    fun `subagent with inline allowlist parses tool names`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools: [file_read, file_write, memory_get]
            ---
            You are a researcher.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        assertEquals(setOf("file_read", "file_write", "memory_get"), config.allowedTools)
    }

    @Test
    fun `subagent with dash allowlist parses tool names`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools:
              - file_read
              - memory_get
            ---
            You are a researcher.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertEquals(setOf("file_read", "memory_get"), config.allowedTools)
    }

    @Test
    fun `subagent with empty allowlist means no tools allowed`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            allowed_tools: []
            ---
            You are a purist.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertEquals(emptySet<Any>(), config.allowedTools)
    }

    @Test
    fun `subagent with unknown fields ignores them gracefully`() {
        val skill = makeSkill(
            """
            ---
            subagent: true
            max_turns: not-a-number
            max_output_tokens: 999999
            color: blue
            ---
            You are a tolerant agent.
            """.trimIndent(),
        )
        val config = SubagentSkill.parseSubagentConfig(skill)
        assertTrue(config.isSubagent)
        // Invalid number falls back to default; out-of-range clamps
        assertEquals(12, config.maxTurns)
        assertEquals(128_000, config.maxOutputTokens)
    }

    // ── buildFilteredTools ───────────────────────────────────────────────

    @Test
    fun `forbidden tools are always excluded`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("shell_execute"),
            makeTool("spawn_agent"),
            makeTool("browser_use"),
            makeTool("memory_get"),
        )
        val filtered = SubagentSkill.buildFilteredTools(all, null)
        val names = filtered.map { it.name }.toSet()
        assertEquals(setOf("file_read", "memory_get"), names)
    }

    @Test
    fun `allowlist restricts to listed tools`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("file_write"),
            makeTool("file_edit"),
            makeTool("memory_get"),
            makeTool("memory_write"),
        )
        val filtered = SubagentSkill.buildFilteredTools(all, setOf("file_read", "memory_get"))
        val names = filtered.map { it.name }.toSet()
        assertEquals(setOf("file_read", "memory_get"), names)
    }

    @Test
    fun `allowlist cannot re-enable forbidden tools`() {
        val all = listOf(
            makeTool("file_read"),
            makeTool("shell_execute"),
            makeTool("spawn_agent"),
        )
        // Even if the skill author lists them, FORBIDDEN wins.
        val filtered = SubagentSkill.buildFilteredTools(
            all, setOf("file_read", "shell_execute", "spawn_agent"),
        )
        assertEquals(listOf("file_read"), filtered.map { it.name })
    }

    @Test
    fun `empty allowlist yields no tools`() {
        val all = listOf(makeTool("file_read"), makeTool("memory_get"))
        val filtered = SubagentSkill.buildFilteredTools(all, emptySet())
        assertTrue(filtered.isEmpty())
    }

    // ── buildSystemPrompt ────────────────────────────────────────────────

    @Test
    fun `system prompt strips frontmatter`() {
        val skill = makeSkill(
            """
            ---
            name: Researcher
            description: Deep research
            subagent: true
            ---
            You are a researcher. Always cite sources.
            """.trimIndent(),
        )
        val prompt = SubagentSkill.buildSystemPrompt(skill)
        assertTrue(prompt.contains("You are a researcher"))
        assertFalse(prompt.contains("subagent:"))
        assertFalse(prompt.contains("---"))
    }

    @Test
    fun `system prompt falls back to description for frontmatter-only skill`() {
        val skill = TestSkill(
            name = "Empty",
            description = "Fallback description",
            body = """
                ---
                name: Empty
                description: Fallback description
                subagent: true
                ---
                """.trimIndent(),
        )
        assertEquals("Fallback description", SubagentSkill.buildSystemPrompt(skill))
    }

    @Test
    fun `system prompt returns raw body when no frontmatter`() {
        val skill = makeSkill("Plain instructions without frontmatter.")
        assertEquals("Plain instructions without frontmatter.", SubagentSkill.buildSystemPrompt(skill))
    }
}