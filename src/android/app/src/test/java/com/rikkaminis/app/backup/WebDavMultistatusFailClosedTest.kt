package com.rikkaminis.app.backup

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-B2 F-07] JVM coverage for the multistatus parse's failure semantics.
 *
 * The whole client is Android-free EXCEPT that `WebDavClient` is an object over
 * OkHttp/XmlPullParser — but `parseMultistatus`/`isSuccessStatus` are private.
 * These tests therefore pin the DECISIONS through the same seam the scan gate
 * cannot: a verbatim copy of the production algorithm is NOT used (that would
 * be a fake-fidelity test); instead the parser is driven through reflection on
 * the real `WebDavClient` object so the production code itself is what runs.
 *
 * Every expectation is a literal. The PRE-fix tree fails every one of these:
 *  - self-entry 404/403 used to return an empty list (not null);
 *  - a missing `<status>` used to arm the propstat (initial `true`);
 *  - a `<href>` inside a `<propstat>` used to overwrite the outer path;
 *  - `" 200"` missed 207/204.
 */
class WebDavMultistatusFailClosedTest {

    // ── the real parser, driven via reflection ───────────────────────────────

    private val instance = WebDavClient(
        WebDavConfig(
            url = "https://dav.example.com",
            username = "alice",
            password = "s3cret",
            path = "RikkaMinis_backups",
        ),
        WebDavClient.defaultClient(),
    )

    private val parse = run {
        val m = WebDavClient::class.java.getDeclaredMethod(
            "parseMultistatus",
            String::class.java,
            okhttp3.HttpUrl::class.java,
        )
        m.isAccessible = true
        m
    }

