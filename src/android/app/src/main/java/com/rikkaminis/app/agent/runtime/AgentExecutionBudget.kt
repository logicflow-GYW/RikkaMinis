package com.rikkaminis.app.agent.runtime

/**
 * T2 — 贯穿整轮 Agent Run 的共享资源预算（纯 JVM，无 Android 依赖）。
 *
 * 把 turn、provider attempt（retry/fallback 共用）、tool call、shell command、
 * compact 和 child/subagent 的资源消耗统一到一个父预算。
 *
 * 设计规则（见 docs/stability/runtime-contract.md §5 与蓝图 §T2）：
 *  1. 预算不会出现负数（计数消耗到上限即拒绝，snapshot 恒 >= 0）。
 *  2. 失败的预留不改变预算（Denied 不产生任何副作用）。
 *  3. 预留后取消可释放"尚未使用的预留"；已消耗计数不可回退。
 *  4. child/subagent 只能从 parent 剩余预算中获得配额（token 预算启用时强制）。
 *  5. child 不能创建无限 child（child 的 token 池与各计数均有限，天然受限）。
 *  6. deadline 使用单调时间（注入 clock，默认 [System.nanoTime]），不用 wall clock。
 *  7. [maxEstimatedTokens] == null 表示 provider 无法提供可靠 token 计数：
 *     token 维度的 consume 返回 Allowed 但不记账，snapshot 保持 null —— 不伪造精确值。
 *  8. budget exhaustion 是明确原因（[BudgetExhaustedReason]），不混用普通 provider error。
 *
 * 线程安全：所有可变状态在内部锁内更新，检查与增量原子完成，并发调用不会双重成功。
 *
 * Phase A：本类只产生 [BudgetDecision] 与 [BudgetSnapshot]，不改变任何生产行为；
 *          T7 以 adapter 接入后按 advisory → enforced 分阶段启用（见启用说明）。
 */
