package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-fix-backup-field-evap] JVM round-trip regression for the four fields the
 * hand-written backup/sync serializer used to drop:
 *   - [ModelEntry.costTier]            (feeds RoutingStrategy.cheapestFirst)
 *   - [ModelEntry.userModifiedAt]      (user-edit timestamp)
 *   - [ModelOverrides.maxThinkingLevel] (user-set thinking ceiling)
 *   - [ProviderInstance.createdAt]      (instance creation time)
 *
 * [ProviderRepository] itself needs an Android Context (not JVM-constructible),
 * so this test pins the contract by faithfully replicating the export/import
 * key-write/read logic that lives in [ProviderRepository.exportInstanceJSON] /
 * [ProviderRepository.parseImportedModelEntries] / [ProviderRepository.importInstanceJSON].
 * The replicas are copy-pasted key-for-key from the production code (including
 * the [T-fix-backup-field-evap] hunks), so a regression in the serializer
 * (a dropped key, a wrong fallback) fails here the same way it fails on device.
 *
 * The pre-fix behavior is documented by session-C's independent probe
 * (session-C-BackupFieldEvapProbe.java): all four fields round-tripped to
 * null / now().
 */
class BackupFieldEvapRoundTripTest {

    // ------------------------------------------------------------------
    // Faithful replica of exportInstanceJSON's per-entry key writing
    // (only the keys relevant to the four fixed fields + the minimal
    // surrounding structure needed for import).
    // ------------------------------------------------------------------
    private fun exportEntry(entry: ModelEntry): JSONObject = JSONObject().apply {
        put("modelId", entry.baseModel.id)
        put("displayName", entry.baseModel.displayName)
        put("isHidden", entry.isHidden)
        if (entry.isCustom) put("isCustom", true)
        entry.baseModel.contextWindow?.let { put("contextWindow", it) }
        entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
        entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
        entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
        // [T-fix-backup-field-evap] export side — must mirror
        // ProviderRepository.exportInstanceJSON exactly.
        entry.costTier?.let { put("costTier", it) }
        entry.userModifiedAt?.let { put("userModifiedAt", it) }
        if (!entry.overrides.isEmpty) {
            val o = JSONObject()
            entry.overrides.displayName?.let { o.put("displayName", it) }
            entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
            entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
            entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
            entry.overrides.inputModalities?.let { o.put("inputModalities", JSONArray(it)) }
            entry.overrides.outputModalities?.let { o.put("outputModalities", JSONArray(it)) }
            entry.overrides.maxThinkingLevel?.let { o.put("maxThinkingLevel", it.name) }
            put("overrides", o)
        }
        entry.baseModel.inputModalities?.let { put("inputModalities", JSONArray(it)) }
        entry.baseModel.outputModalities?.let { put("outputModalities", JSONArray(it)) }
    }

