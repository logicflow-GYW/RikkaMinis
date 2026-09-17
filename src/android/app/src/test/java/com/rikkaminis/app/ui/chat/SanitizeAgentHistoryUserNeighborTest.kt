package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the sanitize merge relaxation (fix/audit-0917-b9):
 * merging placeholder tool_results into a neighbour USED to require that the
 * neighbour already carried a ToolResult; a plain-text USER neighbour fell
 * through to the insert branch, producing
 * [assistant(tool_use), user(placeholders), user(original text)] — two
 * consecutive USER messages, which Anthropic hard-rejects with 400.
 *
 * Also pins ensureRoleAlternationBeforeUserAppend (T-consecutive-user-bridge).
 */
class SanitizeAgentHistoryUserNeighborTest {

    private fun userMsg(content: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = content, contentParts = parts)

    private fun assistantMsg(content: String = "", parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = content, contentParts = parts)

    private fun toolUse(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id, name, org.json.JSONObject())

    private fun toolResult(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolResult(id, name, "output of $id", isError = false)

    @Test
    fun `plain text user neighbour merges instead of inserting`() {
        val msgs = mutableListOf(
            userMsg("check the files"),
            assistantMsg(parts = listOf(toolUse("call_1"))),
            userMsg("and also look at logs"), // plain-text neighbour, no tool_result
        )
        sanitizeAgentHistoryMessages(msgs)
        assertEquals(3, msgs.size)
        // placeholders must land INSIDE the existing user message — no new
        // user message inserted before it
        assertTrue(msgs[2].contentParts.any { it is AgentContentPart.ToolResult })
        assertEquals("and also look at logs", msgs[2].content)
        // exactly one USER message after the tool_use (no consecutive USER)
        assertEquals(1, msgs.drop(1).count { it.role == LLMMessage.Role.USER })
    }

    @Test
    fun `assistant neighbour still inserts a dedicated user message`() {
        val msgs = mutableListOf(
            assistantMsg(parts = listOf(toolUse("call_1"))),
            assistantMsg("a later assistant turn"),
        )
        sanitizeAgentHistoryMessages(msgs)
        assertEquals(3, msgs.size)
        assertTrue(msgs[1].contentParts.any { it is AgentContentPart.ToolResult })
        assertEquals(LLMMessage.Role.USER, msgs[1].role)
    }

    @Test
    fun `merged placeholder keeps the original user content`() {
        val msgs = mutableListOf(
            assistantMsg(parts = listOf(toolUse("call_2"))),
            userMsg("original instruction"),
        )
        sanitizeAgentHistoryMessages(msgs)
        assertEquals(
            SANITIZE_PLACEHOLDER_RESULT_CONTENT,
            msgs[1].contentParts.filterIsInstance<AgentContentPart.ToolResult>().first().content,
        )
        assertEquals("original instruction", msgs[1].content)
    }

    @Test
    fun `alternation bridge splits consecutive user tail`() {
        val history = mutableListOf(userMsg("first"), userMsg("second"))
        ensureRoleAlternationBeforeUserAppend(history)
        // bridge appends at the tail, after the consecutive USER messages
        assertEquals(LLMMessage.Role.ASSISTANT, history[2].role)
        assertEquals(3, history.size)
        assertEquals(LLMMessage.Role.USER, history[1].role)
    }

    @Test
    fun `alternation bridge is a no-op after assistant tail`() {
        val history = mutableListOf(userMsg("q"), assistantMsg("a"))
        ensureRoleAlternationBeforeUserAppend(history)
        assertEquals(2, history.size)
    }
}
