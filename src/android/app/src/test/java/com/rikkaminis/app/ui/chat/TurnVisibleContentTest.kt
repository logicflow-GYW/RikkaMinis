package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1017 ISSUE-03 + ISSUE-02] Two defects in the `toolCalls.isEmpty()`
 * tail of `AgentLoopEngine.runAgentLoop`, both pinned here.
 *
 * ISSUE-03 — the tail asked "did this turn show the user anything?" of the
 * RUN-scoped accumulators (`loopState.accumulatedText` / `allToolBlocks`).
 * Both are append-only for the whole run, so from the first tool-call turn
 * onward the answer was permanently `true` and
 * `if (!hasVisibleContent && finishedCleanly)` could never fire again: the
 * empty-turn recovery (including the `NudgeExistingPart(isToolResult = true)`
 * arm, i.e. exactly the "model stalled after a tool result" case the reminder
 * exists for) was unreachable, and the run stopped silently on an empty reply.
 *
 * ISSUE-02 — the turn's row finalize (`updateAssistantMessage(..., false, ...)`)
 * sat ABOVE the four `continue` branches (empty-turn recovery ×2, EOF-stub
 * continuation, EOF one-shot retry). `loopState.assistantId` is minted once per
 * run, so those branches stream into the row we just finalized — and a
 * finalized row rejects every later delta (`StaleStreamPublishPolicy`
 * DROP_FINALIZED: canonical `isStreaming=false` plus the side channel drained
 * by the finalize itself; nothing in `runAgentLoop` flips the row back, because
 * the `isStreaming = true` writers only touch `_streamingById` and
 * `mergeStreamingOverlay` overlays ids that are present there). The bubble
 * froze for the rest of the run.
 *
 * Every expectation below is a literal (evidence discipline): none is derived
 * from the implementation, so mutating the predicate or moving the finalize
 * cannot move an expectation with it and keep the suite green. The two
 * source-slice tests carry the placement contract, which no value-level
 * assertion can express.
 */
class TurnVisibleContentTest {

    private val sourcePath = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    private fun text(id: String, content: String) = AssistantBlock(id = id, kind = "text", content = content)
    private fun toolUse(id: String) = AssistantBlock(id = id, kind = "tool_use", content = "")
    private fun thinking(id: String, content: String) = AssistantBlock(id = id, kind = "thinking", content = content)

    // ── ISSUE-03: the predicate answers for THIS turn ───────────────────────

    @Test
    fun `an empty turn block slice carries no visible content`() {
        // Turn 0 dispatched tools (those blocks are NOT in this slice) and
        // turn 1 streamed nothing: the question is about turn 1, so the answer
        // must be "nothing visible" — that is the whole point of the fix.
        assertFalse(TurnVisibleContent.hasVisibleContent(turnText = "", turnBlocks = emptyList()))
    }

    @Test
    fun `whitespace-only turn text is not visible content`() {
        assertFalse(TurnVisibleContent.hasVisibleContent(turnText = "   \n\t ", turnBlocks = emptyList()))
    }

    @Test
    fun `streamed turn text is visible content`() {
        assertTrue(TurnVisibleContent.hasVisibleContent(turnText = "hello", turnBlocks = emptyList()))
    }

    @Test
    fun `a rendered tool_use block in this turn is visible content`() {
        // Parity with the pre-fix expression: a tool capsule the user can see
        // counts, even with no text.
        assertTrue(TurnVisibleContent.hasVisibleContent(turnText = "", turnBlocks = listOf(toolUse("t1"))))
    }

    @Test
    fun `a text block counts only when it carries non-blank content`() {
        assertFalse(TurnVisibleContent.hasVisibleContent(turnText = "", turnBlocks = listOf(text("b1", ""))))
        assertFalse(TurnVisibleContent.hasVisibleContent(turnText = "", turnBlocks = listOf(text("b1", "  "))))
        assertTrue(TurnVisibleContent.hasVisibleContent(turnText = "", turnBlocks = listOf(text("b1", "x"))))
    }

    @Test
    fun `a thinking-only block is not visible content - parity with the pre-fix predicate`() {
        // The pre-fix expression listed exactly two kinds ("tool_use", "text");
        // thinking was never visible content, so a thinking-only empty turn
        // still owns the recovery/hint path. Pinned so a future "improvement"
        // of the predicate is a deliberate decision, not a drift.
        assertFalse(
            TurnVisibleContent.hasVisibleContent(
                turnText = "",
                turnBlocks = listOf(thinking("k1", "a very long chain of thought")),
            ),
        )
    }

