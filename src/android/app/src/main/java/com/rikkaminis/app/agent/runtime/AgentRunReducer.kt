package com.rikkaminis.app.agent.runtime

/**
 * [T5-agent-run-state] Agent Run 状态机 reducer —— 纯函数，无 Android 依赖。
 *
 * 设计要点（对应蓝图 T5 章节与 `docs/stability/runtime-contract.md` 第 4 节）：
 *
 * 1. **单一事实源**：一次 run 的推进全部经由 [reduce]，调用方（T7 adapter）
 *    只发事件，不直接改状态。
 * 2. **非法转换可发现**：[reduce] 对非法事件返回 [AgentRunTransition.Rejected]，
 *    状态不变并携带 [AgentRunRejection]（原因枚举 + 描述），**不静默修正**。
 * 3. **终态唯一**：终态收到运行类事件一律拒绝（TERMINAL_STATE_IMMUTABLE）；
 *    重复 [AgentRunEvent.RunFinalized] 且目标一致时幂等 no-op（changed=false），
 *    目标不一致时拒绝（TERMINAL_STATE_CONFLICT）。
 * 4. **统一收尾**：除 [AgentRunPhase.PREPARING]（尚无进行中工作）外，
 *    任何运行中状态必须先进入 FINALIZING（经 UserCancelled / DeadlineReached /
 *    ProcessInterrupted / PersistenceFailed / ProviderAttemptFinished(FATAL) /
 *    WorkCompleted）才能收到 [AgentRunEvent.RunFinalized]。
 * 5. **竞态容忍**：FINALIZING 中到达的过期运行类事件（取消后 provider 结果、
 *    进程死亡后 tool 结果等）被接受但 no-op，不污染终态判定。
 * 6. **Succeeded 前置条件**：存在 outcome unknown 工具或发生过持久化失败时，
 *    RunFinalized(SUCCEEDED) 被拒绝。
 *
 * 本 reducer 不执行网络、数据库或 shell；不依赖任何 Android / Coroutine 类型。
 */

/** Agent Run 事件（蓝图推荐事件集 + WorkCompleted 收尾事件）。 */
sealed class AgentRunEvent {

    /** Run 开始（IDLE → PREPARING）。仅允许一次。 */
    data class RunStarted(val runId: String) : AgentRunEvent()

    /** 开始一次 provider attempt（计数 +1，进入 CALLING_MODEL）。 */
    data object ProviderAttemptStarted : AgentRunEvent()

    /** provider 单次尝试结束。结局决定走向（见 [ProviderAttemptOutcome]）。 */
    data class ProviderAttemptFinished(val outcome: ProviderAttemptOutcome) : AgentRunEvent()

    /** 决定重试（RETRYING → CALLING_MODEL）。reason 供 trace。 */
    data class RetryRequested(val reason: String? = null) : AgentRunEvent()

    /** 决定切换 fallback（FALLING_BACK → CALLING_MODEL）。 */
    data class FallbackSelected(val fallbackMemberIndex: Int? = null) : AgentRunEvent()

    /**
     * TF-H: 全部 fallback 成员已耗尽，无法再切换。
     * FALLING_BACK → FINALIZING；随后应由 [RunFinalized] 落终态。
     */
    data object FallbackExhausted : AgentRunEvent()

    /** 开始一次工具调用（仅 EXECUTING_TOOLS 内，计数 +1）。 */
    data class ToolStarted(val toolName: String) : AgentRunEvent()

    /**
     * 工具结束。`resultKnown=false` 表示"副作用可能已发生但结果未知"
     * （shell 死亡 / 超时 / 结果截断），该 run 此后不得进入 SUCCEEDED。
     */
    data class ToolFinished(val toolName: String, val resultKnown: Boolean) : AgentRunEvent()

    /** 开始上下文压缩（计数 +1，进入 COMPACTING）。 */
    data class CompactionStarted(val reason: String? = null) : AgentRunEvent()

    /** 压缩完成（COMPACTING → CALLING_MODEL）。 */
    data class CompactionFinished(val markerLength: Int? = null) : AgentRunEvent()

