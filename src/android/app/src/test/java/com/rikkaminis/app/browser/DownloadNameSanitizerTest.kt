package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [BrowserTabPool.sanitizeDownloadName] — the RC11
 * download-filename guard. Page-controlled download names must never be able
 * to smuggle a path component out of the session workspace directory.
 */
class DownloadNameSanitizerTest {

    // ── Accepted — plain, safe names pass through ─────────────────────────

    @Test fun `plain filename passes through unchanged`() {
        assertEquals("report.html", BrowserTabPool.sanitizeDownloadName("report.html"))
        assertEquals("report.pdf", BrowserTabPool.sanitizeDownloadName("  report.pdf  "))
    }

    @Test fun `normal filename with spaces and unicode passes`() {
        assertEquals("我的 文档.pdf", BrowserTabPool.sanitizeDownloadName("我的 文档.pdf"))
        assertEquals("my file (2).png", BrowserTabPool.sanitizeDownloadName("my file (2).png"))
    }

    @Test fun `trimmed surrounding whitespace returns trimmed value`() {
        assertEquals("report.html", BrowserTabPool.sanitizeDownloadName("  report.html  "))
    }

    @Test fun `200 character name passes`() {
        val name = "a".repeat(200)
        assertEquals(name, BrowserTabPool.sanitizeDownloadName(name))
    }

    // ── Rejected — path traversal / separators / NUL ──────────────────────

    @Test fun `traversal with forward slash is rejected`() {
        assertNull(BrowserTabPool.sanitizeDownloadName("../../x"))
        assertNull(BrowserTabPool.sanitizeDownloadName("a/../b"))
        assertNull(BrowserTabPool.sanitizeDownloadName("/abs"))
        assertNull(BrowserTabPool.sanitizeDownloadName("sub/dir.txt"))
    }

    @Test fun `traversal with backslash is rejected`() {
        assertNull(BrowserTabPool.sanitizeDownloadName("..\\..\\x"))
        assertNull(BrowserTabPool.sanitizeDownloadName("C:\\windows\\system32"))
    }

    @Test fun `NUL byte is rejected`() {
        assertNull(BrowserTabPool.sanitizeDownloadName("a\u0000b.txt"))
    }

    // ── Rejected — empty / too long ───────────────────────────────────────

    @Test fun `empty and blank names are rejected`() {
        assertNull(BrowserTabPool.sanitizeDownloadName(""))
        assertNull(BrowserTabPool.sanitizeDownloadName("   "))
    }

    @Test fun `201 character name is rejected`() {
        assertNull(BrowserTabPool.sanitizeDownloadName("a".repeat(201)))
    }

    @Test fun `trailing slash is rejected as a directory`() {
        assertNull(BrowserTabPool.sanitizeDownloadName("report.html/"))
    }
}
