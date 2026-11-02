package com.rikkaminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-internal-usage-events] One row per LLM call that spent tokens without
 * producing a chat message (compaction summary, session title, quick-test
 * probe). See [com.rikkaminis.app.data.usage.InternalUsageRecord] for why this
 * is a side table instead of the single `usage_events` ledger upstream uses.
 *
 * No foreign key to `sessions` on purpose: this is a cost ledger, not session
 * content. Deleting a session must not erase "these tokens were spent" — the
 * money was already spent. `session_id` is kept for context only and may point
 * at a session that no longer exists.
 */
@Entity(
    tableName = "internal_usage_events",
    indices = [Index(value = ["created_at"])],
)
data class InternalUsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Stable id, never localized — one of [com.rikkaminis.app.data.usage.InternalUsageSources]. */
    @ColumnInfo(name = "source") val source: String,
    /** Model id in the same namespace as `messages.usage_model_id`. */
    @ColumnInfo(name = "model_id") val modelId: String,
    /** ModelEntry id, mirroring `messages.usage_entry_id`. Null when unknown. */
    @ColumnInfo(name = "entry_id") val entryId: String?,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int,
    @ColumnInfo(name = "cache_creation_tokens") val cacheCreationTokens: Int,
    @ColumnInfo(name = "cache_read_tokens") val cacheReadTokens: Int,
)
