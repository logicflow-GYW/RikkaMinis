package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure functions extracted from [PersistentShell].
 *
 * [PersistentShell] itself depends on Android (Context, ProcessBuilder, etc.),
 * so these tests focus on the top-level [internalParseMinisExitCode] and
 * [internalTruncateOutput] functions extracted for JVM testability.
 *
 * Android-only behaviour (shell process lifecycle, PTY I/O, heredoc injection,
 * timeout handling, memory monitoring) is covered by the instrumented tests
 * in the androidTest source set.
 */
class PersistentShellTest {

    // ── internalParseMinisExitCode ────────────────────────────────────

    @Test
    fun `parseExitCode returns code for valid marker`() {
        assertEquals(0, internalParseMinisExitCode("__MINIS_DONE_abc123_EXIT_0__done", "abc123"))
    }

    @Test
    fun `parseExitCode returns non-zero code`() {
        assertEquals(127, internalParseMinisExitCode("__MINIS_DONE_xyz_EXIT_127__", "xyz"))
    }

    @Test
    fun `parseExitCode returns -1 when marker not found`() {
        assertEquals(-1, internalParseMinisExitCode("no marker here", "abc"))
    }

    @Test
    fun `parseExitCode returns -1 when exit code is not numeric`() {
        assertEquals(-1, internalParseMinisExitCode("__MINIS_DONE_m1_EXIT_abc__", "m1"))
    }

    @Test
    fun `parseExitCode returns -1 for empty text`() {
        assertEquals(-1, internalParseMinisExitCode("", "marker"))
    }

    @Test
    fun `parseExitCode handles marker at end of string`() {
        assertEquals(0, internalParseMinisExitCode("output text__MINIS_DONE_m_EXIT_0__", "m"))
    }

    @Test
    fun `parseExitCode handles marker with special regex chars`() {
        // Marker contains characters that would be special in a regex
        assertEquals(42, internalParseMinisExitCode("__MINIS_DONE_a.b+c_EXIT_42__", "a.b+c"))
    }

    @Test
    fun `parseExitCode handles multiple exit codes`() {
        // Only the first match should be returned
        val text = "__MINIS_DONE_m_EXIT_0__ __MINIS_DONE_m_EXIT_1__"
        assertEquals(0, internalParseMinisExitCode(text, "m"))
    }

    @Test
    fun `parseExitCode handles large exit code`() {
        assertEquals(255, internalParseMinisExitCode("__MINIS_DONE_m_EXIT_255__", "m"))
    }

    @Test
    fun `parseExitCode handles marker with hyphens and underscores`() {
        assertEquals(1, internalParseMinisExitCode("__MINIS_DONE_my-marker_1_EXIT_1__", "my-marker_1"))
    }

    @Test
    fun `parseExitCode differentiates markers with similar prefixes`() {
        val text = "pre__MINIS_DONE_abc_EXIT_0__mid__MINIS_DONE_abcdef_EXIT_1__"
        // When searching for "abc", the first match should be _EXIT_0__
        assertEquals(0, internalParseMinisExitCode(text, "abc"))
    }

    // ── internalTruncateOutput ────────────────────────────────────────

    @Test
    fun `truncateOutput appends text within limit`() {
        val sb = StringBuilder()
        assertFalse(internalTruncateOutput(sb, "hello", 100))
        assertEquals("hello", sb.toString())
    }

    @Test
    fun `truncateOutput returns true when text exceeds limit`() {
        val sb = StringBuilder()
        assertTrue(internalTruncateOutput(sb, "hello world", 5))
        assertEquals("hello", sb.toString())
    }

    @Test
    fun `truncateOutput appends to existing content`() {
        val sb = StringBuilder("prefix_")
        assertFalse(internalTruncateOutput(sb, "suffix", 100))
        assertEquals("prefix_suffix", sb.toString())
    }

    @Test
    fun `truncateOutput returns true when total exceeds limit`() {
        val sb = StringBuilder("abcdefghij")
        assertTrue(internalTruncateOutput(sb, "klmnopqrst", 15))
        assertEquals("abcdefghijklmno", sb.toString())
    }

