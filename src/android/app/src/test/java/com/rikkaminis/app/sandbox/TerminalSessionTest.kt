package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure string-logic layer extracted from [TerminalSession].
 *
 * [TerminalSession] itself depends on Android (Context, Handler, Termux JNI),
 * so these tests focus on the top-level [internalNormalizeLineEndings] function
 * that was extracted for JVM testability.
 *
 * Android-only behaviour (killTermuxProcessTree, termuxShellPid reflection,
 * start/stop lifecycle, PTY I/O) is covered by the instrumented tests in
 * the androidTest source set.
 */
class TerminalSessionTest {

    // ── internalNormalizeLineEndings ──────────────────────────────────

    @Test
    fun `normalizeLineEndings returns empty string for empty input`() {
        assertEquals("", internalNormalizeLineEndings(""))
    }

    @Test
    fun `normalizeLineEndings returns plain text unchanged`() {
        assertEquals("hello world", internalNormalizeLineEndings("hello world"))
    }

    @Test
    fun `normalizeLineEndings converts LF to CR`() {
        assertEquals("line1\rline2\rline3", internalNormalizeLineEndings("line1\nline2\nline3"))
    }

    @Test
    fun `normalizeLineEndings passes bare CR through`() {
        // Bare CR is passed through as-is (the TTY's ICRNL will map it to LF)
        assertEquals("line1\rline2", internalNormalizeLineEndings("line1\rline2"))
    }

    @Test
    fun `normalizeLineEndings collapses CRLF to single CR`() {
        assertEquals("line1\rline2", internalNormalizeLineEndings("line1\r\nline2"))
    }

    @Test
    fun `normalizeLineEndings handles mixed line endings`() {
        // CR + LF, bare LF, bare CR, CRLF, LF
        val input = "a\r\nb\nc\rd\r\ne\nf"
        val expected = "a\rb\rc\rd\re\rf"
        assertEquals(expected, internalNormalizeLineEndings(input))
    }

    @Test
    fun `normalizeLineEndings handles trailing CRLF`() {
        assertEquals("hello\r", internalNormalizeLineEndings("hello\r\n"))
    }

    @Test
    fun `normalizeLineEndings handles trailing LF`() {
        assertEquals("hello\r", internalNormalizeLineEndings("hello\n"))
    }

    @Test
    fun `normalizeLineEndings handles trailing CR`() {
        assertEquals("hello\r", internalNormalizeLineEndings("hello\r"))
    }

    @Test
    fun `normalizeLineEndings handles consecutive LFs`() {
        assertEquals("a\r\r\rb", internalNormalizeLineEndings("a\n\n\nb"))
    }

    @Test
    fun `normalizeLineEndings handles consecutive CRLFs`() {
        assertEquals("a\r\r\rb", internalNormalizeLineEndings("a\r\n\r\n\r\nb"))
    }

    @Test
    fun `normalizeLineEndings handles unicode text`() {
        val input = "你好\n世界\r\n测试"
        assertEquals("你好\r世界\r测试", internalNormalizeLineEndings(input))
    }

    @Test
    fun `normalizeLineEndings handles emoji`() {
        val input = "emoji🔥\nnext🔥\r\nend"
        assertEquals("emoji🔥\rnext🔥\rend", internalNormalizeLineEndings(input))
    }

    @Test
    fun `normalizeLineEndings returns same value for no-newline input`() {
        val input = "a string with no newlines at all"
        assertEquals(input, internalNormalizeLineEndings(input))
    }

    @Test
    fun `normalizeLineEndings handles single character`() {
        assertEquals("a", internalNormalizeLineEndings("a"))
        assertEquals("\r", internalNormalizeLineEndings("\n"))
        assertEquals("\r", internalNormalizeLineEndings("\r"))
    }

    @Test
    fun `normalizeLineEndings handles CR followed by CRLF`() {
        // "\r\r\n" = bare CR, then CRLF → should become "\r\r"
        assertEquals("\r\r", internalNormalizeLineEndings("\r\r\n"))
    }

    @Test
    fun `normalizeLineEndings handles very long string`() {
        val input = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\nl\nm\nn\no\np\nq\nr\ns\nt\nu\nv\nw\nx\ny\nz"
        val expected = "a\rb\rc\rd\re\rf\rg\rh\ri\rj\rk\rl\rm\rn\ro\rp\rq\rr\rs\rt\ru\rv\rw\rx\ry\rz"
        assertEquals(expected, internalNormalizeLineEndings(input))
    }

    @Test
    fun `normalizeLineEndings handles all-CRLF input`() {
        val input = "a\r\nb\r\nc\r\n"
        assertEquals("a\rb\rc\r", internalNormalizeLineEndings(input))
    }
}