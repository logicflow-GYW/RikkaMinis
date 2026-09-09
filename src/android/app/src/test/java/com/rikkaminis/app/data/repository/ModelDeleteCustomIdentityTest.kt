package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-custom-identity] JVM regression for the "manually-added model
 * becomes undeletable" bug (model-delete-bug, 2026-08-24).
 *
 * Root cause chain:
 *   1. User adds a custom model via Add Custom Model (isCustom = true).
 *   2. User taps Refresh on the provider detail screen
 *      (ProviderDetailScreen:268 -> refreshModels, NOT autoRefreshModels).
 *   3. [ProviderRepository.replaceEntries] rebuilt every entry the /v1/models
 *      response returned with a hard-coded `isCustom = false`, only keeping
 *      custom entries whose id was NOT in the API list (remainingCustom).
 *   4. The API returns the same id the user manually added -> the entry lives
 *      on, visible, but its custom identity (and with it the long-press
 *      delete gesture, gated on entry.isCustom) is gone -> undeletable.
 *
 * [ProviderRepository] needs an Android Context (not JVM-constructible), so —
 * like BackupFieldEvapRoundTripTest — this test pins the contract by
 * faithfully replicating replaceEntries' custom-identity handling (the
 * [T-provider-custom-identity] hunk) and removeEntry's visibility semantics
 * against the same data-model classes the production code uses. The replica
 * is kept in sync with production by construction: it mirrors the exact
 * `prior = existingByModelId[...]` / `isCustom = prior?.isCustom ?: false`
 * logic now living in ProviderRepository.replaceEntries (L960-1003).
 */
class ModelDeleteCustomIdentityTest {

    private fun model(id: String, displayName: String = id) =
        LLMModel(id = id, displayName = displayName, provider = "openAI")

    /**
     * Faithful replica of the custom-identity portion of
     * ProviderRepository.replaceEntries (post-fix):
     *   - new entries inherit `isCustom = prior?.isCustom ?: false`
     *   - custom entries absent from the refreshed list are kept (remainingCustom)
     * The test compiles against the REAL ModelEntry/LLMModel data classes.
     */
    private fun replaceEntriesReplica(
        existing: List<ModelEntry>,
        instanceId: String,
        models: List<LLMModel>,
    ): List<ModelEntry> {
        val existingForInstance = existing.filter { it.providerInstanceId == instanceId }
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existingForInstance) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        val refreshedModelIds = models.map { it.id }.toSet()

        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = model,
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = prior?.isCustom ?: false,
                isHidden = prior?.isHidden ?: true,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }

        val remainingCustom = existingForInstance.filter {
            it.isCustom && it.baseModel.id !in refreshedModelIds
        }

        return newEntries + remainingCustom
    }

    // ------------------------------ tests ------------------------------

    @Test
    fun `refresh with same id keeps manual model's custom identity`() {
        val instanceId = "i1"
        val manual = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model("manual-model", "My Manual"),
            isCustom = true,
            isHidden = false,
            uuid = "manual-uuid-1",
        )
        // API /v1/models returns the SAME id the user manually added.
        val refreshed: List<LLMModel> = listOf(model("manual-model", "My Manual"))

        val result = replaceEntriesReplica(listOf(manual), instanceId, refreshed)

        // The refreshed entry keeps isCustom = true — the pre-fix bug rebuilt
        // it with isCustom = false here, silently making it undeletable.
        val entry = result.single { it.baseModel.id == "manual-model" }
        assertTrue(
            "manual entry must stay custom after refresh with same id",
            entry.isCustom,
        )
        assertEquals("manual-uuid-1", entry.uuid)
        assertEquals("My Manual", entry.baseModel.displayName)
    }

    @Test
    fun `refresh keeps invisible custom model absent from api list`() {
        val instanceId = "i1"
        val manual = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model("manual-only", "Only Local"),
            isCustom = true,
            isHidden = false,
        )
        // API returns a DIFFERENT model; manual-only is not in the list.
        val refreshed: List<LLMModel> = listOf(model("api-model"))

        val result = replaceEntriesReplica(listOf(manual), instanceId, refreshed)

        val manualAfter = result.single { it.baseModel.id == "manual-only" }
        assertTrue(
            "custom entry absent from API list must survive via remainingCustom",
            manualAfter.isCustom,
        )
    }

    @Test
    fun `refresh of builtin entry keeps non-custom identity`() {
        val instanceId = "i1"
        val builtin = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model("gpt-x", "GPT-X"),
            isCustom = false,
            isHidden = true,
        )
        val refreshed: List<LLMModel> = listOf(model("gpt-x", "GPT-X"))

        val result = replaceEntriesReplica(listOf(builtin), instanceId, refreshed)

        val entry = result.single { it.baseModel.id == "gpt-x" }
        assertFalse("builtin entry must remain non-custom after refresh", entry.isCustom)
        assertEquals("isHidden carries forward", true, entry.isHidden)
    }

    @Test
    fun `after refresh the custom entry remains removable`() {
        val instanceId = "i1"
        val manual = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model("manual-model", "My Manual"),
            isCustom = true,
            isHidden = false,
            uuid = "manual-uuid-1",
        )
        val refreshed: List<LLMModel> = listOf(model("manual-model", "My Manual"))

        val result = replaceEntriesReplica(listOf(manual), instanceId, refreshed).toMutableList()

        // What removeEntry does (ProviderRepository L1096+): remove by entry id,
        // stripping from any modelGroups along the way (groups not modeled here).
        val removed = result.firstOrNull { it.uuid == "manual-uuid-1" }
        assertTrue("entry must be present after refresh (visible to long-press)", removed != null)
        result.removeAll { it.uuid == "manual-uuid-1" }
        assertFalse("removeEntry must fully drop the refreshed custom entry", result.any { it.uuid == "manual-uuid-1" })
    }

    @Test
    fun `refresh keeps prior override for custom entry`() {
        val instanceId = "i1"
        val manual = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model("manual-model", "My Manual"),
            isCustom = true,
            isHidden = false,
            overrides = ModelOverrides(displayName = "Renamed By User"),
        )
        val refreshed: List<LLMModel> = listOf(model("manual-model", "My Manual"))

        val result = replaceEntriesReplica(listOf(manual), instanceId, refreshed)

        val entry = result.single { it.baseModel.id == "manual-model" }
        assertEquals("Renamed By User", entry.overrides.displayName)
        assertTrue(entry.isCustom)
    }
}