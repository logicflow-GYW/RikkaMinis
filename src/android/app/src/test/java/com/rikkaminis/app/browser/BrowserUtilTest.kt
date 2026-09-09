package com.rikkaminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for [BrowserUtil] pure functions.
 */
class BrowserUtilTest {

    // ── guessMimeType ──────────────────────────────────────────────────────

    @Test fun `guessMimeType html extension`() {
        assertEquals("text/html", guessMimeType("index.html"))
        assertEquals("text/html", guessMimeType("index.htm"))
    }

    @Test fun `guessMimeType css`() {
        assertEquals("text/css", guessMimeType("style.css"))
    }

    @Test fun `guessMimeType js`() {
        assertEquals("application/javascript", guessMimeType("script.js"))
    }

    @Test fun `guessMimeType json`() {
        assertEquals("application/json", guessMimeType("data.json"))
    }

    @Test fun `guessMimeType image types`() {
        assertEquals("image/png", guessMimeType("image.png"))
        assertEquals("image/jpeg", guessMimeType("photo.jpg"))
        assertEquals("image/jpeg", guessMimeType("photo.jpeg"))
        assertEquals("image/gif", guessMimeType("anim.gif"))
        assertEquals("image/svg+xml", guessMimeType("icon.svg"))
        assertEquals("image/webp", guessMimeType("pic.webp"))
    }

    @Test fun `guessMimeType video audio`() {
        assertEquals("video/mp4", guessMimeType("video.mp4"))
        assertEquals("audio/mpeg", guessMimeType("song.mp3"))
    }

    @Test fun `guessMimeType pdf`() {
        assertEquals("application/pdf", guessMimeType("doc.pdf"))
    }

    @Test fun `guessMimeType text`() {
        assertEquals("text/plain", guessMimeType("readme.txt"))
        assertEquals("text/plain", guessMimeType("readme.md"))
    }

    @Test fun `guessMimeType xml`() {
        assertEquals("text/xml", guessMimeType("data.xml"))
    }

    @Test fun `guessMimeType unknown extension`() {
        assertEquals("application/octet-stream", guessMimeType("file.xyz"))
    }

    @Test fun `guessMimeType no extension`() {
        assertEquals("application/octet-stream", guessMimeType("README"))
    }

    @Test fun `guessMimeType case insensitive`() {
        assertEquals("text/html", guessMimeType("index.HTML"))
        assertEquals("image/png", guessMimeType("image.PNG"))
    }

    @Test fun `guessMimeType empty string`() {
        assertEquals("application/octet-stream", guessMimeType(""))
    }

    // ── extensionForMimeType ───────────────────────────────────────────────

    @Test fun `extensionForMimeType html`() {
        assertEquals("html", extensionForMimeType("text/html"))
    }

    @Test fun `extensionForMimeType plain text`() {
        assertEquals("txt", extensionForMimeType("text/plain"))
    }

    @Test fun `extensionForMimeType json`() {
        assertEquals("json", extensionForMimeType("application/json"))
    }

    @Test fun `extensionForMimeType image types`() {
        assertEquals("png", extensionForMimeType("image/png"))
        assertEquals("jpg", extensionForMimeType("image/jpeg"))
        assertEquals("gif", extensionForMimeType("image/gif"))
        assertEquals("webp", extensionForMimeType("image/webp"))
        assertEquals("svg", extensionForMimeType("image/svg+xml"))
    }

    @Test fun `extensionForMimeType pdf`() {
        assertEquals("pdf", extensionForMimeType("application/pdf"))
    }

    @Test fun `extensionForMimeType unknown mime`() {
        assertEquals("bin", extensionForMimeType("application/x-unknown"))
    }

    @Test fun `extensionForMimeType strips charset`() {
        assertEquals("html", extensionForMimeType("text/html; charset=utf-8"))
    }

    @Test fun `extensionForMimeType empty string`() {
        assertEquals("bin", extensionForMimeType(""))
    }

    @Test fun `extensionForMimeType case insensitive`() {
        assertEquals("html", extensionForMimeType("TEXT/HTML"))
    }

    // ── formatBytes ────────────────────────────────────────────────────────

    @Test fun `formatBytes zero`() {
        assertEquals("0 B", formatBytes(0))
    }

    @Test fun `formatBytes bytes`() {
        assertEquals("1 B", formatBytes(1))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test fun `formatBytes kilobytes`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1024.0 KB", formatBytes(1024 * 1024 - 1))
    }

