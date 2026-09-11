package com.rikkaminis.app.provider.openai

// [refactor/split-provider] Batch 3: think-tag scanning moved VERBATIM from
// OpenAIProvider.kt (was lines 60-236): ThinkTagDef / THINK_TAG_FORMATS /
// ThinkTagScanResult / scanThinkTags. Already internal (unit test imports
// them; OpenAIProvider's streaming path consumes them).

/**
 * Defines a think-tag format pair: [open] marks the start of thinking
 * content, [close] marks the end. Matching is case-insensitive.
 * [altClose] is an alternative terminator for the same [open] (e.g.
 * DeepSeek R1 closes `<thinking>` with `<response>` instead of `</thinking>`).
 */
internal data class ThinkTagDef(
    val open: String,
    val close: String,
    val altClose: String? = null,
)

/**
 * Explicit think-tag formats. Matching is case-insensitive, so `<thinking>`
 * and `<THINKING>` both match the `<thinking>` entry. These are safe to scan
 * for on ALL models — an explicit tag can't collide with plain prose.
 */
internal val THINK_TAG_FORMATS: List<ThinkTagDef> = listOf(
    ThinkTagDef("<thinking>", "</thinking>", altClose = "<response>"), // DeepSeek R1 style
    // [fix/ttfb-thinktag-composer] GLM/llama.cpp-family relays emit
    // `<think>…</think>` inline in the content field (measured: relay
    // glm-5.3-flash streams its whole reasoning inline, no `reasoning_content`
    // field — the other half of the "thinking leaked into body" reports).
    // The `>` terminator keeps it distinct from `<thinking>` under indexOf
    // matching (neither string is a substring of the other), so both formats
    // coexist and the earliest-index scan picks the right one.
    ThinkTagDef("<think>", "</think>", altClose = "<response>"),
    ThinkTagDef("<reasoning>", "</reasoning>", altClose = "<response>"),
    ThinkTagDef("[think]", "[/think]"),
    ThinkTagDef("[reasoning]", "[/reasoning]"),
    // [T-think-tag-catalog-expand] Common third-party/self-consistent labels
    // some OpenAI-compatible relays emit inside `content` (half of the
    // "thinking leaked into body" bug): explicit symmetric tags are safe to
    // scan for — an explicit tag can't collide with plain prose. `<Thought>`
    // is seen from several argoshas (case-insensitive, so `<thought>` also
    // matches); `<analysis>` from reflect-style models. altClose handles the
    // common "ends at next <response>" legacy terminator used by gateways
    // that strip `</...>` closers.
    ThinkTagDef("<Thought>", "</Thought>", altClose = "<response>"),
    ThinkTagDef("<analysis>", "</analysis>", altClose = "<response>"),
)

/**
 * Result of a single [scanThinkTags] call.
 */
internal data class ThinkTagScanResult(
    val visible: String,
    val thinking: String,
    val remainingBuffer: String,
    val insideTag: Boolean,
    val currentFormat: ThinkTagDef?,
)

/**
 * Scans [buffer] for think-tag markers (case-insensitive) using [formats].
 *
 * When [insideTag] is true, searches for the [currentFormat] close tag.
 * When false, searches for any open tag from [formats].
 *
 * This is a pure scanner — it does not mutate any state. Callers are
 * responsible for updating their own state from the result.
 *
 * Fast path: when no tag is found, only the trailing partial-tag-prefix
 * (e.g. `<th` of `<thinking>`) is kept buffered — plain text streams
 * through immediately without accumulating (streaming UX must not lag).
 */
