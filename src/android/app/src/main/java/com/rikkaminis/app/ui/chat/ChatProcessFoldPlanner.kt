package com.rikkaminis.app.ui.chat

/** Presentation only: neither block order/identity nor tool execution is changed. */
object ChatProcessFoldPlanner {
    const val MINIMUM_TOOLS = 2
    const val LIVE_RECENT_TOOLS = 3

    data class Group(
        val key: String,
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

    fun isFoldable(block: AssistantBlock): Boolean =
        block.kind == "tool_use" && block.toolName !in interactiveTools && when (block.toolStatus) {
            ToolBlockStatus.SUCCESS, ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT,
            ToolBlockStatus.CANCELLED, ToolBlockStatus.DEDUPLICATED -> true
            else -> false
        }

    /**
     * Every non-tool/interactive tool is a boundary. Text (including short notes) and
     * thinking remain in original order, so the answer is never guessed into a summary.
     * One key per contiguous TOOL segment survives parallel completions and stream stop.
     * Unfinished calls and the latest three remain visible, even inside that segment.
     */
    fun plan(blocks: List<AssistantBlock>, isStreaming: Boolean): List<Group> {
        val recent = if (isStreaming) {
            blocks.indices.filter { blocks[it].kind == "tool_use" }.takeLast(LIVE_RECENT_TOOLS).toSet()
        } else emptySet()
        val groups = mutableListOf<Group>()
        var start = 0
        while (start < blocks.size) {
            if (blocks[start].kind != "tool_use" || blocks[start].toolName in interactiveTools) {
                start++
                continue
            }
            var end = start + 1
            while (end < blocks.size && blocks[end].kind == "tool_use" && blocks[end].toolName !in interactiveTools) end++
            val indices = (start until end).filter { it !in recent && isFoldable(blocks[it]) }.toSet()
            if (indices.size >= MINIMUM_TOOLS) {
                val members = indices.map { blocks[it] }
                groups += Group(
                    key = blocks[start].id,
                    range = start until end,
                    memberIndices = indices,
                    toolCount = members.size,
                    failedCount = members.count {
                        it.toolStatus == ToolBlockStatus.FAILED || it.toolStatus == ToolBlockStatus.TIMEOUT
                    },
                    cancelledCount = members.count { it.toolStatus == ToolBlockStatus.CANCELLED },
                    deduplicatedCount = members.count { it.toolStatus == ToolBlockStatus.DEDUPLICATED },
                    durationMs = members.sumOf { it.durationMs },
                )
            }
            start = end
        }
        return groups
    }

    /** Manual choices always win; browsing postpones only a group's FIRST automatic fold. */
    fun isExpanded(key: String, choices: Map<String, Boolean>, foldedOnce: Set<String>, browsing: Boolean): Boolean =
        choices[key] ?: (browsing && key !in foldedOnce)
}
