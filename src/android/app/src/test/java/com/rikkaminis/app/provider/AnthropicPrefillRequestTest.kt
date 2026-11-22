package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.anthropic.AnthropicProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real public clamp -> real builder -> recorded bytes, not a cached last request. */
class AnthropicPrefillRequestTest {
    @Test fun requestCapabilityMatchesWireAcrossGenerationThinkingAndBudget() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val ids = listOf("claude-sonnet-4-6", "claude-opus-4-7", "claude-sonnet-5",
                "claude-haiku-4-5-20251001", "claude-3-7-sonnet-20250219", "unknown-model")
            var cases = 0
            for (id in ids) for (reasoning in listOf(true, false)) {
                val adaptive = id in ids.take(3)
                val knownLegacy = id == ids[3] || id == ids[4]
                val provider = AnthropicProvider("fixture-key", LLMModel(id, id, "Anthropic",
                    maxOutputTokens = 4096, supportsReasoning = reasoning), server.url("/").toString().trimEnd('/'))
                for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.AUTO, ThinkingLevel.HIGH)) {
                    for (requested in listOf(1, 512, 1024, 1025, 4096, 100_000)) {
                        val cap = minOf(requested, 4096)
                        // Explicit oracle: AUTO is not clamped; HIGH on a declared
                        // non-reasoning model becomes OFF before the real emitter.
                        val effectiveHigh = level == ThinkingLevel.HIGH && reasoning
                        val expectedThinking = when {
                            adaptive && (level == ThinkingLevel.AUTO || effectiveHigh) -> "adaptive"
                            adaptive -> "disabled"
                            effectiveHigh && cap > 1024 -> "enabled"
                            else -> "absent"
                        }
                        val expectedPrefill = knownLegacy && expectedThinking == "absent"
                        assertEquals("$id/$reasoning/$level/$requested", expectedPrefill,
                            provider.supportsPrefillForRequest(requested, level))
                        val history = buildList {
                            add(LLMMessage(LLMMessage.Role.USER, "question"))
                            add(LLMMessage(LLMMessage.Role.ASSISTANT, "unfinished"))
                            if (!provider.supportsPrefillForRequest(requested, level)) {
                                add(LLMMessage(LLMMessage.Role.USER, "continue"))
                            }
                        }
                        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
                        provider.sendMessage(history, null, requested, thinkingLevel = level)
                        val body = JSONObject(server.takeRequest().body.readUtf8())
                        assertEquals(cap, body.getInt("max_tokens"))
                        assertEquals(expectedThinking, body.optJSONObject("thinking")?.optString("type") ?: "absent")
                        val messages = body.getJSONArray("messages")
                        assertEquals(if (expectedPrefill) "assistant" else "user", messages.getJSONObject(messages.length()-1).getString("role"))
                        if (expectedThinking == "enabled") {
                            assertEquals(cap - 1, body.getJSONObject("thinking").getInt("budget_tokens"))
                            assertEquals(1.0, body.getDouble("temperature"), 0.0)
                        }
                        cases++
                    }
                }
            }
            assertEquals(216, cases)
        } finally { server.shutdown() }
    }

    @Test fun queriesUseCurrentModelAndDoNotDependOnPreviousWireCall() {
        val p = AnthropicProvider("fixture-key", LLMModel("claude-haiku-4-5", "legacy", "Anthropic", supportsReasoning = true))
        assertTrue(p.supportsPrefillForRequest(512, ThinkingLevel.HIGH))
        assertFalse(p.supportsPrefillForRequest(4096, ThinkingLevel.HIGH))
        assertTrue(p.supportsPrefillForRequest(4096, ThinkingLevel.OFF))
        p.model = LLMModel("claude-sonnet-4-6", "new", "Anthropic", supportsReasoning = true)
        assertFalse(p.supportsPrefillForRequest(512, ThinkingLevel.OFF))
        for (id in listOf("claude-mystery", "not-a-claude-4-5", "claude-mystery-4-5", "claude-sonnet-4-9")) {
            p.model = LLMModel(id, "unknown", "Anthropic")
            assertFalse(id, p.supportsPrefillForRequest(512, ThinkingLevel.OFF))
        }
    }
}
