package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.TraceEventType
import com.rikkaminis.app.agent.runtime.AgentExecutionBudget
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentRunState
import org.junit.Test

/**
 * [T4-B] ScenarioReportFactory 测试：从运行证据组装报告。
 */
class ScenarioReportFactoryTest {

    private fun budget(now: Long = 1_000L): AgentExecutionBudget =
        AgentExecutionBudget(
            startedAtMonotonicMs = now,
            deadlineMonotonicMs = now + 60_000,
            maxTurns = 100,
            maxProviderAttempts = 100,
            maxToolCalls = 100,
            maxShellCommands = 100,
            maxCompactionCalls = 100,
            maxConcurrentTools = 4,
            maxEstimatedTokens = null,
            monotonicClock = { now },
        )

    @Test
    fun `build succeeds for terminal Succeeded state`() {
        val b = budget()
        b.consumeTurn()
        b.consumeProviderAttempt()
        val report = ScenarioReportFactory.build(
            evidence = ScenarioReportFactory.RunEvidence(
                finalState = AgentRunState(phase = AgentRunPhase.SUCCEEDED, providerAttemptCount = 1),
                persistenceMark = PersistenceMark.COMPLETED,
                traceEvents = listOf(
                    HarnessTraceEvent(TraceEventType.RUN_START, 0),
                    HarnessTraceEvent(TraceEventType.RUN_FINALIZED, 1),
                ),
            ),
            budget = b,
        )
        assertEq(report.terminal, com.rikkaminis.app.harness.contract.TerminalState.SUCCEEDED)
        assertEq(report.providerAttempts, 1)
        assertEq(report.persistenceMark, PersistenceMark.COMPLETED)
        assertEq(report.traceTerminalEvents, 1)
        assertEq(report.leaseCount, 0, "sessionSlotReleased=true → leaseCount=0")
        assertEq(report.recoverable, false)
    }

    @Test
    fun `Interrupted state is recoverable and marks partial persistence`() {
        val b = budget()
        val report = ScenarioReportFactory.build(
            evidence = ScenarioReportFactory.RunEvidence(
                finalState = AgentRunState(phase = AgentRunPhase.INTERRUPTED, providerAttemptCount = 1),
                persistenceMark = PersistenceMark.PARTIAL,
                traceEvents = listOf(
                    HarnessTraceEvent(TraceEventType.RUN_START, 0),
                    HarnessTraceEvent(TraceEventType.PROCESS_INTERRUPTED, 1),
                    HarnessTraceEvent(TraceEventType.RUN_FINALIZED, 2),
                ),
            ),
            budget = b,
        )
        assertEq(report.terminal, com.rikkaminis.app.harness.contract.TerminalState.INTERRUPTED)
        assertEq(report.persistenceMark, PersistenceMark.PARTIAL)
        assertEq(report.recoverable, true)
        assertEq(report.traceTerminalEvents, 1)
    }

    @Test
    fun `non-terminal state throws`() {
        val b = budget()
        val threw = try {
            ScenarioReportFactory.build(
                evidence = ScenarioReportFactory.RunEvidence(
                    finalState = AgentRunState(phase = AgentRunPhase.CALLING_MODEL),
                ),
                budget = b,
            )
            false
        } catch (e: IllegalStateException) {
            true
        }
        if (!threw) throw AssertionError("expected IllegalStateException for non-terminal state")
    }

    @Test
    fun `lease not released maps to leaseCount 1`() {
        val b = budget()
        val report = ScenarioReportFactory.build(
            evidence = ScenarioReportFactory.RunEvidence(
                finalState = AgentRunState(phase = AgentRunPhase.SUCCEEDED),
                sessionSlotReleased = false,
                persistenceMark = PersistenceMark.COMPLETED,
            ),
            budget = b,
        )
        assertEq(report.leaseCount, 1, "sessionSlotReleased=false → leaseCount=1")
    }

    @Test
    fun `duplicate side effects and cancellations pass through`() {
        val b = budget()
        val report = ScenarioReportFactory.build(
            evidence = ScenarioReportFactory.RunEvidence(
                finalState = AgentRunState(phase = AgentRunPhase.CANCELLED),
                duplicateSideEffects = 2,
                providerCancellations = 1,
                toolCancellations = 1,
                spawnRejected = 3,
                compactCalls = 1,
                persistenceMark = PersistenceMark.PARTIAL,
            ),
            budget = b,
        )
        assertEq(report.duplicateSideEffects, 2)
        assertEq(report.providerCancellations, 1)
        assertEq(report.toolCancellations, 1)
        assertEq(report.spawnRejected, 3)
        assertEq(report.compactCalls, 1)
    }

    private fun assertEq(actual: Any?, expected: Any?, msg: String = "actual=$actual expected=$expected") {
        if (actual != expected) throw AssertionError(msg)
    }
}
