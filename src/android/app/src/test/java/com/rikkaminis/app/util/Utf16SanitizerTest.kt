package com.rikkaminis.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [backlog #1] Lone-surrogate sanitizer.
 *
 * Pins the contract the two call sites rely on: clean text comes back as the
 * SAME instance (the fast path must not allocate), lone surrogates become
 * U+FFFD, and legitimate pairs — including astral-plane characters such as
 * emoji and CJK extension B — survive untouched.
 */
class Utf16SanitizerTest {

    private val fffd = '\uFFFD'

    // ── fast path ──────────────────────────────────────────────────────────

    @Test fun `clean text returns the same instance`() {
        val s = "hello world"
        assertSame(s, Utf16Sanitizer.sanitize(s))
    }

    @Test fun `astral pair is not touched`() {
        val s = "a\uD83D\uDE00b"          // a 😀 b
        assertSame(s, Utf16Sanitizer.sanitize(s))
        assertFalse(Utf16Sanitizer.hasLoneSurrogate(s))
    }

    @Test fun `empty text returns the same instance`() {
        val s = ""
        assertSame(s, Utf16Sanitizer.sanitize(s))
        assertFalse(Utf16Sanitizer.hasLoneSurrogate(s))
    }

    // ── lone high surrogate ────────────────────────────────────────────────

    @Test fun `lone high surrogate becomes U+FFFD`() {
        assertEquals("a${fffd}b", Utf16Sanitizer.sanitize("a\uD800b"))
    }

    @Test fun `trailing high surrogate becomes U+FFFD`() {
        assertEquals("ab$fffd", Utf16Sanitizer.sanitize("ab\uD800"))
    }

    @Test fun `two consecutive high surrogates both become U+FFFD`() {
        assertEquals("$fffd$fffd", Utf16Sanitizer.sanitize("\uD800\uD800"))
    }

    // ── lone low surrogate ─────────────────────────────────────────────────

    @Test fun `lone low surrogate becomes U+FFFD`() {
        assertEquals("a${fffd}b", Utf16Sanitizer.sanitize("a\uDC00b"))
    }

    @Test fun `leading low surrogate becomes U+FFFD`() {
        assertEquals("${fffd}ab", Utf16Sanitizer.sanitize("\uDC00ab"))
    }

    // ── mixed ──────────────────────────────────────────────────────────────

    @Test fun `pair plus lone surrogate keeps the pair`() {
        val s = "\uD83D\uDE00\uD800"
        assertEquals("\uD83D\uDE00$fffd", Utf16Sanitizer.sanitize(s))
    }

    @Test fun `probe agrees with sanitize on whether anything changed`() {
        val cases = listOf(
            "clean", "", "a\uD800", "\uDC00", "\uD83D\uDE00", "x\uD800y\uDC00z",
        )
        for (c in cases) {
            val changed = Utf16Sanitizer.sanitize(c) !== c
            assertEquals("probe mismatch for ${c.length} chars", changed, Utf16Sanitizer.hasLoneSurrogate(c))
        }
    }

    @Test fun `length is preserved per replaced code unit`() {
        // one lone surrogate -> exactly one U+FFFD (unlike a DELETE strategy,
        // which would shorten the string and shift every index after it)
        assertEquals(3, Utf16Sanitizer.sanitize("a\uD800b").length)
    }

    @Test fun `sanitize is idempotent`() {
        val once = Utf16Sanitizer.sanitize("a\uD800b\uDC00\uD83D\uDE00")
        assertSame(once, Utf16Sanitizer.sanitize(once))
    }

    @Test fun `surrogate range boundaries`() {
        assertTrue(Utf16Sanitizer.hasLoneSurrogate("\uD7FF\uD800"))   // just below high range
        assertFalse(Utf16Sanitizer.hasLoneSurrogate("\uD7FF\uE000")) // just below + just above
    }
}
