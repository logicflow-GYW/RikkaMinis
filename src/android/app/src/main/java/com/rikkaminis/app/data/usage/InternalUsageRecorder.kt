package com.rikkaminis.app.data.usage

import android.util.Log
import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.InternalUsageEventEntity
import com.rikkaminis.app.data.model.LLMUsage
import kotlinx.coroutines.CancellationException

/**
 * [T-internal-usage-events] The single write path into `internal_usage_events`.
 *
 * Every non-message LLM call must go through [record] rather than inserting the
 * entity itself — one funnel is what makes the "did we wire every call site?"
 * question answerable with a grep for `InternalUsageRecorder.record` instead of a
 * hunt for `insertInternalUsageEvent` scattered across the tree. Adding a fourth
 * call site means adding a fourth `record(...)` line, not a fourth schema write.
 *
 * Call sites today (all three `ProviderExecutionGateway.send` consumers):
 * - [com.rikkaminis.app.conversation.CompactOrchestration] — compaction summary
 * - ChatPromptAndTools — session title (auto + manual regenerate share this path)
 * - QuickTestSheet — the provider connection probe (TEXT route)
 *
 * Failure policy: a ledger write must never break the call it is accounting for.
 * A failed insert is logged and swallowed (the tokens are spent either way; the
 * chat/compaction result matters more) — but scope cancellation is rethrown so a
 * cancelled view-model never leaves a half-run write behind.
 */
object InternalUsageRecorder {
    private const val TAG = "InternalUsage"

    /**
     * Records [usage] for [source], or does nothing when the channel reported no
     * tokens (see [InternalUsageRecord.from]). Safe to call with a null usage.
     */
    suspend fun record(
        dao: ChatDao,
        source: String,
        modelId: String,
        entryId: String?,
        sessionId: String?,
        usage: LLMUsage?,
    ) {
        val record = InternalUsageRecord.from(
            source = source,
            modelId = modelId,
            entryId = entryId,
            sessionId = sessionId,
            usage = usage,
            nowMs = System.currentTimeMillis(),
        ) ?: return
        write(dao, record)
    }

    suspend fun write(dao: ChatDao, record: InternalUsageRecord) {
        try {
            dao.insertInternalUsageEvent(record.toEntity())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never let accounting break the feature it is accounting for.
            Log.w(TAG, "ledger insert failed (source=${record.source})", e)
        }
    }
}

fun InternalUsageRecord.toEntity(): InternalUsageEventEntity = InternalUsageEventEntity(
    createdAt = createdAt,
    source = source,
    modelId = modelId,
    entryId = entryId,
    sessionId = sessionId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheCreationTokens = cacheCreationTokens,
    cacheReadTokens = cacheReadTokens,
)

fun InternalUsageEventEntity.toRecord(): InternalUsageRecord = InternalUsageRecord(
    createdAt = createdAt,
    source = source,
    modelId = modelId,
    entryId = entryId,
    sessionId = sessionId,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheCreationTokens = cacheCreationTokens,
    cacheReadTokens = cacheReadTokens,
)
