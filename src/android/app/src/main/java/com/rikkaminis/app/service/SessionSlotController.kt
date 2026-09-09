package com.rikkaminis.app.service

import java.util.UUID

/**
 * 纯状态会话并发槽位控制器（无 Android / coroutine / Flow 依赖，可 JVM 单测）。
 *
 * 这是 T1（stability/T1-session-concurrency）的核心交付：把"准入 + 取消 + 释放"的
 * 全部状态迁移收敛到一个同步区间内，消除旧 `SessionConcurrencyManager` 的竞态
 * （容量检查与写入分离、Set 按 sessionId 去重掩盖并发 run、waitQueue 手动管理）。
 *
 * 不变量（每个方法在同一个 `synchronized(this)` 区间内完成检查与写入）：
 * 1. `activeRunIds.size <= maxConcurrent` 永远成立。
 * 2. 每个进入 active 的 runId 最终恰好对应一次 [release] 或 [cancel]（后者等价于提前释放槽位）。
 * 3. [release] / [cancel] 幂等：对不存在的 runId 返回 no-op，绝不释放他人的槽位。
 * 4. waiting 严格 FIFO 提升；已从 waiting 移除（取消）的 runId 永远不会再被提升为 active。
 * 5. 同一 runId 重复 [acquire] 返回 [AcquireOutcome.Duplicate]，语义明确，不静默折叠。
 *
 * 本类只管理"槽位"，不感知 sessionId——session 语义由上层 adapter
 * （[SessionConcurrencyManager]）负责；runId 是槽位级唯一标识。
 */
class SessionSlotController(
    val maxConcurrent: Int,
) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be > 0, was $maxConcurrent" }
    }

    private val activeRunIds = ArrayDeque<String>()
    private val waitingRunIds = ArrayDeque<String>()

    /** 生成唯一 run token（默认 UUID；测试可注入确定性生成器）。 */
    var runIdGenerator: () -> String = { UUID.randomUUID().toString() }

    /** 分配一个当前未使用的唯一 runId。 */
    fun newRunId(): String = synchronized(this) {
        var id = runIdGenerator()
        while (id in activeRunIds || id in waitingRunIds) {
            id = runIdGenerator()
        }
        id
    }

    sealed interface AcquireOutcome {
        /** 容量未满，立即获得槽位。 */
        data class Acquired(val runId: String) : AcquireOutcome

        /** 容量已满，已进入 FIFO 等待队列。 */
        data class Queued(val runId: String) : AcquireOutcome

        /** 同一 runId 已存在（active 或 waiting），拒绝重复准入。 */
        data class Duplicate(val runId: String) : AcquireOutcome
    }

    /**
     * 请求一个槽位。容量检查与写入在同一个同步区间内完成，并发到达不可能同时越过容量检查。
     */
    fun acquire(runId: String): AcquireOutcome = synchronized(this) {
        when {
            runId in activeRunIds || runId in waitingRunIds -> AcquireOutcome.Duplicate(runId)
            activeRunIds.size < maxConcurrent -> {
                activeRunIds.addLast(runId)
                AcquireOutcome.Acquired(runId)
            }
            else -> {
                waitingRunIds.addLast(runId)
                AcquireOutcome.Queued(runId)
            }
        }
    }

    sealed interface CancelOutcome {
        /** 从 waiting 移除；不影响任何 active 槽位，也不触发提升。 */
        data class WaitingRemoved(val runId: String) : CancelOutcome

        /** 从 active 移除（释放槽位）；若 waiting 非空，[promotedRunId] 为被提升的队首。 */
        data class ActiveRemoved(val runId: String, val promotedRunId: String?) : CancelOutcome

        /** runId 不存在（幂等 no-op）。 */
        object Noop : CancelOutcome
    }

    /**
     * 取消一个 run：从 waiting 移除（绝不提升），或从 active 移除（等价于释放并提升队首）。
     * 取消与提升互斥在同一同步区间内：已取消的 waiter 不可能再被提升。
     */
    fun cancel(runId: String): CancelOutcome = synchronized(this) {
        if (waitingRunIds.remove(runId)) {
            CancelOutcome.WaitingRemoved(runId)
        } else if (activeRunIds.remove(runId)) {
            CancelOutcome.ActiveRemoved(runId, promoteNextLocked())
        } else {
            CancelOutcome.Noop
        }
    }

    /**
     * 释放一个 active 槽位（幂等：runId 不存在时返回 null 且状态不变）。
     *
     * @return 若 waiting 非空，返回被提升为 active 的队首 runId；否则 null。
     */
    fun release(runId: String): String? = synchronized(this) {
        if (!activeRunIds.remove(runId)) return null
        promoteNextLocked()
    }

    fun isActive(runId: String): Boolean = synchronized(this) { runId in activeRunIds }

    fun isWaiting(runId: String): Boolean = synchronized(this) { runId in waitingRunIds }

    /** 当前状态的不可变快照（测试与观测用）。 */
    fun snapshot(): Snapshot = synchronized(this) {
        Snapshot(
            activeRunIds = activeRunIds.toList(),
            waitingRunIds = waitingRunIds.toList(),
        )
    }

    /** 仅测试用：清空全部状态（生产路径不调用）。 */
    internal fun resetForTesting() {
        synchronized(this) {
            activeRunIds.clear()
            waitingRunIds.clear()
        }
    }

    private fun promoteNextLocked(): String? {
        val next = waitingRunIds.removeFirstOrNull() ?: return null
        activeRunIds.addLast(next)
        return next
    }

    data class Snapshot(
        val activeRunIds: List<String>,
        val waitingRunIds: List<String>,
    ) {
        val activeCount: Int get() = activeRunIds.size
        val waitingCount: Int get() = waitingRunIds.size

        /** 已登记的总 run 数（active + waiting）。 */
        val total: Int get() = activeRunIds.size + waitingRunIds.size
    }
}
