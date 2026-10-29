package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [R5-E1] End-to-end wire contracts for the two OpenAI defects this branch fixes.
 *
 * The pure tables live in `R5E1WiringTest` (provider/openai) — this class proves
 * the REAL provider emits the expected bytes, with MockWebServer standing in for
 * the vendor. Everything below is a literal: no expectation is derived from the
 * code under test.
 *
 *  - E-F-05: a non-2xx `url` download must never become an attachment, and an
 *    image request that produced nothing must not return success.
 *  - E-F-06: request-level `imageParts` must ride the last user turn even when
 *    that turn already carries `contentParts` — and must NOT be duplicated when
 *    the composer put the same bytes in both carriers.
 */
class R5E1OpenAIImageContractTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    private val visionModel = LLMModel(
        id = "vision-test",
        displayName = "Vision Test",
        provider = "OpenAI",
        inputModalities = listOf("text", "image"),
    )

    /** 8-byte PNG signature + payload; only the magic matters to the mime sniffer. */
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = visionModel,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sseBody(vararg events: String): String = buildString {
        for (event in events) {
            append("data: $event")
            append("\n\n")
        }
        append("data: [DONE]")
        append("\n\n")
    }

    // ── E-F-05 ────────────────────────────────────────────────────────

    @Test
    fun `403 on the image url download is not wrapped into an image attachment`() = runBlocking {
        val imageUrl = server.url("/expired.png").toString()
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[{"url":"$imageUrl"}]}""")
                .setHeader("Content-Type", "application/json"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("<html><body>expired signature</body></html>")
                .setHeader("Content-Type", "text/html"),
        )

        val thrown = try {
            provider.generateImage("a red cube")
            null
        } catch (t: Throwable) {
            t
        }

        if (thrown == null) fail("a failed download must not be reported as a generated image")
        assertTrue("expected LLMError.ProviderError, got $thrown", thrown is LLMError.ProviderError)
        // Literal: the hop's status must be named so the failure is diagnosable.
        assertEquals(
            "Provider error: images/generations declared 1 image item(s) but none was usable: (last download HTTP 403)",
            thrown!!.message,
        )
    }

    @Test
    fun `500 on the image url download is likewise rejected`() = runBlocking {
        val imageUrl = server.url("/boom.png").toString()
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[{"url":"$imageUrl"}]}""")
                .setHeader("Content-Type", "application/json"),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("upstream exploded"))

        val thrown = try {
            provider.generateImage("a red cube")
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue(thrown is LLMError.ProviderError)
        assertTrue(
            "status must be reported: ${thrown?.message}",
            thrown!!.message!!.contains("HTTP 500"),
        )
    }

    @Test
    fun `2xx download still yields the image`() = runBlocking {
        val imageUrl = server.url("/ok.png").toString()
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[{"url":"$imageUrl"}]}""")
                .setHeader("Content-Type", "application/json"),
        )
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(pngBytes))
                .setHeader("Content-Type", "image/png"),
        )

        val response = provider.generateImage("a red cube")
        assertEquals(1, response.mediaAttachments.size)
        assertEquals("image/png", response.mediaAttachments[0].mimeType)
        assertEquals(10, response.mediaAttachments[0].data.size)
    }

    // ── E-F-06 ────────────────────────────────────────────────────────

    private fun sentChatUserContent(): JSONArray {
        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        val messages = body.getJSONArray("messages")
        for (i in messages.length() - 1 downTo 0) {
            val msg = messages.getJSONObject(i)
            if (msg.optString("role") == "user" && msg.opt("content") is JSONArray) {
                return msg.getJSONArray("content")
            }
        }
        throw AssertionError("no structured user message in the request body: $body")
    }

    private fun countParts(content: JSONArray, type: String): Int {
        var n = 0
        for (i in 0 until content.length()) {
            if (content.getJSONObject(i).optString("type") == type) n++
        }
        return n
    }

    @Test
    fun `request-level image rides a last user turn that already has contentParts`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
                .setHeader("Content-Type", "text/event-stream"),
        )

        provider.sendMessage(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(AgentContentPart.Text("look at this")),
                ),
            ),
            systemPrompt = null,
            maxTokens = 64,
            imageParts = listOf(LLMMessage.ImagePart(pngBytes, "image/png")),
        )

        val content = sentChatUserContent()
        assertEquals(1, countParts(content, "text"))
        assertEquals(1, countParts(content, "image_url"))
    }

    @Test
    fun `the same bytes in both carriers are sent exactly once`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
                .setHeader("Content-Type", "text/event-stream"),
        )

        // Byte-equal but a DIFFERENT array instance, which is the shape the worker
        // path produces after decoding contentParts from JSON.
        val turnCopy = pngBytes.copyOf()
        provider.sendMessage(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(
                        AgentContentPart.Text("[attached image: a.png]"),
                        AgentContentPart.ImageData(data = turnCopy, mimeType = "image/png"),
                    ),
                ),
            ),
            systemPrompt = null,
            maxTokens = 64,
            imageParts = listOf(LLMMessage.ImagePart(pngBytes, "image/png")),
        )

        val content = sentChatUserContent()
        assertEquals("the composer's dual carrier must not double the photo", 1, countParts(content, "image_url"))
    }

    @Test
    fun `distinct request image is appended to the turn's own image`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
                .setHeader("Content-Type", "text/event-stream"),
        )

        val historyImage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 1, 1, 1)
        provider.sendMessage(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = listOf(AgentContentPart.ImageData(data = historyImage, mimeType = "image/png")),
                ),
            ),
            systemPrompt = null,
            maxTokens = 64,
            imageParts = listOf(LLMMessage.ImagePart(pngBytes, "image/png")),
        )

        val content = sentChatUserContent()
        assertEquals(2, countParts(content, "image_url"))
    }
}
