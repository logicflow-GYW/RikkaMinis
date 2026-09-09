package com.rikkaminis.app.harness

import com.rikkaminis.app.harness.contract.*
import com.rikkaminis.app.harness.fakes.*
import com.rikkaminis.app.harness.runner.HarnessRunner
import com.rikkaminis.app.harness.runner.ScenarioVerifier
import com.rikkaminis.app.harness.scenarios.FaultScenarios
import org.junit.Test

/**
 * 故障矩阵 F01-F14 验收测试。
 */
class FaultMatrixScenarioTest {

    @Test
    fun `F01 - 429 then fallback succeeds`() = runScenario(FaultScenarios.F01)
    @Test
    fun `F02 - all providers fail`() = runScenario(FaultScenarios.F02)
    @Test
    fun `F03 - stream reset then fallback succeeds`() = runScenario(FaultScenarios.F03)
    @Test
    fun `F04 - provider drops after first chunk`() = runScenario(FaultScenarios.F04)
    @Test
    fun `F05 - tool failure converges`() = runScenario(FaultScenarios.F05)
    @Test
    fun `F06 - side effect then shell death`() = runScenario(FaultScenarios.F06)
    @Test
    fun `F07 - user cancels during provider call`() = runScenario(FaultScenarios.F07)
    @Test
    fun `F08 - user cancels during tool call`() = runScenario(FaultScenarios.F08)
    @Test
    fun `F09 - compact timeout`() = runScenario(FaultScenarios.F09)
    @Test
    fun `F10 - five concurrent sessions, sixth queued`() = runF10()
    @Test
    fun `F11 - deadline reached`() = runScenario(FaultScenarios.F11)
    @Test
    fun `F12 - persistence write fails`() = runScenario(FaultScenarios.F12)
    @Test
    fun `F13 - recursive spawn rejected`() = runScenario(FaultScenarios.F13)
    @Test
    fun `F14 - process death and restart`() = runScenario(FaultScenarios.F14)

    private fun runScenario(scenario: FaultScenario) {
        for (iteration in 1..5) {
            val report = executeScenario(scenario)
            val violations = ScenarioVerifier.verify(scenario, report)
            if (violations.isNotEmpty()) {
                throw AssertionError("${scenario.id} iteration $iteration: violations: ${violations.joinToString("; ")}")
            }
        }
    }

    private fun runF10() {
        val slots = FakeSessionSlots(maxConcurrent = 5)
        val runIds = (1..6).map { "run-F10-$it" }
        val results = runIds.map { slots.acquire(it) }

        assertTrue(results[0], "first 5 acquired")
        assertTrue(results[1], "first 5 acquired")
        assertTrue(results[2], "first 5 acquired")
        assertTrue(results[3], "first 5 acquired")
        assertTrue(results[4], "first 5 acquired")
        assertTrue(!results[5], "6th queued")
        assertTrue(slots.activeCount() == 5, "active=5")
        assertTrue(slots.waitingCount() == 1, "waiting=1")

        slots.release("run-F10-1")
        assertTrue(slots.activeCount() == 5, "active=5 after release")
        assertTrue(slots.waitingCount() == 0, "waiting=0 after release")
        assertTrue(slots.isActive("run-F10-6"), "run6 now active")
        assertTrue(slots.isReleased("run-F10-1"), "run1 released")

        (2..6).forEach { slots.release("run-F10-$it") }
        assertTrue(slots.activeCount() == 0, "all released")
        slots.reset()

        val runs2 = (1..6).map { "run-F10b-$it" }
        runs2.forEach { slots.acquire(it) }
        assertTrue(slots.waitingCount() == 1, "waiting=1")

        assertTrue(slots.cancelWaiting("run-F10b-6"), "cancel waiting")
        assertTrue(slots.waitingCount() == 0, "waiting=0 after cancel")
        assertTrue(!slots.isActive("run-F10b-6"), "run6 not active")

        slots.release("run-F10b-1")
        assertTrue(slots.activeCount() == 4, "active=4")
        assertTrue(slots.waitingCount() == 0, "waiting=0 after release")

        (2..5).forEach { slots.release("run-F10b-$it") }
        assertTrue(slots.activeCount() == 0, "all released")
    }

    private fun executeScenario(scenario: FaultScenario): ScenarioReport {
        val clock = FakeClock()
        val trace = FakeTraceSink()
        val providers = scenario.turns.flatMap { turn -> turn.attempts }
            .mapIndexed { idx, _ -> FakeProvider(providerId = "provider-$idx") }
            .distinctBy { it.providerId }
            .toMutableList()
        if (providers.isEmpty()) {
            providers.add(FakeProvider("provider-0"))
        }
        val tools = FakeToolExecutor(scenario.toolScripts)
        val shell = scenario.shellScript?.let { FakeShell(it) }
        val persistence = FakePersistence(scenario.persistence)
        val slots = FakeSessionSlots(scenario.maxConcurrent)
        val runner = HarnessRunner(clock, trace)
        return runner.run(scenario, providers, tools, shell, persistence, slots)
    }

    private fun assertTrue(condition: Any, message: String) {
        if (condition != true) throw AssertionError(message)
    }
}