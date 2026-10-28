package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [T-webdav-backup] JVM coverage for the WebDAV client and sync layer against
 * a MockWebServer: PROPFIND parsing (names/sizes/dates, collection filtering,
 * href shapes, failed propstat), upload with directory auto-creation, auth
 * header, error mapping, and the remote-list filename convention.
 *
 * The client is pure JVM (OkHttp + XmlPullParser), so nothing here needs
 * Robolectric or an Android context.
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var config: WebDavConfig

    private val client: OkHttpClient = WebDavClient.defaultClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = WebDavConfig(
            url = server.url("/dav/").toString().trimEnd('/'),
            username = "alice",
            password = "s3cret",
            path = "RikkaMinis_backups",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun dav() = WebDavClient(config, client)

    private fun enqueue207(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body)
        )
    }

    // ── PROPFIND parsing ───────────────────────────────────────────────────

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/RikkaMinis_backups/</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>RikkaMinis_backups</d:displayname>
                <d:resourcetype><d:collection/></d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/RikkaMinis_backups/rikkaminis-backup-20260804-1530.json</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>rikkaminis-backup-20260804-1530.json</d:displayname>
                <d:getcontentlength>1234</d:getcontentlength>
                <d:getlastmodified>Tue, 04 Aug 2026 07:30:00 GMT</d:getlastmodified>
                <d:resourcetype/>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/RikkaMinis_backups/notes.txt</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>notes.txt</d:displayname>
                <d:getcontentlength>99</d:getcontentlength>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>""".trimIndent()

    @Test
    fun `list parses names sizes and rfc1123 dates and drops the collection itself`() {
        enqueue207(multistatus)
        val items = dav().list()
        // The collection's own entry (/dav/RikkaMinis_backups/) is filtered out.
        assertEquals(2, items.size)
        val backup = items.first { it.displayName == "rikkaminis-backup-20260804-1530.json" }
        assertEquals(1234L, backup.contentLength)
        assertEquals(Instant.parse("2026-08-04T07:30:00Z"), backup.lastModified)
        assertEquals(false, backup.isCollection)
        // Unrelated file in the same folder is listed at client level;
        // WebDavSync.listBackupFiles filters it by name convention.
        assertTrue(items.any { it.displayName == "notes.txt" })
    }

    @Test
    fun `list handles absolute hrefs and namespace-prefix-free xml`() {
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>${server.url("/")}dav/RikkaMinis_backups/</D:href>
                <D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
              <D:response>
                <D:href>${server.url("/")}dav/RikkaMinis_backups/rikkaminis-backup-20260803-0900.json</D:href>
                <D:propstat>
                  <D:prop>
                    <D:getcontentlength>42</D:getcontentlength>
                    <D:getlastmodified>2026-08-03T09:00:00Z</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>""".trimIndent()
        )
        val items = dav().list()
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("rikkaminis-backup-20260803-0900.json", item.displayName)
        assertEquals(42L, item.contentLength)
        // ISO-8601 fallback branch of parseLastModified.
        assertEquals(Instant.parse("2026-08-03T09:00:00Z"), item.lastModified)
    }

    @Test
    fun `list skips entries whose propstat status is not 200`() {
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/missing.json</d:href>
                <d:propstat>
                  <d:prop><d:getcontentlength>0</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/RikkaMinis_backups/rikkaminis-backup-20260802-0000.json</d:href>
                <d:propstat>
                  <d:prop><d:getcontentlength>7</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()
        )
        val items = dav().list()
        assertEquals(1, items.size)
        assertEquals("rikkaminis-backup-20260802-0000.json", items.first().displayName)
    }

    @Test
    fun `list keeps an entry whose 200 propstat is followed by a 404 propstat`() {
        // [FIX-R3-D / F-10] RFC 4918 lets one <response> carry several
        // <propstat> groups: the properties the server could answer, and those
        // it could not (404). The parser kept ONE status for the whole
        // response, so the trailing 404 group erased the 200 and the resource
        // disappeared — a folder that every other client lists came back empty
        // ("no remote backups").
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/rikkaminis-backup-20260801-1200.json</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>99</d:getcontentlength>
                    <d:getlastmodified>Sat, 01 Aug 2026 12:00:00 GMT</d:getlastmodified>
                    <d:resourcetype/>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop>
                    <d:displayname/>
                    <d:getcontenttype/>
                  </d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()
        )
        val items = dav().list()
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("rikkaminis-backup-20260801-1200.json", item.displayName)
        assertEquals(99L, item.contentLength)
        assertEquals(Instant.parse("2026-08-01T12:00:00Z"), item.lastModified)
        assertFalse(item.isCollection)
    }

    @Test
    fun `a 404 propstat cannot erase a displayname the 200 propstat carried`() {
        // Same shape, other direction: the 404 group also carries a
        // <displayname> (some servers emit an empty one per missing property).
        // It must not overwrite the answered group's value.
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/2026-08.json</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>2026-08.json</d:displayname>
                    <d:getcontentlength>5</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:displayname>ignored</d:displayname></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()
        )
        val items = dav().list()
        assertEquals(1, items.size)
        assertEquals("2026-08.json", items.first().displayName)
        assertEquals(5L, items.first().contentLength)
    }

    @Test
    fun `an entry whose every propstat group failed is still dropped`() {
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/gone.json</d:href>
                <d:propstat>
                  <d:prop><d:getcontentlength>0</d:getcontentlength></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getlastmodified/></d:prop>
                  <d:status>HTTP/1.1 403 Forbidden</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent()
        )
        assertTrue(dav().list().isEmpty())
    }

    @Test
    fun `a response-level status without propstat still decides`() {
        // Some servers answer a failed PROPFIND with <href> + <status> and no
        // propstat at all; that response must stay dropped.
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/nope.json</d:href>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:response>
              <d:response>
                <d:href>/dav/RikkaMinis_backups/ok.json</d:href>
                <d:status>HTTP/1.1 200 OK</d:status>
              </d:response>
            </d:multistatus>""".trimIndent()
        )
        val items = dav().list()
        assertEquals(listOf("ok.json"), items.map { it.displayName })
    }

    @Test
    fun `buildUrl encodes special characters in path segments`() {
        val weird = config.copy(path = "我的 备份/子目录")
        val url = WebDavClient(weird, client).buildUrl("rikkaminis-backup-1.json")
        // http://localhost:PORT/dav/%E6%88%91%E7%9A%84%20%E5%A4%87%E4%BB%BD/%E5%AD%90%E7%9B%AE%E5%BD%95/rikkaminis-backup-1.json
        assertEquals("/dav/", server.url("/dav/").encodedPath)
        assertTrue(url.encodedPath.startsWith("/dav/"))
        assertTrue(url.encodedPath.contains("%E6%88%91")) // 我
        assertTrue(url.encodedPath.contains("%20")) // space
        assertTrue(url.encodedPath.endsWith("rikkaminis-backup-1.json"))
    }

    @Test
    fun `buildUrl refuses dot segments instead of resolving them`() {
        // [audit-0917] HttpUrl.Builder resolves a segment that is exactly
        // "." or ".." and pops the previous path element. Measured against
        // okhttp 4.12.0: base https://h/dav/ + ".." + "escape.json" used to
        // build https://h/dav/escape.json — a config path could climb out of
        // the backup directory. Re-encoding is not a fix (addPathSegment
        // writes "%252E%252E"; addEncodedPathSegment("..") resolves anyway),
        // so the request is refused.
        val d = clientFor("https://example.com/dav")
        for (bad in listOf("..", ".", "../../etc/passwd", "sub/../..")) {
            try {
                d.buildUrl(bad)
                fail("expected WebDavException for traversal segment \"$bad\"")
            } catch (e: WebDavException) {
                assertTrue(
                    "message should name the offending segment: ${e.message}",
                    e.message!!.contains("outside the backup directory"),
                )
            }
        }
        // A segment that merely CONTAINS dots is legitimate and untouched.
        val normal = d.buildUrl("a..b.json")
        assertTrue("ordinary dotted name must build: $normal", normal.encodedPath.endsWith("a..b.json"))
        assertEquals("/dav/RikkaMinis_backups/a..b.json", normal.encodedPath)
    }

    // ── Upload ─────────────────────────────────────────────────────────────

    @Test
    fun `upload sends PUT with basic auth and payload`() {
        server.enqueue(MockResponse().setResponseCode(201))
        dav().put("rikkaminis-backup-20260804-1000.json", "{}".toByteArray())
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/RikkaMinis_backups/rikkaminis-backup-20260804-1000.json", request.path)
        assertEquals("Basic YWxpY2U6czNjcmV0", request.getHeader("Authorization"))
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun `upload creates the collection on 409 conflict then retries`() {
        // PUT → 409 Conflict; ensureCollectionExists: PROPFIND depth 0 → 404,
        // MKCOL → 201 Created; PUT retry → 201.
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        dav().put("rikkaminis-backup-20260804-1000.json", "{}".toByteArray())

        val put1 = server.takeRequest()
        assertEquals("PUT", put1.method)
        val propfind = server.takeRequest()
        assertEquals("PROPFIND", propfind.method)
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/dav/RikkaMinis_backups", mkcol.path)
        val put2 = server.takeRequest()
        assertEquals("PUT", put2.method)
    }

    @Test
    fun `mkcol tolerates 405 method not allowed`() {
        server.enqueue(MockResponse().setResponseCode(405))
        dav().mkcol()
        val request = server.takeRequest()
        assertEquals("MKCOL", request.method)
    }

    @Test
    fun `ensureCollectionExists mkcols only on 404 not on auth failure`() {
        // PROPFIND depth 0 → 404 → MKCOL → 201.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        dav().ensureCollectionExists()
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals("MKCOL", server.takeRequest().method)

        // PROPFIND → 401 → error propagates, no MKCOL attempt.
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            dav().ensureCollectionExists()
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(401, e.statusCode)
        }
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    @Test
    fun `upload maps server errors to WebDavException`() {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            dav().put("rikkaminis-backup-20260804-1000.json", "{}".toByteArray())
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(500, e.statusCode)
        }
    }

    // ── Download / delete / test ───────────────────────────────────────────

    @Test
    fun `download returns the payload as utf8`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"formatVersion\":1}"))
        val bytes = dav().get("rikkaminis-backup-20260804-1000.json")
        assertEquals("{\"formatVersion\":1}", String(bytes, Charsets.UTF_8))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Basic YWxpY2U6czNjcmV0", request.getHeader("Authorization"))
    }

    @Test
    fun `download maps 401 to WebDavException with status code`() {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            dav().get("rikkaminis-backup-20260804-1000.json")
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun `delete sends DELETE`() {
        server.enqueue(MockResponse().setResponseCode(204))
        dav().delete("rikkaminis-backup-20260804-1000.json")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/dav/RikkaMinis_backups/rikkaminis-backup-20260804-1000.json", request.path)
    }

    @Test
    fun `testConnection succeeds on a 207`() {
        enqueue207("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>""")
        dav().testConnection() // no exception
    }

    @Test
    fun `invalid url throws WebDavException with -1`() {
        val bad = WebDavClient(config.copy(url = "not a url"), client)
        try {
            bad.testConnection()
            fail("expected WebDavException")
        } catch (e: WebDavException) {
            assertEquals(-1, e.statusCode)
        }
    }

    // ── WebDavSync layer ───────────────────────────────────────────────────

    @Test
    fun `listBackupFiles keeps only the filename convention newest first`() {
        // ensureCollectionExists() PROPFINDs depth 0 first, then list() PROPFINDs depth 1.
        enqueue207(multistatus)
        enqueue207(multistatus) // contains the collection, one backup, notes.txt
        val items = WebDavSync.listBackupFiles(config, client)
        assertEquals(1, items.size)
        assertEquals("rikkaminis-backup-20260804-1530.json", items.first().displayName)
        assertEquals(1234L, items.first().size)
        assertEquals(Instant.parse("2026-08-04T07:30:00Z"), items.first().lastModified)
    }

    @Test
    fun `listBackupFiles still lists pre-rename openminis-backup files`() {
        val legacy = """<?xml version="1.0"?>
            |<d:multistatus xmlns:d="DAV:">
            |  <d:response>
            |    <d:href>/dav/RikkaMinis_backups/</d:href>
            |    <d:propstat>
            |      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            |      <d:status>HTTP/1.1 200 OK</d:status>
            |    </d:propstat>
            |  </d:response>
            |  <d:response>
            |    <d:href>/dav/RikkaMinis_backups/openminis-backup-20260801-1200.json</d:href>
            |    <d:propstat>
            |      <d:prop><d:getcontentlength>99</d:getcontentlength></d:prop>
            |      <d:status>HTTP/1.1 200 OK</d:status>
            |    </d:propstat>
            |  </d:response>
            |</d:multistatus>""".trimMargin()
        enqueue207(legacy)
        enqueue207(legacy)
        val items = WebDavSync.listBackupFiles(config, client)
        assertEquals(1, items.size)
        assertEquals("openminis-backup-20260801-1200.json", items.first().displayName)
    }

    @Test
    fun `sync backup pushes the json payload and sorts multiple files`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"/>"""))
        server.enqueue(MockResponse().setResponseCode(201))
        WebDavSync.backup(config, "{}", client)
        assertEquals("PROPFIND", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val path = put.path ?: ""
        // Second-precision name keeps same-minute local exports from being
        // overwritten; convention stays rikkaminis-backup-*.json.
        assertTrue("unexpected PUT path: $path", path.matches(Regex("^/dav/RikkaMinis_backups/rikkaminis-backup-\\d{8}-\\d{6}\\.json$")))
        assertEquals("{}", put.body.readUtf8())
    }

    @Test
    fun `sync restore downloads and returns the document`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"formatVersion\":1}"))
        val json = WebDavSync.restore(config, WebDavBackupItem("h", "rikkaminis-backup-1.json", 17, Instant.EPOCH), client)
        assertEquals("{\"formatVersion\":1}", json)
        assertEquals("/dav/RikkaMinis_backups/rikkaminis-backup-1.json", server.takeRequest().path)
    }

    // ── Non-WebDAV responses (captive portal / proxy login pages) ─────────
    //
    // [fix/p1-webdav-propf] World W5: the link comes up on a Wi-Fi portal, a
    // proxy error page or an SSO login page, and the PROPFIND is answered
    // with 200 + HTML. Before the fix `isSuccessful` waved it through, the
    // parser found zero <response> nodes, and the UI said "the server has no
    // backups" — while testConnection() (same code path, depth 0) said the
    // connection was fine. Both statements were wrong and both sounded
    // certain.

    private fun enqueuePage(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(body)
        )
    }

    private val loginPage = """<!DOCTYPE html>
        |<html><head><title>Sign in</title></head>
        |<body><form action="/login"><h1>Wi-Fi sign-in required</h1></form></body></html>"""
        .trimMargin()

    private fun expectNonDav(block: () -> Unit): WebDavNonDavResponse =
        try {
            block()
            throw AssertionError(
                "expected WebDavNonDavResponse — the call reported success for a non-WebDAV body",
            )
        } catch (e: WebDavNonDavResponse) {
            e
        }

    @Test
    fun `list throws on 200 plus an html login page instead of an empty list`() {
        enqueuePage(200, loginPage)
        val e = expectNonDav { dav().list() }
        assertEquals(200, e.statusCode)
        assertTrue("message should name the cause: ${e.message}", e.message!!.contains("non-WebDAV response"))
        assertTrue("message should keep the code: ${e.message}", e.message!!.contains("HTTP 200"))
        assertTrue("message should point at the likely cause: ${e.message}", e.message!!.contains("portal"))
    }

    @Test
    fun `testConnection fails when the server answers with an html page`() {
        enqueuePage(200, loginPage)
        val e = expectNonDav { dav().testConnection() }
        assertEquals(200, e.statusCode)
    }

    @Test
    fun `an html page that merely mentions multistatus is still refused`() {
        // Guards the second signal: the body DOES contain the word, so only
        // "the parsed document root is <multistatus>" can reject it.
        enqueuePage(
            200,
            "<!DOCTYPE html><html><body><p>PROPFIND multistatus rejected by proxy</p></body></html>",
        )
        val e = expectNonDav { dav().list() }
        assertEquals(200, e.statusCode)
    }

    @Test
    fun `a 207 whose body is not a multistatus document is refused with its own wording`() {
        enqueuePage(207, "<html><body>multistatus expected</body></html>")
        val e = expectNonDav { dav().list() }
        assertEquals(207, e.statusCode)
        assertTrue("message should say unreadable/207: ${e.message}", e.message!!.contains("HTTP 207"))
    }

    @Test
    fun `truncated multistatus xml is refused rather than read as an empty folder`() {
        // Root parses as multistatus, then the document ends mid-element: the
        // parser blows up, which must not degrade to "no backups".
        enqueuePage(
            207,
            """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/x""",
        )
        expectNonDav { dav().list() }
    }

    @Test
    fun `list returns an empty list for a genuine multistatus with zero responses`() {
        // The legitimate "empty folder" case must keep working — and must NOT
        // throw. This is the arm the fix is most likely to break.
        enqueue207("""<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:"/>""")
        assertEquals(0, dav().list().size)
    }

    @Test
    fun `list still returns the single file of a one-entry multistatus`() {
        enqueue207(
            """<?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/RikkaMinis_backups/rikkaminis-backup-20260101-0101.json</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>5</d:getcontentlength>
                    <d:getlastmodified>Thu, 01 Jan 2026 01:01:00 GMT</d:getlastmodified>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>""".trimIndent(),
        )
        val items = dav().list()
        assertEquals(1, items.size)
        assertEquals("rikkaminis-backup-20260101-0101.json", items.first().displayName)
        assertEquals(Instant.parse("2026-01-01T01:01:00Z"), items.first().lastModified)
    }

    @Test
    fun `listBackupFiles propagates the non-WebDAV failure instead of an empty list`() {
        // The exact user-visible symptom: the remote-backup screen showed an
        // empty list. Its 404 tolerance must not swallow a login page.
        enqueuePage(200, loginPage)
        val e = expectNonDav { WebDavSync.listBackupFiles(config, client) }
        assertEquals(200, e.statusCode)
    }

    // ── Non-WebDAV bodies on PUT / GET / DELETE ───────────────────────────
    //
    // [fix/webdav-method-judgement] World W5, the other half of the PROPFIND
    // seam above: put()/get()/delete() judged only the status code, so a
    // portal 200 made an upload report success with nothing stored, fed the
    // login page to ConfigBackup.import as if it were a backup document, and
    // reported deletions that never happened. The judge reads the FIRST bytes
    // (a JSON payload never opens with `<`), so a real backup whose message
    // content merely mentions html keeps working — that arm is a test below,
    // not an assumption.

    @Test
    fun `put refuses a portal page served in place of the upload answer`() {
        enqueuePage(200, loginPage)
        val e = expectNonDav { dav().put("rikkaminis-backup-1.json", "{}".toByteArray()) }
        assertEquals(200, e.statusCode)
        assertTrue("message should name the operation: ${e.message}", e.message!!.contains("Upload"))
        assertTrue("message should point at the likely cause: ${e.message}", e.message!!.contains("portal"))
    }

    @Test
    fun `put refuses a portal page on the create-collection retry path too`() {
        // First PUT → 409, ensureCollectionExists PROPFIND → 404 + MKCOL → 201,
        // retry PUT → 200 + login page. Only the retry path is judged here:
        // a fix applied to just one of the two success branches would leave
        // this case green.
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        enqueuePage(200, loginPage)
        expectNonDav { dav().put("rikkaminis-backup-1.json", "{}".toByteArray()) }
    }

    @Test
    fun `put tolerates an empty body and a json body on success`() {
        server.enqueue(MockResponse().setResponseCode(201)) // empty 201
        dav().put("rikkaminis-backup-a.json", "{}".toByteArray())
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"stored\":true}")) // non-empty 200
        dav().put("rikkaminis-backup-b.json", "{}".toByteArray())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `get refuses a portal page instead of feeding it to the importer`() {
        enqueuePage(200, loginPage)
        val e = expectNonDav { dav().get("rikkaminis-backup-1.json") }
        assertEquals(200, e.statusCode)
        assertTrue("message should name the operation: ${e.message}", e.message!!.contains("Download"))
    }

    @Test
    fun `get refuses an empty 200 body`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val e = expectNonDav { dav().get("rikkaminis-backup-1.json") }
        assertTrue("message should say empty: ${e.message}", e.message!!.contains("empty body"))
    }

    @Test
    fun `get tolerates json whose message content merely mentions html`() {
        // A real backup carries arbitrary user text — a message can contain
        // "<html". The judge looks at the OPENING bytes only; a substring scan
        // here would refuse valid backups.
        val body = """{"messages":[{"text":"see the <html> spec below"}]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val bytes = dav().get("rikkaminis-backup-1.json")
        assertEquals(body, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `get tolerates a bom and leading whitespace before the json`() {
        val body = "\uFEFF\n  {\"formatVersion\":1}"
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val bytes = dav().get("rikkaminis-backup-1.json")
        assertEquals(body, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `delete refuses a portal page instead of reporting a deletion that never happened`() {
        enqueuePage(200, loginPage)
        val e = expectNonDav { dav().delete("rikkaminis-backup-1.json") }
        assertEquals(200, e.statusCode)
        assertTrue("message should name the operation: ${e.message}", e.message!!.contains("Delete"))
    }

    @Test
    fun `delete tolerates the normal empty success bodies`() {
        server.enqueue(MockResponse().setResponseCode(204))
        dav().delete("rikkaminis-backup-a.json")
        server.enqueue(MockResponse().setResponseCode(200)) // empty 200 also legal
        dav().delete("rikkaminis-backup-b.json")
        assertEquals(2, server.requestCount)
    }

    // ── Transport security (plain-HTTP refusal) ───────────────────────────

    private fun clientFor(url: String): WebDavClient =
        WebDavClient(config.copy(url = url), client)

    @Test
    fun `plain http to a public host is refused`() {
        val d = clientFor("http://example.com/dav")
        try {
            d.buildUrl()
            fail("expected WebDavException for plain HTTP to a public host")
        } catch (e: WebDavException) {
            assertEquals(-1, e.statusCode)
            assertTrue("message should name the host: ${e.message}", e.message!!.contains("example.com"))
            assertTrue("message should say HTTPS: ${e.message}", e.message!!.contains("https"))
        }
    }

    @Test
    fun `https to a public host builds the url`() {
        val url = clientFor("https://example.com/dav").buildUrl()
        assertTrue("expected https url: $url", url.isHttps)
        assertTrue(url.toString().startsWith("https://example.com/"))
    }

    @Test
    fun `plain http to loopback and private hosts is allowed`() {
        // MockWebServer itself runs on 127.0.0.1, so every existing test in
        // this class already depends on the loopback escape.
        for (url in listOf(
            "http://127.0.0.1:5240/dav",
            "http://localhost:5240/dav",
            "http://192.168.1.20:5240/dav",
            "http://10.0.0.5:5240/dav",
            "http://172.16.3.9:5240/dav",
            "http://172.31.255.1:5240/dav",
        )) {
            val built = clientFor(url).buildUrl()
            assertTrue("expected loopback/private url to build: $url", built.toString().startsWith("http://"))
        }
    }

    @Test
    fun `private-host detection boundaries`() {
        val f = { h: String -> WebDavClient(config, client).isLoopbackOrPrivateHost(h) }
        assertTrue(f("localhost"))
        assertTrue(f("127.0.0.1"))
        assertTrue(f("10.1.2.3"))
        assertTrue(f("192.168.0.1"))
        assertTrue(f("172.16.0.1"))
        assertTrue(f("172.31.9.9"))
        // Just outside the private ranges must NOT be allowed
        assertTrue(!f("172.32.0.1"))
        assertTrue(!f("172.15.0.1"))
        assertTrue(!f("11.0.0.1"))
        assertTrue(!f("193.168.0.1"))
        assertTrue(!f("example.com"))
    }
}
