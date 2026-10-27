package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.provider.openai.openAIKeyAuthHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [deep-audit-1025 FIX-I] JVM tests for the provider RPC symmetry fixes:
 *
 *  1. **Predicate semantics** — [ProviderType.supportsCustomBaseURL] is the
 *     single source for "does this provider type honor a custom base URL":
 *     everything except openRouter ([da1023-C]: openRouter's request builder
 *     pins https://openrouter.ai/api/v1). Expected values are written as
 *     literals over the full 6-value enum so a semantics mutation cannot
 *     masquerade as PASS by re-deriving them from the constant under test.
 *
 *  2. **instanceJsonCore key symmetry** — the mutation RPC face's
 *     instance JSON carries every key the debug listing's per-instance object
 *     carries (the [GH#68] image-endpoint keys included), so a mutation
 *     response round-trips without losing the image-endpoint state. The key
 *     list is asserted against a literal, not derived from either builder.
 */
class ProviderRpcSymmetryTest {

    private fun instance(
        type: ProviderType = ProviderType.gemini,
        base: String? = null,
    ): ProviderInstance = ProviderInstance(
        id = "inst-1",
        label = "Test Provider",
        providerType = type,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = base,
    )

    // ---- 1. Predicate: full 6-value enum, literal expectations ----

    @Test
    fun predicate_anthropicSupportsCustomBase() {
        assertTrue(ProviderType.anthropic.supportsCustomBaseURL)
    }

    @Test
    fun predicate_geminiSupportsCustomBase() {
        assertTrue(ProviderType.gemini.supportsCustomBaseURL)
    }

    @Test
    fun predicate_openAISupportsCustomBase() {
        assertTrue(ProviderType.openAI.supportsCustomBaseURL)
    }

    @Test
    fun predicate_openRouterDoesNotSupportCustomBase() {
        assertFalse(ProviderType.openRouter.supportsCustomBaseURL)
    }

    @Test
    fun predicate_xAISupportsCustomBase() {
        assertTrue(ProviderType.xAI.supportsCustomBaseURL)
    }

    @Test
    fun predicate_kimiCodeSupportsCustomBase() {
        assertTrue(ProviderType.kimiCode.supportsCustomBaseURL)
    }

    // ---- 2. instanceJsonCore: key symmetry (literal expected key set) ----

    private val expectedKeys = setOf(
        "id",
        "label",
        "providerType",
        "credentialType",
        "isEnabled",
        "customBaseURL",
        "appendV1Suffix",
        "useResponsesAPI",
        // [da1030-C ISSUE-04] Azure auth shape: readable on both RPC faces and
        // writable on both, so a create→list→update round trip cannot drop it.
        "azureMode",
        "imageEndpointMode",
        "imageEndpointResolved",
        "customUserAgent",
        "createdAt",
        "hasCredential",
        "modelEntryCount",
    )

    @Test
    fun instanceJsonCore_coversAllInstanceLevelKeys() {
        val json = instanceJsonCore(instance(), hasCredential = false, entryCount = 7)
        assertEquals(expectedKeys, json.keySet().toSet())
    }

    @Test
    fun instanceJsonCore_azureModeAlwaysPresent() {
        // Literal expectation: a mutation response must answer `false` for a
        // fresh instance, not omit the key. Deriving the expectation from
        // azureMode's own default would let a "drop the key" regression PASS.
        val json = instanceJsonCore(instance(), hasCredential = false, entryCount = 0)
        assertEquals(false, json.getBoolean("azureMode"))
    }

    // ---- 3. InstancesTest auth fork: azureMode picks the header shape ----

    @Test
    fun probeAuthHeader_azureModeUsesApiKeyHeader() {
        val (name, value) = probeAuthHeader(azureMode = true, key = "sk-test")
        assertEquals("api-key", name)
        assertEquals("sk-test", value)
    }

    @Test
    fun probeAuthHeader_nonAzureUsesBearerHeader() {
        val (name, value) = probeAuthHeader(azureMode = false, key = "sk-test")
        assertEquals("Authorization", name)
        assertEquals("Bearer sk-test", value)
    }

    // -- [da1031-D ISSUE-07] the two faces are ONE body: `probeAuthHeader` is a
    // -- thin wrapper over `openAIKeyAuthHeader`, so both must answer identically.
    // -- Expectations are literals (never derived from the branch under test).

    @Test
    fun openAIKeyAuthHeader_azurePicksApiKeyHeader() {
        val (name, value) = openAIKeyAuthHeader(azure = true, token = "sk-direct")
        assertEquals("api-key", name)
        assertEquals("sk-direct", value)
    }

    @Test
    fun openAIKeyAuthHeader_nonAzurePicksBearerHeader() {
        val (name, value) = openAIKeyAuthHeader(azure = false, token = "sk-direct")
        assertEquals("Authorization", name)
        assertEquals("Bearer sk-direct", value)
    }

    @Test
    fun probeAuthHeader_delegatesToOpenAIKeyAuthHeader_azureBranch() {
        assertEquals(
            openAIKeyAuthHeader(azure = true, token = "sk-delegated"),
            probeAuthHeader(azureMode = true, key = "sk-delegated"),
        )
        assertEquals("api-key" to "sk-delegated", probeAuthHeader(azureMode = true, key = "sk-delegated"))
    }

    @Test
    fun probeAuthHeader_delegatesToOpenAIKeyAuthHeader_bearerBranch() {
        assertEquals(
            openAIKeyAuthHeader(azure = false, token = "sk-delegated"),
            probeAuthHeader(azureMode = false, key = "sk-delegated"),
        )
        assertEquals(
            "Authorization" to "Bearer sk-delegated",
            probeAuthHeader(azureMode = false, key = "sk-delegated"),
        )
    }

    @Test
    fun instanceJsonCore_imageEndpointModeAlwaysPresent() {
        val json = instanceJsonCore(instance(), hasCredential = false, entryCount = 0)
        assertEquals("auto", json.getString("imageEndpointMode"))
        assertTrue(json.isNull("imageEndpointResolved"))
    }

    @Test
    fun instanceJsonCore_forcedImageEndpointRoundTrips() {
        val inst = instance(type = ProviderType.openAI)
        inst.imageEndpointMode = ImageEndpointMode.chatCompletions
        inst.imageEndpointResolved = ImageEndpointMode.imagesGenerations
        val json = instanceJsonCore(inst, hasCredential = true, entryCount = 3)
        assertEquals("chatCompletions", json.getString("imageEndpointMode"))
        assertEquals("imagesGenerations", json.getString("imageEndpointResolved"))
        assertEquals(true, json.getBoolean("hasCredential"))
        assertEquals(3, json.getInt("modelEntryCount"))
    }

    @Test
    fun instanceJsonCore_nullCustomBaseURLIsJsonNull() {
        val json = instanceJsonCore(instance(base = null), hasCredential = false, entryCount = 0)
        assertTrue(json.isNull("customBaseURL"))
        assertEquals("gemini", json.getString("providerType"))
        assertEquals("Test Provider", json.getString("label"))
    }

    @Test
    fun instanceJsonCore_presentCustomBaseURLCarriesValue() {
        val json = instanceJsonCore(instance(base = "https://api.example.com"), hasCredential = false, entryCount = 0)
        assertEquals("https://api.example.com", json.getString("customBaseURL"))
    }

    // ---- 4. [fix/dv16-a A-07] parseImageEndpointMode: shared value domain ----
    //
    // The create face previously dropped the imageEndpointMode key entirely
    // (only the update face accepted it), so a create→list→update round trip
    // could not express the endpoint shape the request path honors. The parse
    // is now one shared helper; the accepted token set (Kotlin enum names AND
    // wire SerialName) and the error wording are pinned with literals.

    @Test
    fun parseImageEndpointMode_acceptsEnumNamesAndWireTokens() {
        assertEquals(ImageEndpointMode.auto, parseImageEndpointMode("auto"))
        assertEquals(ImageEndpointMode.imagesGenerations, parseImageEndpointMode("imagesGenerations"))
        assertEquals(ImageEndpointMode.imagesGenerations, parseImageEndpointMode("images_generations"))
        assertEquals(ImageEndpointMode.chatCompletions, parseImageEndpointMode("chatCompletions"))
        assertEquals(ImageEndpointMode.chatCompletions, parseImageEndpointMode("chat_completions"))
    }

    @Test
    fun parseImageEndpointMode_refusesUnknownTokenWithUpdateWording() {
        try {
            parseImageEndpointMode("nope")
            fail("expected RPCException")
        } catch (expected: RPCException) {
            assertEquals(-32602, expected.code)
            assertEquals(
                "imageEndpointMode must be auto | images_generations | chat_completions (got 'nope')",
                expected.message,
            )
        }
    }
}
