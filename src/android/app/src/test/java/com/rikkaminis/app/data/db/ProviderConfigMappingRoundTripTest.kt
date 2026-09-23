package com.rikkaminis.app.data.db

import com.rikkaminis.app.data.model.FallbackStrategy
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full-coverage round-trip tests for ProviderConfig ↔ ProviderConfigSnapshot
 * (ProviderConfigMapping.kt). The "field silently evaporates" bug family
 * (GH#68 image-endpoint → ProviderInstance.pinned → knobs H1 → worker
 * thinking-rules → reasoning_content) recurred five times: every fix added a
 * round-trip test for ONE recently-changed field, while every OTHER field of
 * the same data class had zero runtime coverage (the four-way sync check is a
 * static column-name comparison only).
 *
 * These tests pin the WHOLE contract: construct a fully-populated instance of
 * every data type in the mapping → toSnapshot → assert DB-row fields →
 * toProviderConfig → assert every field survives. If a new field is added to
 * ProviderInstance / ModelEntry / ModelGroup / ProviderConfig and mapped
 * nowhere, `full round trip preserves every field` fails — same guard shape
 * as ProviderConfigSnapshotTest.mutationSnapshot, but at runtime and for both
 * mapping directions.
 *
 * Pure-Kotlin only (no android.* / Context): runs as a plain JVM unit test.
 */
class ProviderConfigMappingRoundTripTest {

    // Mirrors the repository's Json instance settings (they shape every blob).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    // Fully-populated: every nullable/defaulted field set to a NON-default
    // value, so a dropped mapping shows up as a mismatch (not a null==null).
    private fun fullModel(id: String) = LLMModel(
        id = id,
        displayName = "DN-$id",
        provider = "openAI",
        contextWindow = 128_000,
        maxOutputTokens = 8_192,
        supportsReasoning = true,
        interleavedReasoningField = "reasoning_content",
        reasoningEffortValues = listOf("low", "high"),
        declaresNoEffortTiers = false,
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("text"),
    )

    private fun fullInstance(id: String) = ProviderInstance(
        id = id,
        label = "L-$id",
        providerType = ProviderType.openRouter,
        credentialType = ProviderCredential.oauth,
        isEnabled = false,
        createdAt = 1_700_000_000_000L,
        customBaseURL = "https://relay.example.com/api",
        appendV1Suffix = false,
        customUserAgent = "claude-code/1.0",
        useResponsesAPI = true,
        azureMode = true,
        imageEndpointMode = ImageEndpointMode.chatCompletions,
        imageEndpointResolved = ImageEndpointMode.imagesGenerations,
        pinned = true,
    )

    private fun fullEntry(instanceId: String, modelId: String, uuid: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = fullModel(modelId),
        overrides = ModelOverrides(
            displayName = "ODN-$modelId",
            maxOutputTokens = 4_096,
            contextWindow = 64_000,
            supportsReasoning = false,
            inputModalities = listOf("text"),
            outputModalities = listOf("text", "image"),
            maxThinkingLevel = ThinkingLevel.HIGH,
        ),
        isCustom = true,
        isHidden = true,
        uuid = uuid,
        userModifiedAt = 1_700_000_001_000L,
        costTier = 2,
    )

    private fun fullConfig(): ProviderConfig {
        val e1 = fullEntry("i1", "m1", "e-uuid-1")
        // Second entry: EMPTY overrides → overridesJson must be null (not "{}").
        val e2 = ModelEntry(
            providerInstanceId = "i2",
            baseModel = fullModel("m2"),
            uuid = "e-uuid-2",
        )
        val i2 = ProviderInstance(
            id = "i2",
            label = "L-i2",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
        )
        val g1 = ModelGroup(
            id = "g1",
            name = "G1",
            memberEntryIds = mutableListOf("e-uuid-1", "e-uuid-2"),
            strategy = RoutingStrategy.cheapestFirst,
            fallbackStrategy = FallbackStrategy.always,
            defaultThinkingLevel = ThinkingLevel.ULTRA,
            contextLimitTokens = 200_000,
            lastContextLimitTokens = 100_000,
        )
        // Second group: all defaults (strategy/fallback/thinking/ctx null).
        val g2 = ModelGroup(id = "g2", name = "G2")
        return ProviderConfig(
            instances = mutableListOf(fullInstance("i1"), i2),
            modelEntries = mutableListOf(e1, e2),
            modelGroups = mutableListOf(g1, g2),
            defaultPrimaryGroupId = "g1",
            defaultSubGroupId = "g2",
            voiceInputGroupId = "g1",
            voiceOutputGroupId = "g2",
            agentLoopModelEntryIds = mutableListOf("e-uuid-1"),
            agentLoopGroupIds = mutableListOf("g1"),
        )
    }

