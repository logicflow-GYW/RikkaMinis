package com.openminis.app.ui.chat

import com.openminis.app.agent.runtime.AgentExecutionBudget
import com.openminis.app.agent.runtime.AgentRunEvent
import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentRunReducer
import com.openminis.app.agent.runtime.AgentRunState
import com.openminis.app.agent.runtime.AgentRunTransition
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import com.openminis.app.agent.runtime.BudgetDecision
import com.openminis.app.agent.runtime.BudgetExhaustedReason
import com.openminis.app.agent.runtime.BudgetSnapshot
import com.openminis.app.tools.AgentTraceRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FE-5 route C (step 1): T7/T9 agent-run observation layer extracted from
 * ChatViewModel. This class owns, verbatim, what used to be scattered VM
 * state + methods:
 *
 *  - T9 trace file management ([traceRunFile], [newTraceFile],
 *    [retainTraceFiles], [appendTraceLine]) — one JSONL file per run.
 *  - T7-A advisory budget consume/trace ([t7ConsumeAndTrace],
 *    [t7Remaining], [t7Total]).
 *  - T7-A observation state ([activeRunId], [activeRunBudget],
 *    [t7ObservedPhase], [t7BudgetStopReason]) + tolerant trace wrappers
 *    ([t7State], [t7Retry]).
 *  - T7-B resource lease wrappers ([t7ResourceAcquire],
 *    [t7ResourceRelease]) and unified run finalization ([t7EndRun]).
 *  - T7-D bypass-verification reducer ([t7Reduce], [t7ReducerState]).
 *  - [activeTraceTurn] — current loop turn index, read by executeTool so
 *    tool events carry the turn they belong to.
 *
 * Pure JVM concerns only: no Android framework imports (the session-scoped
 * trace directory is injected as [traceDirResolver], the wall/monotonic
 * clocks as [wallClockMs]/[monotonicClockMs], and the reducer-rejection
 * logger as [warn]). Behavior is verbatim-identical to the former VM
 * members, including the "trace failures never break the agent loop"
 * invariant.
 *
 * Concurrency: same as when these fields lived on the VM — the fields are
 * @Volatile and touched from the loop coroutine (plus Main hops for
 * cross-function events like compactAll / cancelStream). No new
 * synchronization is introduced by the move.
 */
