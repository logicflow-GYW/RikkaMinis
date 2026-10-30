package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LOOP-1030 R3] 压缩效果口径的纯函数测试。期望值全部字面量，不从常量推导
 * （JVM 装置自指陷阱：期望跟着常量走会伪装成 PASS）。
 */
class CompactEffectTest {

    private fun msg(id: String?, asciiChars: Int) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = "x".repeat(asciiChars),
        dbMessageId = id,
    )

    // ---- verdict：折叠了 ≠ 变短了 -------------------------------------------------

    @Test
    fun `a compact that saves 174 tokens does not unlock the trim skip`() {
        // 10-29 的典型无效压缩：只折叠 1 条消息，中位收益 174 token。
        assertEquals(
            CompactEffect.Verdict.FOLDED_NO_GAIN,
            CompactEffect.verdict(compacted = true, beforeTokens = 10_000L, afterTokens = 9_826L),
        )
    }

    @Test
    fun `gain of exactly the minimum unlocks the trim skip`() {
        assertEquals(
            CompactEffect.Verdict.FOLDED_AND_SHRANK,
            CompactEffect.verdict(compacted = true, beforeTokens = 10_000L, afterTokens = 9_488L),
        )
    }

    @Test
    fun `one token short of the minimum stays closed`() {
        assertEquals(
            CompactEffect.Verdict.FOLDED_NO_GAIN,
            CompactEffect.verdict(compacted = true, beforeTokens = 10_000L, afterTokens = 9_489L),
        )
    }

    @Test
    fun `a compact that grew the projection stays closed`() {
        // 摘要比折叠掉的内容还长（10-29：+1190 字摘要换 174 token）。
        assertEquals(
            CompactEffect.Verdict.FOLDED_NO_GAIN,
            CompactEffect.verdict(compacted = true, beforeTokens = 9_000L, afterTokens = 9_200L),
        )
    }

    @Test
    fun `nothing folded stays closed even when the projection looks smaller`() {
        assertEquals(
            CompactEffect.Verdict.NOTHING_FOLDED,
            CompactEffect.verdict(compacted = false, beforeTokens = 9_000L, afterTokens = 1_000L),
        )
    }

    // ---- projectedTokens：口径 = 摘要 + 锚点后尾部 --------------------------------

    @Test
    fun `projection counts summary plus the tail after the anchor`() {
        // 4 条 ASCII 消息，每条 400 字符 = 100 token（chars/4）。
        val history = listOf(msg("m1", 400), msg("m2", 400), msg("m3", 400), msg("m4", 400))
        val summary = "s".repeat(400) // 100 token
        // 锚点 m2 → 尾部 = m3 + m4 = 200 token；加摘要 100 = 300。
        assertEquals(300L, CompactEffect.projectedTokens(history, summary, "m2"))
    }

    @Test
    fun `empty summary or missing anchor degrades to the whole history`() {
        val history = listOf(msg("m1", 400), msg("m2", 400))
        assertEquals(200L, CompactEffect.projectedTokens(history, null, null))
        assertEquals(200L, CompactEffect.projectedTokens(history, "", "gone")) // 锚点不在历史 → 全量
        assertEquals(201L, CompactEffect.projectedTokens(history, "s".repeat(4), "gone"))
    }

    @Test
    fun `anchor resolves by the last matching db id`() {
        val history = listOf(msg("m1", 400), msg("m2", 400), msg("m3", 400))
        // 锚点 m2 → 只剩 m3 = 100 token。
        assertEquals(100L, CompactEffect.projectedTokens(history, null, "m2"))
    }

    // ---- 端到端形状：一次真实无效压缩 --------------------------------------------

    @Test
    fun `the 10-29 net-loss shape ends in FOLDED_NO_GAIN`() {
        // 折叠 1 条 700 字符（175 token）消息，摘要 1190 字符（297 token），
        // 尾部 750 + 1000 字符（437 token）→ before = 1175，after = 1297（净长 122）。
        // 即「压缩成功但上下文反而长了」——旧判据会在这时关掉硬 trim。
        val history = listOf(
            msg("m1", 700),
            msg("m2", 3_000),
            msg("m3", 1_000),
        )
        val before = CompactEffect.projectedTokens(history, null, null) // 首次压缩：无摘要、无锚点 = 全量
        val after = CompactEffect.projectedTokens(history, "s".repeat(1_190), "m1") // 297 + 1000 = 1297
        assertEquals(1_175L, before)
        assertEquals(1_297L, after)
        assertEquals(
            CompactEffect.Verdict.FOLDED_NO_GAIN,
            CompactEffect.verdict(compacted = true, beforeTokens = before, afterTokens = after),
        )
    }
}