    @Test
    fun `finished cleanly accepts a null stop with no truncation and rejects a truncated turn`() {
        assertTrue(TurnVisibleContent.finishedCleanly(null, false))
        assertTrue(TurnVisibleContent.finishedCleanly("stop", false))
        assertTrue(TurnVisibleContent.finishedCleanly("end_turn", false))
        assertFalse(TurnVisibleContent.finishedCleanly("length", false))
        assertFalse(TurnVisibleContent.finishedCleanly("tool_calls", false))
        // A truncated turn is never "clean", even when the stop reason is null
        // or "stop" — the EOF retry branch owns it.
        assertFalse(TurnVisibleContent.finishedCleanly(null, true))
        assertFalse(TurnVisibleContent.finishedCleanly("stop", true))
    }

    @Test
    fun `the empty-turn trigger fires for an empty turn after a tool turn`() {
        // The audited scenario, end to end at the predicate: turn 1 streamed
        // nothing and finished with "stop".
        assertTrue(
            TurnVisibleContent.isEmptyTurn("", emptyList(), turnFinishReason = "stop", turnTruncated = false),
        )
        // …and stays off when the turn showed anything.
        assertFalse(
            TurnVisibleContent.isEmptyTurn("answer", emptyList(), turnFinishReason = "stop", turnTruncated = false),
        )
    }

    // ── Source contract: the call sites ────────────────────────────────────

    @Test
    fun `the tail reads per-turn state, never the run-scoped accumulators`() {
        val engine = readRepoFile(sourcePath)
        assertTrue(
            "tail must scope the block slice to this turn",
            engine.contains("val turnVisibleBlocks = loopState.allToolBlocks.drop(turnStartBlockIndex)"),
        )
        assertTrue(
            "tail must ask the pure predicate with this turn's text + blocks",
            engine.contains("TurnVisibleContent.hasVisibleContent(turnText, turnVisibleBlocks)"),
        )
        assertTrue(
            "the trigger must be the one extracted decision",
            engine.contains(
                "TurnVisibleContent.isEmptyTurn(turnText, turnVisibleBlocks, turnFinishReason, turnTruncated)",
            ),
        )
        assertFalse(
            "the run-scoped read is the defect — it must not come back",
            engine.contains("loopState.accumulatedText.isNotBlank()"),
        )
    }

    // ── ISSUE-02: the finalize sits after every continue branch ────────────

    @Test
    fun `the row finalize runs once and after every continue branch`() {
        val engine = readRepoFile(sourcePath)
        val finalize = "host.updateAssistantMessage(loopState.assistantId, loopState.accumulatedText, false, loopState.allToolBlocks)"
        val at = engine.indexOf(finalize)
        assertTrue("finalize call not found in AgentLoopEngine", at > 0)
        assertEquals("exactly one turn-tail finalize", at.toLong(), engine.lastIndexOf(finalize).toLong())

        // No `continue` may bypass it: between the finalize and the block's
        // `break` STATEMENT there is none. Statement-shaped matching on purpose
        // — a bare indexOf("break") lands inside the first "…break…" word in the
        // comments and would shorten the region until the check has no teeth.
        val breakAt = Regex("\\n[ \\t]*break[ \\t]*\\n").find(engine, at)?.range?.first
        assertTrue("no break statement after the finalize", breakAt != null && breakAt > at)
        val tail = engine.substring(at, breakAt!!)
        assertFalse("a continue branch can skip the finalize: $tail",
            Regex("\\n[ \\t]*continue[ \\t]*\\n").containsMatchIn(tail))

        // …and every branch that carries on into the next turn is above it.
        for (anchor in listOf(
            "is EmptyTurnRecovery.Plan.NudgeExistingPart -> {",
            "is EmptyTurnRecovery.Plan.AppendStandalone -> {",
            "EOF-truncated ×\$loopState.eofStubContinues (ceiling \$MAX_EOF_STUB_CONTINUES) — giving up with visible error",
            "EOF-truncated stream with no visible content — one-shot retry (drop empty turn)",
        )) {
            val anchorAt = engine.indexOf(anchor)
            assertTrue("anchor not found: $anchor", anchorAt > 0)
            assertTrue("continue branch must precede the finalize: $anchor", anchorAt < at)
        }
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
}