internal class ChatAgentTraceObserver(
    private val traceDirResolver: (sessionId: String) -> File?,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicClockMs: () -> Long = System::nanoTime,
    private val warn: (String, String) -> Unit = { _, _ -> },
) {
    /** T9: trace recorder, sunk into [appendTraceLine]. */
    internal val agentTraceRecorder =
        AgentTraceRecorder(appendLine = { line -> appendTraceLine(line) })

    /**
     * Host-side state backing [agentTraceRecorder]:
     *  - [traceRunFile] — the file of the run currently being recorded
     *    (null when no run is active). Captured at runAgentLoop entry so
     *    every event of one run lands in the same file even though the
     *    recorder's sink is a stateless callback.
     *  - [activeTraceTurn] — current loop turn index, read by executeTool so
     *    tool events carry the turn they belong to.
     */
    @Volatile
    internal var traceRunFile: File? = null

    @Volatile
    internal var activeTraceTurn = -1

    /**
     * T7: Agent Run 观察状态（T7-A 阶段只接入 trace，不改变行为）。
     *
     *  - [activeRunId] — 本轮 run 的唯一标识（T1 runId 语义；T7-A 阶段用
     *    局部 UUID，T7-B 接 SessionSlotController 后替换为槽位 runId）。
     *  - [activeRunBudget] — 本轮 run 的观察预算（advisory）：各计数
     *    consume 并在 trace 里记录 budget_consume / budget_refuse，但
     *    **不阻断任何行为**（T7-C 才启用 deadline/计数预算的 enforced 语义）。
     */
    @Volatile
    internal var activeRunId: String? = null

    @Volatile
    internal var activeRunBudget: AgentExecutionBudget? = null

    /**
     * T7-C: 本轮 run 因预算耗尽（deadline / 计数上限）而中断的原因。
     * 由调用点（turn/provider/tool/shell 循环）在 Denied 时设置，
     * runAgentLoop 出口据此选择显式终态（BudgetExhausted 不是静默失败）。
     */
    @Volatile
    internal var t7BudgetStopReason: String? = null

    /**
     * T7-A: 观察用当前 phase（schema 枚举字符串）。仅用于让 UserCancelled /
     * 中断等"任意阶段可达"的事件有准确的 from；不是状态机单一事实源
     * （T7-D 才接 reducer）。
     */
    @Volatile
    internal var t7ObservedPhase: String? = null

    /**
     * T7-D: 终态 reducer 旁路验证状态 —— 类级持有，使 compactAll /
     * cancelStream / executeTool 等独立函数也能发事件（null = 无活跃 run）。
     * 只在 runAgentLoop 生命周期内非 null：入口初始化为 IDLE，
     * t7EndRun 落终态后置 null。
     */
    @Volatile
    internal var t7ReducerState: AgentRunState? = null

    /**
     * T7-D: 把 AgentRun 事件发给 T5 状态机（旁路验证）。reducer 拒绝时只
     * 记录日志，不改生产行为；无活跃 run 时 no-op。
     */
    internal fun t7Reduce(event: AgentRunEvent) {
        val state = t7ReducerState ?: return
        when (val r = AgentRunReducer.reduce(state, event)) {
            is AgentRunTransition.Accepted -> {
                if (r.changed) t7ReducerState = r.state
            }
            is AgentRunTransition.Rejected -> {
                warn(
                    "ChatVMStream",
                    "T7-D reducer REJECTED ${event::class.simpleName}: ${r.rejection.message}",
                )
            }
        }
    }

    /**
     * T9: persist one trace line into the run's trace file. The file is
     * captured once at runAgentLoop entry ([newTraceFile]) so a single run
     * never fragments across files. Failures are swallowed — tracing must
     * never break the agent loop.
     */
    internal fun appendTraceLine(line: String) {
        runCatching {
            val file = traceRunFile ?: return
            file.appendText("$line\n")
        }
    }

    /**
     * T7-A: advisory 预算消耗 + trace 记录。consume 结果无论 Allowed 还是
     * Denied 都只写 trace，**不阻断**（Denied 意味着观察上限到达，记录
     * budget_refuse 供审计；T7-C 接入 enforced 模式后才在 Denied 处停止）。
     * dimension/refuseReason 用 AgentTraceRecorder 的 schema 常量。
     */
    internal fun t7ConsumeAndTrace(
        dimension: String,
        consume: (AgentExecutionBudget) -> BudgetDecision,
    ): Boolean {
        val budget = activeRunBudget ?: return true  // 观察未启动（无预算）→ 不阻断
        // consume 本身是纯逻辑（计数 + 决策），不包 runCatching —— 预算状态
        // 变化不因 trace 失败而丢失；trace 记录单独包 runCatching。
        val decision = consume(budget)
        return when (decision) {
            is BudgetDecision.Allowed -> {
                runCatching {
                    val snap = budget.snapshot()
                    agentTraceRecorder.budgetConsume(
                        dimension = dimension,
                        consumed = 1,
                        remaining = t7Remaining(dimension, snap),
                        total = t7Total(dimension, budget),
                    )
                }
                true
            }
            is BudgetDecision.Denied -> {
                val deniedReason = decision.reason  // smart-cast to Denied
                runCatching {
                    agentTraceRecorder.budgetRefuse(
                        dimension = dimension,
                        requested = 1,
                        remaining = t7Remaining(dimension, budget.snapshot()),
                        reason = when (deniedReason) {
                            BudgetExhaustedReason.TURN_LIMIT,
                            BudgetExhaustedReason.PROVIDER_ATTEMPT_LIMIT,
                            BudgetExhaustedReason.TOOL_CALL_LIMIT,
                            BudgetExhaustedReason.SHELL_COMMAND_LIMIT,
                            BudgetExhaustedReason.COMPACTION_CALL_LIMIT,
                            BudgetExhaustedReason.CONCURRENT_TOOLS_LIMIT,
                            BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED -> AgentTraceRecorder.REFUSE_BUDGET_EXHAUSTED
                            BudgetExhaustedReason.DEADLINE_EXPIRED -> AgentTraceRecorder.REFUSE_DEADLINE_REACHED
                        },
                    )
                }
                false  // T7-C: Denied → 调用点必须停止（不再发新请求/工具）
            }
        }
    }

    internal fun t7Remaining(dimension: String, snap: BudgetSnapshot): Int = when (dimension) {
        AgentTraceRecorder.DIMENSION_TURNS -> snap.turnsUsed.let { T7_OBSERVE_MAX_TURNS - it }
        AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS -> T7_OBSERVE_MAX_PROVIDER_ATTEMPTS - snap.providerAttemptsUsed
        AgentTraceRecorder.DIMENSION_TOOL_CALLS -> T7_OBSERVE_MAX_TOOL_CALLS - snap.toolCallsUsed
        AgentTraceRecorder.DIMENSION_SHELL_COMMANDS -> T7_OBSERVE_MAX_SHELL_COMMANDS - snap.shellCommandsUsed
        AgentTraceRecorder.DIMENSION_COMPACTION_CALLS -> T7_OBSERVE_MAX_COMPACTION_CALLS - snap.compactionCallsUsed
        AgentTraceRecorder.DIMENSION_CONCURRENT_TOOLS -> T7_OBSERVE_MAX_CONCURRENT_TOOLS - snap.concurrentToolsActive
        else -> 0
    }

    internal fun t7Total(dimension: String, budget: AgentExecutionBudget): Int = when (dimension) {
        AgentTraceRecorder.DIMENSION_TURNS -> budget.maxTurns
        AgentTraceRecorder.DIMENSION_PROVIDER_ATTEMPTS -> budget.maxProviderAttempts
        AgentTraceRecorder.DIMENSION_TOOL_CALLS -> budget.maxToolCalls
        AgentTraceRecorder.DIMENSION_SHELL_COMMANDS -> budget.maxShellCommands
        AgentTraceRecorder.DIMENSION_COMPACTION_CALLS -> budget.maxCompactionCalls
        AgentTraceRecorder.DIMENSION_CONCURRENT_TOOLS -> budget.maxConcurrentTools
        else -> 0
    }

    /**
     * T7-A: stateTransition 的容错封装 —— trace 观察失败不影响主执行。
     * 同时维护 [t7ObservedPhase] 供"任意阶段可达"事件（UserCancelled 等）
     * 作为准确的 from。
     */
    internal fun t7State(from: String, to: String, reason: String?) {
        runCatching { agentTraceRecorder.stateTransition(from, to, reason) }
        t7ObservedPhase = to
    }

    /**
     * T7-A: retryDecision 的容错封装 —— 记录 T3 重试策略的观察结果。
     */
    internal fun t7Retry(
        operationType: String,
        operationName: String?,
        safetyLevel: String?,
        outcome: String?,
        reason: String?,
        attempt: Int?,
        maxAttempts: Int?,
        willRetry: Boolean?,
    ) {
        runCatching {
            agentTraceRecorder.retryDecision(
                operationType = operationType,
                operationName = operationName,
                safetyLevel = safetyLevel,
                outcome = outcome,
                reason = reason,
                attempt = attempt,
                maxAttempts = maxAttempts,
                willRetry = willRetry,
            )
        }
    }

    /**
     * T7-B: 资源 lease 的容错封装 —— 记录 resource_acquire 事件并消耗
     * 对应预算维度（advisory）。resourceType 用 AgentTraceRecorder 的
     * RESOURCE_* 常量；leaseToken 用 runId + 资源前缀保证唯一。
     * 观察失败不影响主执行。
     */
    internal fun t7ResourceAcquire(
        resourceType: String,
        resourceId: String,
        leaseToken: String,
    ) {
        runCatching { agentTraceRecorder.resourceAcquire(resourceType, resourceId, leaseToken) }
    }

    /**
     * T7-B: 资源 lease 释放的容错封装 —— 记录 resource_release 事件并释放
     * 对应预算维度（幂等）。releasedBy 用 AgentTraceRecorder 的 RELEASED_*
     * 常量，供审计判断释放原因（normal/cancel/finalize/error/timeout）。
     */
    internal fun t7ResourceRelease(
        resourceType: String,
        resourceId: String,
        leaseToken: String,
        releasedBy: String,
    ) {
        runCatching { agentTraceRecorder.resourceRelease(resourceType, resourceId, leaseToken, releasedBy) }
    }

    /**
     * T7-B: 统一终态收尾 —— 写 2.0 trace_end（terminal state + budget 终态
     * 快照），并清空本轮观察上下文。幂等：trace 侧由 recorder 的 terminal
     * 去重保证只写一次；本函数对 null budget 安全（观察未启动时 no-op）。
     */
    internal fun t7EndRun(
        terminal: AgentTerminal,
        reason: AgentTerminalReason?,
        durationMs: Long,
        error: String? = null,
    ) {
        val budget = activeRunBudget
        val runId = activeRunId
        // T7-D: 终态 reducer —— RunFinalized 只产生一次终态（reducer 幂等保护）
        t7Reduce(
            AgentRunEvent.RunFinalized(
                terminal = terminal,
                reason = reason,
            )
        )
        runCatching {
            // T7-B: session slot lease 释放 —— 任何终态路径都在这里 release，
            // 与 runAgentLoop 入口的 acquire 配对（lease 平衡可被审计）。
            runId?.let { rid ->
                t7ResourceRelease(
                    resourceType = AgentTraceRecorder.RESOURCE_SESSION_SLOT,
                    resourceId = releaseSessionId,
                    leaseToken = "slot-$rid",
                    releasedBy = when (terminal) {
                        AgentTerminal.SUCCEEDED -> AgentTraceRecorder.RELEASED_NORMAL
                        AgentTerminal.CANCELLED -> AgentTraceRecorder.RELEASED_CANCEL
                        AgentTerminal.INTERRUPTED -> AgentTraceRecorder.RELEASED_RECOVERY
                        AgentTerminal.FAILED -> AgentTraceRecorder.RELEASED_ERROR
                    },
                )
            }
            val snap = budget?.snapshot()
            agentTraceRecorder.endRun(
                terminalState = t7TerminalSchema(terminal),
                terminalReason = t7TerminalReasonSchema(reason),
                durationMs = durationMs,
                totalProviderAttempts = snap?.providerAttemptsUsed,
                totalToolCalls = snap?.toolCallsUsed,
                totalShellCommands = snap?.shellCommandsUsed,
                totalCompactions = snap?.compactionCallsUsed,
                budgetFinalJson = snap?.let { t7BudgetSnapshotJson(it) },
                leasesRemaining = 0,
                error = error,
            )
        }
        activeRunBudget = null
        activeRunId = null
        t7ObservedPhase = null
        t7BudgetStopReason = null
        t7ReducerState = null  // T7-D: run 结束，状态机清理
    }

    /**
     * T7-B: session id used for the session-slot lease release events. The
     * observer itself is session-agnostic (the VM re-points it on session
     * switch); the loop sets this at run start so release events carry the
     * same id the acquire used even if the active session changed mid-run.
     */
    @Volatile
    internal var releaseSessionId: String = ""

    /**
     * T9: allocate the trace file for a new run:
     * `minis-sessions/<sid>/workspace/.traces/agent-<stamp>.jsonl`.
     * Collision-safe (appends -2/-3…) and applies a simple retention cap
     * (oldest files pruned beyond [MAX_TRACE_FILES_PER_SESSION]) so a chatty
     * session can't accumulate unbounded trace data.
     */
    internal fun newTraceFile(sessionId: String): File? {
        return runCatching {
            val stamps = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val baseName = "agent-${stamps.format(Date())}"
            val dir = traceDirResolver(sessionId)
                ?: return null
            dir.mkdirs()
            var file = File(dir, "$baseName.jsonl")
            var n = 2
            while (file.exists()) {
                file = File(dir, "$baseName-$n.jsonl")
                n++
            }
            // Allocate the file now so its timestamp marks it as the newest —
            // retention (which prunes oldest FIRST) then never kills the file
            // we are about to write into.
            file.createNewFile()
            retainTraceFiles(dir)
            file
        }.getOrNull()
    }

    /** Keep at most [MAX_TRACE_FILES_PER_SESSION] files in [dir], oldest first. */
    internal fun retainTraceFiles(dir: File) {
        runCatching {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
            while (files.size > MAX_TRACE_FILES_PER_SESSION) {
                files.removeAt(0).delete()
            }
        }
    }

    companion object {
        /** T9: trace retention cap per session (oldest pruned first). */
        const val MAX_TRACE_FILES_PER_SESSION = 20

        // ── T7-A: 观察预算默认上限（advisory 观察用，不阻断任何行为）──
        // 这些数字只用于 trace 记录当前消耗进度（budget_consume/refuse 事件），
        // 不改变生产行为；T7-C 接入 enforced 模式前由 T4-B/T10 依据真实基线校准。
        internal const val T7_OBSERVE_MAX_TURNS = 200
        internal const val T7_OBSERVE_MAX_PROVIDER_ATTEMPTS = 64
        internal const val T7_OBSERVE_MAX_TOOL_CALLS = 128
        internal const val T7_OBSERVE_MAX_SHELL_COMMANDS = 128
        internal const val T7_OBSERVE_MAX_COMPACTION_CALLS = 8
        internal const val T7_OBSERVE_MAX_CONCURRENT_TOOLS = 4
        /** 观察 deadline：60 分钟单调时间（advisory，不阻断）。 */
        internal const val T7_OBSERVE_DEADLINE_MS = 60L * 60L * 1000L

        /**
         * T7-A: 把 [AgentRunPhase] 映射为 trace schema v2 的 state_transition
         * 枚举字符串（驼峰，如 "CallingModel"）。schema 枚举见
         * docs/stability/trace-schema-v2.md —— 不能用 `.name`（全大写）。
         * internal companion 纯函数（无实例依赖），供 JVM 测试直接断言。
         */
        internal fun t7PhaseSchema(phase: AgentRunPhase): String = when (phase) {
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

        /**
         * T7-A: 把 [AgentTerminal] 映射为 trace schema v2 的 terminal_state
         * 枚举（驼峰）。不能用 `.name`（全大写）。
         */
        internal fun t7TerminalSchema(terminal: AgentTerminal): String = when (terminal) {
            AgentTerminal.SUCCEEDED -> "Succeeded"
            AgentTerminal.FAILED -> "Failed"
            AgentTerminal.CANCELLED -> "Cancelled"
            AgentTerminal.INTERRUPTED -> "Interrupted"
        }

        /**
         * T7-A: 把 [AgentTerminalReason] 映射为 trace schema v2 的
         * terminal_reason 枚举（snake_case）。
         */
        internal fun t7TerminalReasonSchema(reason: AgentTerminalReason?): String? = when (reason) {
            null -> null
            AgentTerminalReason.COMPLETED -> "completed_normally"
            AgentTerminalReason.EXECUTION_FAILED -> "all_fallbacks_exhausted"
            AgentTerminalReason.USER_CANCELLED -> "user_cancelled"
            AgentTerminalReason.DEADLINE_EXCEEDED -> "deadline_reached"
            AgentTerminalReason.PROCESS_INTERRUPTED -> "process_interrupted"
            AgentTerminalReason.PERSISTENCE_FAILED -> "persistence_failed"
            // schema 无 outcome_unknown 枚举；结果未知最接近"执行未确认完成"语义
            AgentTerminalReason.OUTCOME_UNKNOWN -> "process_interrupted"
        }
    }
}
