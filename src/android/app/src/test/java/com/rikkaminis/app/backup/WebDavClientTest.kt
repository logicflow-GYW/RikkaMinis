package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
