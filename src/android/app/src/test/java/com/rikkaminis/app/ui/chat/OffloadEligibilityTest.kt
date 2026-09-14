package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/offload-payload-stub] Pure-JVM tests for the context-offload
 * eligibility rule.
 *
 * Offloading an *observation* is lossless-ish: the stub names a path and the
 * model can `file_read` it back. Offloading a tool-call *payload* is not —
 * `file_write.content` IS the data, so the stub overwrites the very thing the
 * instruction is supposed to carry.
 *
 * The rule is asserted in both directions on purpose: the whole point is that
 * `ToolUse` stopped being eligible, and the cheapest way to break this later
 * is to "restore" payload offloading for the token savings.
 */
class OffloadEligibilityTest {

    private fun toolUse(name: String, content: String) =
        AgentContentPart.ToolUse(
            id = "call_1",
            name = name,
            input = JSONObject().put("path", "/tmp/x.kt").put("content", content),
        )

    private fun toolResult(content: String, imageBytes: Int? = null) =
        AgentContentPart.ToolResult(
            id = "call_1",
            name = "shell_execute",
            content = content,
            imageData = imageBytes?.let { ByteArray(it) },
        )

    // ── ToolUse must never be eligible ─────────────────────────────────────

    @Test
    fun `large file_write payload is not an offload candidate`() {
        // This is the regression pin. A 10KB write payload used to be offloaded,
        // which put the stub where the file content belonged.
        assertFalse(isOffloadEligible(toolUse("file_write", "x".repeat(10_000))))
    }

    @Test
    fun `file_edit and other tool calls are not candidates either`() {
        assertFalse(isOffloadEligible(toolUse("file_edit", "y".repeat(10_000))))
        assertFalse(isOffloadEligible(toolUse("shell_execute", "z".repeat(50_000))))
    }

    // ── observations keep being eligible ──────────────────────────────────

    @Test
    fun `large tool result stays eligible`() {
        assertTrue(isOffloadEligible(toolResult("a".repeat(501))))
    }

    @Test
    fun `tool result just under the threshold stays in context`() {
        assertFalse(isOffloadEligible(toolResult("a".repeat(500))))
    }

    @Test
    fun `small tool result carrying a large image is eligible`() {
        assertTrue(isOffloadEligible(toolResult("ok", imageBytes = 2048)))
    }

    @Test
    fun `small tool result with a small image is not eligible`() {
        assertFalse(isOffloadEligible(toolResult("ok", imageBytes = 1024)))
    }

    @Test
    fun `bare images follow their own threshold`() {
        assertTrue(isOffloadEligible(AgentContentPart.ImageData(ByteArray(1025), "image/png")))
        assertFalse(isOffloadEligible(AgentContentPart.ImageData(ByteArray(1024), "image/png")))
    }

    @Test
    fun `text parts are never offloaded as parts`() {
        // Text is handled by trim/compact at message granularity, not by
        // rewriting a part in place.
        assertFalse(isOffloadEligible(AgentContentPart.Text("t".repeat(100_000))))
    }
}
