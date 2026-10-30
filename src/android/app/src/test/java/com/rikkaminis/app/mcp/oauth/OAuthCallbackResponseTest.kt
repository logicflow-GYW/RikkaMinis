package com.rikkaminis.app.mcp.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-FW-OAUTH-D1/D2] Pure-function half of the callback response fix.
 *
 *  - D2: `callbackDenied` is the single refusal predicate; the page builder is
 *    keyed on it so the page and the termination can never disagree again (the
 *    old split — `!error.isNullOrBlank()` for termination, `error != null` for
 *    the page — left `?error=` showing a failure page while the listener kept
 *    waiting out the full 5-minute timeout).
 *  - D1: `callbackHttpResponse` declares `Content-Length` in UTF-8 **bytes**
 *    (the old code declared `html.length`, a UTF-16 code-unit count, so any
 *    non-ASCII `error` truncated the page) and says `charset=utf-8`.
 *
 * All byte counts below are hand-counted literals, not derived from the
 * implementation. The wire-level half lives in OAuthCallbackServerFdTest.
 */
class OAuthCallbackResponseTest {

    // ── [FIX-FW-OAUTH-D2] the four code×error combinations ─────────────────

    @Test
    fun `callbackDenied keys on error presence, never on blankness`() {
        assertFalse("code only = a grant", callbackDenied("abc", null))
        assertTrue("error only = a refusal", callbackDenied(null, "access_denied"))
        assertTrue("error= (present, empty) is still a refusal", callbackDenied(null, ""))
        assertFalse(
            "code+error together: code wins (RFC 6749 forbids the combo; see report D3)",
            callbackDenied("abc", "access_denied"),
        )
        assertFalse("neither = a stray request, not a refusal", callbackDenied(null, null))
        // An empty code is a PRESENT code — not a refusal. Documented residual.
        assertFalse("empty code is still a present code", callbackDenied("", null))
    }

    @Test
    fun `the page and the predicate agree on every combination`() {
        assertEquals(
            "<html><body><h1>Authorization complete</h1>" +
                "<p>You can close this tab.</p><script>window.close()</script></body></html>",
            callbackPageHtml("abc", null),
        )
        assertTrue(
            callbackPageHtml(null, "access_denied").contains("Authorization was not completed"),
        )
        assertTrue(
            "error= renders the refusal page too (the old split rendered it but never terminated)",
            callbackPageHtml(null, "").contains("Authorization was not completed"),
        )
        assertTrue(
            "both present = success page, matching callbackDenied",
            callbackPageHtml("abc", "access_denied").contains("Authorization complete"),
        )
    }

    @Test
    fun `an empty error renders the no-reason wording, not a hole`() {
        val page = callbackPageHtml(null, "")
        assertTrue(
            page.contains("The authorization was refused by the server, which gave no reason"),
        )
        assertFalse("no dangling 'reported: .'", page.contains("reported:"))
        assertFalse(page.contains(": ."))
    }

    @Test
    fun `a non-empty error still quotes the reason, escaped`() {
        val page = callbackPageHtml(null, "<x>&")
        assertTrue(page.contains("The authorization server reported: &lt;x&gt;&amp;."))
    }

    // ── [FIX-FW-OAUTH-D1] bytes, not code units ─────────────────────────────

    @Test
    fun `content length counts utf-8 bytes, not utf-16 code units`() {
        // "<p>拒绝</p>": 9 chars, 13 UTF-8 bytes (7 ASCII + 2 × 3-byte CJK).
        // Hand-counted, not derived from the implementation.
        val response = callbackHttpResponse("<p>拒绝</p>")
        val text = String(response, Charsets.UTF_8)
        assertTrue(
            "header must declare the byte count and the charset",
            text.startsWith(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: 13\r\nConnection: close\r\n\r\n",
            ),
        )
        val body = text.substringAfter("\r\n\r\n")
        assertEquals("<p>拒绝</p>", body)
        assertEquals(
            "the declared 13 is the byte count",
            13,
            body.toByteArray(Charsets.UTF_8).size,
        )
        assertEquals("9 chars — the old declaration would have said 9", 9, body.length)
    }

    @Test
    fun `the chinese denial page declares its true byte length`() {
        // Page = 193 chars; "拒绝" = 2 chars = 6 UTF-8 bytes ⇒ 197 bytes
        // (191 ASCII + 2 × 3). Hand-counted.
        val page = callbackPageHtml(null, "拒绝")
        val bytes = callbackHttpResponse(page)
        val text = String(bytes, Charsets.UTF_8)
        val declared = Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt()
        assertEquals("197 bytes, not the 193-char count", 197, declared)
        assertNotEquals("193 is the code-unit count — the old bug", 193, declared)
        val headLen = text.indexOf("\r\n\r\n") + 4
        assertEquals("head (ASCII) + declared body = everything written", headLen + 197, bytes.size)
    }

    @Test
    fun `the empty-error page declares its true byte length`() {
        // 221 chars, all ASCII ⇒ 221 bytes. Hand-counted.
        val page = callbackPageHtml(null, "")
        val bytes = callbackHttpResponse(page)
        val declared = Regex("Content-Length: (\\d+)").find(String(bytes, Charsets.UTF_8))!!
            .groupValues[1].toInt()
        assertEquals(221, declared)
    }

    @Test
    fun `the success page is unchanged`() {
        // 118 chars, all ASCII ⇒ 118 bytes. Hand-counted.
        val page = callbackPageHtml(null, null)
        val bytes = callbackHttpResponse(page)
        val declared = Regex("Content-Length: (\\d+)").find(String(bytes, Charsets.UTF_8))!!
            .groupValues[1].toInt()
        assertEquals(118, declared)
    }
}
