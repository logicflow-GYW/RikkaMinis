package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the provider-layer defense-in-depth sanitizers
 * (D-class in the request-construction error audit, 2026-08-14).
 */
class RequestSanitizersTest {

    // ─── helpers ───────────────────────────────────────────────────────────

    private fun user(text: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.USER, content = text, contentParts = parts)

    private fun assistant(text: String, parts: List<AgentContentPart> = emptyList()) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = parts)

    private fun toolUse(id: String, name: String = "shell_execute") =
        AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())

    private fun toolResult(id: String, name: String = "shell_execute", content: String = "ok") =
        AgentContentPart.ToolResult(id = id, name = name, content = content)

    private val logs = mutableListOf<String>()
    private fun sanitize(messages: List<LLMMessage>): List<LLMMessage> {
        logs.clear()
        return sanitizeToolPairing(messages) { logs.add(it) }
    }

    private fun partsOf(msg: LLMMessage): List<AgentContentPart> = msg.contentParts

    // ─── pass-through: valid history untouched ─────────────────────────────

    @Test
    fun `paired tool exchange passes through untouched`() {
        val msgs = listOf(
            user("do it"),
            assistant("ok", listOf(toolUse("t1"))),
            user("", listOf(toolResult("t1"))),
        )
        val out = sanitize(msgs)
        assertEquals(3, out.size)
        assertEquals(1, partsOf(out[2]).filterIsInstance<AgentContentPart.ToolResult>().size)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `multi-turn agent loop untouched`() {
        val msgs = listOf(
            user("hi"),
            assistant("calling", listOf(toolUse("a"), toolUse("b"))),
            user("", listOf(toolResult("a"), toolResult("b"))),
            assistant("done"),
            user("next"),
        )
        val out = sanitize(msgs)
        assertEquals(5, out.size)
        assertTrue(logs.isEmpty())
    }

    // ─── orphan tool_result (D2/D3 core) ───────────────────────────────────

    @Test
    fun `tool_result not matching a live tool_use is dropped`() {
        val msgs = listOf(
            assistant("calling", listOf(toolUse("a"))),
            // Result for "a" is expected; "zzz" is an orphan.
            user("", listOf(toolResult("a"), toolResult("zzz"))),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        val kept = partsOf(out[1]).filterIsInstance<AgentContentPart.ToolResult>()
        assertEquals(listOf("a"), kept.map { it.id })
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("1 orphan tool_result"))
    }

    @Test
    fun `orphan tool_result at history head is dropped`() {
        // Compact slice / reload can begin with a user tool_result whose
        // matching assistant tool_use was cut off. The orphan result is
        // stripped; the message is returned empty (caller may filter empties).
        val msgs = listOf(user("", listOf(toolResult("ghost"))))
        val out = sanitize(msgs)
        assertEquals(1, out.size)
        assertTrue(partsOf(out[0]).isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun `user message keeps text when only the orphan result is dropped`() {
        val msgs = listOf(
            assistant("calling", listOf(toolUse("a"))),
            user("note for the model", listOf(AgentContentPart.Text("note for the model"), toolResult("a"), toolResult("zzz"))),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        assertEquals(1, partsOf(out[1]).filterIsInstance<AgentContentPart.Text>().size)
        assertEquals(listOf("a"), partsOf(out[1]).filterIsInstance<AgentContentPart.ToolResult>().map { it.id })
    }

    // ─── orphan tool_use (D1 gap) ──────────────────────────────────────────

    @Test
    fun `tool_use with no matching result in next user message is dropped`() {
        // User interrupted and replied with text instead of tool results.
        val msgs = listOf(
            assistant("calling", listOf(toolUse("a"))),
            user("please stop"),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        assertTrue(partsOf(out[0]).filterIsInstance<AgentContentPart.ToolUse>().isEmpty())
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("1 orphan tool_use"))
    }

    @Test
    fun `tool_use as the final message (truncated history) is dropped`() {
        val msgs = listOf(
            user("hi"),
            assistant("calling", listOf(toolUse("a"))),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        assertTrue(partsOf(out[1]).filterIsInstance<AgentContentPart.ToolUse>().isEmpty())
        assertEquals(1, logs.size)
    }

    @Test
    fun `partial answer strips only the unanswered tool_use`() {
        val msgs = listOf(
            assistant("calling", listOf(toolUse("a"), toolUse("b"))),
            user("", listOf(toolResult("a"))),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        val uses = partsOf(out[0]).filterIsInstance<AgentContentPart.ToolUse>()
        assertEquals(listOf("a"), uses.map { it.id })
        // "b" was stripped → its result would have been orphaned too, but it
        // was never present, so only the use strip is logged.
        assertEquals(1, logs.size)
    }

    @Test
    fun `empty assistant after stripping tool_use is dropped entirely`() {
        val msgs = listOf(
            user("hi"),
            assistant("", listOf(toolUse("a"))), // no text at all
            user("please stop"),
        )
        val out = sanitize(msgs)
        assertEquals(3, out.size)
        // The stripped assistant message is returned empty — Anthropic's call
        // site applies the empty-drop filter; the sanitizer itself does not.
        assertEquals(LLMMessage.Role.ASSISTANT, out[1].role)
        assertTrue(partsOf(out[1]).isEmpty())
    }

    // ─── provider protocol specifics ───────────────────────────────────────

    @Test
    fun `openai shape - tool result after assistant tool_calls survives`() {
        // OpenAI: assistant(tool_calls) must be answered by role:"tool".
        val msgs = listOf(
            assistant("call", listOf(toolUse("t1"))),
            user("", listOf(toolResult("t1"))),
            assistant("done"),
        )
        val out = sanitize(msgs)
        assertEquals(3, out.size)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `gemini shape - functionResponse without functionCall is dropped`() {
        // Gemini pairs functionCall ↔ functionResponse; an orphan response
        // (no preceding call in the last model turn) is a 400. The orphan
        // response is stripped; the message is returned empty (caller may
        // filter empties).
        val msgs = listOf(
            assistant("plain text"), // no functionCall
            user("", listOf(toolResult("orphan"))),
        )
        val out = sanitize(msgs)
        assertEquals(2, out.size)
        assertEquals(LLMMessage.Role.USER, out[1].role)
        assertTrue(partsOf(out[1]).filterIsInstance<AgentContentPart.ToolResult>().isEmpty())
        assertEquals(1, logs.size)
    }

    // ─── clampOutboundMaxTokens ────────────────────────────────────────────

    @Test
    fun `maxTokens in range is unchanged`() {
        assertEquals(8192, clampOutboundMaxTokens(8192, ceiling = 128_000))
        assertEquals(4096, clampOutboundMaxTokens(4096, ceiling = 16_384))
    }

    @Test
    fun `maxTokens above model ceiling is clamped`() {
        // Sub-agent frontmatter says 64K but the model only supports 8K.
        assertEquals(8192, clampOutboundMaxTokens(65536, ceiling = 8192))
    }

    @Test
    fun `maxTokens above global ceiling is clamped`() {
        assertEquals(128_000, clampOutboundMaxTokens(1_000_000, ceiling = 1_000_000))
    }

    @Test
    fun `non-positive maxTokens clamps to 1`() {
        assertEquals(1, clampOutboundMaxTokens(0, ceiling = 16_384))
        assertEquals(1, clampOutboundMaxTokens(-5, ceiling = 16_384))
    }

    @Test
    fun `absurdly low ceiling still yields at least 1`() {
        assertEquals(1, clampOutboundMaxTokens(2048, ceiling = 0))
    }

    // ─── clampOutboundTemperature ──────────────────────────────────────────

    @Test
    fun `temperature in range is unchanged`() {
        assertEquals(0.7, clampOutboundTemperature(0.7), 0.0)
        assertEquals(0.0, clampOutboundTemperature(0.0), 0.0)
        assertEquals(2.0, clampOutboundTemperature(2.0), 0.0)
    }

    @Test
    fun `temperature above max is clamped`() {
        assertEquals(2.0, clampOutboundTemperature(3.5), 0.0)
        assertEquals(1.0, clampOutboundTemperature(1.5, max = 1.0), 0.0)
    }

    @Test
    fun `negative temperature clamps to 0`() {
        assertEquals(0.0, clampOutboundTemperature(-0.5), 0.0)
    }
}
