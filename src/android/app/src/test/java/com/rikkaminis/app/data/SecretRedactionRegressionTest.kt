package com.rikkaminis.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Independent regression cases: all strings are invented canaries. */
class SecretRedactionRegressionTest {
    private fun red(s: String, known: List<String> = emptyList()) = SecretRedactionRules.redact(s, known).text

    @Test fun shortAndNumericSecretsAreNameBound() {
        assertFalse(red("password=1234\nPASSWORD=x").contains("1234"))
        assertFalse(red("password=1234\nPASSWORD=x").contains("PASSWORD=x"))
        val raw = "{\"password\":123456,\"count\":123456,\"n\":0.1234567890123456789012345}"
        val safe = red(raw, listOf("123456"))
        assertEquals("[MINIS_REDACTED]", JSONObject(safe).getString("password"))
        assertTrue(safe.contains("\"count\":123456"))
        assertTrue(safe.contains("0.1234567890123456789012345"))
    }
    @Test fun escapedJsonKeysQuotesAndKnownValuesAreCovered() {
        val key = red("{\"api\\u005fkey\":\"opaqueCanary9283\",\"n\":1}")
        assertFalse(key.contains("opaqueCanary9283"))
        assertEquals(1, JSONObject(key).getInt("n"))
        val value = red("{\"password\":\"prefix\\\"TAIL_CANARY\",\"count\":4096}")
        assertFalse(value.contains("TAIL_CANARY"))
        assertEquals(4096, JSONObject(value).getInt("count"))
        val known = red("{\"value\":\"opaque\\u002dcanary9283\"}", listOf("opaque-canary9283"))
        assertEquals("[MINIS_REDACTED]", JSONObject(known).getString("value"))
    }
    @Test fun publicKeysAndSeparatedMetricsStayByteIdentical() {
        val s = "{\"key\":\"public-row-123\",\"token_count\":123456,\"max_tokens\":4096,\"signature\":\"public-method-id\"}"
        assertEquals(s, red(s))
        assertFalse(SecretRedactionRules.isSecretName("ACCOUNT_ID"))
        assertTrue(SecretRedactionRules.isSecretName("CF_API_TOKEN"))
        assertTrue(SecretRedactionRules.isSecretName("X-API-Key"))
    }
    @Test fun urlSiblingAndCompoundHeadersHaveCorrectBoundaries() {
        val url = red("https://example.test/?access_token=opaqueCanary9283&next=public-resource#top")
        assertFalse(url.contains("opaqueCanary9283"))
        assertTrue(url.contains("&next=public-resource#top"))
        val cookie = red("Cookie: sid=firstCanary123; session=secondCanary123\nnext: intact")
        assertFalse(cookie.contains("firstCanary123"))
        assertFalse(cookie.contains("secondCanary123"))
        assertTrue(cookie.contains("next: intact"))
    }
    @Test fun nameEvidenceOverridesDataUriShapeExemption() {
        val s = red("password=\"data:text/plain;base64,c2VjcmV0\"")
        assertFalse(s.contains("c2VjcmV0"))
        val image = "data:image/png;base64,iVBORsk-abcdefghijklmnop"
        assertEquals(image, red(image))
        assertFalse(red(image, listOf("iVBORsk-abcdefghijklmnop")).contains("iVBOR"))
    }
    @Test fun placeholdersAreIdempotentEvenWithKnownSubstring() {
        val values = listOf("MINIS", "opaqueCanary9283")
        val once = red("password=opaqueCanary9283\nnext=normal", values)
        assertEquals(once, red(once, values))
        assertEquals(once, red(red(once, values), values))
    }
    @Test fun innerCommandStringsAreSafeWithoutChangingNumbers() {
        val s = red("{\"command\":\"curl -H \\\"Authorization: Bearer opaqueCanary9283\\\" https://example.test\",\"timeout\":900}")
        assertFalse(s.contains("opaqueCanary9283"))
        assertEquals(900, JSONObject(s).getInt("timeout"))
    }
    @Test fun deepNestedJsonAndOverlappingKnownValueDoNotLeak() {
        val deep = "[".repeat(80) + "{\"password\":\"prefix-longer-canary\"}" + "]".repeat(80)
        val safe = red(deep, listOf("prefix-longer", "longer-canary"))
        assertFalse(safe.contains("canary"))
        assertEquals(80, safe.count { it == '[' } - 1) // one marker opening bracket
    }
    @Test fun literalReplacementCanRemoveButCannotInventCredentials() {
        val original = "API_KEY=opaqueCanary9283\nNOTE=[MINIS_REDACTED]\nTIMEOUT=30"
        assertFalse(EnvVarRedactor.wouldEraseCredential(original, original.replace("30", "40")))
        assertTrue(EnvVarRedactor.wouldEraseCredential(original, original.replace("opaqueCanary9283", "[MINIS_REDACTED]")))
        assertFalse(EnvVarRedactor.wouldEraseCredential("ordinary text", "documentation [MINIS_REDACTED]"))
    }
    @Test fun sourceFailureHidesOutputWithoutThrowingOrSwallowingCancel() {
        val old = EnvVarRedactor.additionalSecretValues
        try {
            EnvVarRedactor.additionalSecretValues = { error("synthetic source failure") }
            val view = EnvVarRedactor.redactForDiagnostics("opaqueCanary9283")
            assertFalse(view.text.contains("opaqueCanary9283"))
            assertEquals(1, view.hits)
            EnvVarRedactor.additionalSecretValues = { throw kotlinx.coroutines.CancellationException("stop") }
            try { EnvVarRedactor.redactForDiagnostics("x"); fail("cancel must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
        } finally { EnvVarRedactor.additionalSecretValues = old }
    }
    @Test fun unknownCaptureTailIsNeverAssumedSafe() {
        val safe = EnvVarRedactor.hideTruncatedCaptureTail("good line\nopaque-prefix")
        assertTrue(safe.startsWith("good line\n"))
        assertFalse(safe.contains("opaque-prefix"))
    }
}
