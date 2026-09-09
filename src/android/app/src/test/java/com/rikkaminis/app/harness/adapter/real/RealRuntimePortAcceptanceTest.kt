package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.adapter.real.RealRuntimePort
import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.ScenarioReport
import com.rikkaminis.app.harness.runner.ScenarioVerifier
import com.rikkaminis.app.harness.scenarios.FaultScenarios
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T7-real-runtime] F01-F14 验收测试 —— 通过 [RealRuntimePort] 用真实生产组件
 * 装配驱动 [RealAgentAdapterSkeleton]。
 *
 * ## 与 [RealAgentAdapterAcceptanceTest] 的区别
 *
 * | 维度 | RealAgentAdapterAcceptanceTest | 本测试 |
 * |---|---|---|
 * | 运行时端口 | [ScenarioRuntimePort]（纯 fake） | [RealRuntimePort]（真实生产组件） |
 * | 槽位 | 简单计数器 | 真实 [SessionSlotController]（T1 FIFO/排队/取消） |
 * | Trace | 内存收集（[HarnessTraceEvent] 列表） | 真实 [AgentTraceRecorder]（T6 schema 2.0 JSONL） |
 * | 预算/Reducer | 骨架（生产类） | 骨架（生产类，不变） |
 * | Provider/Tool/Shell/Persist | 场景脚本 | 场景脚本（[ScenarioBehaviorSource]） |
 *
 * 除 [ScenarioVerifier] 标准断言外，本测试还验证真实生产组件的注入：
 * - 槽位快照：run 结束后 `activeCount == 0`（所有槽位已释放，T1 不变量 3/4）；
 * - trace JSONL：`trace_start` 恰好一条、`trace_end` 恰好一条（T6 terminal 去重）。
 *
 * F10（五会话并发）因使用专用 FakeSessionSlots 测试路径，不在本测试中覆盖。
 */
class RealRuntimePortAcceptanceTest {

    private val allScenarios: List<FaultScenario> = listOf(
        FaultScenarios.F01, FaultScenarios.F02, FaultScenarios.F03,
        FaultScenarios.F04, FaultScenarios.F05, FaultScenarios.F06,
        FaultScenarios.F07, FaultScenarios.F08, FaultScenarios.F09,
        FaultScenarios.F11, FaultScenarios.F12, FaultScenarios.F13,
        FaultScenarios.F14,
    )

    @Test
    fun `all F01-F14 scenarios pass through RealRuntimePort`() {
        val failures = mutableListOf<String>()
        for (scenario in allScenarios) {
            val outcome = runScenario(scenario)
            val violations = ScenarioVerifier.verify(scenario, outcome.report)
            if (violations.isNotEmpty()) {
                failures.add("${scenario.id}: ${violations.joinToString("; ") { "${it.category} ${it.message}" }}")
            }
        }
        assertTrue(
            "F01-F14 violations via RealRuntimePort:\n  ${failures.joinToString("\n  ")}",
            failures.isEmpty(),
        )
    }

    // ── 单场景测试（与 RealAgentAdapterAcceptanceTest 一一对应） ──────

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

    // ── 真实组件不变量测试 ───────────────────────────────────────────

    @Test
    fun `session slot released after every scenario run`() {
        val failures = mutableListOf<String>()
        for (scenario in allScenarios) {
            val outcome = runScenario(scenario)
            val snap = outcome.port.slot.snapshot()
            if (snap.activeCount != 0 || snap.waitingCount != 0) {
                failures.add(
                    "${scenario.id}: slot not drained: active=${snap.activeCount} waiting=${snap.waitingCount}"
                )
            }
        }
        assertTrue(
            "slot leak detected:\n  ${failures.joinToString("\n  ")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `trace_start and trace_end exactly once per run`() {
        val failures = mutableListOf<String>()
        for (scenario in allScenarios) {
            val outcome = runScenario(scenario)
            val lines = outcome.traceLines
            val starts = lines.count { it.contains("\"trace_start\"") }
            val ends = lines.count { it.contains("\"trace_end\"") }
            if (starts != 1) {
                failures.add("${scenario.id}: trace_start=$starts (expected 1)")
            }
            if (ends != 1) {
                failures.add("${scenario.id}: trace_end=$ends (expected 1)")
            }
        }
        assertTrue(
            "trace terminal mismatch:\n  ${failures.joinToString("\n  ")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `emits schema v2 header on every run`() {
        for (scenario in allScenarios) {
            val outcome = runScenario(scenario)
            val lines = outcome.traceLines
            if (lines.isEmpty()) {
                fail("${scenario.id}: no trace lines emitted")
                return
            }
            val first = lines.first()
            assertTrue(
                "${scenario.id}: trace_start missing schema_version",
                first.contains("trace_schema_version"),
            )
            assertTrue(
                "${scenario.id}: trace_start missing run_id",
                first.contains("run_id"),
            )
        }
    }

    private fun verifySingle(scenario: FaultScenario) {
        val outcome = runScenario(scenario)
        val violations = ScenarioVerifier.verify(scenario, outcome.report)
        assertTrue(
            "${scenario.id} violations via RealRuntimePort:\n  ${violations.joinToString("\n  ") { "${it.category} ${it.message}" }}",
            violations.isEmpty(),
        )
    }

    private fun runScenario(scenario: FaultScenario): ScenarioOutcome {
        val traceLines = mutableListOf<String>()
        val port = RealRuntimePort.forScenarioWithSink(
            scenario = scenario,
            sink = { traceLines += it },
        )
        val adapter = RealAgentAdapterSkeleton(port)
        val report = runBlocking { adapter.executeScenario(scenario) }
        return ScenarioOutcome(port, report, traceLines)
    }

    private class ScenarioOutcome(
        val port: RealRuntimePort,
        val report: ScenarioReport,
        val traceLines: List<String>,
    )
}