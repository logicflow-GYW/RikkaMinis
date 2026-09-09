package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.ScenarioReport
import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunTransition
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentTerminalReason
import com.rikkaminis.app.agent.runtime.BudgetDecision
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import com.rikkaminis.app.harness.contract.TraceEventType
import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.CooldownRecord
import com.rikkaminis.app.harness.adapter.ScenarioRuntimePort
import kotlinx.coroutines.delay

/**
 * [T4-B] 生产 adapter —— 场景驱动循环（与 T4-A `HarnessRunner` 同构）。
 *
 * ## 职责
 *
 * 消费 [FaultScenario]（故障脚本），经 [AgentRuntimePort] 驱动 provider / tool /
 * shell / persistence / session slot，产出与 T4-A `HarnessRunner` **同构**的
 * [ScenarioReport]（[ScenarioReportFactory] 保证结构一致），由 `ScenarioVerifier`
 * 断言 —— runner 被替换为生产 adapter，场景与断言引擎原样复用。
 *
 * ## 状态机契约（T5 `AgentRunReducer`）
 *
 * 驱动循环只发事件（[AgentRunEvent]），不直接改状态；终态唯一、非法转换拒绝。
 * 所有出口必须：先经终止事件（UserCancelled / DeadlineReached / ProcessInterrupted /
 * PersistenceFailed / WorkCompleted / ProviderAttemptFinished(FATAL)）进入
 * FINALIZING，再 `RunFinalized` 落终态，并记一条 RUN_FINALIZED trace
 * （[ScenarioReportFactory] 用 trace 计数断言 `traceTerminalEvents == 1`）。
 *
 * 与 T4-A 参考实现对齐的语义：
 * - 用户取消 → CANCELLED（终态契约 4.1）；
 * - 进程死亡 / 断流 / deadline / outcome-unknown 工具 → INTERRUPTED（可恢复）；
 * - fallback 链耗尽 / turn 预算耗尽 → FAILED；
 * - finalAnswer 且持久化成功 → SUCCEEDED；持久化失败 → FAILED；
 * - 首次 outcome-unknown 副作用不计 duplicate（只有重跑才计）。
 *
 * @param runtime 运行时端口（T4-B 阶段 = [ScenarioRuntimePort]，T7 后 = 真实实现）
 * @param budgetFactory 按场景创建生产预算（注入以支持测试确定性）
 * @param reduce 状态机 reduce 函数（注入以便测试替换为记录版本）
 */