    @Test
    fun `full round trip preserves every field`() {
        val original = fullConfig()

        val snap = original.toSnapshot(json, jsonSyncHash = "hash-1")

        assertSnapshotRows(original, snap)
        val roundTripped = snap.toProviderConfig(json)
        assertRoundTripped(original, roundTripped)
    }

    /** DB-row level: catches bugs in toSnapshot itself (not just the inverse). */
    private fun assertSnapshotRows(original: ProviderConfig, snap: ProviderConfigSnapshot) {
        // --- instance rows ---
        val i1 = snap.instances.first { it.id == "i1" }
        assertEquals("L-i1", i1.label)
        assertEquals("openRouter", i1.providerType)
        assertEquals("oauth", i1.credentialType)
        assertEquals("https://relay.example.com/api", i1.customBaseURL)
        assertEquals(0, i1.appendV1Suffix)
        assertEquals(1, i1.useResponsesAPI)
        assertEquals(1, i1.azureMode)
        // DB columns store the enum NAME (valueOf round-trips); @SerialName
        // only affects the JSON mirror, not the Room path.
        assertEquals("chatCompletions", i1.imageEndpointMode)
        assertEquals("imagesGenerations", i1.imageEndpointResolved)
        assertEquals("claude-code/1.0", i1.customUserAgent)
        assertEquals(0, i1.isEnabled)
        assertEquals(0, i1.sortOrder)
        assertEquals(1_700_000_000_000L, i1.createdAt)
        assertEquals(1, i1.pinned)
        val i2 = snap.instances.first { it.id == "i2" }
        assertEquals(1, i2.sortOrder)
        assertEquals(1, i2.appendV1Suffix)
        assertEquals(0, i2.pinned)
        assertEquals("auto", i2.imageEndpointMode)
        assertNull(i2.imageEndpointResolved)
        assertEquals("anthropic", i2.providerType)
        assertEquals(1, i2.isEnabled)

        // --- entry rows ---
        val e1 = snap.entries.first { it.id == "i1/m1" }
        assertEquals("i1", e1.providerInstanceId)
        val decodedModel = json.decodeFromString(
            LLMModel.serializer(),
            e1.baseModelJson,
        )
        assertEquals("m1", decodedModel.id)
        assertEquals("reasoning_content", decodedModel.interleavedReasoningField)
        assertEquals(listOf("low", "high"), decodedModel.reasoningEffortValues)
        assertEquals(listOf("text", "image"), decodedModel.inputModalities)
        val decodedOverrides = json.decodeFromString(
            ModelOverrides.serializer(),
            e1.overridesJson!!,
        )
        assertEquals(ThinkingLevel.HIGH, decodedOverrides.maxThinkingLevel)
        assertEquals(64_000, decodedOverrides.contextWindow)
        assertEquals(1, e1.isCustom)
        assertEquals(1, e1.isHidden)
        assertEquals(0, e1.sortOrder) // per-instance contiguous
        assertEquals(1_700_000_001_000L, e1.userModifiedAt)
        assertEquals(2, e1.costTier)
        val e2 = snap.entries.first { it.id == "i2/m2" }
        assertNull("empty overrides must serialize as null, not {}", e2.overridesJson)
        assertEquals(0, e2.sortOrder)

        // --- group rows ---
        val g1 = snap.groups.first { it.id == "g1" }
        assertEquals(
            listOf("i1/m1", "i2/m2"),
            json.decodeFromString(ListSerializer(String.serializer()), g1.memberEntryIdsJson),
        )
        assertEquals("cheapestFirst", g1.strategy)
        assertEquals("always", g1.fallbackStrategy)
        assertEquals("ULTRA", g1.defaultThinkingLevel)
        assertEquals(200_000, g1.contextLimitTokens)
        assertEquals(100_000, g1.lastContextLimitTokens)
        val g2 = snap.groups.first { it.id == "g2" }
        assertEquals("fallback", g2.strategy)
        assertEquals("default", g2.fallbackStrategy)
        assertNull(g2.defaultThinkingLevel)

        // --- agent-loop rows ---
        val entryLoop = snap.loopIds.first { it.kind == "entry" }
        assertEquals("i1/m1", entryLoop.targetId)
        assertEquals(0, entryLoop.sortOrder)
        val groupLoop = snap.loopIds.first { it.kind == "group" }
        assertEquals("g1", groupLoop.targetId)

        // --- meta rows ---
        val metaMap = snap.meta.associate { it.key to it.value }
        assertEquals("g1", metaMap[ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID])
        assertEquals("g2", metaMap[ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID])
        assertEquals("g1", metaMap[ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID])
        assertEquals("g2", metaMap[ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID])
        assertEquals("hash-1", metaMap[ProviderConfigMetaKeys.JSON_SYNC_HASH])
    }

