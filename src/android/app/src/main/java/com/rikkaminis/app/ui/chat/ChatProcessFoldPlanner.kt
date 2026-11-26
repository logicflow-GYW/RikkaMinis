package com.rikkaminis.app.ui.chat

/**
 * Decides which stretches of an assistant message collapse into one "process" row.
 *
 * Presentation only: neither block order/identity nor tool execution changes.
 *
 * The fold unit is a **process segment**, not a set of tools:
 *
 *  - A segment is `[leading thinking] + [finished tool calls, with thinking,
 *    empty text and one-line remarks between them] + [trailing remark]`.
 *  - Thinking that led into the calls is a MEMBER of the segment, so a collapsed
 *    segment hides the reasoning together with the calls it produced. Folding
 *    the calls alone left the reasoning behind as a row of orphan bars that
 *    pointed at nothing — that is the defect this model replaces.
 *  - A segment ends at a non-empty reply, an info row, a sub-agent / user_ask
 *    card, or an unfinished call. Those boundaries always render something, so
 *    two folded summaries can never sit next to each other.
 *  - It folds when it holds at least two finished calls, or at least one call
 *    together with thinking. Reasoning with no calls never folds.
 *  - Folding is deterministic from block state alone: NOTHING expands itself
 *    because of scrolling, history browsing or an interruption. A segment that
 *    holds a cancelled call folds like any other and simply reports the
 *    cancellation in its summary line — the user's own next message explains why
 *    the run stopped, so an extra banner would only repeat it.
 *  - The ONE exception is the segment that is still running: it stays open so
 *    live work is visible, and only its last [LIVE_RECENT_TOOLS] calls render;
 *    older finished ones collapse behind a count line.
 */
object ChatProcessFoldPlanner {
    /** Calls kept visible in the running turn's trailing segment. */
    const val LIVE_RECENT_TOOLS = 3

    /** Minimum finished calls for a segment that carries no reasoning. */
    const val MINIMUM_TOOLS = 2

    data class Segment(
        /** Stable identity: the id of the first block in the segment. */
        val key: String,
        /** First member through last member; the whole span is replaced by the row. */
        val range: IntRange,
        val memberIndices: Set<Int>,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
        val deduplicatedCount: Int,
        /** Sum of recorded execution durations, NOT wall time (parallel calls overlap). */
        val durationMs: Long,
        /** Action phrase from the segment's last reasoning, else the last call's title. */
        val title: String?,
        /** The only reason to be open without a user tap: the segment is running. */
        val openByDefault: Boolean,
        /**
         * Leading members collapsed even while the segment is open: the running
         * turn keeps only its last [LIVE_RECENT_TOOLS] calls visible and hides
         * the finished prefix behind a count line. Always a prefix of [range].
         */
        val hiddenPrefixEnd: Int = -1,
        /** Calls inside [hiddenPrefixEnd] — what the count line reports. */
        val hiddenToolCount: Int = 0,
    )

    private val interactiveTools = setOf(
        "spawn_agent", "subagent_task", "delegate_task", "user_ask", "ask_user",
    )

    private val terminal = setOf(
        ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT,
        ToolBlockStatus.CANCELLED, ToolBlockStatus.DEDUPLICATED,
    )

    private enum class Kind { TOOL_DONE, TOOL_UNFINISHED, THINKING, TEXT_EMPTY, TEXT_REPLY, NOTE, BARRIER }

    /** One line by line breaks: no newline between the first and last visible char. */
    fun isSingleLine(text: String): Boolean {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return true
        val last = text.indexOfLast { !it.isWhitespace() }
        for (i in first..last) if (text[i] == '\n' || text[i] == '\r') return false
        return true
    }

    private fun hasToolAfter(blocks: List<AssistantBlock>, index: Int): Boolean =
        (index + 1 until blocks.size).any { blocks[it].kind == "tool_use" }

    private fun kindOf(blocks: List<AssistantBlock>, index: Int): Kind {
        val block = blocks[index]
        return when (block.kind) {
            "tool_use" -> when {
                block.toolName in interactiveTools -> Kind.BARRIER
                block.toolStatus in terminal -> Kind.TOOL_DONE
                else -> Kind.TOOL_UNFINISHED
            }
            "thinking" -> Kind.THINKING
            "info" -> Kind.BARRIER
            "text" -> when {
                block.content.isBlank() -> Kind.TEXT_EMPTY
                // A one-line remark with a call after it belongs to the segment;
                // everything after the last call is the reply and never does.
                isSingleLine(block.content) && hasToolAfter(blocks, index) -> Kind.NOTE
                else -> Kind.TEXT_REPLY
            }
            else -> Kind.BARRIER
        }
    }

