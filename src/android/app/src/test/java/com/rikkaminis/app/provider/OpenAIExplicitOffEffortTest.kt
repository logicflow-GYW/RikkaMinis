package com.rikkaminis.app.provider

import com.rikkaminis.app.provider.openai.explicitOffEffortFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GH#377] The OFF-tier wire value is a pure function of (base, azure, model id,
 * declared tiers), so its boundary is JVM-testable without a MockWebServer —
 * same shape as [OpenAIPrefillSupportTest].
 *
 * The regression this file exists for: the predicate used to read ONLY the base
 * URL. A model whose catalog entry declares `[low..max]` (no `none`) still got
 * `effort:"none"` on an official-looking base and the backend rejected the whole
 * request with a 400. The relay case below is the CONTROL ARM — same model, same
 * declared set, different base — and it is what proves the decision was reading
 * the base rather than the model.
 */
class OpenAIExplicitOffEffortTest {

    private val official = "https://api.openai.com/v1"
    private val relay = "https://relay.example.com/v1"
    private val ark = "https://ark.cn-beijing.volces.com/api/v3"

    private fun off(
        basePath: String,
        modelId: String = "gpt-5.1",
        declared: List<String>? = null,
        isAzure: Boolean = false,
    ) = explicitOffEffortFor(basePath, isAzure, modelId, declared)

    // ---------------------------------------------------------------- allowlist

    @Test
    fun `official base with an undeclared model sends none`() {
        // `declared == null` = "the catalog never heard of this model", which must
        // stay permissive or the explicit-off feature silently disappears for
        // every uncovered model (i.e. reverts the fix).
        assertEquals("none", off(official))
    }

    @Test
    fun `official base with a model that declares none sends none`() {
        assertEquals("none", off(official, declared = listOf("none", "low", "medium", "high")))
    }

    @Test
    fun `volcano ark sends minimal as its off tier`() {
        assertEquals("minimal", off(ark, modelId = "doubao-pro"))
    }

    @Test
    fun `ark base with a seed family model sends minimal`() {
        assertEquals("minimal", off(relay, modelId = "seed-1.6"))
    }

    @Test
    fun `azure omits regardless of host`() {
        assertNull(off(official, isAzure = true))
        assertNull(off(ark, modelId = "doubao-pro", isAzure = true))
    }

    @Test
    fun `unlisted relay omits the off tier`() {
        assertNull(off(relay, modelId = "some-relay-model"))
    }

    // ------------------------------------------------------- declared-set veto

    /** The #377 regression itself: official base + a declared set without `none`. */
    @Test
    fun `official base omits none when the model does not declare it`() {
        assertNull(
            "a model declaring [low..max] must not be handed effort=none (400)",
            off(official, modelId = "gpt-6-astra", declared = listOf("low", "medium", "high", "xhigh", "max")),
        )
    }

    /** The control arm: identical model + declared set, only the base differs. */
    @Test
    fun `the same model on a relay also omits - the base alone never decided`() {
        val declared = listOf("low", "medium", "high", "xhigh", "max")
        assertNull(off(relay, modelId = "gpt-6-astra", declared = declared))
        // ...and the official base is where the old code diverged from this one.
        assertNull(off(official, modelId = "gpt-6-astra", declared = declared))
    }

    @Test
    fun `ark omits minimal when the model does not declare it`() {
        assertNull(off(ark, modelId = "doubao-pro", declared = listOf("low", "medium", "high")))
    }

    @Test
    fun `ark still sends minimal when the model does declare it`() {
        assertEquals("minimal", off(ark, modelId = "doubao-pro", declared = listOf("minimal", "low", "high")))
    }

    @Test
    fun `an empty declared list is a veto too`() {
        // Empty is a positive statement ("the catalog lists no tiers"), unlike
        // null. `declaresNoEffortTiers` carries the same shape elsewhere.
        assertNull(off(official, declared = emptyList()))
    }
}
