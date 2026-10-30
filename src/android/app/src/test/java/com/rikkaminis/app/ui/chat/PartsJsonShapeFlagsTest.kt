package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.MediaRef
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SYMPTOM-A F-A4] The `[T-HANG-DIAG] messages-shape` counters must match the
 * dialect the rows are actually written in.
 *
 * Contract: **the key the writer emits == the string the counter looks for.**
 * Each case below builds its partsJson with the REAL producer — the same
 * functions `persistAssistantTurn` / `persistToolResultMessage` /
 * `buildUserPartsJson` call — and the expected literal (`"\"toolUse\""`) is
 * hard-coded, never derived from `@SerialName` (a rename of the annotation
 * would rename the expectation too, and the test would keep passing: the
 * self-referential JVM harness trap).
 *
 * (Note: `contentPartJson.encodeToString` is NOT the producer. The wire shape
 * here is hand-built — `ChatTurnPartsJson.kt` / `ChatMessageJson.kt` — and the
 * polymorphic `ContentPartSerializer` does not even emit the `type`
 * discriminator on encode. Testing the serializer would have pinned a shape
 * the app never writes.)
 *
 * Why it matters: before this fix `toolMessages` was 0 on **every** one of the
 * 52 `messages-shape` lines logged on 2026-10-29 — including a session with
 * 352 messages / 538K chars — and a triage pass read that as "this session has
 * no tool calls". `attachmentMessages` was a different defect: it matched
 * images but missed every `mediaRef` attachment.
 */
class PartsJsonShapeFlagsTest {

    private fun toolUseRow(): String = buildAssistantTurnPartsJson(
        listOf(AgentContentPart.ToolUse(id = "t1", name = "shell_execute", input = JSONObject())),
        emptyMap(),
    )

    private fun toolResultRow(): String = buildToolResultPartsJson(
        listOf(AgentContentPart.ToolResult(id = "t1", name = "shell_execute", content = "ok")),
    )

    private fun mediaRow(): String = buildMediaRefPartJson(
        MediaRef(id = "m1", relativePath = "attachments/a.png", mimeType = "image/png"),
    )

    @Test
    fun `tool rows are counted under the keys the writers actually emit`() {
        val use = toolUseRow()
        val result = toolResultRow()
        assertTrue(use, use.contains("\"toolUse\""))
        assertTrue(result, result.contains("\"toolResult\""))
        assertTrue(use, partsJsonHasToolParts(use))
        assertTrue(result, partsJsonHasToolParts(result))
    }

    @Test
    fun `the old snake-only judgement misses what the writer emits`() {
        // 这条是缺陷本身的证据面：写作 camel、判据只认 snake ⇒ 恒 0。
        val use = toolUseRow()
        assertFalse("writer no longer emits snake: $use", use.contains("\"tool_use\""))
        assertFalse("writer no longer emits snake: $use", use.contains("\"tool_result\""))
    }

    @Test
    fun `media attachments are counted under mediaRef`() {
        val media = mediaRow()
        assertTrue(media, media.contains("\"mediaRef\""))
        assertTrue(media, partsJsonHasAttachmentParts(media))
        assertFalse("a mediaRef row carries no tool carrier: $media", partsJsonHasToolParts(media))
    }

    @Test
    fun `plain text rows carry neither signal`() {
        val text = buildAssistantTurnPartsJson(listOf(AgentContentPart.Text("hello")), emptyMap())
        assertFalse(text, partsJsonHasToolParts(text))
        assertFalse(text, partsJsonHasAttachmentParts(text))
    }

    @Test
    fun `legacy snake rows still count as tool rows`() {
        // 备份导入接受两种方言（ConfigBackup.kt:2971/:2988），所以历史/外来行
        // 不能被算成"无工具"——兼容臂的存在理由就是这一条。
        assertTrue(partsJsonHasToolParts("""[{"type":"tool_use","value":{"toolUseId":"t1"}}]"""))
        assertTrue(partsJsonHasToolParts("""[{"type":"tool_result","value":{"toolUseId":"t1"}}]"""))
    }
}