    @Test
    fun `truncateOutput returns true when already at limit`() {
        val sb = StringBuilder("already full")
        assertTrue(internalTruncateOutput(sb, " more", 12))
        assertEquals("already full", sb.toString())
    }

    @Test
    fun `truncateOutput returns true when already over limit`() {
        val sb = StringBuilder("over the limit already")
        assertTrue(internalTruncateOutput(sb, "anything", 5))
        assertEquals("over the limit already", sb.toString())
    }

    @Test
    fun `truncateOutput handles empty text`() {
        val sb = StringBuilder("existing")
        assertFalse(internalTruncateOutput(sb, "", 100))
        assertEquals("existing", sb.toString())
    }

    @Test
    fun `truncateOutput handles empty string builder`() {
        val sb = StringBuilder()
        assertFalse(internalTruncateOutput(sb, "", 100))
        assertEquals("", sb.toString())
    }

    @Test
    fun `truncateOutput handles exact fit`() {
        val sb = StringBuilder("12345")
        assertFalse(internalTruncateOutput(sb, "67890", 10))
        assertEquals("1234567890", sb.toString())
    }

    @Test
    fun `truncateOutput appends full text when remaining equals text length`() {
        // 9 existing + 1 new = 10, exactly at limit → no truncation
        val sb = StringBuilder("123456789")
        assertFalse(internalTruncateOutput(sb, "A", 10))
        assertEquals("123456789A", sb.toString())
    }

    @Test
    fun `truncateOutput handles two chars over limit`() {
        val sb = StringBuilder("123456789")
        assertTrue(internalTruncateOutput(sb, "AB", 10))
        // 9 existing + 2 new = 11, over limit → append only 1 char
        assertEquals("123456789A", sb.toString())
    }

    @Test
    fun `truncateOutput handles large text over limit`() {
        val sb = StringBuilder("A")
        val large = "B".repeat(1000)
        assertTrue(internalTruncateOutput(sb, large, 50))
        assertEquals("A" + "B".repeat(49), sb.toString())
    }

    @Test
    fun `truncateOutput handles zero max chars`() {
        val sb = StringBuilder()
        assertTrue(internalTruncateOutput(sb, "anything", 0))
        assertEquals("", sb.toString())
    }

    @Test
    fun `truncateOutput handles zero max chars with existing content`() {
        val sb = StringBuilder("existing")
        assertTrue(internalTruncateOutput(sb, "more", 0))
        assertEquals("existing", sb.toString())
    }

    @Test
    fun `truncateOutput handles unicode text`() {
        val sb = StringBuilder()
        assertTrue(internalTruncateOutput(sb, "你好世界", 2))
        assertEquals("你好", sb.toString())
    }

    @Test
    fun `truncateOutput handles emoji correctly`() {
        // Each emoji is multiple bytes (🔥 is 4 bytes in UTF-8) but
        // StringBuilder.length() counts chars, not bytes. In Kotlin/JVM
        // String.length() returns the number of UTF-16 code units.
        // Emoji outside BMP (🔥) is 2 chars (surrogate pair), so "🔥🔥" = 4 chars
        val sb = StringBuilder()
        assertTrue(internalTruncateOutput(sb, "🔥🔥🔥", 4))
        // Should append exactly 4 chars = 2 fire emoji
        assertEquals("🔥🔥", sb.toString())
    }

    @Test
    fun `truncateOutput multiple appends accumulate`() {
        val sb = StringBuilder()
        assertFalse(internalTruncateOutput(sb, "one_", 20))
        assertFalse(internalTruncateOutput(sb, "two_", 20))
        assertFalse(internalTruncateOutput(sb, "three", 20))
        assertEquals("one_two_three", sb.toString())
    }

    @Test
    fun `truncateOutput multiple appends eventually truncate`() {
        val sb = StringBuilder()
        assertFalse(internalTruncateOutput(sb, "12345", 10))
        assertFalse(internalTruncateOutput(sb, "67890", 10))
        assertTrue(internalTruncateOutput(sb, "ABCDE", 10))
        assertEquals("1234567890", sb.toString())
    }

    // ── internalScanMarker ────────────────────────────────────────────