    @Test fun `formatBytes megabytes`() {
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
        assertEquals("2.5 MB", formatBytes(2_621_440))
    }

    @Test fun `formatBytes large values`() {
        assertEquals("100.0 MB", formatBytes(104_857_600))
    }

    // ── cookieValue ───────────────────────────────────────────────────────

    @Test fun `cookieValue exact match returns value`() {
        val map = mapOf("name" to "mycookie", "value" to "abc")
        assertEquals("mycookie", cookieValue(map, "name"))
    }

    @Test fun `cookieValue first alias match wins`() {
        val map = mapOf("name" to "n", "NAME" to "N")
        assertEquals("n", cookieValue(map, "name", "NAME"))
    }

    @Test fun `cookieValue case insensitive fallback`() {
        val map = mapOf("NAME" to "val")
        assertEquals("val", cookieValue(map, "name"))
    }

    @Test fun `cookieValue no match returns null`() {
        val map = mapOf("foo" to "bar")
        assertNull(cookieValue(map, "name"))
    }

    @Test fun `cookieValue null value in map is skipped`() {
        val map = mapOf("name" to null, "value" to "real")
        assertEquals("real", cookieValue(map, "name", "value"))
    }

    @Test fun `cookieValue empty map returns null`() {
        assertNull(cookieValue(emptyMap(), "name"))
    }

    // ── cookieString ───────────────────────────────────────────────────────

    @Test fun `cookieString string value`() {
        assertEquals("abc", cookieString(mapOf("name" to "abc"), "name"))
    }

    @Test fun `cookieString number value stringified`() {
        assertEquals("42", cookieString(mapOf("name" to 42), "name"))
    }

    @Test fun `cookieString null returns null`() {
        assertNull(cookieString(mapOf("name" to null), "name"))
    }

    @Test fun `cookieString boolean returns null`() {
        assertNull(cookieString(mapOf("name" to true), "name"))
    }

    // ── cookieBool ─────────────────────────────────────────────────────────

    @Test fun `cookieBool true boolean`() {
        assertEquals(true, cookieBool(mapOf("secure" to true), "secure"))
    }

    @Test fun `cookieBool false boolean`() {
        assertEquals(false, cookieBool(mapOf("secure" to false), "secure"))
    }

    @Test fun `cookieBool number 1 is true`() {
        assertEquals(true, cookieBool(mapOf("httpOnly" to 1), "httpOnly"))
    }

    @Test fun `cookieBool number 0 is false`() {
        assertEquals(false, cookieBool(mapOf("httpOnly" to 0), "httpOnly"))
    }

    @Test fun `cookieBool string true is true`() {
        assertEquals(true, cookieBool(mapOf("flag" to "true"), "flag"))
    }

    @Test fun `cookieBool string false is false`() {
        assertEquals(false, cookieBool(mapOf("flag" to "false"), "flag"))
    }

    @Test fun `cookieBool string 1 is true`() {
        assertEquals(true, cookieBool(mapOf("flag" to "1"), "flag"))
    }

    @Test fun `cookieBool yes is true`() {
        assertEquals(true, cookieBool(mapOf("flag" to "yes"), "flag"))
    }

    @Test fun `cookieBool unknown string returns false`() {
        assertEquals(false, cookieBool(mapOf("flag" to "maybe"), "flag"))
    }

    @Test fun `cookieBool null returns null`() {
        assertNull(cookieBool(mapOf("flag" to null), "flag"))
    }

    // ── cookieNumber ───────────────────────────────────────────────────────

    @Test fun `cookieNumber int value`() {
        assertEquals(123.0, cookieNumber(mapOf("expires" to 123), "expires")!!, 0.001)
    }

    @Test fun `cookieNumber double value`() {
        assertEquals(123.45, cookieNumber(mapOf("expires" to 123.45), "expires")!!, 0.001)
    }

    @Test fun `cookieNumber numeric string`() {
        assertEquals(123.0, cookieNumber(mapOf("expires" to "123"), "expires")!!, 0.001)
    }

    @Test fun `cookieNumber non-numeric string returns null`() {
        assertNull(cookieNumber(mapOf("expires" to "abc"), "expires"))
    }

    @Test fun `cookieNumber null returns null`() {
        assertNull(cookieNumber(mapOf("expires" to null), "expires"))
    }

    @Test fun `cookieNumber alias matching`() {
        assertEquals(100.0, cookieNumber(mapOf("expirationDate" to 100.0), "expires", "expirationDate")!!, 0.001)
    }
}