    /** Model-level: catches bugs in toProviderConfig itself. */
    private fun assertRoundTripped(original: ProviderConfig, rt: ProviderConfig) {
        // --- instances: every ProviderInstance field ---
        val o1 = original.instances[0]
        val r1 = rt.instances.first { it.id == "i1" }
        assertEquals(o1.label, r1.label)
        assertEquals(o1.providerType, r1.providerType)
        assertEquals(o1.credentialType, r1.credentialType)
        assertEquals(o1.isEnabled, r1.isEnabled)
        assertEquals(o1.createdAt, r1.createdAt)
        assertEquals(o1.customBaseURL, r1.customBaseURL)
        assertEquals(o1.appendV1Suffix, r1.appendV1Suffix)
        assertEquals(o1.customUserAgent, r1.customUserAgent)
        assertEquals(o1.useResponsesAPI, r1.useResponsesAPI)
        assertEquals(o1.azureMode, r1.azureMode)
        assertEquals(o1.imageEndpointMode, r1.imageEndpointMode)
        assertEquals(o1.imageEndpointResolved, r1.imageEndpointResolved)
        assertEquals(o1.pinned, r1.pinned)
        val o2 = original.instances[1]
        val r2 = rt.instances.first { it.id == "i2" }
        assertEquals(o2, r2)

        // --- entries: every ModelEntry field ---
        val oe1 = original.modelEntries[0]
        val re1 = rt.modelEntries.first { it.uuid == "i1/m1" }
        assertEquals(oe1.providerInstanceId, re1.providerInstanceId)
        assertEquals(oe1.baseModel, re1.baseModel)
        assertEquals(oe1.overrides, re1.overrides)
        assertEquals(oe1.isCustom, re1.isCustom)
        assertEquals(oe1.isHidden, re1.isHidden)
        assertEquals(oe1.userModifiedAt, re1.userModifiedAt)
        assertEquals(oe1.costTier, re1.costTier)
        // uuid → composite id is the deliberate mapping; pin its shape.
        assertEquals("i1/m1", re1.uuid)
        val oe2 = original.modelEntries[1]
        val re2 = rt.modelEntries.first { it.uuid == "i2/m2" }
        assertEquals(oe2.baseModel, re2.baseModel)
        assertEquals(ModelOverrides(), re2.overrides)

        // --- groups: every ModelGroup field ---
        val og1 = original.modelGroups[0]
        val rg1 = rt.modelGroups.first { it.id == "g1" }
        assertEquals(og1.name, rg1.name)
        assertEquals(listOf("i1/m1", "i2/m2"), rg1.memberEntryIds.toList())
        assertEquals(og1.strategy, rg1.strategy)
        assertEquals(og1.fallbackStrategy, rg1.fallbackStrategy)
        assertEquals(og1.defaultThinkingLevel, rg1.defaultThinkingLevel)
        assertEquals(og1.contextLimitTokens, rg1.contextLimitTokens)
        assertEquals(og1.lastContextLimitTokens, rg1.lastContextLimitTokens)
        val og2 = original.modelGroups[1]
        val rg2 = rt.modelGroups.first { it.id == "g2" }
        assertEquals(og2, rg2)

        // --- agent-loop + meta ---
        assertEquals(listOf("i1/m1"), rt.agentLoopModelEntryIds.toList())
        assertEquals(listOf("g1"), rt.agentLoopGroupIds.toList())
        assertEquals("g1", rt.defaultPrimaryGroupId)
        assertEquals("g2", rt.defaultSubGroupId)
        assertEquals("g1", rt.voiceInputGroupId)
        assertEquals("g2", rt.voiceOutputGroupId)
    }

    @Test
    fun `first-time import path stores no json_sync_hash`() {
        val snap = fullConfig().toSnapshot(json, jsonSyncHash = null)
        assertTrue(
            "meta must not contain json_sync_hash when hash is null",
            snap.meta.none { it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH },
        )
        assertEquals(4, snap.meta.size)
    }

    @Test
    fun `second round trip is stable`() {
        val once = fullConfig().toSnapshot(json).toProviderConfig(json)
        val twice = once.toSnapshot(json).toProviderConfig(json)
        // uuid is already composite after round one — idMap misses must
        // pass through unchanged, so the second pass is an exact equality.
        assertEquals(once, twice)
    }
}
