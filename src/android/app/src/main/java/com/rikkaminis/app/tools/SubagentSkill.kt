package com.rikkaminis.app.tools

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * Minimal skill info required by sub-agent config parsing and prompt building.
 * Implemented by `SkillRepository.Skill` in production; stubbed in tests to
 * avoid pulling the Android-dependent SkillRepository into JVM unit tests.
 */
interface SkillInfo {
    val name: String
    val description: String
    val body: String
}

/**
 * Sub-agent skill system — skill = independent agent instance.
 *
 * A skill marked `subagent: true` in its SKILL.md frontmatter gets its own
 * system prompt, filtered tool set, independent loop, and budget. The
 * [spawn_agent] tool dispatches to it; the sub-agent's context is fully
 * isolated from the main agent's history.
 *
 * FORBIDDEN tools (never passed to a sub-agent):
 *   - spawn_agent    (anti-recursion)
 *   - shell_execute  (terminal/android privilege escalation)
 *   - browser_use    (browser is a shared resource; a sub-agent shouldn't
 *                     race the main agent's browser tabs)
 */
object SubagentSkill {

    const val NAME = "spawn_agent"

    /** Tools that sub-agents are NEVER allowed to use. */
    val FORBIDDEN_TOOLS: Set<String> = setOf(
        NAME,              // anti-recursion
        "shell_execute",   // privilege escalation (terminal, android-* CLIs)
        "browser_use",     // shared browser resource — no racing
    )

    /** Default budget for sub-agents when the skill doesn't specify. */
    private const val DEFAULT_MAX_TURNS = 12
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

    /**
     * Parsed from a skill's SKILL.md frontmatter. Returned by
     * [parseSubagentConfig] for every skill; [isSubagent] is false
     * for regular skills.
     */
    data class SubagentConfig(
        val isSubagent: Boolean = false,
        val maxTurns: Int = DEFAULT_MAX_TURNS,
        val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        /** Null = allow all non-FORBIDDEN tools. Non-null = explicit allowlist. */
        val allowedTools: Set<String>? = null,
    )

