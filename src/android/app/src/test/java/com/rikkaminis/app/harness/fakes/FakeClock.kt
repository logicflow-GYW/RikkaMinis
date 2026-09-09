package com.rikkaminis.app.harness.fakes

/**
 * 确定性 FakeClock：单调时间，由 runner 手动推进。
 * 不依赖 System.currentTimeMillis() 或任意真实时钟。
 */
class FakeClock(startMs: Long = 0) {
    private var currentMs: Long = startMs

    fun now(): Long = currentMs

    fun advance(ms: Long) {
        require(ms >= 0) { "advance must be non-negative: $ms" }
        currentMs += ms
    }

    fun set(ms: Long) {
        require(ms >= 0) { "set must be non-negative: $ms" }
        currentMs = ms
    }
}