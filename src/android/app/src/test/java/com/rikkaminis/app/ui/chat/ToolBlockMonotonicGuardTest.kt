package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-streamlining-thinking-fix] Tests for [ToolBlockMonotonicGuard] — the
 * monotonic terminal-state guard that prevents a tool block from regressing
 * from a terminal status (SUCCESS/FAILED/TIMEOUT/CANCELLED) back to an alive
 * status (RUNNING/STREAMING/PENDING), which was a candidate root cause of
 * "tool card stuck as being called".
 */
class ToolBlockMonotonicGuardTest {

    private fun block(
        id: String,
        status: ToolBlockStatus? = null,
        content: String = "",
    ) = AssistantBlock(
        id = id,
        kind = "tool_use",
        content = content,
        toolStatus = status,
    )

    // ── 活态 → 终态：放行 ──────────────────────────────────────────────
    @Test
    fun `alive to terminal is allowed`() {
        val prev = listOf(block("t1", ToolBlockStatus.RUNNING))
        val next = listOf(block("t1", ToolBlockStatus.SUCCESS))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertTrue("no regression should be reported", result.regressions.isEmpty())
        assertEquals(ToolBlockStatus.SUCCESS, result.blocks.single().toolStatus)
    }

    @Test
    fun `streaming to failed is allowed`() {
        val prev = listOf(block("t1", ToolBlockStatus.STREAMING))
        val next = listOf(block("t1", ToolBlockStatus.FAILED))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertTrue(result.regressions.isEmpty())
        assertEquals(ToolBlockStatus.FAILED, result.blocks.single().toolStatus)
    }

    // ── 终态 → 活态：clamp，保留终态 ──────────────────────────────────
    @Test
    fun `terminal to alive is clamped, terminal preserved`() {
        val prev = listOf(block("t1", ToolBlockStatus.SUCCESS, content = "done"))
        val next = listOf(block("t1", ToolBlockStatus.RUNNING, content = "regressed"))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertEquals(1, result.regressions.size)
        val r = result.regressions.single()
        assertEquals("t1", r.blockId)
        assertEquals(ToolBlockStatus.SUCCESS, r.prevStatus)
        assertEquals(ToolBlockStatus.RUNNING, r.nextStatus)
        // The guarded list keeps prev's terminal block (content "done"),
        // not the regressed one.
        assertEquals(ToolBlockStatus.SUCCESS, result.blocks.single().toolStatus)
        assertEquals("done", result.blocks.single().content)
    }

    @Test
    fun `timed_out to pending is clamped`() {
        val prev = listOf(block("t1", ToolBlockStatus.TIMEOUT))
        val next = listOf(block("t1", ToolBlockStatus.PENDING))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertEquals(1, result.regressions.size)
        assertEquals(ToolBlockStatus.TIMEOUT, result.blocks.single().toolStatus)
    }

    @Test
    fun `cancelled to streaming is clamped`() {
        val prev = listOf(block("t1", ToolBlockStatus.CANCELLED))
        val next = listOf(block("t1", ToolBlockStatus.STREAMING))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertEquals(1, result.regressions.size)
        assertEquals(ToolBlockStatus.CANCELLED, result.blocks.single().toolStatus)
    }

    // ── prev 为 null 或空：放行 ──────────────────────────────────────
    @Test
    fun `null prev passes through`() {
        val next = listOf(block("t1", ToolBlockStatus.RUNNING))
        val result = ToolBlockMonotonicGuard.guard(null, next)
        assertTrue(result.regressions.isEmpty())
        assertEquals(next, result.blocks)
    }

    @Test
    fun `empty prev passes through`() {
        val next = listOf(block("t1", ToolBlockStatus.RUNNING))
        val result = ToolBlockMonotonicGuard.guard(emptyList(), next)
        assertTrue(result.regressions.isEmpty())
        assertEquals(next, result.blocks)
    }

    // ── 正常 append / 多块混合：不受影响 ─────────────────────────────
    @Test
    fun `append of new block is unaffected`() {
        val prev = listOf(block("t1", ToolBlockStatus.SUCCESS))
        val next = listOf(block("t1", ToolBlockStatus.SUCCESS), block("t2", ToolBlockStatus.RUNNING))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertTrue(result.regressions.isEmpty())
        assertEquals(2, result.blocks.size)
        assertEquals(ToolBlockStatus.RUNNING, result.blocks[1].toolStatus)
    }

    @Test
    fun `mixed list only clamps the regressed id`() {
        val prev = listOf(
            block("t1", ToolBlockStatus.SUCCESS),
            block("t2", ToolBlockStatus.RUNNING),
            block("t3", ToolBlockStatus.CANCELLED),
        )
        // t1 regresses SUCCESS→PENDING; t2 stays alive; t3 stays terminal.
        val next = listOf(
            block("t1", ToolBlockStatus.PENDING),
            block("t2", ToolBlockStatus.RUNNING),
            block("t3", ToolBlockStatus.CANCELLED),
        )
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertEquals(1, result.regressions.size)
        assertEquals(ToolBlockStatus.SUCCESS, result.blocks[0].toolStatus) // clamped back
        assertEquals(ToolBlockStatus.RUNNING, result.blocks[1].toolStatus) // untouched
        assertEquals(ToolBlockStatus.CANCELLED, result.blocks[2].toolStatus) // untouched
    }

    @Test
    fun `id absent in prev is passed through untouched`() {
        val prev = listOf(block("t1", ToolBlockStatus.SUCCESS))
        val next = listOf(block("t1", ToolBlockStatus.SUCCESS), block("t9", ToolBlockStatus.RUNNING))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        assertTrue(result.regressions.isEmpty())
        assertEquals(ToolBlockStatus.RUNNING, result.blocks[1].toolStatus)
    }

    // ── isTerminal edge cases ───────────────────────────────────────
    @Test
    fun `terminal to null is clamped without crash`() {
        val prev = listOf(block("t1", ToolBlockStatus.SUCCESS, content = "done"))
        val next = listOf(block("t1", status = null, content = "lost"))
        val result = ToolBlockMonotonicGuard.guard(prev, next)
        // Regresses to null (no status) — must clamp to terminal, not crash.
        assertEquals(ToolBlockStatus.SUCCESS, result.blocks.single().toolStatus)
        assertEquals("done", result.blocks.single().content)
    }

    @Test
    fun `isTerminal classification`() {
        assertTrue(ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.SUCCESS))
        assertTrue(ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.FAILED))
        assertTrue(ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.TIMEOUT))
        assertTrue(ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.CANCELLED))
        assertTrue(!ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.RUNNING))
        assertTrue(!ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.STREAMING))
        assertTrue(!ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.PENDING))
        assertTrue(!ToolBlockMonotonicGuard.isTerminal(null))
    }
}
