package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FE-5 route B tests for the tool-execution layer extracted from
 * ChatViewModel (ChatToolExecutors.kt / ChatShellExecution.kt).
 *
 * JVM-testable surface: wrapForBash wire format, linuxPathToMinisURL, and
 * the streamed-line display buffer. The sub-agent loop these tests used to
 * cover is gone — dispatch is a real session now (see
 * `executeSpawnAgentTool`); the Android-bound executors (browser, shell
 * coordinator, persistBrowserArtifact) are covered by CI's full suite.
 */
class ChatToolExecutorsTest {

    // ── wrapForBash ───────────────────────────────────────────────

    @Test
    fun `wrapForBash guards on command -v bash with sentinel 119`() {
        val w = wrapForBash("echo hi")
        assertTrue(w.startsWith("( command -v bash >/dev/null 2>&1 || exit 119; "))
        assertTrue(w.contains("| base64 -d > /tmp/.minis-exec-\$\$.sh"))
        assertTrue(w.endsWith("exit " + '$' + "rc )"))
    }

    @Test
    fun `wrapForBash encodes script with guaranteed trailing newline`() {
        val w = wrapForBash("echo hi")
        // decoded payload must end with newline — base64 of "echo hi\n"
        val expected = android.util.Base64.encodeToString(
            "echo hi\n".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        assertTrue(w.contains("printf %s '$expected'"))
    }

    @Test
    fun `wrapForBash keeps already-newlined script unchanged payload`() {
        val w = wrapForBash("echo hi\n")
        val expected = android.util.Base64.encodeToString(
            "echo hi\n".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        assertTrue(w.contains("'$expected'"))
    }

    // ── linuxPathToMinisURL ───────────────────────────────────────

    @Test
    fun `browser path maps to minis url`() {
        assertEquals("minis://browser/s.jpg", linuxPathToMinisURL("/var/minis/browser/s.jpg"))
    }

    @Test
    fun `non-minis path returns null and namespaces are preserved`() {
        assertNull(linuxPathToMinisURL("/tmp/x"))
        assertNull(linuxPathToMinisURL("/var/minis/"))
        assertEquals("minis://workspace/f.txt", linuxPathToMinisURL("/var/minis/workspace/f.txt"))
        assertEquals("minis://browser/s.jpg", linuxPathToMinisURL("/var/minis/browser/s.jpg"))
    }

    // ── streamedLinesForDisplay (per-tool accumulation + 50-line window) ──

    @Test
    fun `display buffer accumulates and trims to last 50 lines`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        var last: String? = null
        for (i in 1..60) {
            last = streamedLinesForDisplay("line$i", key)
        }
        assertEquals(50, last!!.lines().size)
        assertEquals("line60", last.lines().last())
        resetDisplayBuffer(key)
    }

    @Test
    fun `display buffer resets between tools`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        streamedLinesForDisplay("a", key)
        resetDisplayBuffer(key)
        val out = streamedLinesForDisplay("b", key)
        assertEquals("b", out)
        resetDisplayBuffer(key)
    }

    @Test
    fun `partial line replaces the previous fragment instead of appending`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        streamedLinesForDisplay("b12: in_progress No", key, isPartial = true)
        // Still the same line growing — one row, not two.
        assertEquals("b12: in_progress No", streamedLinesForDisplay("b12: in_progress No", key, isPartial = true))
        val out = streamedLinesForDisplay("b12: in_progress None", key, isPartial = false)
        assertEquals("b12: in_progress None", out)
        resetDisplayBuffer(key)
    }

    @Test
    fun `partial fragment is superseded by its completed line`() {
        val key = "test-${System.nanoTime()}"
        resetDisplayBuffer(key)
        streamedLinesForDisplay("Wed", key, isPartial = true)
        val out = streamedLinesForDisplay("Wed Sep  9 16:43:51 UTC 2026", key, isPartial = false)
        assertEquals("Wed Sep  9 16:43:51 UTC 2026", out)
        resetDisplayBuffer(key)
    }
}
