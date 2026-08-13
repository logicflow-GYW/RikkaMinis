package com.openminis.app.data.repository

import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.ProviderInstanceEntity
import com.openminis.app.data.db.ProviderModelGroupEntity
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.RoutingStrategy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-enum-safe-parse] Regression tests for the safe enum parsing in
 * [com.openminis.app.data.db.toProviderConfig].
 *
 * A NEWER build may persist enum names this build doesn't know (a new
 * RoutingStrategy, a new ProviderType, ...). Before the fix, the five bare
 * `valueOf(...)` calls in toProviderConfig threw IllegalArgumentException on
 * such rows — which blew up the whole DB load, fell back to the JSON mirror
 * (failing identically on the same value), and wiped providers/groups from
 * the UI. These tests pin the safe-parse contract: unknown names must fall
 * back to the first enum value, never throw.
 */
class ProviderConfigSafeParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    // Every persisted enum string is unknown to this build — simulating a
    // downgrade scenario where a newer build wrote NEW enum values.
    private fun unknownSnapshot() = ProviderConfigSnapshot(
        instances = listOf(
            ProviderInstanceEntity(
                id = "i1",
                label = "P",
                providerType = "NEW_PROVIDER_TYPE_9",
                credentialType = "NEW_CREDENTIAL_TYPE_9",
                createdAt = 0L,
            )
        ),
        entries = emptyList(),
        groups = listOf(
            ProviderModelGroupEntity(
                id = "g1",
                name = "G",
                strategy = "NEW_STRATEGY_9",
                fallbackStrategy = "NEW_FALLBACK_9",
                memberEntryIdsJson = "[]",
            )
        ),
        loopIds = emptyList(),
        meta = emptyList(),
    )

    @Test
    fun `unknown enum names fall back to defaults instead of throwing`() {
        val config = unknownSnapshot().toProviderConfig(json)

        val inst = config.instances.single()
        assertEquals("unknown providerType must not throw, fall back to first value", ProviderType.anthropic, inst.providerType)
        assertEquals("unknown credentialType must not throw, fall back to first value", ProviderCredential.apiKey, inst.credentialType)

        val group = config.modelGroups.single()
        assertEquals("unknown strategy must not throw, fall back to first value", RoutingStrategy.fallback, group.strategy)
        assertEquals("unknown fallbackStrategy must not throw, fall back to first value", FallbackStrategy.default, group.fallbackStrategy)
        // recovery removed
    }

    @Test
    fun `known enum names still parse normally`() {
        val snapshot = ProviderConfigSnapshot(
            instances = listOf(
                ProviderInstanceEntity(
                    id = "i1",
                    label = "P",
                    providerType = ProviderType.gemini.name,
                    credentialType = ProviderCredential.oauth.name,
                    createdAt = 0L,
                )
            ),
            entries = emptyList(),
            groups = listOf(
                ProviderModelGroupEntity(
                    id = "g1",
                    name = "G",
                    strategy = RoutingStrategy.loadBalance.name,
                    fallbackStrategy = FallbackStrategy.always.name,
                    memberEntryIdsJson = "[]",
                )
            ),
            loopIds = emptyList(),
            meta = emptyList(),
        )

        val config = snapshot.toProviderConfig(json)
        val inst = config.instances.single()
        assertEquals(ProviderType.gemini, inst.providerType)
        assertEquals(ProviderCredential.oauth, inst.credentialType)

        val group = config.modelGroups.single()
        assertEquals(RoutingStrategy.loadBalance, group.strategy)
        assertEquals(FallbackStrategy.always, group.fallbackStrategy)
        // recovery removed
    }

    @Test
    fun `legacy provider types and credentials round-trip unchanged`() {
        // [T-kimi-oauth] kimiCode is the newest ProviderType — make sure the
        // safe-parse refactor didn't break the newest known value.
        val snapshot = ProviderConfigSnapshot(
            instances = listOf(
                ProviderInstanceEntity(
                    id = "i1",
                    label = "P",
                    providerType = ProviderType.kimiCode.name,
                    credentialType = ProviderCredential.apiKey.name,
                    createdAt = 0L,
                )
            ),
            entries = emptyList(),
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        )

        val config = snapshot.toProviderConfig(json)
        assertEquals(ProviderType.kimiCode, config.instances.single().providerType)
        assertTrue("toProviderConfig must complete without exception", true)
    }
}
