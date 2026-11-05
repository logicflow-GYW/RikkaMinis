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
 * When [insideTag] is false, an ORPHAN close token (a `</thinking>` whose
 * opener never arrived) is CONSUMED AND DROPPED rather than emitted as
 * visible text. Gateways that inline the model's reasoning into `content`
 * routinely strip the opener and leave the closer behind, so the token is a
 * wire artifact — never body text. Note the boundary: only the token is
 * dropped, not the text that precedes it (see the ponytail note below).
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
    // [unicode-index-shift] Never search a `buffer.lowercase()` copy: U+0130 (İ)
    // lowercases to TWO code points ("i\u0307"), so every index taken from the
    // lowercased string past an İ is shifted +1 — slicing the SOURCE buffer with
    // such an index eats/corrupts text (2026-10-12 repro:
    // "İ<thinking>abc</thinking>done" -> visible="İ<one", thinking="bc<").
    // All searches below stay in SOURCE coordinates. Same fix family as
    // stripOrphanThinkClosers (2026-09-27, which fixed the same pattern there).
    fun indexOfIgnoreCase(haystack: String, needle: String, from: Int): Int {
        if (needle.isEmpty()) return from
        val last = haystack.length - needle.length
        var j = from.coerceAtLeast(0)
        while (j <= last) {
            if (haystack.regionMatches(j, needle, 0, needle.length, ignoreCase = true)) return j
            j++
        }
        return -1
    }

    /** Longest tail of [buffer] that is a prefix of [tag]. */
    fun maxTagPrefixLen(tag: String): Int {
        val maxLen = minOf(tag.length - 1, buffer.length)
        for (len in maxLen downTo 1) {
            if (buffer.regionMatches(buffer.length - len, tag, 0, len, ignoreCase = true)) return len
        }
        return 0
    }
    val visibleBuilder = StringBuilder()
    val thinkingBuilder = StringBuilder()
    var i = 0
    var tagActive = insideTag
    var activeFormat = currentFormat

    /** Longest tail of [buffer] that is a prefix of some open tag. */
    fun maxOpenTagPrefixLen(): Int {
        var best = 0
        for (fmt in formats) {
            val len = maxTagPrefixLen(fmt.open)
            if (len > best) best = len
        }
        return best
    }

    /**
     * Longest tail of [buffer] that is a prefix of some PRIMARY close token
     * (`</thinking>`, `[/think]`, …) — the orphan-close counterpart of
     * [maxOpenTagPrefixLen]. A closer split across SSE chunks (`</thin` +
     * `king>`) must not leak its head into the body either. altClose is
     * deliberately excluded: `<response>` is ordinary prose far more often
     * than it is a terminator (a pinned test asserts it never opens a region).
     */
    fun maxCloseTagPrefixLen(): Int {
        var best = 0
        for (fmt in formats) {
            val len = maxTagPrefixLen(fmt.close)
            if (len > best) best = len
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
            var bestLen = 0
            var bestIsOrphanClose = false
            for (fmt in formats) {
                val idx = indexOfIgnoreCase(buffer, fmt.open, i)
                if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                    bestIdx = idx
                    bestFmt = fmt
                    bestLen = fmt.open.length
                    bestIsOrphanClose = false
                }
                // Orphan close: some gateways inline the model's reasoning into
                // `content` but strip the OPENER, leaving a bare `</thinking>`
                // (measured on gpt-6-luna via llmhost.net — 10 closers, 0
                // openers across one session; the token reached both the
                // transcript and the DB). Competes with open tags by index.
                val cIdx = indexOfIgnoreCase(buffer, fmt.close, i)
                if (cIdx != -1 && (bestIdx == -1 || cIdx < bestIdx)) {
                    bestIdx = cIdx
                    bestFmt = fmt
                    bestLen = fmt.close.length
                    bestIsOrphanClose = true
                }
            }
            if (bestIdx != -1 && bestIsOrphanClose) {
                // ponytail: drops the artifact token only, never the run before it | 天花板: that run stays
                // in the body (its deltas were already streamed as visible — a scanner cannot retract them)
                // 升级触发: 用户仍报「思考文本挤在正文」→ 需落库/重建期启发式接管
                visibleBuilder.append(buffer, i, bestIdx)
                i = bestIdx + bestLen
            } else if (bestFmt != null) {
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
                // No marker anywhere ahead: emit everything except a possible
                // open/close-tag PREFIX at the tail (kept buffered across chunks).
                val prefixLen = maxOf(maxOpenTagPrefixLen(), maxCloseTagPrefixLen())
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
                val closes = listOfNotNull(fmt.close, fmt.altClose)
                var bestIdx = -1
                var bestLen = 0
                for (c in closes) {
                    val idx = indexOfIgnoreCase(buffer, c, i)
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
                        val len = maxTagPrefixLen(c)
                        if (len > best) best = len
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

    // [state-carry] The loop can exit with an ACTIVE region when a chunk ends
    // exactly where the open tag was consumed (e.g. a relay delta that is
    // exactly "<thinking>"). Hardcoding false/null here dropped the region
    // state: the next chunk treated the thinking body as visible text and its
    // closer was then discarded as an orphan — the whole region leaked into
    // the reply body (2026-10-12 repro: ["<thinking>", "abc</thinking>done"]
    // -> visible="abcdone", thinking=[]). Carry the real state out.
    return ThinkTagScanResult(
        visibleBuilder.toString(),
        thinkingBuilder.toString(),
        "",
        tagActive,
        if (tagActive) activeFormat else null,
    )
}

/**
 * Offline twin of [scanThinkTags]'s orphan-close rule, for text that is
 * ALREADY PERSISTED — no streaming, no caller state, no think-region
 * extraction (everything except the artifact token is returned byte-identical).
 *
 * Why it exists: rows written before the scanner learned to drop orphan
 * closers still carry a bare `</thinking>` in the body (measured 2026-09-27,
 * session 9068927b: 5 assistant rows / 10 tokens, gpt-6-luna via
 * llmhost.net). Cleaning at the read boundary fixes every reader at once —
 * UI transcript, LLM history, drawer preview — without rewriting the table
 * (no migration, idempotent, stored bytes stay auditable).
 *
 * Two guards, both taken from the same measurement, keep it from mangling
 * deliberate text:
 *  - ORPHAN only: a closer whose opener appeared earlier in the SAME text is
 *    kept. A provider without think-tag extraction can persist a visible
 *    `<thinking>…</thinking>` region, and that pair is body text, not
 *    artifact.
 *  - CODE SPANS are skipped: 0/10 artifacts were inside code, while every
 *    deliberate quotation of the token in the same corpus (`` `</thinking>` ``
 *    in prose) was inside backticks. Backtick parity covers inline spans;
 *    fenced blocks are handled by line-start ``` fence detection ([B14a]) —
 *    fence content is copied verbatim and the fence state never leaks into
 *    inline-span parity.
 *
 * Known boundary: a bare (unbackticked) prose mention of the token is
 * indistinguishable from the artifact and IS dropped — accepted, both are
 * cosmetic and the alternative is leaving the reported bug in place.
 */
internal fun stripOrphanThinkClosers(text: String): String {
    if (text.isEmpty()) return text
    // Cheap pre-filter: every close token in THINK_TAG_FORMATS starts with one
    // of these two prefixes, so the per-character pass is skipped for the
    // overwhelming majority of rows (this runs on every session load).
    // Keep matching against `text` below: lowercasing can expand a Unicode
    // code point (for example `İ`), which would shift indices into the source.
    if (text.indexOf("</") == -1 && text.indexOf("[/") == -1) return text
    val out = StringBuilder(text.length)
    var ticks = 0
    // [B14a] Fence-aware scanning. The previous single-parity backtick counter
    // broke on fenced blocks: a ``` opener made ticks odd ("inside code") while
    // any single backtick inside the block flipped it even again — so an
    // orphan `</thinking>` sitting after a fence (or quoted inside one) could
    // be classified the wrong way. Fences now toggle by line-start ```
    // markers; inline single-backtick spans only drive the parity check
    // outside fences, and fence content is copied verbatim (code is never
    // cleaned).
    var inFence = false
    var openersSeen = 0
    var i = 0
    while (i < text.length) {
        if (i == 0 || text[i - 1] == '\n') {
            // CommonMark fence: at most 3 leading spaces before the ``` marker.
            var j = i
            var indent = 0
            while (j < text.length && text[j] == ' ' && indent < 3) {
                j++
                indent++
            }
            if (text.regionMatches(j, "```", 0, 3)) {
                inFence = !inFence
                // Copy the entire fence marker line verbatim — its backticks
                // are fence syntax, not inline-span delimiters, and must not
                // touch the parity counter (after the closing marker the
                // counter is already back outside any fence).
                var end = text.indexOf('\n', i)
                if (end == -1) end = text.length
                out.append(text, i, end)
                i = end
                continue
            }
        }
        val c = text[i]
        if (inFence) {
            out.append(c)
            i++
            continue
        }
        if (c == '`') {
            ticks++
            out.append(c)
            i++
            continue
        }
        var matched = false
        if (ticks % 2 == 0) {
            for (fmt in THINK_TAG_FORMATS) {
                val open = fmt.open
                if (open.isNotEmpty() && text.regionMatches(i, open, 0, open.length, ignoreCase = true)) {
                    openersSeen++
                    out.append(text, i, i + open.length)
                    i += open.length
                    matched = true
                    break
                }
                val close = fmt.close
                if (close.isNotEmpty() && text.regionMatches(i, close, 0, close.length, ignoreCase = true)) {
                    if (openersSeen > 0) out.append(text, i, i + close.length)
                    i += close.length
                    matched = true
                    break
                }
            }
        }
        if (!matched) {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

// -- End of think-tag extraction --
