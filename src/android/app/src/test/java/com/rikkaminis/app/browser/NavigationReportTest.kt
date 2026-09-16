package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-0916b] JVM tests for [navigationReport] — the outcome-to-result mapping
 * that replaces navigate()'s constant `success = true` / "Navigated to $url".
 *
 * Ground truth for the defect: 2026-09-16 10:40:55 Chromium logged
 * `Refusing to load for invalid virtual URL: https://about:blank/` (neither
 * onPageFinished nor onReceivedError fires for a refused load), and 10:41:26 the
 * tool result was `success=true output=Navigated to about:blank` after the full
 * navigation timeout had been burned.
 */
class NavigationReportTest {

    private val details = "  Title: Example\n  Viewport: 960x540"

    // ── LOADED keeps the historical text byte-for-byte ─────────────────────

    @Test fun `loaded keeps the legacy text and reports success`() {
        val r = navigationReport(NavigationOutcome.LOADED, "https://x.com", details)
        assertEquals("Navigated to https://x.com\n$details", r.text)
        assertTrue(r.success)
    }

    @Test fun `loaded ignores stale error and routed fields`() {
        val r = navigationReport(
            NavigationOutcome.LOADED, "https://x.com", details,
            errorDescription = "net::ERR_FAILED", routedTarget = "tel:1", timeoutSec = 30,
        )
        assertEquals("Navigated to https://x.com\n$details", r.text)
        assertTrue(r.success)
    }

    // ── the §20b case: a refused load that never calls back ────────────────

    @Test fun `timed out about blank is a failure, not a navigation`() {
        val r = navigationReport(
            NavigationOutcome.TIMED_OUT, "about:blank", details, timeoutSec = 30,
        )
        assertFalse("the refused load must not be reported as success", r.success)
        assertTrue(r.text.contains("about:blank"))
        assertTrue(r.text.contains("did not complete"))
        assertTrue("the timeout budget is stated", r.text.contains("within 30s"))
        assertTrue("metadata still appended", r.text.endsWith(details))
    }

    @Test fun `timed out without a budget still reads cleanly`() {
        val r = navigationReport(NavigationOutcome.TIMED_OUT, "https://slow.example", details)
        assertFalse(r.success)
        assertFalse("no dangling 'within ' clause", r.text.contains("within"))
        assertTrue(r.text.contains("https://slow.example"))
    }

    @Test fun `timed out never claims the page was navigated to`() {
        val r = navigationReport(NavigationOutcome.TIMED_OUT, "https://x.com", details, timeoutSec = 30)
        assertFalse(
            "must not open with the success sentence",
            r.text.startsWith("Navigated to"),
        )
        assertTrue(r.text.startsWith("Error:"))
    }

    // ── main-frame load failure ────────────────────────────────────────────

    @Test fun `failed carries the webview description and is an error`() {
        val r = navigationReport(
            NavigationOutcome.FAILED, "https://nope.invalid", details,
            errorDescription = "net::ERR_NAME_NOT_RESOLVED",
        )
        assertFalse(r.success)
        assertTrue(r.text.contains("net::ERR_NAME_NOT_RESOLVED"))
        assertTrue(r.text.contains("https://nope.invalid"))
        assertTrue(r.text.contains("did not load"))
        assertTrue(r.text.endsWith(details))
    }

    @Test fun `failed tolerates a blank description`() {
        val r = navigationReport(
            NavigationOutcome.FAILED, "https://x.com", details, errorDescription = "   ",
        )
        assertFalse(r.success)
        assertFalse("no empty em-dash clause", r.text.contains("— ."))
        assertFalse(r.text.contains("  ."))
    }

    @Test fun `failed tolerates a null description`() {
        val r = navigationReport(NavigationOutcome.FAILED, "https://x.com", details)
        assertFalse(r.success)
        assertTrue(r.text.contains("navigation to https://x.com failed"))
    }

