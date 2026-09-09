package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.ScenarioReport
import com.rikkaminis.app.harness.contract.TerminalState
import com.rikkaminis.app.harness.runner.ScenarioVerifier
import com.rikkaminis.app.harness.scenarios.FaultScenarios
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T4-B] F01-F14 验收测试 —— 通过 [RealAgentAdapterSkeleton] 驱动
 * 真实主链（[ScenarioRuntimePort] 模拟故障注入），验证每个场景的
 * 终态、预算、trace 事件、持久化标记与 [FaultScenario.expect] 一致。
 *
 * 每个场景独立运行，不共享状态。F10（并发槽位）不在此测试中——
 * 它走 [FakeSessionSlots] 专用测试路径。
 */
class RealAgentAdapterAcceptanceTest {

    private val allScenarios: List<FaultScenario> = listOf(
        FaultScenarios.F01, FaultScenarios.F02, FaultScenarios.F03,
        FaultScenarios.F04, FaultScenarios.F05, FaultScenarios.F06,
        FaultScenarios.F07, FaultScenarios.F08, FaultScenarios.F09,
        // F10: 并发槽位测试，走 SessionSlotController 专用测试
        FaultScenarios.F11, FaultScenarios.F12, FaultScenarios.F13,
        FaultScenarios.F14,
    )

    @Test
    fun `all F01-F14 scenarios pass through real adapter`() {
        val failures = mutableListOf<String>()
        for (scenario in allScenarios) {
            val report = runScenario(scenario)
            val violations = ScenarioVerifier.verify(scenario, report)
            if (violations.isNotEmpty()) {
                failures.add("${scenario.id}: ${violations.joinToString("; ") { "${it.category} ${it.message}" }}")
            }
        }
        assertTrue(
            "F01-F14 violations:\n  ${failures.joinToString("\n  ")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `F01 429 fallback success`() = verifySingle(FaultScenarios.F01)
    @Test
    fun `F02 all providers fail`() = verifySingle(FaultScenarios.F02)
    @Test
    fun `F03 stream reset retry`() = verifySingle(FaultScenarios.F03)
    @Test
    fun `F04 drop after first chunk`() = verifySingle(FaultScenarios.F04)
    @Test
    fun `F05 tool failure`() = verifySingle(FaultScenarios.F05)
    @Test
    fun `F06 shell death before result`() = verifySingle(FaultScenarios.F06)
    @Test
    fun `F07 user cancel during provider`() = verifySingle(FaultScenarios.F07)
    @Test
    fun `F08 user cancel during tool`() = verifySingle(FaultScenarios.F08)
    @Test
    fun `F09 compact timeout`() = verifySingle(FaultScenarios.F09)
    @Test
    fun `F11 deadline reached`() = verifySingle(FaultScenarios.F11)
    @Test
    fun `F12 persistence failure`() = verifySingle(FaultScenarios.F12)
    @Test
    fun `F13 spawn rejection`() = verifySingle(FaultScenarios.F13)
    @Test
    fun `F14 process death`() = verifySingle(FaultScenarios.F14)

    private fun verifySingle(scenario: FaultScenario) {
        val report = runScenario(scenario)
        val violations = ScenarioVerifier.verify(scenario, report)
        assertTrue(
            "${scenario.id} violations:\n  ${violations.joinToString("\n  ") { "${it.category} ${it.message}" }}",
            violations.isEmpty(),
        )
    }

    private fun runScenario(scenario: FaultScenario): ScenarioReport {
        val runtime = ScenarioRuntimePort(scenario)
        val adapter = RealAgentAdapterSkeleton(runtime)
        return runBlocking { adapter.executeScenario(scenario) }
    }
}