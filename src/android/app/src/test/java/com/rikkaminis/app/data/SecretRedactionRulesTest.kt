package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/tool-output-redaction-1123] Pins the pure rules that decide what counts
 * as a credential in tool output.
 *
 * The asymmetry under test: a missed credential costs the user a secret once it
 * reaches a model or a persisted diagnostic; a false positive costs the model
 * one unreadable line. But a *careless* false positive (git SHA, token metrics,
 * max_tokens, base64 images) breaks ordinary work and teaches nobody anything —
 * those are pinned below as hard "must remain visible" cases.
 */
class SecretRedactionRulesTest {

    private fun redact(input: String, known: List<String> = emptyList()) =
        SecretRedactionRules.redact(input, known)

    // ── known values ──────────────────────────────────────────────────────

    @Test
    fun `known value is replaced whole`() {
        val r = redact("token is sk-secret123", listOf("sk-secret123"))
        assertFalse(r.text.contains("sk-secret123"))
        assertTrue(r.text.contains(SecretRedactionRules.PLACEHOLDER))
        assertEquals(1, r.hits)
    }

    @Test
    fun `longer known value wins over its substring`() {
        val r = redact("secret = TOKENBAR", listOf("BAR", "TOKENBAR"))
        assertFalse(r.text.contains("TOKENBAR"))
        assertFalse(r.text.contains("TOKEN***"))
    }

    @Test
    fun `known values below the floor are not replaced`() {
        val r = redact("curl http://data true", listOf("http", "data", "true"))
        assertEquals("curl http://data true", r.text)
        assertEquals(0, r.hits)
    }

    @Test
    fun `known value inside a data uri is still redacted`() {
        // Known values are the strongest evidence; the base64 exemption only
        // applies to shape inference.
        val r = redact("data:text/plain;base64,QUJDREVGR0g=", listOf("QUJDREVGR0g="))
        assertFalse(r.text.contains("QUJDREVGR0g="))
    }

    // ── random-looking strings must survive ───────────────────────────────

    @Test
    fun `bare 32 char random hex is not a credential`() {
        val s = "a3b7c921f8e04d16a7c38b90e5726dc4"
        assertTrue(redact("id $s").text.contains(s))
    }

    @Test
    fun `git sha and sha256 survive`() {
        val sha1 = "5f0be508479f7a824302a587b970451f44217d79"
        val sha256 = "033d8636ab781e385f8e993f2bb2f4be8edf52f0a3b7c921f8e04d16a7c38b90"
        val r = redact("commit $sha1 tree $sha256")
        assertTrue(r.text.contains(sha1))
        assertTrue(r.text.contains(sha256))
    }

    @Test
    fun `uuid and public account id survive`() {
        val uuid = "3f2b9c1e-4a5d-4e6f-8a9b-0c1d2e3f4a5b"
        val text = "run=$uuid account=982183014"
        assertEquals(text, redact(text).text)
    }

    @Test
    fun `token metrics and max_tokens are not credentials`() {
        val text = """{"max_tokens": 4096, "tokenCount": 12, "tokenizer": "cl100k"}"""
        assertEquals(text, redact(text).text)
    }

    @Test
    fun `ordinary words containing a prefix are left alone`() {
        val text = "task-1 mask-2 risk-assessment"
        assertEquals(text, redact(text).text)
    }

    // ── high-confidence token formats ─────────────────────────────────────

    @Test
    fun `openai style token is redacted`() {
        val token = "sk-abcdefghijklmnop123456"
        val r = redact("key: $token")
        assertFalse(r.text.contains(token))
        assertTrue(r.text.contains(SecretRedactionRules.PLACEHOLDER))
    }

    @Test
    fun `github aws and hf shaped tokens are redacted`() {
        val gh = "ghp_abcdefghijklmnopqrstuvwxyz0123"
        val aws = "AKIAIOSFODNN7EXAMPLE"
        val hf = "hf_abcdefghijklmnopqrstuvwxyz"
        val r = redact("$gh $aws $hf")
        assertFalse(r.text.contains(gh))
        assertFalse(r.text.contains(aws))
        assertFalse(r.text.contains(hf))
    }

    @Test
    fun `dotenv assignment with a secret name is redacted`() {
        val r = redact("API_KEY=abcdef123456\nTIMEOUT=30")
        assertFalse(r.text.contains("abcdef123456"))
        assertTrue(r.text.contains("TIMEOUT=30"))
    }

