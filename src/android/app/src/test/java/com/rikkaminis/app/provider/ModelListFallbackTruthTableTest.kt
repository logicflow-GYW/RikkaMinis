package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [refactor/reuse-basics-1128] Cross-domain truth table for the single builtin
 * catalog decision that OpenAI and Anthropic models APIs now share.
 *
 * Every expectation is a literal (no `!isCustom && code != 401` restated as an
 * expression), so mutating the shared function turns this red instead of
 * quietly agreeing with itself.
 */
class ModelListFallbackTruthTableTest {

    private val officialExpectations = mapOf(
        // Literal contract of the shared decision. 200 is listed only because the
        // table is exhaustive over the codes the call sites can hand over: the
        // function is consulted on a FAILED fetch, so a 200 never reaches it and
        // its `true` here is not a statement about successful responses.
        200 to true,
        400 to true,
        401 to false,
        403 to false,
        404 to true,
        429 to true,
        500 to true,
        502 to true,
        503 to true,
        504 to true,
        529 to true,
        -1 to true,
    )

    @Test
    fun `official endpoint answers only non-auth failures with the builtin catalog`() {
        assertEquals(
            listOf(200, 400, 401, 403, 404, 429, 500, 502, 503, 504, 529, -1),
            officialExpectations.keys.toList(),
        )
        for ((code, expected) in officialExpectations) {
            assertEquals("official status=$code", expected, usesBuiltinCatalogOnFailure(false, code))
        }
    }

    @Test
    fun `custom endpoint never answers any status with the builtin catalog`() {
        for (code in officialExpectations.keys) {
            assertEquals("custom status=$code", false, usesBuiltinCatalogOnFailure(true, code))
        }
    }
}
