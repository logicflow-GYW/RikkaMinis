package com.rikkaminis.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-debugserver-auth] `LLMRequestLog` must redact credential
 * material from request headers BEFORE they are serialized to the wire by
 * `debug.llmRequests` / `debug.agentTrace`. Authorization / x-api-key /
 * cookie (case-insensitive) are replaced with `[redacted:len]`.
 *
 * Tested via the internal [LLMRequestLog.redactHeaders] helper directly
 * (the production serialization path calls it), because `add()` short-circuits
 * when BuildConfig.DEBUG is false (release test variant) and would not retain
 * entries for a `toJSON` round-trip.
 */
class LLMRequestRedactTest {

    @Test
    fun `authorization bearer value is redacted`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("Authorization" to "Bearer sk-abc123"))
        assertEquals("[redacted:16]", redacted["Authorization"])
        // The raw credential string must not survive anywhere.
        assertFalse(redacted.values.joinToString().contains("sk-abc123"))
    }

    @Test
    fun `lowercase x-api-key is redacted`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("x-api-key" to "sekrit-key"))
        assertEquals("[redacted:10]", redacted["x-api-key"])
        assertFalse(redacted.values.joinToString().contains("sekrit"))
    }

    @Test
    fun `mixed-case cookie header name is matched case-insensitively`() {
        val redacted = LLMRequestLog.redactHeaders(mapOf("CoOkIe" to "session=abc"))
        assertEquals("[redacted:11]", redacted["CoOkIe"])
        assertFalse(redacted.values.joinToString().contains("abc"))
    }

    @Test
    fun `sensitive and benign headers mixed - only sensitive redacted`() {
        val redacted = LLMRequestLog.redactHeaders(
            mapOf(
                "X-API-KEY" to "k1",
                "Authorization" to "Bearer t2",
                "Content-Type" to "application/json",
                "X-Request-Id" to "req-123",
            ),
        )
        assertEquals("[redacted:2]", redacted["X-API-KEY"])
        assertEquals("[redacted:9]", redacted["Authorization"])
        // Benign headers pass through untouched.
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("req-123", redacted["X-Request-Id"])
    }

    @Test
    fun `empty headers pass through unchanged`() {
        assertEquals(emptyMap<String, String>(), LLMRequestLog.redactHeaders(emptyMap()))
    }

    @Test
    fun `redacted length is the original value length`() {
        val orig = "sk-tokenwithlength23"
        val out = LLMRequestLog.redactHeaders(mapOf("Authorization" to orig))
        assertEquals("[redacted:${orig.length}]", out["Authorization"])
    }

    @Test
    fun `authorization header survives intact when it is a benign value key mismatch`() {
        // Sanity: a non-sensitive header name is never mis-redacted.
        val out = LLMRequestLog.redactHeaders(mapOf("X-Custom-Hdr" to "Bearer sk-xyz"))
        assertNull(out.keys.firstOrNull { it == "Authorization" })
        assertEquals("Bearer sk-xyz", out["X-Custom-Hdr"])
    }

    // ── URL query redaction (F-3) ───────────────────────────────────────────

    @Test
    fun `gemini-style key query param is redacted`() {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini:generateContent?key=AIzaSyD-verysecretkey123"
        val out = LLMRequestLog.redactURL(url)
        assertFalse(out.contains("verysecretkey123"))
        assertTrue(out.contains("key=[redacted:"))
    }

    @Test
    fun `benign query params pass through while-sensitive ones are redacted`() {
        val url = "https://api.example.com/v1/chat?model=gpt-4&api_key=sekritapikey&stream=true"
        val out = LLMRequestLog.redactURL(url)
        assertTrue(out.contains("model=gpt-4"))
        assertTrue(out.contains("stream=true"))
        assertFalse(out.contains("sekritapikey"))
        assertTrue(out.contains("api_key=[redacted:"))
    }

    @Test
    fun `case-insensitive sensitive query key match`() {
        val out = LLMRequestLog.redactURL("https://api.example.com/x?Token=abc12345&foo=bar")
        assertFalse(out.contains("abc12345"))
        assertTrue(out.contains("Token=[redacted:"))
        assertTrue(out.contains("foo=bar"))
    }

    @Test
    fun `url without query string passes through unchanged`() {
        val url = "https://api.example.com/v1/models"
        assertEquals(url, LLMRequestLog.redactURL(url))
    }

    @Test
    fun `query param without equals sign is left alone`() {
        val url = "https://api.example.com/x?flag"
        assertEquals(url, LLMRequestLog.redactURL(url))
    }

    // ── requestBody secret scanning (F-4) ───────────────────────────────────

    @Test
    fun `sk-token in body is masked`() {
        val body = """{"messages":[],"api_key":"sk-abcdefghijklmnop123456"}"""
        val out = LLMRequestLog.redactSecrets(body)
        assertFalse(out.contains("sk-abcdefghijklmnop"))
        assertTrue(out.contains("sk-***"))
    }

    @Test
    fun `ghp-github-token in body is masked`() {
        val body = """{"token":"ghp_0123456789abcdefghijklmnop"}"""
        val out = LLMRequestLog.redactSecrets(body)
        assertFalse(out.contains("ghp_0123456789"))
        assertTrue(out.contains("ghp_***"))
    }

    @Test
    fun `bearer-token in body is masked`() {
        val body = """{"authorization":"Bearer eyJhbGciOiJIUzI1NiJ9.abc.def"}"""
        val out = LLMRequestLog.redactSecrets(body)
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `api-key-value pair in body is masked`() {
        val body = """{"api_key": "sk-abcdefghijklmnop123456"}"""
        val out = LLMRequestLog.redactSecrets(body)
        assertFalse(out.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun `benign body content is not touched`() {
        val body = """{"model":"gpt-4","messages":[{"role":"user","content":"hello"}]}"""
        assertEquals(body, LLMRequestLog.redactSecrets(body))
    }
}
