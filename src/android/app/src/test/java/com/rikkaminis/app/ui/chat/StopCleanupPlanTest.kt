package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 C2] Pins the Stop-cleanup decision for a multi-round agent
 * bubble (`ChatQueueInterruption.handleUserCancelledCleanup`, Case 2 tail).
 *
 * Source: shelf report B03 F-02. Two defects in one un-sliced read of the
 * bubble:
 *
 *  · **A — duplicate commit.** `last.content` is the bubble's CROSS-TURN body
 *    (`loopState.accumulatedText + turnText` at every flush) and
 *    `last.toolBlocks` is the run-scoped block list; the old collector
 *    concatenated both, so a round-3 Stop re-wrote rounds 1-2's text into a
 *    fresh assistant row → duplicated reply after reload + duplicated context.
 *  · **B — no arm.** The chain `if(empty&&!tool&&!histAsst) drop` /
 *    `if(text&&!histAsst) persist` / `else if(histAsst) resume` left
 *    (`no text`, `tool activity`, `history ends on USER(tool_result)`) — exactly
 *    "round 1 was pure tool_use, Stop during round 2's thinking gap" — matching
 *    nothing: bubble kept, `_canResume` false, Resume banner gone.
 *
 * Every expectation below is a literal string / boolean (evidence discipline):
 * none is derived from the implementation, so mutating the boundary
 * (`indexOfLast` → `indexOfFirst`, `>=` direction), the join separator, or the
 * arm order cannot move an expectation along with it and stay green.
 */
class StopCleanupPlanTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"

    private fun text(id: String, content: String) = AssistantBlock(id = id, kind = "text", content = content)
    private fun toolUse(id: String) = AssistantBlock(id = id, kind = "tool_use", content = "")
    private fun thinking(id: String) = AssistantBlock(id = id, kind = "thinking", content = "thinking…")

    // ── Defect A: only the in-flight round's text is committed ─────────────

    @Test
    fun `single-turn text stop commits the streamed text exactly once`() {
        val blocks = listOf(text("t1", "答案是四十二。"))
        assertEquals("答案是四十二。", unpersistedTurnText(blocks))

        val plan = planStopCleanup(blocks, historyEndsWithAssistant = false)
        assertEquals("答案是四十二。", plan.persistText)
        assertFalse(plan.dropPlaceholder)
        assertTrue(plan.canResume)
    }

    @Test
    fun `multi-round tool stop commits only the round in flight`() {
        // Round 1 text is already durable (early-turn persist) and visible in
        // BOTH bubble carriers; round 2's text is the only unpersisted text.
        val blocks = listOf(
            text("t1", "Let me check that."),
            toolUse("call_1"),
            text("t2", "Checking now"),
        )
        val plan = planStopCleanup(blocks, historyEndsWithAssistant = false)
        assertEquals("Checking now", plan.persistText)
        assertFalse(
            "round 1 text must never ride along: ${plan.persistText}",
            plan.persistText!!.contains("Let me check"),
        )
    }

    @Test
    fun `the LAST tool_use defines the boundary, not the first`() {
        val blocks = listOf(
            toolUse("call_1"),
            text("t2", "old round text"),
            toolUse("call_2"),
            text("t3", "new round text"),
        )
        assertEquals("new round text", unpersistedTurnText(blocks))
    }

    @Test
    fun `text after two completed tool rounds is the third round only`() {
        val blocks = listOf(
            text("t1", "A"),
            toolUse("c1"),
            text("t2", "B"),
            toolUse("c2"),
            text("t3", "C"),
        )
        assertEquals("C", unpersistedTurnText(blocks))
        assertEquals("C", planStopCleanup(blocks, historyEndsWithAssistant = false).persistText)
    }

    @Test
    fun `several text blocks of the in-flight round keep order and separation`() {
        val blocks = listOf(
            toolUse("c1"),
            text("t2a", "first"),
            thinking("th"),
            text("t2b", "second"),
        )
        assertEquals("first\nsecond", unpersistedTurnText(blocks))
    }

    @Test
    fun `empty text blocks contribute nothing`() {
        val blocks = listOf(toolUse("c1"), text("t2", ""), text("t3", "real"))
        assertEquals("real", unpersistedTurnText(blocks))
    }

    @Test
    fun `a tool round produced no text yet means nothing to commit`() {
        val blocks = listOf(text("t1", "round one"), toolUse("c1"))
        assertEquals("", unpersistedTurnText(blocks))
    }

    // ── Defect B: every kept bubble is resumable ───────────────────────────

    @Test
    fun `pure-tool first round then thinking-gap stop keeps the bubble resumable`() {
        // Round 1: tool_use only (no text). Stop lands in round 2's gap.
        // Pre-fix: all three arms false → no Resume banner, bubble frozen on
        // a tool_result tail with no way to continue.
        val plan = planStopCleanup(listOf(toolUse("call_1")), historyEndsWithAssistant = false)
        assertFalse(plan.dropPlaceholder)
        assertNull(plan.persistText)
        assertTrue("the interrupted multi-step task must be resumable", plan.canResume)
    }

    @Test
    fun `two pure-tool rounds then thinking-gap stop is resumable too`() {
        val plan = planStopCleanup(
            listOf(toolUse("call_1"), toolUse("call_2")),
            historyEndsWithAssistant = false,
        )
        assertTrue(plan.canResume)
        assertNull(plan.persistText)
    }

    @Test
    fun `thinking-only placeholder is dropped`() {
        val plan = planStopCleanup(listOf(thinking("th")), historyEndsWithAssistant = false)
        assertTrue(plan.dropPlaceholder)
        assertNull(plan.persistText)
        assertFalse(plan.canResume)
    }

    @Test
    fun `empty bubble before the first chunk is dropped`() {
        val plan = planStopCleanup(emptyList(), historyEndsWithAssistant = false)
        assertTrue(plan.dropPlaceholder)
        assertFalse(plan.canResume)
    }

    @Test
    fun `history already ending on the assistant turn never commits twice`() {
        val blocks = listOf(text("t1", "partial answer"))
        val plan = planStopCleanup(blocks, historyEndsWithAssistant = true)
        assertNull(plan.persistText)
        assertTrue(plan.canResume)
        assertFalse(plan.dropPlaceholder)
    }

    @Test
    fun `one Stop appends once - the second Stop sees its own row`() {
        val blocks = listOf(text("t1", "partial answer"))
        // First Stop: nothing committed yet for this bubble → commit.
        assertEquals("partial answer", planStopCleanup(blocks, false).persistText)
        // Second Stop on the same bubble: Case 2 committed an ASSISTANT row in
        // between, so `agentHistory` now ends on the assistant turn.
        assertNull(planStopCleanup(blocks, true).persistText)
    }

    @Test
    fun `thinking blocks alone never make a bubble committable`() {
        val blocks = listOf(thinking("th1"), thinking("th2"))
        assertEquals("", unpersistedTurnText(blocks))
        assertTrue(planStopCleanup(blocks, historyEndsWithAssistant = false).dropPlaceholder)
    }

    // ── Source contract: the wiring in ChatQueueInterruption ───────────────

    @Test
    fun `the cleanup tail asks the pure planner and keeps its arms`() {
        val src = readRepoFile(sourcePath)
        assertTrue(
            "the decision must come from the pure planner",
            // [R5-C C-C-06] The call gained a third input — the reminder-tail
            // probe — the planner is still the single decision source.
            src.contains(
                "val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant, reminderTail)",
            ),
        )
        assertTrue(
            "the placeholder drop must be the planner's arm",
            src.contains("if (stopPlan.dropPlaceholder) {"),
        )
        assertTrue(
            "the commit must use the planner's slice",
            src.contains("val partialText = stopPlan.persistText"),
        )
        assertTrue(
            "every kept bubble resumes",
            src.contains("if (stopPlan.canResume) _canResume.value = true"),
        )
        // The two defect shapes must not come back.
        assertFalse(
            "collecting the bubble's cross-turn content is defect A",
            src.contains("if (last.content.isNotEmpty()) append(last.content)"),
        )
        assertFalse(
            "a resume arm gated on historyEndsWithAssistant is defect B",
            src.contains("} else if (historyEndsWithAssistant) {"),
        )
    }

    @Test
    fun `an in-flight tool cancel still short-circuits before the planner`() {
        val src = readRepoFile(sourcePath)
        val case1 = src.indexOf("if (hadInflightTools) {")
        val planner = src.indexOf(
            "val stopPlan = planStopCleanup(last.toolBlocks, historyEndsWithAssistant, reminderTail)",
        )
        assertTrue("Case 1 block not found", case1 > 0)
        assertTrue("planner call not found", planner > 0)
        assertTrue("Case 1 (tool cancel) must return before the text tail", case1 < planner)
        assertTrue(
            "Case 1 owns the CANCELLED flip of in-flight blocks",
            src.contains("b.copy(toolStatus = ToolBlockStatus.CANCELLED)"),
        )
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    // ── [R5-C C-C-06] continuation-reminder tail ────────────────────────────

    @Test
    fun `empty bubble stopped over the reminder drops both rows and stays resumable`() {
        val plan = planStopCleanup(
            emptyList(),
            historyEndsWithAssistant = false,
            historyEndsWithContinuationReminder = true,
        )
        assertTrue(plan.dropPlaceholder)
        assertNull(plan.persistText)
        assertTrue(plan.canResume)
        assertTrue(plan.dropContinuationReminder)
    }

    @Test
    fun `empty bubble over a real user tail keeps the pre-existing non-resumable drop`() {
        val plan = planStopCleanup(emptyList(), historyEndsWithAssistant = false)
        assertTrue(plan.dropPlaceholder)
        assertFalse(plan.canResume)
        assertFalse(plan.dropContinuationReminder)
    }

    @Test
    fun `reminder tail matcher accepts only the verbatim reminder written as a user turn`() {
        assertTrue(isContinuationReminderTail(LLMMessage.Role.USER, CONTINUATION_REMINDER_TEXT))
        assertFalse(isContinuationReminderTail(LLMMessage.Role.USER, "continue please"))
        assertFalse(isContinuationReminderTail(LLMMessage.Role.ASSISTANT, CONTINUATION_REMINDER_TEXT))
        assertFalse(isContinuationReminderTail(LLMMessage.Role.USER, null))
    }
}
