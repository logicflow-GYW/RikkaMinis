package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-C8/F-13] What one message contributes to the char-based budget scale.
 *
 * The defect: `estimateHistoryTokens` walked `contentParts` and nothing else, so
 * two whole classes of payload counted as ZERO —
 *  - a message stored in the legacy shape (`content` set, `contentParts` empty),
 *  - `reasoningContent`, which interleaved-reasoning providers (DeepSeek V4 /
 *    Kimi / GLM) require echoed back on every subsequent turn.
 *
 * That scale is the pressure reading whenever `lastContextTokens == 0` (a
 * resumed history, or a mid-session model switch — the engine zeroes the
 * provider reading when the context key changes), so the undercount deferred
 * exactly the two mechanisms it feeds: offload and the turn-granular trim.
 *
 * Every expectation is a literal. Source:
 * `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindow.kt`
 */
class HistoryCharContributionTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindow.kt"

    private fun user(
        content: String = "",
        parts: List<AgentContentPart> = emptyList(),
        reasoning: String? = null,
    ) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        contentParts = parts,
        reasoningContent = reasoning,
    )

    // ── the two missing terms ──────────────────────────────────────────────

    /** Gap 1: the legacy shape. Before the fix this returned 0. */
    @Test
    fun `content is counted when there are no content parts`() {
        assertEquals(11, messageCharContribution(user(content = "hello world")))
    }

    /** Gap 2: echoed reasoning. Before the fix this returned 0. */
    @Test
    fun `reasoning content is counted`() {
        assertEquals(12, messageCharContribution(user(reasoning = "think harder")))
    }

    @Test
    fun `content and reasoning add up`() {
        assertEquals(19, messageCharContribution(user(content = "abcdefg", reasoning = "0123456789ab")))
    }

    // ── parts win when present (wire parity, no double count) ──────────────

    @Test
    fun `text parts are summed`() {
        val msg = user(parts = listOf(AgentContentPart.Text("abc"), AgentContentPart.Text("de")))
        assertEquals(5, messageCharContribution(msg))
    }

    @Test
    fun `content is ignored when parts are present`() {
        // The wire body is the parts; counting `content` too would double-charge
        // the same text (ContextEstimator.estimateMessageTokensFull makes the
        // same call).
        val msg = user(content = "IGNORED", parts = listOf(AgentContentPart.Text("ab")))
        assertEquals(2, messageCharContribution(msg))
    }

    @Test
    fun `reasoning is added on top of parts`() {
        val msg = user(parts = listOf(AgentContentPart.Text("ab")), reasoning = "xyz")
        assertEquals(5, messageCharContribution(msg))
    }

    // ── the other part shapes ──────────────────────────────────────────────

    @Test
    fun `a tool result contributes its content length`() {
        val msg = user(parts = listOf(AgentContentPart.ToolResult(id = "t1", name = "bash", content = "1234567890")))
        assertEquals(10, messageCharContribution(msg))
    }

    @Test
    fun `a tool use contributes the serialised argument length`() {
        val input = JSONObject().put("path", "/tmp/a")
        val msg = user(parts = listOf(AgentContentPart.ToolUse(id = "t1", name = "read", input = input)))
        assertEquals(input.toString().length, messageCharContribution(msg))
    }

    @Test
    fun `an image part contributes no characters`() {
        val msg = user(parts = listOf(AgentContentPart.ImageData(data = byteArrayOf(1, 2, 3), mimeType = "image/png")))
        assertEquals(0, messageCharContribution(msg))
    }

    @Test
    fun `an empty message contributes nothing`() {
        assertEquals(0, messageCharContribution(user()))
    }

    // ── the aggregate still divides by the audit-0920 scale ────────────────

    @Test
    fun `the estimator sums per-message contributions`() {
        val src = java.io.File(sourcePath).readText()
        assertTrue(src.contains("totalChars += messageCharContribution(msg)"))
        assertTrue(src.contains("return (totalChars / 3.5).toInt() + imageTokens"))
        assertFalse("the parts-only loop is the defect",
            src.contains("            totalChars += partCharContribution(part)\n            imageTokens += partImageContribution(part)"))
    }
}
