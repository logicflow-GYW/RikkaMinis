package com.rikkaminis.app.harness.adapter.real

import com.rikkaminis.app.harness.adapter.TraceBridge
import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.TraceEventType
import com.rikkaminis.app.tools.AgentTraceRecorder
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentTerminal

/**
 * schema v2 的 phase 枚举字符串（与 `rikkaminis-trace-schema.md` §3 一致：
 * camelCase，如 "CallingModel"，不是枚举的 SCREAMING_SNAKE）。
 */
internal fun t7RealPhaseSchema(phase: AgentRunPhase): String = when (phase) {
    AgentRunPhase.IDLE -> "Idle"
    AgentRunPhase.PREPARING -> "Preparing"
    AgentRunPhase.CALLING_MODEL -> "CallingModel"
    AgentRunPhase.EXECUTING_TOOLS -> "ExecutingTools"
    AgentRunPhase.RETRYING -> "Retrying"
    AgentRunPhase.FALLING_BACK -> "FallingBack"
    AgentRunPhase.COMPACTING -> "Compacting"
    AgentRunPhase.FINALIZING -> "Finalizing"
    AgentRunPhase.SUCCEEDED -> "Succeeded"
    AgentRunPhase.FAILED -> "Failed"
    AgentRunPhase.CANCELLED -> "Cancelled"
    AgentRunPhase.INTERRUPTED -> "Interrupted"
}

/** schema v2 的 terminal 枚举字符串（与 ChatViewModel.t7TerminalSchema 一致）。 */
internal fun t7RealTerminalSchema(terminal: AgentTerminal): String = when (terminal) {
    AgentTerminal.SUCCEEDED -> "Succeeded"
    AgentTerminal.FAILED -> "Failed"
    AgentTerminal.CANCELLED -> "Cancelled"
    AgentTerminal.INTERRUPTED -> "Interrupted"
}

/**
 * [T7-real-runtime] 真实 trace 运行时 —— 把 harness 的 [HarnessTraceEvent]
 * 桥接到生产 [AgentTraceRecorder]（T6，schema 2.0 JSONL）。
 *
 * ## 职责
 *
 * T4-A harness 以 [TraceEventType] 序列描述一次 run；生产侧 trace 以
 * `AgentTraceRecorder` 的 schema 2.0 事件（state_transition / budget_consume /
 * resource_acquire / trace_end 等）落盘。本类把两者映射：
 *
 * | HarnessTraceEvent | AgentTraceRecorder 调用 |
 * |---|---|
 * | `RUN_START` | `beginRun`（含 provider_count/tool_count） |
 * | `PROVIDER_ATTEMPT_STARTED` | `stateTransition`（→ CallingModel）+ `budgetConsume(provider_attempts)` |
 * | `TOOL_STARTED` | `stateTransition`（→ ExecutingTools） |
 * | `TOOL_FINISHED` | `stateTransition`（ExecutingTools 自环） |
 * | `COMPACT_STARTED` | `stateTransition`（→ Compacting） |
 * | `COMPACT_FINISHED` | `stateTransition`（→ CallingModel） |
 * | `RETRY_REQUESTED` | `stateTransition`（→ Retrying） |
 * | `FALLBACK_SELECTED` | `stateTransition`（→ FallingBack） |
 * | `USER_CANCELLED` / `DEADLINE_REACHED` / `PROCESS_INTERRUPTED` | `stateTransition`（→ Finalizing） |
 * | `RUN_FINALIZED` | `stateTransition`（→ 终态）+ `endRun` |
 * | `SPAWN_REJECTED` | `error` |
 *
 * 与 [TraceBridge] 的关系：骨架通过 `TraceBridge.emit` 输出（写失败不阻断）；
 * 本类作为征收端把同一事件流落成生产 schema。recorder 自带 terminal 去重
 * （`writeTerminalOnce`），多次 RUN_FINALIZED 不会产生重复 trace_end。
 *
 * ## 生产装配
 *
 * 生产环境把 [com.rikkaminis.app.tools.AgentTraceRecorder] 以真实文件 sink
 * （`workspace/.traces/agent-<ts>.jsonl`）构造并传入；JVM 测试用内存 sink
 * （`MutableList<String>` 收集），验证schema 合法性与事件序列。
 */
