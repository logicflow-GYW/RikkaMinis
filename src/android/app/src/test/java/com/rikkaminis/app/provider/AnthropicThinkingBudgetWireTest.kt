package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [deep-audit-1046 S-03] `max_tokens` / `thinking.budget_tokens` / beta-header
 * consistency, asserted on the WIRE (what MockWebServer actually received) —
 * not on the helper that computes each number in isolation.
 *
 * The defect was a split source: `max_tokens` was written from the clamped
 * value while the budget was computed from the RAW request value. Any caller
 * above the model ceiling (sub-agent budget, synthesized request) therefore
 * emitted an illegal pair, and Anthropic answers `thinking.budget_tokens must
 * be less than max_tokens` with a 400. A unit test on `thinkingBudget()` alone
 * cannot see that: both numbers are individually "correct".
 *
 * Kept in its own file (not appended to AnthropicProviderTest) so the pairing
 * contract has one home and the fixture models stay local.
 */
class AnthropicThinkingBudgetWireTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider

    /** Legacy (<=4.5) shape: budget_tokens path, temperature allowed. */
    private val legacySmallCeiling = LLMModel(
        id = "claude-3-5-sonnet-20241022",
        displayName = "Claude 3.5 Sonnet (capped)",
        provider = "Anthropic",
        contextWindow = 200_000,
        maxOutputTokens = 8_192,
        supportsReasoning = true,
    )

    /** Adaptive (4.6+) shape: effort path, temperature rejected. */
    private val adaptiveModel = LLMModel.claudeOpus48

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

    private fun enqueueEmptyOk() {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""",
            ),
        )
    }

    private fun send(maxTokens: Int, level: ThinkingLevel, model: LLMModel) = runBlocking {
        provider.model = model
        enqueueEmptyOk()
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, maxTokens, thinkingLevel = level)
        val request = server.takeRequest()
        JSONObject(request.body.readUtf8()) to request.getHeader("anthropic-beta")
    }

    // -- the illegal pair (the defect) -----------------------------------------

    @Test
    fun `over-ceiling HIGH keeps budget_tokens strictly below the clamped max_tokens`() {
        // Raw 100k against an 8k ceiling: the wire value is 8192, so the budget
        // must be measured against 8192 too (baseline emitted 65536 here).
        val (body, beta) = send(100_000, ThinkingLevel.HIGH, legacySmallCeiling)

        assertEquals(8_192, body.getInt("max_tokens"))
        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        val budget = thinking.getInt("budget_tokens")
        assertTrue("budget_tokens $budget must be > 0", budget > 0)
        assertTrue(
            "budget_tokens $budget must be < max_tokens 8192 — Anthropic 400s otherwise",
            budget < body.getInt("max_tokens"),
        )
        assertEquals(8_191, budget)
        assertNotNull("legacy enabled thinking still announces the interleaved beta", beta)
        assertTrue(beta!!.contains("interleaved-thinking-2025-05-14"))
    }

    @Test
    fun `over-ceiling MEDIUM keeps budget_tokens strictly below the clamped max_tokens`() {
        // MEDIUM's raw cap (32768) also exceeds the 8k ceiling — the same split
        // produced budget_tokens=32768 > max_tokens=8192.
        val (body, _) = send(100_000, ThinkingLevel.MEDIUM, legacySmallCeiling)

        assertEquals(8_192, body.getInt("max_tokens"))
        val budget = body.getJSONObject("thinking").getInt("budget_tokens")
        assertTrue("budget_tokens $budget must be < max_tokens", budget < 8_192)
        assertEquals(8_191, budget)
    }

    @Test
    fun `every top tier stays strictly below the clamped max_tokens`() {
        for (level in listOf(ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA)) {
            val (body, _) = send(100_000, level, legacySmallCeiling)
            assertEquals(8_192, body.getInt("max_tokens"))
            val budget = body.getJSONObject("thinking").getInt("budget_tokens")
            assertTrue("$level budget $budget must be < 8192", budget < 8_192)
        }
    }

    // -- degenerate ceiling: no legal budget exists ---------------------------

    @Test
    fun `no thinking block is emitted when the ceiling leaves no room for a budget`() {
        // max_tokens == 1 leaves nothing below it, so any budget would be
        // illegal. Omitting the field degrades to "no thinking" (the legacy
        // default) instead of shipping a 400.
        val oneTokenCeiling = legacySmallCeiling.copy(maxOutputTokens = 1)
        val (body, beta) = send(1, ThinkingLevel.MEDIUM, oneTokenCeiling)

        assertEquals(1, body.getInt("max_tokens"))
        assertFalse("no thinking field must be written", body.has("thinking"))
        assertNull("no thinking beta without a thinking body", beta)
    }

    // -- beta header: three-way, not two-way ----------------------------------

    @Test
    fun `adaptive model with thinking off sends disabled and no thinking beta`() {
        // Adaptive models think by default, so OFF must be sent explicitly —
        // but the beta header advertises the capability the body just turned
        // off, and relays reject the contradiction.
        val (body, beta) = send(4_096, ThinkingLevel.OFF, adaptiveModel)

        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(
            "a disabled thinking body must not announce a thinking beta (got: $beta)",
            beta?.contains("interleaved-thinking") == true,
        )
        assertFalse(
            "a disabled thinking body must not announce the effort beta (got: $beta)",
            beta?.contains("effort-2025-11-24") == true,
        )
    }

    @Test
    fun `adaptive model with thinking on sends the effort beta and no legacy beta`() {
        val (body, beta) = send(4_096, ThinkingLevel.HIGH, adaptiveModel)

        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
        assertNotNull(beta)
        assertTrue(beta!!.contains("effort-2025-11-24"))
        assertFalse("adaptive thinking must not also announce the legacy beta", beta.contains("interleaved-thinking"))
        assertEquals("high", body.getJSONObject("output_config").getString("effort"))
        assertFalse("adaptive path carries no budget_tokens", body.getJSONObject("thinking").has("budget_tokens"))
    }

    @Test
    fun `legacy model with thinking off sends no thinking field at all`() {
        // Legacy (<=4.5) models default to no thinking, so OFF means "absence" —
        // and absence must not conjure a beta header.
        val (body, beta) = send(4_096, ThinkingLevel.OFF, legacySmallCeiling)

        assertFalse(body.has("thinking"))
        assertNull(beta)
        assertEquals(4_096, body.getInt("max_tokens"))
    }

    // -- [FULLSCAN-1119 F-10] the documented 1024 floor -------------------------

    @Test
    fun `a max_tokens below the 1024 floor omits thinking instead of shipping an illegal budget`() {
        // thinkingBudget(HIGH) on 512 answers 511 — below Anthropic's documented
        // floor. The old guard only checked `budget > 0 && budget < max_tokens`,
        // so the wire carried thinking.enabled + budget_tokens=511 → 400.
        val (body, beta) = send(512, ThinkingLevel.HIGH, legacySmallCeiling)

        assertEquals(512, body.getInt("max_tokens"))
        assertFalse("no thinking field may be emitted: ${body.optJSONObject("thinking")}", body.has("thinking"))
        assertNull("no legacy thinking beta without a thinking body", beta)
    }

    @Test
    fun `a max_tokens exactly at the floor also has no legal budget`() {
        // 1024 needs budget < 1024 AND budget >= 1024: impossible.
        val (body, beta) = send(1024, ThinkingLevel.HIGH, legacySmallCeiling)

        assertEquals(1024, body.getInt("max_tokens"))
        assertFalse("no thinking field may be emitted", body.has("thinking"))
        assertNull(beta)
    }

    @Test
    fun `a legal max_tokens emits a budget inside the documented window`() {
        val (body, beta) = send(2048, ThinkingLevel.LOW, legacySmallCeiling)

        assertEquals(2048, body.getInt("max_tokens"))
        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        val budget = thinking.getInt("budget_tokens")
        assertTrue("budget $budget must be >= 1024", budget >= 1024)
        assertTrue("budget $budget must be < 2048", budget < 2048)
        assertTrue("the legacy beta still rides along", beta!!.contains("interleaved-thinking-2025-05-14"))
    }

    @Test
    fun `a large max_tokens keeps the ladder value`() {
        val (body, _) = send(100_000, ThinkingLevel.ULTRA, legacySmallCeiling)

        // The 8k ceiling caps max_tokens, so the budget is pressed down to 8191.
        assertEquals(8_192, body.getInt("max_tokens"))
        assertEquals(8_191, body.getJSONObject("thinking").getInt("budget_tokens"))
    }
}
