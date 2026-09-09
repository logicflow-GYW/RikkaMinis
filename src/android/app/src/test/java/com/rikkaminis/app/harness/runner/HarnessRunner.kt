package com.rikkaminis.app.harness.runner

import com.rikkaminis.app.harness.contract.*
import com.rikkaminis.app.harness.fakes.*

/**
 * 参考 Agent Run 编排器（test-only）。
 *
 * 确定性执行：所有时间由 FakeClock 驱动，无真实并发。
 * 体现蓝图 §4.1-§4.4 的终态、预算、重试和副作用契约。
 *
 * @param clock 注入的确定性时钟
 * @param trace 注入的 trace sink
 * @param policies 策略注入（正常 = 默认，可注入 bug 用于负例测试）
 */
class HarnessRunner(
    private val clock: FakeClock,
    private val trace: FakeTraceSink,
    private val policies: RunnerPolicies = RunnerPolicies(),
) {

    /**
     * 运行一个故障场景。
     * @param scenario 场景定义
     * @param providers 按 fallback 顺序的 provider 列表
     * @param tools 工具执行器
     * @param shell shell 执行器（可选）
     * @param persistence 持久化
     * @param slots 会话槽位
     * @return 场景运行报告
     */
    fun run(
        scenario: FaultScenario,
        providers: List<FakeProvider>,
        tools: FakeToolExecutor,
        shell: FakeShell?,
        persistence: FakePersistence,
        slots: FakeSessionSlots,
    ): ScenarioReport {
        val startMs = clock.now()
        val deadlineMs = startMs + scenario.deadlineMs
        val runId = "run-${scenario.id}-${startMs}"
        val budget = HarnessBudget(
            maxTurns = BudgetSnapshot.DEFAULT_MAX,
            maxProviderAttempts = BudgetSnapshot.DEFAULT_MAX,
            maxToolCalls = BudgetSnapshot.DEFAULT_MAX,
            maxShellCommands = BudgetSnapshot.DEFAULT_MAX,
            maxCompactionCalls = BudgetSnapshot.DEFAULT_MAX,
        )

        // 槽位 acquire
        slots.acquire(runId)

        // 追踪
        trace.emit(TraceEventType.RUN_START, clock.now(), runId)

        // 运行时状态
        var terminal: TerminalState? = null
        val cooldownRecords = mutableListOf<CooldownRecord>()
        var providerAttempts = 0
        var toolExecutions = 0
        var toolCancellations = 0
        var providerCancellations = 0
        var spawnRejected = 0
        var compactCalls = 0
        val performedToolOps = mutableSetOf<String>()
        var duplicateSideEffects = 0
        var partialContentEmitted = false

        // 处理进程死亡（F14）
        fun checkProcessDeath(): Boolean {
            val deathAt = scenario.processDeathAtMs
            if (deathAt != null && clock.now() >= deathAt) {
                trace.emit(TraceEventType.PROCESS_INTERRUPTED, clock.now(), "process death at ${clock.now()}")
                // 不 re-execute 副作用（已发生的不重复）
                terminal = TerminalState.INTERRUPTED
                return true
            }
            return false
        }

        // 检查 deadline
        fun checkDeadline(): Boolean {
            if (clock.now() >= deadlineMs) {
                trace.emit(TraceEventType.DEADLINE_REACHED, clock.now(), "deadline at ${deadlineMs}")
                if (!policies.ignoreBudget) {
                    terminal = TerminalState.INTERRUPTED
                }
                return true
            }
            return false
        }

        // 检查用户取消
        fun checkCancel(): Boolean {
            val cancelAt = scenario.userCancelAtMs
            if (cancelAt != null && clock.now() >= cancelAt) {
                trace.emit(TraceEventType.USER_CANCELLED, clock.now(), "user cancelled at ${cancelAt}")
                terminal = TerminalState.CANCELLED
                return true
            }
            return false
        }

        // 执行一个 provider attempt
        fun performAttempt(attempt: AttemptScript, providerIndex: Int): AttemptResult {
            providerAttempts++
            val provider = providers[providerIndex]
            provider.recordAttempt()
            val attemptLabel = "turn.${budget.turnsUsed}.provider.${providerIndex}"
            trace.emit(TraceEventType.PROVIDER_ATTEMPT_STARTED, clock.now(), attemptLabel)

            // 延迟
            if (attempt.delayMs > 0) {
                // 延迟期间可能被取消打断
                var remaining = attempt.delayMs
                val stepMs = 10L // 检查粒度
                while (remaining > 0) {
                    clock.advance(minOf(stepMs, remaining))
                    remaining -= stepMs
                    if (checkCancel() || checkProcessDeath() || checkDeadline()) {
                        providerCancellations++
                        trace.emit(TraceEventType.PROVIDER_ATTEMPT_FINISHED, clock.now(), "$attemptLabel cancelled")
                        return AttemptResult.HARD_FAILURE
                    }
                }
            }

            if (checkCancel() || checkProcessDeath() || checkDeadline()) {
                providerCancellations++
                trace.emit(TraceEventType.PROVIDER_ATTEMPT_FINISHED, clock.now(), "$attemptLabel cancelled")
                return AttemptResult.HARD_FAILURE
            }

            trace.emit(TraceEventType.PROVIDER_ATTEMPT_FINISHED, clock.now(), "$attemptLabel ${attempt.result}")

            when (attempt.result) {
                AttemptResult.HTTP_429 -> {
                    val cooldownMs = attempt.cooldownMs
                    provider.markCooldown(clock.now(), cooldownMs)
                    cooldownRecords.add(CooldownRecord(provider.providerId, clock.now() + cooldownMs))
                    trace.emit(TraceEventType.RETRY_REQUESTED, clock.now(), "429 $attemptLabel")
                    trace.emit(TraceEventType.FALLBACK_SELECTED, clock.now(), "fallback from ${provider.providerId}")
                }
                AttemptResult.STREAM_RESET -> {
                    trace.emit(TraceEventType.RETRY_REQUESTED, clock.now(), "stream_reset $attemptLabel")
                    trace.emit(TraceEventType.FALLBACK_SELECTED, clock.now(), "fallback from ${provider.providerId}")
                }
                AttemptResult.DROP_AFTER_FIRST_CHUNK -> {
                    partialContentEmitted = true
                    // 不触发 fallback — 已有部分输出
                }
                AttemptResult.SUCCESS, AttemptResult.LENGTH_FINISH -> {
                    // 成功，继续
                }
                AttemptResult.HARD_FAILURE -> {
                    trace.emit(TraceEventType.FALLBACK_SELECTED, clock.now(), "fallback from ${provider.providerId}")
                }
            }

            return attempt.result
        }

        // 执行工具
        fun executeTools(toolNames: List<String>) {
            for (toolName in toolNames) {
                if (checkCancel() || checkProcessDeath() || checkDeadline()) break

                val script = scenario.toolScripts[toolName]
                val operationId = "turn.${budget.turnsUsed}.tool.$toolName"
                trace.emit(TraceEventType.TOOL_STARTED, clock.now(), operationId)
                toolExecutions++

                // 工具执行延迟模拟
                clock.advance(5) // 短延迟
                val result = tools.execute(toolName, operationId)

                if (result.cancelled) {
                    trace.emit(TraceEventType.TOOL_FINISHED, clock.now(), "$operationId cancelled")
                    toolCancellations++
                    terminal = TerminalState.CANCELLED
                    break
                }

                if (result.spawnRejected) {
                    spawnRejected++
                    trace.emit(TraceEventType.SPAWN_REJECTED, clock.now(), "recursive spawn rejected: $toolName")
                }

                if (result.sideEffectPerformed) {
                    if (policies.rerunUnknownSideEffect && operationId in performedToolOps) {
                        duplicateSideEffects++
                    }
                    performedToolOps.add(operationId)
                }

                // SIDE_EFFECT_THEN_NO_RESULT：副作用已发生但无结果
                if (result.outcomeUnknown) {
                    trace.emit(TraceEventType.TOOL_FINISHED, clock.now(), "$operationId outcomeUnknown")
                    // 不重跑，终态为 Interrupted（除非有 cancel/deadline）
                    if (terminal == null) {
                        terminal = TerminalState.INTERRUPTED
                    }
                    break
                }

                trace.emit(TraceEventType.TOOL_FINISHED, clock.now(), "$operationId ${if (result.success) "success" else "failure"}")
            }
        }

        // 执行 compact
        fun performCompact(delayMs: Long, timeoutMs: Long) {
            budget.consumeCompaction()
            compactCalls++
            trace.emit(TraceEventType.COMPACT_STARTED, clock.now(), "compact delay=${delayMs}ms timeout=${timeoutMs}ms")

            if (delayMs > timeoutMs && timeoutMs > 0) {
                clock.advance(timeoutMs)
                // 超时
                if (!policies.ignoreBudget) {
                    terminal = TerminalState.INTERRUPTED
                }
                trace.emit(TraceEventType.COMPACT_FINISHED, clock.now(), "compact timeout")
                // 超时 = 历史不损坏（compact 从未完成，原始历史完整）
                return
            }

            clock.advance(delayMs)
            trace.emit(TraceEventType.COMPACT_FINISHED, clock.now(), "compact success")
        }

        // ===== 主循环 =====
        turnLoop@ for (turnIndex in scenario.turns.indices) {
            if (terminal != null) break
            if (checkDeadline() || checkCancel() || checkProcessDeath()) break

            val turn = scenario.turns[turnIndex]

            // turn 预算
            if (!policies.ignoreBudget && budget.turnsUsed >= budget.maxTurns) {
                terminal = TerminalState.FAILED
                break
            }
            budget.consumeTurn()

            // Compact (if this turn has one)
            if (turn.compactDelayMs > 0) {
                performCompact(turn.compactDelayMs, scenario.compactTimeoutMs)
                if (terminal != null) break
            }

            // Provider 链
            var succeeded = false
            for (providerIdx in providers.indices) {
                if (terminal != null) break@turnLoop

                val attemptIdx = if (turn.attempts.indices.contains(providerIdx)) providerIdx
                else turn.attempts.indices.last()

                if (providerIdx >= turn.attempts.size) {
                    // 链耗尽
                    break
                }

                val attempt = turn.attempts[providerIdx]
                val result = performAttempt(attempt, providerIdx)

                when (result) {
                    AttemptResult.SUCCESS -> {
                        succeeded = true
                        // 检查是否需要工具执行
                        executeTools(attempt.toolCalls)
                        if (terminal != null) break@turnLoop

                        if (attempt.finalAnswer) {
                            terminal = TerminalState.SUCCEEDED
                            break@turnLoop
                        }
                        // 非 finalAnswer → 继续下一 turn（工具结果已回传）
                        break
                    }
                    AttemptResult.LENGTH_FINISH -> {
                        // 续写：下一 turn
                        break
                    }
                    AttemptResult.DROP_AFTER_FIRST_CHUNK -> {
                        // 部分输出 → Interrupted
                        if (!policies.ignoreBudget) {
                            terminal = TerminalState.INTERRUPTED
                        }
                        break@turnLoop
                    }
                    AttemptResult.HTTP_429, AttemptResult.STREAM_RESET, AttemptResult.HARD_FAILURE -> {
                        // 继续下一个 provider
                        continue
                    }
                }
            }

            if (!succeeded && terminal == null) {
                // 所有 provider 失败
                terminal = TerminalState.FAILED
            }
        }

        // 如果循环结束但终态未设（如所有 turn 走完但无 final answer）
        if (terminal == null) {
            terminal = TerminalState.FAILED
        }

        // ===== Finalize =====
        // 持久化写入
        val persistenceMark = when {
            persistence.script.failOnFinalize -> {
                persistence.finalize(PersistenceMark.FAILED)
                trace.emit(TraceEventType.PERSISTENCE_FAILED, clock.now(), "persistence finalize failed")
                // 终态不得为 Succeeded
                if (terminal == TerminalState.SUCCEEDED) {
                    terminal = TerminalState.FAILED
                }
                PersistenceMark.FAILED
            }
            partialContentEmitted -> {
                persistence.finalize(PersistenceMark.PARTIAL)
                PersistenceMark.PARTIAL
            }
            terminal == TerminalState.CANCELLED -> {
                persistence.finalize(PersistenceMark.PARTIAL)
                PersistenceMark.PARTIAL
            }
            terminal == TerminalState.INTERRUPTED -> {
                persistence.finalize(PersistenceMark.PARTIAL)
                PersistenceMark.PARTIAL
            }
            terminal == TerminalState.SUCCEEDED -> {
                if (policies.succeedAfterPersistenceFail) {
                    // 负例策略：持久化失败仍标记 Succeeded
                    persistence.finalize(PersistenceMark.FAILED)
                    PersistenceMark.FAILED
                } else {
                    persistence.finalize(PersistenceMark.COMPLETED)
                    PersistenceMark.COMPLETED
                }
            }
            else -> {
                persistence.finalize(PersistenceMark.NONE)
                PersistenceMark.NONE
            }
        }

        // 如果 persist 失败但 policy 强制 succeed
        if (policies.succeedAfterPersistenceFail) {
            // 已经是 Failed 了，不变
        }

        // Trace final event
        if (policies.finalizeTwice) {
            trace.emit(TraceEventType.RUN_FINALIZED, clock.now(), "finalize (1)")
            trace.emit(TraceEventType.RUN_FINALIZED, clock.now(), "finalize (2)")
        } else {
            trace.emit(TraceEventType.RUN_FINALIZED, clock.now(), "terminal=$terminal persistence=$persistenceMark")
        }

        // 释放槽位
        if (!policies.leakSessionLease) {
            slots.release(runId)
        }

        // 构建报告
        val terminalEvents = trace.count(TraceEventType.RUN_FINALIZED)
        val totalToolSideEffects = tools.sideEffectCount + (shell?.sideEffectCount ?: 0)
        val totalDuplicates = duplicateSideEffects +
            tools.duplicateSideEffectCount +
            (shell?.duplicateSideEffectCount ?: 0)

        return ScenarioReport(
            terminal = terminal!!,
            providerAttempts = providerAttempts,
            toolExecutions = toolExecutions,
            duplicateSideEffects = totalDuplicates,
            budgetSnapshot = budget.snapshot(),
            leaseCount = if (slots.isReleased(runId)) 0 else 1,
            traceTerminalEvents = terminalEvents,
            persistenceMark = persistenceMark,
            recoverable = when (terminal) {
                TerminalState.INTERRUPTED -> true  // 可安全恢复
                TerminalState.FAILED -> false
                TerminalState.CANCELLED -> false
                TerminalState.SUCCEEDED -> false
            },
            cooldownRecords = cooldownRecords,
            providerCancellations = providerCancellations,
            toolCancellations = toolCancellations,
            spawnRejected = spawnRejected,
            compactCalls = compactCalls,
            historyIntact = !persistence.historyOverwritten,
            traceEvents = trace.events.toList(),
        )
    }
}