    @Test
    fun `json secret field is redacted but sibling numbers survive`() {
        val r = redact("""{"password": "hunter2hunter2", "max_tokens": 4096}""")
        assertFalse(r.text.contains("hunter2hunter2"))
        assertTrue(r.text.contains("\"max_tokens\": 4096"))
    }

    @Test
    fun `authorization header masks the credential not the scheme`() {
        val r = redact("Authorization: Bearer abcdefgh12345")
        assertFalse(r.text.contains("abcdefgh12345"))
        assertTrue(r.text.contains("Authorization: "))
    }

    @Test
    fun `camel case secret names are redacted`() {
        val r = redact("clientSecret=s3cr3tvalue")
        assertFalse(r.text.contains("s3cr3tvalue"))
    }

    @Test
    fun `unterminated quote hides the rest of the line`() {
        val r = redact("password=\"abcdef123456\nnext line")
        assertFalse(r.text.contains("abcdef123456"))
        assertTrue(r.text.contains("next line"))
    }

    @Test
    fun `pem private key block is redacted`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEabc\n-----END RSA PRIVATE KEY-----"
        val r = redact(pem)
        assertFalse(r.text.contains("MIIEabc"))
        assertTrue(r.text.contains(SecretRedactionRules.PLACEHOLDER))
    }

    @Test
    fun `jwt is redacted only when its header decodes to json with alg`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val r = redact("auth $jwt")
        assertFalse(r.text.contains(jwt))
    }

    @Test
    fun `base64 that is not a jwt survives`() {
        val b64 = "eyJhbGciOiJIUzI1NiJ9" // header only, no three segments
        assertEquals(b64, redact(b64).text)
    }

    // ── exemptions and idempotence ────────────────────────────────────────

    @Test
    fun `base64 data uri is exempt from shape inference`() {
        val uri = "data:image/png;base64,iVBORsk-abcdefghijklmnop"
        val r = redact("image $uri")
        assertTrue(r.text.contains(uri))
    }

    @Test
    fun `empty input is unchanged`() {
        val r = redact("")
        assertEquals("", r.text)
        assertEquals(0, r.hits)
    }

    @Test
    fun `second pass returns the same text`() {
        val once = redact("API_KEY=abcdef123456 sk-abcdefghijklmnop").text
        val twice = redact(once).text
        assertEquals(once, twice)
    }

    @Test
    fun `placeholder is recognized`() {
        assertTrue(SecretRedactionRules.isPlaceholder(" [MINIS_REDACTED] "))
        assertFalse(SecretRedactionRules.isPlaceholder("sk-abcdefghijklmnop"))
    }

    @Test
    fun `monkey is not a secret name`() {
        val text = "monkey=banana12 hockeyScore=abcdefg"
        assertEquals(text, redact(text).text)
    }

    // ── [fix/tool-args-log-redaction-1123] ordering contract ───────────────
    //
    // The four `ToolCallComplete ... args=` log sites truncate to 300 chars.
    // If the truncation runs FIRST, a credential straddling the cut is left as
    // a fragment that matches no rule, and that fragment is written verbatim.
    // These two tests pin the order the production sites must keep.

    @Test
    fun `redacting before the cut leaves no credential fragment`() {
        val filler = "x".repeat(280)
        val token = "sk-" + "LIVEprobe".repeat(4) + "abcdefghij"
        val payload = "{\"command\":\"" + filler + " " + token + "\"}"
        val cut = 300

        // Correct order: redact the WHOLE payload, then cut.
        val safe = EnvVarRedactor.redactForDiagnostics(payload).text.take(cut)

        assertFalse(safe.contains(token.substring(0, 6)))
        assertTrue(safe.contains(SecretRedactionRules.PLACEHOLDER))
    }

    @Test
    fun `cutting before redaction leaks the straddling fragment`() {
        val filler = "x".repeat(280)
        val token = "sk-" + "LIVEprobe".repeat(4) + "abcdefghij"
        val payload = "{\"command\":\"" + filler + " " + token + "\"}"
        val cut = 300

        // The WRONG order, pinned so the failure mode stays documented and the
        // two call sites cannot "simplify" back to it without a red test.
        val leaky = EnvVarRedactor.redactForDiagnostics(payload.take(cut)).text

        assertTrue("truncating first must be shown to leak", leaky.contains(token.substring(0, 6)))
    }

    @Test
    fun `plain arguments are not rewritten by the log projection`() {
        val plain = "{\"path\":\"/tmp/plain.txt\",\"lines\":12}"
        assertEquals(plain, EnvVarRedactor.redactForDiagnostics(plain).text)
    }
}
