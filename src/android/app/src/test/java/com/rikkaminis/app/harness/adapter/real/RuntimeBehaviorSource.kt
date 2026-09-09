package com.rikkaminis.app.harness.adapter.real

import com.rikkaminis.app.harness.contract.PersistenceMark

/**
 * [T7-real-runtime] 真实运行时行为源 —— 生产主链能力的委托接口。
 *
 * ## 设计意图
 *
 * [RealRuntimePort] 是 T4-B 定义的 [com.rikkaminis.app.harness.adapter.AgentRuntimePort]
 * 的生产装配。其中两类能力在 JVM 测试中可以直接使用真实生产组件：
 *
 * - 会话槽位 → [com.rikkaminis.app.service.SessionSlotController]（T1，纯 JVM）
 * - trace 输出 → [com.rikkaminis.app.tools.AgentTraceRecorder]（T6，纯 JVM）
 *
 * 而 provider 调用、工具执行、shell、持久化、取消/进程死亡探测依赖 Android
 * 运行环境（ChatViewModel + ExecutionCoordinator + ProcessLifecycle），不能在
 * 纯 JVM 单测中实例化。它们通过本接口委托：
 *
 * - **生产**：由 ChatViewModel 接入层（[RealRuntimePort.chatViewModelSource] 或
 *   未来的生产装配工厂）实现，直连真实 provider 链 / 工具注册表 / shell 管线；
 * - **测试**：由 [ScenarioBehaviorSource] 实现，按 [com.rikkaminis.app.harness.contract.FaultScenario]
 *   脚本返回确定性结果（与 T4-B `ScenarioRuntimePort` 同构），使 F01-F14
 *   能在不依赖网络/沙箱的前提下驱动真实槽位与真实 trace。
 *
 * 本接口的职责边界 = `AgentRuntimePort` 中"不能从生产组件直接取到"的部分。
 */
interface RuntimeBehaviorSource {

    /** 调用 provider（fallback 链第 [attemptIndex] 个成员）。 */
    suspend fun callProvider(attemptIndex: Int): com.rikkaminis.app.harness.adapter.ProviderCallResult

    /** 执行工具。 */
    suspend fun executeTool(toolName: String): com.rikkaminis.app.harness.adapter.ToolCallResult

    /** 执行 shell 命令。 */
    suspend fun runShell(command: String): com.rikkaminis.app.harness.adapter.ShellCallResult

    /** 终态必需持久化。true=成功；false=失败（此后不得进入 Succeeded）。 */
    suspend fun persist(mark: PersistenceMark): Boolean

    /** 用户是否已取消（provider/tool 调用间隙轮询）。 */
    fun isUserCancelled(): Boolean

    /** 进程是否已死亡/被回收（F14 与重启恢复）。 */
    fun isProcessDead(): Boolean
}