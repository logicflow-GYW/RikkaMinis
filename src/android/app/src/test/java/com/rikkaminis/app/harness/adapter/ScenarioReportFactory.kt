package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.CooldownRecord
import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.ScenarioReport
import com.rikkaminis.app.harness.contract.TraceEventType
import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.AgentRunState

/**
 * [T4-B] 报告工厂 —— 从运行证据组装 [ScenarioReport]。
 *
 * 纯函数（输入均为已冻结的值对象），无 Android 依赖，可独立单测。
 *
 * ## 组装来源
 *
 * | ScenarioReport 字段 | 来源 |
 * |---|---|
 * | terminal | 状态机终态（[StateBridge]） |
 * | providerAttempts | 状态机 `providerAttemptCount` |
 * | toolExecutions | 状态机 `toolStartedCount` |
 * | duplicateSideEffects | adapter 运行时计数（经 [RunEvidence] 传入） |
 * | budgetSnapshot | [BudgetBridge]（生产预算快照） |
 * | leaseCount | adapter 运行时槽位状态（0=已释放） |
 * | traceTerminalEvents | trace 中 RUN_FINALIZED 计数 |
 * | persistenceMark | adapter 运行时持久化结果 |
 * | recoverable | [StateBridge.isRecoverable] |
 * | traceEvents | adapter 收集的 trace 事件 |
 * | violations | 由 [ScenarioReport.copy] 使用方填充或空 |
 *
 * 工厂**不执行任何副作用**，只做聚合 —— 保证可测试性。
 */
object ScenarioReportFactory {

    /**
     * 一次真实运行的可观察证据（adapter 收集，工厂消费）。
     */
    data class RunEvidence(
        /** 生产状态机终态快照（必须已终态）。 */
        val finalState: AgentRunState,
        /** 重复副作用计数（adapter 跟踪：同一副作用操作被执行多次）。 */
        val duplicateSideEffects: Int = 0,
        /** 会话槽位是否已释放（true=0 租约）。 */
        val sessionSlotReleased: Boolean = true,
        /** 持久化标记（adapter 从真实持久化结果映射）。 */
        val persistenceMark: PersistenceMark = PersistenceMark.NONE,
        /** trace 事件序列（adapter 收集，含 RUN_FINALIZED）。 */
        val traceEvents: List<HarnessTraceEvent> = emptyList(),
        /** provider 取消次数。 */
        val providerCancellations: Int = 0,
        /** 工具取消次数。 */
        val toolCancellations: Int = 0,
        /** 递归 spawn 拒绝次数。 */
        val spawnRejected: Int = 0,
        /** compact 调用次数（独立于状态机 compactCount，adapter 实计）。 */
        val compactCalls: Int = 0,
        /** 历史是否完好（compact 超时等不损坏原历史）。 */
        val historyIntact: Boolean = true,
    )

    /**
     * 组装报告。
     * @throws IllegalStateException 若 [RunEvidence.finalState] 非终态（无法产出合法报告）。
     */
    fun build(
        evidence: RunEvidence,
        budget: AgentExecutionBudget,
        maxTurnsOverride: Int? = null,
        cooldownRecords: List<CooldownRecord> = emptyList(),
    ): ScenarioReport {
        val terminal = StateBridge.terminalOf(evidence.finalState)
            ?: throw IllegalStateException(
                "cannot build ScenarioReport from non-terminal state: ${evidence.finalState.phase}"
            )

        return ScenarioReport(
            terminal = terminal,
            providerAttempts = evidence.finalState.providerAttemptCount,
            toolExecutions = evidence.finalState.toolStartedCount,
            duplicateSideEffects = evidence.duplicateSideEffects,
            budgetSnapshot = BudgetBridge.toHarnessSnapshot(budget, maxTurnsOverride),
            leaseCount = if (evidence.sessionSlotReleased) 0 else 1,
            traceTerminalEvents = evidence.traceEvents.count { it.type == TraceEventType.RUN_FINALIZED },
            persistenceMark = evidence.persistenceMark,
            recoverable = StateBridge.isRecoverable(evidence.finalState.phase),
            cooldownRecords = cooldownRecords,
            providerCancellations = evidence.providerCancellations,
            toolCancellations = evidence.toolCancellations,
            spawnRejected = evidence.spawnRejected,
            compactCalls = evidence.compactCalls,
            historyIntact = evidence.historyIntact,
            traceEvents = evidence.traceEvents,
            violations = emptyList(),
        )
    }
}
