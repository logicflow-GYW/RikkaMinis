package com.rikkaminis.app.harness.adapter

import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.PersistenceMark

/**
 * [T4-B] 测试用 [AgentRuntimePort] 实现 —— 结构测试专用。
 *
 * 不驱动真实 provider/tool/shell（T7 前不存在真实实现），只记录：
 * - 被调用的方法与参数（供断言骨架的调用序列）；
 * - 可编程的行为（slot 拒绝等边界）。
 *
 * T7 完成后此 fake 由真实实现替换，骨架调用序列的断言可原样保留。
 */
class FakeRuntimePort(
    var slotAcquired: Boolean = true,
    var persistResult: Boolean = true,
    var userCancelled: Boolean = false,
    var processDead: Boolean = false,
) : AgentRuntimePort {

    val emitted = mutableListOf<HarnessTraceEvent>()
    val providerCalls = mutableListOf<Int>()
    val toolCalls = mutableListOf<String>()
    val shellCalls = mutableListOf<String>()
    val persistCalls = mutableListOf<PersistenceMark>()
    var acquireCount = 0
    var releaseCount = 0

    override suspend fun callProvider(attemptIndex: Int): ProviderCallResult {
        providerCalls += attemptIndex
        return ProviderCallResult.Success(finalAnswer = true)
    }

    override suspend fun executeTool(toolName: String): ToolCallResult =
        ToolCallResult(success = true, sideEffectPerformed = false, resultKnown = true)

    override suspend fun runShell(command: String): ShellCallResult =
        ShellCallResult(success = true, sideEffectPerformed = false, resultKnown = true)

    override suspend fun persist(mark: PersistenceMark): Boolean {
        persistCalls += mark
        return persistResult
    }

    override fun acquireSlot(runId: String): Boolean {
        acquireCount++
        return slotAcquired
    }

    override fun releaseSlot(runId: String) {
        releaseCount++
    }

    override fun emitTrace(event: HarnessTraceEvent) {
        emitted += event
    }

    override fun isUserCancelled(): Boolean = userCancelled

    override fun isProcessDead(): Boolean = processDead
}
