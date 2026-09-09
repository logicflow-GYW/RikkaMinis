package com.rikkaminis.app.harness.fakes

import com.rikkaminis.app.harness.contract.ShellBehavior
import com.rikkaminis.app.harness.contract.ShellScript
import com.rikkaminis.app.harness.contract.SideEffectLevel

/**
 * 可脚本化的 FakeShell。
 *
 * 记录执行次数和副作用计数。runner 根据脚本行为决定执行结果。
 */
class FakeShell(private val script: ShellScript) {
    var executionCount: Int = 0
        private set
    var sideEffectCount: Int = 0
        private set
    var duplicateSideEffectCount: Int = 0
        private set

    private val performedOperations = mutableSetOf<String>()

    /** 执行一次 shell 命令，返回结果。 */
    fun execute(operationId: String): ShellExecutionResult {
        executionCount++

        when (script.behavior) {
            ShellBehavior.SUCCESS -> {
                return ShellExecutionResult(true, truncated = false, sideEffectPerformed = false)
            }
            ShellBehavior.SHELL_DEATH_AFTER_SIDE_EFFECT -> {
                sideEffectCount++
                if (operationId in performedOperations && script.sideEffectLevel != SideEffectLevel.READ_ONLY) {
                    duplicateSideEffectCount++
                }
                performedOperations.add(operationId)
                return ShellExecutionResult(false, shellDied = true, sideEffectPerformed = true, outcomeUnknown = true)
            }
            ShellBehavior.TIMEOUT -> {
                return ShellExecutionResult(false, timedOut = true, sideEffectPerformed = false)
            }
            ShellBehavior.TRUNCATED_OUTPUT -> {
                return ShellExecutionResult(true, truncated = true, sideEffectPerformed = false)
            }
        }
    }

    fun reset() {
        executionCount = 0
        sideEffectCount = 0
        duplicateSideEffectCount = 0
        performedOperations.clear()
    }
}

data class ShellExecutionResult(
    val success: Boolean,
    val truncated: Boolean = false,
    val shellDied: Boolean = false,
    val timedOut: Boolean = false,
    val sideEffectPerformed: Boolean = false,
    val outcomeUnknown: Boolean = false,
)