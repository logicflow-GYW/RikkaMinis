package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.BudgetSnapshot as HarnessBudgetSnapshot
import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.RemainingBudget

/**
 * [T4-B] 预算桥 —— 生产预算（T2 `AgentExecutionBudget`）↔ Harness 契约预算快照。
 *
 * 纯函数（输入已冻结的 budget 实例），无 Android 依赖，可独立单测。
 *
 * ## 为什么需要桥
 *
 * T4-A 的 `HarnessBudget` 是 test-only 的简化计数模型；T2 的
 * `AgentExecutionBudget` 是生产类型（带锁、deadline、token 预算、child 预留）。
 * T4-B adapter 必须用**生产预算**驱动真实主链，同时产出 T4-A 契约要求的
 * [HarnessBudgetSnapshot] 供 `ScenarioVerifier` 断言。
 *
 * ## 映射说明
 *
 * | Harness 字段 | 生产来源 | 语义 |
 * |---|---|---|
 * | turnsUsed | `snapshot().turnsUsed` | 直接映射 |
 * | maxTurns | 构造参数 | 直接映射 |
 * | providerAttemptsUsed | `snapshot().providerAttemptsUsed` | 直接映射 |
 * | toolCallsUsed | `snapshot().toolCallsUsed` | 直接映射 |
 * | shellCommandsUsed | `snapshot().shellCommandsUsed` | 直接映射 |
 * | compactionCallsUsed | `snapshot().compactionCallsUsed` | 直接映射 |
 * | expired | `snapshot().isExpired` | 语义一致 |
 *
 * 生产预算多出的维度（concurrentToolsActive / estimatedTokensUsed /
 * reservedChildTokens）在 Harness 契约中不存在 —— 不做伪造，仅用于
 * adapter 内部决策，不进入断言快照。
 */
object BudgetBridge {

    /**
     * 从生产预算产出 Harness 契约快照。
     * @param budget 生产预算实例（T7 主链持有，adapter 只读）。
     * @param maxTurnsOverride 可选：显式覆盖 maxTurns（当 Harness 场景希望用
     *   契约默认 100 而生产预算 max 不同时）。null = 用生产值。
     */
    fun toHarnessSnapshot(
        budget: AgentExecutionBudget,
        maxTurnsOverride: Int? = null,
    ): HarnessBudgetSnapshot {
        val snap = budget.snapshot()
        return HarnessBudgetSnapshot(
            turnsUsed = snap.turnsUsed,
            maxTurns = maxTurnsOverride ?: budget.maxTurns,
            providerAttemptsUsed = snap.providerAttemptsUsed,
            maxProviderAttempts = budget.maxProviderAttempts,
            toolCallsUsed = snap.toolCallsUsed,
            maxToolCalls = budget.maxToolCalls,
            shellCommandsUsed = snap.shellCommandsUsed,
            maxShellCommands = budget.maxShellCommands,
            compactionCallsUsed = snap.compactionCallsUsed,
            maxCompactionCalls = budget.maxCompactionCalls,
            expired = snap.isExpired,
        )
    }

    /**
     * 生产剩余预算 → 简单视图（供 adapter 决策：是否还能发新 attempt/tool）。
     * Assumed(await T7)：T7 若在 adapter 外做预算消耗，此视图仅作只读参考。
     */
    fun remainingView(budget: AgentExecutionBudget): RemainingBudget = budget.remaining()
}