    /**
     * 本轮工作全部完成（工具序列结束且无待续内容）。
     * EXECUTING_TOOLS → FINALIZING，之后由 [RunFinalized] 落终态。
     */
    data object WorkCompleted : AgentRunEvent()

    /** 用户主动取消（任意运行中状态 → FINALIZING）。 */
    data class UserCancelled(val reason: String? = null) : AgentRunEvent()

    /** 达到总 deadline（任意运行中状态 → FINALIZING）。 */
    data class DeadlineReached(val atMonotonicMs: Long? = null) : AgentRunEvent()

    /** 进程死亡 / 系统回收 / 执行结果未知（任意运行中状态 → FINALIZING）。 */
    data class ProcessInterrupted(val reason: String? = null) : AgentRunEvent()

    /** 必需持久化失败（任意运行中状态 → FINALIZING，并禁止 SUCCEEDED）。 */
    data class PersistenceFailed(val what: String? = null) : AgentRunEvent()

    /**
     * 收尾落终态。仅从 FINALIZING（或 PREPARING 特例）接受；
     * 终态上重复且目标一致时幂等 no-op，目标不一致时拒绝。
     */
    data class RunFinalized(
        val terminal: AgentTerminal,
        val reason: AgentTerminalReason? = null,
    ) : AgentRunEvent()
}

/** reducer 对单个事件的判定结果。 */
sealed class AgentRunTransition {
    /** 事件被接受。`changed=false` 表示幂等 no-op（状态未变）。 */
    data class Accepted(val state: AgentRunState, val changed: Boolean) : AgentRunTransition()

    /** 事件被拒绝：状态不变，原因见 [AgentRunRejection]。 */
    data class Rejected(val state: AgentRunState, val rejection: AgentRunRejection) : AgentRunTransition()
}

/** 非法转换的原因。 */
enum class AgentRunRejectionReason {
    /** IDLE 收到非 RunStarted 事件。 */
    RUN_NOT_STARTED,

    /** 非 IDLE 收到 RunStarted（重复 start）。 */
    RUN_ALREADY_STARTED,

    /** 终态收到运行类事件（终态不可转回 running）。 */
    TERMINAL_STATE_IMMUTABLE,

    /** 终态收到目标不一致的 RunFinalized（终态唯一性）。 */
    TERMINAL_STATE_CONFLICT,

    /** RunFinalized 来自不允许的阶段（非 FINALIZING/PREPARING）。 */
    FINALIZE_NOT_IN_FINALIZING,

    /** 存在 outcome unknown 工具时不允许 SUCCEEDED。 */
    SUCCEED_WITH_UNKNOWN_TOOL,

    /** 发生过持久化失败时不允许 SUCCEEDED。 */
    SUCCEED_WITH_PERSISTENCE_FAILURE,

    /** 事件与当前阶段不匹配（其余非法转换）。 */
    INVALID_PHASE_FOR_EVENT,
}

/** 一次拒绝的完整描述（事件 + 原因 + 人类可读说明）。 */
data class AgentRunRejection(
    val event: AgentRunEvent,
    val reason: AgentRunRejectionReason,
    val message: String,
)

/** [AgentRunReducer.reduceAll] 的结果。 */
data class AgentRunBatchResult(
    val initialState: AgentRunState,
    val finalState: AgentRunState,
    /** 逐个应用得到的判定序列（在首个拒绝处截断）。 */
    val transitions: List<AgentRunTransition>,
    /** 首个被拒绝事件的索引；全部接受时为 null。 */
    val firstRejectedIndex: Int?,
    /** 是否到达终态。 */
    val reachedTerminal: Boolean,
) {
    /** 实际应用的事件数。 */
    val appliedCount: Int get() = transitions.size
}

/**
 * 状态机 reducer。纯函数：相同的 (state, event) 恒返回相同的判定结果。
 */
object AgentRunReducer {

