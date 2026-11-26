package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessFoldPlannerTest {
    private fun tool(
        id: String,
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
        name: String = "shell_execute",
        ms: Long = 100,
        title: String = "",
    ) = AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms, toolTitle = title)

    private fun text(id: String, content: String) = AssistantBlock(id = id, kind = "text", content = content)
    private fun thinking(id: String, content: String = "…") = AssistantBlock(id = id, kind = "thinking", content = content)
    private fun info(id: String, name: String = "compact") = AssistantBlock(id = id, kind = "info", content = "notice", toolName = name)

    private fun plan(vararg blocks: AssistantBlock, live: Boolean = false) =
        ChatProcessFoldPlanner.plan(blocks.toList(), live, enabled = true)

    private fun off(vararg blocks: AssistantBlock) =
        ChatProcessFoldPlanner.plan(blocks.toList(), isStreaming = false, enabled = false)

    // ── switch off = the pre-feature rendering ──────────────────────────────

    @Test fun `switch off folds nothing at all`() {
        assertTrue(off(tool("a"), tool("b"), tool("c"), text("r", "Done.")).isEmpty())
        assertTrue(off(thinking("t"), tool("a"), text("r", "Done.")).isEmpty())
        assertTrue(off(tool("a"), tool("b"), info("i"), tool("c"), tool("d")).isEmpty())
    }

    // ── the segment model ──────────────────────────────────────────────────

    @Test fun `reasoning folds together with the call it produced`() {
        // One call plus its reasoning is enough: the pair is the unit of work.
        val g = plan(thinking("t"), tool("a", ms = 300), text("r", "Done.")).single()
        assertEquals("t", g.key)
        assertEquals(0..1, g.range)
        assertEquals(setOf(0, 1), g.memberIndices)
        assertEquals(1, g.toolCount)
        assertEquals(300L, g.durationMs)
    }

    @Test fun `reasoning alone never folds`() {
        assertTrue(plan(thinking("t"), text("r", "Done.")).isEmpty())
        assertTrue(plan(thinking("a"), thinking("b"), text("r", "Done.")).isEmpty())
    }

    @Test fun `a single call without reasoning stays a pill`() {
        assertTrue(plan(tool("a"), text("r", "Done.")).isEmpty())
    }

    @Test fun `two finished calls fold even without reasoning`() {
        val g = plan(tool("a", ms = 500), tool("b", ms = 900), text("r", "Done.")).single()
        assertEquals("a", g.key)
        assertEquals(0..1, g.range)
        assertEquals(2, g.toolCount)
        assertEquals(1400L, g.durationMs)
    }

    @Test fun `thinking between calls is a member of the same segment`() {
        val g = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), text("r", "Done."),
        ).single()
        assertEquals(0..3, g.range)
        assertEquals(setOf(0, 1, 2, 3), g.memberIndices)
        assertEquals(2, g.toolCount)
    }

    @Test fun `a reply ends the segment and is never a member`() {
        // A single-line phrase followed by more work is a remark (folds along);
        // a reply is what ends the run, so the boundary case uses prose.
        val segments = plan(
            tool("a"), tool("b"), text("r", "Done.\nSecond line."), tool("c"), tool("d"), text("r2", "Again."),
        )
        assertEquals(listOf(0..1, 3..4), segments.map { it.range })
        assertTrue(segments.none { 2 in it.memberIndices || 5 in it.memberIndices })
    }

    @Test fun `a one-line remark between calls folds along and never counts as a tool`() {
        val g = plan(tool("a"), text("n", "Now checking the log."), tool("b"), text("r", "Done.")).single()
        assertEquals(0..2, g.range)
        assertEquals(2, g.toolCount)
        assertTrue(g.memberIndices.contains(1))
    }

    // ── boundaries ─────────────────────────────────────────────────────────

    @Test fun `info rows and interactive cards cut the segment`() {
        val info = info("i")
        val g = plan(tool("a"), tool("b"), info, tool("c"), tool("d"), text("r", "Done.")).map { it.range }
        assertEquals(listOf(0..1, 3..4), g)
        for (name in listOf("spawn_agent", "subagent_task", "delegate_task", "user_ask", "ask_user")) {
            val barrier = tool("special", name = name)
            val ranges = plan(tool("a"), tool("b"), barrier, tool("c"), tool("d"), text("r", "Done.")).map { it.range }
            assertEquals(listOf(0..1, 3..4), ranges)
        }
    }

    @Test fun `two adjacent segments can never render back to back`() {
        // Every boundary kind renders something visible, so the invariant holds
        // by construction; this pins it against a future "merge" refactor.
        val segments = plan(tool("a"), tool("b"), info("i"), tool("c"), tool("d"), text("r", "Done."))
        assertEquals(2, segments.size)
        // Exactly one block (the info row) sits between the two segments.
        assertEquals(1, segments[1].range.first - segments[0].range.last - 1)
        assertFalse(segments[1].range.first in segments[0].memberIndices)
    }

    // ── interruption ───────────────────────────────────────────────────────

    @Test fun `an interrupted segment folds and reports the cancellation`() {
        val cancelled = tool("c", status = ToolBlockStatus.CANCELLED)
        val g = plan(tool("a"), tool("b"), cancelled, text("r", "Stopped.")).single()
        // Interruption is NOT a reason to open: it folds like any settled run and
        // the summary carries the count. Nothing expands itself but live work.
        assertFalse(g.openByDefault)
        assertEquals(1, g.cancelledCount)
        assertEquals(0, g.failedCount)
    }

    @Test fun `only the running segment ever opens without a tap`() {
        // Settled, interrupted and failed segments all stay closed.
        val settled = plan(tool("a"), tool("b"), text("r", "Done.")).single()
        assertFalse(settled.openByDefault)
        val interrupted = plan(tool("a"), tool("b", status = ToolBlockStatus.CANCELLED), text("r", "Stop.")).single()
        assertFalse(interrupted.openByDefault)
        val failed = plan(tool("a", status = ToolBlockStatus.FAILED), tool("b"), text("r", "Done.")).single()
        assertFalse(failed.openByDefault)
        val running = plan(tool("a"), tool("b"), live = true).single()
        assertTrue(running.openByDefault)
    }

    @Test fun `a settled segment folds and does not open by default`() {
        val g = plan(tool("a"), tool("b"), text("r", "Done.")).single()
        assertFalse(g.openByDefault)
    }

    @Test fun `a cancelled call never counts as a failure`() {
        val g = plan(tool("a"), tool("b", status = ToolBlockStatus.CANCELLED), text("r", "Stopped.")).single()
        assertEquals(0, g.failedCount)
        assertEquals(1, g.cancelledCount)
    }

    @Test fun `failed and timed out calls count as failures`() {
        val g = plan(
            tool("a", status = ToolBlockStatus.FAILED),
            tool("b", status = ToolBlockStatus.TIMEOUT),
            text("r", "Done."),
        ).single()
        assertEquals(2, g.failedCount)
    }

    // ── running turn ───────────────────────────────────────────────────────

    @Test fun `the running segment stays open`() {
        val g = plan(tool("a"), tool("b"), live = true).single()
        assertTrue(g.openByDefault)
        assertEquals(-1, g.hiddenPrefixEnd)
        assertEquals(0, g.hiddenToolCount)
    }

    @Test fun `a long running segment hides its finished prefix`() {
        val blocks = (1..5).map { tool("t$it") }
        val g = plan(*blocks.toTypedArray(), live = true).single()
        assertEquals(5, g.toolCount)
        // Newest three stay visible: the prefix ends right before t3 (index 2).
        assertEquals(1, g.hiddenPrefixEnd)
        assertEquals(2, g.hiddenToolCount)
    }

    @Test fun `a running segment keeps the reasoning it started with`() {
        val g = plan(thinking("t"), tool("a"), tool("b"), live = true).single()
        assertEquals("t", g.key)
        assertEquals(0..2, g.range)
        assertEquals(-1, g.hiddenPrefixEnd)
    }

    @Test fun `the live prefix counts only calls, never the reasoning before them`() {
        val blocks = listOf(thinking("t")) +
            (1..5).map { tool("t$it") }
        val g = plan(*blocks.toTypedArray(), live = true).single()
        assertEquals(2, g.hiddenToolCount)
        // t1 and t2 are hidden; the leading reasoning sits before the cut too.
        assertTrue(g.hiddenPrefixEnd >= 0)
        assertTrue(g.hiddenPrefixEnd < g.range.last)
    }

    // ── title ──────────────────────────────────────────────────────────────

    @Test fun `the title comes from the segment's own reasoning`() {
        val g = plan(
            thinking("t", "**Inspecting the fold rules**\nThen read the planner."),
            tool("a"),
            text("r", "Done."),
        ).single()
        assertEquals("Inspecting the fold rules", g.title)
    }

    @Test fun `the title falls back to the last call label`() {
        val g = plan(tool("a", title = "Read file"), tool("b", title = "Run tests"), text("r", "Done.")).single()
        assertEquals("Run tests", g.title)
    }

    @Test fun `a segment with neither reasoning nor label has no title`() {
        val g = plan(tool("a", name = ""), tool("b", name = ""), text("r", "Done.")).single()
        assertNull(g.title)
    }

    // ── expansion policy ───────────────────────────────────────────────────

    @Test fun `manual choices beat the segment default`() {
        assertTrue(ChatProcessFoldPlanner.isExpanded("a", mapOf("a" to true), false))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", mapOf("a" to false), true))
    }

    @Test fun `the segment default decides when the user has not chosen`() {
        assertTrue(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), true))
        assertFalse(ChatProcessFoldPlanner.isExpanded("a", emptyMap(), false))
    }

    @Test fun `folding depends on blocks and taps only`() {
        // The signature is the contract: no browsing/scroll input can flip a
        // segment open. A second identical call returns the identical state.
        val segments = plan(tool("a"), tool("b"), text("r", "Done."))
        val first = segments.map { ChatProcessFoldPlanner.isExpanded(it.key, emptyMap(), it.openByDefault) }
        val second = segments.map { ChatProcessFoldPlanner.isExpanded(it.key, emptyMap(), it.openByDefault) }
        assertEquals(first, second)
        assertFalse(first.any { it })
    }
}
