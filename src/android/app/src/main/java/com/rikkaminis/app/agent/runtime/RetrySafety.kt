package com.rikkaminis.app.agent.runtime

/**
 * [T3-retry-side-effects] 自动重试副作用等级。
 *
 * 所有工具和 shell 操作必须归入以下等级之一。等级只允许由受信任的
 * Kotlin 调用点、内部工具注册表或系统内建 adapter 指定——不能接受
 * LLM 在工具参数里自报安全等级后直接信任。
 *
 * 透明自动重试的含义（蓝图 4.4 节）：
 * - [READ_ONLY]：可以，在预算内；
 * - [IDEMPOTENT_WRITE]：只有存在幂等键或执行后校验时可以；
 * - [NON_IDEMPOTENT_WRITE]：不可以透明重试；
 * - [UNKNOWN]：默认不可以。命令超时或 shell 在返回结果前死亡时，
 *   正确结果不是"重跑"，而是 `OutcomeUnknown → 检查状态 → 决定恢复/报告`。
 */
enum class RetrySafety {
    /** 只读、无外部副作用。可以透明自动重试（在预算内）。 */
    READ_ONLY,

    /**
     * 重复执行结果可证明相同（确定目标 + 可重复设置 + 有幂等键或执行后校验）。
     * 只有存在幂等键或执行后校验时才允许透明重试。
     */
    IDEMPOTENT_WRITE,

    /** 重复执行可能重复副作用（append/发送/创建/删除/提交/发布/支付等）。不可以透明重试。 */
    NON_IDEMPOTENT_WRITE,

    /** 系统无法证明安全性。默认不可以透明重试。 */
    UNKNOWN,
    ;

    /**
     * 保守度排序，用于调用方安全声明降级规则：
     * `READ_ONLY(0) < IDEMPOTENT_WRITE(1) < NON_IDEMPOTENT_WRITE(2) < UNKNOWN(3)`
     */
    val conservativeness: Int
        get() = when (this) {
            READ_ONLY -> 0
            IDEMPOTENT_WRITE -> 1
            NON_IDEMPOTENT_WRITE -> 2
            UNKNOWN -> 3
        }

    /**
     * 应用调用方的安全声明（若未来允许 shell 调用方提供安全提示）。
     *
     * 该提示**只能降低自动化权限，不能提升**：`UNKNOWN` 不能被提升为
     * 更安全的等级；提升必须由应用侧分类器和验证器共同决定。因此：
     * - callerClaim 比注册表等级更保守 → 取 callerClaim（降级）；
     * - callerClaim 与注册表同级或更乐观 → 保持注册表等级（拒绝提升）。
     */
    fun applyCallerClaim(callerClaim: RetrySafety?): RetrySafety {
        if (callerClaim == null) return this
        return if (callerClaim.conservativeness > this.conservativeness) callerClaim else this
    }
}

/**
 * 重试决策结果（蓝图 T3 模型）。
 *
 * 与 `RetrySafety` 分离：等级描述"能不能重试"，结果描述"这
 * 一次失败后应该怎么做"。
 */
enum class RetryOutcome {
    /** 可以透明自动重试（在预算内）。 */
    SafeToRetry,

    /** 必须先验证副作用是否已发生，再决定重试或报告。 */
    MustVerifyFirst,

    /**
     * 结果未知（可能已发生副作用），不得透明重跑。
     * 应向上层返回"结果未知"语义，由上层检查状态后决定恢复或报告。
     */
    OutcomeUnknown,

    /** 明确不重试（命令已执行且失败、预算耗尽、用户取消等）。 */
    DoNotRetry,
}
