package com.rikkaminis.app.mcp.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [r3-B11-F08] `htmlEscape` guards the denial page this loopback listener serves.
 *
 * The `error` / `error_description` of an OAuth redirect are text whichever
 * party answered the redirect URI controls. They are now quoted back to the
 * browser in the "authorization was not completed" page, so they must be
 * escaped — a bare interpolation would serve attacker markup from a loopback
 * origin the Custom Tab still trusts.
 *
 * Pure string work: no Android types, so it runs on the plain JVM.
 */
class OAuthCallbackHtmlEscapeTest {

    @Test
    fun `escapes the five html metacharacters`() {
        assertEquals("&amp;", htmlEscape("&"))
        assertEquals("&lt;", htmlEscape("<"))
        assertEquals("&gt;", htmlEscape(">"))
        assertEquals("&quot;", htmlEscape("\""))
        assertEquals("&#39;", htmlEscape("'"))
    }

    @Test
    fun `ampersand is escaped before the entities it introduces`() {
        // "&lt;" in the INPUT must not survive as a live entity.
        assertEquals("&amp;lt;", htmlEscape("&lt;"))
    }

    @Test
    fun `a script tag is neutralized`() {
        val escaped = htmlEscape("<script>alert(1)</script>")
        assertFalse(escaped.contains("<script>"))
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", escaped)
    }

    @Test
    fun `ordinary error codes pass through unchanged`() {
        assertEquals("access_denied", htmlEscape("access_denied"))
        assertEquals("invalid_scope: read", htmlEscape("invalid_scope: read"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", htmlEscape(""))
    }
}
