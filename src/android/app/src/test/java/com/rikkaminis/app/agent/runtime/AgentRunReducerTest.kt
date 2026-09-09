package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T5-agent-run-state] AgentRunReducer 纯 JVM 测试。
 *
 * 覆盖蓝图 T5 测试矩阵：
 * - 每条合法状态转换（happy path 全链 + retry/fallback/compact/cancel/deadline 分支）；
 * - 每条非法转换（Rejected + 原因枚举，不静默修正）；
 * - 终态幂等 finalize；
 * - 竞态：cancel 与 provider success 同时到达、tool result 与 process death 同时到达、
 *   deadline 与 retry 同时到达；
 * - 重放事件序列结果稳定；
 * - 终态唯一性、计数器只增、stale 事件不污染终态。
 */
class AgentRunReducerTest {

    // ─── 辅助 ─────────────────────────────────────────────────────────

    private fun reduceBatch(vararg events: AgentRunEvent): AgentRunBatchResult =
        AgentRunReducer.reduceAll(events.toList())

    private fun AgentRunTransition.acceptedState(): AgentRunState =
        (this as AgentRunTransition.Accepted).state

    private fun AgentRunTransition.rejection(): AgentRunRejection =
        (this as AgentRunTransition.Rejected).rejection

    private fun AgentRunState.accept(event: AgentRunEvent): AgentRunState =
        AgentRunReducer.reduce(this, event).acceptedState()

    // ─── 合法转换：全链路径 ────────────────────────────────────────────