    /** Action phrase for a segment: its last reasoning, else the last call's label. */
    private fun titleOf(blocks: List<AssistantBlock>, range: IntRange, kinds: List<Kind>): String? {
        for (i in range.reversed()) {
            if (kinds[i] == Kind.THINKING) {
                extractThinkingTitle(blocks[i].content)?.let { return it }
                break
            }
        }
        for (i in range.reversed()) {
            if (kinds[i] == Kind.TOOL_DONE) {
                val tool = blocks[i]
                if (tool.toolTitle.isNotBlank()) return tool.toolTitle
                if (tool.toolName.isNotBlank()) return tool.toolName
                break
            }
        }
        return null
    }

    // The default is OFF, matching the shipped setting: a caller that forgets
    // to pass the user's choice gets the pre-feature transcript, not folding.
    fun plan(blocks: List<AssistantBlock>, isStreaming: Boolean, enabled: Boolean = false): List<Segment> {
        if (!enabled || blocks.isEmpty()) return emptyList()
        val kinds = blocks.indices.map { kindOf(blocks, it) }

        // Maximal member runs: everything between two visible boundaries.
        val runs = ArrayList<IntRange>()
        var start = -1
        var last = -1
        for (i in kinds.indices) {
            when (kinds[i]) {
                Kind.BARRIER, Kind.TOOL_UNFINISHED, Kind.TEXT_REPLY -> {
                    if (start >= 0 && last >= start) runs += start..last
                    start = -1
                    last = -1
                }
                Kind.TOOL_DONE, Kind.THINKING, Kind.TEXT_EMPTY, Kind.NOTE -> {
                    if (start < 0) start = i
                    last = i
                }
            }
        }
        if (start >= 0 && last >= start) runs += start..last

        val out = ArrayList<Segment>(runs.size)
        for (range in runs) {
            val finished = range.filter { kinds[it] == Kind.TOOL_DONE }
            val hasThinking = range.any { kinds[it] == Kind.THINKING }
            // Reasoning folds together with the calls it produced; calls alone
            // still need two of them, and reasoning alone never folds.
            val folds = finished.size >= MINIMUM_TOOLS || (finished.size == 1 && hasThinking)
            if (!folds) continue
            val tools = finished.map { blocks[it] }
            val trailing = range.last == blocks.lastIndex
            val live = isStreaming && trailing
            // Running work is the only thing that opens itself. A cancelled run
            // is settled: it folds and says so in the summary (cancelledCount).
            val openByDefault = live
            // A live segment shows only its newest calls; the finished prefix
            // collapses behind a count line so the transcript keeps shrinking
            // while the model works instead of growing until the turn ends.
            val keepFrom = if (live && finished.size > LIVE_RECENT_TOOLS) finished.size - LIVE_RECENT_TOOLS else 0
            out += Segment(
                key = blocks[range.first].id,
                range = range,
                memberIndices = range.toSet(),
                toolCount = tools.size,
                failedCount = tools.count {
                    it.toolStatus == ToolBlockStatus.FAILED || it.toolStatus == ToolBlockStatus.TIMEOUT
                },
                cancelledCount = tools.count { it.toolStatus == ToolBlockStatus.CANCELLED },
                deduplicatedCount = tools.count { it.toolStatus == ToolBlockStatus.DEDUPLICATED },
                durationMs = tools.sumOf { it.durationMs },
                title = titleOf(blocks, range, kinds),
                openByDefault = openByDefault,
                hiddenPrefixEnd = if (keepFrom > 0) finished[keepFrom] - 1 else -1,
                hiddenToolCount = keepFrom,
            )
        }
        return out
    }

    /**
     * A segment is open only when the user opened it or when it is still
     * running. Scrolling never changes this: the previous rule expanded every
     * segment that had not been folded yet while the list was being scrolled,
     * which read as "the whole transcript blew open" exactly when the user was
     * trying to read it.
     */
    fun isExpanded(
        key: String,
        choices: Map<String, Boolean>,
        openByDefault: Boolean,
    ): Boolean = choices[key] ?: openByDefault
}
