package com.rikkaminis.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LOOP-1030 R3] 接线形状：`maybeAutoCompactInLoop` 必须按**测出来的净收益**
 * 放行硬 trim 的跳过，而不是按「压缩事件落地」这个布尔。
 *
 * 钉结构而不是钉计数：判据是「返回值由 verdict 决定」「压缩前/后两次投影都
 * 基于各自的锚点与摘要」，不是「某符号出现 N 次」——后者对「新增一条绕过
 * 路径」零敏感（R5-F3 的教训）。
 */
class CompactEffectWiringTest {

    private fun region(): String = readRepoFile(
        "app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindowExt.kt",
    ).substringAfter("internal suspend fun ChatViewModel.maybeAutoCompactInLoop(")
        .substringBefore("internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded()")

    @Test
    fun `the trim skip is gated by a measured gain verdict`() {
        val src = region()
        assertTrue("verdict call missing", src.contains("CompactEffect.verdict(compacted"))
        assertTrue(
            "folded must be derived from FOLDED_AND_SHRANK",
            src.contains("val folded = compactEffect == CompactEffect.Verdict.FOLDED_AND_SHRANK"),
        )
        assertTrue("the loop must still return the gate value", src.contains("return folded"))
    }

    @Test
    fun `the boolean-only decision is gone`() {
        val src = region()
        assertFalse(
            "old decision (marker/summary change alone) must not survive",
            src.contains("val folded = _cachedLatestMarker?.lastCompactedMessageId != markerBefore"),
        )
    }

    @Test
    fun `both projections use their own anchor and summary`() {
        val src = region()
        assertTrue(
            "before projection must use the pre-compact snapshot",
            src.contains("CompactEffect.projectedTokens(agentHistory, summaryBefore, markerBefore)"),
        )
        assertTrue(
            "after projection must use the live marker and summary",
            src.contains("CompactEffect.projectedTokens(agentHistory, summaryAfter, markerAfter)"),
        )
    }

    @Test
    fun `a no-gain fold is logged with both readings and the minimum`() {
        val src = region()
        assertTrue(src.contains("compact folded but the context did not shrink"))
        assertTrue(src.contains("CompactEffect.MIN_GAIN_TOKENS"))
        // 两个读数都要进日志，否则下一次审计仍然只有布尔。
        assertTrue(src.contains("before=\${beforeProjected}tok"))
        assertTrue(src.contains("after=\${afterProjected}tok"))
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