    @Test
    fun `happy path start model tools succeed`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r1"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("file_read"),
            AgentRunEvent.ToolFinished("file_read", resultKnown = true),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertTrue(batch.reachedTerminal)
        val s = batch.finalState
        assertEquals(AgentRunPhase.SUCCEEDED, s.phase)
        assertEquals(AgentTerminalReason.COMPLETED, s.terminalReason)
        assertEquals("r1", s.runId)
        assertEquals(1, s.providerAttemptCount)
        assertEquals(1, s.toolStartedCount)
        assertEquals(1, s.toolFinishedCount)
        assertFalse(s.hasOutcomeUnknownTool)
        assertFalse(s.persistenceFailed)
    }

    @Test
    fun `happy path without tools`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r2"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
        assertEquals(0, batch.finalState.toolStartedCount)
    }

    @Test
    fun `retry chain retries and succeeds`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r3"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.RetryRequested("stream reset"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
        assertEquals(2, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `fallback chain selects fallback then succeeds`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r4"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FALLBACK_FAILURE),
            AgentRunEvent.FallbackSelected(1),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
        assertEquals(2, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `fatal provider failure leads to failed`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r5"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FATAL_FAILURE),
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.FAILED, batch.finalState.phase)
        assertEquals(AgentTerminalReason.EXECUTION_FAILED, batch.finalState.terminalReason)
    }

    @Test
    fun `all providers fail after retry and fallback rounds`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r6"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.RetryRequested("429"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FALLBACK_FAILURE),
            AgentRunEvent.FallbackSelected(3),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FATAL_FAILURE),
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.FAILED, batch.finalState.phase)
        assertEquals(3, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `compaction mid run then continues`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r7"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("file_read"),
            AgentRunEvent.ToolFinished("file_read", resultKnown = true),
            AgentRunEvent.CompactionStarted("context too long"),
            AgentRunEvent.CompactionFinished(42),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        val s = batch.finalState
        assertEquals(AgentRunPhase.SUCCEEDED, s.phase)
        assertEquals(1, s.compactCount)
        assertEquals(2, s.providerAttemptCount)
    }

    @Test
    fun `user cancel during model call goes cancelled`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r8"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.UserCancelled("stop"),
            AgentRunEvent.RunFinalized(AgentTerminal.CANCELLED, AgentTerminalReason.USER_CANCELLED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.CANCELLED, batch.finalState.phase)
        assertEquals(AgentTerminalReason.USER_CANCELLED, batch.finalState.terminalReason)
        assertEquals(1, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `deadline reached goes interrupted`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r9"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.DeadlineReached(123456L),
            AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.INTERRUPTED, batch.finalState.phase)
        assertEquals(AgentTerminalReason.DEADLINE_EXCEEDED, batch.finalState.terminalReason)
    }

    @Test
    fun `process interrupted goes interrupted`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r10"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProcessInterrupted("process death"),
            AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.INTERRUPTED, batch.finalState.phase)
    }

    @Test
    fun `preparing can finalize directly without any attempt`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r11"),
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.FAILED, batch.finalState.phase)
        assertEquals(0, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `persistence failure leads to failed and forbids succeed`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r12"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.PersistenceFailed("message insert"),
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.PERSISTENCE_FAILED),
        )
        assertNull(batch.firstRejectedIndex)
        val s = batch.finalState
        assertEquals(AgentRunPhase.FAILED, s.phase)
        assertTrue(s.persistenceFailed)
        assertEquals(AgentTerminalReason.PERSISTENCE_FAILED, s.terminalReason)
    }

    @Test
    fun `work completed twice is no-op in finalizing`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r13"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
    }

    // ─── 非法转换：Rejected + 原因 ──────────────────────────────────────

    @Test
    fun `idle rejects any event before run started`() {
        val t = AgentRunReducer.reduce(
            AgentRunState.initial(),
            AgentRunEvent.ProviderAttemptStarted,
        )
        assertEquals(AgentRunRejectionReason.RUN_NOT_STARTED, t.rejection().reason)
        // 拒绝不改状态：phase 保持 IDLE
        assertEquals("state must stay IDLE", AgentRunPhase.IDLE, (t as AgentRunTransition.Rejected).state.phase)
    }

    @Test
    fun `second run started is rejected`() {
        val s = AgentRunState.initial().accept(AgentRunEvent.RunStarted("r"))
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunStarted("r2"))
        assertEquals(AgentRunRejectionReason.RUN_ALREADY_STARTED, t.rejection().reason)
    }

    @Test
    fun `run started in finalizing is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.UserCancelled())
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunStarted("r2"))
        assertEquals(AgentRunRejectionReason.RUN_ALREADY_STARTED, t.rejection().reason)
    }

    @Test
    fun `terminal state rejects running events`() {
        val terminal = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.WorkCompleted)
            .accept(AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertEquals(AgentRunPhase.SUCCEEDED, terminal.phase)

        for (event in listOf(
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("x"),
            AgentRunEvent.ToolFinished("x", true),
            AgentRunEvent.RetryRequested(),
            AgentRunEvent.FallbackSelected(),
            AgentRunEvent.CompactionStarted(),
            AgentRunEvent.CompactionFinished(),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.UserCancelled(),
            AgentRunEvent.DeadlineReached(),
            AgentRunEvent.ProcessInterrupted(),
            AgentRunEvent.PersistenceFailed(),
        )) {
            val t = AgentRunReducer.reduce(terminal, event)
            assertEquals(
                "${event::class.simpleName} must be rejected from terminal state",
                AgentRunRejectionReason.TERMINAL_STATE_IMMUTABLE, t.rejection().reason,
            )
        }
    }

    @Test
    fun `run finalize with different terminal is rejected`() {
        val terminal = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.WorkCompleted)
            .accept(AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        val t = AgentRunReducer.reduce(terminal, AgentRunEvent.RunFinalized(AgentTerminal.FAILED))
        assertEquals(AgentRunRejectionReason.TERMINAL_STATE_CONFLICT, t.rejection().reason)
    }

    @Test
    fun `run finalized from calling model is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(AgentTerminal.FAILED))
        assertEquals(AgentRunRejectionReason.FINALIZE_NOT_IN_FINALIZING, t.rejection().reason)
    }

    @Test
    fun `run finalized from executing tools is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertEquals(AgentRunRejectionReason.FINALIZE_NOT_IN_FINALIZING, t.rejection().reason)
    }

    @Test
    fun `tool started while calling model is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
        val t = AgentRunReducer.reduce(s, AgentRunEvent.ToolStarted("file_read"))
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `provider attempt finished while preparing is rejected`() {
        val s = AgentRunState.initial().accept(AgentRunEvent.RunStarted("r"))
        val t = AgentRunReducer.reduce(
            s, AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
        )
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `retry requested while calling model is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RetryRequested())
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `fallback selected while retrying is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE))
        val t = AgentRunReducer.reduce(s, AgentRunEvent.FallbackSelected())
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `retry requested while falling back is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FALLBACK_FAILURE))
        val t = AgentRunReducer.reduce(s, AgentRunEvent.RetryRequested())
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `work completed while calling model is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
        val t = AgentRunReducer.reduce(s, AgentRunEvent.WorkCompleted)
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `compaction finished without starting is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
        val t = AgentRunReducer.reduce(s, AgentRunEvent.CompactionFinished())
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    @Test
    fun `tool finished while compacting is rejected`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.CompactionStarted())
        val t = AgentRunReducer.reduce(s, AgentRunEvent.ToolFinished("x", true))
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, t.rejection().reason)
    }

    // ─── Succeeded 前置条件 ────────────────────────────────────────────

    @Test
    fun `cannot succeed with outcome unknown tool`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.ToolStarted("shell_execute"))
            .accept(AgentRunEvent.ToolFinished("shell_execute", resultKnown = false))
            .accept(AgentRunEvent.WorkCompleted)
        assertTrue(s.hasOutcomeUnknownTool)
        assertEquals(AgentRunPhase.FINALIZING, s.phase)

        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertEquals(AgentRunRejectionReason.SUCCEED_WITH_UNKNOWN_TOOL, t.rejection().reason)

        // 但允许以 Interrupted 收尾（“结果未知”语义）
        val interrupted = AgentRunReducer.reduce(
            s, AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.OUTCOME_UNKNOWN),
        )
        assertEquals(AgentRunPhase.INTERRUPTED, interrupted.acceptedState().phase)
    }

    @Test
    fun `cannot succeed after persistence failure`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.PersistenceFailed("db insert"))
        assertTrue(s.persistenceFailed)
        assertEquals(AgentRunPhase.FINALIZING, s.phase)

        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertEquals(
            AgentRunRejectionReason.SUCCEED_WITH_PERSISTENCE_FAILURE, t.rejection().reason,
        )
    }

    // ─── 终态幂等 finalize ─────────────────────────────────────────────

    @Test
    fun `idempotent finalize on terminal keeps state`() {
        val s = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProviderAttemptStarted)
            .accept(AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS))
            .accept(AgentRunEvent.WorkCompleted)
            .accept(
                AgentRunEvent.RunFinalized(
                    AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED,
                )
            )

        val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertTrue("repeat finalize must be accepted", t is AgentRunTransition.Accepted)
        val accepted = t as AgentRunTransition.Accepted
        assertEquals("repeat finalize must be no-op", false, accepted.changed)
        assertEquals(AgentRunPhase.SUCCEEDED, accepted.state.phase)
        // 不覆盖已有 reason
        assertEquals(AgentTerminalReason.COMPLETED, accepted.state.terminalReason)
    }

    @Test
    fun `idempotent finalize for every terminal`() {
        for (terminal in AgentTerminal.entries) {
            val events = happyPathToFinalizing().toMutableList().apply {
                add(AgentRunEvent.RunFinalized(terminal))
            }
            val s = reduceBatch(*events.toTypedArray()).finalState
            val t = AgentRunReducer.reduce(s, AgentRunEvent.RunFinalized(terminal))
            assertTrue("$terminal repeat finalize accepted", t is AgentRunTransition.Accepted)
            assertEquals(
                AgentRunPhase.ofTerminal(terminal),
                (t as AgentRunTransition.Accepted).state.phase,
            )
        }
    }

    // ─── 竞态场景 ──────────────────────────────────────────────────────

    @Test
    fun `cancel then provider success is tolerated and ends cancelled`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.UserCancelled("user hit stop"),
            // 取消后 provider 结果才到达（stale）
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.RunFinalized(AgentTerminal.CANCELLED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.CANCELLED, batch.finalState.phase)
        // stale 结果不增加 attempt（attempt 在 start 时已计数）
        assertEquals(1, batch.finalState.providerAttemptCount)
    }

    @Test
    fun `provider success then cancel also ends cancelled`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.UserCancelled("user hit stop"),
            AgentRunEvent.RunFinalized(AgentTerminal.CANCELLED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.CANCELLED, batch.finalState.phase)
    }

    @Test
    fun `tool result after process death is ignored`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ProcessInterrupted("process death"),
            // 进程死亡后工具结果到达：被忽略，不计入 toolFinishedCount
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = false),
            AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.PROCESS_INTERRUPTED),
        )
        assertNull(batch.firstRejectedIndex)
        val s = batch.finalState
        assertEquals(AgentRunPhase.INTERRUPTED, s.phase)
        assertEquals(1, s.toolStartedCount)
        assertEquals("stale ToolFinished must not bump finish count", 0, s.toolFinishedCount)
        assertFalse("stale unknown tool result must not poison state", s.hasOutcomeUnknownTool)
    }

    @Test
    fun `tool result marked unknown before death forces interrupted not succeeded`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = false),
            AgentRunEvent.ProcessInterrupted("process death"),
            AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.OUTCOME_UNKNOWN),
        )
        assertNull(batch.firstRejectedIndex)
        assertTrue(batch.finalState.hasOutcomeUnknownTool)
        assertEquals(AgentRunPhase.INTERRUPTED, batch.finalState.phase)
        assertEquals(AgentTerminalReason.OUTCOME_UNKNOWN, batch.finalState.terminalReason)
    }

    @Test
    fun `deadline then retry is ignored and ends interrupted`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.DeadlineReached(999L),
            // deadline 后重试请求到达：被忽略，不发起新 attempt
            AgentRunEvent.RetryRequested("would retry"),
            AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED, AgentTerminalReason.DEADLINE_EXCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        val s = batch.finalState
        assertEquals(AgentRunPhase.INTERRUPTED, s.phase)
        assertEquals("no new attempt after deadline", 1, s.providerAttemptCount)
        assertEquals(AgentTerminalReason.DEADLINE_EXCEEDED, s.terminalReason)
    }

    @Test
    fun `retry before deadline continues normally`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.RetryRequested("still inside budget"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
        assertEquals(2, batch.finalState.providerAttemptCount)
    }

    // ─── 重放稳定性 ────────────────────────────────────────────────────

    @Test
    fun `replay same event sequence yields identical result`() {
        val events = listOf(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.RetryRequested("reset"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("file_read"),
            AgentRunEvent.ToolFinished("file_read", true),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        val r1 = AgentRunReducer.reduceAll(events)
        val r2 = AgentRunReducer.reduceAll(events)
        assertEquals(r1.finalState, r2.finalState)
        assertEquals(r1.reachedTerminal, r2.reachedTerminal)
        assertEquals(r1.firstRejectedIndex, r2.firstRejectedIndex)
        assertEquals(r1.transitions.size, r2.transitions.size)
    }

    @Test
    fun `replay with rejection is stable and fail-fast`() {
        val events = listOf(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("x"),          // 合法
            AgentRunEvent.RetryRequested(),           // 非法：EXECUTING_TOOLS 中
            AgentRunEvent.ToolFinished("x", true),    // 不应被处理
            AgentRunEvent.WorkCompleted,              // 不应被处理
        )
        val r1 = AgentRunReducer.reduceAll(events)
        val r2 = AgentRunReducer.reduceAll(events)

        assertEquals(4, r1.firstRejectedIndex)
        assertEquals(5, r1.appliedCount)
        assertEquals(5, r1.transitions.size)
        assertEquals(AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT, r1.transitions.last().rejection().reason)
        // 首个拒绝后停止：finalState 停在 EXECUTING_TOOLS
        assertEquals(AgentRunPhase.EXECUTING_TOOLS, r1.finalState.phase)
        assertEquals(r1.finalState, r2.finalState)
        assertEquals(r1.firstRejectedIndex, r2.firstRejectedIndex)
    }

    @Test
    fun `step by step replay from initial equals batch result`() {
        val events = listOf(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FATAL_FAILURE),
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED),
        )
        val batch = AgentRunReducer.reduceAll(events)

        var s = AgentRunState.initial()
        for (e in events) {
            val t = AgentRunReducer.reduce(s, e)
            assertTrue(t is AgentRunTransition.Accepted)
            s = (t as AgentRunTransition.Accepted).state
        }
        assertEquals(batch.finalState, s)
    }

    // ─── 计数与状态观察 ────────────────────────────────────────────────

    @Test
    fun `counters only increase`() {
        val events = listOf(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.TRANSIENT_FAILURE),
            AgentRunEvent.RetryRequested("again"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("t1"),
            AgentRunEvent.ToolStarted("t2"),
            AgentRunEvent.ToolFinished("t1", true),
            AgentRunEvent.ToolFinished("t2", true),
            AgentRunEvent.CompactionStarted("long"),
            AgentRunEvent.CompactionFinished(10),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED),
        )
        val s = AgentRunReducer.reduceAll(events).finalState
        assertEquals(3, s.providerAttemptCount)
        assertEquals(2, s.toolStartedCount)
        assertEquals(2, s.toolFinishedCount)
        assertEquals(1, s.compactCount)
    }

    @Test
    fun `provider attempts cannot start after cancelled terminal`() {
        val cancelled = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.UserCancelled())
            .accept(AgentRunEvent.RunFinalized(AgentTerminal.CANCELLED))
        val t = AgentRunReducer.reduce(cancelled, AgentRunEvent.ProviderAttemptStarted)
        assertEquals(AgentRunRejectionReason.TERMINAL_STATE_IMMUTABLE, t.rejection().reason)
    }

    @Test
    fun `provider attempts cannot start after interrupted terminal`() {
        val interrupted = AgentRunState.initial()
            .accept(AgentRunEvent.RunStarted("r"))
            .accept(AgentRunEvent.ProcessInterrupted())
            .accept(AgentRunEvent.RunFinalized(AgentTerminal.INTERRUPTED))
        val t = AgentRunReducer.reduce(interrupted, AgentRunEvent.ProviderAttemptStarted)
        assertEquals(AgentRunRejectionReason.TERMINAL_STATE_IMMUTABLE, t.rejection().reason)
    }

    @Test
    fun `reduceAll with empty events stays idle`() {
        val batch = AgentRunReducer.reduceAll(emptyList())
        assertNull(batch.firstRejectedIndex)
        assertFalse(batch.reachedTerminal)
        assertEquals(AgentRunPhase.IDLE, batch.finalState.phase)
        assertEquals(0, batch.transitions.size)
    }

    @Test
    fun `initial state defaults are sane`() {
        val s = AgentRunState.initial()
        assertEquals(AgentRunPhase.IDLE, s.phase)
        assertNull(s.runId)
        assertEquals(0, s.providerAttemptCount)
        assertEquals(0, s.toolStartedCount)
        assertEquals(0, s.compactCount)
        assertFalse(s.isTerminal)
        assertNull(s.terminalReason)
    }

    // ─── 辅助：到 FINALIZING 的合法路径 ────────────────────────────────

    private fun happyPathToFinalizing(): List<AgentRunEvent> = listOf(
        AgentRunEvent.RunStarted("r"),
        AgentRunEvent.ProviderAttemptStarted,
        AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
        AgentRunEvent.WorkCompleted,
    )
}