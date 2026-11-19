package com.rikkaminis.app.provider.gemini

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FULLSCAN-1119 F-11] The clamp asserted on the WIRE: what MockWebServer
 * actually received, not what a helper computed.
 *
 * `thinkingConfig.thinkingBudget` comes from the model-family ladder, which has
 * no idea what `maxOutputTokens` this particular request sends. A 2.5 Pro at
 * HIGH asks for 16384 thinking tokens even when the caller allows 1024 output
 * tokens: Gemini answers 400 for budget > maxOutputTokens, and where it is
 * accepted thinking eats the entire output allowance so the reply body is
 * empty.
 *
 * Expected values are literals from the API contract (Pro HIGH ladder = 16384),
 * never read back out of the code under test.
 */
class GeminiThinkingBudgetWireTest {

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

    private fun provider(model: LLMModel) = GeminiProvider(
        apiKey = "test-key",
        model = model,
        basePath = server.url("/").toString().trimEnd('/'),
    )

    /** Runs one non-streaming turn and returns the request body the server saw. */
    private fun wireBody(model: LLMModel, maxTokens: Int, level: ThinkingLevel): JSONObject = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""",
            ),
        )
        provider(model).sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
            null,
            maxTokens,
            thinkingLevel = level,
        )
        JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a small output budget caps the thinking budget on the wire`() {
        val body = wireBody(LLMModel.gemini25Pro, 1024, ThinkingLevel.HIGH)
        val config = body.getJSONObject("generationConfig")

        assertEquals(1024, config.getInt("maxOutputTokens"))
        val thinking = config.getJSONObject("thinkingConfig")
        val budget = thinking.getInt("thinkingBudget")
        assertEquals("thinking is bounded by the output budget, got $budget", 1024, budget)
        assertTrue("the Pro ladder asked for far more", budget <= config.getInt("maxOutputTokens"))
    }

    @Test
    fun `a large output budget leaves the family ladder value alone`() {
        // A model whose declared ceiling leaves room for the whole Pro ladder.
        val roomy = LLMModel.gemini25Pro.copy(maxOutputTokens = 100_000)
        val body = wireBody(roomy, 100_000, ThinkingLevel.HIGH)
        val config = body.getJSONObject("generationConfig")

        assertEquals(100_000, config.getInt("maxOutputTokens"))
        // Pro HIGH ladder value, unchanged when there is room for it.
        assertEquals(16384, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test
    fun `the 2_5 Flash family is clamped by the same rule`() {
        val body = wireBody(LLMModel.gemini25Flash, 512, ThinkingLevel.HIGH)
        val config = body.getJSONObject("generationConfig")

        assertEquals(512, config.getInt("maxOutputTokens"))
        val budget = config.getJSONObject("thinkingConfig").getInt("thinkingBudget")
        assertTrue("Flash HIGH asked for 8192, got $budget", budget <= 512)
    }

    @Test
    fun `the emitted pair is legal for every boundary`() {
        for (maxTokens in listOf(512, 1024, 2048, 100_000)) {
            val config = wireBody(LLMModel.gemini25Pro, maxTokens, ThinkingLevel.HIGH)
                .getJSONObject("generationConfig")
            val wireMax = config.getInt("maxOutputTokens")
            val budget = config.getJSONObject("thinkingConfig").getInt("thinkingBudget")
            assertTrue(
                "thinkingBudget=$budget must not exceed maxOutputTokens=$wireMax",
                budget <= wireMax,
            )
        }
    }
}
