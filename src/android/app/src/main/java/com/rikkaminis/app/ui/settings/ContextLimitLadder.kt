package com.rikkaminis.app.ui.settings

import java.util.Locale

/**
 * Shared ladder logic for the group context-limit picker.
 *
 * The fixed stops are deliberately powers of two. This is an agent app, not a
 * chat app: below 64K the system prompt, tool schemas and a couple of tool
 * results consume a large share of the budget, and compaction starts removing
 * useful work. 64K is therefore the conservative floor; 128K is the practical
 * floor for an agent session. Provider-specific values (200K / 400K / 2M etc.)
 * stay reachable through the single custom stop instead of making the ladder a
 * catalogue of vendor marketing numbers.
 *
 * Values saved by older builds outside the preset list remain valid: the UI
 * maps them onto the custom stop and shows their real number. Only *new* custom
 * input is validated against [customMinTokens]..[customMaxTokens].
 */
object ContextLimitLadder {
    const val customMinTokens: Int = 64_000
    const val customMaxTokens: Int = Int.MAX_VALUE - 1

    val presets: List<Int> = listOf(
        64_000,
        128_000,
        256_000,
        512_000,
        1_000_000,
    )

    /** True for any bounded value that is not one of the fixed stops. */
    fun isCustomValue(tokens: Int?): Boolean =
        tokens != null &&
            tokens > 0 &&
            tokens < Int.MAX_VALUE &&
            tokens !in presets

    /** Compact display label: 64K, 128K, 1M, 1.5M, 200K, … */
    fun label(tokens: Int): String {
        val (divisor, suffix) = if (tokens >= 1_000_000) {
            1_000_000.0 to "M"
        } else {
            1_000.0 to "K"
        }
        val amount = String.format(Locale.US, "%.2f", tokens / divisor)
            .trimEnd('0')
            .trimEnd('.')
        return "$amount$suffix"
    }

    /**
     * Slider stop for a persisted value. Legacy values outside [presets]
     * (including 32K / 200K / 400K / 2M) land on [customIndex] without being
     * rewritten; null and the unlimited sentinel map to [unlimitedIndex].
     */
    fun stepIndexFor(tokens: Int?, customIndex: Int, unlimitedIndex: Int): Int = when {
        tokens == null || tokens >= Int.MAX_VALUE -> unlimitedIndex
        tokens in presets -> presets.indexOf(tokens)
        else -> customIndex
    }

    /** Parses new custom input. Out-of-range or non-numeric text returns null. */
    fun parseCustom(raw: String): Int? {
        val value = raw.trim().toLongOrNull() ?: return null
        if (value < customMinTokens || value > customMaxTokens) return null
        return value.toInt()
    }
}