class RealTraceRuntime(
    private val recorder: AgentTraceRecorder,
    private val runId: String,
    private val sessionId: String,
) {
    /** 收集到的原始 JSONL 行（测试断言用；生产可忽略）。 */
    val lines: MutableList<String> = mutableListOf()

    private val phaseSchema: Map<TraceEventType, String?> = mapOf(
        TraceEventType.RUN_START to null,
        TraceEventType.PROVIDER_ATTEMPT_STARTED to t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
        TraceEventType.TOOL_STARTED to t7RealPhaseSchema(AgentRunPhase.EXECUTING_TOOLS),
        TraceEventType.TOOL_FINISHED to t7RealPhaseSchema(AgentRunPhase.EXECUTING_TOOLS),
        TraceEventType.COMPACT_STARTED to t7RealPhaseSchema(AgentRunPhase.COMPACTING),
        TraceEventType.COMPACT_FINISHED to t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
        TraceEventType.RETRY_REQUESTED to t7RealPhaseSchema(AgentRunPhase.RETRYING),
        TraceEventType.FALLBACK_SELECTED to t7RealPhaseSchema(AgentRunPhase.FALLING_BACK),
        TraceEventType.USER_CANCELLED to t7RealPhaseSchema(AgentRunPhase.FINALIZING),
        TraceEventType.DEADLINE_REACHED to t7RealPhaseSchema(AgentRunPhase.FINALIZING),
        TraceEventType.PROCESS_INTERRUPTED to t7RealPhaseSchema(AgentRunPhase.FINALIZING),
        TraceEventType.PERSISTENCE_FAILED to t7RealPhaseSchema(AgentRunPhase.FINALIZING),
        TraceEventType.RUN_FINALIZED to null, // 终态由 detail 解析
        TraceEventType.SPAWN_REJECTED to null,
    )

    /**
     * 处理一条事件。写失败吞异常（蓝图 §T6 兼容要求），不阻断主执行。
     */
    fun record(event: HarnessTraceEvent) {
        runCatching { recordInternal(event) }
    }

    private fun recordInternal(event: HarnessTraceEvent) {
        when (event.type) {
            TraceEventType.RUN_START -> {
                _startMs = event.atMs
                recorder.beginRun(
                    runId = runId,
                    sessionId = sessionId,
                    provider = "harness",
                    prompt = event.detail ?: "scenario run",
                    providerCount = 0,
                    toolCount = 0,
                )
            }
            // attempt 结束：若为最终失败进入 Finalizing（骨架同时发 PROCESS_INTERRUPTED
            // 或 RUN_FINALIZED 处理终态；此处仅记录结果，不重复 stateTransition）。
            TraceEventType.PROVIDER_ATTEMPT_FINISHED -> Unit
            TraceEventType.PROVIDER_ATTEMPT_STARTED -> {
                recorder.stateTransition(
                    from = "Idle".takeIf { _first } ?: phaseSchema[TraceEventType.PROVIDER_ATTEMPT_STARTED]!!,
                    to = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    reason = "ProviderAttemptStarted",
                )
                _first = false
                recorder.budgetConsume(
                    dimension = AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS,
                    consumed = 1,
                    remaining = 0,
                    total = 0,
                )
            }
            TraceEventType.TOOL_STARTED -> {
                recorder.toolCall(
                    turn = 0,
                    toolId = "tool-${event.atMs}",
                    name = event.detail ?: "unknown",
                    argsJson = "{}",
                )
            }
            TraceEventType.TOOL_FINISHED -> {
                recorder.toolResult(
                    turn = 0,
                    toolId = "tool-${event.atMs}",
                    name = event.detail ?: "unknown",
                    success = true,
                    output = "{}",
                    durationMs = 0,
                )
            }
            TraceEventType.COMPACT_STARTED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.COMPACTING),
                    reason = event.detail,
                )
            }
            TraceEventType.COMPACT_FINISHED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.COMPACTING),
                    to = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    reason = event.detail,
                )
            }
            TraceEventType.RETRY_REQUESTED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.RETRYING),
                    reason = event.detail,
                )
                recorder.retryDecision(
                    operationType = "provider_call",
                    operationName = event.detail,
                    safetyLevel = AgentTraceRecorder.SAFETY_READ_ONLY,
                    outcome = AgentTraceRecorder.OUTCOME_SAFE_TO_RETRY,
                    reason = event.detail,
                    attempt = null,
                    maxAttempts = null,
                    willRetry = true,
                )
            }
            TraceEventType.FALLBACK_SELECTED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.FALLING_BACK),
                    reason = event.detail,
                )
            }
            TraceEventType.USER_CANCELLED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.FINALIZING),
                    reason = "UserCancelled: ${event.detail}",
                )
            }
            TraceEventType.DEADLINE_REACHED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.FINALIZING),
                    reason = "DeadlineReached",
                )
            }
            TraceEventType.PROCESS_INTERRUPTED -> {
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.CALLING_MODEL),
                    to = t7RealPhaseSchema(AgentRunPhase.FINALIZING),
                    reason = event.detail ?: "ProcessInterrupted",
                )
            }
            TraceEventType.PERSISTENCE_FAILED -> {
                recorder.persistenceResult(
                    target = "message_db",
                    success = false,
                    errorType = "write_failure",
                    durationMs = 0,
                )
            }
            TraceEventType.RUN_FINALIZED -> {
                val terminal = when {
                    event.detail?.contains("SUCCEEDED") == true -> AgentTerminal.SUCCEEDED
                    event.detail?.contains("CANCELLED") == true -> AgentTerminal.CANCELLED
                    event.detail?.contains("INTERRUPTED") == true -> AgentTerminal.INTERRUPTED
                    else -> AgentTerminal.FAILED
                }
                recorder.stateTransition(
                    from = t7RealPhaseSchema(AgentRunPhase.FINALIZING),
                    to = t7RealTerminalSchema(terminal),
                    reason = event.detail,
                )
                recorder.endRun(
                    terminalState = t7RealTerminalSchema(terminal),
                    terminalReason = null,
                    durationMs = if (_startMs > 0) event.atMs - _startMs else 0,
                )
            }
            TraceEventType.SPAWN_REJECTED -> {
                recorder.error(turn = null, phase = t7RealPhaseSchema(AgentRunPhase.EXECUTING_TOOLS), message = "spawn rejected")
            }
        }
    }

    private var _first = true
    private var _startMs = 0L

    /** run 开始时刻（单调），用于 duration 计算。 */
    fun markStart(atMs: Long) {
        _startMs = atMs
    }

    companion object {
        /** 生产 sink：创建写文件（或转发）的 recorder。sink 由调用方提供。 */
        fun createWithSink(
            runId: String,
            sessionId: String,
            sink: (String) -> Unit,
        ): RealTraceRuntime {
            val recorder = AgentTraceRecorder(appendLine = sink)
            return RealTraceRuntime(recorder, runId, sessionId)
        }
    }
}