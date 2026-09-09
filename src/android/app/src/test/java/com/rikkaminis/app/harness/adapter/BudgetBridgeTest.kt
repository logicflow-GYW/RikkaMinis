package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import org.junit.Test

/**
 * [T4-B] BudgetBridge 纯逻辑测试：生产预算 → Harness 契约预算快照。
 *
 * 验证生产 `AgentExecutionBudget`（T2）与 Harness `BudgetSnapshot`（T4-A）
 * 的字段映射正确，且生产额外维度（token/child/并发）不伪造进契约快照。
 */
class BudgetBridgeTest {

    private fun budget(
        maxTurns: Int = 100,
        maxProviderAttempts: Int = 100,
        maxToolCalls: Int = 100,
        maxShellCommands: Int = 100,
        maxCompactionCalls: Int = 100,
    ): AgentExecutionBudget {
        val now = 1_000L
        return AgentExecutionBudget(
            startedAtMonotonicMs = now,
            deadlineMonotonicMs = now + 60_000,
            maxTurns = maxTurns,
            maxProviderAttempts = maxProviderAttempts,
            maxToolCalls = maxToolCalls,
            maxShellCommands = maxShellCommands,
            maxCompactionCalls = maxCompactionCalls,
            maxConcurrentTools = 4,
            maxEstimatedTokens = null,
            monotonicClock = { now },
        )
    }

    @Test
    fun `fresh budget maps to zero-used snapshot`() {
        val b = budget()
        val snap = BudgetBridge.toHarnessSnapshot(b)
        assertEq(snap.turnsUsed, 0)
        assertEq(snap.providerAttemptsUsed, 0)
        assertEq(snap.toolCallsUsed, 0)
        assertEq(snap.shellCommandsUsed, 0)
        assertEq(snap.compactionCallsUsed, 0)
        assertEq(snap.maxTurns, 100)
        assertEq(snap.expired, false)
    }

    @Test
    fun `consumed budget maps consumed counters`() {
        val b = budget(maxTurns = 5, maxProviderAttempts = 3, maxToolCalls = 7)
        b.consumeTurn()
        b.consumeTurn()
        b.consumeProviderAttempt()
        b.consumeToolCall()
        b.consumeToolCall()
        b.consumeToolCall()

        val snap = BudgetBridge.toHarnessSnapshot(b)
        assertEq(snap.turnsUsed, 2)
        assertEq(snap.maxTurns, 5)
        assertEq(snap.providerAttemptsUsed, 1)
        assertEq(snap.maxProviderAttempts, 3)
        assertEq(snap.toolCallsUsed, 3)
        assertEq(snap.maxToolCalls, 7)
    }

    @Test
    fun `maxTurnsOverride wins over production max`() {
        val b = budget(maxTurns = 5)
        val snap = BudgetBridge.toHarnessSnapshot(b, maxTurnsOverride = 100)
        assertEq(snap.maxTurns, 100)
    }

    @Test
    fun `deadline-expired budget marks expired`() {
        val now = 1_000L
        val b = AgentExecutionBudget(
            startedAtMonotonicMs = now,
            deadlineMonotonicMs = now + 1, // 立即过期
            maxTurns = 10,
            maxProviderAttempts = 10,
            maxToolCalls = 10,
            maxShellCommands = 10,
            maxCompactionCalls = 10,
            maxConcurrentTools = 4,
            maxEstimatedTokens = null,
            monotonicClock = { now + 5 },
        )
        val snap = BudgetBridge.toHarnessSnapshot(b)
        assertEq(snap.expired, true)
    }

    @Test
    fun `token budget null is not forged`() {
        val b = budget()
        // maxEstimatedTokens = null → snapshot 不涉及 token（Harness 契约无此维度）
        val snap = BudgetBridge.toHarnessSnapshot(b)
        // 契约快照不含 estimatedTokensUsed 字段 —— 编译期已保证不伪造。
        assertEq(snap.expired, false)
    }

    private fun assertEq(actual: Any?, expected: Any?, msg: String = "actual=$actual expected=$expected") {
        if (actual != expected) throw AssertionError(msg)
    }
}
