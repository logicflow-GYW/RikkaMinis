package com.rikkaminis.app.ui.chat

/** Pure policies consumed by ThinkingBlock and the stopped/snapshot full-content viewer. */
object ThinkingReadPlanner {
    const val WINDOW_CHARS = 8_000
    const val INLINE_HARD_CAP = 100_000
    const val CHUNK_CHARS = 4_000
    // Conservative UTF-16 budget (200KB) leaves ample room under Binder's shared 1MB limit.
    const val CLIPBOARD_TEXT_CHARS = 100_000

    data class Anchor(val frozenWindowStart: Int? = null) {
        val isReadingHistory: Boolean get() = frozenWindowStart != null
    }

    fun expanded(manualChoice: Boolean?, autoExpand: Boolean, isStreaming: Boolean, isLast: Boolean): Boolean =
        manualChoice ?: (autoExpand && isStreaming && isLast)

    /** Only a REAL DragInteraction may take control; scrollTo is not a finger drag. */
    fun updateAnchor(old: Anchor, value: Int, max: Int, isDragging: Boolean, tailStart: Int): Anchor {
        val gap = (max - value).coerceAtLeast(0)
        return when {
            isDragging && gap > 4 -> Anchor(old.frozenWindowStart ?: tailStart)
            gap <= 4 -> Anchor()
            else -> old
        }
    }

    fun canCopyAsText(charCount: Int): Boolean = charCount <= CLIPBOARD_TEXT_CHARS

    /** Ranges, not full-string copies: split off main, then materialise only lazy visible rows. */
    fun chunks(content: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < content.length) {
            var end = minOf(content.length, start + CHUNK_CHARS)
            if (end < content.length) {
                var newline = end - 1
                val floor = maxOf(start, end - 200)
                while (newline >= floor && content[newline] != '\n') newline--
                if (newline >= floor) end = newline + 1
                // Never split an emoji's UTF-16 surrogate pair (including on a long single line).
                if (end > start && Character.isHighSurrogate(content[end - 1]) &&
                    Character.isLowSurrogate(content[end])) end--
            }
            ranges += start until end
            start = end
        }
        return ranges
    }
}
