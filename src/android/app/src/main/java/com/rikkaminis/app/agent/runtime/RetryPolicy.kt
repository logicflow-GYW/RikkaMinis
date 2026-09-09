package com.rikkaminis.app.agent.runtime

/**
 * [T3-retry-side-effects] 命令失败形态分类。
 *
 * 用于把底层信号（exitCode / shellAlive / truncated）映射成语义明确的
 * 失败形态，再交给 [RetryPolicy.decideRetry] 决策。核心原则：
 * **不把 `exitCode != 0` 简化为"没有执行"，不把输出截断误判为命令未执行。**
 */
enum class CommandFailureKind {
    /**
     * shell 进程死亡（exitCode == -1，PersistentShell.readLoop 进程退出）。
     * 命令可能根本没执行，也可能已执行但结果未返回。
     */
    SHELL_DIED,

    /**
     * 命令超时（exitCode == 124）。可能已部分执行，可能留下僵尸进程。
     */
    TIMEOUT,

    /**
     * 输出被截断但 shell 仍存活。命令**已执行**（副作用已发生），
     * 只是输出不完整——不得误判为"命令未执行"。
     */
    OUTPUT_TRUNCATED,

    /**
     * 正常退出但 exitCode != 0，shell 存活。命令**已执行并失败**
     * （脚本错误、命令不存在等），不是基础设施故障。
     */
    NON_ZERO_EXIT,

    /**
     * 结果已成功但返回丢失（shell 在命令成功后死亡、结束 marker 丢失等）。
     * 副作用可能已发生，且没有可靠的返回结果——状态检查优先于重跑。
     */
    RESULT_LOST,
}

/**
 * [T3-retry-side-effects] 纯逻辑重试决策器（无 Android 依赖，JVM 可测）。
 *
 * 决策矩阵（蓝图 T3 测试矩阵 + 4.4 节契约）：
 *
 * | safety / failure | SHELL_DIED / TIMEOUT | OUTPUT_TRUNCATED / RESULT_LOST | NON_ZERO_EXIT |
 * |---|---|---|---|
 * | READ_ONLY | SafeToRetry（预算内） | SafeToRetry（预算内） | DoNotRetry |
 * | IDEMPOTENT_WRITE | 有验证→SafeToRetry；无验证→MustVerifyFirst | 有验证→MustVerifyFirst；无验证→OutcomeUnknown | DoNotRetry |
 * | NON_IDEMPOTENT_WRITE | OutcomeUnknown | OutcomeUnknown | DoNotRetry |
 * | UNKNOWN | OutcomeUnknown | OutcomeUnknown | DoNotRetry |
 *
 * 预算耗尽（attempt >= maxRetries）时：
 * - READ_ONLY → DoNotRetry（只读，可直接按失败处理）；
 * - 其它等级 → OutcomeUnknown（不能伪装成功，必须向上层报告结果未知）。
 */
object RetryPolicy {

    /**
     * 决定这次失败后是否/如何重试。
     *
     * @param safety 副作用等级（来自受信任注册表或显式调用点声明）。
     * @param failure 失败形态。
     * @param attempt 当前已尝试次数（从 1 开始；0 表示前置调用，按"还有预算"处理）。
     * @param maxRetries 最多允许的总尝试次数（默认 2，与 ExecutionCoordinator.MAX_AUTO_RETRIES 对齐）。
     * @param hasVerification 是否存在幂等键或执行后校验手段。
     */
    fun decideRetry(
        safety: RetrySafety,
        failure: CommandFailureKind,
        attempt: Int,
        maxRetries: Int = 2,
        hasVerification: Boolean = false,
    ): RetryOutcome {
        val retriesLeft = attempt < maxRetries
        return when (safety) {
            RetrySafety.READ_ONLY -> when (failure) {
                // 命令已执行并失败（脚本错误）——重跑只会得到同样的失败。
                CommandFailureKind.NON_ZERO_EXIT -> RetryOutcome.DoNotRetry
                // 只读命令重跑无副作用：shell 死亡/超时/截断/结果丢失都可安全重试。
                else -> if (retriesLeft) RetryOutcome.SafeToRetry else RetryOutcome.DoNotRetry
            }

            RetrySafety.IDEMPOTENT_WRITE -> when (failure) {
                CommandFailureKind.NON_ZERO_EXIT -> RetryOutcome.DoNotRetry
                // shell 死亡/超时：命令可能根本没执行。有验证手段 → 重跑+校验；
                // 无验证手段 → 无法证明未执行，必须请求验证。
                CommandFailureKind.SHELL_DIED, CommandFailureKind.TIMEOUT ->
                    if (!retriesLeft) RetryOutcome.OutcomeUnknown
                    else if (hasVerification) RetryOutcome.SafeToRetry
                    else RetryOutcome.MustVerifyFirst
                // 截断/结果丢失：命令几乎确定已执行（副作用已发生），
                // 状态检查优先于重跑——先验证，再决定。
                CommandFailureKind.OUTPUT_TRUNCATED, CommandFailureKind.RESULT_LOST ->
                    if (hasVerification) RetryOutcome.MustVerifyFirst
                    else RetryOutcome.OutcomeUnknown
            }

            RetrySafety.NON_IDEMPOTENT_WRITE -> when (failure) {
                CommandFailureKind.NON_ZERO_EXIT -> RetryOutcome.DoNotRetry
                // 不允许透明重跑：副作用可能已发生，返回"结果未知"。
                else -> RetryOutcome.OutcomeUnknown
            }

            RetrySafety.UNKNOWN -> when (failure) {
                CommandFailureKind.NON_ZERO_EXIT -> RetryOutcome.DoNotRetry
                // 系统无法证明安全性：不透明重跑，返回"结果未知"。
                else -> RetryOutcome.OutcomeUnknown
            }
        }
    }
}
