package com.rikkaminis.app.provider.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FULLSCAN-1119 F-10] Anthropic's legacy thinking contract is
 * `1024 <= budget_tokens < max_tokens`. `AnthropicProvider.thinkingBudget`
 * only enforced the per-LEVEL ladder plus the upper bound, so a request whose
 * `max_tokens` sits below the documented floor emitted `budget_tokens=511` /
 * `1023` — a deterministic 400 ("budget_tokens must be at least 1024").
 *
 * Values here drive the REAL [AnthropicLegacyThinkingBudget] (production code,
 * no Android types). The provider's delegation to it is pinned by
 * `AnthropicLegacyThinkingBudgetWiringTest`, and the body the provider actually
 * sends by `AnthropicThinkingBudgetWireTest`.
 */
class AnthropicLegacyThinkingBudgetTest {

    // -- no legal pair --------------------------------------------------------

    @Test
    fun `a ceiling at or below the floor has NO legal budget`() {
        // max_tokens 512: thinkingBudget(HIGH) answers 511 — below the floor.
        assertNull(AnthropicLegacyThinkingBudget.legalBudgetTokens(511, 512))
        // max_tokens 1024: 1023 is still below 1024, and 1024 itself is not
        // strictly less than max_tokens. Nothing can be legal.
        assertNull(AnthropicLegacyThinkingBudget.legalBudgetTokens(1023, 1024))
        assertNull(AnthropicLegacyThinkingBudget.legalBudgetTokens(1024, 1024))
        assertNull(AnthropicLegacyThinkingBudget.legalBudgetTokens(0, 1024))
    }

    // -- floor ----------------------------------------------------------------

    @Test
    fun `a budget below the floor is raised to 1024`() {
        assertEquals(1024, AnthropicLegacyThinkingBudget.legalBudgetTokens(0, 4096))
        assertEquals(1024, AnthropicLegacyThinkingBudget.legalBudgetTokens(800, 4096))
        // ...but only when the raised value still fits under max_tokens.
        assertEquals(1024, AnthropicLegacyThinkingBudget.legalBudgetTokens(800, 1025))
        assertNull(AnthropicLegacyThinkingBudget.legalBudgetTokens(800, 1024))
    }

    // -- ceiling --------------------------------------------------------------

    @Test
    fun `a budget at or above the ceiling is pressed down to max_tokens - 1`() {
        assertEquals(2047, AnthropicLegacyThinkingBudget.legalBudgetTokens(2048, 2048))
        assertEquals(1024, AnthropicLegacyThinkingBudget.legalBudgetTokens(65536, 1025))
        assertEquals(4095, AnthropicLegacyThinkingBudget.legalBudgetTokens(65536, 4096))
    }

    @Test
    fun `a legal budget is passed through unchanged`() {
        assertEquals(1024, AnthropicLegacyThinkingBudget.legalBudgetTokens(1024, 4096))
        assertEquals(8191, AnthropicLegacyThinkingBudget.legalBudgetTokens(8191, 100_000))
        assertEquals(32768, AnthropicLegacyThinkingBudget.legalBudgetTokens(32_768, 100_000))
    }

    // -- the property that matters --------------------------------------------

    @Test
    fun `every answer is wire-legal or absent`() {
        val ceilings = listOf(1, 512, 1024, 1025, 2048, 8192, 100_000)
        val requested = listOf(0, 1, 511, 1023, 1024, 2048, 8191, 32768, 65536)
        for (maxTokens in ceilings) {
            for (want in requested) {
                val budget = AnthropicLegacyThinkingBudget.legalBudgetTokens(want, maxTokens)
                if (budget == null) continue
                assertTrue(
                    "budget=$budget illegal for max_tokens=$maxTokens (requested $want)",
                    budget >= AnthropicLegacyThinkingBudget.MIN_BUDGET_TOKENS && budget < maxTokens,
                )
            }
        }
    }

    @Test
    fun `the floor constant is the documented 1024`() {
        assertEquals(1024, AnthropicLegacyThinkingBudget.MIN_BUDGET_TOKENS)
    }
}
