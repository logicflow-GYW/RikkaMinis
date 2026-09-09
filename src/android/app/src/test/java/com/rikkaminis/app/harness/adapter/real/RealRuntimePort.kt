package com.rikkaminis.app.harness.adapter.real

import com.rikkaminis.app.harness.adapter.AgentRuntimePort
import com.rikkaminis.app.harness.adapter.ProviderCallResult
import com.rikkaminis.app.harness.adapter.ShellCallResult
import com.rikkaminis.app.harness.adapter.ToolCallResult
import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.service.SessionSlotController
import com.rikkaminis.app.tools.AgentTraceRecorder

/**
 * [T7-real-runtime] 真实运行时端口 —— 用生产组件装配 [AgentRuntimePort]。
 *
 * ## 装配边界
 *
 * | 能力 | 来源 | 真实度 |
 * |---|---|---|
 * | `acquireSlot` / `releaseSlot` | [RealSlotRuntime] → [SessionSlotController]（T1） | ✅ 生产级真实 |
 * | `emitTrace` | [RealTraceRuntime] → [AgentTraceRecorder]（T6，schema 2.0） | ✅ 生产级真实 |
 * | `callProvider` | [RuntimeBehaviorSource] 委托 | 🔶 注入（生产=ChatViewModel 链，测试=场景脚本） |
 * | `executeTool` / `runShell` | [RuntimeBehaviorSource] 委托 | 🔶 注入 |
 * | `persist` | [RuntimeBehaviorSource] 委托 | 🔶 注入 |
 * | `isUserCancelled` / `isProcessDead` | [RuntimeBehaviorSource] 委托 | 🔶 注入 |
 *
 * 生产装配（await ChatViewModel freeze）：
 * ```
 * val bridge = ChatViewModelRuntimeBridge(viewModel)
 * val port = RealRuntimePort(
 *     slotController = SessionSlotController(MAX_CONCURRENT),
 *     traceRecorder = AgentTraceRecorder(appendLine = { traceFile.appendLine(it) }),
 *     behavior = ChatViewModelBehaviorSource(viewModel),
 *     runId = sessionSlotController.newRunId(),
 * )
 * ```
 *
 * @param slotController 真实 T1 会话槽位控制器
 * @param traceRecorder 真实 T6 trace recorder（测试用内存 sink，生产用文件 sink）
 * @param behavior 行为源（生产=ChatViewModel 工厂，测试=ScenarioBehaviorSource）
 * @param runId 本次 run 的唯一标识（由 slotController.newRunId() 生成或传入）
 * @param sessionId 会话 ID
 */
class RealRuntimePort(
    private val slotController: SessionSlotController,
    private val recorder: AgentTraceRecorder,
    private val behavior: RuntimeBehaviorSource,
    private val runId: String,
    private val sessionId: String,
) : AgentRuntimePort {

    /** 真实槽位运行时（暴露快照供测试断言）。 */
    val slot: RealSlotRuntime = RealSlotRuntime(slotController)

    /** 真实 trace 运行时（暴露 JSONL 行供测试断言）。 */
    val trace: RealTraceRuntime = RealTraceRuntime(recorder, runId, sessionId)

    // ── AgentRuntimePort ───────────────────────────────────────────────

    override suspend fun callProvider(attemptIndex: Int): ProviderCallResult =
        behavior.callProvider(attemptIndex)

    override suspend fun executeTool(toolName: String): ToolCallResult =
        behavior.executeTool(toolName)

    override suspend fun runShell(command: String): ShellCallResult =
        behavior.runShell(command)

    override suspend fun persist(mark: PersistenceMark): Boolean =
        behavior.persist(mark)

    override fun acquireSlot(runId: String): Boolean =
        slot.acquire(runId)

    override fun releaseSlot(runId: String) {
        slot.release(runId)
    }

    override fun emitTrace(event: HarnessTraceEvent) {
        trace.record(event)
    }

    override fun isUserCancelled(): Boolean =
        behavior.isUserCancelled()

    override fun isProcessDead(): Boolean =
        behavior.isProcessDead()

    // ── 工厂方法 ───────────────────────────────────────────────────────

    companion object {
        /**
         * 从场景创建装配 —— 用于 F01-F14 验收测试。
         * 使用真实 [SessionSlotController]（T1）和真实 [AgentTraceRecorder]（T6）。
         */
        fun forScenario(
            scenario: FaultScenario,
            sessionId: String = "test-session-${scenario.id}",
            maxConcurrent: Int = 5,
        ): RealRuntimePort {
            val slotController = SessionSlotController(maxConcurrent)
            val runId = "run-${scenario.id}-${System.currentTimeMillis()}"
            val behavior = ScenarioBehaviorSource(scenario)
            val recorder = AgentTraceRecorder(appendLine = { /* 默认空收集 */ })
            return RealRuntimePort(
                slotController = slotController,
                recorder = recorder,
                behavior = behavior,
                runId = runId,
                sessionId = sessionId,
            )
        }

        /**
         * 带 trace sink 的场景装配 —— 构造时传入 JSONL 行收集器，
         * 测试后可通过 [RealRuntimePort.trace.lines] 断言。
         */
        fun forScenarioWithSink(
            scenario: FaultScenario,
            sink: (String) -> Unit,
            sessionId: String = "test-session-${scenario.id}",
            maxConcurrent: Int = 5,
        ): RealRuntimePort {
            val slotController = SessionSlotController(maxConcurrent)
            val runId = "run-${scenario.id}-${System.currentTimeMillis()}"
            val behavior = ScenarioBehaviorSource(scenario)
            val recorder = AgentTraceRecorder(appendLine = sink)
            return RealRuntimePort(
                slotController = slotController,
                recorder = recorder,
                behavior = behavior,
                runId = runId,
                sessionId = sessionId,
            )
        }
    }
}