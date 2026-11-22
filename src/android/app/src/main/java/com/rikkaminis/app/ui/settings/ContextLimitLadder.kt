package com.rikkaminis.app.ui.settings

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

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
 * There is no "unlimited" stop. Turning the group limit off is the only
 * "no group override" path. Values saved by older builds as the old unlimited
 * sentinel are treated as off at the UI boundary without rewriting config.
 *
 * Custom input is expressed in K: entering 800 stores 800,000 tokens.
 * Display auto-scales to the friendliest unit (800K, 1.5M, 2M) instead of
 * forcing a fixed K suffix on large values.
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

    /** The retired "Unlimited" slider value. Legacy configs keep their bytes. */
    fun isLegacyUnlimited(tokens: Int?): Boolean =
        tokens != null && tokens >= Int.MAX_VALUE

    /** Compact label that never rounds to a different persisted token count. */
    fun label(tokens: Int): String {
        if (tokens >= 1_000_000) {
            val amount = String.format(Locale.US, "%.2f", tokens / 1_000_000.0)
                .trimEnd('0')
                .trimEnd('.')
            if ((amount.toDouble() * 1_000_000.0).roundToLong() == tokens.toLong()) {
                return "${amount}M"
            }
        }
        return "${formatK(tokens)}K"
    }

    /** Pre-fills the K-unit dialog without its suffix. */
    fun customInputValue(tokens: Int): String = formatK(tokens)

    /** Normalizes the locale decimal separator; other grouping marks are dropped. */
    fun sanitizeCustomKInput(
        raw: String,
        decimalSeparator: Char = DecimalFormatSymbols.getInstance().decimalSeparator,
    ): String {
        val out = StringBuilder()
        var hasDecimalPoint = false
        for (ch in raw) {
            when {
                ch.isDigit() -> out.append(ch)
                ch == decimalSeparator && !hasDecimalPoint -> {
                    out.append('.')
                    hasDecimalPoint = true
                }
            }
        }
        return out.toString().take(12)
    }

    /**
     * Parses the K-unit dialog into tokens. A fractional K is valid
     * (200.5 → 200,500 tokens), but the result must still fit the same
     * 64K..Int.MAX_VALUE-1 range as storage.
     */
    fun parseCustomK(raw: String): Int? {
        val k = raw.trim().toDoubleOrNull() ?: return null
        if (!k.isFinite() || k < customMinTokens / 1000.0) return null
        val tokens = (k * 1000.0).roundToLong()
        if (tokens < customMinTokens || tokens > customMaxTokens) return null
        return tokens.toInt()
    }

    /**
     * Slider stop for a persisted value. Legacy values outside [presets]
     * (including 32K / 200K / 400K / 2M) land on [customIndex] without being
     * rewritten.
     */
    fun stepIndexFor(tokens: Int?, customIndex: Int): Int = when {
        tokens != null && tokens in presets -> presets.indexOf(tokens)
        else -> customIndex
    }

    private fun formatK(tokens: Int): String =
        String.format(Locale.US, "%.3f", tokens / 1000.0)
            .trimEnd('0')
            .trimEnd('.')
}
