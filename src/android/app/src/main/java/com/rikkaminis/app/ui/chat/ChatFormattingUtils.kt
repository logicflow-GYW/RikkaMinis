package com.rikkaminis.app.ui.chat

import java.util.Locale

/**
 * Pure formatting helpers extracted from [ChatToolFormatting] so they can be
 * JVM-tested without loading Compose classes.
 *
 * The original functions in [ChatToolFormatting] are removed; callers resolve
 * these from the same package.
 */

// [audit-0917] DateTimeFormatter, not a shared SimpleDateFormat: minSdk is 26
// so java.time is available natively, and SimpleDateFormat carries mutable
// calendar state — this formatter is a top-level val used from both Compose
// composition and tool-execution coroutines, where a concurrent format() call
// could interleave and emit a garbled timestamp.
internal val stepTimestampFormatter: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

internal fun formatStepTimestamp(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(stepTimestampFormatter)

internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "conversation_history" -> "history"
    "web_search" -> "search"
    else -> toolName
}

internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "RikkaMinis is using Shell"
    "file_read" -> "RikkaMinis is reading File"
    "file_write" -> "RikkaMinis is using Editor"
    "file_edit" -> "RikkaMinis is editing File"
    "browser_use" -> "RikkaMinis is using Browser"
    "read_image" -> "RikkaMinis is reading Image"
    "memory_write", "memory_get" -> "RikkaMinis is using Memory"
    "conversation_history" -> "RikkaMinis is reading History"
    "web_search" -> "RikkaMinis is using Search"
    else -> "RikkaMinis is using ${toolDisplayName(toolName)}"
}

internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}

// ─── Thinking title (live header label) ──────────────────────────────────────

/**
 * How many chars of the END of a thinking block [extractThinkingTitle] may
 * inspect. A live block grows on every delta (recomposition re-runs the
 * extraction on each length change) and can reach the 100k hard cap, so the
 * scan is bounded to the tail — where the newest action phrase lives anyway.
 * Cost per call is O(scan budget), not O(block size).
 */
internal const val THINKING_TITLE_SCAN_BUDGET = 4000

/**
 * Longest phrase accepted as a title. Anything longer is prose that happened to
 * start with `**`, not an action label — skip it and keep scanning upwards.
 * The header ellipsizes, so this only has to keep whole paragraphs out.
 */
internal const val THINKING_TITLE_MAX_CHARS = 160

private val THINKING_TITLE_BOLD_RE = Regex("""\*\*(.+?)\*\*""")

/**
 * Latest action phrase inside a thinking block's text, or null when there is
 * none — used as the LIVE header label of a thinking row.
 *
 * Ported from rikkahub's `extractThinkingTitle` (its last line that consists of
 * a single bold span), then adapted to this app's real data. Two shapes are
 * observed in our own sessions (2026-09-28 samples):
 *
 *  • concatenated phrases on one line — `**我统计子包层级****我把子包层级也数清楚**`
 *    (session 4a9ba4d3, 11:32). The verbatim whole-line rule returns the merged
 *    blob `我统计子包层级****我把子包层级也数清楚`; the NEWEST span (`我把子包层级也数清楚`)
 *    is the useful one, so a pure bold run takes its last span.
 *  • a phrase followed by its explanation — `**Clarifying the scope first…** Before …`.
 *    Here the phrase is the line-leading span, so that one wins.
 *
 * Line-leading only: an inline `**emphasis**` mid-sentence is not a title, and a
 * phrase inside a fenced code block is never reported (see the parity note in
 * the body): a tail still left inside an unterminated fence refuses outright —
 * thinking text of a coding agent often ends inside a code fence.
 *
 * Frozen blocks are deliberately excluded by the caller (title shows while
 * streaming only, matching rikkahub, whose title is likewise gated on
 * `loading`): a title on every row of a long transcript rebuilds the per-row
 * title wall that the 2026-09-28 pill-fold experiment was reverted for.
 *
 * Pure + Android-free, so the sandbox JVM harness compiles it directly.
 */
