package com.rikkaminis.app.harness.fakes

import com.rikkaminis.app.harness.contract.CooldownRecord

/**
 * 可脚本化的 FakeProvider。
 *
 * 职责：
 * - 记录 attempt 计数
 * - 记录 429 后的冷却状态
 * - 被 runner 驱动，不自行决定行为（行为由场景脚本控制）
 */
class FakeProvider(
    val providerId: String,
) {
    var attemptCount: Int = 0
        private set
    var cooldownUntilMs: Long? = null
        private set

    /** 记录一次 attempt 开始。 */
    fun recordAttempt() {
        attemptCount++
    }

    /** 记录 429 冷却。 */
    fun markCooldown(nowMs: Long, cooldownMs: Long) {
        cooldownUntilMs = nowMs + cooldownMs
    }

    /** 当前是否在冷却中。 */
    fun isCooling(nowMs: Long): Boolean =
        cooldownUntilMs != null && nowMs < cooldownUntilMs!!

    /** 重置状态（用于多轮测试）。 */
    fun reset() {
        attemptCount = 0
        cooldownUntilMs = null
    }
}