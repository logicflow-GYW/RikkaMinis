package com.rikkaminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [model-defaults-1112] Contract tests for the shared image-input decision and
 * the optimistic unknown-context default.
 *
 * Two invariants, both with literal expectations (never derived from the code
 * under test):
 *
 *  1. [imageInputDecision] — a DECLARED list always decides (NATIVE vs
 *     DECLARED_TEXT_ONLY, regardless of the id); an ABSENT list falls to the
 *     family hint; the family hint is positive-only (it must never widen a
 *     declared text-only model, and text-family ids like deepseek / qwen plain
 *     must stay UNKNOWN).
 *  2. [acceptsImageInput] agrees with the provider gate: NATIVE and
 *     FAMILY_HINT go on the wire; DECLARED_TEXT_ONLY and UNKNOWN do not.
 *     (The provider gate intentionally diverges from
 *     ChatViewModel.currentModelSupportsImages, which stays `?: true` so the
 *     ATTACH affordance is never locked by unknown metadata — the divergence
 *     is the documented design, not a bug.)
 *
 * Plus the [LLMModel.capabilityPromptFragment] unknown-modality guard: a model
 * that never declared inputs must not receive a "You cannot natively process
 * images" assertion (that prompt text is what told an undeclared vision relay
 * "I can't see images").
 *
 * Every expected value is a hand-written literal.
 */
class ImageInputDecisionTest {

    // ── declared lists always win ───────────────────────────────────────────

    @Test
    fun `declared image input is NATIVE regardless of id`() {
        // deepseek is a text family; a DECLARED image row must still win.
        val m = LLMModel("deepseek-v4", "DeepSeek V4", "Custom", inputModalities = listOf("text", "image"))
        assertEquals(ImageInputDecision.NATIVE, m.imageInputDecision)
        assertTrue(m.acceptsImageInput)
    }

    @Test
    fun `declared image_input suffix form is NATIVE after normalization`() {
        val m = LLMModel("relay-model", "Relay", "Custom", inputModalities = listOf("text", "image_input"))
        assertEquals(ImageInputDecision.NATIVE, m.imageInputDecision)
    }

    @Test
    fun `declared text-only is DECLARED_TEXT_ONLY even for a vision family id`() {
        // The family hint must NOT widen a declared text-only model.
        val m = LLMModel("gpt-5-mini", "GPT-5 mini", "OpenAI", inputModalities = listOf("text"))
        assertEquals(ImageInputDecision.DECLARED_TEXT_ONLY, m.imageInputDecision)
        assertFalse(m.acceptsImageInput)
    }

    @Test
    fun `declared text-only claude id stays DECLARED_TEXT_ONLY`() {
        val m = LLMModel("claude-haiku-4-5", "Haiku", "Anthropic", inputModalities = listOf("text"))
        assertEquals(ImageInputDecision.DECLARED_TEXT_ONLY, m.imageInputDecision)
        assertFalse(m.acceptsImageInput)
    }

    // ── absent list falls to the family hint ────────────────────────────────

    @Test
    fun `absent list with vision family id is FAMILY_HINT`() {
        for (id in listOf(
            "gpt-5.5",                    // OpenAI reasoning generation
            "gpt-4o-2026-01-01",          // 4o line
            "claude-sonnet-4-6",          // Anthropic multimodal line
            "gemini-2.5-flash",           // Google
            "qwen3-vl-235b",              // -vl suffix family
            "glm-4.5v",                   // GLM vision
            "grok-4",                     // xAI vision generation
        )) {
            val m = LLMModel(id, id, "Custom")
            assertEquals("id=$id should be FAMILY_HINT", ImageInputDecision.FAMILY_HINT, m.imageInputDecision)
            assertTrue("id=$id should send images", m.acceptsImageInput)
        }
    }

    @Test
    fun `absent list with a text family id is UNKNOWN`() {
        for (id in listOf(
            "deepseek-v4",
            "deepseek-r3",
            "qwen3-235b",
            "kimi-k3",
            "minimax-m2",
            "text-embedding-3-large",     // also guards the embedding family
            "claude-2.1",                 // pre-multimodal Anthropic line
            "gemini-1.0-pro",             // pre-multimodal Google line
        )) {
            val m = LLMModel(id, id, "Custom")
            assertEquals("id=$id should be UNKNOWN", ImageInputDecision.UNKNOWN, m.imageInputDecision)
            assertFalse("id=$id must not send images", m.acceptsImageInput)
        }
    }

    @Test
    fun `empty declared list counts as absent`() {
        // normalizeModalities maps an empty list to null → family path.
        val m = LLMModel("gpt-5-mini", "GPT-5 mini", "OpenAI", inputModalities = emptyList())
        assertEquals(ImageInputDecision.FAMILY_HINT, m.imageInputDecision)
    }

    // ── capabilityPromptFragment unknown guard ─────────────────────────────

    @Test
    fun `unknown modalities produce no capability fragment`() {
        val m = LLMModel("relay-unknown", "Relay", "Custom")
        assertNull(m.capabilityPromptFragment())
    }

    @Test
    fun `declared text-only still asserts the shell-tool path`() {
        val m = LLMModel("deepseek-v4", "DeepSeek V4", "Custom", inputModalities = listOf("text"))
        val fragment = m.capabilityPromptFragment()
        assertTrue(fragment!!.contains("cannot natively process images"))
    }

    @Test
    fun `fully multimodal still returns null`() {
        val m = LLMModel(
            "omni-model", "Omni", "Custom",
            inputModalities = listOf("text", "image", "pdf", "audio", "video"),
        )
        assertNull(m.capabilityPromptFragment())
    }

    // ── optimistic unknown-context default ──────────────────────────────────

    @Test
    fun `unknown id heuristic default is 1M`() {
        val m = LLMModel("totally-unknown-model", "Unknown", "Custom")
        assertEquals(1_000_000, m.contextWindowTokens)
        assertEquals(LLMModel.ContextWindowSource.HEURISTIC, m.contextWindowSource)
    }

    @Test
    fun `explicit window keeps its value and classifies EXPLICIT`() {
        val m = LLMModel("small-model", "Small", "Custom", contextWindow = 32_768)
        assertEquals(32_768, m.contextWindowTokens)
        assertEquals(LLMModel.ContextWindowSource.EXPLICIT, m.contextWindowSource)
    }

    @Test
    fun `family heuristics are unchanged anchors`() {
        assertEquals(200_000, LLMModel("claude-haiku-4-5", "h", "Anthropic").contextWindowTokens)
        assertEquals(1_000_000, LLMModel("claude-sonnet-4-6", "s", "Anthropic").contextWindowTokens)
        assertEquals(400_000, LLMModel("gpt-5.2", "g", "OpenAI").contextWindowTokens)
        assertEquals(128_000, LLMModel("deepseek-v4", "d", "Custom").contextWindowTokens)
        assertEquals(32_000, LLMModel("gemini-1.0-pro", "g", "Google").contextWindowTokens)
    }
}
