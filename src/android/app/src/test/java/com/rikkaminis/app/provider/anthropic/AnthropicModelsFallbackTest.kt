package com.rikkaminis.app.provider.anthropic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-b08 F-06] The Anthropic models-list fallback decision.
 *
 * Before: every failed candidate base handed back `LLMModel.allAnthropic` for the
 * official instance — including after a 401/403 — so `refreshModels` reported
 * `SUCCESS_API` for a revoked key. Custom endpoints keep `emptyList()` (they
 * often have no `/v1/models` at all).
 */
class AnthropicModelsFallbackTest {

    @Test
    fun `official instance keeps the builtin catalog as a network net`() {
        assertTrue(usesBuiltinCatalogOnFailure(isCustomEndpoint = false, lastFailureCode = 502))
    }

    @Test
    fun `official instance does not use it after 401`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomEndpoint = false, lastFailureCode = 401))
    }

    @Test
    fun `official instance does not use it after 403`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomEndpoint = false, lastFailureCode = 403))
    }

    @Test
    fun `a custom endpoint never gets the builtin catalog`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomEndpoint = true, lastFailureCode = 500))
    }

    @Test
    fun `a never-attempted code -1 stays a network failure`() {
        assertTrue(usesBuiltinCatalogOnFailure(isCustomEndpoint = false, lastFailureCode = -1))
    }
}
