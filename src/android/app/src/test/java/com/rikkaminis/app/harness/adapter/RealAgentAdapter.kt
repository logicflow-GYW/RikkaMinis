package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.ScenarioReport
import com.rikkaminis.app.harness.contract.TraceEventType
import com.rikkaminis.app.harness.contract.HarnessTraceEvent

/**
 * [T4-B] 生产 Adapter —— 把 T4-A 故障场景驱动到真实 Agent Run 主链。
 *
 * ## 职责
 *
 * 消费 [FaultScenario]（故障脚本，见 `harness/scenarios/FaultScenarios.kt`），
 * 通过 [AgentRuntimePort]（T7 提供真实实现）驱动真实 provider / tool / shell /
 * persistence / session slot，产出与 T4-A `HarnessRunner` **同构**的
 * [ScenarioReport]，由 `ScenarioVerifier`（`harness/runner/ScenarioVerifier.kt`）
 * 断言。
 *
 * 这样 T4-B 交付后：`FaultScenarios`（场景定义）与 `ScenarioVerifier`（断言引擎）
 * 原样复用，只有"runner"被替换为生产 adapter —— 这正是 T4-A 交付报告中
 * 对 T4-B 的接口约定（`executeScenario(scenario) + verify(scenario, report)`）。
 *
 * ## 与 T7 的关系（重要）
 *
 * - T7（主链接入）正在施工，`ChatViewModel.kt` 的 Agent Run 主链接口**未冻结**；
 * - 本接口与 [AgentRuntimePort] 是 T4-B 定义的对 T7 的**对接面**：T7 完成后实现
 *   [AgentRuntimePort]，adapter 即可驱动真实主链，无需改场景和断言；
 * - 凡是依赖 T7 接口的字段/方法，本文档标注 `Assumed(await T7)` —— 可能随 T7
 *   冻结而调整。
 *
 * ## 阶段策略（蓝图 §T4-B）
 *
 * - 当前（T7 未完成）：接口 + 骨架 + 纯逻辑映射（[ScenarioReportFactory]、
 *   [BudgetBridge]、[StateBridge]）就绪，用 fake runtime 验证结构；
 * - T7 完成后：以真实 [AgentRuntimePort] 实现替换，跑通 F01-F14。
 */
interface RealAgentAdapter {

    /**
     * 运行一个故障场景，返回与 T4-A `HarnessRunner` 同构的报告。
     *
     * 实现必须保证：
     * 1. 终态唯一（[ScenarioReport.terminal] 只落一次）；
     * 2. 资源释放（session slot / tool slot / shell 全部释放后 [ScenarioReport.leaseCount] == 0）；
     * 3. trace terminal event 恰好一次（[ScenarioReport.traceTerminalEvents] == 1）；
     * 4. persistence 标记符合终态语义（Succeeded 不得带 FAILED/PARTIAL 之外标记）；
     * 5. 副作用未知的工具不得被透明重跑（[ScenarioReport.duplicateSideEffects] 可解释）。
     */
    suspend fun executeScenario(scenario: FaultScenario): ScenarioReport
}

/**
 * [T4-B] 真实 Agent Run 运行时端口 —— T7 完成后的对接点。
 *
 * ## 设计意图
 *
 * 这是 adapter 与真实主链之间的**唯一耦合面**。T4-A 的 `HarnessRunner` 直接
 * 操作 fakes；生产 adapter 改经本端口操作真实运行时。T7 把 `ChatViewModel` 的
 * 主链能力（provider 调用、tool 执行、shell、持久化、槽位、trace、取消/进程
 * 状态）封装为本端口的一个实现（`RealRuntimePort`），T4-B 不感知实现细节。
 *
 * 每个方法都标注了 T4-A fake 的对应物，便于 T7 实现时对照：
 *
 * | 本端口方法 | T4-A fake 对应 | 说明 |
 * |---|---|---|
 * | [callProvider] | `FakeProvider` | 按 attempt 语义调用真实 provider 链 |
 * | [executeTool] | `FakeToolExecutor` | 执行真实工具，返回是否发生副作用/结果是否已知 |
 * | [runShell] | `FakeShell` | 执行真实 shell（T7 接入 `ExecutionCoordinator`） |
 * | [persist] | `FakePersistence` | 落库/落文件，返回是否成功 |
 * | [acquireSlot]/[releaseSlot] | `FakeSessionSlots` | 会话并发槽位（T7 接 `SessionConcurrencyManager`） |
 * | [emitTrace] | `FakeTraceSink` | trace 输出（T7 接 `AgentTraceRecorder` schema 2.0） |
 * | [isUserCancelled]/[isProcessDead] | runner 内部检查 | 取消/进程死亡探测（T7 接 ViewModel 取消 + ProcessLifecycle） |
 *
 * ## Assumed(await T7)
 *
 * - 方法签名可能随 T7 冻结微调（例如 provider 调用参数可能需要模型组/上下文）；
 * - `Assumed` 标记的方法在 T7 交付前由 fake 实现提供，仅用于结构测试。
 */
interface AgentRuntimePort {

