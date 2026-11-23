package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.model.*
import org.json.JSONObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ChatToolPrivacyTest {
    private var privacy = true
    @Before fun setup() { privacy = EnvVarPrivacyStore.isEnabled; EnvVarPrivacyStore.setEnabled(true) }
    @After fun cleanup() { EnvVarPrivacyStore.setEnabled(privacy) }
    private fun capsule(id: String) = AnthropicReplayCodec.complete(AnthropicReplay.Source.fromActualRequest(
        "https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "privacy-fixture")!!,
        Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"PRIVATE-CANARY"},{"type":"tool_use","id":"$id","name":"read_probe","input":{}}]""") as JsonArray)
    private fun group(id: String, result: String) = listOf(
        LLMMessage(LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(id, "read_probe", JSONObject())), dbMessageId = "a-$id", anthropicReplay = capsule(id)),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(id, "read_probe", result)), dbMessageId = "r-$id"))
    @Test fun changedReplayGroupIsRemovedButNeighborAndPrivateBytesAreUntouched() {
        val before = group("one", "password=1234") + group("two", "ordinary result")
        val private = before[0].anthropicReplay!!.blocksCopy()
        val next = redactToolHistoryForModel(before)
        assertNull(next[0].anthropicReplay)
        assertSame(before[2].anthropicReplay, next[2].anthropicReplay)
        assertEquals(private, before[0].anthropicReplay!!.blocksCopy())
        assertEquals("password=1234", (before[1].contentParts.single() as AgentContentPart.ToolResult).content)
        assertFalse((next[1].contentParts.single() as AgentContentPart.ToolResult).content.contains("1234"))
    }
    @Test fun privacyOffAffectsHotRequestNotNewDurableHistory() {
        EnvVarPrivacyStore.setEnabled(false)
        val raw = group("one", "password=1234")
        assertSame(raw, redactToolHistoryForModel(raw))
        EnvVarPrivacyStore.setEnabled(true)
        assertNull(redactToolHistoryForModel(raw)[0].anthropicReplay)
    }
    @Test fun coldReadRealParserBuilderProjectsOldRowWithoutChangingRawJson() {
        val raw = """[{"type":"toolResult","value":{"toolUseId":"one","name":"read_probe","output":"password=1234","success":true}}]"""
        val row = com.rikkaminis.app.data.db.MessageEntity("result", "privacy", "user", raw, 0L, sortOrder = 0)
        val messages = buildLlmMessagesFromParsed(parseRows(listOf(row)), File(".")) { _, _ -> null }
        assertFalse((messages.single().contentParts.single() as AgentContentPart.ToolResult).content.contains("1234"))
        assertEquals(raw, row.partsJson)
    }
    @Test fun derivedSummaryOnlyIsFilteredAndPinnedUserTextSurvives() {
        val user = "user literal password=1234"
        val pin = "<pinned-user-messages>password=2468</pinned-user-messages>"
        val s = "<context-summary>password=9876\n$pin</context-summary>\n$user"
        val out = redactDerivedSummaryText(s)
        assertFalse(out.contains("9876"))
        assertTrue(out.contains(pin))
        assertTrue(out.endsWith(user))
        assertEquals(user, redactDerivedSummaryText(user))
    }
    @Test fun imageAndToolIdentitySurviveProjection() {
        val bytes = byteArrayOf(10, 20, 30)
        val tool = AgentContentPart.ToolResult("identity", "browser_use", "password=1234", true, bytes, "image/png", "/var/minis/browser/x.png")
        val raw = listOf(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(tool)))
        val next = redactToolHistoryForModel(raw).single().contentParts.single() as AgentContentPart.ToolResult
        assertEquals(tool.id, next.id)
        assertEquals(tool.name, next.name)
        assertTrue(next.isError)
        assertSame(bytes, next.imageData)
        assertEquals(tool.imageLinuxPath, next.imageLinuxPath)
    }
}
