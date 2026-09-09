package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.ModelTurnScript
import com.rikkaminis.app.harness.contract.AttemptScript
import com.rikkaminis.app.harness.contract.AttemptResult
import com.rikkaminis.app.harness.contract.ScenarioExpectations
import com.rikkaminis.app.harness.contract.TerminalState
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * [T4-B] adapter 骨架结构测试 —— 创建/装配/槽位拒绝/资源释放路径。
 *
 * 注意：T7 未完成时 [RealAgentAdapterSkeleton.driveTurnLoop] 是占位（不执行
 * 真实调用），因此本测试**只验证骨架结构**（接口装配、槽位拒绝路径、release
 * 幂等路径、诚实 FAILED 占位、报告组装），不验证 F01-F14 的完整行为 ——
 * 那是 T7 完成后的验收内容（见 `docs/stability/t4b-acceptance-checklist.md`）。
 */
class RealAgentAdapterSkeletonTest {

    private val minimalScenario = FaultScenario(
        id = "STRUCT-1",
        description = "skeleton structure test",
        turns = listOf(
            ModelTurnScript(
                attempts = listOf(AttemptScript(AttemptResult.SUCCESS, finalAnswer = true)),
            )
        ),
        deadlineMs = Long.MAX_VALUE / 2,
        expect = ScenarioExpectations(
            terminal = TerminalState.SUCCEEDED,
            providerAttempts = 1,
            persistence = PersistenceMark.COMPLETED,
        ),
    )

    // ── 创建 / 装配 ────────────────────────────────────────────────────────

    @Test
    fun `adapter creates with default budget factory`() {
        val runtime = FakeRuntimePort()
        val adapter = RealAgentAdapterSkeleton(runtime)
        // 默认工厂可创建预算（不抛异常）
        val budget = RealAgentAdapterSkeleton.defaultBudgetFor(minimalScenario)
        assertTrue(budget.maxTurns == 100, "default maxTurns=100")
        assertTrue(budget.maxProviderAttempts == 100, "default maxProviderAttempts=100")
        assertTrue(adapter != null, "adapter constructed")
    }

    @Test
    fun `adapter assembles with injected reducer`() {
        val runtime = FakeRuntimePort()
        val adapter = RealAgentAdapterSkeleton(
            runtime = runtime,
            reduce = AgentRunReducer::reduce,
        )
        // 装配不抛异常即通过
        assertTrue(adapter != null, "adapter constructed with injected reducer")
    }

    // ── 生命周期：槽位获取/释放 ────────────────────────────────────────────

    @Test
    fun `slot acquired then released on run`() {
        val runtime = FakeRuntimePort(slotAcquired = true)
        val adapter = RealAgentAdapterSkeleton(runtime)
        val report = runBlocking { adapter.executeScenario(minimalScenario) }
        assertTrue(runtime.acquireCount == 1, "acquire called once")
        assertTrue(runtime.releaseCount == 1, "release called once")
        assertTrue(report.leaseCount == 0, "leaseCount=0 after release")
    }

    @Test
    fun `skeleton drives turn loop with fake runtime port`() {
        val runtime = FakeRuntimePort(slotAcquired = true)
        val adapter = RealAgentAdapterSkeleton(runtime)
        val report = runBlocking { adapter.executeScenario(minimalScenario) }
        // driveTurnLoop now runs: FakeRuntimePort returns Success(finalAnswer=true)
        // on the first callProvider(0), so the loop completes with SUCCEEDED.
        assertTrue(report.terminal == TerminalState.SUCCEEDED, "skeleton → SUCCEEDED (FakeRuntimePort returns finalAnswer=true)")
        assertTrue(runtime.providerCalls.size == 1, "1 provider call")
        assertTrue(runtime.toolCalls.isEmpty(), "no tool calls (minimal scenario has none)")
        assertTrue(runtime.shellCalls.isEmpty(), "no shell calls")
        assertTrue(report.traceTerminalEvents == 1, "terminal trace event exactly once")
        assertTrue(runtime.persistCalls.size == 1, "persist called once on finalAnswer")
        assertTrue(
            runtime.persistCalls.first() == com.rikkaminis.app.harness.contract.PersistenceMark.COMPLETED,
            "persist with COMPLETED",
        )
    }

    @Test
    fun `slot denied produces FAILED without calling provider`() {
        val runtime = FakeRuntimePort(slotAcquired = false)
        val adapter = RealAgentAdapterSkeleton(runtime)
        val report = runBlocking { adapter.executeScenario(minimalScenario) }
        assertTrue(report.terminal == TerminalState.FAILED, "slot denied → FAILED")
        assertTrue(runtime.providerCalls.isEmpty(), "no provider call when slot denied")
        assertTrue(runtime.acquireCount == 1, "acquire attempted")
        assertTrue(runtime.releaseCount == 0, "no release needed for denied slot")
        assertTrue(report.leaseCount == 0, "denied slot leaves no lease")
        assertTrue(report.traceTerminalEvents == 1, "terminal trace event exactly once")
    }

    // ── trace 事件 ─────────────────────────────────────────────────────────

    @Test
    fun `trace events emitted through runtime port`() {
        val runtime = FakeRuntimePort()
        val adapter = RealAgentAdapterSkeleton(runtime)
        runBlocking { adapter.executeScenario(minimalScenario) }
        assertTrue(runtime.emitted.isNotEmpty(), "at least one trace event emitted")
        assertTrue(
            runtime.emitted.any { it.type == com.rikkaminis.app.harness.contract.TraceEventType.RUN_START },
            "RUN_START emitted",
        )
        assertTrue(
            runtime.emitted.any { it.type == com.rikkaminis.app.harness.contract.TraceEventType.RUN_FINALIZED },
            "RUN_FINALIZED emitted",
        )
    }

    // ── 报告结构 ───────────────────────────────────────────────────────────

    @Test
    fun `report carries budget snapshot`() {
        val runtime = FakeRuntimePort()
        val adapter = RealAgentAdapterSkeleton(runtime)
        val report = runBlocking { adapter.executeScenario(minimalScenario) }
        assertTrue(report.budgetSnapshot.maxTurns == 100, "budget snapshot present")
        assertTrue(report.persistenceMark == PersistenceMark.COMPLETED, "skeleton completion → COMPLETED")
    }

    private fun assertTrue(condition: Boolean, msg: String) {
        if (!condition) throw AssertionError(msg)
    }
}
