package com.rikkaminis.app.ui.chat

/**
 * Decides which runs of an assistant message fold into one "N tools · time" row.
 *
 * Presentation only: neither block order/identity nor tool execution changes.
 * Rule set is ported from the upstream public planner (`ToolFoldPlanner`,
 * 1a71df0f) so the folding reads the same as the app the user compared against:
 *
 *  - A run starts at a finished tool, optionally including the thinking block
 *    that leads directly into it.
 *  - It continues through finished tools (counted), thinking, empty text and
 *    one-line remarks between calls (folded along, NOT counted).
 *  - It ends at a non-empty reply, an info row, a sub-agent / user_ask block,
 *    or a tool that has not finished.
 *  - Only a run of at least [MINIMUM_TOOLS] tools folds. When the run is cut by
 *    a barrier / unfinished tool / message end, only its part up to the latest
 *    remark folds; without such a remark nothing folds, so live work stays
 *    visible instead of collapsing under the user.
 */
object ChatProcessFoldPlanner {
    const val MINIMUM_TOOLS = 2

    /** While a turn runs, this many latest calls stay visible as pills. */
    const val LIVE_RECENT_TOOLS = 3

    data class Group(
        /** Stable identity and placement: the first folded block. */
        val key: String,
        /** First member through last member; everything here is replaced by the row. */
        val range: IntRange,
        val memberIndices: Set<Int>,
        val toolCount: Int,
        val failedCount: Int,
        val cancelledCount: Int,
        val deduplicatedCount: Int,
        /** Sum of recorded execution durations, NOT wall time (parallel calls overlap). */
        val durationMs: Long,
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
                // A one-line remark with a call after it is part of the run;
                // everything after the last call is the reply and never is.
                isSingleLine(block.content) && hasToolAfter(blocks, index) -> Kind.NOTE
                else -> Kind.TEXT_REPLY
            }
            else -> Kind.BARRIER
        }
    }

    /** Stats over an explicit hidden set; null below the minimum. */
    private fun groupOfIndices(blocks: List<AssistantBlock>, hidden: List<Int>): Group? {
        if (hidden.size < MINIMUM_TOOLS) return null
        val tools = hidden.map { blocks[it] }
        return Group(
            key = blocks[hidden.first()].id,
            range = hidden.first()..hidden.last(),
            memberIndices = hidden.toSet(),
            toolCount = tools.size,
            failedCount = tools.count { it.toolStatus == ToolBlockStatus.FAILED || it.toolStatus == ToolBlockStatus.TIMEOUT },
            cancelledCount = tools.count { it.toolStatus == ToolBlockStatus.CANCELLED },
            deduplicatedCount = tools.count { it.toolStatus == ToolBlockStatus.DEDUPLICATED },
            durationMs = tools.sumOf { it.durationMs },
        )
    }

    /** Stats over [range]; null when it holds fewer than the minimum number of tools. */
    private fun group(blocks: List<AssistantBlock>, range: IntRange): Group? {
        val members = range.map { blocks[it] }
        val tools = range.map { blocks[it] }.filter { it.kind == "tool_use" && it.toolName !in interactiveTools && it.toolStatus in terminal }
        if (tools.size < MINIMUM_TOOLS) return null
        return Group(
            key = blocks[range.first].id,
            range = range,
            memberIndices = range.toSet(),
            toolCount = tools.size,
            failedCount = tools.count { it.toolStatus == ToolBlockStatus.FAILED || it.toolStatus == ToolBlockStatus.TIMEOUT },
            cancelledCount = tools.count { it.toolStatus == ToolBlockStatus.CANCELLED },
            deduplicatedCount = tools.count { it.toolStatus == ToolBlockStatus.DEDUPLICATED },
            durationMs = tools.sumOf { it.durationMs },
        )
    }

    fun plan(blocks: List<AssistantBlock>, isStreaming: Boolean, enabled: Boolean = true): List<Group> {
        if (!enabled || blocks.isEmpty()) return emptyList()
        val kinds = blocks.indices.map { kindOf(blocks, it) }
        val out = mutableListOf<Group>()
        var start: Int? = null
        var leadThinking: Int? = null
        var lastMember = 0
        var softEnd: Group? = null

        fun reset() {
            start = null
            softEnd = null
        }
        fun closeAtSoftEnd() {
            softEnd?.let { out += it }
        }

        for (i in kinds.indices) {
            when (kinds[i]) {
                Kind.BARRIER, Kind.TOOL_UNFINISHED -> {
                    // Settled turns (including one the user interrupted) have no
                    // live work left: a run cut by an info row, an interactive
                    // card or a cancelled call must still collapse. While the
                    // turn runs, only the part before the last remark folds.
                    val s = start
                    if (!isStreaming && s != null) {
                        group(blocks, s..lastMember)?.let { out += it }
                    } else {
                        closeAtSoftEnd()
                    }
                    reset()
                    leadThinking = null
                }
                Kind.TOOL_DONE -> {
                    if (start == null) start = leadThinking ?: i
                    leadThinking = null
                    lastMember = i
                }
                Kind.NOTE -> {
                    val s = start
                    if (s == null) {
                        leadThinking = null
                    } else {
                        lastMember = i
                        softEnd = group(blocks, s..i)
                    }
                }
                Kind.THINKING -> if (start == null && leadThinking == null) leadThinking = i
                Kind.TEXT_EMPTY -> Unit
                Kind.TEXT_REPLY -> {
                    val s = start
                    if (s != null) group(blocks, s..lastMember)?.let { out += it }
                    reset()
                    leadThinking = null
                }
            }
        }
        // Live tail: only the part up to the latest remark may fold, so running
        // work stays visible. Once the turn has ended there is no live work
        // left, and a finished run must not stay expanded only because the
        // model ended on a tool call instead of a text reply.
        if (isStreaming) {
            // Running turn: completed steps collapse as soon as they are no
            // longer among the last [LIVE_RECENT_TOOLS] calls, so the transcript
            // shrinks while the model keeps working instead of only at the end.
            val s = start
            if (s == null) {
                closeAtSoftEnd()
            } else {
                val toolPositions = (s..lastMember).filter {
                    kinds[it] == Kind.TOOL_DONE || kinds[it] == Kind.TOOL_UNFINISHED
                }
                val recent = toolPositions.takeLast(LIVE_RECENT_TOOLS).toSet()
                val hidden = (s..lastMember).filter { kinds[it] == Kind.TOOL_DONE && it !in recent }
                groupOfIndices(blocks, hidden)?.let { out += it }
            }
        } else {
            val s = start
            if (s != null) group(blocks, s..lastMember)?.let { out += it } else closeAtSoftEnd()
        }
        return out
    }

    /** Manual choices always win; browsing postpones only a group's FIRST automatic fold. */
    fun isExpanded(key: String, choices: Map<String, Boolean>, foldedOnce: Set<String>, browsing: Boolean): Boolean =
        choices[key] ?: (browsing && key !in foldedOnce)
}
