package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-0916] Boundary tests for the compact greying walk
 * ([applyCompactGreyedRange]). The greying used to be inline in the live
 * compact path with zero coverage, and its tail repair was a no-op (an
 * inverted condition selecting rows that were NOT greyed), so when the walk
 * never met the anchor's row the whole list — including the in-flight
 * streaming bubble — stayed greyed until the next rebuild. Observed
 * on-device 2026-09-16.
 */
class ChatCompactGrayingTest {

    private fun row(
        id: String,
        role: String = "user",
        isCompactedHistory: Boolean = false,
        isStreaming: Boolean = false,
        isQueued: Boolean = false,
        sourceDbIds: List<String> = emptyList(),
        toolName: String? = null,
        payload: String = "",
    ): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = "body of $id",
        isCompactedHistory = isCompactedHistory,
        isStreaming = isStreaming,
        isQueued = isQueued,
        sourceDbIds = sourceDbIds,
        toolBlocks = if (toolName != null) {
            listOf(AssistantBlock(id = "$id-block", kind = "tool_use", toolName = toolName, toolArgs = payload))
        } else emptyList(),
    )

    // ── the flip ───────────────────────────────────────────────

    @Test
    fun `rows up to the anchor id flip point stay greyed and the tail stays clear`() {
        val h = listOf(row("u1"), row("a1", "assistant"), row("u2"), row("a2", "assistant"))
        val out = applyCompactGreyedRange(h, "a1")
        assertTrue(out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
        assertFalse(out[2].isCompactedHistory)
        assertFalse(out[3].isCompactedHistory)
    }

    @Test
    fun `a restored row carrying the anchor id in sourceDbIds flips the walk`() {
        val h = listOf(
            row("u1"),
            row("a1", "assistant", sourceDbIds = listOf("anchorDbId")),
            row("u2"),
        )
        val out = applyCompactGreyedRange(h, "anchorDbId")
        assertTrue(out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
        assertFalse(out[2].isCompactedHistory)
    }

    // ── the tail repair (the 2026-09-16 find) ──────────────────

    @Test
    fun `no-flip walk must not leave the in-flight tail greyed`() {
        // The anchor id matches NO UI row (the live-session norm when the
        // anchor is a tool-result carrier): the walk greys every non-system
        // row. The repair must put the tail — everything after the last
        // settled row, and at-or-after the last user prompt — back to full
        // opacity. The old repair was a no-op and left exactly this state.
        val h = listOf(
            row("u1"),
            row("a1", "assistant"),
            row("u2"),
            row("a2", "assistant", isStreaming = true),
            row("u3", isQueued = true),
        )
        val out = applyCompactGreyedRange(h, "no-such-anchor")
        assertFalse("the current instruction must never be greyed", out[2].isCompactedHistory)
        assertFalse("the streaming answer must never be greyed", out[3].isCompactedHistory)
        assertFalse("the queued prompt must never be greyed", out[4].isCompactedHistory)
        assertTrue("the compacted range stays greyed", out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
    }

    @Test
    fun `tail repair also heals a stale flag when the walk flips late`() {
        // A merged row carrying the union of sourceDbIds can flip the walk
        // LATE (below the current instruction), leaving the tail greyed; the
        // old repair was gated on "the walk never flipped" and skipped this.
        val h = listOf(
            row("u1"),
            row("a1", "assistant"),
            row("u2"),
            row("a2", "assistant", isStreaming = true, sourceDbIds = listOf("anchorDbId")),
        )
        val out = applyCompactGreyedRange(h, "anchorDbId")
        assertFalse("the current instruction must never be greyed", out[2].isCompactedHistory)
        assertFalse("the streaming answer must never be greyed", out[3].isCompactedHistory)
        assertTrue("rows above the instruction stay greyed", out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
    }

    // ── [audit-0916-fix] the repair must not un-grey IN-RANGE rows ──

    @Test
    fun `a one-turn session keeps its only prompt greyed`() {
        // The walk-back cannot move earlier than the sole user prompt, so the
        // anchor IS that prompt. The unconditional repair used to treat
        // "at-or-after the last settled user prompt" as active and rendered
        // the folded instruction at full opacity while the divider still
        // counted it as compacted.
        val h = listOf(row("u1"), row("a1", "assistant"))
        val out = applyCompactGreyedRange(h, "u1")
        assertTrue("the folded instruction must stay greyed", out[0].isCompactedHistory)
        assertFalse("the kept answer stays clear", out[1].isCompactedHistory)
    }

    @Test
    fun `an anchor below the last prompt never un-greys the rows it folded`() {
        // compactBefore(anchor = last assistant row): everything up to and
        // including the anchor is inside the compacted range.
        val h = listOf(row("u1"), row("a1", "assistant"), row("u2"), row("a2", "assistant"))
        val out = applyCompactGreyedRange(h, "a2")
        assertTrue(out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
        assertTrue("the folded instruction must stay greyed", out[2].isCompactedHistory)
        assertTrue("the folded answer must stay greyed", out[3].isCompactedHistory)
    }

    @Test
    fun `a settled anchor still clears the tail that follows it`() {
        val h = listOf(row("u1"), row("a1", "assistant"), row("u2"), row("a2", "assistant"))
        val out = applyCompactGreyedRange(h, "a1")
        assertTrue(out[0].isCompactedHistory)
        assertTrue(out[1].isCompactedHistory)
        assertFalse(out[2].isCompactedHistory)
        assertFalse(out[3].isCompactedHistory)
    }

    // ── invariants ─────────────────────────────────────────────

    @Test
    fun `system rows are never greyed and prior dividers are dropped`() {
        val h = listOf(
            // [fix/silent-auto-compact-notice-leak] The payload is what
            // distinguishes a divider from a notice sharing its iconKind —
            // see isCompactDividerRow. A fixture without one is not a
            // production shape.
            row("div", "system", toolName = "compact", payload = "SUMMARY"), // a prior divider: dropped
            row("notice", "system"),                          // kept, never greyed
            row("u1"),
            row("a1", "assistant"),
            row("u2"),
            row("a2", "assistant"),
        )
        val out = applyCompactGreyedRange(h, "a1")
        assertEquals(5, out.size)
        assertEquals("notice", out[0].id)
        assertFalse(out[0].isCompactedHistory)
        assertTrue("the compacted range stays greyed", out[1].isCompactedHistory)
        assertTrue(out[2].isCompactedHistory)
        assertFalse("the kept tail stays clear", out[3].isCompactedHistory)
        assertFalse(out[4].isCompactedHistory)
    }

    @Test
    fun `a notice sharing the divider iconKind is not dropped with it`() {
        // [fix/silent-auto-compact-notice-leak] The hard-trim / context-full /
        // failure notices are `appendSystemInfo(..., iconKind = "compact")`
        // rows — the SAME toolName as the divider. The old inline predicate
        // matched on toolName alone and deleted them along with the card, so a
        // manual compact silently swallowed a "context reached the limit"
        // notice the user had just been shown. Only the divider carries a
        // summary payload.
        val h = listOf(
            row("trim", "system", toolName = "compact"),                       // notice: kept
            row("div", "system", toolName = "compact", payload = "SUMMARY"),   // divider: dropped
            row("u1"),
            row("a1", "assistant"),
        )
        val out = applyCompactGreyedRange(h, "a1")
        assertEquals(listOf("trim", "u1", "a1"), out.map { it.id })
    }

    @Test
    fun `an empty list and an all-system list are safe`() {
        assertTrue(applyCompactGreyedRange(emptyList(), "x").isEmpty())
        val out = applyCompactGreyedRange(listOf(row("n", "system")), "x")
        assertEquals(1, out.size)
        assertFalse(out[0].isCompactedHistory)
    }
}
