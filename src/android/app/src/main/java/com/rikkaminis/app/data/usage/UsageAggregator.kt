package com.rikkaminis.app.data.usage

import org.json.JSONObject

/**
 * Pure-JVM aggregation over raw usage rows. No Android/Compose dependency —
 * unit-testable in the sandbox (org.json is available both on Android and via
 * the Maven artifact used by the JVM test classpath).
 */
data class UsageRow(
    val modelId: String,
    val tokenUsageJson: String,
    val createdAtMs: Long,
    val sessionId: String,
)

data class UsageFilter(val sinceMs: Long? = null, val untilMs: Long? = null)

data class AggregatedModelStats(
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val distinctDays: Set<String>,
    val distinctSessions: Set<String>,
) {
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens
}

object UsageAggregator {
    /**
     * Aggregate [rows] filtered by [filter]. Malformed JSON rows are skipped
     * (same tolerance as the current UI code). Time filtering is applied
     * before aggregation (filter-then-aggregate). Day bucketing uses the
     * injected [dayFormat]; production passes a device-local-timezone
     * formatter while tests pass a UTC one for determinism.
     */
    fun aggregate(
        rows: List<UsageRow>,
        filter: UsageFilter = UsageFilter(),
        dayFormat: (Long) -> String = { it.toString() }, // injectable for tests
    ): Map<String, AggregatedModelStats> {
        val since = filter.sinceMs
        val until = filter.untilMs

        val byModel = LinkedHashMap<String, MutableList<UsageRow>>()
        for (row in rows) {
            // [sinceMs <= createdAt < untilMs) — half-open window.
            if (since != null && row.createdAtMs < since) continue
            if (until != null && row.createdAtMs >= until) continue
            byModel.getOrPut(row.modelId) { mutableListOf() }.add(row)
        }

        val result = LinkedHashMap<String, AggregatedModelStats>()
        for ((modelId, modelRows) in byModel) {
            var input = 0L
            var output = 0L
            var cacheCr = 0L
            var cacheRd = 0L
            val days = LinkedHashSet<String>()
            val sessions = LinkedHashSet<String>()
            for (row in modelRows) {
                val usage = try { JSONObject(row.tokenUsageJson) } catch (_: Exception) { continue }
                input += usage.optLong("inputTokens", 0)
                output += usage.optLong("outputTokens", 0)
                // Legacy key fallbacks kept identical to the previous inline
                // UI parsing (Anthropic-style *InputTokens naming).
                cacheCr += usage.optLong("cacheCreationTokens", usage.optLong("cacheCreationInputTokens", 0))
                cacheRd += usage.optLong("cacheReadTokens", usage.optLong("cacheReadInputTokens", 0))
                days.add(dayFormat(row.createdAtMs))
                sessions.add(row.sessionId)
            }
            result[modelId] = AggregatedModelStats(
                modelId = modelId,
                inputTokens = input,
                outputTokens = output,
                cacheCreationTokens = cacheCr,
                cacheReadTokens = cacheRd,
                distinctDays = days,
                distinctSessions = sessions,
            )
        }
        return result
    }
}