class AgentExecutionBudget(
    val startedAtMonotonicMs: Long,
    val deadlineMonotonicMs: Long,
    val maxTurns: Int,
    val maxProviderAttempts: Int,
    val maxToolCalls: Int,
    val maxShellCommands: Int,
    val maxCompactionCalls: Int,
    val maxConcurrentTools: Int,
    val maxEstimatedTokens: Long?,
    private val monotonicClock: () -> Long = DEFAULT_MONOTONIC_CLOCK,
) {

    init {
        require(deadlineMonotonicMs >= startedAtMonotonicMs) {
            "deadlineMonotonicMs ($deadlineMonotonicMs) must not precede startedAtMonotonicMs ($startedAtMonotonicMs)"
        }
        require(maxTurns >= 0) { "maxTurns must be >= 0, was $maxTurns" }
        require(maxProviderAttempts >= 0) { "maxProviderAttempts must be >= 0, was $maxProviderAttempts" }
        require(maxToolCalls >= 0) { "maxToolCalls must be >= 0, was $maxToolCalls" }
        require(maxShellCommands >= 0) { "maxShellCommands must be >= 0, was $maxShellCommands" }
        require(maxCompactionCalls >= 0) { "maxCompactionCalls must be >= 0, was $maxCompactionCalls" }
        require(maxConcurrentTools >= 0) { "maxConcurrentTools must be >= 0, was $maxConcurrentTools" }
        require(maxEstimatedTokens == null || maxEstimatedTokens >= 0) {
            "maxEstimatedTokens must be null or >= 0, was $maxEstimatedTokens"
        }
    }

    private val lock = Any()

    private var turnsUsed = 0
    private var providerAttemptsUsed = 0
    private var toolCallsUsed = 0
    private var shellCommandsUsed = 0
    private var compactionCallsUsed = 0
    private var concurrentToolsActive = 0
    private var estimatedTokensUsed: Long? = if (maxEstimatedTokens != null) 0L else null
    // null = token 预算未启用 / 未知；非 null 时初始为 0L
    private var reservedChildTokens = 0L // 已预留给 child 但尚未消耗

    // ─── 计数消耗（不可回退） ───────────────────────────────────────────────

    /** 消耗一个 agent turn。 */
    fun consumeTurn(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (turnsUsed >= maxTurns) return BudgetDecision.Denied(BudgetExhaustedReason.TURN_LIMIT)
        turnsUsed++
        BudgetDecision.Allowed
    }

    /** 消耗一次 provider attempt（retry / fallback 共用同一计数）。 */
    fun consumeProviderAttempt(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (providerAttemptsUsed >= maxProviderAttempts) {
            return BudgetDecision.Denied(BudgetExhaustedReason.PROVIDER_ATTEMPT_LIMIT)
        }
        providerAttemptsUsed++
        BudgetDecision.Allowed
    }

    /** 消耗一次 tool call（总计数，不可回退；并发槽位见 [tryAcquireToolSlot]）。 */
    fun consumeToolCall(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (toolCallsUsed >= maxToolCalls) return BudgetDecision.Denied(BudgetExhaustedReason.TOOL_CALL_LIMIT)
        toolCallsUsed++
        BudgetDecision.Allowed
    }

    /** 消耗一次 shell command（总计数，不可回退）。 */
    fun consumeShellCommand(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (shellCommandsUsed >= maxShellCommands) {
            return BudgetDecision.Denied(BudgetExhaustedReason.SHELL_COMMAND_LIMIT)
        }
        shellCommandsUsed++
        BudgetDecision.Allowed
    }

    /** 消耗一次 compaction（LLM 压缩调用，有成本）。 */
    fun consumeCompaction(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (compactionCallsUsed >= maxCompactionCalls) {
            return BudgetDecision.Denied(BudgetExhaustedReason.COMPACTION_CALL_LIMIT)
        }
        compactionCallsUsed++
        BudgetDecision.Allowed
    }

    /**
     * 消耗已知的 token 用量。仅在 [maxEstimatedTokens] 非 null 时检查并记账；
     * 为 null（token 计数不可靠）时返回 Allowed 且不记账，snapshot 保持 null，不伪造精确值。
     */
    fun consumeEstimatedTokens(tokens: Long): BudgetDecision {
        require(tokens >= 0) { "token consumption cannot be negative: $tokens" }
        return synchronized(lock) {
            if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
            val cap = maxEstimatedTokens ?: return BudgetDecision.Allowed
            val used = estimatedTokensUsed ?: 0L
            if (used >= cap || tokens > cap - used) {
                return BudgetDecision.Denied(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED)
            }
            estimatedTokensUsed = used + tokens
            BudgetDecision.Allowed
        }
    }

    // ─── 可释放预留 ─────────────────────────────────────────────────────────

    /**
     * 预留一个并发工具槽位（可释放的 lease）。成功后才真正占槽；
     * 失败不改变任何状态。执行结束或取消时调用 [releaseToolSlot]。
     */
    fun tryAcquireToolSlot(): BudgetDecision = synchronized(lock) {
        if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
        if (concurrentToolsActive >= maxConcurrentTools) {
            return BudgetDecision.Denied(BudgetExhaustedReason.CONCURRENT_TOOLS_LIMIT)
        }
        concurrentToolsActive++
        BudgetDecision.Allowed
    }

    /** 释放一个并发工具槽位。幂等：重复释放被 clamp 到 0，不会释放"别人的"槽位。 */
    fun releaseToolSlot(): Unit = synchronized(lock) {
        concurrentToolsActive = maxOf(0, concurrentToolsActive - 1)
    }

    /**
     * 为 child/subagent 预留 token 配额。child 只能从 parent 剩余预算中获得配额：
     *   remaining = cap - used - alreadyReserved
     * [maxEstimatedTokens] 为 null 时不做配额审计（token 不可靠，不得伪造），返回 Allowed。
     * 预留失败不改变任何状态。
     */
    fun tryReserveChildBudget(tokens: Long): BudgetDecision {
        require(tokens >= 0) { "child budget reservation cannot be negative: $tokens" }
        return synchronized(lock) {
            if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
            val cap = maxEstimatedTokens ?: return BudgetDecision.Allowed
            val used = estimatedTokensUsed ?: 0L
            // 不变量：used + reservedChildTokens <= cap 恒成立（见 consumeChildTokens/releaseChildBudget），不会溢出
            val remainingForChildren = cap - used - reservedChildTokens
            if (tokens > remainingForChildren) {
                return BudgetDecision.Denied(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED)
            }
            reservedChildTokens += tokens
            BudgetDecision.Allowed
        }
    }

    /**
     * 释放尚未消耗的 child 预留。只回退"预留未用"部分，已消耗计数不受影响。
     * 幂等：释放量被 clamp 到当前预留，不会把预留变成负数。
     */
    fun releaseChildBudget(tokens: Long): Unit {
        require(tokens >= 0) { "child budget release cannot be negative: $tokens" }
        synchronized(lock) {
            reservedChildTokens = maxOf(0L, reservedChildTokens - tokens)
        }
    }

    /**
     * child 报告实际 token 消耗：从预留中扣减并计入 parent 总账。
     * 消耗不能超过预留（child 只从 parent 剩余获得配额）；失败不改变任何状态。
     */
    fun consumeChildTokens(tokens: Long): BudgetDecision {
        require(tokens >= 0) { "child token consumption cannot be negative: $tokens" }
        return synchronized(lock) {
            if (isExpiredLocked()) return BudgetDecision.Denied(BudgetExhaustedReason.DEADLINE_EXPIRED)
            val cap = maxEstimatedTokens ?: return BudgetDecision.Allowed
            val used = estimatedTokensUsed ?: 0L
            if (tokens > reservedChildTokens || tokens > cap - used) {
                return BudgetDecision.Denied(BudgetExhaustedReason.TOKEN_BUDGET_EXCEEDED)
            }
            reservedChildTokens -= tokens
            estimatedTokensUsed = used + tokens
            BudgetDecision.Allowed
        }
    }

    // ─── 查询 ───────────────────────────────────────────────────────────────

    /** 已用维度快照。estimatedTokensUsed == null 表示 token 计数未知/未启用。 */
    fun snapshot(): BudgetSnapshot = synchronized(lock) {
        BudgetSnapshot(
            turnsUsed = turnsUsed,
            providerAttemptsUsed = providerAttemptsUsed,
            toolCallsUsed = toolCallsUsed,
            shellCommandsUsed = shellCommandsUsed,
            compactionCallsUsed = compactionCallsUsed,
            concurrentToolsActive = concurrentToolsActive,
            estimatedTokensUsed = estimatedTokensUsed,
            reservedChildTokens = reservedChildTokens,
            isExpired = isExpiredLocked(),
        )
    }

    /** 剩余维度视图（供 T7 adapter 决策与 trace 使用）。 */
    fun remaining(): RemainingBudget = synchronized(lock) {
        val now = monotonicClock()
        RemainingBudget(
            turnsRemaining = maxOf(0, maxTurns - turnsUsed),
            providerAttemptsRemaining = maxOf(0, maxProviderAttempts - providerAttemptsUsed),
            toolCallsRemaining = maxOf(0, maxToolCalls - toolCallsUsed),
            shellCommandsRemaining = maxOf(0, maxShellCommands - shellCommandsUsed),
            compactionCallsRemaining = maxOf(0, maxCompactionCalls - compactionCallsUsed),
            concurrentToolSlotsRemaining = maxOf(0, maxConcurrentTools - concurrentToolsActive),
            estimatedTokensRemaining = maxEstimatedTokens?.let { cap ->
                maxOf(0L, cap - (estimatedTokensUsed ?: 0L) - reservedChildTokens)
            },
            millisRemaining = if (now >= deadlineMonotonicMs) 0L else deadlineMonotonicMs - now,
            isExpired = now >= deadlineMonotonicMs,
        )
    }

    /** 是否已过 deadline。用注入 clock。 */
    fun isExpired(): Boolean = synchronized(lock) { isExpiredLocked() }

    /**
     * 以给定单调时间判断是否过期。直接比较（非差值），对 Long 溢出/回拨安全：
     * 即使 now 或 deadline 接近 Long.MIN_VALUE / Long.MAX_VALUE，也不会因相减溢出而误判。
     */
    fun isExpired(nowMs: Long): Boolean = synchronized(lock) { nowMs >= deadlineMonotonicMs }

    private fun isExpiredLocked(): Boolean = monotonicClock() >= deadlineMonotonicMs

    companion object {
        /**
         * 默认单调时钟：JVM 的 nanoTime 单调（不受 wall clock 调整影响），换算为毫秒。
         * 生产接入（T7）可替换为 SystemClock.elapsedRealtime() 等单调源。
         */
        val DEFAULT_MONOTONIC_CLOCK: () -> Long = { System.nanoTime() / 1_000_000L }
    }
}

