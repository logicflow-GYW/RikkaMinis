package com.rikkaminis.app.provider.openai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-b08 F-06 / F-07] The two models-list decisions that used to be inline in
 * `fetchModels`.
 *
 * F-06: a 401/403 on the OFFICIAL instance must NOT be answered with the builtin
 * catalog (that is what reported SUCCESS_API for a revoked key).
 * F-07: the Azure list URL is `{root}/openai/models?api-version=…` — the query
 * stays LAST; `/v1`/`/models` must never land inside the api-version value.
 *
 * All expectations are literals.
 */
class OpenAIModelsUrlAndFallbackTest {

    // -- F-06 ---------------------------------------------------------------

    @Test
    fun `official instance uses the builtin catalog on a network failure`() {
        assertTrue(usesBuiltinCatalogOnFailure(isCustomBase = false, statusCode = 502))
    }

    @Test
    fun `official instance does not use it on 401`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomBase = false, statusCode = 401))
    }

    @Test
    fun `official instance does not use it on 403`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomBase = false, statusCode = 403))
    }

    @Test
    fun `custom base never uses the builtin catalog`() {
        assertFalse(usesBuiltinCatalogOnFailure(isCustomBase = true, statusCode = 500))
        assertFalse(usesBuiltinCatalogOnFailure(isCustomBase = true, statusCode = 200))
    }

    // -- F-07 ---------------------------------------------------------------

    @Test
    fun `plain base gets only slash models appended`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            buildModelsURL("https://api.openai.com/v1", azureMode = false),
        )
    }

    @Test
    fun `a base without v1 keeps it that way`() {
        assertEquals(
            "https://vllm.internal/models",
            buildModelsURL("https://vllm.internal/", azureMode = false),
        )
    }

    @Test
    fun `azure keeps the api-version query last`() {
        assertEquals(
            "https://my-res.openai.azure.com/openai/models?api-version=2024-10-21",
            buildModelsURL(
                "https://my-res.openai.azure.com?api-version=2024-10-21",
                azureMode = true,
            ),
        )
    }

    @Test
    fun `azure strips a trailing v1 from the path`() {
        // Azure's newer `/openai/v1` base style: the version segment is a PATH
        // segment there, and the list endpoint still lives at /openai/models.
        assertEquals(
            "https://my-res.openai.azure.com/openai/models?api-version=2024-10-21",
            buildModelsURL(
                "https://my-res.openai.azure.com/openai/v1?api-version=2024-10-21",
                azureMode = true,
            ),
        )
    }

    @Test
    fun `azure does not double the openai segment`() {
        assertEquals(
            "https://my-res.openai.azure.com/openai/models?api-version=2024-10-21",
            buildModelsURL(
                "https://my-res.openai.azure.com/openai?api-version=2024-10-21",
                azureMode = true,
            ),
        )
    }

    @Test
    fun `azure without a query still produces a real path`() {
        assertEquals(
            "https://my-res.openai.azure.com/openai/models",
            buildModelsURL("https://my-res.openai.azure.com/", azureMode = true),
        )
    }

    @Test
    fun `a blank base falls back to the public endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            buildModelsURL(null, azureMode = false),
        )
    }
}