    /**
     * 调用真实 provider（fallback 链的第 [attemptIndex] 个成员）。
     * @param attemptIndex 当前 attempt 在 fallback 链中的序号（0 起）。
     * @return 生产侧分类结果。
     * Assumed(await T7)：参数形态（模型组/上下文/流式收集）以 T7 冻结为准。
     */
    suspend fun callProvider(attemptIndex: Int): ProviderCallResult

    /**
     * 执行真实工具。
     * @param toolName 工具名（来自 provider 返回的 toolCalls）。
     * Assumed(await T7)：真实工具注册表与参数传递以 T7 冻结为准。
     */
    suspend fun executeTool(toolName: String): ToolCallResult

    /**
     * 执行真实 shell 命令。
     * Assumed(await T7)：接入 `ExecutionCoordinator` 与 `RetryPolicy` 后签名可能变化。
     */
    suspend fun runShell(command: String): ShellCallResult

    /**
     * 执行必需持久化。
     * @param mark 终态对应的持久化标记。
     * @return true=成功，false=失败（失败后 run 不得进入 Succeeded）。
     */
    suspend fun persist(mark: PersistenceMark): Boolean

    /** 尝试获取会话槽位。返回 true=成功，false=排队/拒绝。 */
    fun acquireSlot(runId: String): Boolean

    /** 释放会话槽位。幂等。 */
    fun releaseSlot(runId: String)

    /** 输出一条 trace 事件（T7 接 `AgentTraceRecorder`）。写失败不得阻断执行。 */
    fun emitTrace(event: HarnessTraceEvent)

    /** 用户是否已取消（provider/tool 调用间隙轮询）。 */
    fun isUserCancelled(): Boolean

    /** 进程是否已死亡/被回收（用于 F14 与重启恢复）。 */
    fun isProcessDead(): Boolean
}

/** provider 调用结果（镜像 `AttemptResult` 语义的生产侧分类）。 */
sealed class ProviderCallResult {
    /** 成功。payload 含工具调用名列表；finalAnswer=true 表示本轮终结。 */
    data class Success(
        val toolCalls: List<String> = emptyList(),
        val finalAnswer: Boolean = false,
    ) : ProviderCallResult()

    /** 429（RPM/配额）。cooldownMs 为生产冷却时长。 */
    data class RateLimited(val cooldownMs: Long) : ProviderCallResult()

    /** 流重置（HTTP/2 CANCEL 等瞬态）。 */
    data class StreamReset(val detail: String? = null) : ProviderCallResult()

    /** 首 chunk 后断流（部分输出已产生）。 */
    data class DroppedAfterFirstChunk(val partialContent: Boolean = true) : ProviderCallResult()

    /** 硬失败（fallback 链继续）。 */
    data class HardFailure(val detail: String? = null) : ProviderCallResult()

    /** finish_reason=length（需续写）。 */
    data class LengthFinish(val detail: String? = null) : ProviderCallResult()
}

/** 工具执行结果。 */
data class ToolCallResult(
    val success: Boolean,
    /** 是否已产生外部副作用（即使结果丢失）。 */
    val sideEffectPerformed: Boolean = false,
    /** 结果是否已知（false = OutcomeUnknown，禁止透明重跑）。 */
    val resultKnown: Boolean = true,
    /** 是否因取消而中断。 */
    val cancelled: Boolean = false,
    /** 递归 spawn 是否被拒绝。 */
    val spawnRejected: Boolean = false,
)

/** shell 执行结果。 */
data class ShellCallResult(
    val success: Boolean,
    val sideEffectPerformed: Boolean = false,
    val resultKnown: Boolean = true,
)

/**
 * 场景驱动骨架的运行时装配点 —— 供 [RealAgentAdapter] 实现使用。
 *
 * 把一个场景运行所需的全部依赖收拢为单个装配对象，便于：
 * - 当前阶段：用 fake 装配做结构测试（[FakeRuntimePort]）；
 * - T7 完成后：用真实装配替换（无需改驱动骨架）。
 */
data class AgentRunAssembly(
    /** 预算（T2 生产类型）。由 adapter 按场景创建。 */
    val budget: com.rikkaminis.app.agent.runtime.AgentExecutionBudget,
    /** reducer（T5 生产类型）—— 单一事实源，驱动骨架只发事件。 */
    val reducer: com.rikkaminis.app.agent.runtime.AgentRunReducer,
    /** 运行时端口（T7 真实实现 / 当前 fake）。 */
    val runtime: AgentRuntimePort,
)

/**
 * [T4-B] 事件类型 → [TraceEventType] 的 trace 简化辅助。
 * 驱动骨架用 [AgentRuntimePort.emitTrace] 输出，T7 接真实 recorder。
 */
object TraceBridge {
    /** 把 T4-A 的 trace 事件类型原样转发（T4-A 已镜像 T5 事件名）。 */
    fun emit(runtime: AgentRuntimePort, type: TraceEventType, atMs: Long, detail: String? = null) {
        runCatching { runtime.emitTrace(HarnessTraceEvent(type, atMs, detail)) }
        // trace 写失败不阻断执行（蓝图 §T6 兼容要求），此处吞异常。
    }
}
