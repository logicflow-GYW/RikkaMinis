package com.rikkaminis.app.harness.adapter.real

import com.rikkaminis.app.service.SessionSlotController

/**
 * [T7-real-runtime] 真实槽位运行时 —— 包装 [SessionSlotController]（T1 生产类）
 * 为 [com.rikkaminis.app.harness.adapter.AgentRuntimePort] 的 acquire/release 语义。
 *
 * ## 映射
 *
 * | AgentRuntimePort | SessionSlotController | 语义 |
 * |---|---|---|
 * | `acquireSlot(runId) → Boolean` | `acquire(runId) → Acquired \| Queued \| Duplicate` | 仅 Acquired 返回 true（立即获得）；Queued/Duplicate 返回 false（排队/拒绝） |
 * | `releaseSlot(runId)` | `release(runId) → String?` | 幂等释放（release 对不存在的 runId 返回 null = no-op） |
 * | `isActive(runId) → Boolean` | `isActive(runId)` | 用于调试/断言 |
 *
 * ## 设计说明
 *
 * 与 T4-A `FakeSessionSlots`（F10 专用）的区别：
 * - `FakeSessionSlots` 是纯计数 + list，不保证并发安全，用于确定性的单线程场景测试；
 * - 本类使用生产 `SessionSlotController`（T1 核心交付），所有操作在同一个
 *   `synchronized(this)` 区间内，**并发安全**，保证 active ≤ maxConcurrent 不变式。
 *
 * 注意：`AgentRuntimePort.acquireSlot` 是同步 Boolean，而 `SessionSlotController`
 * 的 `acquire` 如果返回 `Queued` 不会异步提升——提升由后续 `release` 触发。
 * 当前骨架在 acquire 返回 false 时直接 FAILED（"slot denied"），不等待排队。
 * F10 并发槽位测试走专用 `FakeSessionSlots` 路径。
 */
class RealSlotRuntime(
    val controller: SessionSlotController,
) {
    /** 尝试获取槽位。true=立即获得，false=排队/拒绝。 */
    fun acquire(runId: String): Boolean = when (controller.acquire(runId)) {
        is SessionSlotController.AcquireOutcome.Acquired -> true
        is SessionSlotController.AcquireOutcome.Queued -> false
        is SessionSlotController.AcquireOutcome.Duplicate -> false
    }

    /** 释放槽位（幂等）。 */
    fun release(runId: String) {
        controller.release(runId)
    }

    /** 是否为活跃 run。 */
    fun isActive(runId: String): Boolean = controller.isActive(runId)

    /** 当前槽位快照。 */
    fun snapshot(): SessionSlotController.Snapshot = controller.snapshot()
}