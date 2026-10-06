package com.rikkaminis.app.data.db

/** Raw usage record from joined messages + sessions query. */
data class UsageRecord(
    val modelId: String,
    val tokenUsage: String,
    val createdAt: Long,
    val sessionId: String,
    // [T-usage-attribution] Actual provider/model identity recorded at persist
    // time (null for legacy rows; aggregator falls back to sessions.model_id).
    val usageModelId: String? = null,
    val usageEntryId: String? = null,
)

/**
 * Pre-aggregated per-model usage row from the SQL-side aggregation
 * ([ChatDao.usageStatsAggregated], feat/usage-stats-perf-1007): one GROUP BY
 * query replaces materializing every usage row into Kotlin. Field semantics
 * match [com.rikkaminis.app.data.usage.UsageAggregator.aggregate]; distinct
 * days/sessions arrive as counts (COUNT DISTINCT) instead of sets.
 */
data class UsageStatsRow(
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val distinctDays: Int,
    val distinctSessions: Int,
)
