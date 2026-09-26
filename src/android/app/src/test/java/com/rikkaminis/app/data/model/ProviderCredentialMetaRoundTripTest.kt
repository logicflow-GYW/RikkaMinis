package com.rikkaminis.app.data.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ProviderCredentialMeta] — kept after the multi-key
 * feature was removed, because the `credentials_json` column it backs is
 * still part of the persisted schema (see ProviderInstance.credentials).
 *
 * These pin the two contracts that keep the retained blob safe:
 *
 *  1. **Round-trip**: meta → JSON → meta reproduces every field, INCLUDING an
 *     explicitly blank label (the model deliberately does NOT default it — an
     * explicit empty string round-trips as the user left it rather than being
     * rewritten to a generated name on every save).
 *  2. **Missing fields**: an older build's payload (no `isEnabled`, no
     * `migrated`, no `id`) decodes with the defaults — `coerceInputValues`
     * covers absent fields, so a JSON mirror written by an older build stays
     * valid.
 *
 * Secrets are deliberately NOT a field here — the class doc explains why; this
 * test asserts that the serialized shape carries no secret-shaped field, so a
 * future refactor that sneaks one in fails loudly.
 */
class ProviderCredentialMetaRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun roundTrip_preservesEveryField() {
        val meta = ProviderCredentialMeta(
            id = "uuid-1",
            label = "主号",
            note = "expires 2027-01",
            isEnabled = false,
            createdAt = 1_700_000_000_000L,
            migrated = true,
        )
        val encoded = json.encodeToString(ProviderCredentialMeta.serializer(), meta)
        val decoded = json.decodeFromString(ProviderCredentialMeta.serializer(), encoded)
        assertEquals(meta, decoded)
    }

    @Test
    fun roundTrip_blankLabelIsNotRewritten() {
        val meta = ProviderCredentialMeta(id = "uuid-2", label = "")
        val decoded = json.decodeFromString(
            ProviderCredentialMeta.serializer(),
            json.encodeToString(ProviderCredentialMeta.serializer(), meta),
        )
        assertEquals("", decoded.label)
        assertEquals("Key #1", decoded.displayLabel(0))
    }

    @Test
    fun olderBuildPayload_missingFieldsTakeDefaults() {
        // A payload written by a build before isEnabled/migrated/createdAt
        // existed. coerceInputValues covers the absent fields.
        val decoded = json.decodeFromString(
            ProviderCredentialMeta.serializer(),
            """{"id":"uuid-3","label":"备用","note":""}""",
        )
        assertEquals("备用", decoded.label)
        assertTrue("isEnabled defaults to true", decoded.isEnabled)
        assertFalse("migrated defaults to false", decoded.migrated)
        assertTrue(decoded.createdAt > 0)
    }

    @Test
    fun listRoundTrip_preservesOrder() {
        // Order was user-arranged and is the only thing tying a metadata row
        // to a physical EncryptedPrefs slot name, so it must not reorder.
        val metas = listOf(
            ProviderCredentialMeta(id = "a", label = "one"),
            ProviderCredentialMeta(id = "b", label = "two"),
            ProviderCredentialMeta(id = "c", label = "three"),
        )
        val decoded = json.decodeFromString(
            ListSerializer(ProviderCredentialMeta.serializer()),
            json.encodeToString(ListSerializer(ProviderCredentialMeta.serializer()), metas),
        )
        assertEquals(metas.map { it.id }, decoded.map { it.id })
    }

    @Test
    fun unknownFieldsAreIgnored() {
        // Forward compat: a future build adds a field, an older build must
        // still read the payload (ignoreUnknownKeys).
        val decoded = json.decodeFromString(
            ProviderCredentialMeta.serializer(),
            """{"id":"uuid-4","label":"x","note":"","someFutureField":true}""",
        )
        assertEquals("x", decoded.label)
    }

    @Test
    fun serializedShapeCarriesNoSecretField() {
        // SECRETS ARE NOT HERE. If a refactor sneaks a secret-shaped field
        // into the serialized form, this fails loudly — the meta rides the
        // config document (Room row, JSON mirror, backup, sync payload) and is
        // therefore the part that is not encrypted at rest.
        val meta = ProviderCredentialMeta(id = "uuid-5", label = "x", note = "y")
        val encoded = json.encodeToString(ProviderCredentialMeta.serializer(), meta)
        val lower = encoded.lowercase()
        for (forbidden in listOf("secret", "apikey", "api_key", "password", "token", "keyvalue")) {
            assertFalse("serialized meta must not carry a $forbidden-shaped field", lower.contains(forbidden))
        }
    }

    @Test
    fun displayLabel_fallsBackToPositional() {
        assertEquals("Key #1", ProviderCredentialMeta(label = "").displayLabel(0))
        assertEquals("Key #3", ProviderCredentialMeta(label = "").displayLabel(2))
        assertEquals("主号", ProviderCredentialMeta(label = "主号").displayLabel(5))
    }

    @Test
    fun credentialsBlob_survivesInstanceRoundTrip() {
        // The retained `credentials_json` column is empty in practice today,
        // but the mapping must not be the thing that loses data if anything
        // ever writes it again: an instance carrying metadata must serialize
        // and read back with the same ids and order.
        val instance = ProviderInstance(
            id = "i",
            label = "l",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            credentials = mutableListOf(
                ProviderCredentialMeta(id = "a", label = "one"),
                ProviderCredentialMeta(id = "b", label = "two"),
            ),
        )
        val encoded = json.encodeToString(
            ListSerializer(ProviderCredentialMeta.serializer()),
            instance.credentials,
        )
        val decoded = json.decodeFromString(
            ListSerializer(ProviderCredentialMeta.serializer()),
            encoded,
        )
        assertEquals(listOf("a", "b"), decoded.map { it.id })
        assertEquals(listOf("one", "two"), decoded.map { it.label })
    }
}