internal fun extractThinkingTitle(
    text: CharSequence,
    scanBudget: Int = THINKING_TITLE_SCAN_BUDGET,
): String? {
    if (text.isEmpty()) return null
    val windowStart = (text.length - scanBudget).coerceAtLeast(0)
    val lines = text.subSequence(windowStart, text.length).toString().split('\n')
    // When the window starts mid-text its first line is a fragment, whose
    // "line-leading" bold span is not line-leading at all — never read it.
    val lowest = if (windowStart > 0) 1 else 0
    // Fence parity, computed TOP-DOWN: `insideFence[i]` is true when an odd
    // number of ``` markers precede line i. A bottom-up walk cannot answer "is
    // this bold line inside a fence?" — the marker that opens the fence sits
    // ABOVE the line, so the walk reaches the candidate first and reports it.
    // Measured against this function (2026-09-28): `**old**\n```\n**fake
    // title**\n` used to return `fake title`. Ceiling: a window that itself
    // opens inside an unterminated fence inverts the parity — accepted, since
    // the 4000-char budget is far longer than a fenced sample.
    val insideFence = BooleanArray(lines.size)
    var fenceOpen = false
    for (i in lines.indices) {
        insideFence[i] = fenceOpen
        if (lines[i].trim().startsWith("```")) fenceOpen = !fenceOpen
    }
    // A tail still inside an unterminated fence refuses outright: for a coding
    // agent that is a code sample, and a phrase above it may belong to the
    // sample too. Keeps the refusal the pinned test asserts.
    if (insideFence[lines.size - 1]) return null
    for (idx in lines.indices.reversed()) {
        if (idx < lowest) break
        if (insideFence[idx]) continue
        val line = lines[idx].trim()
        if (!line.startsWith("**")) continue
        val spans = THINKING_TITLE_BOLD_RE.findAll(line).map { it.groupValues[1].trim() }.toList()
        if (spans.isEmpty()) continue
        val leadingOnlyBold = THINKING_TITLE_BOLD_RE.replace(line, "").isBlank()
        val candidate = if (leadingOnlyBold) spans.last() else spans.first()
        if (candidate.isNotEmpty() && candidate.length <= THINKING_TITLE_MAX_CHARS) return candidate
    }
    return null
}

// ─── Thinking header extras (length / phase duration) ────────────────────────

/**
 * Char-count label at the right edge of a thinking header (`9K`). Truncating
 * division, unchanged from the inline expression it replaces: 12_345 reads
 * `12K`.
 *
 * ponytail: 沿用旧内联表达式的截断取整，不做本地化 | 天花板: 12_000–12_999 一律显示
 * `12K` | 升级触发: 用户反馈这个数字看起来不对。
 */
internal fun thinkingCharCountLabel(charCount: Int): String = when {
    charCount >= 1000 -> "${charCount / 1000}K"
    else -> "$charCount"
}

/**
 * The optional right-edge extras of a thinking header row. Both are
 * user-toggleable (Settings → Appearance → Deep Thinking), so an all-null
 * instance is a legal state: the header then shows only its icon, label and
 * chevron.
 */
internal data class ThinkingHeaderExtras(
    val duration: String?,
    val charCount: String?,
)

/**
 * [T-thinking-header-toggles] Pure decision of what a thinking header shows on
 * its right edge. Extracted from the composable so the full toggle matrix is
 * JVM-testable without Compose.
 *
 * With both flags ON this reproduces the pre-toggle behaviour exactly:
 *  • [showDuration] — only for a FINISHED block carrying a duration stamp. The
 *    stamp is in-session only ([AssistantBlock] is not persisted), so a
 *    reloaded conversation shows no duration even with the flag ON; that is
 *    also why turning the char count OFF can leave the right edge empty after
 *    a reload.
 *  • [showCharCount] — whenever the block has content.
 */
internal fun thinkingHeaderExtras(
    showDuration: Boolean,
    showCharCount: Boolean,
    isStreaming: Boolean,
    durationMs: Long,
    charCount: Int,
): ThinkingHeaderExtras {
    val duration = if (showDuration && !isStreaming && durationMs > 0L) {
        formatToolDuration(durationMs)
    } else {
        null
    }
    val chars = if (showCharCount && charCount > 0) thinkingCharCountLabel(charCount) else null
    return ThinkingHeaderExtras(duration = duration, charCount = chars)
}