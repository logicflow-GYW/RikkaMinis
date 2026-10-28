package com.rikkaminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-envvar-redactor-tests] JVM tests for the env-var output redactor.
 *
 * `mask` and `redact` are pure functions with no Android dependency, so
 * they run on the plain JVM. The tests pin the masking rule, the
 * longest-first de-dup ordering, the minimum-match-length threshold, and
 * the privacy-gate behaviour of `redactIfEnabled` (which is a no-op when
 * Privacy Mode is off or the repository isn't wired).
 */
class EnvVarRedactorTest {

    // ── mask rule ─────────────────────────────────────────────────────

    @Test
    fun `short value under 8 chars becomes all stars`() {
        assertEquals("*****", EnvVarRedactor.mask("abcde"))     // 5
        assertEquals("******", EnvVarRedactor.mask("abcdef"))   // 6
        assertEquals("*******", EnvVarRedactor.mask("abcdefg")) // 7
    }

    @Test
    fun `value of exactly 8 chars keeps first 2 and last 2`() {
        // len == 8 -> first2 + 4 stars + last2
        assertEquals("sk****aj", EnvVarRedactor.mask("sk1234aj"))
    }

    @Test
    fun `long value masks only the middle`() {
        val value = "sk-abcdefghijklmnop" // 19 chars
        val masked = EnvVarRedactor.mask(value)
        assertEquals(value.length, masked.length)
        assertTrue(masked.startsWith("sk"))   // first 2 chars kept verbatim
        assertTrue(masked.endsWith("op"))
        // entire middle span (after the leading 2, before the trailing 2) is stars
        assertTrue(masked.substring(2, masked.length - 2).all { it == '*' })
    }

    // ── redact: matching ──────────────────────────────────────────────

    @Test
    fun `redacts a value that appears verbatim`() {
        val (out, hits) = EnvVarRedactor.redact("token is sk-secret123", listOf("sk-secret123"))
        assertEquals("token is sk********23", out)
        assertEquals(1, hits)
    }

    @Test
    fun `no match returns output unchanged with zero hits`() {
        val (out, hits) = EnvVarRedactor.redact("nothing sensitive", listOf("sk-secret123"))
        assertEquals("nothing sensitive", out)
        assertEquals(0, hits)
    }

    @Test
    fun `values shorter than MIN_MATCH_LEN are skipped`() {
        // "http", "data", "true" are length 4 -> below MIN_MATCH_LEN(5), skipped
        val (out, hits) = EnvVarRedactor.redact(
            "curl http://data true",
            listOf("http", "data", "true"),
        )
        assertEquals("curl http://data true", out)
        assertEquals(0, hits)
    }

    // ── redact: longest-first ordering ────────────────────────────────

    @Test
    fun `longer value wins over its substring`() {
        // BAR is a substring of TOKENBAR (both >= MIN_MATCH_LEN). Longest-first
        // masks TOKENBAR whole (TO****AR) so BAR never matches separately. A
        // short-first bug would instead produce "TOKEN***".
        val (out, hits) = EnvVarRedactor.redact(
            "secret = TOKENBAR",
            listOf("BAR", "TOKENBAR"),
        )
        assertEquals("secret = TO****AR", out)
        assertEquals(1, hits)
    }

    @Test
    fun `duplicate values are de-duplicated`() {
        val (out, hits) = EnvVarRedactor.redact(
            "a sk-secret123 b",
            listOf("sk-secret123", "sk-secret123"),
        )
        assertEquals("a sk********23 b", out)
        assertEquals(1, hits)
    }

    @Test
    fun `two distinct values produce two hits`() {
        val (out, hits) = EnvVarRedactor.redact(
            "first sk-alpha123 then sk-beta456",
            listOf("sk-alpha123", "sk-beta456"),
        )
        assertEquals(2, hits)
        assertFalse(out.contains("sk-alpha123"))
        assertFalse(out.contains("sk-beta456"))
    }

    @Test
    fun `empty candidate list returns output unchanged`() {
        val (out, hits) = EnvVarRedactor.redact("text", emptyList())
        assertEquals("text", out)
        assertEquals(0, hits)
    }

    @Test
    fun `all candidates below threshold returns unchanged`() {
        val (out, hits) = EnvVarRedactor.redact("ab cd ef", listOf("ab", "cd", "ef"))
        assertEquals("ab cd ef", out)
        assertEquals(0, hits)
    }

    // ── redactIfEnabled: privacy gate ─────────────────────────────────
    //
    // `redactIfEnabled` reads two process-wide singletons (privacy-mode flag
    // and the wired repository); the full masking path needs an Android
    // Context so it's exercised on-device. These tests pin the pure short-
    // circuit branches that run before any Android dependency is touched.

    @Test
    fun `redactIfEnabled short-circuits when privacy mode is disabled`() {
        val wasEnabled = EnvVarPrivacyStore.isEnabled
        EnvVarPrivacyStore.setEnabled(false)
        try {
            val (out, hits) = EnvVarRedactor.redactIfEnabled("unchanged")
            assertEquals("unchanged", out)
            assertEquals(0, hits)
        } finally {
            EnvVarPrivacyStore.setEnabled(wasEnabled)
        }
    }

    @Test
    fun `redactIfEnabled short-circuits when no repository is wired`() {
        val wasEnabled = EnvVarPrivacyStore.isEnabled
        EnvVarPrivacyStore.setEnabled(true)
        EnvVarRedactor.envVarRepository = null
        try {
            val (out, hits) = EnvVarRedactor.redactIfEnabled("unchanged")
            assertEquals("unchanged", out)
            assertEquals(0, hits)
        } finally {
            EnvVarPrivacyStore.setEnabled(wasEnabled)
        }
    }

    // ── [T-envvar-redactor-testable-tail] the repository-wired tail, as a pure fn ──

    @Test
    fun `redactWithReminder appends the system reminder when a value was masked`() {
        val (out, hits) = EnvVarRedactor.redactWithReminder(
            "curl -H \"Authorization: Bearer sk-1234567890abcdef\"",
            listOf("sk-1234567890abcdef"),
        )
        assertTrue(out.contains(EnvVarRedactor.SYSTEM_REMINDER))
        assertTrue(out.contains("sk**"))  // first2 kept, middle masked
        assertFalse(out.contains("sk-1234567890abcdef"))
        assertEquals(1, hits)
    }

    @Test
    fun `redactWithReminder adds no reminder when nothing matched`() {
        val (out, hits) = EnvVarRedactor.redactWithReminder("clean output", listOf("sk-1234567890abcdef"))
        assertEquals("clean output", out)
        assertFalse(out.contains(EnvVarRedactor.SYSTEM_REMINDER))
        assertEquals(0, hits)
    }

    @Test
    fun `redactWithReminder reminder goes after the masked body`() {
        val (out, _) = EnvVarRedactor.redactWithReminder("value: sk-1234567890abcdef", listOf("sk-1234567890abcdef"))
        assertTrue(out.endsWith(EnvVarRedactor.SYSTEM_REMINDER))
    }

    // ── [T-envvar-index-integrity] shape-based fallback ────────────────
    //
    // Reached when `env-vars.json` is unreadable: no value list exists, so the
    // only defence left is the SHAPE of what is in the output. Over-masking is
    // the intended direction; under-masking is the bug.

    @Test
    fun `unindexed masks a shell assignment whose name is secret`() {
        val (out, hits) = EnvVarRedactor.redactUnindexed("export API_KEY=cf-abcdefghij next")
        assertFalse("raw value must be gone: $out", out.contains("cf-abcdefghij"))
        assertTrue(out.startsWith("export API_KEY="))
        assertEquals(1, hits)
    }

    @Test
    fun `unindexed masks a quoted json value`() {
        val (out, hits) = EnvVarRedactor.redactUnindexed("""{"clientSecret": "s3cr3tvalue"}""")
        assertFalse(out, out.contains("s3cr3tvalue"))
        assertEquals(1, hits)
    }

    @Test
    fun `unindexed masks an Authorization header value whole`() {
        val (out, _) = EnvVarRedactor.redactUnindexed("Authorization: Bearer abcdefg12345")
        assertFalse(out, out.contains("abcdefg12345"))
    }

    @Test
    fun `unindexed masks camelCase secret names`() {
        val (out, _) = EnvVarRedactor.redactUnindexed("dbPassword=hunter2hunter2")
        assertFalse(out, out.contains("hunter2hunter2"))
    }

    @Test
    fun `unindexed leaves names that only contain the letters KEY alone`() {
        // "monkey" / "hockey" are the classic false positives of a substring
        // rule; segment matching keeps them visible.
        val (out, hits) = EnvVarRedactor.redactUnindexed("monkey=banana12 hockeyScore=abcdefg")
        assertTrue(out, out.contains("banana12"))
        assertTrue(out, out.contains("abcdefg"))
        assertEquals(0, hits)
    }

    @Test
    fun `unindexed ignores values shorter than MIN_MATCH_LEN`() {
        // Same floor as value matching: masking "1" would shred normal output.
        val (out, hits) = EnvVarRedactor.redactUnindexed("PWD=abc")
        assertEquals("PWD=abc", out)
        assertEquals(0, hits)
    }

    @Test
    fun `unindexed masks bare provider tokens`() {
        val (out, hits) = EnvVarRedactor.redactUnindexed("curl -H x ghp_abcdefghijklmnop")
        assertFalse(out, out.contains("ghp_abcdefghijklmnop"))
        assertEquals(1, hits)
    }

    @Test
    fun `unindexed does not treat a token prefix inside a word as a token`() {
        // "task-1234567890" contains "sk-" — masking it would corrupt ordinary
        // output, and the boundary check is what prevents that.
        val (out, hits) = EnvVarRedactor.redactUnindexed("task-1234567890")
        assertEquals("task-1234567890", out)
        assertEquals(0, hits)
    }

    @Test
    fun `unindexed masks nothing in clean output and returns it unchanged`() {
        val (out, hits) = EnvVarRedactor.redactUnindexed("total 4 files in /var/minis")
        assertEquals("total 4 files in /var/minis", out)
        assertEquals(0, hits)
    }

    @Test
    fun `unindexed counts each masked span once`() {
        val (out, hits) = EnvVarRedactor.redactUnindexed("API_KEY=aaaaaaaaaa\nDB_TOKEN=bbbbbbbbbb")
        assertEquals(2, hits)
        assertFalse(out, out.contains("aaaaaaaaaa"))
        assertFalse(out, out.contains("bbbbbbbbbb"))
    }

    @Test
    fun `unindexed masking preserves total length`() {
        // Length preservation is what lets the two passes share indices; a
        // variable-length mask would silently drop the tail of the output.
        val input = "API_KEY=abcdefghij and ghp_klmnopqrst"
        val (out, _) = EnvVarRedactor.redactUnindexed(input)
        assertEquals(input.length, out.length)
    }

    @Test
    fun `degraded with reminder says the masking is best-effort`() {
        val (out, hits) = EnvVarRedactor.redactDegradedWithReminder("API_KEY=abcdefghij", emptyList())
        assertEquals(1, hits)
        assertTrue(out.endsWith(EnvVarRedactor.UNINDEXED_SYSTEM_REMINDER))
        // The degraded reminder must not claim the plain "a value was detected"
        // wording — it would read as "this output is now safe".
        assertFalse(out.contains(EnvVarRedactor.SYSTEM_REMINDER))
    }

    @Test
    fun `degraded with reminder adds nothing when neither value nor shape matched`() {
        val (out, hits) = EnvVarRedactor.redactDegradedWithReminder("nothing to see", emptyList())
        assertEquals("nothing to see", out)
        assertEquals(0, hits)
        assertFalse(out.contains(EnvVarRedactor.UNINDEXED_SYSTEM_REMINDER))
    }

    @Test
    fun `degraded keeps value matching for the keys that were recovered`() {
        // A partially recovered index knows SOME values: those are still matched
        // exactly, even when the surrounding text has no secret-shaped name.
        val value = "zqxjkvbwmp"
        val (out, hits) = EnvVarRedactor.redactDegradedWithReminder("prefix $value suffix", listOf(value))
        assertFalse(out, out.contains(value))
        assertEquals(1, hits)
    }
}
