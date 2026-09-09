package com.rikkaminis.app.harness.fakes

/**
 * 确定性会话槽位控制器（F10 场景用）。
 *
 * 纯逻辑，无协程、无 StateFlow。支持 FIFO 排队和取消等待者。
 * 真正的并发正确性由 T1 的 SessionSlotController 负责。
 * 本 fake 用于验证场景协议中的 active ≤ limit、FIFO 顺序、取消不复活等语义。
 */
class FakeSessionSlots(private val maxConcurrent: Int = 5) {
    private val active = mutableListOf<String>()
    private val waiting = mutableListOf<String>()
    private val released = mutableListOf<String>()

    /** 尝试获取一个槽位。返回 true=立即获得，false=进入 FIFO 排队。 */
    fun acquire(runId: String): Boolean {
        if (active.size < maxConcurrent) {
            active.add(runId)
            return true
        }
        waiting.add(runId)
        return false
    }

    /** 释放一个槽位。如果队列非空，将下一个等待者移入 active。 */
    fun release(runId: String) {
        val idx = active.indexOf(runId)
        if (idx >= 0) {
            active.removeAt(idx)
        }
        released.add(runId)
        if (idx >= 0 && waiting.isNotEmpty()) {
            val next = waiting.removeAt(0)
            active.add(next)
        }
    }

    /** 取消一个等待者（不释放 active 槽位）。 */
    fun cancelWaiting(runId: String): Boolean {
        val idx = waiting.indexOf(runId)
        if (idx >= 0) {
            waiting.removeAt(idx)
            return true
        }
        return false
    }

    fun activeCount(): Int = active.size
    fun waitingCount(): Int = waiting.size
    fun isActive(runId: String): Boolean = runId in active
    fun isWaiting(runId: String): Boolean = runId in waiting
    fun isReleased(runId: String): Boolean = runId in released
    fun snapshot(): Triple<List<String>, List<String>, List<String>> = Triple(active.toList(), waiting.toList(), released.toList())

    /** 获取当前活跃的 FIFO 顺序副本。 */
    fun activeOrder(): List<String> = active.toList()

    /** 获取当前等待的 FIFO 顺序副本。 */
    fun waitingOrder(): List<String> = waiting.toList()

    fun reset() {
        active.clear()
        waiting.clear()
        released.clear()
    }
}