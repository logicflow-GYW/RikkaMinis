package com.rikkaminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-context-window-sources] JVM tests for [LLMModel.contextWindowSource] —
 * the classification that powers the group-priority capacity policy and the
 * Token Usage "heuristic guess" warning.
 *
 * Regression pinned: a 1M-context model whose metadata wasn't reported (custom
 * / router / image-output / obscure ids) silently landed on the 128K id-guess
 * and capped offload/compaction judgment at ⅛ of real capacity. Correctly
 * classifying it as HEURISTIC lets the group limit take over and flags the
 * guess in the Token Usage sheet so the user can correct it.
 */
class LLMModelContextWindowSourceTest {

    @Test
    fun `explicit contextWindow classifies as EXPLICIT`() {
        val m = LLMModel(
            id = "claude-sonnet-4-6",
            displayName = "Sonnet",
            provider = "anthropic",
            contextWindow = 1_000_000,
        )
        assertEquals(LLMModel.ContextWindowSource.EXPLICIT, m.contextWindowSource)
    }

    @Test
    fun `null contextWindow classifies as HEURISTIC`() {
        val m = LLMModel(
            id = "some-obscure-custom-model",
            displayName = "Custom",
            provider = "openai",
            contextWindow = null,
        )
        assertEquals(LLMModel.ContextWindowSource.HEURISTIC, m.contextWindowSource)
    }

    @Test
    fun `zero contextWindow classifies as HEURISTIC`() {
        val m = LLMModel(
            id = "router-model",
            displayName = "Router",
            provider = "openrouter",
            contextWindow = 0,
        )
        assertEquals(LLMModel.ContextWindowSource.HEURISTIC, m.contextWindowSource)
    }

    @Test
    fun `user override resolving to a value classifies as EXPLICIT`() {
        // Mirrors ModelEntry.model (effective) semantics: an override folding
        // into contextWindow means the user deliberately set it → explicit,
        // even if the base had no metadata.
        val base = LLMModel(
            id = "mystery-model",
            displayName = "Mystery",
            provider = "xai",
            contextWindow = null,
        )
        val effective = base.copy(contextWindow = 128_000)
        assertEquals(LLMModel.ContextWindowSource.EXPLICIT, effective.contextWindowSource)
        assertEquals(LLMModel.ContextWindowSource.HEURISTIC, base.contextWindowSource)
    }
}