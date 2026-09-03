package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FE-5 round-trip tests for the pure serializers / rebuilders extracted from
 * ChatViewModel into ChatTurnPartsJson / ChatStreamToolHelpers /
 * ChatTranscriptRebuild.
 *
 * The core invariant: **build → persist-shape JSON → parsePartsJson →
 * rebuild** must restore the same semantic content. These exercise the exact
 * production code paths (not copies) so the wire format cannot drift.
 */
class ChatTurnPartsJsonTest {

    private fun entity(role: String, partsJson: String, id: String = "e1") = MessageEntity(
        id = id, sessionId = "s1", role = role, partsJson = partsJson,
        createdAt = 1L, sortOrder = 1,
    )

    // ── buildAssistantTurnPartsJson round-trip ─────────────────────

    @Test
    fun `text and toolUse parts round-trip through parsePartsJson`() {
        val parts = listOf(
            AgentContentPart.Text("hello"),
            AgentContentPart.ToolUse(
                id = "t1", name = "shell_execute",
                input = JSONObject("""{"command":"ls"}"""),
            ),
        )
        val meta = mapOf(
            "t1" to AssistantBlock(
                id = "t1", kind = "tool_use", toolTitle = "Execute Shell",
                browserURL = "https://example.com", imageFilePath = null,
            ),
        )
        val json = buildAssistantTurnPartsJson(parts, meta)
        val parsed = parsePartsJson(json)

        assertEquals(2, parsed.size)
        val text = parsed[0] as ParsedPart.Text
        assertEquals("hello", text.value)
        val toolUse = parsed[1] as ParsedPart.ToolUse
        assertEquals("t1", toolUse.id)
        assertEquals("shell_execute", toolUse.name)
        assertEquals("Execute Shell", toolUse.description)
        assertEquals("https://example.com", toolUse.pageURL)
    }

