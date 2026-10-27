package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1018 ISSUE-02] P1 — the sub-agent loop dropped every reasoning
 * chunk on the floor.
 *
 * `runSubagentLoop`'s `when (chunk)` handled Text / ToolCallComplete /
 * Finished only, so ThinkingDelta and ReasoningContent fell into `else -> {}`,
 * and all three ASSISTANT history entries were written without
 * `reasoningContent`. Consequences: (a) context fidelity — the model re-read
 * its own sub-agent turns as if it had never reasoned; (b) a narrow provider
 * 400 surface (thinking=AUTO + no ReasoningEchoDecider policy + a hard
 * relay-side validation) where an assistant turn carrying tool_use must echo
 * reasoning_content back.
 *
 * The accumulation semantics are the MAIN loop's, copied: the opaque
 * ReasoningContent blob wins over the ThinkingDelta concatenation, an empty
 * blob round-trips as "" (DeepSeek V4 emits `reasoning_content: ""` and
 * fabricating text would be in-context-learned), and no reasoning at all
 * yields null.
 */
class SubagentReasoningHandbackTest {

    // ------------------------------------------------------------------
    // Accumulator semantics (the extracted pure decision)
    // ------------------------------------------------------------------

    @Test
    fun `no reasoning chunks at all accumulates to null`() {
        val acc = SubagentReasoningAccumulator()
        acc.accept(LLMStreamChunk.Text("hello"))
        acc.accept(LLMStreamChunk.ToolUseStart("id", "name"))
        acc.accept(LLMStreamChunk.Finished("stop", truncated = false))
        assertNull(acc.value())
    }

    @Test
    fun `thinking deltas concatenate in arrival order`() {
        val acc = SubagentReasoningAccumulator()
        acc.accept(LLMStreamChunk.ThinkingDelta("alpha "))
        acc.accept(LLMStreamChunk.ThinkingDelta("beta "))
        acc.accept(LLMStreamChunk.ThinkingDelta("gamma"))
        assertEquals("alpha beta gamma", acc.value())
    }

    @Test
    fun `opaque blob takes precedence over the thinking concatenation`() {
        val acc = SubagentReasoningAccumulator()
        acc.accept(LLMStreamChunk.ThinkingDelta("visible thinking "))
        acc.accept(LLMStreamChunk.ReasoningContent("server-emitted blob"))
        assertEquals("server-emitted blob", acc.value())
    }

    @Test
    fun `empty blob round-trips as empty string, never null`() {
        // DeepSeek V4 sends reasoning_content: "" on non-thinking turns — the
        // exact value has to travel back, so an empty blob is a VERDICT, not
        // an absence. Collapsing it to null here is the regression this pins.
        val acc = SubagentReasoningAccumulator()
        acc.accept(LLMStreamChunk.ThinkingDelta("some thinking"))
        acc.accept(LLMStreamChunk.ReasoningContent(""))
        assertEquals("", acc.value())
    }

    @Test
    fun `last blob wins — assignment, not append`() {
        val acc = SubagentReasoningAccumulator()
        acc.accept(LLMStreamChunk.ReasoningContent("first"))
        acc.accept(LLMStreamChunk.ReasoningContent("second"))
        assertEquals("second", acc.value())
    }

    @Test
    fun `each turn gets its own accumulator — no cross-turn bleed`() {
        val turn1 = SubagentReasoningAccumulator()
        turn1.accept(LLMStreamChunk.ThinkingDelta("t1"))
        assertEquals("t1", turn1.value())
        val turn2 = SubagentReasoningAccumulator()
        assertNull("a fresh turn must not inherit turn 1", turn2.value())
        turn2.accept(LLMStreamChunk.ReasoningContent("t2"))
        assertEquals("t2", turn2.value())
        assertEquals("t1", turn1.value())
    }

    // ------------------------------------------------------------------
    // History entry carries it
    // ------------------------------------------------------------------

    @Test
    fun `appendSubagentTurnText carries the reasoning content`() {
        val history = mutableListOf<LLMMessage>()
        assertTrue(appendSubagentTurnText(history, "answer", "captured reasoning"))
        val assistant = history.last()
        assertEquals(LLMMessage.Role.ASSISTANT, assistant.role)
        assertEquals("answer", assistant.content)
        assertEquals("captured reasoning", assistant.reasoningContent)
    }

    @Test
    fun `appendSubagentTurnText without reasoning keeps the old shape`() {
        val history = mutableListOf<LLMMessage>()
        assertTrue(appendSubagentTurnText(history, "answer"))
        assertNull(history.last().reasoningContent)
    }

    @Test
    fun `blank turn still appends nothing with or without reasoning`() {
        val history = mutableListOf<LLMMessage>()
        assertFalse(appendSubagentTurnText(history, "  ", "reasoning"))
        assertFalse(appendSubagentTurnText(history, "", "reasoning"))
        assertTrue(history.isEmpty())
    }

    // ------------------------------------------------------------------
    // Wiring: every hand-back site must actually pass it
    // ------------------------------------------------------------------

    @Test
    fun `sub-agent loop consumes both reasoning chunk kinds`() {
        val body = subagentLoopBody()
        assertTrue(
            "ThinkingDelta must reach the accumulator",
            body.contains("is LLMStreamChunk.ThinkingDelta -> turnReasoning.accept(chunk)"),
        )
        assertTrue(
            "ReasoningContent must reach the accumulator",
            body.contains("is LLMStreamChunk.ReasoningContent -> turnReasoning.accept(chunk)"),
        )
    }

    @Test
    fun `accumulator is per-turn so reasoning never spans turns`() {
        val body = subagentLoopBody()
        val decl = body.indexOf("val turnReasoning = SubagentReasoningAccumulator()")
        val whileIdx = body.indexOf("while (turns < config.maxTurns)")
        assertTrue("per-turn accumulator must be declared", decl >= 0)
        assertTrue("…INSIDE the turn loop", decl > whileIdx)
    }

    @Test
    fun `all three assistant hand-backs carry the accumulated reasoning`() {
        val body = subagentLoopBody()
        // 2 × appendSubagentTurnText (residue refill, truncation continuation)
        assertEquals(
            "both appendSubagentTurnText hand-backs must pass value()",
            2,
            Regex("appendSubagentTurnText\\(history, text, turnReasoning\\.value\\(\\)\\)")
                .findAll(body).count(),
        )
        // 1 × the tool-call turn's direct LLMMessage add
        assertEquals(
            "the tool-call ASSISTANT add must carry value()",
            1,
            Regex("reasoningContent = turnReasoning\\.value\\(\\),")
                .findAll(body).count(),
        )
    }

    private fun subagentLoopBody(): String {
        val src = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatToolExecutors.kt")
        val start = src.indexOf("internal suspend fun runSubagentLoop(")
        assertTrue("runSubagentLoop must exist", start >= 0)
        // The loop ends at the function's closing brace; the rest of the file
        // (post-tool hooks) is irrelevant, so slice to the next top-level decl.
        val next = src.indexOf("/**", start)
        return if (next > start) src.substring(start, next) else src.substring(start)
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
