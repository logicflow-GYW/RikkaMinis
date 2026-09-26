package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for [ProviderHealthTracker.AdaptiveTtfbBudget] + ring/snapshot
 * behavior (absorb-network-pack).
 *
 * Registered (not fixed here): the ceiling case below pins the CURRENT semantics
 * (`budgetMs` clamps to a fixed 120s and ignores the route's static budget),
 * while the type's own KDoc claims "never above the route's static budget".
 * The API has no production consumer yet; fix the clamp when it gets wired
 * (upgrade trigger: first real caller, e.g. a first-token watchdog).
 */
class AdaptiveTtfbBudgetTest {

    private fun attempt(ttfb: Long?, success: Boolean, reason: String? = null): ProviderHealthTracker.Attempt =
        ProviderHealthTracker.Attempt(
            modelId = "m1", modelDisplayName = "Model One",
            startedAtMs = System.currentTimeMillis(), ttfbMs = ttfb, success = success, failureReason = reason,
        )

    private fun feed(vararg ttfbs: Long) {
        for (t in ttfbs) ProviderHealthTracker.finish(attempt(t, success = true), success = true)
    }

    @Test
    fun budget_thinHistory_fallsBackToRouteDefault() {
        // Empty and below MIN_SAMPLES: a single cold-start outlier must not
        // stretch the budget — the route default stands.
        assertEquals(42_000L, ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(emptyList(), 42_000L))
        ProviderHealthTracker.clear()
        feed(10_000L, 12_000L)
        assertEquals(42_000L, ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("m1"), 42_000L))
    }

    @Test
    fun budget_enoughSamples_adaptsToP95WithHeadroom() {
        ProviderHealthTracker.clear()
        feed(10_000L, 20_000L, 30_000L)
        // p95 = ceil((3-1)*0.95)=2 → 30s; 30s * 1.6 = 48s, inside [15s,120s].
        val b = ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("m1"), 42_000L)
        assertEquals(48_000L, b)
    }

    @Test
    fun budget_clampedToFloorAndCeiling() {
        ProviderHealthTracker.clear()
        // Suspiciously fast history: must not go below the 15s floor.
        feed(100L, 200L, 300L)
        assertEquals(15_000L, ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("m1"), 42_000L))
        ProviderHealthTracker.clear()
        // Very slow relay: must not exceed the 120s ceiling.
        feed(200_000L, 300_000L, 400_000L)
        assertEquals(120_000L, ProviderHealthTracker.AdaptiveTtfbBudget.budgetMs(
            ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("m1"), 42_000L))
    }

    @Test
    fun recordedTtfbs_onlySuccessfulAttempts() {
        ProviderHealthTracker.clear()
        ProviderHealthTracker.finish(attempt(11_000L, success = true), success = true)
        ProviderHealthTracker.finish(attempt(null, success = false, reason = "HTTP 502"), success = false, failureReason = "HTTP 502")
        ProviderHealthTracker.finish(attempt(13_000L, success = true), success = true)
        // A failed attempt carries no TTFB and must not enter the budget.
        assertEquals(listOf(11_000L, 13_000L), ProviderHealthTracker.AdaptiveTtfbBudget.recordedTtfbs("m1"))
    }

    @Test
    fun ringBoundedToCapacity_oldestEvicted() {
        ProviderHealthTracker.clear()
        for (i in 1..25) ProviderHealthTracker.finish(attempt(i * 1_000L, success = true), success = true)
        assertEquals(20, ProviderHealthTracker.snapshot().first().attempts.size)
        assertEquals(6_000L, ProviderHealthTracker.snapshot().first().attempts.first().ttfbMs)
    }

    @Test
    fun snapshot_successRateAndLastFailure() {
        ProviderHealthTracker.clear()
        ProviderHealthTracker.finish(attempt(10_000L, success = true), success = true)
        ProviderHealthTracker.finish(attempt(null, success = false, reason = "HTTP 429"), success = false, failureReason = "HTTP 429")
        val h = ProviderHealthTracker.snapshot().first()
        assertEquals(0.5, h.successRate, 1e-9)
        assertEquals("HTTP 429", h.lastFailureReason)
        // P50/P95 come from successful attempts only.
        assertEquals(10_000L, h.ttfbP50Ms)
        assertEquals(10_000L, h.ttfbP95Ms)
    }
}
