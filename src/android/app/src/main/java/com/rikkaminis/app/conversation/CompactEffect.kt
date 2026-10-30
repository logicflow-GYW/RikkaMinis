package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.LLMMessage

/**
 * [LOOP-1030 R3] 压缩效果的**判定口径**：折叠了 ≠ 上下文真的变短了。
 *
 * 旧判据（`marker 变了 ‖ summary 变了`）把「压缩事件落地」当成「有效果」：
 * 10-29 的 42 次自动压缩里 13 次（31%）只折叠 1 条消息（中位 174 token），
 * 却照样写摘要（~4.2s / 323 输出 token）、`folded=true`，于是调用方
 * （AgentLoopEngine）**关掉了当轮硬 trim**——历史照旧增长，投影里还多背
 * 一份 1190 字的摘要，净效果是变长。同指标读数其实存在
 * （[ContextCompactor.estimateTailTokens] / [ContextCompactor.estimateTokens]），
 * 只是从来没有人把压缩前后放在一起比。
 *
 * 口径 = **投影体积**：`summary 的 token 估算 + 锚点之后尾部的 token 估算`。
 * 这正是下一次 provider 请求实际会带上的东西（[effectiveAgentHistory] 的
 * summary + tail 投影），锚点不可解析时退化为全量——与消费方的退化策略
 * 一致，所以「量出来的变小」与「真的会变小」同向。
 *
 * 纯函数，JVM 可测。期望值一律字面量，不从常量推导。
 */
object CompactEffect {

    enum class Verdict {
        /** 压缩事件没落地（锚点与摘要都没变）——硬 trim 保持开启。 */
        NOTHING_FOLDED,

        /** 压缩落地了，但投影没有净变小（或小于最低收益）——硬 trim 保持开启。 */
        FOLDED_NO_GAIN,

        /** 压缩落地且投影真的变小——调用方可以跳过当轮硬 trim。 */
        FOLDED_AND_SHRANK,
    }

    /**
     * 「值得为它关掉硬 trim」的最低净收益（token）。
     *
     * 取值依据：10-29 的无效压缩中位收益 ≈174 token（只折叠 1 条消息），
     * 512 = 中位的 ~3 倍，确保这批样本全部落进 FOLDED_NO_GAIN；同时远小于
     * 摘要本身的典型成本（~323 输出 token + 提示词开销），一个只换回来
     * 几百 token 的压缩不值得把硬 trim 这个兜底关掉一轮。
     */
    const val MIN_GAIN_TOKENS = 512L

    /**
     * 投影体积：摘要 token + 锚点之后尾部的 token。
     * 与 [ContextCompactor.estimateTailTokens] 的退化策略一致（锚点空/不可解析 = 全量）。
     */
    fun projectedTokens(history: List<LLMMessage>, summary: String?, anchorDbId: String?): Long =
        ContextCompactor.estimateTokens(summary.orEmpty()) +
            ContextCompactor.estimateTailTokens(history, anchorDbId)

    fun verdict(
        compacted: Boolean,
        beforeTokens: Long,
        afterTokens: Long,
        minGainTokens: Long = MIN_GAIN_TOKENS,
    ): Verdict = when {
        !compacted -> Verdict.NOTHING_FOLDED
        beforeTokens - afterTokens >= minGainTokens -> Verdict.FOLDED_AND_SHRANK
        else -> Verdict.FOLDED_NO_GAIN
    }
}