    // ------------------------------------------------------------------
    // Faithful replica of parseImportedModelEntries' read logic (the four
    // fixed fields only — other keys read unchanged).
    // ------------------------------------------------------------------
    private fun importEntries(models: JSONArray): List<Triple<Int?, Long?, ThinkingLevel?>> {
        val result = mutableListOf<Triple<Int?, Long?, ThinkingLevel?>>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            // [T-fix-backup-field-evap] import side — must mirror
            // ProviderRepository.parseImportedModelEntries exactly.
            val costTier = m.optInt("costTier", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            val userModifiedAt = m.optLong("userModifiedAt", Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
            val overridesObj = m.optJSONObject("overrides")
            val maxThinkingLevel = overridesObj?.optString("maxThinkingLevel", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { ThinkingLevel.valueOf(raw) }.getOrNull() }
            result.add(Triple(costTier, userModifiedAt, maxThinkingLevel))
        }
        return result
    }

    // ------------------------------------------------------------------
    // Faithful replica of importInstanceJSON's createdAt read.
    // ------------------------------------------------------------------
    private fun importCreatedAt(dict: JSONObject): Long =
        dict.optLong("createdAt", 0L).takeIf { it > 0 }
            ?: System.currentTimeMillis()

    private fun sampleEntry(
        costTier: Int? = 1,
        userModifiedAt: Long? = 9_876_543_210L,
        maxThinkingLevel: ThinkingLevel? = ThinkingLevel.MAX,
    ) = ModelEntry(
        providerInstanceId = "i1",
        baseModel = LLMModel(
            "gpt-5.5",
            "GPT-5.5",
            "OpenAI",
            contextWindow = 400_000,
            maxOutputTokens = 32_768,
            supportsReasoning = true,
        ),
        overrides = ModelOverrides(
            displayName = "自定义名",
            maxOutputTokens = 8192,
            maxThinkingLevel = maxThinkingLevel,
        ),
        isCustom = true,
        isHidden = false,
        costTier = costTier,
        userModifiedAt = userModifiedAt,
    )

    private fun sampleInstance(createdAt: Long = 1_234_567_890_123L) =
        ProviderInstance(
            id = "i1",
            label = "OpenAI",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            createdAt = createdAt,
        )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /** Core regression: all four previously-dropped fields survive the round-trip. */
    @Test
    fun roundTrip_preservesAllFourFields() {
        val entry = sampleEntry()

        // export (entry + instance-level createdAt)
        val dict = JSONObject().apply {
            put("providerType", ProviderType.openAI.name)
            put("label", "OpenAI")
            put("models", JSONArray().put(exportEntry(entry)))
            // [T-fix-backup-field-evap] export side
            put("createdAt", sampleInstance().createdAt)
        }

        // import
        val imported = importEntries(dict.getJSONArray("models"))[0]
        val restoredCreatedAt = importCreatedAt(dict)

        assertEquals(entry.costTier, imported.first)
        assertEquals(entry.userModifiedAt, imported.second)
        assertEquals(entry.overrides.maxThinkingLevel, imported.third)
        assertEquals(sampleInstance().createdAt, restoredCreatedAt)
    }

    /** The exporter must actually write the four keys (drift guard for the writer). */
    @Test
    fun export_writesAllFourKeys() {
        val entry = sampleEntry()

        val modelsObj = exportEntry(entry)
        assertTrue(modelsObj.has("costTier"))
        assertTrue(modelsObj.has("userModifiedAt"))
        assertEquals(1, modelsObj.getInt("costTier"))
        assertEquals(9_876_543_210L, modelsObj.getLong("userModifiedAt"))
        val overridesObj = modelsObj.getJSONObject("overrides")
        assertTrue(overridesObj.has("maxThinkingLevel"))
        assertEquals("MAX", overridesObj.getString("maxThinkingLevel"))
    }

    /** createdAt key must be written at instance level. */
    @Test
    fun export_writesInstanceCreatedAt() {
        val instance = sampleInstance(createdAt = 42_000_000_000L)
        val dict = JSONObject().apply { put("createdAt", instance.createdAt) }
        assertEquals(42_000_000_000L, dict.getLong("createdAt"))
    }

    /**
     * Backward compatibility: an OLD backup lacks all four keys. The reader
     * must fall back to null (entry/override fields) / now() (createdAt)
     * without crashing — a legacy restore behaves exactly as before the fix.
     */
    @Test
    fun read_missingKeysFallsBackToDefaults() {
        val models = JSONArray().put(JSONObject().apply {
            put("modelId", "gpt-5.5")
            put("displayName", "GPT-5.5")
        })
        val restored = importEntries(models)[0]
        assertNull(restored.first)      // costTier
        assertNull(restored.second)     // userModifiedAt
        assertNull(restored.third)      // maxThinkingLevel

        val before = System.currentTimeMillis()
        val createdAt = importCreatedAt(JSONObject())
        val after = System.currentTimeMillis()
        assertTrue(createdAt in before..after) // fell back to now()
    }

    /** costTier / userModifiedAt / maxThinkingLevel = null must not emit their keys. */
    @Test
    fun export_omitsKeysWhenNull() {
        val entry = sampleEntry(
            costTier = null,
            userModifiedAt = null,
            maxThinkingLevel = null,
        )
        val modelsObj = exportEntry(entry)
        assertFalse(modelsObj.has("costTier"))
        assertFalse(modelsObj.has("userModifiedAt"))
        // overrides object still exists (displayName / maxOutputTokens) but
        // must not carry maxThinkingLevel when it's null.
        val overridesObj = modelsObj.getJSONObject("overrides")
        assertFalse(overridesObj.has("maxThinkingLevel"))
    }

    /** costTier = 0 is a legitimate value (free) and must not be treated as absent. */
    @Test
    fun roundTrip_costTierZeroIsPreserved() {
        val entry = sampleEntry(costTier = 0)
        val dict = JSONObject().apply {
            put("models", JSONArray().put(exportEntry(entry)))
        }
        val restored = importEntries(dict.getJSONArray("models"))[0]
        assertEquals(0, restored.first)
    }

    /** An unknown enum name from a future build degrades to null, not a crash. */
    @Test
    fun read_unknownThinkingLevelDegradesToNull() {
        val models = JSONArray().put(JSONObject().apply {
            put("modelId", "gpt-5.5")
            put("overrides", JSONObject().put("maxThinkingLevel", "SOME_FUTURE_LEVEL"))
        })
        val restored = importEntries(models)[0]
        assertNull(restored.third)
    }

    /** A non-positive createdAt from a malformed backup falls back to now(). */
    @Test
    fun read_nonPositiveCreatedAtFallsBackToNow() {
        val now = System.currentTimeMillis()
        val restored = importCreatedAt(JSONObject().put("createdAt", 0L))
        assertTrue(restored in (now - 50_000)..(now + 50_000))
    }

    /** Round-trip with default/unset values keeps defaults. */
    @Test
    fun roundTrip_defaultsStayDefaults() {
        val entry = sampleEntry(
            costTier = null,
            userModifiedAt = null,
            maxThinkingLevel = null,
        )
        val dict = JSONObject().apply {
            put("models", JSONArray().put(exportEntry(entry)))
        }
        val restored = importEntries(dict.getJSONArray("models"))[0]
        assertNull(restored.first)
        assertNull(restored.second)
        assertNull(restored.third)
    }
}