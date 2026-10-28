package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMediaAttachment
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [r3-b08 F-14] The provider-RSS probe's size estimate.
 *
 * What it pins: the estimate must see the parts that T132 moved OUT of `content`
 * (tool calls / tool results / history images live in `contentParts`) and the
 * bytes a model GENERATES (tool-call arguments, media), or the probe logs
 * `in=0` / `out=0` on exactly the heavy turns it exists to explain.
 *
 * Units are the probe's own: UTF-16 chars ×2 ("~2B/char"), media in raw bytes.
 * Every expectation is a literal.
 */
class ProviderRssEstimateTest {

    private fun msg(
        content: String = "",
        contentParts: List<AgentContentPart> = emptyList(),
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        audioParts: List<LLMMessage.AudioPart> = emptyList(),
    ) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        imageParts = imageParts,
        audioParts = audioParts,
        contentParts = contentParts,
    )

    @Test
    fun `plain text content counts two bytes per character`() {
        assertEquals(4L, approxInputBytes(listOf(msg(content = "ab")), emptyList()))
    }

    @Test
    fun `a structured text part is counted`() {
        val parts = listOf<AgentContentPart>(AgentContentPart.Text("abc"))
        assertEquals(6L, approxInputBytes(listOf(msg(contentParts = parts)), emptyList()))
    }

    @Test
    fun `a tool result counts its text and its image bytes`() {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.ToolResult(
                id = "t1",
                name = "read_image",
                content = "ok",
                imageData = byteArrayOf(1, 2, 3, 4, 5),
            )
        )
        // "ok" = 2 chars → 4 bytes, plus 5 raw image bytes.
        assertEquals(9L, approxInputBytes(listOf(msg(contentParts = parts)), emptyList()))
    }

    @Test
    fun `a tool use counts its name and its argument json`() {
        val args = JSONObject().put("k", "v") // {"k":"v"} = 9 chars
        val parts = listOf<AgentContentPart>(
            AgentContentPart.ToolUse(id = "t1", name = "read", input = args)
        )
        // "read" (4) + 9 chars = 13 chars → 26 bytes.
        assertEquals(26L, approxInputBytes(listOf(msg(contentParts = parts)), emptyList()))
    }

    @Test
    fun `an image part counts its raw bytes`() {
        val parts = listOf<AgentContentPart>(
            AgentContentPart.ImageData(data = byteArrayOf(1, 2, 3), mimeType = "image/png")
        )
        assertEquals(3L, approxInputBytes(listOf(msg(contentParts = parts)), emptyList()))
    }

    @Test
    fun `per-message and request-level images are both counted`() {
        val perMessage = listOf(LLMMessage.ImagePart(data = byteArrayOf(1, 2), mimeType = "image/png"))
        val turn = listOf(LLMMessage.ImagePart(data = byteArrayOf(1, 2, 3), mimeType = "image/png"))
        assertEquals(5L, approxInputBytes(listOf(msg(imageParts = perMessage)), turn))
    }

    @Test
    fun `audio parts are counted by their base64 length`() {
        val audio = listOf(LLMMessage.AudioPart(format = "wav", base64Data = "AAAA"))
        assertEquals(4L, approxInputBytes(listOf(msg(audioParts = audio)), emptyList()))
    }

    @Test
    fun `the non-streaming response counts its text and its media`() {
        val response = LLMResponse(
            text = "abc",
            stopReason = "stop",
            usage = null,
            mediaAttachments = listOf(
                LLMMediaAttachment(
                    type = LLMMediaAttachment.MediaType.IMAGE,
                    mimeType = "image/png",
                    data = byteArrayOf(1, 2, 3, 4, 5, 6, 7),
                )
            ),
        )
        assertEquals(6L + 7L, approxOutputBytes(response))
    }

    @Test
    fun `stream chunks count text, thinking and reasoning`() {
        assertEquals(4L, approxOutputChunkBytes(LLMStreamChunk.Text("ab")))
        assertEquals(6L, approxOutputChunkBytes(LLMStreamChunk.ThinkingDelta("abc")))
        assertEquals(2L, approxOutputChunkBytes(LLMStreamChunk.ReasoningContent("a")))
    }

    @Test
    fun `a completed tool call counts its argument json`() {
        val chunk = LLMStreamChunk.ToolCallComplete(
            id = "t1",
            name = "read",
            args = JSONObject().put("k", "v"), // 9 chars
        )
        assertEquals(18L, approxOutputChunkBytes(chunk))
    }

    @Test
    fun `a tool input delta alone does not double count`() {
        // `accumulated` is cumulative; ToolCallComplete already pays for it.
        assertEquals(0L, approxOutputChunkBytes(LLMStreamChunk.ToolInputDelta("t1", "{\"k\":\"v\"")))
    }

    @Test
    fun `generated media counts its bytes`() {
        val chunk = LLMStreamChunk.MediaAttachment(
            LLMMediaAttachment(
                type = LLMMediaAttachment.MediaType.IMAGE,
                mimeType = "image/png",
                data = byteArrayOf(1, 2, 3),
            )
        )
        assertEquals(3L, approxOutputChunkBytes(chunk))
    }

    @Test
    fun `lifecycle chunks cost nothing`() {
        assertEquals(0L, approxOutputChunkBytes(LLMStreamChunk.Started))
        assertEquals(0L, approxOutputChunkBytes(LLMStreamChunk.Finished("stop")))
    }
}
