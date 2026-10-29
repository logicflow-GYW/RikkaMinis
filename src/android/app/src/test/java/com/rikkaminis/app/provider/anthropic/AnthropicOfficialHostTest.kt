package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-E F-07 / C-06①] The official-ness of an Anthropic base is a **host**
 * property, not "the user typed something into the custom-base field".
 *
 * Why this file exists (the gap the audit named): [AnthropicModelsFallbackTest]
 * only ever passed BOOLEANS into `usesBuiltinCatalogOnFailure`, so nothing in
 * the suite noticed that production derived that boolean from `baseURL != null`
 * — the one input shape that actually occurs (`effectiveBaseURL` appends `/v1`)
 * was never fed to the judgement. The second test below feeds it.
 *
 * Every expectation is a literal, written from the URL shapes themselves — none
 * is derived from the constant under test, so flipping a constant cannot make a
 * stale expectation pass.
 */
class AnthropicOfficialHostTest {

    @Test
    fun `public endpoint shapes are official`() {
        // null/blank = the provider's own default base.
        assertTrue(isOfficialAnthropicHost(null))
        assertTrue(isOfficialAnthropicHost(""))
        assertTrue(isOfficialAnthropicHost("   "))

        assertTrue(isOfficialAnthropicHost("https://api.anthropic.com"))
        // THE shape that reaches the provider in production: effectiveBaseURL
        // appends /v1 because appendV1Suffix defaults to true.
        assertTrue(isOfficialAnthropicHost("https://api.anthropic.com/v1"))
        assertTrue(isOfficialAnthropicHost("https://api.anthropic.com/v1/"))
        assertTrue(isOfficialAnthropicHost("https://api.anthropic.com:443/v1"))
        // No scheme, mixed case — a user pasting by hand.
        assertTrue(isOfficialAnthropicHost("api.anthropic.com"))
        assertTrue(isOfficialAnthropicHost("API.anthropic.COM/V1"))
    }

    @Test
    fun `relay and look-alike hosts are not official`() {
        assertFalse(isOfficialAnthropicHost("https://relay.example.com/anthropic"))
        assertFalse(isOfficialAnthropicHost("https://relay.example.com/anthropic/v1"))
        assertFalse(isOfficialAnthropicHost("https://api.deepseek.com/anthropic/v1"))
        // Look-alikes: the HOST has to match exactly, not merely contain the
        // string — a suffix/prefix/subdomain or a userinfo trick is a relay.
        assertFalse(isOfficialAnthropicHost("https://api.anthropic.com.evil.com/v1"))
        assertFalse(isOfficialAnthropicHost("https://evil-api.anthropic.com/v1"))
        assertFalse(isOfficialAnthropicHost("https://api.anthropic.com@evil.com/v1"))
    }

    @Test
    fun `an instance that pasted the public endpoint is judged official`() {
        val instance = ProviderInstance(
            id = "p-anthropic-default",
            label = "Anthropic",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://api.anthropic.com",
        )
        // appendV1Suffix defaults to true — spelling out the value production
        // actually hands to AnthropicProvider / AnthropicModelsApi.
        assertEquals("https://api.anthropic.com/v1", instance.effectiveBaseURL)
        // What the pre-fix `basePath != "https://api.anthropic.com"` test answered:
        assertTrue(
            "the retired judgement called this a relay",
            instance.effectiveBaseURL != "https://api.anthropic.com",
        )
        // …and what both call sites (`isCustomEndpoint`) now derive from it.
        assertFalse(!isOfficialAnthropicHost(instance.effectiveBaseURL))
    }

    @Test
    fun `an instance pointed at a relay stays a relay`() {
        val instance = ProviderInstance(
            id = "p-anthropic-relay",
            label = "Relay",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://relay.example.com/anthropic",
        )
        assertEquals("https://relay.example.com/anthropic/v1", instance.effectiveBaseURL)
        assertFalse(isOfficialAnthropicHost(instance.effectiveBaseURL))
        assertTrue(!isOfficialAnthropicHost(instance.effectiveBaseURL))
    }

    @Test
    fun `trailing slash on a relay base keeps it a relay`() {
        val instance = ProviderInstance(
            id = "p-anthropic-relay-slash",
            label = "Relay",
            providerType = ProviderType.anthropic,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://relay.example.com/anthropic/",
        )
        assertFalse(isOfficialAnthropicHost(instance.effectiveBaseURL))
    }
}
