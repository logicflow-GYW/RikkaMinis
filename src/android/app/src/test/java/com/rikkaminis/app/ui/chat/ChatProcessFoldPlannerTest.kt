package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessFoldPlannerTest {
    private fun tool(id: String, status: ToolBlockStatus = ToolBlockStatus.SUCCESS, name: String = "shell_execute", ms: Long = 100) =
        AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)
    private fun text(id: String, content: String) = AssistantBlock(id = id, kind = "text", content = content)
    private fun thinking(id: String) = AssistantBlock(id = id, kind = "thinking", content = "…")
    private fun plan(vararg blocks: AssistantBlock, live: Boolean = false) = ChatProcessFoldPlanner.plan(blocks.toList(), live)

    @Test fun `reply-less, single-tool and empty transcripts stay natural`() {
        assertTrue(plan().isEmpty())
        assertTrue(plan(text("a", "answer")).isEmpty())
        assertTrue(plan(tool("one"), text("a", "answer")).isEmpty())
    }

    @Test fun `a run folds once a non-empty reply ends it`() {
        val g = plan(tool("one", ms = 500), tool("two", ms = 900), text("reply", "Done.")).single()
        assertEquals("one", g.key)
        assertEquals(0..1, g.range)
        assertEquals(2, g.toolCount)
        assertEquals(1400L, g.durationMs)
    }

    @Test fun `a live trailing run stays visible but folds once the turn ends`() {
        val blocks = arrayOf(tool("one"), tool("two"), tool("three"))
        assertTrue(plan(*blocks, live = true).isEmpty())
        val finished = plan(*blocks, live = false).single()
        assertEquals(0..2, finished.range)
        assertEquals(3, finished.toolCount)
    }

    @Test fun `one-line remark folds along and soft-ends a run cut by a running tool`() {
        val blocks = listOf(tool("one"), tool("two"), text("note", "Now checking the tests."), tool("running", ToolBlockStatus.RUNNING))
        val g = plan(*blocks.toTypedArray(), live = true).single()
        assertEquals(0..2, g.range)
        assertEquals(2, g.toolCount)
        assertTrue(g.memberIndices.contains(2))
    }

    @Test fun `multi-line text is a reply boundary, not a remark`() {
        val blocks = listOf(
            tool("a"), tool("b"), text("mid", "第一行\n第二行"), tool("c"), tool("d"), text("reply", "Done."),
        )
        val groups = plan(*blocks.toTypedArray())
        assertEquals(listOf(0..1, 3..4), groups.map { it.range })
    }

    @Test fun `leading thinking folds with the run and middle thinking folds along`() {
        val blocks = listOf(thinking("lead"), tool("a"), thinking("mid"), tool("b"), thinking("tail"), text("reply", "Done."))
        val g = plan(*blocks.toTypedArray()).single()
        assertEquals("lead", g.key)
        assertEquals(0..3, g.range)
        assertEquals(2, g.toolCount)
        assertFalse(g.memberIndices.contains(4))
    }

    @Test fun `empty text is transparent and does not break a run`() {
        val blocks = listOf(tool("a"), text("empty", ""), tool("b"), text("reply", "Done."))
        assertEquals(0..2, plan(*blocks.toTypedArray()).single().range)
    }

    @Test fun `info subagent and user prompts are barriers`() {
        val info = AssistantBlock("info", "info", "notice", toolName = "compact")
        val groups = plan(tool("a"), tool("b"), info, tool("c"), tool("d"), text("reply", "Done."))
        // The barrier cut the first run before any reply, so only the last run folds.
        assertEquals(listOf(3..4), groups.map { it.range })
        for (name in listOf("spawn_agent", "subagent_task", "user_ask", "ask_user")) {
            val barrier = tool("special", name = name)
            val gs = plan(tool("a"), tool("b"), barrier, tool("c"), tool("d"), text("reply", "Done."))
            assertEquals(listOf(3..4), gs.map { it.range })
        }
    }

    @Test fun `failure timeout cancel and dedup keep independent buckets`() {
        val g = plan(
            tool("f", ToolBlockStatus.FAILED), tool("t", ToolBlockStatus.TIMEOUT),
            tool("c", ToolBlockStatus.CANCELLED), tool("d", ToolBlockStatus.DEDUPLICATED),
            text("reply", "Done."),
        ).single()
        assertEquals(4, g.toolCount)
        assertEquals(2, g.failedCount)
        assertEquals(1, g.cancelledCount)
        assertEquals(1, g.deduplicatedCount)
    }

    @Test fun `a running tool ends the run so live work is never hidden`() {
        // Cut by an unfinished call with no remark after it: nothing folds.
        assertTrue(plan(tool("a"), tool("b"), tool("live", ToolBlockStatus.RUNNING), text("reply", "Done."), live = true).isEmpty())
        // With a one-line remark the completed prefix folds and the live call stays.
        val withNote = plan(tool("a"), tool("b"), text("note", "Checking."),
            tool("live", ToolBlockStatus.RUNNING), live = true).single()
        assertEquals(0..2, withNote.range)
        assertFalse(withNote.memberIndices.contains(3))
    }

    @Test fun `user can turn folding off and every tool stays visible`() {
        val blocks = (1..6).map { tool("t$it") } + text("reply", "Done.")
        assertTrue(ChatProcessFoldPlanner.plan(blocks, false, enabled = false).isEmpty())
        assertEquals(1, ChatProcessFoldPlanner.plan(blocks, false).size)
    }

    @Test fun `browsing holds back the first fold until following resumes`() {
        assertTrue(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), emptySet(), true))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), emptySet(), false))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), setOf("a"), true))
    }

    @Test fun `manual choices win token growth stop and re-entry`() {
        val live = listOf(tool("a"), tool("b"), text("reply", "Done."))
        assertTrue(ChatProcessFoldPlanner.isExpanded("a", mapOf("a" to true), setOf("a"), false))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", mapOf("a" to false), emptySet(), true))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), emptySet(), false))
    }
}