/**
 * 策略注入：正常 = 全 false。负例/变异测试时注入特定 bug。
 */
data class RunnerPolicies(
    /** 忽略预算上限（允许超限）。 */
    val ignoreBudget: Boolean = false,
    /** 发射两次 RUN_FINALIZED。 */
    val finalizeTwice: Boolean = false,
    /** 副作用未知的工具被透明重跑。 */
    val rerunUnknownSideEffect: Boolean = false,
    /** 不释放 session 槽位。 */
    val leakSessionLease: Boolean = false,
    /** 持久化失败后仍标记 Succeeded。 */
    val succeedAfterPersistenceFail: Boolean = false,
)

/**
 * 简单的预算计数模型（test-only，镜像蓝图 §4.3）。
 */
class HarnessBudget(
    val maxTurns: Int,
    val maxProviderAttempts: Int,
    val maxToolCalls: Int,
    val maxShellCommands: Int,
    val maxCompactionCalls: Int,
) {
    var turnsUsed: Int = 0; private set
    var providerAttemptsUsed: Int = 0; private set
    var toolCallsUsed: Int = 0; private set
    var shellCommandsUsed: Int = 0; private set
    var compactionCallsUsed: Int = 0; private set

    fun consumeTurn() { turnsUsed++ }
    fun consumeProviderAttempt() { providerAttemptsUsed++ }
    fun consumeToolCall() { toolCallsUsed++ }
    fun consumeShellCommand() { shellCommandsUsed++ }
    fun consumeCompaction() { compactionCallsUsed++ }

    fun snapshot(): BudgetSnapshot = BudgetSnapshot(
        turnsUsed = turnsUsed,
        maxTurns = maxTurns,
        providerAttemptsUsed = providerAttemptsUsed,
        maxProviderAttempts = maxProviderAttempts,
        toolCallsUsed = toolCallsUsed,
        maxToolCalls = maxToolCalls,
        shellCommandsUsed = shellCommandsUsed,
        maxShellCommands = maxShellCommands,
        compactionCallsUsed = compactionCallsUsed,
        maxCompactionCalls = maxCompactionCalls,
        expired = false,
    )
}