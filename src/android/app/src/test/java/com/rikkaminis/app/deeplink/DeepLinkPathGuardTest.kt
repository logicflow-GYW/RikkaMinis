package com.rikkaminis.app.deeplink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [DeepLinkPathGuard] — the path-traversal guard applied
 * to `minis://session/<id>/<resource-path>` before the resource path reaches
 * `ChatScreen` / `PRootKernel.resolveHostPath`.
 *
 * Note on URI encoding: `DeepLinkHandler.parse` reads `uri.path`, and
 * `android.net.Uri.getPath()` already *decodes* percent-encoding. So a link
 * like `minis://session/x/%2e%2e/a.html` surfaces as the literal resource
 * path `/x/../a.html`, and its `..` segment is what this guard rejects. The
 * tests below exercise that decoded form directly (the guard is a pure
 * string function and has no Android dependency), plus the raw `%2e%2e`
 * case for clarity.
 */
class DeepLinkPathGuardTest {

    // ── dot-segments ──────────────────────────────────────────────────────

    @Test fun `parent traversal segment is unsafe`() {
        assertTrue(DeepLinkPathGuard.isUnsafeSegment(".."))
    }

    @Test fun `current dir segment is unsafe`() {
        assertTrue(DeepLinkPathGuard.isUnsafeSegment("."))
    }

    @Test fun `resource path with dotdot climb is rejected`() {
        // minis://session/x/../../a.html  →  resource path "/x/../../a.html"
        assertTrue(DeepLinkPathGuard.hasUnsafeSegment("/x/../../a.html"))
    }

    @Test fun `encoded dotdot climb resolves to unsafe literal dotdot`() {
        // minis://session/x/%2e%2e/a.html — Uri.getPath() decodes `%2e%2e` → `..`
        assertTrue(DeepLinkPathGuard.hasUnsafeSegment("/x/../a.html"))
    }

    @Test fun `resource path with single dot segment is rejected`() {
        // minis://session/x/./a.html
        assertTrue(DeepLinkPathGuard.hasUnsafeSegment("/x/./a.html"))
    }

    // ── separators ────────────────────────────────────────────────────────

    @Test fun `colon segment is unsafe`() {
        assertTrue(DeepLinkPathGuard.isUnsafeSegment("C:evil"))
    }

    @Test fun `backslash segment is unsafe`() {
        assertTrue(DeepLinkPathGuard.isUnsafeSegment("..\\..\\x"))
        assertTrue(DeepLinkPathGuard.isUnsafeSegment("a\\b"))
    }

    @Test fun `resource path with backslash traversal is rejected`() {
        // Windows-style `..\\..\\x` smuggled into the tail
        assertTrue(DeepLinkPathGuard.hasUnsafeSegment("/x/..\\..\\x"))
    }

    // ── benign paths ──────────────────────────────────────────────────────

    @Test fun `plain filename segment is safe`() {
        assertFalse(DeepLinkPathGuard.isUnsafeSegment("report.html"))
        assertFalse(DeepLinkPathGuard.isUnsafeSegment("snake.html"))
    }

    @Test fun `normal resource path is accepted`() {
        // minis://session/x/report.html
        assertFalse(DeepLinkPathGuard.hasUnsafeSegment("/x/report.html"))
    }

    @Test fun `empty segments from double slash are ignored`() {
        // minis://session/x/a//b.html — the empty segment between `//` is
        // legitimate and must NOT trip the guard (resolver collapses it).
        assertFalse(DeepLinkPathGuard.hasUnsafeSegment("/x/a//b.html"))
    }

    @Test fun `nested normal path is accepted`() {
        assertFalse(DeepLinkPathGuard.hasUnsafeSegment("/x/browser/snake.html"))
    }
}