    @Test
    fun `scanMarker hits when marker fully inside one chunk`() {
        val r = internalScanMarker("", "hello\n__MINIS_DONE_abc123_EXIT_0__done", "abc123")
        assertTrue(r.hit)
        assertEquals("hello\n", r.beforeMarker)
        assertEquals(0, r.exitCode)
        assertEquals("", r.keptTail)
    }

    @Test
    fun `scanMarker hits when marker split across chunks`() {
        // marker 前 20 字符在 tail，剩余在 text
        val marker = "__MINIS_DONE_abc123_EXIT_0__"
        val r = internalScanMarker(marker.substring(0, 20), marker.substring(20) + "tail", "abc123")
        assertTrue(r.hit)
        assertEquals(0, r.exitCode)
        assertEquals("", r.keptTail)
    }

    @Test
    fun `scanMarker hits when only one char left in tail`() {
        val marker = "__MINIS_DONE_abc123_EXIT_0__"
        val r = internalScanMarker(marker.take(1), marker.drop(1), "abc123")
        assertTrue(r.hit)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `scanMarker hits when split point is before id in text`() {
        // tail 只有前缀 "__MINIS_DONE_"，其余全在 text
        val marker = "__MINIS_DONE_abc123_EXIT_0__"
        val r = internalScanMarker("__MINIS_DONE_", marker.removePrefix("__MINIS_DONE_"), "abc123")
        assertTrue(r.hit)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `scanMarker miss keeps only trailing window`() {
        val out = "x".repeat(5000)
        val r = internalScanMarker("", out, "abc123")
        assertFalse(r.hit)
        // 保留尾部 = markerPattern.length - 1
        assertEquals("__MINIS_DONE_abc123_EXIT_".length - 1, r.keptTail.length)
        assertEquals(out.dropLast(r.keptTail.length), r.beforeMarker)
        assertEquals(-1, r.exitCode)
    }

    @Test
    fun `scanMarker miss with short combined keeps everything in tail`() {
        val r = internalScanMarker("", "hi", "abc123")
        assertFalse(r.hit)
        assertEquals("", r.beforeMarker)
        assertEquals("hi", r.keptTail)
    }

    @Test
    fun `scanMarker empty tail and empty text`() {
        val r = internalScanMarker("", "", "abc123")
        assertFalse(r.hit)
        assertEquals("", r.beforeMarker)
        assertEquals("", r.keptTail)
    }

    @Test
    fun `scanMarker marker at very start of text`() {
        val r = internalScanMarker("", "__MINIS_DONE_abc123_EXIT_1__rest", "abc123")
        assertTrue(r.hit)
        assertEquals("", r.beforeMarker)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `scanMarker exit code non-zero`() {
        val r = internalScanMarker("", "out__MINIS_DONE_abc123_EXIT_127__", "abc123")
        assertTrue(r.hit)
        assertEquals(127, r.exitCode)
    }

    @Test
    fun `scanMarker tail plus text still misses when no marker`() {
        // tail 里含 marker 前缀但不完整，text 也不补全 → 仍 miss
        val r = internalScanMarker("__MINIS_DONE_ab", "more output no marker", "abc123")
        assertFalse(r.hit)
        assertEquals(-1, r.exitCode)
    }

    @Test
    fun `scanMarker accumulated tail eventually completes marker`() {
        // 模拟多段拼接：前面的 chunk 把 marker 前缀保留在 tail 里，
        // 最后一段补全剩余部分才 hit，flushed 只含真实输出。
        val marker = "__MINIS_DONE_abc123_EXIT_42__"
        val mid = marker.length / 2
        val chunks = listOf("output-part1 ", "partial ", marker.substring(0, mid), marker.substring(mid))
        var tail = ""
        val flushed = StringBuilder()
        var finalResult: MarkerScanResult? = null
        for (chunk in chunks) {
            val r = internalScanMarker(tail, chunk, "abc123")
            flushed.append(r.beforeMarker)
            if (r.hit) { finalResult = r; break }
            tail = r.keptTail
        }
        assertNotNull(finalResult)
        assertEquals(42, finalResult?.exitCode)
        assertEquals("output-part1 partial ", flushed.toString())
    }
}