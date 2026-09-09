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
}
