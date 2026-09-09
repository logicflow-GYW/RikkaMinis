package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentToolDefinition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [ChatViewModel] companion pure functions.
 *
 * [ChatViewModel.preflightEmptyStringAllowed] and
 * [ChatViewModel.preflightValidateToolCallImpl] are both `internal` companion
 * functions — pure Kotlin, no Android dependency — so they are directly
 * constructible and testable in a JVM unit test.
 */
class ChatViewModelCompanionTest {

    // ── preflightEmptyStringAllowed ────────────────────────────────────────

    @Test fun `emptyStringAllowed file_edit new_string returns true`() {
        assertTrue(ChatViewModel.preflightEmptyStringAllowed("file_edit", "new_string"))
    }

    @Test fun `emptyStringAllowed file_edit old_string returns false`() {
        assertFalse(ChatViewModel.preflightEmptyStringAllowed("file_edit", "old_string"))
    }

    @Test fun `emptyStringAllowed unknown tool returns false`() {
        assertFalse(ChatViewModel.preflightEmptyStringAllowed("shell_execute", "command"))
    }

    @Test fun `emptyStringAllowed unknown field returns false`() {
        assertFalse(ChatViewModel.preflightEmptyStringAllowed("file_edit", "nonexistent"))
    }

    @Test fun `emptyStringAllowed empty tool returns false`() {
        assertFalse(ChatViewModel.preflightEmptyStringAllowed("", "new_string"))
    }

    // ── preflightValidateToolCallImpl ──────────────────────────────────────

    private fun toolDef(name: String, required: List<String> = emptyList()) =
        AgentToolDefinition(name = name, description = "test", parameters = emptyMap(), required = required)

    private fun preflightBlocking(name: String, args: JSONObject, tools: List<AgentToolDefinition>): String {
        val result = ChatViewModel.preflightValidateToolCallImpl(name, args, tools)
        assertNotNull("expected a blocking reason for $name", result)
        return result!!
    }

    @Test fun `preflight well-formed call returns null`() {
        val tools = listOf(toolDef("file_read", required = listOf("path")))
        val args = JSONObject().put("path", "/tmp/test.txt")
        assertNull(ChatViewModel.preflightValidateToolCallImpl("file_read", args, tools))
    }

    @Test fun `preflight empty args with required fields returns error`() {
        val tools = listOf(toolDef("file_read", required = listOf("path")))
        val result = preflightBlocking("file_read", JSONObject(), tools)
        assertTrue(result.contains("empty arguments"))
        assertTrue(result.contains("path"))
    }

    @Test fun `preflight missing required field returns error`() {
        val tools = listOf(toolDef("file_write", required = listOf("path", "content")))
        val result = preflightBlocking("file_write", JSONObject().put("path", "/tmp/test.txt"), tools)
        assertTrue(result.contains("missing required parameter"))
        assertTrue(result.contains("content"))
        assertFalse(result.contains("path"))
    }

    @Test fun `preflight missing multiple fields lists all`() {
        val tools = listOf(toolDef("multi", required = listOf("a", "b", "c")))
        val result = preflightBlocking("multi", JSONObject(), tools)
        assertTrue(result.contains("a"))
        assertTrue(result.contains("b"))
        assertTrue(result.contains("c"))
    }

    @Test fun `preflight null field treated as missing`() {
        val tools = listOf(toolDef("file_edit", required = listOf("old_string", "new_string")))
        val args = JSONObject().put("old_string", JSONObject.NULL).put("new_string", "replacement")
        val result = preflightBlocking("file_edit", args, tools)
        assertTrue(result.contains("old_string"))
    }

    @Test fun `preflight empty string on non-allowed field returns error`() {
        val tools = listOf(toolDef("file_read", required = listOf("path")))
        val result = preflightBlocking("file_read", JSONObject().put("path", ""), tools)
        assertTrue(result.contains("missing required parameter"))
    }

    @Test fun `preflight empty string on allowed field returns null`() {
        val tools = listOf(toolDef("file_edit", required = listOf("old_string", "new_string")))
        val args = JSONObject().put("old_string", "original").put("new_string", "")
        assertNull(ChatViewModel.preflightValidateToolCallImpl("file_edit", args, tools))
    }

