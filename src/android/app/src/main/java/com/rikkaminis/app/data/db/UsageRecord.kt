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
