package com.openminis.app.ui.chat

import com.openminis.app.agent.runtime.AgentRunPhase
import com.openminis.app.agent.runtime.AgentTerminal
import com.openminis.app.agent.runtime.AgentTerminalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T7-agent-runtime-integration] T7-A trace schema 映射纯 JVM 测试。
 *
 * 验证 ChatAgentTraceObserver companion 里 T5 枚举 → trace schema v2 字符串的映射，
 * 保证写入 JSONL 的 state_transition / terminal_state / terminal_reason
 * 与 docs/stability/trace-schema-v2.md 的枚举完全一致（驼峰 / snake_case），
 * 防止 `.name`（全大写）或拼写漂移污染审计数据。
 */
class T7TraceSchemaMappingTest {

    // ─── AgentRunPhase → state_transition 枚举 ───────────────────────

    @Test
    fun `phase mapping covers every AgentRunPhase with schema camelCase`() {
        val expected = mapOf(
            AgentRunPhase.IDLE to "Idle",
            AgentRunPhase.PREPARING to "Preparing",
            AgentRunPhase.CALLING_MODEL to "CallingModel",
            AgentRunPhase.EXECUTING_TOOLS to "ExecutingTools",
            AgentRunPhase.RETRYING to "Retrying",
            AgentRunPhase.FALLING_BACK to "FallingBack",
            AgentRunPhase.COMPACTING to "Compacting",
            AgentRunPhase.FINALIZING to "Finalizing",
            AgentRunPhase.SUCCEEDED to "Succeeded",
            AgentRunPhase.FAILED to "Failed",
            AgentRunPhase.CANCELLED to "Cancelled",
            AgentRunPhase.INTERRUPTED to "Interrupted",
        )
        assertEquals(AgentRunPhase.entries.size, expected.size)
        AgentRunPhase.entries.forEach { phase ->
            assertEquals("phase ${phase.name} must map to schema enum", expected[phase], ChatAgentTraceObserver.t7PhaseSchema(phase))
        }
    }

    @Test
    fun `phase mapping never emits ALL-CAPS enum name`() {
        AgentRunPhase.entries.forEach { phase ->
            val mapped = ChatAgentTraceObserver.t7PhaseSchema(phase)
            // schema 枚举是 PascalCase（如 "CallingModel"），绝不能是 enum 的 ALL-CAPS name
            assertTrue(
                "phase ${phase.name} must not map to ALL-CAPS enum name, was '$mapped'",
                mapped != phase.name,
            )
        }
    }

    // ─── AgentTerminal → terminal_state 枚举 ─────────────────────────

    @Test
    fun `terminal mapping covers every AgentTerminal with schema camelCase`() {
        val expected = mapOf(
            AgentTerminal.SUCCEEDED to "Succeeded",
            AgentTerminal.FAILED to "Failed",
            AgentTerminal.CANCELLED to "Cancelled",
            AgentTerminal.INTERRUPTED to "Interrupted",
        )
        assertEquals(AgentTerminal.entries.size, expected.size)
        AgentTerminal.entries.forEach { terminal ->
            assertEquals(
                "terminal ${terminal.name} must map to schema enum",
                expected[terminal],
                ChatAgentTraceObserver.t7TerminalSchema(terminal),
            )
        }
    }

    // ─── AgentTerminalReason → terminal_reason 枚举 ──────────────────

    @Test
    fun `terminal reason mapping covers every AgentTerminalReason with schema snake_case`() {
        val expected = mapOf(
            AgentTerminalReason.COMPLETED to "completed_normally",
            AgentTerminalReason.EXECUTION_FAILED to "all_fallbacks_exhausted",
            AgentTerminalReason.USER_CANCELLED to "user_cancelled",
            AgentTerminalReason.DEADLINE_EXCEEDED to "deadline_reached",
            AgentTerminalReason.PROCESS_INTERRUPTED to "process_interrupted",
            AgentTerminalReason.PERSISTENCE_FAILED to "persistence_failed",
            // OUTCOME_UNKNOWN 无 schema 对应，映射到 process_interrupted（最接近语义）
            AgentTerminalReason.OUTCOME_UNKNOWN to "process_interrupted",
        )
        assertEquals(AgentTerminalReason.entries.size, expected.size)
        AgentTerminalReason.entries.forEach { reason ->
            assertEquals(
                "reason ${reason.name} must map to schema enum",
                expected[reason],
                ChatAgentTraceObserver.t7TerminalReasonSchema(reason),
            )
        }
    }

    @Test
    fun `terminal reason null maps to null`() {
        assertNull(ChatAgentTraceObserver.t7TerminalReasonSchema(null))
    }

    @Test
    fun `terminal reason mapping never emits ALL-CAPS enum name`() {
        AgentTerminalReason.entries.forEach { reason ->
            val mapped = ChatAgentTraceObserver.t7TerminalReasonSchema(reason)
            if (mapped != null) {
                // schema terminal_reason 是 snake_case 全小写
                assertTrue(
                    "reason ${reason.name} must not map to ALL-CAPS enum name, was '$mapped'",
                    mapped != reason.name && mapped.none { it.isUpperCase() },
                )
            }
        }
    }
}
