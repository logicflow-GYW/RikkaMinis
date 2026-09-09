package com.rikkaminis.app.tools

import com.rikkaminis.app.workspace.MemoryRollupRunner
import java.io.File

/**
 * Reads the largest completed daily log that has not already been distilled,
 * classifies entries into stable rules (conventions / decisions / lessons),
 * and appends them to MEMORY-ROLLUP.md. The source log is never modified.
 *
 * Idempotent: a date already rolled up is skipped. An optional date can be
 * passed to target one specific daily log; the default chooses the largest
 * eligible old log so missed dates remain reachable.
 */
object MemoryRollupTool {

    private const val TOOL_NAME = "memory_rollup"

    fun agentToolDefinition(): com.rikkaminis.app.data.model.AgentToolDefinition {
        return com.rikkaminis.app.data.model.AgentToolDefinition(
            name = TOOL_NAME,
            description = "Distill stable rules from the largest eligible old daily log into MEMORY-ROLLUP.md. " +
                "It selects the largest non-empty YYYY-MM-DD log that has not already been rolled up " +
                "and contains stable entries; an older missed log remains reachable. " +
                "A date already rolled up is skipped and source logs are never modified. " +
                "Call this when daily logs are getting large to surface reusable knowledge.",
            parameters = emptyMap(),
            required = emptyList(),
            propertyOrdering = emptyList(),
        )
    }

    fun openAIDefinition(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", TOOL_NAME)
                put("description", agentToolDefinition().description)
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject())
                    put("required", org.json.JSONArray())
                })
            })
        }
    }

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "memory_rollup",
    )

    fun execute(memoryDir: File): ToolResult {
        return try {
            val runner = MemoryRollupRunner(memoryDir)
            val outcome = runner.runOnce()
            val (message, success) = when (outcome) {
                MemoryRollupRunner.Outcome.ROLLED_UP ->
                    "Memory rollup completed: the largest eligible daily log was distilled into MEMORY-ROLLUP.md" to true
                MemoryRollupRunner.Outcome.SKIPPED_ALREADY ->
                    "Memory rollup skipped: the selected log was already distilled (idempotent)" to true
                MemoryRollupRunner.Outcome.NO_LOG_YESTERDAY ->
                    "No eligible daily log found — nothing to roll up" to true
                MemoryRollupRunner.Outcome.NOTHING_TO_DISTILL ->
                    "The selected log had no distillable stable rules (all entries transient)" to true
                MemoryRollupRunner.Outcome.ERROR ->
                    "Memory rollup failed due to an I/O error" to false
            }
            ToolResult(message, success)
        } catch (t: Throwable) {
            ToolResult("Memory rollup error: ${t.message}", false)
        }
    }
}