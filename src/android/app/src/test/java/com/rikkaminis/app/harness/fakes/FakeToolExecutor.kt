package com.rikkaminis.app.harness.fakes

import com.rikkaminis.app.harness.contract.SideEffectLevel
import com.rikkaminis.app.harness.contract.ToolCallScript
import com.rikkaminis.app.harness.contract.ToolBehavior

/**
 * 可脚本化的 FakeToolExecutor。
 *
 * 记录：
 * - executionCount = 工具调用次数
 * - sideEffectCount = 实际发生副作用的执行次数
 * - cancelledCount = 被取消的次数
 * - spawnRejectedCount = 递归 spawn 被拒绝的次数
 */
class FakeToolExecutor(
    private val scripts: Map<String, ToolCallScript>,
) {
    private val _executionCount = mutableMapOf<String, Int>()
    var cancelledCount: Int = 0
        private set
    var spawnRejectedCount: Int = 0
        private set
    var sideEffectCount: Int = 0
        private set
    var duplicateSideEffectCount: Int = 0
        private set

    // 记录已执行过的操作 ID（用于检测重复副作用）
    private val performedOperations = mutableSetOf<String>()

    /** 执行一个工具，返回结果。 */
    fun execute(toolName: String, operationId: String): HarnessToolResult {
        val count = _executionCount.getOrDefault(toolName, 0) + 1
        _executionCount[toolName] = count
        val script = scripts[toolName] ?: return HarnessToolResult(
            toolName, true, false, 0
        )

        when (script.behavior) {
            ToolBehavior.SUCCESS -> {
                // 成功执行，无副作用
                return HarnessToolResult(toolName, true, false, 0)
            }
            ToolBehavior.FAILURE -> {
                return HarnessToolResult(toolName, false, false, 0)
            }
            ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT -> {
                sideEffectCount++
                // 检查是否重复执行
                if (operationId in performedOperations && script.sideEffectLevel != SideEffectLevel.READ_ONLY) {
                    duplicateSideEffectCount++
                }
                performedOperations.add(operationId)
                return HarnessToolResult(toolName, false, true, 0, outcomeUnknown = true)
            }
            ToolBehavior.BLOCK_UNTIL_CANCELLED -> {
                return HarnessToolResult(toolName, false, false, 0, cancelled = true)
            }
            ToolBehavior.SPAWN -> {
                if (script.spawnDepth > 0) {
                    spawnRejectedCount++
                    return HarnessToolResult(toolName, false, false, 0, spawnRejected = true)
                }
                // 允许 spawn
                return HarnessToolResult(toolName, true, false, 0)
            }
        }
    }

    /** 获取工具执行次数。 */
    fun totalExecutions(): Int = _executionCount.values.sum()

    /** 重置。 */
    fun reset() {
        _executionCount.clear()
        cancelledCount = 0
        spawnRejectedCount = 0
        sideEffectCount = 0
        duplicateSideEffectCount = 0
        performedOperations.clear()
    }
}

data class HarnessToolResult(
    val toolName: String,
    val success: Boolean,
    val sideEffectPerformed: Boolean,
    val sideEffectCount: Int = 0,
    val cancelled: Boolean = false,
    val outcomeUnknown: Boolean = false,
    val spawnRejected: Boolean = false,
)