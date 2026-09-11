package com.rikkaminis.app.provider

/**
 * Anthropic Messages API usage accounting.
 *
 * Semantics (verified against live relay traffic on 2026-09-12):
 * - `input_tokens` reports FRESH (non-cached) input only. Cache reads and
 *   cache creations are metered separately (`cache_read_input_tokens` /
 *   `cache_creation_input_tokens`) and are NOT included in `input_tokens`.
 * - Therefore fresh input tokens = `input_tokens` as-is; total context
 *   consumed = `input_tokens` + cache read + cache creation.
 *
 * The previous implementation subtracted the cache total from `input_tokens`
 * a second time (double deduction): with any full-prefix cache hit the fresh
 * input collapsed towards zero (observed 50 - 1924 -> 0), and
 * `latestContextTokens` under-reported the context size used for max_tokens
 * headroom estimation. Keep this function free of Android/org.json
 * dependencies so it stays unit-testable in a plain JVM.
 *
 * @return Pair (freshInputTokens, latestContextTokens).
 */
fun anthropicUsageAccounting(
    inputTokens: Int,
    cacheReadInputTokens: Int?,
    cacheCreationInputTokens: Int?,
): Pair<Int, Int> {
    val cacheTotal = (cacheReadInputTokens ?: 0) + (cacheCreationInputTokens ?: 0)
    return inputTokens to (inputTokens + cacheTotal)
}