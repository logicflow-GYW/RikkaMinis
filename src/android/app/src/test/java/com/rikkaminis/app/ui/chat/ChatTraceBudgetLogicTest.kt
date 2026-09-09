package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.BudgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure T7 trace-budget serializers extracted in the FE-4
 * pure sweep ([t7InitialBudgetJson] / [t7BudgetSnapshotJson]).
 */
class ChatTraceBudgetLogicTest {

    private fun budget(maxEstimatedTokens: Long? = null) = AgentExecutionBudget(
        startedAtMonotonicMs = 0L,
        deadlineMonotonicMs = 1000L,
        maxTurns = 200,
        maxProviderAttempts = 64,
        maxToolCalls = 128,
        maxShellCommands = 128,
        maxCompactionCalls = 8,
        maxConcurrentTools = 4,
        maxEstimatedTokens = maxEstimatedTokens,
    )

    private fun snapshot(estimatedTokensUsed: Long? = null) = BudgetSnapshot(
        turnsUsed = 10,
        providerAttemptsUsed = 2,
        toolCallsUsed = 5,
        shellCommandsUsed = 3,
        compactionCallsUsed = 1,
        concurrentToolsActive = 2,
        estimatedTokensUsed = estimatedTokensUsed,
        reservedChildTokens = 0L,
        isExpired = false,
    )

    @Test
    fun `t7InitialBudgetJson serializes all max fields`() {
        val json = t7InitialBudgetJson(budget(maxEstimatedTokens = 50000L))
        assertTrue(json.contains("\"max_turns\":200"))
        assertTrue(json.contains("\"max_provider_attempts\":64"))
        assertTrue(json.contains("\"max_tool_calls\":128"))
        assertTrue(json.contains("\"max_concurrent_tools\":4"))
        assertTrue(json.contains("\"max_estimated_tokens\":50000"))
    }

    @Test
    fun `t7InitialBudgetJson omits null max estimated tokens`() {
        val json = t7InitialBudgetJson(budget(maxEstimatedTokens = null))
        assertFalse(json.contains("max_estimated_tokens"))
    }

    @Test
    fun `t7BudgetSnapshotJson serializes consumed fields`() {
        val json = t7BudgetSnapshotJson(snapshot(estimatedTokensUsed = 1234L))
        assertTrue(json.contains("\"turns_consumed\":10"))
        assertTrue(json.contains("\"tool_calls_consumed\":5"))
        assertTrue(json.contains("\"is_expired\":false"))
        assertTrue(json.contains("\"estimated_tokens_consumed\":1234"))
    }

    @Test
    fun `t7BudgetSnapshotJson omits null estimated tokens`() {
        val json = t7BudgetSnapshotJson(snapshot(estimatedTokensUsed = null))
        assertFalse(json.contains("estimated_tokens_consumed"))
    }
}
