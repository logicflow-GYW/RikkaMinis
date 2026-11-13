package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.normalizeModalities
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

    // ── [field-identity P2-E] a sparse catalog row must not DEGRADE ──────
    //
    // The pre-fix `entry.copy(baseModel = fresh)` treated every catalog row as
    // complete. A relay answering `{id}` only wiped the entry's known
    // capabilities. These pin the "silence is not an assertion" rule.

    @Test
    fun `catalog row that reports nothing does not erase known capabilities`() {
        // The entry already knows: 200K window, reasoning on, sees images.
        val existing = LLMModel(
            id = "m1",
            displayName = "Pretty Name",
            provider = "TestVendor",
            contextWindow = 200_000,
            maxOutputTokens = 8_000,
            supportsReasoning = true,
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        )
        val e = entry(modelId = "m1", baseModel = existing)
        // The relay answers `{id}` and nothing else.
        val sparse = LLMModel(id = "m1", displayName = "m1", provider = "TestVendor")

        val result = reconcileSelectedEntry(e, freshCatalogRow = sparse)

        assertFalse(result.changed)
        assertEquals(200_000, result.merged.baseModel.contextWindow)
        assertEquals(8_000, result.merged.baseModel.maxOutputTokens)
        assertEquals(true, result.merged.baseModel.supportsReasoning)
        assertEquals(listOf("text", "image"), result.merged.baseModel.inputModalities)
        assertEquals(listOf("text"), result.merged.baseModel.outputModalities)
        assertEquals("Pretty Name", result.merged.baseModel.displayName)
    }

    @Test
    fun `a single dropped field degrades only that field`() {
        val existing = LLMModel(
            id = "m1",
            displayName = "m1",
            provider = "TestVendor",
            contextWindow = 200_000,
            supportsReasoning = true,
            inputModalities = listOf("text", "image"),
        )
        val e = entry(modelId = "m1", baseModel = existing)
        // Fresh row moved the window up but forgot the modality list.
        val fresh = LLMModel(
            id = "m1",
            displayName = "m1",
            provider = "TestVendor",
            contextWindow = 1_000_000,
            supportsReasoning = true,
        )

        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)

        assertTrue(result.changed)
        assertEquals(1_000_000, result.merged.baseModel.contextWindow)
        // The dropped field keeps the value the entry already had:
        assertEquals(listOf("text", "image"), result.merged.baseModel.inputModalities)
    }

    @Test
    fun `an empty modality list is silence, not an assertion`() {
        // [INCR-1113 F-01] `emptyList()` means "no opinion", exactly like
        // `null` — see `mergeCatalogRow`'s KDoc for the two facts that force
        // it (normalizeModalities folds empty→null; the cross-process hop
        // decodes a missing key into emptyList). An earlier revision of this
        // test asserted the opposite, i.e. it pinned a contract the rest of
        // the app contradicts.
        val existing = LLMModel(
            id = "m1",
            displayName = "m1",
            provider = "TestVendor",
            inputModalities = listOf("text", "image"),
        )
        val e = entry(modelId = "m1", baseModel = existing)
        val fresh = LLMModel(
            id = "m1",
            displayName = "m1",
            provider = "TestVendor",
            inputModalities = emptyList(),
        )

        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)

        // The entry keeps what it had — an empty list is not a downgrade order.
        assertFalse(result.changed)
        assertEquals(listOf("text", "image"), result.merged.baseModel.inputModalities)

        // ...and the cross-process reading is pinned mechanically: a missing
        // key decodes to `emptyList()`, which normalizes to `null` = silence.
        assertEquals(null, emptyList<String>().normalizeModalities())
    }

    @Test
    fun `a blank display name does not overwrite a real one`() {
        val existing = LLMModel(
            id = "m1",
            displayName = "Pretty Name",
            provider = "TestVendor",
        )
        val e = entry(modelId = "m1", baseModel = existing)
        val fresh = LLMModel(id = "m1", displayName = "", provider = "TestVendor")

        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)

        assertFalse(result.changed)
        assertEquals("Pretty Name", result.merged.baseModel.displayName)
    }

    @Test
    fun `declaresNoEffortTiers survives a sparse refresh`() {
        // Nullable Boolean: null means "unknown", not "no".
        val existing = LLMModel(
            id = "m1",
            displayName = "m1",
            provider = "TestVendor",
            declaresNoEffortTiers = true,
        )
        val e = entry(modelId = "m1", baseModel = existing)
        val fresh = LLMModel(id = "m1", displayName = "m1", provider = "TestVendor")

        val result = reconcileSelectedEntry(e, freshCatalogRow = fresh)

        assertEquals(true, result.merged.baseModel.declaresNoEffortTiers)
    }
}
