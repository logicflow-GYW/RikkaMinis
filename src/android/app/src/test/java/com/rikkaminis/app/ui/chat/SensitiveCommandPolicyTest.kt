package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-sensitive-transcript] The android-* helpers are reached through
 * `shell_execute`, so provenance lives only in the command line and only while
 * the call is in flight (ToolResult carries no command field). This policy is
 * therefore the single gate deciding what survives into the saved transcript,
 * and both directions matter: a missed clipboard dump leaks a credential,
 * while a false positive withholds an ordinary answer from the user's own
 * history.
 */
class SensitiveCommandPolicyTest {

    @Test
    fun `a bare clipboard call is redacted`() {
        assertTrue(SensitiveCommandPolicy.shouldRedactFromTranscript("android-clipboard get"))
    }

    @Test
    fun `a bare speech call is redacted`() {
        assertTrue(SensitiveCommandPolicy.shouldRedactFromTranscript("android-speech listen --max 3"))
    }

    @Test
    fun `an absolute path is still recognised`() {
        assertTrue(SensitiveCommandPolicy.shouldRedactFromTranscript("/usr/local/bin/android-clipboard get"))
    }

    @Test
    fun `chaining and shell wrapping do not hide the helper`() {
        assertTrue(SensitiveCommandPolicy.shouldRedactFromTranscript("cd /tmp && android-clipboard set hello"))
        assertTrue(SensitiveCommandPolicy.shouldRedactFromTranscript("sh -c 'android-clipboard get'"))
    }

    @Test
    fun `ordinary commands are not touched`() {
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("ls -la /var/minis"))
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("android-weather 1.3 103.8"))
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("android-device info"))
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("android-speak hello"))
    }

    @Test
    fun `a longer name that merely starts with the helper is not a match`() {
        // Token equality, not prefix: android-clipboard-helper is a different
        // program and must not force a redaction.
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("android-clipboard-helper get"))
    }

    @Test
    fun `missing or blank commands are not redacted`() {
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript(null))
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript(""))
        assertFalse(SensitiveCommandPolicy.shouldRedactFromTranscript("   "))
    }

    @Test
    fun `the placeholder names the helper so a reader knows it was withheld`() {
        assertEquals(
            "[android-clipboard output withheld from the saved transcript]",
            SensitiveCommandPolicy.redactionPlaceholder("android-clipboard get"),
        )
        assertEquals(
            "[android-speech output withheld from the saved transcript]",
            SensitiveCommandPolicy.redactionPlaceholder("android-speech listen"),
        )
    }

    @Test
    fun `the placeholder still reads sensibly when the command is unavailable`() {
        val fallback = SensitiveCommandPolicy.redactionPlaceholder(null)
        assertTrue("got: $fallback", fallback.contains("withheld"))
        assertFalse("got: $fallback", fallback.contains("null"))
    }
}