    // ── handed to another app ──────────────────────────────────────────────

    @Test fun `routed externally succeeds but says the page did not change`() {
        val r = navigationReport(
            NavigationOutcome.ROUTED_EXTERNALLY, "https://x.com", details,
            routedTarget = "tel:+123",
        )
        assertTrue("the routing itself worked", r.success)
        assertTrue(r.text.contains("tel:+123"))
        assertTrue(r.text.contains("did not change"))
        assertFalse(r.text.startsWith("Navigated to"))
        assertTrue(r.text.endsWith(details))
    }

    @Test fun `routed externally falls back to the requested url`() {
        val r = navigationReport(
            NavigationOutcome.ROUTED_EXTERNALLY, "mailto:a@b.c", details, routedTarget = null,
        )
        assertTrue(r.success)
        assertTrue(r.text.contains("mailto:a@b.c"))
    }

    // ── every non-loaded outcome is distinguishable ────────────────────────

    @Test fun `only loaded produces the legacy sentence`() {
        val outcomes = NavigationOutcome.entries.filter { it != NavigationOutcome.LOADED }
        for (o in outcomes) {
            val r = navigationReport(o, "https://x.com", details, timeoutSec = 30)
            assertFalse("$o must not reuse the loaded text", r.text.startsWith("Navigated to"))
        }
    }

    @Test fun `only loaded and routed report success`() {
        val expectedSuccess = setOf(
            NavigationOutcome.LOADED, NavigationOutcome.ROUTED_EXTERNALLY,
        )
        for (o in NavigationOutcome.entries) {
            val r = navigationReport(o, "https://x.com", details)
            assertEquals("$o success flag", o in expectedSuccess, r.success)
        }
    }

    @Test fun `every outcome appends the live page metadata`() {
        for (o in NavigationOutcome.entries) {
            val r = navigationReport(o, "https://x.com", details)
            assertTrue("$o must carry metadata", r.text.endsWith(details))
        }
    }

    @Test fun `outcome set is exactly the four reachable ones`() {
        // PENDING was removed from the enum: after await() every completion path
        // stamps a real outcome, so an "unknown" value would be dead code that
        // only invites a wrong default.
        assertEquals(
            setOf("LOADED", "FAILED", "TIMED_OUT", "ROUTED_EXTERNALLY"),
            NavigationOutcome.entries.map { it.name }.toSet(),
        )
    }

    @Test fun `navigationResult defaults are inert`() {
        val r = NavigationResult(NavigationOutcome.LOADED)
        assertEquals(NavigationOutcome.LOADED, r.outcome)
        assertEquals(null, r.errorDescription)
        assertEquals(null, r.routedTarget)
    }

    // ── navigationReportedUrl ──────────────────────────────────────────────

    @Test fun `loaded names where the page ended up`() {
        // Redirect case: requested /a, landed on /b. The historical behavior
        // (and the useful one) is to report where the page actually is.
        assertEquals(
            "https://x.com/b",
            navigationReportedUrl(NavigationOutcome.LOADED, "https://x.com/b", "https://x.com/a"),
        )
    }

    @Test fun `failed names the requested url, not the stale current one`() {
        // A failed load leaves _currentURL on the PREVIOUS page — echoing that
        // would blame a URL that was never attempted.
        for (o in listOf(
            NavigationOutcome.FAILED, NavigationOutcome.TIMED_OUT,
            NavigationOutcome.ROUTED_EXTERNALLY,
        )) {
            assertEquals(
                "$o must report the requested url",
                "https://new.example",
                navigationReportedUrl(o, "https://previous.example", "https://new.example"),
            )
        }
    }

    @Test fun `navigationReportedUrl is idempotent when nothing changed`() {
        assertEquals(
            "https://x.com",
            navigationReportedUrl(NavigationOutcome.TIMED_OUT, "https://x.com", "https://x.com"),
        )
    }
}
