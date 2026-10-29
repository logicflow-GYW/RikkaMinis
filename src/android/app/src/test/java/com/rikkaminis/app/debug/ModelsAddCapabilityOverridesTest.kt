package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-E F-04] Behaviour of the custom-model add face: the explicit capability
 * values a caller passes to `provider.models.add` must survive enrichment.
 *
 * Real data classes, real `ModelEntry.model` (`baseModel.withOverrides(...)`),
 * no repository: [addedModelEntry] is the production constructor `modelsAdd`
 * calls, and `ModelEntry.model` is the production read path every consumer
 * goes through.
 *
 * The defect this pins: those three values used to be written ONLY onto
 * `baseModel`. `ModelsDevApi.applyDevData` prefers the registry
 * (`devModel.contextWindow ?: model.contextWindow`, same for maxOutputTokens /
 * supportsReasoning), so for any id models.dev knows, a hand-typed value was
 * replaced by the catalog default on the very next read while the add answered
 * ok:true — the same class of loss `ModelsCollection.add` already fixed at
 * [r3-b08 F-04].
 *
 * LITERAL expectations only.
 */
class ModelsAddCapabilityOverridesTest {

    private fun model(
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        supportsReasoning: Boolean? = null,
    ) = LLMModel(
        id = "acme-reasoner",
        displayName = "Acme Reasoner",
        provider = "custom-endpoint",
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsReasoning = supportsReasoning,
    )

    @Test
    fun explicitCapabilitiesLandInOverrides() {
        val entry = addedModelEntry(
            instanceId = "inst-1",
            model = model(),
            contextWindow = 16384,
            maxOutputTokens = 4096,
            supportsReasoning = true,
        )
        assertEquals("inst-1", entry.providerInstanceId)
        assertEquals(16384, entry.overrides.contextWindow)
        assertEquals(4096, entry.overrides.maxOutputTokens)
        assertEquals(true, entry.overrides.supportsReasoning)
        assertTrue("provider.models.add still mints custom entries", entry.isCustom)
    }

    @Test
    fun absentCapabilitiesStayNullSoNothingIsPinned() {
        val entry = addedModelEntry(
            instanceId = "inst-1",
            model = model(),
            contextWindow = null,
            maxOutputTokens = null,
            supportsReasoning = null,
        )
        assertNull(entry.overrides.contextWindow)
        assertNull(entry.overrides.maxOutputTokens)
        assertNull(entry.overrides.supportsReasoning)
    }

    @Test
    fun overrideWinsOverACatalogValueAlreadyOnTheBaseModel() {
        // Simulates the state AFTER enrichment copied the registry values onto
        // the base model: the explicit value must still win.
        val enriched = model(contextWindow = 200_000, maxOutputTokens = 8_192, supportsReasoning = true)
        val entry = addedModelEntry(
            instanceId = "inst-1",
            model = enriched,
            contextWindow = 16_384,
            maxOutputTokens = 4_096,
            supportsReasoning = false,
        )
        assertEquals(16_384, entry.model.contextWindow)
        assertEquals(4_096, entry.model.maxOutputTokens)
        assertEquals(false, entry.model.supportsReasoning)
    }

    @Test
    fun withoutOverridesTheCatalogValueIsWhatCallersSee() {
        // Control arm: an entry built the OLD way (no overrides) reports the
        // catalog value — that is precisely the loss this fix prevents, and it
        // keeps the test honest about what `ModelEntry.model` does.
        val entry = ModelEntry(
            providerInstanceId = "inst-1",
            baseModel = model(contextWindow = 200_000, supportsReasoning = true),
            isCustom = true,
        )
        assertEquals(200_000, entry.model.contextWindow)
        assertEquals(true, entry.model.supportsReasoning)
        assertNull("nothing is pinned without overrides", entry.overrides.supportsReasoning)
    }

    @Test
    fun modelsAddRoutesThroughTheSharedConstructor() {
        // Shape half: the RPC face must not re-inline the construction, or this
        // whole contract can drift away from `ModelsCollection.add` again.
        val relative = "src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        var text: String? = null
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) { text = f.readText(); break }
            dir = dir.parentFile
        }
        val code = text ?: error("$relative not found from user.dir=${System.getProperty("user.dir")}")
        assertTrue(
            "modelsAdd must call addedModelEntry(...) instead of building the entry inline",
            code.contains("val entry = addedModelEntry("),
        )
        assertTrue(
            "exactly one ModelOverrides(...) construction inside modelsAdd's constructor",
            code.contains("overrides = ModelOverrides("),
        )
    }
}