    // ── Tool definition ──────────────────────────────────────────────────

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Spawn a sub-agent with its own system prompt, tool set, " +
            "and budget. The sub-agent runs independently and returns its final " +
            "result. Use this to delegate complex sub-tasks to a focused agent. " +
            "The skill must be defined with `subagent: true` in its SKILL.md " +
            "frontmatter and must already be installed and enabled. " +
            "Recursive spawn_agent is forbidden.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this sub-agent should do",
            ),
            "skill_name" to AgentToolParam(
                "string",
                "The name (or id) of the sub-agent skill to invoke",
            ),
            "query" to AgentToolParam(
                "string",
                "The task, question, or instruction to give to the sub-agent",
            ),
        ),
        required = listOf("tool_title", "skill_name", "query"),
        propertyOrdering = listOf("tool_title", "skill_name", "query"),
    )

    // ── Config parsing ───────────────────────────────────────────────────

    /**
     * Parse sub-agent configuration from the skill's body (SKILL.md content).
     * Recognises YAML frontmatter fields:
     *   subagent: true
     *   max_turns: 12
     *   max_output_tokens: 4096
     *   allowed_tools: [file_read, file_write, memory_get]
     *
     * Returns [SubagentConfig] with isSubagent=false for skills without
     * `subagent: true` in the frontmatter — existing skills are unaffected.
     */
    fun parseSubagentConfig(skill: SkillInfo): SubagentConfig {
        val body = skill.body
        if (body.isBlank()) return SubagentConfig()

        // Extract frontmatter between --- markers
        val lines = body.lines()
        if (lines.size < 2 || !lines[0].trim().startsWith("---")) return SubagentConfig()

        val endIdx = lines.subList(1, lines.size)
            .indexOfFirst { it.trim().startsWith("---") }
            .takeIf { it >= 0 }
            ?.plus(1)
        if (endIdx == null) return SubagentConfig()

        val frontmatter = lines.subList(1, endIdx)
        var isSubagent = false
        var maxTurns = DEFAULT_MAX_TURNS
        var maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS
        var allowedTools: Set<String>? = null

        var i = 0
        while (i < frontmatter.size) {
            val trimmed = frontmatter[i].trim()
            when {
                trimmed.startsWith("subagent:") -> {
                    val value = trimmed.substringAfter(":").trim()
                    isSubagent = value == "true" || value == "yes"
                }
                trimmed.startsWith("max_turns:") -> {
                    maxTurns = trimmed.substringAfter(":").trim().toIntOrNull() ?: DEFAULT_MAX_TURNS
                }
                trimmed.startsWith("max_output_tokens:") -> {
                    maxOutputTokens = trimmed.substringAfter(":").trim().toIntOrNull() ?: DEFAULT_MAX_OUTPUT_TOKENS
                }
                trimmed.startsWith("allowed_tools:") -> {
                    val listStr = trimmed.substringAfter(":").trim()
                    val tools = mutableSetOf<String>()
                    if (listStr.isNotEmpty()) {
                        parseListValue(listStr)?.let { tools.addAll(it) }
                    }
                    // Multi-line form: subsequent lines starting with "- "
                    var j = i + 1
                    while (j < frontmatter.size) {
                        val item = frontmatter[j].trim()
                        if (item.startsWith("- ")) {
                            tools.add(item.removePrefix("- ").trim().trim('"', '\''))
                            j++
                        } else {
                            break
                        }
                    }
                    allowedTools = tools.ifEmpty { emptySet() }
                }
            }
            i++
        }

        return SubagentConfig(
            isSubagent = isSubagent,
            maxTurns = maxTurns.coerceIn(1, 100),
            maxOutputTokens = maxOutputTokens.coerceIn(256, 128_000),
            allowedTools = allowedTools,
        )
    }

    /**
     * Build the filtered tool list for a sub-agent.
     * Excludes [FORBIDDEN_TOOLS] and, when [allowedTools] is non-null,
     * only includes tools whose name is in that set.
     */
    fun buildFilteredTools(
        allTools: List<AgentToolDefinition>,
        allowedTools: Set<String>?,
    ): List<AgentToolDefinition> {
        return allTools.filter { tool ->
            tool.name !in FORBIDDEN_TOOLS &&
                (allowedTools == null || tool.name in allowedTools)
        }
    }

    /**
     * Build the system prompt for a sub-agent from the skill body.
     * Strips frontmatter, returns the raw body text.
     * Falls back to the skill description when the body is only frontmatter.
     */
    fun buildSystemPrompt(skill: SkillInfo): String {
        val body = skill.body
        if (body.isBlank()) return skill.description

        val lines = body.lines()
        if (lines.size >= 2 && lines[0].trim().startsWith("---")) {
            val endIdx = lines.subList(1, lines.size)
                .indexOfFirst { it.trim().startsWith("---") }
                .takeIf { it >= 0 }
                ?.plus(1)
            if (endIdx != null) {
                val contentLines = if (endIdx + 1 < lines.size) {
                    lines.subList(endIdx + 1, lines.size)
                } else {
                    emptyList()
                }
                val content = contentLines.joinToString("\n").trim()
                if (content.isNotBlank()) return content
                // Frontmatter-only skill (no body content) → fall back to description.
                return skill.description
            }
        }
        return body
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parse a YAML list value like `[file_read, file_write]` or
     * `- file_read\n- file_write`.
     */
    private fun parseListValue(value: String): Set<String>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return emptySet()

        // Inline list: [item1, item2, ...]
        if (trimmed.startsWith("[")) {
            return trimmed
                .removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().trim('"', '\'') }
                .filter { it.isNotBlank() }
                .toSet()
                .ifEmpty { null }
        }

        // Multi-line list: each line starts with "- "
        val items = trimmed.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().trim('"', '\'') }
            .filter { it.isNotBlank() }
        return items.toSet().ifEmpty { null }
    }
}

/** A tool call emitted by a sub-agent during its loop. */
data class SubagentToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
)