internal fun scanThinkTags(
    buffer: String,
    insideTag: Boolean,
    currentFormat: ThinkTagDef?,
    formats: List<ThinkTagDef>,
): ThinkTagScanResult {
    val bufLower = buffer.lowercase()
    val visibleBuilder = StringBuilder()
    val thinkingBuilder = StringBuilder()
    var i = 0
    var tagActive = insideTag
    var activeFormat = currentFormat

    /** Longest tail of [bufLower] that is a prefix of some open tag. */
    fun maxOpenTagPrefixLen(): Int {
        var best = 0
        for (fmt in formats) {
            val open = fmt.open.lowercase()
            val maxLen = minOf(open.length - 1, bufLower.length) // full tag is found by indexOf, never a prefix here
            for (len in maxLen downTo 1) {
                if (open.startsWith(bufLower.substring(bufLower.length - len))) {
                    if (len > best) best = len
                    break
                }
            }
        }
        return best
    }

    while (i < buffer.length) {
        if (!tagActive) {
            // Search for the EARLIEST open tag in the whole (remaining) buffer,
            // across all formats (case-insensitive). We pick by index, not by
            // FORMAT ORDER — the previous directory-order scan could latch onto
            // a `<thinking>` that appears LATER than a `<response>` that precedes
            // it, wrongly swallowing text and letting true thinking leak into
            // the visible body.
            var bestFmt: ThinkTagDef? = null
            var bestIdx = -1
            for (fmt in formats) {
                val openLower = fmt.open.lowercase()
                val idx = bufLower.indexOf(openLower, i)
                if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                    bestIdx = idx
                    bestFmt = fmt
                }
            }
            if (bestFmt != null) {
                // Found a real open tag: emit the preceding visible text, enter
                // the thinking region, and CONSUME the open tag. Continue the
                // loop so a region closed in this same buffer (e.g.
                // <thinking>…</thinking>) can open the next one — previously a
                // single scan returned after the first open tag and dropped
                // everything between it and the close, leaking it to visible.
                val openLen = bestFmt.open.length
                visibleBuilder.append(buffer, i, bestIdx)
                tagActive = true
                activeFormat = bestFmt
                i = bestIdx + openLen
            } else {
                // No open tag anywhere ahead: emit everything except a possible
                // open-tag PREFIX at the tail (kept buffered across chunks).
                val prefixLen = maxOpenTagPrefixLen()
                val keepFrom = buffer.length - prefixLen
                visibleBuilder.append(buffer, i, keepFrom)
                val remaining = buffer.substring(keepFrom)
                return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), remaining, false, null)
            }
        } else {
            val fmt = activeFormat
            if (fmt != null) {
                // Close candidates: primary close + altClose (e.g. <thinking>
                // can end with either </thinking> or <response>); take the
                // earliest.
                val closes = listOfNotNull(fmt.close, fmt.altClose).map { it.lowercase() }
                var bestIdx = -1
                var bestLen = 0
                for (c in closes) {
                    val idx = bufLower.indexOf(c, i)
                    if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                        bestIdx = idx
                        bestLen = c.length
                    }
                }
                if (bestIdx == -1) {
                    // Close tag not yet arrived — emit thinking text except a
                    // possible close-tag prefix at the tail (any close candidate).
                    var best = 0
                    for (c in closes) {
                        val maxLen = minOf(c.length - 1, bufLower.length)
                        for (len in maxLen downTo 1) {
                            if (c.startsWith(bufLower.substring(bufLower.length - len))) {
                                if (len > best) best = len
                                break
                            }
                        }
                    }
                    val keepFrom = buffer.length - best
                    thinkingBuilder.append(buffer, i, keepFrom)
                    val remaining = buffer.substring(keepFrom)
                    return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), remaining, true, activeFormat)
                } else {
                    thinkingBuilder.append(buffer, i, bestIdx)
                    i = bestIdx + bestLen
                    tagActive = false
                    activeFormat = null
                    // Loop continues scanning for the next open tag in this
                    // same buffer (handles multiple consecutive regions and
                    // the text between them).
                }
            } else {
                // Defensive: activeFormat should never be null while tagActive.
                // Treat as no-op so the stream never spins.
                break
            }
        }
    }

    return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), "", false, null)
}

// -- End of think-tag extraction --
