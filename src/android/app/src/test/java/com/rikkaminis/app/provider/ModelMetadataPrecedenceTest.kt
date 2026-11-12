package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [gateway-context-1112] Contract tests for the metadata precedence rules.
 *
 * The defect this pins: the endpoint's own `/v1/models` number was DISCARDED
 * in favour of the models.dev registry's value for the same model, and when
 * the registry had no row for the id the endpoint published (it publishes
 * `vendor/model`, the registry keys the bare model) the app had nothing but
 * an id-shape guess — which is the 128K the user saw for a gateway that was
 * advertising 960 000.
 *
 * The precedence is **endpoint > registry > heuristic**. These tests pin both
 * halves of that sentence: the endpoint wins, and the registry still fills a
 * gap.
 *
 * Every expected value is a hand-written literal.
 */
class ModelMetadataPrecedenceTest {

    private fun endpointModel(contextWindow: Int? = null) = LLMModel(
        id = "codebuddy-cn/hy4free",
        displayName = "Hy4",
        provider = "Custom",
        contextWindow = contextWindow,
    )

    private fun merge(
        model: LLMModel,
        registryContextWindow: Int? = null,
        registryMaxOutputTokens: Int? = null,
        registrySupportsReasoning: Boolean? = null,
        registryInputModalities: List<String>? = null,
        registryOutputModalities: List<String>? = null,
        registryDeclaresNoEffortTiers: Boolean = false,
    ) = mergeRegistryRow(
        model = model,
        registryContextWindow = registryContextWindow,
        registryMaxOutputTokens = registryMaxOutputTokens,
        registrySupportsReasoning = registrySupportsReasoning,
        registryInterleavedField = null,
        registryInputModalities = registryInputModalities,
        registryOutputModalities = registryOutputModalities,
        registryReasoningEffortValues = null,
        registryDeclaresNoEffortTiers = registryDeclaresNoEffortTiers,
    )

    // ── endpoint wins ──────────────────────────────────────────────────────

    @Test
    fun `the endpoint window beats the registry window`() {
        // The real case: the gateway said 960 000, the registry said 200 000
        // for the same model. Before the fix the registry won.
        val out = merge(endpointModel(contextWindow = 960_000), registryContextWindow = 200_000)
        assertEquals(960_000, out.contextWindow)
    }

    @Test
    fun `the endpoint output cap beats the registry output cap`() {
        // The endpoint DID report a cap (16 384) and the registry says 8 192 —
        // the endpoint's number wins.
        val model = LLMModel("m", "M", "Custom", maxOutputTokens = 16_384)
        val out = merge(model, registryMaxOutputTokens = 8_192)
        assertEquals(16_384, out.maxOutputTokens)
    }

    @Test
    fun `the endpoint reasoning flag beats the registry one`() {
        val model = LLMModel("m", "M", "Custom", supportsReasoning = false)
        val out = merge(model, registrySupportsReasoning = true)
        assertEquals(false, out.supportsReasoning)
    }

    @Test
    fun `the endpoint modality list beats the registry one`() {
        // The endpoint declared text-only input and said nothing about
        // output; the registry would have said "text, image" for both.
        val model = LLMModel("m", "M", "Custom", inputModalities = listOf("text"))
        val out = merge(
            model,
            registryInputModalities = listOf("text", "image"),
            registryOutputModalities = listOf("text", "image"),
        )
        assertEquals(listOf("text"), out.inputModalities)
        assertEquals(listOf("text", "image"), out.outputModalities)
    }

    // ── the registry still fills a gap ─────────────────────────────────────

    @Test
    fun `the registry fills the window when the endpoint reported none`() {
        val out = merge(endpointModel(contextWindow = null), registryContextWindow = 1_048_576)
        assertEquals(1_048_576, out.contextWindow)
    }

    @Test
    fun `the registry fills the output cap when the endpoint reported none`() {
        val out = merge(endpointModel(), registryMaxOutputTokens = 65_536)
        assertEquals(65_536, out.maxOutputTokens)
    }

    @Test
    fun `the registry fills the reasoning flag when the endpoint reported none`() {
        val out = merge(endpointModel(), registrySupportsReasoning = true)
        assertEquals(true, out.supportsReasoning)
    }

    @Test
    fun `the registry fills the modality lists when the endpoint reported none`() {
        val out = merge(
            endpointModel(),
            registryInputModalities = listOf("text", "image"),
            registryOutputModalities = listOf("text"),
        )
        assertEquals(listOf("text", "image"), out.inputModalities)
        assertEquals(listOf("text"), out.outputModalities)
    }

    @Test
    fun `nothing reported anywhere leaves the window null`() {
        // null means "no real value anywhere" — the caller then falls back to
        // the id-shape heuristic. It must NOT become 0.
        val out = merge(endpointModel(contextWindow = null), registryContextWindow = null)
        assertNull(out.contextWindow)
    }

    // ── declaresNoEffortTiers is affirmative, not fill-the-gap ─────────────

    @Test
    fun `a registry row that declares no effort tiers wins`() {
        val out = merge(endpointModel(), registryDeclaresNoEffortTiers = true)
        assertEquals(true, out.declaresNoEffortTiers)
    }

    @Test
    fun `a registry row that does not declare it leaves the base value alone`() {
        val model = LLMModel("m", "M", "Custom", declaresNoEffortTiers = true)
        val out = merge(model, registryDeclaresNoEffortTiers = false)
        assertEquals(true, out.declaresNoEffortTiers)
    }

    // ── registry lookup id ────────────────────────────────────────────────

    @Test
    fun `a vendor-prefixed id falls back to its bare part`() {
        // The local gateway's id shape.
        assertEquals("glm-5.3-flash", registryLookupId("codebuddy-cn/glm-5.3-flash"))
    }

    @Test
    fun `a plain id is returned unchanged`() {
        assertEquals("glm-5.3-flash", registryLookupId("glm-5.3-flash"))
    }

    @Test
    fun `only the first segment is removed`() {
        // models.dev ids can themselves contain a slash (moonshotai/kimi-k3),
        // so the remainder must be kept whole.
        assertEquals("kimi-k3", registryLookupId("moonshotai/kimi-k3"))
    }

    @Test
    fun `a leading slash leaves the id unchanged`() {
        assertEquals("/leading", registryLookupId("/leading"))
    }

    @Test
    fun `a trailing slash leaves the id unchanged`() {
        assertEquals("trailing/", registryLookupId("trailing/"))
    }

    @Test
    fun `an empty id is returned unchanged`() {
        assertEquals("", registryLookupId(""))
    }
}