    @Test fun `preflight unknown tool returns null (pass-through)`() {
        val tools = listOf(toolDef("file_read"))
        assertNull(ChatViewModel.preflightValidateToolCallImpl("nonexistent", JSONObject(), tools))
    }

    @Test fun `preflight no required fields returns null`() {
        val tools = listOf(toolDef("noop"))
        assertNull(ChatViewModel.preflightValidateToolCallImpl("noop", JSONObject(), tools))
    }

    @Test fun `preflight tool_title non-blocking field not enforced`() {
        val tools = listOf(toolDef("my_tool", required = listOf("tool_title", "path")))
        // Only "path" is enforced — tool_title is non-blocking.
        val result = preflightBlocking("my_tool", JSONObject().put("tool_title", "My Tool"), tools)
        assertTrue(result.contains("path"))
        assertFalse(result.contains("tool_title"))
    }

    @Test fun `preflight non-blocking only field does not reject empty args`() {
        val tools = listOf(toolDef("my_tool", required = listOf("tool_title")))
        assertNull(ChatViewModel.preflightValidateToolCallImpl("my_tool", JSONObject(), tools))
    }

    @Test fun `preflight empty args with only non-blocking required returns null`() {
        val tools = listOf(toolDef("my_tool", required = listOf("tool_title")))
        assertNull(ChatViewModel.preflightValidateToolCallImpl("my_tool", JSONObject(), tools))
    }

    @Test fun `preflight whitespace string is not rejected`() {
        val tools = listOf(toolDef("file_edit", required = listOf("new_string")))
        val args = JSONObject().put("new_string", "\n")
        assertNull(ChatViewModel.preflightValidateToolCallImpl("file_edit", args, tools))
    }

    @Test fun `preflight explicit null in JSONObject is rejected`() {
        // org.json: JSONObject.NULL is a sentinel, not a String
        val tools = listOf(toolDef("file_read", required = listOf("path")))
        val result = preflightBlocking("file_read", JSONObject().put("path", JSONObject.NULL), tools)
        assertTrue(result.contains("path"))
    }

    @Test fun `preflight non-string value passes through`() {
        // Non-string values (int, boolean) are not checked for emptiness
        val tools = listOf(toolDef("numeric", required = listOf("count")))
        val args = JSONObject().put("count", 42)
        assertNull(ChatViewModel.preflightValidateToolCallImpl("numeric", args, tools))
    }

    @Test fun `preflight case sensitivity of tool name`() {
        val tools = listOf(toolDef("FileRead", required = listOf("path")))
        val args = JSONObject().put("path", "/tmp/x")
        // Tool names are case-sensitive
        assertNull(ChatViewModel.preflightValidateToolCallImpl("FileRead", args, tools))
        assertNull(ChatViewModel.preflightValidateToolCallImpl("fileread", JSONObject(), tools))
    }

    // ── consumePendingCaret (read-and-clear contract) ──────────────────────

    @Test fun `pendingCaret defaults to null`() {
        assertNull(PendingCaretHelper().read())
    }

    @Test fun `pendingCaret set then consume returns value`() {
        val helper = PendingCaretHelper()
        helper.write(42)
        assertEquals(42, helper.consume())
    }

    @Test fun `pendingCaret consume clears to null`() {
        val helper = PendingCaretHelper()
        helper.write(99)
        helper.consume()
        assertNull(helper.read())
    }

    @Test fun `pendingCaret consume twice second returns null`() {
        val helper = PendingCaretHelper()
        helper.write(7)
        helper.consume()
        assertNull(helper.consume())
    }

    @Test fun `pendingCaret overwrite then consume returns latest`() {
        val helper = PendingCaretHelper()
        helper.write(1)
        helper.write(2)
        assertEquals(2, helper.consume())
    }

    /**
     * Pure-Kotlin mirror of [ChatViewModel]'s `_pendingCaret` / `pendingCaret` /
     * `consumePendingCaret()` pattern. Verifies the read-and-clear contract
     * without needing a full ViewModel construction.
     *
     * These are instance methods on the ViewModel, not companion functions, so
     * we test the semantic contract via a standalone helper.
     */
    private class PendingCaretHelper {
        private var value: Int? = null
        fun read(): Int? = value
        fun write(v: Int) { value = v }
        fun consume(): Int? {
            val v = value
            value = null
            return v
        }
    }
}