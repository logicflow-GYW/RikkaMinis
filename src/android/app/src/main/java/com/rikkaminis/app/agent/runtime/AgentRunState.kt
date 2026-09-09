package com.rikkaminis.app.agent.runtime

/**
 * [T5-agent-run-state] Agent Run 终态状态机 —— 纯逻辑核心（无 Android 依赖）。
 *
 * 目标：把目前散落在多个布尔量、消息字段和工具状态中的"运行中/失败/取消/恢复"
 * 语义提炼成可测试的单一事实源。本文件只定义模型，转换逻辑见 [AgentRunReducer]。
 *
 * 背景契约：`docs/stability/runtime-contract.md` 第 4 节（四终态契约）。
 * 一次用户请求最终只能归入四种终态之一：SUCCEEDED / FAILED / CANCELLED / INTERRUPTED。
 *
 * INTERRUPTED ≠ FAILED：它表示"系统无法证明执行没有发生副作用"或"结果没有完整落库"。
 *
 * 本文件 + [AgentRunReducer] 为纯 JVM 逻辑，T7 以 adapter 接入 Agent 主链；
 * 不接 ViewModel、不执行网络/数据库/shell。
 */

/**
 * Agent Run 的阶段（phase）。前 8 个为运行中阶段，后 4 个为终态。
 *
 * 状态流（合法路径，详见 [AgentRunReducer]）：
 * ```
 * Idle --RunStarted--> Preparing --ProviderAttemptStarted--> CallingModel
 *   --ProviderAttemptFinished(Success)--> ExecutingTools --WorkCompleted--> Finalizing
 *   --ProviderAttemptFinished(TransientFailure)--> Retrying --RetryRequested--> CallingModel
 *   --ProviderAttemptFinished(FallbackFailure)--> FallingBack --FallbackSelected--> CallingModel
 *   --ProviderAttemptFinished(FatalFailure)--> Finalizing
 * ExecutingTools <--ToolStarted/ToolFinished--> ExecutingTools
 * CallingModel/ExecutingTools/... --CompactionStarted--> Compacting --CompactionFinished--> CallingModel
 * 任意运行中 --UserCancelled|DeadlineReached|ProcessInterrupted|PersistenceFailed--> Finalizing
 * Finalizing --RunFinalized--> Succeeded | Failed | Cancelled | Interrupted
 * ```
 */
enum class AgentRunPhase {
    /** Run 尚未开始。 */
    IDLE,

    /** RunStarted 之后、首次 provider 调用之前（构建请求、注入上下文等）。 */
    PREPARING,

    /** provider 调用进行中（网络往返、流式接收）。 */
    CALLING_MODEL,

    /** 工具执行中（一个或多个工具）。 */
    EXECUTING_TOOLS,

    /** 上一次次尝试失败，决定重试但尚未发起新 attempt。 */
    RETRYING,

    /** 上一次次尝试失败，决定切换 fallback 但尚未发起新 attempt。 */
    FALLING_BACK,

    /** 上下文压缩进行中。 */
    COMPACTING,

    /** 收尾中：资源释放、必需持久化、trace 终结。之后必须经 RunFinalized 进入终态。 */
    FINALIZING,

    // ── 终态（terminal）──────────────────────────────────────────────

    /** 回答、工具结果和必要的持久化全部完成。 */
    SUCCEEDED,

    /** 执行失败，错误已暴露，临时状态已清理。 */
    FAILED,

    /** 用户主动取消，后台请求、工具和资源都已停止或进入明确的取消状态。 */
    CANCELLED,

    /** 进程死亡、系统回收、达到总 deadline 或执行结果未知，不能伪装成成功。 */
    INTERRUPTED,
    ;

    /** 是否为四种终态之一。 */
    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED

    companion object {
        /** 把 [AgentTerminal] 映射为对应的终态 phase。 */
        fun ofTerminal(terminal: AgentTerminal): AgentRunPhase = when (terminal) {
            AgentTerminal.SUCCEEDED -> SUCCEEDED
            AgentTerminal.FAILED -> FAILED
            AgentTerminal.CANCELLED -> CANCELLED
            AgentTerminal.INTERRUPTED -> INTERRUPTED
        }
    }
}

/**
 * 四种终态（契约 4.1 节冻结）。与 [AgentRunPhase] 的四个终态一一对应，
 * 供 RunFinalized 事件和 trace 直接引用，避免对 phase 做自由字符串比较。
 */
enum class AgentTerminal {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * 终态原因，供 trace（T6）与恢复决策（T8）使用。可扩展，不影响状态机合法性。
 */
enum class AgentTerminalReason {
    /** 正常完成。 */
    COMPLETED,

    /** 执行失败（provider 全灭、致命错误等）。 */
    EXECUTION_FAILED,

    /** 用户主动取消。 */
    USER_CANCELLED,

    /** 达到总 deadline。 */
    DEADLINE_EXCEEDED,

    /** 进程死亡 / 系统回收 / 执行结果未知。 */
    PROCESS_INTERRUPTED,

    /** 必需持久化失败。 */
    PERSISTENCE_FAILED,

    /** 存在 OutcomeUnknown 的工具结果，不能证明副作用安全。 */
    OUTCOME_UNKNOWN,
}

/** provider 单次尝试的结局，决定 [AgentRunPhase] 的走向。 */
enum class ProviderAttemptOutcome {
    /** 调用成功（输出或工具指令已就绪）。 */
    SUCCESS,

    /** 瞬态失败，可在预算内重试。 */
    TRANSIENT_FAILURE,

    /** 失败且需要切换到 fallback 成员。 */
    FALLBACK_FAILURE,

    /** 致命失败（预算耗尽 / 全 fallback 失败），进入收尾。 */
    FATAL_FAILURE,
}

/**
 * Agent Run 的不可变快照。每次 [AgentRunReducer.reduce] 返回新实例；
 * 计数器只增不减，作为终态不变量（budget/trace/恢复）的旁证。
 *
 * 所有字段均为"观察值"，供 adapter/trace 读取；状态合法性由 [AgentRunReducer] 判定。
 */
data class AgentRunState(
    /** 当前阶段。 */
    val phase: AgentRunPhase = AgentRunPhase.IDLE,
    /** RunStarted 携带的 run 标识（T1 起 runId 与 sessionId 分离）。 */
    val runId: String? = null,
    /** 已开始的 provider attempt 次数（只增）。 */
    val providerAttemptCount: Int = 0,
    /** 已开始的工具调用次数（只增）。 */
    val toolStartedCount: Int = 0,
    /** 已结束的工具调用次数（只增）。 */
    val toolFinishedCount: Int = 0,
    /** 已开始的 compaction 次数（只增）。 */
    val compactCount: Int = 0,
    /**
     * 是否存在 outcome unknown 的工具结果。为 true 时不得进入 SUCCEEDED
     * （契约不变量 7：自动恢复不会重复执行无法证明幂等的副作用操作）。
     */
    val hasOutcomeUnknownTool: Boolean = false,
    /**
     * 是否发生过必需持久化失败。为 true 时不得进入 SUCCEEDED
     * （契约 4.1：Succeeded = 回答、工具结果和必要的持久化全部完成）。
     */
    val persistenceFailed: Boolean = false,
    /** 进入终态时记录的原因；运行中为 null。 */
    val terminalReason: AgentTerminalReason? = null,
) {
    /** 是否为终态。 */
    val isTerminal: Boolean get() = phase.isTerminal

    /** 初始状态（IDLE）。 */
    companion object {
        fun initial(): AgentRunState = AgentRunState()
    }
}
