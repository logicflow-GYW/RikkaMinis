package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the TTFB half of the health ring.
 *
 * [ProviderHealthTracker.recordFirstToken] returns a COPY (it never mutates the
 * attempt it is handed) and [ProviderHealthTracker.finish] is what appends to the
 * ring. So the caller has to thread the copy through — the shape the trace
 * listener uses:
 *
 *     var attempt = begin(model, name)                      // callStart
 *     attempt = recordFirstToken(attempt, ttfbMs)           // responseHeadersStart
 *     finish(attempt, success = …)                          // callEnd
 *
 * Dropping that return value (the obvious-looking call) leaves every recorded
 * attempt with `ttfbMs = null`, which makes `recordedTtfbs()` / `ttfbP50Ms` /
 * `ttfbP95Ms` permanently empty — the metrics look wired but carry no TTFB, and
 * the adaptive first-token budget silently degrades to "not enough samples".
 * Both directions are pinned below so the contract cannot drift unnoticed.
 */
class ProviderHealthFeedTest {

    @Test
    fun threadedCopy_isWhatMakesTtfbReachTheRing() {
        ProviderHealthTracker.clear()
        var attempt = ProviderHealthTracker.begin("feed-model", "Feed Model")
        attempt = ProviderHealthTracker.recordFirstToken(attempt, 12_345L)
        ProviderHealthTracker.finish(attempt, success = true, durationMs = 20_000L)

        assertEquals(
            "TTFB 应进入环形缓冲（监听器必须回写 recordFirstToken 的副本）",
            listOf(12_345L),
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("feed-model"),
        )
        val health = ProviderHealthTracker.snapshot().first { it.modelId == "feed-model" }
        assertEquals("快照应能看到这条 TTFB", 12_345L, health.ttfbP50Ms)
    }

    @Test
    fun droppingTheCopy_losesTheSample_trackerStaysTheSingleSourceOfTruth() {
        ProviderHealthTracker.clear()
        val attempt = ProviderHealthTracker.begin("dropped-model", "Dropped Model")
        ProviderHealthTracker.recordFirstToken(attempt, 9_999L)   // 返回值被丢弃
        ProviderHealthTracker.finish(attempt, success = true, durationMs = 10_000L)

        assertTrue(
            "契约：不加回写就收不到 TTFB —— 这条断言是给未来改 API 的人看的",
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("dropped-model").isEmpty(),
        )
        assertEquals("attempt 本身仍应被记录", 1, ProviderHealthTracker.snapshot()
            .first { it.modelId == "dropped-model" }.attempts.size)
    }

    @Test
    fun thinHistory_fallsBackToTheRouteStaticBudget() {
        ProviderHealthTracker.clear()
        assertEquals(
            "样本不足时必须退回路由静态预算（否则第一次请求就用错预算）",
            30_000L,
            ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(emptyList(), 30_000L),
        )
    }
}