class RealAgentAdapterSkeleton(
    private val runtime: AgentRuntimePort,
    private val budgetFactory: (FaultScenario) -> AgentExecutionBudget = { defaultBudgetFor(it) },
    private val reduce: (AgentRunState, AgentRunEvent) -> AgentRunTransition = AgentRunReducer::reduce,
) : RealAgentAdapter {

    /** 驱动循环状态（骨架内部可变聚合，供 evidence 收集）。 */
    private class DriveState {
        var duplicateSideEffects = 0
        var providerCancellations = 0
        var toolCancellations = 0
        var spawnRejected = 0
        var compactCalls = 0
        var historyIntact = true
        var persistenceMark = PersistenceMark.NONE
        var sessionSlotReleased = true
        /** 已产生副作用的工具操作（按 run 内唯一 id），用于 duplicate 判定。 */
        val performedToolOps = mutableSetOf<String>()
        /** 是否存在 outcome-unknown 的工具结果（run 结束未收敛时不得伪装成功）。 */
        var hasOutcomeUnknownTool = false
        val traceEvents = mutableListOf<HarnessTraceEvent>()
        val cooldowns = mutableListOf<CooldownRecord>()
    }

    override suspend fun executeScenario(scenario: FaultScenario): ScenarioReport {
        val budget = budgetFactory(scenario)
        val drive = DriveState()
        val runId = "run-${scenario.id}-${budget.startedAtMonotonicMs}"

        // 1. 槽位获取（T1/T7 语义：acquire 成功才运行）
        val acquired = runtime.acquireSlot(runId)
        if (!acquired) {
            // 排队/拒绝：直接 FAILED（不伪装成功，不占用资源）
            drive.sessionSlotReleased = true
            drive.persistenceMark = PersistenceMark.NONE
            emit(runtime, drive, TraceEventType.RUN_START, detail = "slot denied: $runId")
            emit(runtime, drive, TraceEventType.RUN_FINALIZED, detail = "terminal=FAILED slot-denied")
            val state = AgentRunState(
                phase = AgentRunPhase.FAILED,
                runId = runId,
                terminalReason = AgentTerminalReason.EXECUTION_FAILED,
            )
            return ScenarioReportFactory.build(
                evidence = evidenceOf(drive, state),
                budget = budget,
            )
        }

        // 2. 状态机启动（T5 契约：IDLE → RunStarted → PREPARING）
        var state = AgentRunState.initial()
        state = apply(reduce, state, AgentRunEvent.RunStarted(runId))
        emit(runtime, drive, TraceEventType.RUN_START, detail = runId)

        // 3. 主循环
        try {
            // T4-B: 在 turn loop 前重置 ScenarioRuntimePort 的计时器，
            // 避免 setup 开销（实例化、预算创建、reducer 初始化等）影响
            // F14 processDeathAtMs（20ms）的精确检测。
            if (runtime is ScenarioRuntimePort) runtime.resetTimer()
            driveTurnLoop(scenario, budget, drive) { event ->
                state = apply(reduce, state, event)
            }
        } finally {
            // 4. 无论如何释放槽位（蓝图 §4.2 不变量 3/4）
            runtime.releaseSlot(runId)
            drive.sessionSlotReleased = true
        }

        // 5. 终态持久化标记派生：显式未设置时按终态语义补齐
        //     （F07/F08 CANCELLED、F11/F14 INTERRUPTED → PARTIAL；SUCCEEDED → COMPLETED；FAILED → NONE）
        if (drive.persistenceMark == PersistenceMark.NONE && state.isTerminal) {
            drive.persistenceMark = when (state.phase) {
                AgentRunPhase.SUCCEEDED -> PersistenceMark.COMPLETED
                AgentRunPhase.CANCELLED,
                AgentRunPhase.INTERRUPTED -> PersistenceMark.PARTIAL
                else -> PersistenceMark.NONE
            }
        }

        // 6. 组装报告（纯逻辑，可测）
        return ScenarioReportFactory.build(
            evidence = evidenceOf(drive, state),
            budget = budget,
            cooldownRecords = drive.cooldowns,
        )
    }

    /**
     * turn loop 实现 —— 通用 Agent Run 驱动循环。
     *
     * 按场景 [FaultScenario] 定义的 turn 序列驱动运行时端口：
     * 1. 每轮开始检查 deadline/userCancelled/processDead
     * 2. 消耗 turn 预算（T2）
     * 3. 尝试 fallback 链（预期间隔检测）
     * 4. 处理 provider 结果（成功/429/stream reset/drop/hard failure/length finish）
     * 5. 执行工具（tool calls）
     * 6. 检查工具结果副作用
     * 7. 终态判定
     *
     * 所有 reducer 事件通过 [emitEvent] 发出，供 T5 状态机旁路验证。
     */
    private suspend fun driveTurnLoop(
        scenario: FaultScenario,
        budget: AgentExecutionBudget,
        drive: DriveState,
        emitEvent: (AgentRunEvent) -> Unit,
    ) {
        // Trace emission helper: records into drive.traceEvents AND
        // passes through runtime.emitTrace so the ScenarioReport's
        // trace event counts are populated.
        fun emitTrace(type: TraceEventType, detail: String? = null) {
            val now = System.nanoTime() / 1_000_000L
            drive.traceEvents += HarnessTraceEvent(type, now, detail)
            TraceBridge.emit(runtime, type, now, detail)
        }

        /**
         * 统一收尾：先落终态（reducer 从 FINALIZING 接受），再记 RUN_FINALIZED trace。
         * 调用方必须先发过终止事件（UserCancelled / ProcessInterrupted / DeadlineReached /
         * WorkCompleted / ProviderAttemptFinished(FATAL) 等）使状态进入 FINALIZING。
         */
        fun finalizeRun(terminal: AgentTerminal, reason: AgentTerminalReason) {
            emitEvent(AgentRunEvent.RunFinalized(terminal, reason))
            emitTrace(TraceEventType.RUN_FINALIZED, "terminal=$terminal")
        }

        for ((turnIdx, turn) in scenario.turns.withIndex()) {
            // ── 前置检查 ─────────────────────────────────────────────
            if (budget.isExpired()) {
                emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                emitTrace(TraceEventType.DEADLINE_REACHED, "deadline at turn entry")
                finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED)
                return
            }
            if (runtime.isUserCancelled()) {
                emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                emitTrace(TraceEventType.USER_CANCELLED, "user cancelled at turn entry")
                finalizeRun(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED)
                return
            }
            if (runtime.isProcessDead()) {
                emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "process death at turn entry")
                finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                return
            }

            // ── turn 预算 ────────────────────────────────────────────
            if (budget.consumeTurn() is BudgetDecision.Denied) {
                emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(turn_limit)"))
                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "turn limit")
                finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                return
            }

            // ── compact 阶段 ──────────────────────────────────────────
            if (turn.compactDelayMs > 0) {
                emitEvent(AgentRunEvent.CompactionStarted("pre_turn_$turnIdx"))
                drive.compactCalls++
                val timeoutMs = scenario.compactTimeoutMs
                if (timeoutMs > 0) {
                    // 有超时上限：等待到超时点，超时即中断（F09 语义：历史不损坏）
                    delay(turn.compactDelayMs.coerceAtMost(timeoutMs))
                    if (turn.compactDelayMs > timeoutMs) {
                        drive.historyIntact = true
                        drive.persistenceMark = PersistenceMark.PARTIAL
                        emitTrace(TraceEventType.COMPACT_FINISHED, "compact timeout")
                        emitEvent(AgentRunEvent.ProcessInterrupted("compact_timeout"))
                        finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                        return
                    }
                } else {
                    delay(turn.compactDelayMs)
                }
                emitTrace(TraceEventType.COMPACT_FINISHED, "compact success")
                emitEvent(AgentRunEvent.CompactionFinished())
            }

            // ── provider fallback 链 ─────────────────────────────────
            for ((attemptIdx, attempt) in turn.attempts.withIndex()) {
                if (budget.isExpired()) {
                    emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                    emitTrace(TraceEventType.DEADLINE_REACHED, "deadline before attempt")
                    finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED)
                    return
                }
                if (runtime.isUserCancelled()) {
                    emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                    emitTrace(TraceEventType.USER_CANCELLED, "user cancelled before attempt")
                    drive.providerCancellations++
                    finalizeRun(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED)
                    return
                }
                if (runtime.isProcessDead()) {
                    emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                    emitTrace(TraceEventType.PROCESS_INTERRUPTED, "process death before attempt")
                    finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                    return
                }

                // 消耗 provider attempt 预算
                if (budget.consumeProviderAttempt() is BudgetDecision.Denied) {
                    emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(provider_attempts)"))
                    emitTrace(TraceEventType.PROCESS_INTERRUPTED, "provider attempt budget")
                    finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                    return
                }

                emitEvent(AgentRunEvent.ProviderAttemptStarted)

                // 模拟冷却/前置等待（F07/F11：等待期间必须轮询 deadline/cancel/death，
                // 对齐参考 HarnessRunner 10ms 粒度的时钟推进；打断 = providerCancellations++）
                if (attempt.delayMs > 0) {
                    var remaining = attempt.delayMs
                    while (remaining > 0) {
                        val step = minOf(10L, remaining)
                        delay(step)
                        remaining -= step
                        if (budget.isExpired()) {
                            emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
                            emitTrace(TraceEventType.DEADLINE_REACHED, "deadline during attempt delay")
                            drive.providerCancellations++
                            finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED)
                            return
                        }
                        if (runtime.isUserCancelled()) {
                            emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                            emitTrace(TraceEventType.USER_CANCELLED, "user cancelled during attempt delay")
                            drive.providerCancellations++
                            finalizeRun(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED)
                            return
                        }
                        if (runtime.isProcessDead()) {
                            emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "process death during attempt delay")
                            drive.providerCancellations++
                            finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                            return
                        }
                    }
                }

                val providerResult = runtime.callProvider(attemptIdx)

                when (providerResult) {
                    is ProviderCallResult.Success -> {
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.SUCCESS
                        ))
                        // 执行工具
                        for (toolName in providerResult.toolCalls) {
                            if (budget.consumeToolCall() is BudgetDecision.Denied) {
                                emitEvent(AgentRunEvent.ProcessInterrupted("budget_exhausted(tool_calls)"))
                                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "tool call budget")
                                finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                                return
                            }
                            emitEvent(AgentRunEvent.ToolStarted(toolName))
                            val toolResult = runtime.executeTool(toolName)
                            emitEvent(AgentRunEvent.ToolFinished(toolName, toolResult.resultKnown))
                            // duplicate 判定：仅同操作重跑才计（首次 outcome-unknown 不算重复）
                            if (toolResult.sideEffectPerformed) {
                                val opId = "turn.$turnIdx.tool.$toolName"
                                if (opId in drive.performedToolOps) drive.duplicateSideEffects++
                                drive.performedToolOps.add(opId)
                            }
                            if (!toolResult.resultKnown) drive.hasOutcomeUnknownTool = true
                            if (toolResult.spawnRejected) drive.spawnRejected++

                            // 用户取消的确定性来源：工具结果 cancelled 且进程未死 ⇒ 用户取消
                            if (toolResult.cancelled && !runtime.isProcessDead()) {
                                drive.toolCancellations++
                                emitEvent(AgentRunEvent.UserCancelled("tool_cancelled"))
                                emitTrace(TraceEventType.USER_CANCELLED, "tool cancelled")
                                finalizeRun(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED)
                                return
                            }
                            // 进程死亡（F14：工具执行期间死亡）→ INTERRUPTED，不是 FAILED
                            if (runtime.isProcessDead()) {
                                emitEvent(AgentRunEvent.ProcessInterrupted("process_death"))
                                emitTrace(TraceEventType.PROCESS_INTERRUPTED, "process death during tool")
                                finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                                return
                            }
                            // 兜底：工具返回后用户取消（F08 语义）
                            if (runtime.isUserCancelled()) {
                                drive.toolCancellations++
                                emitEvent(AgentRunEvent.UserCancelled("user_cancelled"))
                                emitTrace(TraceEventType.USER_CANCELLED, "user cancelled after tool")
                                finalizeRun(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED)
                                return
                            }
                        }
                        // finalAnswer → 本轮终结（不再走更多 turn）
                        if (providerResult.finalAnswer) {
                            emitEvent(AgentRunEvent.WorkCompleted)
                            // 终态持久化（F12：失败则终态 FAILED，不伪装成功）
                            val persistOk = runtime.persist(PersistenceMark.COMPLETED)
                            if (persistOk) {
                                drive.persistenceMark = PersistenceMark.COMPLETED
                                finalizeRun(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED)
                            } else {
                                emitEvent(AgentRunEvent.PersistenceFailed("finalize_persist"))
                                drive.persistenceMark = PersistenceMark.FAILED
                                finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.PERSISTENCE_FAILED)
                            }
                            return
                        }
                        // 无 finalAnswer → 继续下一轮
                    }

                    is ProviderCallResult.RateLimited -> {
                        drive.cooldowns.add(CooldownRecord(
                            providerId = "provider_$attemptIdx",
                            cooldownUntilMs = System.nanoTime() / 1_000_000L + providerResult.cooldownMs,
                        ))
                        // 还有后续 attempt → fallback 继续
                        if (attemptIdx + 1 < turn.attempts.size) {
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FALLBACK_FAILURE
                            ))
                            emitTrace(TraceEventType.FALLBACK_SELECTED, "rate limited, fallback to ${attemptIdx + 1}")
                            emitEvent(AgentRunEvent.FallbackSelected(attemptIdx + 1))
                        } else {
                            // fallback 链耗尽 → FATAL 直达 FINALIZING（不能先 TRANSIENT 再 FATAL）
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            emitEvent(AgentRunEvent.ProcessInterrupted("all_fallbacks_exhausted"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "all_fallbacks_exhausted after rate limit")
                            finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                            return
                        }
                    }

                    is ProviderCallResult.StreamReset -> {
                        // 尝试重试（同一 provider）或 fallback
                        if (attemptIdx + 1 < turn.attempts.size) {
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.TRANSIENT_FAILURE
                            ))
                            emitTrace(TraceEventType.RETRY_REQUESTED, "stream reset, retry")
                            emitEvent(AgentRunEvent.RetryRequested("stream_reset"))
                        } else {
                            // fallback 链耗尽 → 直接 FATAL（TRANSIENT 后再 FATAL 会被 reducer 拒绝）
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            emitEvent(AgentRunEvent.ProcessInterrupted("all_fallbacks_exhausted"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "all_fallbacks_exhausted after stream reset")
                            finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                            return
                        }
                    }

                    is ProviderCallResult.DroppedAfterFirstChunk -> {
                        // 首 chunk 后断流 → 部分输出已产生 → INTERRUPTED（可恢复）
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.TRANSIENT_FAILURE
                        ))
                        drive.persistenceMark = PersistenceMark.PARTIAL
                        emitEvent(AgentRunEvent.ProcessInterrupted("dropped_after_first_chunk"))
                        emitTrace(TraceEventType.PROCESS_INTERRUPTED, "dropped after first chunk")
                        finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED)
                        return
                    }

                    is ProviderCallResult.HardFailure -> {
                        if (attemptIdx + 1 < turn.attempts.size) {
                            // 还有 fallback → 尝试下一个
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FALLBACK_FAILURE
                            ))
                            emitTrace(TraceEventType.FALLBACK_SELECTED, "hard failure, fallback to ${attemptIdx + 1}")
                            emitEvent(AgentRunEvent.FallbackSelected(attemptIdx + 1))
                        } else {
                            // fallback 链耗尽
                            emitEvent(AgentRunEvent.ProviderAttemptFinished(
                                ProviderAttemptOutcome.FATAL_FAILURE
                            ))
                            emitEvent(AgentRunEvent.ProcessInterrupted("all_fallbacks_exhausted"))
                            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "all_fallbacks_exhausted after hard failure")
                            finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
                            return
                        }
                    }

                    is ProviderCallResult.LengthFinish -> {
                        // finish_reason=length → 续写（下一轮继续）
                        emitEvent(AgentRunEvent.ProviderAttemptFinished(
                            ProviderAttemptOutcome.SUCCESS
                        ))
                    }
                }
            }
        }

        // ── 所有 turn 耗尽 → 正常结束（但未 finalAnswer）────────────
        if (budget.isExpired()) {
            emitEvent(AgentRunEvent.DeadlineReached(budget.startedAtMonotonicMs))
            emitTrace(TraceEventType.DEADLINE_REACHED, "deadline after turns exhausted")
            finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED)
        } else if (drive.hasOutcomeUnknownTool) {
            // outcome unknown 工具 → 不能证明副作用安全（F06 语义）→ INTERRUPTED 可恢复
            emitEvent(AgentRunEvent.ProcessInterrupted("outcome_unknown_tool"))
            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "run ended with outcome unknown tool")
            finalizeRun(AgentTerminal.INTERRUPTED, AgentTerminalReason.OUTCOME_UNKNOWN)
        } else {
            emitEvent(AgentRunEvent.ProcessInterrupted("turns_exhausted_without_final"))
            emitTrace(TraceEventType.PROCESS_INTERRUPTED, "turns exhausted without final")
            finalizeRun(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED)
        }
    }

    private fun emit(
        runtime: AgentRuntimePort,
        drive: DriveState,
        type: TraceEventType,
        detail: String? = null,
    ) {
        val now = System.nanoTime() / 1_000_000L
        drive.traceEvents += HarnessTraceEvent(type, now, detail)
        TraceBridge.emit(runtime, type, now, detail)
    }

    private fun apply(
        reduce: (AgentRunState, AgentRunEvent) -> AgentRunTransition,
        state: AgentRunState,
        event: AgentRunEvent,
    ): AgentRunState {
        return when (val t = reduce(state, event)) {
            is AgentRunTransition.Accepted -> t.state
            is AgentRunTransition.Rejected ->
                // 非法转换必须显式可见（不静默修正），骨架记录后保持原状态。
                // TODO(await T7): 接入 trace/报告作为 violation 或 terminal reason。
                state
        }
    }

    private fun evidenceOf(
        drive: DriveState,
        state: AgentRunState,
    ) = ScenarioReportFactory.RunEvidence(
        finalState = state,
        duplicateSideEffects = drive.duplicateSideEffects,
        sessionSlotReleased = drive.sessionSlotReleased,
        persistenceMark = drive.persistenceMark,
        traceEvents = drive.traceEvents,
        providerCancellations = drive.providerCancellations,
        toolCancellations = drive.toolCancellations,
        spawnRejected = drive.spawnRejected,
        compactCalls = drive.compactCalls,
        historyIntact = drive.historyIntact,
    )

    companion object {
        /**
         * 默认预算：镜像 T4-A `HarnessBudget` 的宽松上限（DEFAULT_MAX=100），
         * deadline 取"far future"（场景自带 deadline 覆盖）。
         * Assumed(await T7)：T7 冻结后生产预算数字由 T7/T10 决定。
         */
        fun defaultBudgetFor(scenario: FaultScenario): AgentExecutionBudget {
            val now = System.nanoTime() / 1_000_000L
            return AgentExecutionBudget(
                startedAtMonotonicMs = now,
                deadlineMonotonicMs = now + scenario.deadlineMs.coerceAtMost(Long.MAX_VALUE / 4),
                maxTurns = 100,
                maxProviderAttempts = 100,
                maxToolCalls = 100,
                maxShellCommands = 100,
                maxCompactionCalls = 100,
                maxConcurrentTools = 4,
                maxEstimatedTokens = null, // token 计数不可靠时不伪造
                monotonicClock = { System.nanoTime() / 1_000_000L },
            )
        }
    }
}