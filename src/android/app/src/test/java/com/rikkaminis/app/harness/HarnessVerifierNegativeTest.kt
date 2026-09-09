package com.rikkaminis.app.harness

import com.rikkaminis.app.harness.contract.*
import com.rikkaminis.app.harness.fakes.*
import com.rikkaminis.app.harness.runner.HarnessRunner
import com.rikkaminis.app.harness.runner.RunnerPolicies
import com.rikkaminis.app.harness.runner.ScenarioVerifier
import com.rikkaminis.app.harness.scenarios.FaultScenarios
import org.junit.Test

/**
 * 负例/变异测试：证明 ScenarioVerifier 能将违反正确分类为五类之一。
 */
class HarnessVerifierNegativeTest {

    @Test
    fun `violation TERMINAL_STATE when terminal mismatches`() {
        val report = ScenarioReport(
            terminal = TerminalState.FAILED,
            providerAttempts = 1,
            toolExecutions = 0,
            duplicateSideEffects = 0,
            budgetSnapshot = BudgetSnapshot(
                turnsUsed = 0, maxTurns = 100,
                providerAttemptsUsed = 1, maxProviderAttempts = 100,
                toolCallsUsed = 0, maxToolCalls = 100,
                shellCommandsUsed = 0, maxShellCommands = 100,
                compactionCallsUsed = 0, maxCompactionCalls = 100,
                expired = false,
            ),
            leaseCount = 0,
            traceTerminalEvents = 1,
            persistenceMark = PersistenceMark.COMPLETED,
            recoverable = false,
        )
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.TERMINAL_STATE },
            "Expected TERMINAL_STATE violation"
        )
    }

    @Test
    fun `violation TERMINAL_STATE when trace terminal events mismatch`() {
        val report = buildReport(FaultScenarios.F01).copy(
            traceTerminalEvents = 0
        )
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.TERMINAL_STATE },
            "Expected TERMINAL_STATE for traceTerminalEvents mismatch"
        )
    }

    @Test
    fun `violation BUDGET when provider attempts mismatch`() {
        val report = buildReport(FaultScenarios.F01).copy(providerAttempts = 5)
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.BUDGET },
            "Expected BUDGET violation for providerAttempts"
        )
    }

    @Test
    fun `violation BUDGET when tool executions mismatch`() {
        val report = buildReport(FaultScenarios.F01).copy(toolExecutions = 3)
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.BUDGET },
            "Expected BUDGET violation for toolExecutions"
        )
    }

    @Test
    fun `violation RESOURCE when lease not released`() {
        val report = buildReport(FaultScenarios.F01).copy(leaseCount = 1)
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.RESOURCE },
            "Expected RESOURCE violation for lease leak"
        )
    }

    @Test
    fun `violation PERSISTENCE when persistence mark mismatch`() {
        val report = buildReport(FaultScenarios.F01).copy(
            persistenceMark = PersistenceMark.PARTIAL
        )
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.PERSISTENCE },
            "Expected PERSISTENCE violation for persistence mark"
        )
    }

    @Test
    fun `violation SIDE_EFFECT when duplicate side effects`() {
        val report = buildReport(FaultScenarios.F01).copy(duplicateSideEffects = 2)
        val violations = ScenarioVerifier.verify(FaultScenarios.F01, report)
        assertTrue(
            violations.any { it.category == ViolationCategory.SIDE_EFFECT },
            "Expected SIDE_EFFECT violation for duplicate side effects"
        )
    }

    @Test
    fun `BUGGY runner - finalize twice produces TERMINAL_STATE violation`() {
        val scenario = FaultScenarios.F01
        val clock = FakeClock()
        val trace = FakeTraceSink()
        val providers = listOf(FakeProvider("p0"), FakeProvider("p1"))
        val tools = FakeToolExecutor(emptyMap())
        val persistence = FakePersistence(PersistenceScript())
        val slots = FakeSessionSlots()

        val runner = HarnessRunner(clock, trace, RunnerPolicies(finalizeTwice = true))
        val report = runner.run(scenario, providers, tools, null, persistence, slots)
        val violations = ScenarioVerifier.verify(scenario, report)

        assertTrue(
            violations.any { it.category == ViolationCategory.TERMINAL_STATE },
            "Expected TERMINAL_STATE violation for double finalize"
        )
    }

    @Test
    fun `BUGGY runner - leak session lease produces RESOURCE violation`() {
        val scenario = FaultScenarios.F01
        val clock = FakeClock()
        val trace = FakeTraceSink()
        val providers = listOf(FakeProvider("p0"), FakeProvider("p1"))
        val tools = FakeToolExecutor(emptyMap())
        val persistence = FakePersistence(PersistenceScript())
        val slots = FakeSessionSlots()

        val runner = HarnessRunner(clock, trace, RunnerPolicies(leakSessionLease = true))
        val report = runner.run(scenario, providers, tools, null, persistence, slots)
        val violations = ScenarioVerifier.verify(scenario, report)

        assertTrue(
            violations.any { it.category == ViolationCategory.RESOURCE },
            "Expected RESOURCE violation for leaked lease"
        )
    }

    @Test
    fun `BUGGY runner - finalize twice on failed scenario produces TERMINAL_STATE violation`() {
        val scenario = FaultScenarios.F02
        val clock = FakeClock()
        val trace = FakeTraceSink()
        val providers = listOf(FakeProvider("p0"), FakeProvider("p1"), FakeProvider("p2"))
        val tools = FakeToolExecutor(emptyMap())
        val persistence = FakePersistence(PersistenceScript())
        val slots = FakeSessionSlots()

        val runner = HarnessRunner(clock, trace, RunnerPolicies(finalizeTwice = true))
        val report = runner.run(scenario, providers, tools, null, persistence, slots)
        val violations = ScenarioVerifier.verify(scenario, report)

        assertTrue(
            violations.any { it.category == ViolationCategory.TERMINAL_STATE },
            "Expected TERMINAL_STATE violation for double finalize"
        )
    }

    private fun buildReport(scenario: FaultScenario): ScenarioReport {
        val e = scenario.expect
        return ScenarioReport(
            terminal = e.terminal,
            providerAttempts = e.providerAttempts,
            toolExecutions = e.toolExecutions,
            duplicateSideEffects = e.duplicateSideEffects,
            budgetSnapshot = BudgetSnapshot(
                turnsUsed = 0, maxTurns = 100,
                providerAttemptsUsed = e.providerAttempts, maxProviderAttempts = 100,
                toolCallsUsed = e.toolExecutions, maxToolCalls = 100,
                shellCommandsUsed = 0, maxShellCommands = 100,
                compactionCallsUsed = 0, maxCompactionCalls = 100,
                expired = false,
            ),
            leaseCount = 0,
            traceTerminalEvents = e.traceTerminalEvents,
            persistenceMark = e.persistence,
            recoverable = e.recoverable,
            cooldownRecords = List(e.cooldownCount) { CooldownRecord("p$it", 60000) },
        )
    }

    private fun assertTrue(condition: Any, message: String) {
        if (condition != true) throw AssertionError(message)
    }
}