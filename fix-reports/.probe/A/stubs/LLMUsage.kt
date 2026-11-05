package com.rikkaminis.app.data.model

/** Probe stub — mirror of the production @Serializable data class without the
 *  serialization annotation (the probe never serialises; @Serializable would
 *  drag the kotlinx-serialization plugin into a plain kotlinc call). */
data class LLMUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val latestContextTokens: Int = 0,
)
