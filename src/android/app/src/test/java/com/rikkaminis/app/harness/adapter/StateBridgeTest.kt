package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.TerminalState
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentRunState
import org.junit.Test

/**
 * [T4-B] StateBridge 纯逻辑测试：生产状态机 ↔ Harness 契约终态映射。
 */
class StateBridgeTest {

    // ── terminalOf(AgentTerminal) ─────────────────────────────────────────

    @Test
    fun `AgentTerminal to TerminalState - all four`() {
        assertEq(StateBridge.terminalOf(AgentTerminal.SUCCEEDED), TerminalState.SUCCEEDED)
        assertEq(StateBridge.terminalOf(AgentTerminal.FAILED), TerminalState.FAILED)
        assertEq(StateBridge.terminalOf(AgentTerminal.CANCELLED), TerminalState.CANCELLED)
        assertEq(StateBridge.terminalOf(AgentTerminal.INTERRUPTED), TerminalState.INTERRUPTED)
    }

    // ── terminalFromPhase ──────────────────────────────────────────────────

    @Test
    fun `terminal phase maps to TerminalState`() {
        assertEq(StateBridge.terminalFromPhase(AgentRunPhase.SUCCEEDED), TerminalState.SUCCEEDED)
        assertEq(StateBridge.terminalFromPhase(AgentRunPhase.FAILED), TerminalState.FAILED)
        assertEq(StateBridge.terminalFromPhase(AgentRunPhase.CANCELLED), TerminalState.CANCELLED)
        assertEq(StateBridge.terminalFromPhase(AgentRunPhase.INTERRUPTED), TerminalState.INTERRUPTED)
    }

    @Test
    fun `non-terminal phase maps to null`() {
        for (phase in AgentRunPhase.entries) {
            if (phase.isTerminal) continue
            assertEq(StateBridge.terminalFromPhase(phase), null, "phase=$phase")
        }
    }

    // ── terminalOf(AgentRunState) ──────────────────────────────────────────

    @Test
    fun `state terminal maps, running state maps to null`() {
        assertEq(
            StateBridge.terminalOf(AgentRunState(phase = AgentRunPhase.SUCCEEDED)),
            TerminalState.SUCCEEDED,
        )
        assertEq(
            StateBridge.terminalOf(AgentRunState(phase = AgentRunPhase.CALLING_MODEL)),
            null,
        )
    }

    // ── agentTerminalOf (reverse) ──────────────────────────────────────────

    @Test
    fun `TerminalState to AgentTerminal round-trips`() {
        for (t in TerminalState.entries) {
            assertEq(StateBridge.agentTerminalOf(t), AgentTerminal.valueOf(t.name))
        }
    }

    // ── isRecoverable ──────────────────────────────────────────────────────

    @Test
    fun `only INTERRUPTED is recoverable`() {
        assertTrue(StateBridge.isRecoverable(AgentRunPhase.INTERRUPTED), "INTERRUPTED recoverable")
        assertTrue(!StateBridge.isRecoverable(AgentRunPhase.SUCCEEDED), "SUCCEEDED not recoverable")
        assertTrue(!StateBridge.isRecoverable(AgentRunPhase.FAILED), "FAILED not recoverable")
        assertTrue(!StateBridge.isRecoverable(AgentRunPhase.CANCELLED), "CANCELLED not recoverable")
        assertTrue(!StateBridge.isRecoverable(AgentRunPhase.CALLING_MODEL), "running not recoverable")
    }

    private fun assertEq(actual: Any?, expected: Any?, msg: String = "actual=$actual expected=$expected") {
        if (actual != expected) throw AssertionError(msg)
    }

    private fun assertTrue(condition: Boolean, msg: String) {
        if (!condition) throw AssertionError(msg)
    }
}
