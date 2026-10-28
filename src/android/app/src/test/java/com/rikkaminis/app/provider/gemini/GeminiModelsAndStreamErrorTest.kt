package com.rikkaminis.app.provider.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-b08 F-02 / F-03] Gemini's two wire-level decisions.
 *
 * F-02: only a request that addresses GOOGLE may fall back to the builtin
 * catalog — a relay's failed model-list fetch must stay empty so the caller
 * preserves the user's rows.
 * F-03: an inline `{"error":{…}}` SSE frame becomes the matching HTTP-class
 * error instead of an EOF that the truncation guard turns into a continuation
 * retry.
 */
class GeminiModelsAndStreamErrorTest {

    // -- F-02 ---------------------------------------------------------------

    @Test
    fun `no custom base is the official endpoint`() {
        assertTrue(isOfficialGeminiBase(null))
        assertTrue(isOfficialGeminiBase(""))
        assertTrue(isOfficialGeminiBase("   "))
    }

    @Test
    fun `the public generativelanguage host is official`() {
        assertTrue(isOfficialGeminiBase("https://generativelanguage.googleapis.com/v1beta"))
    }

    @Test
    fun `a relay is not official`() {
        assertFalse(isOfficialGeminiBase("https://my-relay.example.com/v1beta"))
        assertFalse(isOfficialGeminiBase("https://gemini-proxy.internal"))
    }

    @Test
    fun `host matching is case-insensitive`() {
        assertTrue(isOfficialGeminiBase("HTTPS://GenerativeLanguage.GoogleAPIs.com/v1beta"))
    }

    // -- F-03 ---------------------------------------------------------------

    @Test
    fun `the code field wins when it is a real http status`() {
        assertEquals(429, geminiStreamErrorStatus(429, "RESOURCE_EXHAUSTED"))
        assertEquals(400, geminiStreamErrorStatus(400, "RESOURCE_EXHAUSTED"))
    }

    @Test
    fun `the google status string maps when the code is absent`() {
        assertEquals(429, geminiStreamErrorStatus(0, "RESOURCE_EXHAUSTED"))
        assertEquals(401, geminiStreamErrorStatus(0, "UNAUTHENTICATED"))
        assertEquals(403, geminiStreamErrorStatus(0, "PERMISSION_DENIED"))
        assertEquals(400, geminiStreamErrorStatus(0, "INVALID_ARGUMENT"))
        assertEquals(503, geminiStreamErrorStatus(0, "UNAVAILABLE"))
        assertEquals(500, geminiStreamErrorStatus(0, "INTERNAL"))
        assertEquals(504, geminiStreamErrorStatus(0, "DEADLINE_EXCEEDED"))
    }

    @Test
    fun `a meaningless code and status both land on 500`() {
        assertEquals(500, geminiStreamErrorStatus(0, ""))
        assertEquals(500, geminiStreamErrorStatus(1, "WHATEVER"))
        assertEquals(500, geminiStreamErrorStatus(200, "OK"))
    }

    @Test
    fun `status matching tolerates case and padding`() {
        assertEquals(429, geminiStreamErrorStatus(0, "  resource_exhausted "))
    }
}