    /** ProviderAttemptStarted 的合法来源阶段。 */
    private val PROVIDER_START_SOURCES = setOf(
        AgentRunPhase.PREPARING,
        AgentRunPhase.CALLING_MODEL,
        AgentRunPhase.EXECUTING_TOOLS,
        AgentRunPhase.RETRYING,
        AgentRunPhase.FALLING_BACK,
        AgentRunPhase.COMPACTING,
    )

    /** CompactionStarted 的合法来源阶段。 */
    private val COMPACT_START_SOURCES = setOf(
        AgentRunPhase.PREPARING,
        AgentRunPhase.CALLING_MODEL,
        AgentRunPhase.EXECUTING_TOOLS,
        AgentRunPhase.RETRYING,
        AgentRunPhase.FALLING_BACK,
    )

    /**
     * 处理单个事件。返回 [AgentRunTransition.Accepted]（可能为幂等 no-op）
     * 或 [AgentRunTransition.Rejected]（非法转换，状态不变）。
     */
    fun reduce(state: AgentRunState, event: AgentRunEvent): AgentRunTransition {
        val phase = state.phase

        // ── 终态硬边界 ────────────────────────────────────────────────
        if (phase.isTerminal) {
            return when (event) {
                is AgentRunEvent.RunFinalized ->
                    if (event.terminal == phase.toTerminalOrNull()) {
                        // 幂等 finalize：终态保持，不重复产生终态
                        noChange(state)
                    } else {
                        rejected(
                            state, event,
                            AgentRunRejectionReason.TERMINAL_STATE_CONFLICT,
                            "terminal=$phase already reached; RunFinalized(${event.terminal}) would violate terminal uniqueness",
                        )
                    }

                else -> rejected(
                    state, event,
                    AgentRunRejectionReason.TERMINAL_STATE_IMMUTABLE,
                    "terminal state $phase cannot accept ${event::class.simpleName}",
                )
            }
        }

        // ── IDLE 边界 ─────────────────────────────────────────────────
        if (phase == AgentRunPhase.IDLE) {
            return when (event) {
                is AgentRunEvent.RunStarted ->
                    accepted(state.copy(phase = AgentRunPhase.PREPARING, runId = event.runId))

                else -> rejected(
                    state, event,
                    AgentRunRejectionReason.RUN_NOT_STARTED,
                    "run not started; ${event::class.simpleName} requires RunStarted first",
                )
            }
        }

        // ── FINALIZING：收尾中，容忍过期运行类事件（no-op），
        //    只接受 RunFinalized 落终态或重复的终止信号 ────────────────
        if (phase == AgentRunPhase.FINALIZING) {
            return when (event) {
                is AgentRunEvent.RunFinalized -> finalizeFrom(state, event)

                is AgentRunEvent.PersistenceFailed ->
                    if (state.persistenceFailed) noChange(state)
                    else accepted(state.copy(persistenceFailed = true))

                is AgentRunEvent.UserCancelled,
                is AgentRunEvent.DeadlineReached,
                is AgentRunEvent.ProcessInterrupted -> noChange(state) // 已在收尾，重复终止信号 no-op

                is AgentRunEvent.RunStarted -> rejected(
                    state, event,
                    AgentRunRejectionReason.RUN_ALREADY_STARTED,
                    "run already started (finalizing)",
                )

                else -> noChange(state) // 过期运行类事件：忽略，不污染终态
            }
        }

        // ── 常规运行中阶段 ────────────────────────────────────────────
        return when (event) {
            is AgentRunEvent.RunStarted -> rejected(
                state, event,
                AgentRunRejectionReason.RUN_ALREADY_STARTED,
                "run already started (phase=$phase)",
            )

            is AgentRunEvent.ProviderAttemptStarted ->
                if (phase in PROVIDER_START_SOURCES) {
                    accepted(
                        state.copy(
                            phase = AgentRunPhase.CALLING_MODEL,
                            providerAttemptCount = state.providerAttemptCount + 1,
                        )
                    )
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.CALLING_MODEL)
                }

            is AgentRunEvent.ProviderAttemptFinished -> {
                if (phase != AgentRunPhase.CALLING_MODEL) {
                    invalidPhase(state, event, phase, AgentRunPhase.CALLING_MODEL)
                } else {
                    when (event.outcome) {
                        ProviderAttemptOutcome.SUCCESS ->
                            accepted(state.copy(phase = AgentRunPhase.EXECUTING_TOOLS))

                        ProviderAttemptOutcome.TRANSIENT_FAILURE ->
                            accepted(state.copy(phase = AgentRunPhase.RETRYING))

                        ProviderAttemptOutcome.FALLBACK_FAILURE ->
                            accepted(state.copy(phase = AgentRunPhase.FALLING_BACK))

                        ProviderAttemptOutcome.FATAL_FAILURE ->
                            accepted(state.copy(phase = AgentRunPhase.FINALIZING))
                    }
                }
            }

            is AgentRunEvent.RetryRequested ->
                if (phase == AgentRunPhase.RETRYING) {
                    accepted(state.copy(phase = AgentRunPhase.CALLING_MODEL))
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.RETRYING)
                }

