package com.rikkaminis.app.ui.chat

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R4-Q F-14] Wiring assertions for the sub-agent dispatch path.
 *
 * The main loop gates every provider request on the run's observation budget
 * (`AgentLoopEngine`: deadline check + `t7ConsumeAndTrace(PROVIDER_ATTEMPTS)`),
 * and records skill usage whenever a SKILL.md is read. The sub-agent path did
 * neither: a runaway sub-agent burned provider attempts the budget never
 * counted, and skills dispatched through `spawn_agent` never showed up in the
 * "最近使用" ordering.
 *
 * These are source-shape assertions on purpose: the behaviour lives inside a
 * Compose-free but Android-coupled tool dispatcher, and what can silently rot
 * is the WIRING (a gate that exists but sits after the request, a `continue`
 * that skips it, a second dispatch arm that forgets the counter).
 */
class SubagentBudgetGateTest {

    private val promptAndTools by lazy { readRepoFile(PROMPT_AND_TOOLS) }

    @Test
    fun subagentRequestIsGatedByDeadlineAndAttemptBudgetBeforeTheGateway() {
        val spawn = functionRegion(promptAndTools, "internal suspend fun ChatViewModel.executeSpawnAgentTool(")
        val gatewayAt = spawn.indexOf("ProviderExecutionGateway.stream(")
        assertTrue("gateway call not found in the spawn dispatch", gatewayAt > 0)

        val deadlineAt = spawn.indexOf("activeRunBudget?.isExpired() == true")
        assertTrue(
            "the sub-agent must refuse a request past the run deadline, before the gateway call",
            deadlineAt in 0 until gatewayAt,
        )
        val consumeAt = spawn.indexOf("DIMENSION_PROVIDER_ATTEMPTS")
        assertTrue(
            "the sub-agent must consume a PROVIDER_ATTEMPTS unit before the gateway call",
            consumeAt in 0 until gatewayAt,
        )
        assertTrue(
            "a refused request must be distinguishable from a provider failure",
            spawn.contains("SubagentBudgetExhaustedException"),
        )
    }

    @Test
    fun subagentSkillUsageIsRecorded() {
        val spawn = functionRegion(promptAndTools, "internal suspend fun ChatViewModel.executeSpawnAgentTool(")
        assertTrue(
            "dispatching a sub-agent is a skill use — record it once the skill resolved",
            spawn.contains("recordSkillUse(skill.id)"),
        )
        val subagentTool = functionRegion(promptAndTools, "internal suspend fun ChatViewModel.executeSubagentTool(")
        assertTrue(
            "the sub-agent's own FileReadTool arm must record SKILL.md reads like the main arm",
            subagentTool.contains("recordSkillUse(sid)"),
        )
        assertTrue(
            "the sub-agent file-read arm must still be the plain tool call",
            subagentTool.contains("FileReadTool.NAME -> FileReadTool.execute("),
        )
        assertEquals(
            "exactly one arm records sub-agent skill reads (a second copy would double count)",
            1,
            Regex("skillIdFromPath\\(readPath\\)").findAll(subagentTool).count(),
        )
    }

    /** Source of the function starting at [signature], cut at the next top-level
     *  declaration (a line starting at column 0 with `internal`/`private`/`fun`/
     *  `@`), so ordering assertions cannot see the neighbouring function. */
    private fun functionRegion(src: String, signature: String): String {
        val at = src.indexOf(signature)
        assertTrue("signature not found: $signature", at >= 0)
        val rest = src.substring(at + signature.length)
        val end = Regex("\n(?:@|internal |private |public |fun )").find(rest)
        return if (end == null) rest else rest.substring(0, end.range.first)
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PROMPT_AND_TOOLS =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
    }
}
