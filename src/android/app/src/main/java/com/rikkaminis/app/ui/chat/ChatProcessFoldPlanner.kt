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
 *    together with thinking. Reasoning with no calls never folds, and a segment
 *    short enough to fit on screen is not worth a row of its own.
 *
 * While the turn runs, the trailing segment keeps a **tail**: the newest
 * [TAIL_STEPS] steps (a step = one thinking block plus the calls it produced)
 * stay rendered, capped at [MAX_TAIL_BLOCKS] blocks. A model may follow one
 * thought with two calls or with thirty, so counting steps — not blocks, and not
 * calls — is what keeps the shape honest in both directions. The tail exists
 * only while something has actually been absorbed; until then the segment is not
 * emitted at all and the transcript looks exactly like the plain renderer.
 *
 * When the turn settles (reply, interruption, cancellation) the tail goes away
 * and the whole segment collapses to its row.
 */
object ChatProcessFoldPlanner {
    /** Newest steps kept visible in the running segment's tail. */
    const val TAIL_STEPS = 2

    /** Hard cap on tail blocks, so one thought followed by thirty calls stays short. */
    const val MAX_TAIL_BLOCKS = 6

    /** Minimum finished calls for a segment that carries no reasoning. */
    const val MINIMUM_TOOLS = 2

    /** How a segment renders right now. */
    enum class Mode {
        /** Only the summary row; every member is hidden. */
        COLLAPSED,

        /** Summary row plus the live tail; the absorbed prefix is hidden. */
        TAIL,

        /** Summary row plus every member (the user opened it). */
        FULL,
    }

    data class Segment(
        /** Stable identity: the id of the first block in the segment. */
        val key: String,
        /** First member through last member. */
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
        /** True while the turn runs and this is the trailing segment with a tail. */
        val liveTail: Boolean = false,
        /**
         * First member of the live tail. Members before it are absorbed into the
         * summary row. -1 when the segment has no tail.
         */
        val tailStart: Int = -1,
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

    /**
     * First index of the live tail inside [range], or [range].first when the
     * whole segment fits. A step starts at the leading block and at every
     * thinking block after it, so `thought → 5 calls → thought → 3 calls` keeps
     * the last two steps; a single thought followed by thirty calls is capped by
     * [MAX_TAIL_BLOCKS] instead.
     */
    private fun tailStartOf(range: IntRange, kinds: List<Kind>): Int {
        val stepStarts = ArrayList<Int>()
        for (i in range) {
            if (i == range.first || kinds[i] == Kind.THINKING) stepStarts += i
        }
        val bySteps = stepStarts.takeLast(TAIL_STEPS).first()
        val byBlocks = range.last - MAX_TAIL_BLOCKS + 1
        return maxOf(bySteps, byBlocks, range.first)
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
            // A run is LIVE while the turn streams and nothing settled came after
            // it. Testing `range.last == blocks.lastIndex` was wrong: an unfinished
            // call is a BOUNDARY, so the run in front of a running call looked
            // settled and collapsed to its row. During a turn the last call is
            // usually RUNNING, which made the whole process disappear behind one
            // row at exactly the moment the user wanted to watch it.
            // (`none { BARRIER || TEXT_REPLY }` is the same test: a run can only be
            // followed by TOOL_UNFINISHED, TEXT_REPLY or BARRIER.)
            val live = isStreaming && (range.last + 1..blocks.lastIndex).none {
                kinds[it] == Kind.BARRIER || kinds[it] == Kind.TEXT_REPLY
            }
            val tailStart = if (live) tailStartOf(range, kinds) else -1
            // Nothing absorbed yet: emit no segment at all, so a running turn that
            // still fits on screen renders exactly like the plain transcript.
            if (live && tailStart <= range.first) continue
            val tools = finished.map { blocks[it] }
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
                liveTail = live,
                tailStart = tailStart,
            )
        }
        return out
    }

    /**
     * Render mode for [segment]. A tap always wins; otherwise the running
     * segment shows its tail and a settled one is collapsed. Scrolling is not an
     * input: the previous rule expanded every not-yet-folded segment while the
     * list was being scrolled, which read as the transcript blowing open.
     */
    fun mode(
        key: String,
        choices: Map<String, Boolean>,
        liveTail: Boolean,
    ): Mode = when (choices[key]) {
        true -> Mode.FULL
        false -> Mode.COLLAPSED
        null -> if (liveTail) Mode.TAIL else Mode.COLLAPSED
    }

    /**
     * Choice a tap on the summary row records, or null to clear the explicit
     * choice and fall back to [mode]'s default.
     *
     * Closing a RUNNING segment must land on its tail, not on the collapsed
     * row: writing `false` there made the explicit choice outrank the live
     * default, so one open-then-close during a turn removed the tail for the
     * rest of that turn (and for every later one, since the choice sticks).
     * Only a settled segment has a collapsed state worth remembering.
     */
    fun nextChoice(current: Mode, liveTail: Boolean): Boolean? = when (current) {
        Mode.COLLAPSED, Mode.TAIL -> true
        Mode.FULL -> if (liveTail) null else false
    }
}