    @Test
    fun `toolUse with blank name is skipped`() {
        val parts = listOf(
            AgentContentPart.Text("a"),
            AgentContentPart.ToolUse(id = "t2", name = "", input = JSONObject()),
        )
        val json = buildAssistantTurnPartsJson(parts, emptyMap())
        val parsed = parsePartsJson(json)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0] is ParsedPart.Text)
    }

    @Test
    fun `text-only variant emits no toolUse`() {
        val json = buildTextOnlyAssistantPartsJson(
            listOf(AgentContentPart.Text("partial"), AgentContentPart.Text("<system-reminder>stopped</system-reminder>")),
        )
        val parsed = parsePartsJson(json)
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it is ParsedPart.Text })
    }

    @Test
    fun `escaping round-trips quotes newlines unicode`() {
        val tricky = "quote\" newline\\n 中文 \\\\ backslash"
        val json = buildAssistantTurnPartsJson(listOf(AgentContentPart.Text(tricky)), emptyMap())
        val parsed = parsePartsJson(json)
        assertEquals(tricky, (parsed[0] as ParsedPart.Text).value)
    }

    // ── buildUsageJson ─────────────────────────────────────────────

    @Test
    fun `usage json coalesces null cache fields to zero`() {
        val json = buildUsageJson(LLMUsage(inputTokens = 10, outputTokens = 20))
        val obj = JSONObject(json)
        assertEquals(10, obj.getInt("inputTokens"))
        assertEquals(20, obj.getInt("outputTokens"))
        assertEquals(0, obj.getInt("cacheCreationTokens"))
        assertEquals(0, obj.getInt("cacheReadTokens"))
    }

    @Test
    fun `usage json keeps cache fields when present`() {
        val json = buildUsageJson(LLMUsage(1, 2, cacheCreationInputTokens = 3, cacheReadInputTokens = 4, latestContextTokens = 5))
        val obj = JSONObject(json)
        assertEquals(3, obj.getInt("cacheCreationTokens"))
        assertEquals(4, obj.getInt("cacheReadTokens"))
        assertEquals(5, obj.getInt("latestContextTokens"))
    }

    // ── buildToolResultPartsJson ───────────────────────────────────

    @Test
    fun `toolResult json round-trips without legacy snapshot field`() {
        val longOutput = (1..100).joinToString("\n") { "line$it" }
        val parts = listOf(
            AgentContentPart.ToolResult(id = "r1", name = "file_read", content = longOutput, isError = false),
        )
        val json = buildToolResultPartsJson(parts)
        val parsed = parsePartsJson(json)
        val tr = parsed[0] as ParsedPart.ToolResult
        assertEquals("r1", tr.toolUseId)
        assertEquals("file_read", tr.name)
        assertEquals(longOutput, tr.output)
        assertTrue(tr.success)
        // [fix/audit-s1l1] legacy snapshot preview field removed — nothing reads it
        val value = org.json.JSONArray(json).getJSONObject(0).getJSONObject("value")
        assertFalse(value.has("snapshot"))
    }

    // ── extractPartialStringValue ──────────────────────────────────

    @Test
    fun `partial json extracts streamed tool_title with and without space`() {
        assertEquals("Wri", extractPartialStringValue("tool_title", """{"tool_title": "Wri"""))
        assertEquals("Wri", extractPartialStringValue("tool_title", """{"tool_title":"Wri"""))
    }

    @Test
    fun `partial json handles truncated before closing quote`() {
        val acc = """{"command":"echo he""""
        assertEquals("echo he", extractPartialStringValue("command", acc))
    }

    @Test
    fun `partial json returns null when key absent`() {
        assertNull(extractPartialStringValue("tool_title", """{"command":"ls"}"""))
    }

    // ── friendlyToolTitle / parseToolParams ─────────────────────────

    @Test
    fun `known tool names get fixed titles`() {
        assertEquals("Execute Shell", friendlyToolTitle("shell_execute"))
        assertEquals("Read File", friendlyToolTitle("file_read"))
    }

    @Test
    fun `unknown tool names are humanized`() {
        assertEquals("Spawn Agent", friendlyToolTitle("spawn_agent"))
    }

    @Test
    fun `parseToolParams parses plain map`() {
        val m = parseToolParams("""{"a":1,"b":"x"}""")
        assertEquals(1, m["a"])
        assertEquals("x", m["b"])
    }

    @Test
    fun `parseToolParams malformed degrades to empty map`() {
        assertTrue(parseToolParams("not json{").isEmpty())
        assertTrue(parseToolParams("").isEmpty())
    }

    // ── strip pair ─────────────────────────────────────────────────

    @Test
    fun `stripDisplayOnlyArtifacts removes reminder and attached files xml`() {
        val raw = "keep <system-reminder>note</system-reminder> mid <user-attached-files>x</user-attached-files> end"
        // Exact production semantics (verified by direct replay):
        // regex `\s*<reminder>…</reminder>\s*` eats BOTH surrounding spaces
        // → "keepmid <xml> end"; the XML cut then removes the block cleanly
        // → "keepmid  end"; trim only strips the outer edges.
        // Reminder glued to the preceding word is the documented iOS-parity
        // behaviour — the reminder was injected AFTER "keep " mid-stream.
        assertEquals("keepmid  end", stripDisplayOnlyArtifacts(raw))
    }

    @Test
    fun `plain text passes through unchanged without trim`() {
        // leading space must survive when nothing was stripped
        assertEquals("  plain  ", stripDisplayOnlyArtifacts("  plain  "))
    }

    @Test
    fun `stripAttachedFilesXml handles unterminated xml`() {
        assertEquals("keep ", stripAttachedFilesXml("keep <user-attached-files>no end"))
    }

    // ── buildSingleLlmMessage / buildLlmMessagesFromParsed ─────────

    @Test
    fun `llm message from text row`() {
        val row = ParsedRow(
            entity = entity("user", """[{"type":"text","value":"hi"}]"""),
            parts = listOf(ParsedPart.Text("hi")),
            sourceChars = 30, malformed = false,
        )
        val out = buildLlmMessagesFromParsed(listOf(row), File("/tmp"))
        assertEquals(1, out.size)
        assertEquals(LLMMessage.Role.USER, out[0].role)
        assertEquals("hi", out[0].content)
    }

    @Test
    fun `malformed row falls back to raw partsJson as text`() {
        val e = entity("assistant", """[{"type":"text","value":"bro""")
        val out = buildSingleLlmMessage(e, e.partsJson, emptyList(), malformed = true, mediaBaseDir = File("/tmp"))
        assertEquals(e.partsJson, out.content)
        assertEquals(LLMMessage.Role.ASSISTANT, out.role)
        assertEquals(e.id, out.dbMessageId)
    }

    @Test
    fun `mediaRef image is re-inlined only when file exists`() {
        val dir = java.nio.file.Files.createTempDirectory("fe5").toFile()
        val img = File(dir, "img.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val parts = listOf(
            ParsedPart.MediaRef(relativePath = "img.png", mimeType = "image/png", originalFileName = "", linuxPath = null),
        )
        val e = entity("user", "[]")
        val out = buildSingleLlmMessage(e, "[]", parts, false, dir)
        assertEquals(1, out.imageParts.size)
        assertEquals(3, out.imageParts[0].data.size)

        val outMissing = buildSingleLlmMessage(e, "[]", parts, false, dir.parentFile)
        assertEquals(0, outMissing.imageParts.size)
    }

    // ── buildChatMessagesTranscript ────────────────────────────────

    @Test
    fun `consecutive assistant rows coalesce into one chat message`() {
        val parsed = listOf(
            ParsedRow(entity("assistant", """[{"type":"text","value":"a1"}]""", "m1"), listOf(ParsedPart.Text("a1")), 24, false),
            ParsedRow(entity("assistant", """[{"type":"text","value":"a2"}]""", "m2"), listOf(ParsedPart.Text("a2")), 24, false),
        )
        val out = buildChatMessagesTranscript(parsed, File("/tmp"))
        assertEquals(1, out.size)
        assertEquals("a1\n\na2", out[0].content)
        assertEquals(2, out[0].sourceDbIds.size)
    }

    @Test
    fun `toolUse row merges toolResult from later user row`() {
        val parsed = listOf(
            ParsedRow(
                entity("assistant", "[]", "m1"),
                listOf(ParsedPart.ToolUse(id = "t1", name = "file_read", input = "{}", description = "", pageURL = null, imageFilePath = null)),
                2, false,
            ),
            ParsedRow(
                entity("user", "[]", "m2"),
                listOf(ParsedPart.ToolResult(toolUseId = "t1", name = "file_read", output = "result text", success = false)),
                2, false,
            ),
        )
        val out = buildChatMessagesTranscript(parsed, File("/tmp"))
        val assistant = out.first { it.role == "assistant" }
        val block = assistant.toolBlocks.first()
        assertEquals(ToolBlockStatus.FAILED, block.toolStatus)
        assertTrue(block.content.contains("result text"))
    }

    @Test
    fun `system-reminder only user row is skipped`() {
        val parsed = listOf(
            ParsedRow(entity("user", """[{"type":"text","value":"<system-reminder>x</system-reminder>"}]""", "m1"),
                listOf(ParsedPart.Text("<system-reminder>x</system-reminder>")), 40, false),
        )
        val out = buildChatMessagesTranscript(parsed, File("/tmp"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `malformed row renders fallback placeholder`() {
        val parsed = listOf(
            ParsedRow(entity("assistant", "[broken", "m1"), emptyList(), 7, true),
        )
        val out = buildChatMessagesTranscript(parsed, File("/tmp"))
        assertEquals(1, out.size)
        assertTrue(out[0].content.startsWith("(message could not be parsed"))
    }

    @Test
    fun `restored thinking block from reasoningContent`() {
        val e = entity("assistant", """[{"type":"text","value":"hi"}]""", "m1").let {
            it.copy(reasoningContent = "thought process")
        }
        val parsed = listOf(ParsedRow(e, listOf(ParsedPart.Text("hi")), 24, false))
        val out = buildChatMessagesTranscript(parsed, File("/tmp"))
        assertEquals(1, out.size)
        val thinking = out[0].toolBlocks.first { it.kind == "thinking" }
        assertEquals("thought process", thinking.content)
    }
}