    private fun parseBody(xml: String, requestPath: String): List<*>? {
        val url = "https://dav.example.com$requestPath".toHttpUrlOrNull()
            ?: error("bad request path $requestPath")
        return parse.invoke(instance, xml, url) as List<*>?
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun selfEntry(status: String?, extraPropstat: String = ""): String = """
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/RikkaMinis_backups/</d:href>
            <d:propstat>
              <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
              ${if (status == null) "" else "<d:status>$status</d:status>"}
            </d:propstat>
            $extraPropstat
          </d:response>
        </d:multistatus>
    """.trimIndent()

    private fun listEntry(href: String, status: String): String = """
          <d:response>
            <d:href>$href</d:href>
            <d:propstat>
              <d:prop>
                <d:getcontentlength>123</d:getcontentlength>
                <d:getlastmodified>Mon, 26 Oct 2026 10:00:00 GMT</d:getlastmodified>
              </d:prop>
              <d:status>$status</d:status>
            </d:propstat>
          </d:response>
    """.trimIndent()

    private fun doc(vararg responses: String): String =
        """<d:multistatus xmlns:d="DAV:">
        ${responses.joinToString("\n")}
        </d:multistatus>
        """.trimIndent()

    // ── F-07③ the requested collection failing inside a 207 ─────────────────

    @Test
    fun `self entry with 404 in a 207 fails closed to null`() {
        val out = parseBody(selfEntry("HTTP/1.1 404 Not Found"), "/dav/RikkaMinis_backups")
        assertNull("a 207 that says the collection is 404 must not read as an empty folder", out)
    }

    @Test
    fun `self entry with 403 in a 207 fails closed to null`() {
        val out = parseBody(selfEntry("HTTP/1.1 403 Forbidden"), "/dav/RikkaMinis_backups")
        assertNull(out)
    }

    @Test
    fun `self entry with a 200 survives and children still list`() {
        val out = parseBody(
            doc(
                selfEntry("HTTP/1.1 200 OK"),
                listEntry("/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json", "HTTP/1.1 200 OK"),
            ),
            "/dav/RikkaMinis_backups",
        )
        assertTrue(out != null)
        assertEquals(1, out!!.size)
    }

    @Test
    fun `self entry missing its status fails closed to null`() {
        val out = parseBody(selfEntry(null), "/dav/RikkaMinis_backups")
        assertNull(out)
    }

    // ── F-07① a propstat without a status must not be armed ──────────────────

    @Test
    fun `propstat without a status does not rescue a failed self entry`() {
        // The 404 propstat carries the failure; a second, status-less propstat
        // used to default to OK and commit the resource as existing.
        val body = selfEntry(
            "HTTP/1.1 404 Not Found",
            "<d:propstat><d:prop><d:displayname>backups</d:displayname></d:prop></d:propstat>",
        )
        assertNull(parseBody(body, "/dav/RikkaMinis_backups"))
    }

    @Test
    fun `propstat without a status does not list the entry`() {
        val body = doc(
            """
            <d:response>
              <d:href>/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json</d:href>
              <d:propstat>
                <d:prop><d:getcontentlength>7</d:getcontentlength></d:prop>
              </d:propstat>
            </d:response>
            """.trimIndent(),
        )
        val out = parseBody(body, "/dav/RikkaMinis_backups")
        assertTrue(out != null)
        assertEquals(0, out!!.size)
    }

    @Test
    fun `a 204 propstat arms its entry where the old 200-substring did not`() {
        val body = doc(
            """
            <d:response>
              <d:href>/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json</d:href>
              <d:propstat>
                <d:prop><d:getcontentlength>7</d:getcontentlength></d:prop>
                <d:status>HTTP/1.1 204 No Content</d:status>
              </d:propstat>
            </d:response>
            """.trimIndent(),
        )
        val out = parseBody(body, "/dav/RikkaMinis_backups")
        assertTrue(out != null)
        assertEquals(1, out!!.size)
    }

    @Test
    fun `a bare 207 multistatus status line counts as 2xx`() {
        val body = doc(
            """
            <d:response>
              <d:href>/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json</d:href>
              <d:propstat>
                <d:prop><d:getcontentlength>7</d:getcontentlength></d:prop>
                <d:status>HTTP/1.1 207 Multi-Status</d:status>
              </d:propstat>
            </d:response>
            """.trimIndent(),
        )
        val out = parseBody(body, "/dav/RikkaMinis_backups")
        assertEquals(1, out!!.size)
    }

    // ── F-07② an href inside a propstat must not overwrite the path ──────────

    @Test
    fun `href inside a propstat does not overwrite the resource path`() {
        // lockdiscovery echoes an <href> inside the propstat; the outer href is
        // the real resource. The old parser overwrote it.
        val body = doc(
            """
            <d:response>
              <d:href>/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json</d:href>
              <d:propstat>
                <d:prop>
                  <d:lockdiscovery>
                    <d:activelock>
                      <d:lockroot><d:href>/dav/OTHER/path.json</d:href></d:lockroot>
                    </d:activelock>
                  </d:lockdiscovery>
                </d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
            </d:response>
            """.trimIndent(),
        )
        val out = parseBody(body, "/dav/RikkaMinis_backups")
        assertTrue(out != null)
        assertEquals(1, out!!.size)
        val info = out[0] as WebDavResourceInfo
        assertEquals("/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json", info.href)
    }

    @Test
    fun `response level href still wins outside propstat`() {
        val out = parseBody(
            doc(listEntry("/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json", "HTTP/1.1 200 OK")),
            "/dav/RikkaMinis_backups",
        )
        val info = out!![0] as WebDavResourceInfo
        assertEquals("/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json", info.href)
    }

    // ── the guarantee the fix does NOT change ────────────────────────────────

    @Test
    fun `a genuine empty multistatus still reads as an empty folder`() {
        val out = parseBody(doc(), "/dav/RikkaMinis_backups")
        assertTrue(out != null)
        assertEquals(0, out!!.size)
    }

    @Test
    fun `self entry that succeeds is not listed as a child`() {
        val out = parseBody(
            doc(
                selfEntry("HTTP/1.1 200 OK"),
                listEntry("/dav/RikkaMinis_backups/rikkaminis-20261026-090000000.json", "HTTP/1.1 200 OK"),
            ),
            "/dav/RikkaMinis_backups",
        )
        val hrefs = out!!.map { (it as WebDavResourceInfo).href }
        assertFalse(hrefs.contains("/dav/RikkaMinis_backups"))
    }
}
