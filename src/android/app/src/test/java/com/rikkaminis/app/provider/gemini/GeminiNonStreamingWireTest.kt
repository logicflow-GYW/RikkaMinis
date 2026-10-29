package com.rikkaminis.app.provider.gemini

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.rikkaminis.app.data.model.LLMModel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [R5-E2 E-C-02② / E-F-06] The Gemini non-streaming arm as the CALLER sees it:
 * a 2xx that carries a failure, a 2xx with no candidate, and the image
 * carriers on a turn whose `contentParts` are non-empty.
 *
 * Split from `GeminiNonStreamingVerdictTest` on purpose: that class holds the
 * pure tables (verdict, carrier table, host parsing) and can be compiled
 * without Android/OkHttp, so those tables are provable outside CI. This class
 * needs MockWebServer + the real provider and therefore only runs in the
 * Gradle test task.
 *
 * Expected values are hand-written literals (see evidence-discipline): the
 * status codes, the vision placeholder string and the model modalities are
 * re-stated from the API contract, never read back out of the code under test.
 */
class GeminiNonStreamingWireTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(model: LLMModel = LLMModel.gemini25Flash) = GeminiProvider(
        apiKey = "test-key",
        model = model,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    private fun oneUserTurn() = listOf(LLMMessage(LLMMessage.Role.USER, "hi"))

    // -- E-C-02② : a 2xx is not success --

    @Test
    fun `a 200 carrying an error object raises the mapped error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota exceeded"}}""",
            ),
        )
        try {
            provider().sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected a RateLimited failure, got a successful response")
        } catch (e: com.rikkaminis.app.data.model.LLMError.RateLimited) {
            // mapped by geminiStreamErrorStatus + mapHttpError, exactly like the
            // streaming arm's inline-error branch
        }
    }

    @Test
    fun `a 200 carrying a revoked-key error is an InvalidApiKey`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}"""))
        try {
            provider().sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected an InvalidApiKey failure, got a successful response")
        } catch (e: com.rikkaminis.app.data.model.LLMError.InvalidApiKey) {
            // as above
        }
    }

    @Test
    fun `a blocked prompt never becomes an empty end_turn`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            val r = provider().sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected a ProviderError, got text=${r.text} stop=${r.stopReason}")
        } catch (e: com.rikkaminis.app.data.model.LLMError.ProviderError) {
            assertTrue(
                "the block reason must reach the user, got: ${e.message}",
                e.message.orEmpty().contains("SAFETY"),
            )
        }
    }

    @Test
    fun `an empty candidate array never becomes an empty end_turn`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        try {
            val r = provider().sendMessage(oneUserTurn(), null, 100)
            throw AssertionError("expected a ProviderError, got text=${r.text} stop=${r.stopReason}")
        } catch (e: com.rikkaminis.app.data.model.LLMError.ProviderError) {
            assertTrue(e.message.orEmpty().contains("no candidates"))
        }
    }

    @Test
    fun `a pure tool turn still reports end_turn with empty text`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{}}}]},"finishReason":"STOP"}]}""",
            ),
        )
        val r = provider().sendMessage(oneUserTurn(), null, 100)
        assertEquals("", r.text)
        assertEquals("end_turn", r.stopReason)
    }

    // -- E-F-06 : the carriers on a structured turn --

    private fun visionModel() = LLMModel(
        id = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash",
        provider = "Google",
        inputModalities = listOf("text", "image"),
    )

    private val okBody =
        """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""

    private fun lastTurnParts(body: String): List<JSONObject> {
        val contents = JSONObject(body).getJSONArray("contents")
        val parts = contents.getJSONObject(contents.length() - 1).getJSONArray("parts")
        return (0 until parts.length()).map { parts.getJSONObject(it) }
    }

    @Test
    fun `request images reach the wire on a structured contentParts turn`() = runBlocking {
        server.enqueue(MockResponse().setBody(okBody))
        provider(visionModel()).sendMessage(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "look at this",
                    contentParts = listOf(AgentContentPart.Text("look at this")),
                ),
            ),
            null, 100,
            imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3, 4), "image/png")),
        )
        val parts = lastTurnParts(server.takeRequest().body.readUtf8())
        assertTrue(
            "the request-level image was dropped before the wire (R5-E2 E-F-06)",
            parts.any { it.has("inlineData") },
        )
        assertEquals(1, parts.count { it.has("inlineData") })
    }

    @Test
    fun `an already-inlined image is not sent twice`() = runBlocking {
        server.enqueue(MockResponse().setBody(okBody))
        val bytes = byteArrayOf(1, 2, 3, 4)
        provider(visionModel()).sendMessage(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "look at this",
                    // the live chat shape: ONE image list, two carriers
                    imageParts = listOf(LLMMessage.ImagePart(bytes, "image/png")),
                    contentParts = listOf(
                        AgentContentPart.Text("look at this"),
                        AgentContentPart.ImageData(bytes, "image/png"),
                    ),
                ),
            ),
            null, 100,
            imageParts = listOf(LLMMessage.ImagePart(bytes, "image/png")),
        )
        val parts = lastTurnParts(server.takeRequest().body.readUtf8())
        assertEquals(
            "the same attachment must reach the wire exactly once",
            1,
            parts.count { it.has("inlineData") },
        )
    }

    @Test
    fun `a text-only Gemini model sees the request image as a placeholder`() = runBlocking {
        server.enqueue(MockResponse().setBody(okBody))
        // gemini25Flash in the builtin catalog declares no input modalities →
        // no vision → the §27a placeholder, never an inlineData part.
        provider().sendMessage(
            listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "look",
                    contentParts = listOf(AgentContentPart.Text("look")),
                ),
            ),
            null, 100,
            imageParts = listOf(LLMMessage.ImagePart(byteArrayOf(1, 2, 3, 4), "image/png")),
        )
        val parts = lastTurnParts(server.takeRequest().body.readUtf8())
        assertTrue(
            "expected the vision placeholder, got ${parts.map { it.keys().asSequence().toList() }}",
            parts.any {
                it.optString("text", "") ==
                    "[Image attached but this model does not support vision input]"
            },
        )
        assertTrue(parts.none { it.has("inlineData") })
    }
}
