package com.rikkaminis.app.harness.adapter.real

import com.rikkaminis.app.harness.adapter.ProviderCallResult
import com.rikkaminis.app.harness.adapter.ShellCallResult
import com.rikkaminis.app.harness.adapter.ToolCallResult
import com.rikkaminis.app.harness.contract.AttemptResult
import com.rikkaminis.app.harness.contract.AttemptScript
import com.rikkaminis.app.harness.contract.FaultScenario
import com.rikkaminis.app.harness.contract.PersistenceMark
import com.rikkaminis.app.harness.contract.ToolBehavior
import com.rikkaminis.app.harness.contract.ToolCallScript
import kotlinx.coroutines.delay

/**
 * [T7-real-runtime] 场景脚本行为源 —— 按 [FaultScenario] 脚本返回确定性
 * provider/tool/shell/persist/cancel/death 行为。
 *
 * 与 T4-B `ScenarioRuntimePort` 的职责一致（F01-F14 故障注入），但实现为
 * [RuntimeBehaviorSource] 委托，使 [RealRuntimePort] 可以把真实生产组件
 * （SessionSlotController / AgentTraceRecorder）与行为注入解耦。
 *
 * ## 语义对齐
 *
 * 保持 `ScenarioRuntimePort` 的既有语义：
 * - `callProvider` 按 turn/attempt 推进：attemptIdx==0 且当前 turn attempts
 *   耗尽时前进到下一 turn（F05/F13 多 turn 场景）；
 * - `executeTool` 按 toolName 查脚本，SIDE_EFFECT_THEN_NO_RESULT 在 100ms 后
 *   检查 process death（F14）；
 * - `persist` 在 `scenario.persistence.failOnFinalize` 时返回 false（F12）；
 * - 用户取消 / 进程死亡支持**时间型**（userCancelAtMs / processDeathAtMs，
 *   相对 [resetTimer] 时刻）与**即时型**（置位字段）双通道。
 */
class ScenarioBehaviorSource(
    private val scenario: FaultScenario,
    var persistResult: Boolean = true,
    var userCancelled: Boolean = false,
    var processDead: Boolean = false,
) : RuntimeBehaviorSource {

    /** 当前执行到的 turn 索引 */
    private var currentTurn = 0

    /** 当前 turn 内已尝试的 attempt 计数 */
    private var attemptInTurn = 0

    /** 当前 turn 内已消耗的工具脚本索引 */
    private var toolIndex = 0

    /** 场景的当前 attempt 脚本（由 turn 和 attempt 索引定位） */
    private fun currentAttempt(): AttemptScript? {
        if (currentTurn >= scenario.turns.size) return null
        val turn = scenario.turns[currentTurn]
        if (attemptInTurn >= turn.attempts.size) return null
        return turn.attempts[attemptInTurn]
    }

    /** 场景的工具脚本（按 toolName 索引） */
    private fun toolScriptFor(name: String): ToolCallScript? =
        scenario.toolScripts[name]

    override suspend fun callProvider(attemptIndex: Int): ProviderCallResult {
        // 新 turn 开始的信号：adapter 以 attemptIdx==0 进入一个 turn。
        // 若当前 turn 的 attempts 已耗尽，前进到下一 turn（F05/F13 多 turn 场景
        // 第二轮的 SUCCESS 必须来自 turns[1] 而不是被当成"no more attempts"）。
        if (attemptIndex == 0 &&
            currentTurn < scenario.turns.size &&
            attemptInTurn >= scenario.turns[currentTurn].attempts.size
        ) {
            currentTurn++
            attemptInTurn = 0
        }
        val script = currentAttempt()
        attemptInTurn++

        if (script == null) {
            return ProviderCallResult.HardFailure("no more attempts in scenario")
        }

        return when (script.result) {
            AttemptResult.SUCCESS -> ProviderCallResult.Success(
                toolCalls = script.toolCalls,
                finalAnswer = script.finalAnswer,
            )
            AttemptResult.HTTP_429 -> ProviderCallResult.RateLimited(cooldownMs = script.cooldownMs)
            AttemptResult.STREAM_RESET -> ProviderCallResult.StreamReset("scenario script")
            AttemptResult.DROP_AFTER_FIRST_CHUNK -> ProviderCallResult.DroppedAfterFirstChunk()
            AttemptResult.HARD_FAILURE -> ProviderCallResult.HardFailure("scenario script hard failure")
            AttemptResult.LENGTH_FINISH -> ProviderCallResult.LengthFinish("scenario script length finish")
        }
    }

    override suspend fun executeTool(toolName: String): ToolCallResult {
        val script = toolScriptFor(toolName)

        val behavior = script?.behavior ?: ToolBehavior.SUCCESS
        return when (behavior) {
            ToolBehavior.SUCCESS -> ToolCallResult(
                success = true, sideEffectPerformed = false, resultKnown = true,
            )
            ToolBehavior.FAILURE -> ToolCallResult(
                success = false, sideEffectPerformed = false, resultKnown = true,
            )
            ToolBehavior.SIDE_EFFECT_THEN_NO_RESULT -> {
                // F14: 模拟工具执行耗时，并在 delay 后检查 process death
                delay(100)
                // 如果 process death 在工具执行期间发生，工具未完成即中断
                if (isProcessDead()) {
                    ToolCallResult(
                        success = false, sideEffectPerformed = false, resultKnown = true,
                        cancelled = true,
                    )
                } else {
                    ToolCallResult(
                        success = false, sideEffectPerformed = true, resultKnown = false,
                    )
                }
            }
            ToolBehavior.BLOCK_UNTIL_CANCELLED -> ToolCallResult(
                success = false, sideEffectPerformed = true, resultKnown = false,
                cancelled = true,
            )
            ToolBehavior.SPAWN -> ToolCallResult(
                success = false, sideEffectPerformed = false, resultKnown = true,
                spawnRejected = true,
            )
        }
    }

    override suspend fun runShell(command: String): ShellCallResult =
        ShellCallResult(success = true, sideEffectPerformed = false, resultKnown = true)

    override suspend fun persist(mark: PersistenceMark): Boolean {
        // F12: failOnFinalize 场景模拟"收尾持久化失败"
        return if (scenario.persistence.failOnFinalize) false else persistResult
    }

    /** 记录开始时刻（单调时间 ms），用于 userCancelAtMs / processDeathAtMs 检测。
     *  惰性初始化：首次调用 [elapsedMs] 时记录，不需要骨架显式调用 resetTimer。
     *  避免 RealRuntimePort 的 setup 开销影响 F14 的 20ms processDeath 窗口。 */
    private var startAtMs: Long = -1L

    /** 重置计时器 —— 在 turn loop 开始前调用，避免 setup 开销影响 F14 检测 */
    fun resetTimer() {
        startAtMs = System.nanoTime() / 1_000_000L
        currentTurn = 0
        attemptInTurn = 0
        toolIndex = 0
    }

    private fun elapsedMs(): Long {
        if (startAtMs < 0) startAtMs = System.nanoTime() / 1_000_000L
        return (System.nanoTime() / 1_000_000L) - startAtMs
    }

    override fun isUserCancelled(): Boolean =
        userCancelled || (scenario.userCancelAtMs != null && elapsedMs() >= scenario.userCancelAtMs!!)

    override fun isProcessDead(): Boolean =
        processDead || (scenario.processDeathAtMs != null && elapsedMs() >= scenario.processDeathAtMs!!)
}