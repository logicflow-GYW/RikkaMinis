package com.openminis.app.ui.chat

// [refactor/split-streaming-markdown] Batch 2: streaming-merge PURE functions
// moved VERBATIM from StreamingMarkdownText.kt (was lines 697-952). Zero
// Compose dependency — plain String -> List<String> / String -> String logic
// consumed by the flatten pipeline (ChatFlatItems / AppendOnlyMarkdownSegmenter)
// and covered by StreamingMergeFunctionsTest + StreamingMarkdownTextTest.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            // Trim trailing empty line we used as boundary, but keep
            // intentional internal newlines.
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            // A fence line both closes the previous fragment (when we're
            // not inside a fence) and opens/closes the fence fragment.
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            // Boundary: paragraph end. Drop the blank line itself; it
            // signals the split.
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

// ── Raw-text-layer streaming merge helpers ─────────────────────────────────

/**
 * Minimum streaming overlap length for suffix-prefix deduplication.
 * Shorter overlaps are treated as normal delta text (concatenated).
 */
private const val MINIMUM_STREAMING_OVERLAP_LENGTH = 3

/**
 * Determines whether [incoming] is a regressive (backwards) snapshot of
 * [current] — i.e., a shorter string that starts with the same characters.
 * When true, the streaming output has regressed (e.g. "Hello world" → "Hello")
 * and the snapshot should be ignored.
 */
fun shouldIgnoreRegressiveStreamingSnapshot(current: String, incoming: String): Boolean {
    if (current.isEmpty() || incoming.isEmpty()) return false
    return incoming.length < current.length && current.startsWith(incoming)
}

/**
 * Merge an agent text snapshot [incoming] into the [current] accumulated text.
 *
 * Handles the common streaming artifact where the LLM returns a progressive
 * snapshot that is shorter than the current text (regression), or a
 * divergent replacement.
 *
 * This is the "raw text layer" protection — it operates on the full text
 * string, not on markdown fragments. Used in conjunction with
 * [coalesceMarkdownFragments] which operates at the fragment level.
 *
 * @return The merged text, preferring the longer/more complete version.
 */
fun mergeAgentTextSnapshot(current: String, incoming: String): String {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    if (incoming == current) return current
    if (shouldIgnoreRegressiveStreamingSnapshot(current, incoming)) {
        return current
    }
    return incoming
}

/**
 * Legacy streaming text merge with full overlap detection and divergent
 * snapshot handling.
 *
 * This is a more conservative merge that handles:
 * - Normal appends (incoming is longer and starts with current)
 * - Suffix-prefix overlaps (deduplicate the common boundary)
 * - Divergent streaming snapshots (mid-stream rewrite)
 * - Fallback concatenation
 *
 * @return The merged text.
 */
fun mergeLegacyStreamingText(current: String, incoming: String): String {
    if (incoming.isEmpty()) return current
    if (current.isEmpty()) return incoming
    if (incoming == current) return current
    if (shouldIgnoreRegressiveStreamingSnapshot(current, incoming)) {
        return current
    }
    // Normal append: incoming is longer and starts with current
    if (incoming.length >= current.length && incoming.startsWith(current)) {
        return incoming
    }
    // Suffix-prefix overlap deduplication
    val overlap = longestSuffixPrefixOverlap(current, incoming)
    if (overlap >= MINIMUM_STREAMING_OVERLAP_LENGTH) {
        return current + incoming.substring(overlap)
    }
    // Divergent snapshot detection
    val commonPrefixLength = commonPrefixLength(current, incoming)
    if (looksLikeDivergentStreamingSnapshot(current, incoming, commonPrefixLength)) {
        return if (incoming.length >= current.length) incoming else current
    }
    // Fallback: concatenate
    return current + incoming
}

/**
 * Calculates the longest suffix of [current] that is also a prefix of
 * [incoming]. Capped at 4096 characters for performance.
 *
 * @return The length of the longest suffix-prefix overlap, or 0 if none.
 */
private fun longestSuffixPrefixOverlap(current: String, incoming: String): Int {
    val maxOverlap = minOf(current.length, incoming.length, 4096)
    for (length in maxOverlap downTo 1) {
        if (incoming.startsWith(current.substring(current.length - length))) {
            return length
        }
    }
    return 0
}

/**
 * Calculates the length of the common prefix shared by [a] and [b].
 */
private fun commonPrefixLength(a: String, b: String): Int {
    val maxLength = minOf(a.length, b.length)
    var index = 0
    while (index < maxLength && a[index].code == b[index].code) {
        index++
    }
    return index
}

/**
 * Determines whether [a] and [b] look like divergent streaming snapshots —
 * i.e., they share a long common prefix but then diverge (mid-stream
 * rewrite). When true, the longer version should be kept instead of
 * concatenating.
 *
 * @param commonPrefixLength The pre-computed common prefix length of [a] and [b].
 */
private fun looksLikeDivergentStreamingSnapshot(
    a: String,
    b: String,
    commonPrefixLength: Int,
): Boolean {
    if (commonPrefixLength < 12) return false
    val shorterLength = minOf(a.length, b.length)
    if (shorterLength == 0) return false
    return commonPrefixLength >= 24 || commonPrefixLength.toFloat() / shorterLength.toFloat() >= 0.6f
}
