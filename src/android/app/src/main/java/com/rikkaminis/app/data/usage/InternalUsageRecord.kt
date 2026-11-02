package com.rikkaminis.app.data.usage

import com.rikkaminis.app.data.model.LLMUsage

/**
 * [T-internal-usage-events] Cost ledger for LLM calls that never become chat
 * messages.
 *
 * The usage page aggregates `messages.token_usage` — which by construction only
 * sees calls that produced a message. Compaction summaries, session titles and
 * the quick-test probe all spend tokens on the user's provider account but write
 * no message, so they were invisible: on a per-token relay the usage page
 * systematically under-reported what the account was actually billed.
 *
 * This record is the pure half of the fix (no Room imports so it stays
 * JVM-testable): the policy — which calls count, how a null `usage` is treated,
 * how rows fold into per-source stats — lives here; the Room entity + insert
 * live in [com.rikkaminis.app.data.db.InternalUsageEventEntity] and
 * [InternalUsageRecorder].
 *
 * Why a side table instead of one unified `usage_events` ledger for every call
 * (the shape upstream RikkaHub uses): the chat path already writes
 * `messages.token_usage` at persist time, has tests, and feeds the SQL fast
 * path with its JSON1 aggregation. Rewriting it to a second write would be a
 * ~1000-line migration for zero user-visible gain, and two writers of the same
 * fact is exactly the drift this project keeps paying for.
 * ponytail: side table covers only non-message calls.
 * 天花板: per-model internal attribution (rows are grouped by source, not model),
 * and a call whose channel reports no usage at all stays unrecorded.
 * 升级触发: someone needs "which model burned my compaction tokens", or a
 * provider starts reporting usage asynchronously — then unify on one table.
 */
data class InternalUsageRecord(
    val createdAt: Long,
    val source: String,
    val modelId: String,
    val entryId: String?,
    val sessionId: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationTokens: Int,
    val cacheReadTokens: Int,
) {
    val totalTokens: Long get() = inputTokens.toLong() + outputTokens.toLong() +
        cacheCreationTokens.toLong() + cacheReadTokens.toLong()

    companion object {
        /**
         * Builds a ledger row, or null when there is nothing worth recording.
         *
         * - null `usage` → no row. The channel simply did not report tokens;
         *   writing a zero row would inflate the request count while saying
         *   nothing about cost.
         * - every counter null/zero → no row (same reason).
         * - `cacheCreationInputTokens` / `cacheReadInputTokens` are nullable
         *   upstream; null means "not reported", which for a sum is 0.
         */
        fun from(
            source: String,
            modelId: String,
            entryId: String?,
            sessionId: String?,
            usage: LLMUsage?,
            nowMs: Long,
        ): InternalUsageRecord? {
            if (usage == null) return null
            val cacheCreation = usage.cacheCreationInputTokens ?: 0
            val cacheRead = usage.cacheReadInputTokens ?: 0
            if (usage.inputTokens <= 0 && usage.outputTokens <= 0 &&
                cacheCreation <= 0 && cacheRead <= 0
            ) {
                return null
            }
            return InternalUsageRecord(
                createdAt = nowMs,
                source = source,
                modelId = modelId,
                entryId = entryId,
                sessionId = sessionId,
                inputTokens = usage.inputTokens.coerceAtLeast(0),
                outputTokens = usage.outputTokens.coerceAtLeast(0),
                cacheCreationTokens = cacheCreation.coerceAtLeast(0),
                cacheReadTokens = cacheRead.coerceAtLeast(0),
            )
        }
    }
}

/** Stable ids persisted in the ledger; never localize these. */
object InternalUsageSources {
    const val COMPACT = "compact"
    const val TITLE = "title"
    const val QUICK_TEST = "quick_test"
}

/** Per-source rollup shown in the usage page's internal-call section. */
data class InternalSourceStats(
    val source: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
) {
    val totalInput: Long get() = inputTokens + cacheCreationTokens + cacheReadTokens
    val totalTokens: Long get() = totalInput + outputTokens
}

/**
 * Folds ledger rows into one entry per source, biggest spender first.
 *
 * Kept as a pure function (not a SQL GROUP BY) on purpose: the ledger is tiny —
 * one row per compaction and per title — and a pure function is testable on the
 * JVM, which the SQL path is not.
 */
fun aggregateInternalUsageBySource(
    records: List<InternalUsageRecord>,
): List<InternalSourceStats> =
    records.groupBy { it.source }
        .map { (source, rows) ->
            InternalSourceStats(
                source = source,
                requests = rows.size,
                inputTokens = rows.sumOf { it.inputTokens.toLong() },
                outputTokens = rows.sumOf { it.outputTokens.toLong() },
                cacheCreationTokens = rows.sumOf { it.cacheCreationTokens.toLong() },
                cacheReadTokens = rows.sumOf { it.cacheReadTokens.toLong() },
            )
        }
        .sortedWith(compareByDescending<InternalSourceStats> { it.totalTokens }.thenBy { it.source })
