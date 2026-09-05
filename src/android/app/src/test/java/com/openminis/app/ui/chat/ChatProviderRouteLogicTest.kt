package com.openminis.app.ui.chat

import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the route-change detection contract for [providerRouteChanged]:
 * exactly the fields ProviderFactory snapshots into the provider must count as
 * "route changed"; metadata-only fields must not.
 */
class ChatProviderRouteLogicTest {

    private fun instance(block: ProviderInstance.() -> ProviderInstance = { this }): ProviderInstance =
        block(
            ProviderInstance(
                id = "inst-1",
                label = "Test",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
            )
        )

    @Test
    fun identicalInstancesAreNotChanged() {
        val a = instance()
        val b = instance()
        assertFalse(providerRouteChanged(a, b))
    }

    // ── Route fields (must trigger) ───────────────────────────────

    @Test
    fun customBaseUrlChangeTriggers() {
        assertTrue(providerRouteChanged(instance(), instance { copy(customBaseURL = "https://x") }))
    }

    @Test
    fun appendV1SuffixChangeTriggers() {
        assertTrue(providerRouteChanged(instance(), instance { copy(appendV1Suffix = false) }))
    }

    @Test
    fun useResponsesApiChangeTriggers() {
        assertTrue(providerRouteChanged(instance(), instance { copy(useResponsesAPI = true) }))
    }

    @Test
    fun azureModeChangeTriggers() {
        assertTrue(providerRouteChanged(instance(), instance { copy(azureMode = true) }))
    }

    @Test
    fun customUserAgentChangeTriggers() {
        assertTrue(providerRouteChanged(instance(), instance { copy(customUserAgent = "mina/1.0") }))
    }

    @Test
    fun imageEndpointModeChangeTriggers() {
        assertTrue(
            providerRouteChanged(
                instance(),
                instance { copy(imageEndpointMode = ImageEndpointMode.imagesGenerations) }
            )
        )
    }

    // ── Non-route fields (must NOT trigger) ───────────────────────

    @Test
    fun labelChangeDoesNotTrigger() {
        assertFalse(providerRouteChanged(instance(), instance { copy(label = "Renamed") }))
    }

    @Test
    fun isEnabledChangeDoesNotTrigger() {
        // enable/disable is handled by the existing disabled-provider re-resolution,
        // not by route drift.
        assertFalse(providerRouteChanged(instance(), instance { copy(isEnabled = false) }))
    }

    @Test
    fun pinnedChangeDoesNotTrigger() {
        assertFalse(providerRouteChanged(instance(), instance { copy(pinned = true) }))
    }

    @Test
    fun imageEndpointResolvedChangeDoesNotTrigger() {
        // probe-result cache, not user intent — auto mode re-probes on its own.
        assertFalse(
            providerRouteChanged(
                instance(),
                instance { copy(imageEndpointResolved = ImageEndpointMode.chatCompletions) }
            )
        )
    }

    @Test
    fun customKnobsChangeTriggers() {
        // [T-provider-extra-headers/body] Knobs change the wire contract and
        // must rebuild the cached provider (instanceContext snapshot).
        assertTrue(
            providerRouteChanged(
                instance(),
                instance {
                    copy(customHeaders = listOf(com.openminis.app.data.model.CustomHeader("X-Foo", "bar")))
                }
            )
        )
        assertTrue(
            providerRouteChanged(
                instance(),
                instance {
                    copy(customBodyFields = listOf(com.openminis.app.data.model.CustomBodyField("temperature", "0.9")))
                }
            )
        )
    }
}