/** 一次预算消耗/预留决策。Allowed 表示成功且已记账；Denied 表示被拒绝且未改变任何状态。 */
sealed class BudgetDecision {
    data object Allowed : BudgetDecision()

    data class Denied(val reason: BudgetExhaustedReason) : BudgetDecision()
}

/** 预算耗尽的明确原因。任何 Denied 都必须能归因到其中一个原因，不混用普通 provider error。 */
enum class BudgetExhaustedReason {
    TURN_LIMIT,
    PROVIDER_ATTEMPT_LIMIT,
    TOOL_CALL_LIMIT,
    SHELL_COMMAND_LIMIT,
    COMPACTION_CALL_LIMIT,
    CONCURRENT_TOOLS_LIMIT,
    TOKEN_BUDGET_EXCEEDED,
    DEADLINE_EXPIRED,
}

/** 已用维度快照。estimatedTokensUsed == null 表示 token 计数未知/未启用（不得当作精确值）。 */
data class BudgetSnapshot(
    val turnsUsed: Int,
    val providerAttemptsUsed: Int,
    val toolCallsUsed: Int,
    val shellCommandsUsed: Int,
    val compactionCallsUsed: Int,
    val concurrentToolsActive: Int,
    val estimatedTokensUsed: Long?,
    val reservedChildTokens: Long,
    val isExpired: Boolean,
)

/** 剩余维度视图。estimatedTokensRemaining == null 表示 token 预算未启用。 */
data class RemainingBudget(
    val turnsRemaining: Int,
    val providerAttemptsRemaining: Int,
    val toolCallsRemaining: Int,
    val shellCommandsRemaining: Int,
    val compactionCallsRemaining: Int,
    val concurrentToolSlotsRemaining: Int,
    val estimatedTokensRemaining: Long?,
    val millisRemaining: Long,
    val isExpired: Boolean,
)
