package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [model-catalog-split] Pure decision cores for the selection-only entry
 * table:
 *
 *  - [decideSelectionOnlyKeep] — which legacy rows survive the one-shot
 *    migration (`modelEntries` = user selections only).
 *  - [reconcileSelectedEntry] — refresh merges catalog metadata into a
 *    selected entry without touching identity/user-authored fields, and
 *    NEVER removes entries the catalog stopped listing.
 *
 * Expectation values below are LITERAL, never derived from the functions
 * under test (JVM 装置自指陷阱 — see evidence-discipline).
 */
class SelectionOnlyEntryRulesTest {

    private fun model(
        id: String,
        contextWindow: Int? = 128_000,
        supportsReasoning: Boolean? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "TestVendor",
        contextWindow = contextWindow,
        supportsReasoning = supportsReasoning,
    )

    private fun entry(
        instanceId: String = "inst-1",
        modelId: String,
        isCustom: Boolean = false,
        isHidden: Boolean = false,
        overrides: ModelOverrides = ModelOverrides(),
        uuid: String = "uuid-$modelId",
        costTier: Int? = null,
        userModifiedAt: Long? = null,
        baseModel: LLMModel? = null,
    ) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = baseModel ?: model(modelId),
        overrides = overrides,
        isCustom = isCustom,
        isHidden = isHidden,
        uuid = uuid,
        costTier = costTier,
        userModifiedAt = userModifiedAt,
    )

    // ── decideSelectionOnlyKeep ─────────────────────────────────────────

    @Test
    fun `legacy hidden non-custom catalog row is dropped`() {
        // The exact shape the old replaceEntries wrote for every remote row.
        val e = entry(modelId = "gpt-x", isCustom = false, isHidden = true)
        assertFalse(decideSelectionOnlyKeep(e, voiceTemplateModelIds = emptySet(), providerInstanceIdIsSystemVoice = false))
    }

    @Test
    fun `custom entries survive even when hidden`() {
        val e = entry(modelId = "my-model", isCustom = true, isHidden = true)
        assertTrue(decideSelectionOnlyKeep(e, voiceTemplateModelIds = emptySet(), providerInstanceIdIsSystemVoice = false))
    }

    @Test
    fun `visible entries survive`() {
        val e = entry(modelId = "picked", isCustom = false, isHidden = false)
        assertTrue(decideSelectionOnlyKeep(e, voiceTemplateModelIds = emptySet(), providerInstanceIdIsSystemVoice = false))
    }

    @Test
    fun `voice template seeds survive even when hidden`() {
        val e = entry(modelId = "mimo-tts-seed", isCustom = false, isHidden = true)
        assertTrue(
            decideSelectionOnlyKeep(
                e,
                voiceTemplateModelIds = setOf("mimo-tts-seed", "mimo-asr-seed"),
                providerInstanceIdIsSystemVoice = false,
            ),
        )
    }

    @Test
    fun `non-seed hidden rows on a voice instance are still dropped`() {
        val e = entry(modelId = "gpt-x", isCustom = false, isHidden = true)
        assertFalse(
            decideSelectionOnlyKeep(
                e,
                voiceTemplateModelIds = setOf("mimo-tts-seed"),
                providerInstanceIdIsSystemVoice = false,
            ),
        )
    }

    @Test
    fun `system voice sentinel entries survive defensively`() {
        val e = entry(instanceId = "system-sentinel", modelId = "system-asr-online", isHidden = true)
        assertTrue(
            decideSelectionOnlyKeep(e, voiceTemplateModelIds = emptySet(), providerInstanceIdIsSystemVoice = true),
        )
    }

    // ── reconcileSelectedEntry ──────────────────────────────────────────

    @Test
    fun `entry absent from catalog is left untouched`() {
        val e = entry(modelId = "picked-rare", costTier = 1, userModifiedAt = 42L)
        val result = reconcileSelectedEntry(e, freshCatalogRow = null)
        assertFalse(result.changed)
        assertEquals(e, result.merged)
    }

    @Test
    fun `identical catalog row does not count as changed`() {
        val e = entry(modelId = "m1")
        val result = reconcileSelectedEntry(e, freshCatalogRow = model("m1"))
        assertFalse(result.changed)
    }

    @Test
    fun `fresh catalog metadata wins on baseModel`() {
        val e = entry(modelId = "m1")
        val fresh = model("m1", contextWindow = 1_000_000, supportsReasoning = true)
        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)
        assertTrue(result.changed)
        assertEquals(1_000_000, result.merged.baseModel.contextWindow)
        assertEquals(true, result.merged.baseModel.supportsReasoning)
    }

    @Test
    fun `user-authored state survives metadata refresh`() {
        val overrides = ModelOverrides(displayName = "My Name", contextWindow = 777)
        val e = entry(
            modelId = "m1",
            overrides = overrides,
            uuid = "user-uuid",
            costTier = 2,
            userModifiedAt = 1234L,
        )
        val fresh = model("m1", contextWindow = 1_000_000)
        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)
        assertTrue(result.changed)
        // Identity + user metadata must survive the merge:
        assertEquals("user-uuid", result.merged.uuid)
        assertEquals(2, result.merged.costTier)
        assertEquals(1234L, result.merged.userModifiedAt)
        assertEquals("My Name", result.merged.overrides.displayName)
        assertEquals(777, result.merged.overrides.contextWindow)
        // baseModel carries the fresh catalog metadata (the override is
        // applied later through ModelEntry.model):
        assertEquals(1_000_000, result.merged.baseModel.contextWindow)
        // Entry-level effective model still folds the override:
        assertEquals(777, result.merged.model.contextWindow)
    }

    @Test
    fun `isCustom flag survives metadata refresh`() {
        val e = entry(modelId = "m1", isCustom = true)
        val fresh = model("m1", contextWindow = 999)
        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)
        assertTrue(result.changed)
        assertTrue(result.merged.isCustom)
        assertEquals(999, result.merged.baseModel.contextWindow)
    }
}
