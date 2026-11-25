package com.rikkaminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class ChatProcessFoldPlannerTest {
    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS, name: String = "shell_execute", ms: Long = 100) =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)
    private fun plan(vararg blocks: AssistantBlock, live: Boolean = false) = ChatProcessFoldPlanner.plan(blocks.toList(), live)

    @Test fun `no tools and a single tool stay natural`() {
        assertTrue(plan().isEmpty())
        assertTrue(plan(AssistantBlock("text", "text", "answer")).isEmpty())
        assertTrue(plan(tool("one")).isEmpty())
    }
    @Test fun `completed consecutive calls use summed execution time`() {
        val group = plan(tool("one", ms = 500), tool("two", ms = 900)).single()
        assertEquals("one", group.key)
        assertEquals(2, group.toolCount)
        assertEquals(setOf(0, 1), group.memberIndices)
        assertEquals(1400L, group.durationMs)
    }
    @Test fun `failure timeout cancel dedup are independent summary buckets`() {
        val g = plan(tool("f", ToolBlockStatus.FAILED), tool("t", ToolBlockStatus.TIMEOUT),
            tool("c", ToolBlockStatus.CANCELLED), tool("d", ToolBlockStatus.DEDUPLICATED)).single()
        assertEquals(4, g.toolCount)
        assertEquals(2, g.failedCount)
        assertEquals(1, g.cancelledCount)
        assertEquals(1, g.deduplicatedCount)
    }
    @Test fun `live latest three tools remain outside the folded members`() {
        val blocks = (0..7).map { tool("t$it") }
        val g = ChatProcessFoldPlanner.plan(blocks, true).single()
        assertEquals(5, g.toolCount)
        assertEquals(setOf(0, 1, 2, 3, 4), g.memberIndices)
    }
    @Test fun `all unfinished parallel calls stay visible even before latest three`() {
        for (status in listOf(ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING, ToolBlockStatus.RUNNING)) {
            val blocks = (0..7).map { tool("t$it", if (it == 0 || it == 2) status else ToolBlockStatus.SUCCESS) }
            val g = ChatProcessFoldPlanner.plan(blocks, true).single()
            assertEquals(setOf(1, 3, 4), g.memberIndices)
            assertEquals("t0", g.key)
            val done = ChatProcessFoldPlanner.plan(blocks.map { it.copy(toolStatus = ToolBlockStatus.SUCCESS) }, false).single()
            assertEquals(g.key, done.key)
            assertEquals(8, done.toolCount)
        }
        assertTrue(plan(tool("unknown").copy(toolStatus = null), tool("one")).isEmpty())
    }
    @Test fun `text order boundaries and a running tool are never hidden`() {
        val blocks = listOf(AssistantBlock("A", "text", "A"), tool("one"), tool("two", ToolBlockStatus.FAILED),
            AssistantBlock("B", "text", "B"), tool("three", ToolBlockStatus.RUNNING), AssistantBlock("C", "text", "C"))
        val groups = ChatProcessFoldPlanner.plan(blocks, false)
        assertEquals(1, groups.size)
        val hidden = groups.flatMap { it.memberIndices }.toSet()
        assertEquals(listOf("A", "B", "three", "C"), blocks.filterIndexed { i, _ -> i !in hidden }.map { it.id })
        assertEquals(1, groups.single().failedCount)
    }
    @Test fun `thinking short notes info and final answers are conservative boundaries`() {
        for (kind in listOf("thinking", "text", "info")) {
            val middle = AssistantBlock("middle", kind, "A short note")
            val groups = plan(tool("one"), tool("two"), middle, tool("three"), tool("four"))
            assertEquals(2, groups.size)
            assertFalse(groups.any { 2 in it.memberIndices })
        }
    }
    @Test fun `subagents and waiting user prompts never fold`() {
        for (name in listOf("spawn_agent", "subagent_task", "delegate_task", "user_ask", "ask_user")) {
            val groups = plan(tool("a"), tool("b"), tool("interaction", name = name), tool("c"), tool("d"))
            assertEquals(2, groups.size)
            assertFalse(groups.any { 2 in it.memberIndices })
        }
    }
    @Test fun `browsing holds back the first fold until following resumes`() {
        assertTrue(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), emptySet(), true))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), emptySet(), false))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), setOf("a"), true))
    }
    @Test fun `manual choices win token growth stop and re-entry`() {
        val blocks = (0..7).map { tool("t$it") }
        val live = ChatProcessFoldPlanner.plan(blocks, true).single()
        val stopped = ChatProcessFoldPlanner.plan(blocks, false).single()
        assertEquals(live.key, stopped.key)
        assertTrue(ChatProcessFoldPlanner.isExpanded(stopped.key, mapOf(live.key to true), setOf(live.key), false))
        assertFalse(ChatProcessFoldPlanner.isExpanded(live.key, mapOf(live.key to false), emptySet(), true))
        // A different message/session owns fresh collections; identical block ids do not inherit choices.
        assertFalse(ChatProcessFoldPlanner.isExpanded(live.key, emptyMap(), emptySet(), false))
    }
}
