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
 * [R5-E F-06 + C-07③] The image carriers that live OUTSIDE `contentParts` —
 * the request-level list and the message's own `imageParts` — used to be read
 * only in the "no contentParts at all" branch, i.e. never on a turn the app
 * actually builds.
 *
 * Two halves are pinned here:
 *  · they now reach the wire on a contentParts turn, and
 *  · they are attached ONCE. Every producer fills both carriers from the same
 *    `prepared.imageParts`, so a naive "append both" fix would put each
 *    attached screenshot on the wire twice; "same bytes in three carriers" is
 *    the assertion that keeps that from creeping back.
 *
 * claudeHaiku45 declares no inputModalities → non-vision → each image becomes
 * the [VISION_UNSUPPORTED_PLACEHOLDER] text block, which is countable without
 * any base64 payload on the wire.
 */
class AnthropicImageCarrierWireTest {

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

    private fun messagesOf(
        messages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ): List<JSONObject> {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""",
            ),
        )
        kotlinx.coroutines.runBlocking {
            provider.sendMessage(messages, null, 512, imageParts = imageParts)
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val arr = body.getJSONArray("messages")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun contentOf(message: JSONObject): List<JSONObject> =
        when (val content = message.opt("content")) {
            is JSONArray -> (0 until content.length()).map { content.getJSONObject(it) }
            is String -> if (content.isEmpty()) emptyList() else
                listOf(JSONObject().put("type", "text").put("text", content))
            else -> emptyList()
        }

    private fun blocksOf(
        messages: List<LLMMessage>,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
    ): List<JSONObject> = messagesOf(messages, imageParts).flatMap { contentOf(it) }

    private fun List<JSONObject>.placeholders(): Int = count {
        it.optString("text") == VISION_UNSUPPORTED_PLACEHOLDER
    }

    private fun user(
        content: String,
        parts: List<AgentContentPart>,
        imgs: List<LLMMessage.ImagePart> = emptyList(),
    ) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = content,
        contentParts = parts,
        imageParts = imgs,
    )

    @Test
    fun `request-level images reach the wire on a contentParts turn`() {
        val carrier = LLMMessage.ImagePart(data = pngBytes, mimeType = "image/png")
        val blocks = blocksOf(
            listOf(user("what is this", listOf(AgentContentPart.Text("what is this")))),
            imageParts = listOf(carrier),
        )
        // Pre-fix this was 1: the contentParts branch never read `imageParts`,
        // and the top-level list is only emitted for the last user turn — so a
        // constructed turn answered about an image it never received.
        assertEquals(2, blocks.size)
        assertEquals(1, blocks.placeholders())
        assertEquals("what is this", blocks[0].optString("text"))
    }

    @Test
    fun `the message carrier reaches the wire on a contentParts turn`() {
        val carrier = LLMMessage.ImagePart(data = pngBytes, mimeType = "image/png")
        val blocks = blocksOf(
            listOf(user("what is this", listOf(AgentContentPart.Text("what is this")), listOf(carrier))),
        )
        assertEquals(2, blocks.size)
        assertEquals(1, blocks.placeholders())
    }

    @Test
    fun `the same bytes in three carriers are sent once`() {
        val carrier = LLMMessage.ImagePart(data = pngBytes, mimeType = "image/png")
        val blocks = blocksOf(
            listOf(
                user(
                    "what is this",
                    listOf(
                        AgentContentPart.Text("what is this"),
                        AgentContentPart.ImageData(pngBytes, "image/png"),
                    ),
                    listOf(carrier),
                ),
            ),
            imageParts = listOf(carrier),
        )
        // contentParts already emitted the image; the request-level list and
        // msg.imageParts hold the SAME bytes, so neither may be appended.
        assertEquals(2, blocks.size)
        assertEquals(1, blocks.placeholders())
    }

    @Test
    fun `a turn with two distinct images sends both, once each`() {
        val other = byteArrayOf(0x47, 0x49, 0x46, 0x38)
        val blocks = blocksOf(
            listOf(
                user(
                    "compare",
                    listOf(
                        AgentContentPart.Text("compare"),
                        AgentContentPart.ImageData(pngBytes, "image/png"),
                        AgentContentPart.ImageData(other, "image/gif"),
                    ),
                ),
            ),
        )
        assertEquals(3, blocks.size)
        assertEquals(2, blocks.placeholders())
    }

    @Test
    fun `an image on a non-last user turn still arrives`() {
        val carrier = LLMMessage.ImagePart(data = pngBytes, mimeType = "image/png")
        val messages = messagesOf(
            listOf(
                user("first", listOf(AgentContentPart.Text("first")), listOf(carrier)),
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = "noted",
                    contentParts = listOf(AgentContentPart.Text("noted")),
                ),
                user("second", listOf(AgentContentPart.Text("second"))),
            ),
        )
        assertEquals(3, messages.size)
        // The request-level list belongs to the LAST user turn only (unchanged
        // contract) but an earlier turn's own carrier is still its own.
        val first = contentOf(messages[0])
        assertEquals(2, first.size)
        assertEquals(VISION_UNSUPPORTED_PLACEHOLDER, first[1].optString("text"))
        val last = contentOf(messages[2])
        assertEquals(1, last.size)
        assertEquals("second", last[0].optString("text"))
    }
}
