package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-chat-1119 F-12] A continuation turn that streamed only whitespace
 * must NOT be folded into the previous assistant entry.
 *
 * The fold exists so a length-wall / EOF-stub continuation lands in the SAME
 * transcript entry the user is watching. Its old guard tested "carries nothing
 * at all" (`content.isEmpty() && contentParts.isEmpty()`), so a turn that
 * returned `"\n"` passed and got merged into the previous entry's body. The
 * empty-turn recovery then ran, saw "no visible content", and executed
 * `agentHistory.removeAt(size - 1)` — dropping the merged entry, i.e. DELETING
 * every character of the answer the user had already received. The model then
 * rewrote the answer from scratch (double text against the copy on screen).
 *
 * The guard is now the same predicate the empty-turn detector uses: nothing
 * VISIBLE (blank text and no rendered block).
 */
class ContinuationBlankFoldTest {

    private fun text(t: String) = AgentContentPart.Text(t)

    private fun assistant(content: String, parts: List<AgentContentPart>? = null): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = content,
            contentParts = parts ?: if (content.isEmpty()) emptyList() else listOf(text(content)),
        )

    private fun userText(value: String, dbId: String? = null): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = value,
            contentParts = listOf(text(value)),
            dbMessageId = dbId,
        )

    private fun lengthWallReminder(): LLMMessage =
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "cut off mid-sentence",
            contentParts = listOf(text("cut off mid-sentence by the output token limit. Continue EXACTLY from the cut point.")),
        )

    // ── the defect: a whitespace-only continuation is NOT a fold ─────────

    @Test
    fun `a whitespace-only continuation is not folded and the previous body survives`() {
        val body = "FIX-R4-Q answer part 1: the model had already streamed this text to the user."
        val history = mutableListOf<LLMMessage>(userText("prompt"), assistant(body), lengthWallReminder())
        val before = history.map { it.content }

        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, assistant("\n", listOf(text("\n"))))

        assertFalse("a blank continuation must not fold into the previous answer", folded)
        assertEquals("nothing is mutated on a false verdict — 3 entries stay 3", 3, history.size)
        assertEquals("the previous assistant entry is untouched", before, history.map { it.content })
        assertEquals(
            "DATA RETENTION: the already-streamed body is still in the transcript",
            body,
            history[1].content,
        )
        assertTrue(
            "the continuation reminder is still there (the fold is what removes it)",
            ContinuationHistoryMerge.isContinuationReminder(history[2]),
        )
    }

    @Test
    fun `whitespace of every shape is refused`() {
        for (blank in listOf("\n", "  ", "\n\n\n", "\t", " \n \t ")) {
            val history = mutableListOf<LLMMessage>(userText("p"), assistant("body-$blank"), lengthWallReminder())
            val bodyBefore = history[1].content
            val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(
                history,
                assistant(blank, listOf(text(blank))),
            )
            assertFalse("[$blank] blank continuation must not fold", folded)
            assertEquals("[$blank] history size", 3, history.size)
            assertEquals("[$blank] previous body intact", bodyBefore, history[1].content)
        }
    }

    @Test
    fun `the empty-turn recovery drops the BLANK entry, never the previous answer`() {
        // End-to-end shape of the defect: the engine appends the continuation
        // entry, then the empty-turn recovery removes `history[size - 1]`.
        // With the fold refused, that last entry is the BLANK one.
        val body = "answer part 1"
        val history = mutableListOf<LLMMessage>(userText("p"), assistant(body), lengthWallReminder())

        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, assistant("\n", listOf(text("\n"))))
        if (!folded) history.add(assistant("\n", listOf(text("\n"))))
        assertEquals("the blank continuation is its own entry", 4, history.size)

        // what EmptyTurnRecovery does on a blank turn
        history.removeAt(history.size - 1)

        assertEquals("3 entries again", 3, history.size)
        assertEquals("DATA RETENTION: the streamed body is still there", body, history[1].content)
    }

    // ── the feature that must NOT be deleted: real content still folds ────

    @Test
    fun `a continuation with real content still folds into the previous entry`() {
        val history = mutableListOf<LLMMessage>(userText("p"), assistant("part 1"), lengthWallReminder())

        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(
            history,
            assistant(" and part 2"),
        )

        assertTrue("a real continuation must still fold", folded)
        assertEquals("ONE assistant entry", 1, history.count { it.role == LLMMessage.Role.ASSISTANT })
        assertEquals("part 1 and part 2", history[1].content)
        assertFalse(
            "the reminder is removed by the fold",
            ContinuationHistoryMerge.isContinuationReminder(history.last()),
        )
    }

    @Test
    fun `a continuation carrying a tool call is not blank`() {
        val toolUse = AgentContentPart.ToolUse(id = "t1", name = "shell", input = org.json.JSONObject("{}"))
        val history = mutableListOf<LLMMessage>(userText("p"), assistant("part 1"), lengthWallReminder())

        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(
            history,
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(toolUse)),
        )

        assertTrue("a turn with a rendered block is not blank — it must fold", folded)
        assertEquals(1, history.count { it.role == LLMMessage.Role.ASSISTANT })
    }

    // ── the callers' other guards are unchanged ──────────────────────────

    @Test
    fun `the previously empty turn keeps its old behaviour`() {
        val history = mutableListOf<LLMMessage>(userText("p"), assistant("body"), lengthWallReminder())
        val before = history.map { it.content }
        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(
            history,
            LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = emptyList()),
        )
        assertFalse("an empty turn was already refused — unchanged", folded)
        assertEquals(before, history.map { it.content })
    }

    @Test
    fun `a persisted previous row is still never folded into`() {
        val history = mutableListOf(
            userText("p"),
            assistant("body").copy(dbMessageId = "db-1"),
            lengthWallReminder(),
        )
        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, assistant(" and more"))
        assertFalse(folded)
        assertEquals("body", history[1].content)
        assertEquals(3, history.size)
    }

    @Test
    fun `a blank continuation cannot append to a previous entry that is itself blank`() {
        // "前一条本身为空 → 不误删别的消息": the previous assistant entry is an
        // empty shell; a blank continuation must not merge into it and become
        // "the entry the recovery deletes along with the surrounding history".
        val history = mutableListOf<LLMMessage>(
            userText("keep me"),
            assistant(""),
            lengthWallReminder(),
        )
        val before = history.map { it.content to it.role }

        val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, assistant("\n", listOf(text("\n"))))

        assertFalse("blank into blank still does not fold", folded)
        assertEquals("no entry was removed or merged", before, history.map { it.content to it.role })
        assertTrue("the surrounding real messages survive", history.any { it.content == "keep me" })
        assertTrue(
            "the empty shell is NOT the last entry the recovery would drop",
            history.last().role == LLMMessage.Role.USER,
        )
    }
}
