package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.VISION_UNSUPPORTED_PLACEHOLDER
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * [R5-E C-07①/②/③] Wire-level assertions on the Anthropic Messages body.
 *
 * The Messages API hard-400s on an empty text block ("text content blocks must
 * be non-empty") and on an empty `content` array — the same failure one level
 * out. These tests assert the SERIALIZED body (what the endpoint would parse),
 * not internal state, so any refactor that reintroduces an empty block flips
 * them red.
 *
 * claudeHaiku45 carries no `inputModalities`, so the provider treats it as
 * non-vision and every image arm emits the [VISION_UNSUPPORTED_PLACEHOLDER]
 * text block. That is the arm asserted below — it needs no image bytes on the
 * wire, and it proves the carrier was consumed.
 */
class AnthropicContentBlocksWireTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "test-key",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun bodyOf(
        messages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ): JSONObject {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""",
            ),
        )
        kotlinx.coroutines.runBlocking {
            provider.sendMessage(messages, null, 512, imageParts = imageParts)
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    /** Flatten every content block of every (merged) message, in order. */
    private fun blocksOf(body: JSONObject): List<JSONObject> {
        val messages = body.getJSONArray("messages")
        val out = mutableListOf<JSONObject>()
        for (i in 0 until messages.length()) {
            when (val content = messages.getJSONObject(i).opt("content")) {
                is JSONArray -> for (k in 0 until content.length()) out.add(content.getJSONObject(k))
                is String -> if (content.isNotEmpty()) {
                    out.add(JSONObject().put("type", "text").put("text", content))
                }
            }
        }
        return out
    }

    private fun List<JSONObject>.emptyTextBlocks(): Int = count {
        it.optString("type") == "text" && it.optString("text").isEmpty()
    }

    private fun toolUse(id: String, name: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = "",
        contentParts = listOf(AgentContentPart.ToolUse(id = id, name = name, input = JSONObject())),
    )

    @Test
    fun `empty tool result content keeps the tool_result legal`() {
        val blocks = blocksOf(
            bodyOf(
                listOf(
                    toolUse("toolu_01", "file_read"),
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = listOf(
                            AgentContentPart.ToolResult(
                                id = "toolu_01",
                                name = "file_read",
                                content = "",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toolResult = blocks.first { it.optString("type") == "tool_result" }
        assertEquals("toolu_01", toolResult.optString("tool_use_id"))
        val resultContent = toolResult.getJSONArray("content")
        assertEquals(1, resultContent.length())
        assertEquals("text", resultContent.getJSONObject(0).optString("type"))
        // Single-space carrier — the GeminiProvider literal, kept identical so a
        // cross-provider replay of one history is byte-comparable.
        assertEquals(" ", resultContent.getJSONObject(0).optString("text"))
        assertEquals(0, blocks.emptyTextBlocks())
    }

    @Test
    fun `tool result with an image does not also send an empty text block`() {
        val blocks = blocksOf(
            bodyOf(
                listOf(
                    toolUse("toolu_02", "read_image"),
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = listOf(
                            AgentContentPart.ToolResult(
                                id = "toolu_02",
                                name = "read_image",
                                content = "",
                                imageData = pngBytes,
                                imageMimeType = "image/png",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val resultContent = blocks.first { it.optString("type") == "tool_result" }.getJSONArray("content")
        assertEquals(1, resultContent.length())
        assertEquals(VISION_UNSUPPORTED_PLACEHOLDER, resultContent.getJSONObject(0).optString("text"))
        assertEquals(0, blocks.emptyTextBlocks())
    }

    @Test
    fun `tool result with text and image drops nothing`() {
        val blocks = blocksOf(
            bodyOf(
                listOf(
                    toolUse("toolu_03", "read_image"),
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = listOf(
                            AgentContentPart.ToolResult(
                                id = "toolu_03",
                                name = "read_image",
                                content = "decoded 16x16 png",
                                imageData = pngBytes,
                                imageMimeType = "image/png",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val resultContent = blocks.first { it.optString("type") == "tool_result" }.getJSONArray("content")
        assertEquals(2, resultContent.length())
        assertEquals("decoded 16x16 png", resultContent.getJSONObject(0).optString("text"))
        assertEquals(VISION_UNSUPPORTED_PLACEHOLDER, resultContent.getJSONObject(1).optString("text"))
    }

    @Test
    fun `merged same-role turn never carries a fabricated empty text block`() {
        val body = bodyOf(
            listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = "first", contentParts = emptyList()),
                LLMMessage(role = LLMMessage.Role.USER, content = "second", contentParts = emptyList()),
            ),
        )
        // The merger is mandatory (Anthropic alternates roles), so both turns
        // land in one user message — with two text blocks, neither empty.
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        val blocks = blocksOf(body)
        assertEquals(2, blocks.size)
        assertEquals(0, blocks.emptyTextBlocks())
        assertEquals("first", blocks[0].optString("text"))
        assertEquals("second", blocks[1].optString("text"))
    }

    @Test
    fun `an all-empty turn never reaches the wire with an empty payload`() {
        val body = bodyOf(
            listOf(LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = emptyList())),
        )
        val messages = body.optJSONArray("messages")
        for (i in 0 until (messages?.length() ?: 0)) {
            val content = messages!!.getJSONObject(i).opt("content")
            if (content is String) {
                assertEquals("string content must not be empty", false, content.isEmpty())
            }
            if (content is JSONArray) {
                assertEquals("content array must not be empty", false, content.length() == 0)
            }
        }
    }
}
