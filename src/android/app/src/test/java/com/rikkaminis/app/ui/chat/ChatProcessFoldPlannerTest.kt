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
    ) = AssistantBlock(id = id, kind = "tool_use", toolStatus = status, toolName = name, durationMs = ms)

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
        val g = plan(thinking("t1"), tool("a"), thinking("t2"), tool("b"), text("r", "Done.")).single()
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
        val ranges = plan(tool("a"), tool("b"), info("i"), tool("c"), tool("d"), text("r", "Done.")).map { it.range }
        assertEquals(listOf(0..1, 3..4), ranges)
        for (name in listOf("spawn_agent", "subagent_task", "delegate_task", "user_ask", "ask_user")) {
            val barrier = tool("special", name = name)
            val cut = plan(tool("a"), tool("b"), barrier, tool("c"), tool("d"), text("r", "Done.")).map { it.range }
            assertEquals(listOf(0..1, 3..4), cut)
        }
    }

    @Test fun `an unfinished call ends the segment instead of joining it`() {
        val ranges = plan(
            tool("a"), tool("b"), tool("live", status = ToolBlockStatus.RUNNING), tool("c"), tool("d"),
            text("r", "Done."),
        ).map { it.range }
        assertEquals(listOf(0..1, 3..4), ranges)
    }

    // ── the live tail ──────────────────────────────────────────────────────

    @Test fun `a running segment that still fits on screen is not folded at all`() {
        // Nothing absorbed yet => no row, no hiding: the transcript renders like
        // the plain renderer until there is something to absorb.
        assertTrue(plan(thinking("t"), tool("a"), live = true).isEmpty())
        assertTrue(plan(tool("a"), tool("b"), live = true).isEmpty())
        assertTrue(plan(thinking("t"), tool("a"), tool("b"), live = true).isEmpty())
    }

    @Test fun `the tail keeps the newest two steps`() {
        // thought → 1 call → thought → 1 call → thought → 1 call
        val g = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), thinking("t3"), tool("c"),
            live = true,
        ).single()
        assertEquals(0..5, g.range)
        assertTrue(g.liveTail)
        // Last two steps start at t2 (index 2): the third step starts at 4, so the
        // tail is t2..c.
        assertEquals(2, g.tailStart)
    }

    @Test fun `one thought followed by many calls keeps only the newest blocks`() {
        val blocks = listOf(thinking("t")) + (1..30).map { tool("t$it") }
        val g = plan(*blocks.toTypedArray(), live = true).single()
        assertEquals(30, g.toolCount)
        assertTrue(g.liveTail)
        // Two steps would be the whole segment, so the block cap decides instead:
        // the tail is the last MAX_TAIL_BLOCKS of the 31 blocks.
        assertEquals(31 - ChatProcessFoldPlanner.MAX_TAIL_BLOCKS, g.tailStart)
    }

    @Test fun `a run followed by a still-running call keeps its tail`() {
        // The common shape during a turn: finished work, then the call that is
        // running right now. The unfinished call is a boundary, so the run before
        // it must NOT be treated as settled — otherwise the whole process
        // collapses to one row exactly while the user is watching it.
        val g = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), thinking("t3"), tool("c"),
            tool("running", status = ToolBlockStatus.RUNNING),
            live = true,
        ).single()
        assertEquals(0..5, g.range)
        assertTrue(g.liveTail)
        assertEquals(2, g.tailStart)
    }

    @Test fun `a run followed by a reply is settled even while the turn streams`() {
        // A reply after the run means the model has moved on: collapse it.
        val segments = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"),
            text("r", "Done.\nSecond line."),
            thinking("t3"), tool("c"), tool("d"),
            live = true,
        )
        val first = segments.first { it.range.first == 0 }
        assertFalse(first.liveTail)
        assertEquals(-1, first.tailStart)
    }

    @Test fun `a settled segment has no tail`() {
        val g = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), thinking("t3"), tool("c"),
            text("r", "Done."),
        ).single()
        assertFalse(g.liveTail)
        assertEquals(-1, g.tailStart)
    }

    @Test fun `an interrupted segment is settled, not live`() {
        val g = plan(
            tool("a"), tool("b", status = ToolBlockStatus.CANCELLED), text("r", "Stopped."),
        ).single()
        assertFalse(g.liveTail)
        assertEquals(1, g.cancelledCount)
        assertEquals(0, g.failedCount)
    }

    @Test fun `failed and timed out calls count as failures, cancelled does not`() {
        val g = plan(
            tool("a", status = ToolBlockStatus.FAILED),
            tool("b", status = ToolBlockStatus.TIMEOUT),
            tool("c", status = ToolBlockStatus.CANCELLED),
            text("r", "Done."),
        ).single()
        assertEquals(2, g.failedCount)
        assertEquals(1, g.cancelledCount)
    }

    // ── render mode ────────────────────────────────────────────────────────

    @Test fun `a tap always wins over the segment default`() {
        assertEquals(ChatProcessFoldPlanner.Mode.FULL, ChatProcessFoldPlanner.mode("a", mapOf("a" to true), false))
        assertEquals(ChatProcessFoldPlanner.Mode.COLLAPSED, ChatProcessFoldPlanner.mode("a", mapOf("a" to false), true))
    }

    @Test fun `the default follows whether the segment is live`() {
        assertEquals(ChatProcessFoldPlanner.Mode.TAIL, ChatProcessFoldPlanner.mode("a", emptyMap(), true))
        assertEquals(ChatProcessFoldPlanner.Mode.COLLAPSED, ChatProcessFoldPlanner.mode("a", emptyMap(), false))
    }

    @Test fun `render mode depends on blocks and taps only`() {
        val segments = plan(tool("a"), tool("b"), text("r", "Done."))
        val first = segments.map { ChatProcessFoldPlanner.mode(it.key, emptyMap(), it.liveTail) }
        val second = segments.map { ChatProcessFoldPlanner.mode(it.key, emptyMap(), it.liveTail) }
        assertEquals(first, second)
        assertEquals(listOf(ChatProcessFoldPlanner.Mode.COLLAPSED), first)
    }

    // ── tap decisions (2026-11-26 regression) ───────────────────────────────

    @Test fun `closing a running segment clears the choice so the tail returns`() {
        // The defect: closing wrote an explicit `false`, which outranks the live
        // default for the rest of the turn AND every later turn (the choice is
        // saved). Opening then closing once made the tail disappear for good.
        assertNull(ChatProcessFoldPlanner.nextChoice(ChatProcessFoldPlanner.Mode.FULL, liveTail = true))
        // ...and with the choice cleared, the running segment defaults back to TAIL.
        assertEquals(
            ChatProcessFoldPlanner.Mode.TAIL,
            ChatProcessFoldPlanner.mode("a", emptyMap(), liveTail = true),
        )
        // Tapping a tail opens the whole segment; tapping an opened one closes it.
        assertEquals(true, ChatProcessFoldPlanner.nextChoice(ChatProcessFoldPlanner.Mode.TAIL, liveTail = true))
        assertEquals(true, ChatProcessFoldPlanner.nextChoice(ChatProcessFoldPlanner.Mode.COLLAPSED, liveTail = true))
    }

    @Test fun `a settled segment still remembers being closed`() {
        assertEquals(true, ChatProcessFoldPlanner.nextChoice(ChatProcessFoldPlanner.Mode.COLLAPSED, liveTail = false))
        assertEquals(false, ChatProcessFoldPlanner.nextChoice(ChatProcessFoldPlanner.Mode.FULL, liveTail = false))
        // And the remembered `false` keeps it closed once the turn settles.
        assertEquals(
            ChatProcessFoldPlanner.Mode.COLLAPSED,
            ChatProcessFoldPlanner.mode("a", mapOf("a" to false), liveTail = false),
        )
    }

    // ── "close everything" must reach rows that are not composed ────────────

    @Test fun `a close-everything retires choices recorded before it`() {
        // 2026-11-26 device report: the back-to-newest button closed the rows on
        // screen but left the ones further up open. The retire used to be an
        // effect INSIDE the row, and a row scrolled out of the list is not
        // composed at all — so it never ran for exactly the rows that needed it.
        val opened = mapOf("a" to true, "b" to true)
        // Recorded under epoch 3, still epoch 3 → in force.
        assertEquals(opened, ChatProcessFoldPlanner.liveChoices(opened, 3, 3))
        // Recorded under epoch 3, the button has since bumped to 4 → retired.
        assertEquals(emptyMap<String, Boolean>(), ChatProcessFoldPlanner.liveChoices(opened, 3, 4))
        // A row that never recorded anything is unaffected either way.
        assertEquals(emptyMap<String, Boolean>(), ChatProcessFoldPlanner.liveChoices(emptyMap(), 0, 7))
        // A choice recorded AFTER the bump survives it — this is what makes a
        // tap following the button work at all.
        assertEquals(opened, ChatProcessFoldPlanner.liveChoices(opened, 5, 5))
        // Retired means the settled default: collapsed, not "stuck open".
        assertEquals(
            ChatProcessFoldPlanner.Mode.COLLAPSED,
            ChatProcessFoldPlanner.mode("a", ChatProcessFoldPlanner.liveChoices(opened, 3, 4), liveTail = false),
        )
    }

    @Test fun `a note or empty text between the run and a running call keeps the tail`() {
        // A turn routinely emits an empty text block or a one-line note between
        // the finished run and the call that is still running. Those are members,
        // not boundaries, so the segment is still live and keeps its tail.
        val withEmptyText = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), thinking("t3"), tool("c"),
            text("gap", "   "),
            tool("running", status = ToolBlockStatus.RUNNING),
            live = true,
        ).single()
        assertTrue(withEmptyText.liveTail)
        assertEquals(2, withEmptyText.tailStart)

        val withNote = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"), thinking("t3"), tool("c"),
            text("note", "Checking the parser now."),
            tool("running", status = ToolBlockStatus.RUNNING),
            live = true,
        ).single()
        assertTrue(withNote.liveTail)
        assertEquals(2, withNote.tailStart)
    }

    @Test fun `a barrier after the run still settles it mid-turn`() {
        // An info row or a sub-agent card always renders something, so the
        // segment in front of it is finished work — no tail.
        val g = plan(
            thinking("t1"), tool("a"), thinking("t2"), tool("b"),
            info("i"),
            tool("running", status = ToolBlockStatus.RUNNING),
            live = true,
        ).single()
        assertFalse(g.liveTail)
        assertEquals(-1, g.tailStart)
    }
}
