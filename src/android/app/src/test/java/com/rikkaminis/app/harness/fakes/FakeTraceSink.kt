package com.rikkaminis.app.harness.fakes

import com.rikkaminis.app.harness.contract.HarnessTraceEvent
import com.rikkaminis.app.harness.contract.TraceEventType

/**
 * FakeTraceSink：收集所有 trace 事件，可选抛出异常模拟 trace 写入失败。
 *
 * 蓝图 T6 要求：trace 写失败不能阻断主执行，但必须有计数或日志表示丢失。
 * 这里用 failOnAppend 模拟 trace 写入失败场景。
 */
class FakeTraceSink(
    private val failOnAppend: Boolean = false,
) {
    val events = mutableListOf<HarnessTraceEvent>()
    var dropCount: Int = 0
        private set

    fun emit(type: TraceEventType, atMs: Long, detail: String? = null): Boolean {
        if (failOnAppend) {
            dropCount++
            return false
        }
        events.add(HarnessTraceEvent(type, atMs, detail))
        return true
    }

    /** 获取指定类型的事件计数。 */
    fun count(type: TraceEventType): Int = events.count { it.type == type }

    fun reset() {
        events.clear()
        dropCount = 0
    }
}