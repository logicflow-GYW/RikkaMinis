package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.TerminalState
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentTerminalReason

/**
 * [T4-B] 终态映射 —— 生产状态机（T5）↔ Harness 契约（T4-A）。
 *
 * 纯函数，无 Android 依赖，可独立单测。
 *
 * 这是 adapter 的"语义对齐层"：T7 主链用 `AgentRunPhase`/`AgentTerminal`
 * （生产类型），T4-A 断言用 `TerminalState`（test 契约）。两边一一对应，
 * 映射失败必须显式失败（不静默修正），防止 adapter 把生产终态翻译错。
 */
object StateBridge {

    /** 生产终态 → Harness 契约终态。一一对应。 */
    fun terminalOf(agentTerminal: AgentTerminal): TerminalState = when (agentTerminal) {
        AgentTerminal.SUCCEEDED -> TerminalState.SUCCEEDED
        AgentTerminal.FAILED -> TerminalState.FAILED
        AgentTerminal.CANCELLED -> TerminalState.CANCELLED
        AgentTerminal.INTERRUPTED -> TerminalState.INTERRUPTED
    }

    /** 生产 phase → Harness 契约终态。仅接受终态 phase，运行中 phase 返回 null。 */
    fun terminalFromPhase(phase: AgentRunPhase): TerminalState? = when (phase) {
        AgentRunPhase.SUCCEEDED -> TerminalState.SUCCEEDED
        AgentRunPhase.FAILED -> TerminalState.FAILED
        AgentRunPhase.CANCELLED -> TerminalState.CANCELLED
        AgentRunPhase.INTERRUPTED -> TerminalState.INTERRUPTED
        else -> null // 运行中 phase（IDLE/PREPARING/CALLING_MODEL/...）不是终态
    }

    /** 从 [AgentRunState] 取终态；非终态返回 null。 */
    fun terminalOf(state: AgentRunState): TerminalState? = terminalFromPhase(state.phase)

    /** Harness 契约终态 → 生产 [AgentTerminal]（供 RunFinalized 事件使用）。 */
    fun agentTerminalOf(terminal: TerminalState): AgentTerminal = when (terminal) {
        TerminalState.SUCCEEDED -> AgentTerminal.SUCCEEDED
        TerminalState.FAILED -> AgentTerminal.FAILED
        TerminalState.CANCELLED -> AgentTerminal.CANCELLED
        TerminalState.INTERRUPTED -> AgentTerminal.INTERRUPTED
    }

    /**
     * 生产终态原因 → 可恢复性判定。
     *
     * 契约（蓝图 §4.1/§T8）：`INTERRUPTED` 可安全恢复（进程死亡/断流/deadline/
     * outcome unknown），`FAILED`/`CANCELLED` 不可自动恢复，`SUCCEEDED` 无需恢复。
     */
    fun isRecoverable(phase: AgentRunPhase): Boolean = when (phase) {
        AgentRunPhase.INTERRUPTED -> true
        AgentRunPhase.SUCCEEDED,
        AgentRunPhase.FAILED,
        AgentRunPhase.CANCELLED -> false
        else -> false
    }

    /**
     * 生产终态原因 → 人类可读字符串（供 [AgentRunState.terminalReason] 展示/trace）。
     * Assumed(await T7)：T7 是否使用 [AgentTerminalReason] 或自定义 reason 以冻结为准。
     */
    fun reasonText(reason: AgentTerminalReason?): String? = reason?.name
}