            is AgentRunEvent.FallbackSelected ->
                if (phase == AgentRunPhase.FALLING_BACK) {
                    accepted(state.copy(phase = AgentRunPhase.CALLING_MODEL))
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.FALLING_BACK)
                }

            is AgentRunEvent.FallbackExhausted ->
                if (phase == AgentRunPhase.FALLING_BACK) {
                    accepted(state.copy(phase = AgentRunPhase.FINALIZING))
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.FALLING_BACK)
                }

            is AgentRunEvent.ToolStarted ->
                if (phase == AgentRunPhase.EXECUTING_TOOLS) {
                    accepted(
                        state.copy(
                            phase = AgentRunPhase.EXECUTING_TOOLS,
                            toolStartedCount = state.toolStartedCount + 1,
                        )
                    )
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.EXECUTING_TOOLS)
                }

            is AgentRunEvent.ToolFinished ->
                if (phase == AgentRunPhase.EXECUTING_TOOLS) {
                    accepted(
                        state.copy(
                            phase = AgentRunPhase.EXECUTING_TOOLS,
                            toolFinishedCount = state.toolFinishedCount + 1,
                            hasOutcomeUnknownTool =
                                state.hasOutcomeUnknownTool || !event.resultKnown,
                        )
                    )
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.EXECUTING_TOOLS)
                }

            is AgentRunEvent.CompactionStarted ->
                if (phase in COMPACT_START_SOURCES) {
                    accepted(
                        state.copy(
                            phase = AgentRunPhase.COMPACTING,
                            compactCount = state.compactCount + 1,
                        )
                    )
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.COMPACTING)
                }

            is AgentRunEvent.CompactionFinished ->
                if (phase == AgentRunPhase.COMPACTING) {
                    accepted(state.copy(phase = AgentRunPhase.CALLING_MODEL))
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.COMPACTING)
                }

            is AgentRunEvent.WorkCompleted ->
                if (phase == AgentRunPhase.EXECUTING_TOOLS) {
                    accepted(state.copy(phase = AgentRunPhase.FINALIZING))
                } else {
                    invalidPhase(state, event, phase, AgentRunPhase.EXECUTING_TOOLS)
                }

            is AgentRunEvent.UserCancelled ->
                accepted(state.copy(phase = AgentRunPhase.FINALIZING))

            is AgentRunEvent.DeadlineReached ->
                accepted(state.copy(phase = AgentRunPhase.FINALIZING))

            is AgentRunEvent.ProcessInterrupted ->
                accepted(state.copy(phase = AgentRunPhase.FINALIZING))

            is AgentRunEvent.PersistenceFailed ->
                accepted(
                    state.copy(
                        phase = AgentRunPhase.FINALIZING,
                        persistenceFailed = true,
                    )
                )

            is AgentRunEvent.RunFinalized ->
                // PREPARING 特例：尚无进行中的 attempt/tool，直接落终态安全。
                if (phase == AgentRunPhase.PREPARING) {
                    finalizeFrom(state, event)
                } else {
                    rejected(
                        state, event,
                        AgentRunRejectionReason.FINALIZE_NOT_IN_FINALIZING,
                        "RunFinalized requires FINALIZING (current=$phase); " +
                            "route through WorkCompleted / termination event first",
                    )
                }
        }
    }

    /**
     * 顺序应用事件，遇到首个拒绝即停止（fail-fast）。
     * 纯函数：相同输入恒产生相同结果，可安全重放。
     */
    fun reduceAll(
        events: List<AgentRunEvent>,
        initial: AgentRunState = AgentRunState.initial(),
    ): AgentRunBatchResult {
        var state = initial
        val transitions = mutableListOf<AgentRunTransition>()
        var rejectedIndex: Int? = null
        for ((index, event) in events.withIndex()) {
            val transition = reduce(state, event)
            transitions.add(transition)
            if (transition is AgentRunTransition.Rejected) {
                rejectedIndex = index
                break
            }
            state = (transition as AgentRunTransition.Accepted).state
        }
        return AgentRunBatchResult(
            initialState = initial,
            finalState = state,
            transitions = transitions,
            firstRejectedIndex = rejectedIndex,
            reachedTerminal = state.isTerminal,
        )
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    private fun accepted(state: AgentRunState): AgentRunTransition.Accepted =
        AgentRunTransition.Accepted(state, changed = true)

    private fun noChange(state: AgentRunState): AgentRunTransition.Accepted =
        AgentRunTransition.Accepted(state, changed = false)

    private fun rejected(
        state: AgentRunState,
        event: AgentRunEvent,
        reason: AgentRunRejectionReason,
        message: String,
    ): AgentRunTransition.Rejected =
        AgentRunTransition.Rejected(state, AgentRunRejection(event, reason, message))

    private fun invalidPhase(
        state: AgentRunState,
        event: AgentRunEvent,
        actual: AgentRunPhase,
        expected: AgentRunPhase,
    ): AgentRunTransition.Rejected =
        rejected(
            state, event,
            AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT,
            "${event::class.simpleName} invalid in phase=$actual (expected $expected)",
        )

    private fun finalizeFrom(state: AgentRunState, event: AgentRunEvent.RunFinalized): AgentRunTransition {
        val terminal = event.terminal
        if (terminal == AgentTerminal.SUCCEEDED) {
            if (state.hasOutcomeUnknownTool) {
                return rejected(
                    state, event,
                    AgentRunRejectionReason.SUCCEED_WITH_UNKNOWN_TOOL,
                    "cannot SUCCEED while an outcome-unknown tool result exists",
                )
            }
            if (state.persistenceFailed) {
                return rejected(
                    state, event,
                    AgentRunRejectionReason.SUCCEED_WITH_PERSISTENCE_FAILURE,
                    "cannot SUCCEED after a required persistence failure",
                )
            }
        }
        val reason = event.reason ?: defaultReasonFor(terminal)
        return accepted(
            state.copy(
                phase = AgentRunPhase.ofTerminal(terminal),
                terminalReason = reason,
            )
        )
    }

    private fun defaultReasonFor(terminal: AgentTerminal): AgentTerminalReason = when (terminal) {
        AgentTerminal.SUCCEEDED -> AgentTerminalReason.COMPLETED
        AgentTerminal.FAILED -> AgentTerminalReason.EXECUTION_FAILED
        AgentTerminal.CANCELLED -> AgentTerminalReason.USER_CANCELLED
        AgentTerminal.INTERRUPTED -> AgentTerminalReason.PROCESS_INTERRUPTED
    }
}

/** phase → 对应的终态枚举（仅对终态 phase 有意义，否则 null）。 */
fun AgentRunPhase.toTerminalOrNull(): AgentTerminal? = when (this) {
    AgentRunPhase.SUCCEEDED -> AgentTerminal.SUCCEEDED
    AgentRunPhase.FAILED -> AgentTerminal.FAILED
    AgentRunPhase.CANCELLED -> AgentTerminal.CANCELLED
    AgentRunPhase.INTERRUPTED -> AgentTerminal.INTERRUPTED
    else -> null
}
