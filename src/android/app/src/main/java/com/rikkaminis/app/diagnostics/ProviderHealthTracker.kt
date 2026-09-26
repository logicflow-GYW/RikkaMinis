package com.rikkaminis.app.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * [absorb-network-pack: feat-provider-health] In-process provider health
 * aggregation. Ported from Filterrr/RikkaMinis @ 887a7c6 (network pack),
 * package rename only.
 *
 * Data already existed but only as trace lines (OkHttpNetTrace) — no
 * provider-side TTFB/P50/P95 surface. This tracker keeps a bounded,
 * ring-buffered per-model record of recent provider attempts so a future
 * Settings → Provider Health panel (registered as a separate UI item) can
 * show, per model: recent TTFB distribution (P50/P95), success rate over
 * the last N attempts, and the last failure reason.
 *
 * Design:
 *  - Pure JVM, no Android deps — unit-testable in the sandbox.
 *  - Ring buffer per modelId, fixed capacity [CAPACITY] (oldest evicted).
 *  - All methods are cheap + non-blocking (ConcurrentHashMap + array
 *    copy-on-write); safe to call from the streaming hot path.
 *  - In-memory only: resets on process death. This is a "what has this
 *    model done for me lately" surface, not an audit log.
 *  - Feed point: ProviderHealthTraceListener (network package) wires
 *    begin/recordFirstToken/finish from every provider client's
 *    eventListenerFactory.
 */
object ProviderHealthTracker {

    private const val TAG = "ProviderHealth"
    private const val CAPACITY = 20

    /** One provider attempt (one LLM call, retry or fallback included). */
    data class Attempt(
        val modelId: String,
        val modelDisplayName: String,
        val startedAtMs: Long,
        val ttfbMs: Long?,      // null = failed before first token
        val success: Boolean,
        val failureReason: String? = null,
        val durationMs: Long? = null,
    )

    data class ModelHealth(
        val modelId: String,
        val modelDisplayName: String,
        val attempts: List<Attempt>,
        val successRate: Double,       // 0..1 over recorded attempts
        val ttfbP50Ms: Long?,          // null when no successful attempt recorded
        val ttfbP95Ms: Long?,
        val lastFailureReason: String?,
        val lastUsedAtMs: Long,
    )

    private val history = ConcurrentHashMap<String, ArrayDeque<Attempt>>()

    /** Record an attempt start → returns the token used by the finish calls. */
    fun begin(modelId: String, modelDisplayName: String): Attempt {
        return Attempt(
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            startedAtMs = System.currentTimeMillis(),
            ttfbMs = null,
            success = false,
        )
    }

    /** First token arrived on the wire. */
    fun recordFirstToken(attempt: Attempt, ttfbMs: Long): Attempt =
        attempt.copy(ttfbMs = ttfbMs)

    /** Attempt finished (success or terminal failure). Records into the ring. */
    fun finish(attempt: Attempt, success: Boolean, failureReason: String? = null, durationMs: Long? = null) {
        val rec = attempt.copy(success = success, failureReason = failureReason, durationMs = durationMs)
        val q = history.getOrPut(rec.modelId) { ArrayDeque() }
        synchronized(q) {
            q.addLast(rec)
            while (q.size > CAPACITY) q.removeFirst()
        }
    }

    /** Snapshot for the UI panel, worst-recent-first (highest failure rate, then stalest). */
    fun snapshot(): List<ModelHealth> {
        val now = System.currentTimeMillis()
        return history.map { (modelId, q) ->
            val items = synchronized(q) { q.toList() }
            val successes = items.filter { it.success }
            val ttfbs = successes.mapNotNull { it.ttfbMs }.sorted()
            ModelHealth(
                modelId = modelId,
                modelDisplayName = items.lastOrNull()?.modelDisplayName ?: modelId,
                attempts = items,
                successRate = if (items.isEmpty()) 0.0 else successes.size.toDouble() / items.size,
                ttfbP50Ms = percentile(ttfbs, 0.50),
                ttfbP95Ms = percentile(ttfbs, 0.95),
                lastFailureReason = items.lastOrNull { !it.success }?.failureReason,
                lastUsedAtMs = items.lastOrNull()?.startedAtMs ?: now,
            )
        }.sortedWith(
            compareByDescending<ModelHealth> { 1.0 - it.successRate }
                .thenByDescending { it.lastUsedAtMs },
        )
    }

    /** Drop everything (e.g. user cleared the panel). */
    fun clear() = history.clear()

    private fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val idx = ((sorted.size - 1) * p).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * [absorb-network-pack: feat2-adaptive-ttfb-budget] Rolling first-chunk
     * budget for a model route, derived from THIS model's own recorded TTFB
     * history.
     *
     * Why: a fixed one-size budget either flags a relay whose historical
     * first chunk lands at 35-60s as wedged too early, or keeps users
     * staring at a dead-looking spinner behind a wall-clock watchdog
     * designed for the slowest possible route. Feeding the observed P95
     * back into the budget makes both routes feel right without touching
     * the generation backstop.
     *
     * Contract (mirrors FirstChunkTimeoutPolicy's pure-decision style):
     *   - Budget = clamp(P95 * ADAPTIVE_HEADROOM, FLOOR_MS, CEILING_MS).
     *   - Needs >= MIN_SAMPLES recorded TTFBs before adapting — below that
     *     the answer is the route default ([routeDefaultMs]) so a single
     *     cold-start outlier can't stretch the budget.
     *   - Successful attempts only; failures carry no TTFB.
     *
     * (The Filterrr source rides this value to its offload worker as
     * `first_chunk_budget_ms`; this repo has no such request-JSON consumer
     * yet — wiring is part of the follow-up UI item. Exposed here so the
     * consumer lands on a tested surface.)
     */
    object AdaptiveTtfbBudget {

        /** Minimum recorded successful TTFBs before adapting. */
        const val MIN_SAMPLES = 3

        /** Headroom over the observed P95 (relay jitter, cold caches). */
        const val HEADROOM = 1.6

        /** Never below this — protects against a suspiciously fast history. */
        const val FLOOR_MS = 15_000L

        /** Never above the route's static budget — adaptation only widens
         *  or modestly tightens within the proven-safe envelope. */
        const val CEILING_MS = 120_000L

        /**
         * Compute the budget, or [routeDefaultMs] when history is thin.
         * Pure function of (history, routeDefaultMs) — JVM-testable.
         */
        fun budgetMs(history: List<Long>, routeDefaultMs: Long): Long {
            if (history.size < MIN_SAMPLES) return routeDefaultMs
            val p95 = percentile(history.sorted(), 0.95) ?: return routeDefaultMs
            return (p95 * HEADROOM).toLong().coerceIn(FLOOR_MS, CEILING_MS)
        }

        /** All successful TTFBs recorded for [modelId], oldest first. */
        fun recordedTtfbs(modelId: String): List<Long> {
            val q = history[modelId] ?: return emptyList()
            return synchronized(q) { q.toList() }
                .filter { it.success && it.ttfbMs != null }
                .map { it.ttfbMs!! }
        }
    }
}
