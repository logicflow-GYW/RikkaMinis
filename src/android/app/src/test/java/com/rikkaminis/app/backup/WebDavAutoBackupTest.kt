package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * [T-auto-backup-remote-dir] JVM coverage for the automatic-backup remote
 * management surface: auto copies live in their own `auto/` WebDAV
 * subdirectory (never mixed with manual backups), the manual remote list
 * excludes them, legacy flat `rikkaminis-backup-auto-*` stragglers in the
 * backup root remain visible in the auto list, and pruning only touches the
 * `auto/` copies. Pure JVM against a MockWebServer (same harness as
 * WebDavClientTest).
 */
class WebDavAutoBackupTest {

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

    // ── PROPFIND response builders ────────────────────────────────────────

    private fun enqueue207(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body)
        )
    }

    private fun davResponse(
        href: String,
        displayName: String,
        isCollection: Boolean = false,
        size: Long? = null,
        lastModified: String? = null,
    ): String {
        val props = buildString {
            append("<d:displayname>$displayName</d:displayname>")
            if (isCollection) {
                append("<d:resourcetype><d:collection/></d:resourcetype>")
            } else {
                size?.let { append("<d:getcontentlength>$it</d:getcontentlength>") }
                lastModified?.let { append("<d:getlastmodified>$it</d:getlastmodified>") }
                append("<d:resourcetype/>")
            }
        }
        return """
            <d:response>
              <d:href>$href</d:href>
              <d:propstat>
                <d:prop>$props</d:prop>
                <d:status>HTTP/1.1 200 OK</d:status>
              </d:propstat>
            </d:response>"""
    }

    private fun multistatus(vararg entries: String): String =
        """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">${entries.joinToString("")}
        </d:multistatus>"""

    private fun rootDir(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/", "RikkaMinis_backups", isCollection = true)
    )

    private fun autoDir(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/auto/", "auto", isCollection = true)
    )

    /** `auto/` holds three auto backups (09-08, 09-07, 09-06). */
    private fun autoListing(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/auto/", "auto", isCollection = true),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260908-080000.json",
            "rikkaminis-backup-auto-20260908-080000.json", size = 100,
            lastModified = "Tue, 08 Sep 2026 00:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260907-080000.json",
            "rikkaminis-backup-auto-20260907-080000.json", size = 200,
            lastModified = "Mon, 07 Sep 2026 00:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260906-080000.json",
            "rikkaminis-backup-auto-20260906-080000.json", size = 300,
            lastModified = "Sun, 06 Sep 2026 00:00:00 GMT",
        ),
    )

    /** Root holds one manual backup plus one legacy flat auto straggler. */
    private fun rootListing(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/", "RikkaMinis_backups", isCollection = true),
        davResponse(
            "/dav/RikkaMinis_backups/rikkaminis-backup-20260908-100000.json",
            "rikkaminis-backup-20260908-100000.json", size = 50,
            lastModified = "Tue, 08 Sep 2026 02:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/rikkaminis-backup-auto-20260905-080000.json",
            "rikkaminis-backup-auto-20260905-080000.json", size = 400,
            lastModified = "Sat, 05 Sep 2026 00:00:00 GMT",
        ),
    )

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    fun `backupAuto pushes into the auto subdir`() {
        enqueue207(rootDir())   // ensureCollectionExists() root
        enqueue207(autoDir())   // ensureCollectionExists(auto)
        server.enqueue(MockResponse().setResponseCode(201))

        WebDavSync.backupAuto(config, """{"format":"openminis.config.backup"}""", client)

        val r1 = server.takeRequest()
        val r2 = server.takeRequest()
        val r3 = server.takeRequest()
        assertEquals("PROPFIND", r1.method)
        assertEquals("/dav/RikkaMinis_backups", r1.path)
        assertEquals("PROPFIND", r2.method)
        assertEquals("/dav/RikkaMinis_backups/auto", r2.path)
        assertEquals("PUT", r3.method)
        assertTrue(
            "auto backup must land inside the auto/ subdir: ${r3.path}",
            r3.path!!.startsWith("/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-"),
        )
    }

    @Test
    fun `listBackupFiles excludes automatic backups`() {
        enqueue207(rootListing())
        val items = WebDavSync.listBackupFiles(config, client)
        assertEquals(1, items.size)
        assertEquals("rikkaminis-backup-20260908-100000.json", items.first().displayName)
    }

    @Test
    fun `listAutoBackupEntries merges auto subdir and legacy root stragglers newest first`() {
        enqueue207(autoListing())
        enqueue207(rootListing())
        val entries = WebDavSync.listAutoBackupEntries(config, client)
        assertEquals(4, entries.size)
        // newest first: 09-08 (auto/) → 09-07 → 09-06 → 09-05 (root)
        assertEquals("auto", entries[0].subdir)
        assertEquals("rikkaminis-backup-auto-20260908-080000.json", entries[0].item.displayName)
        assertEquals("rikkaminis-backup-auto-20260906-080000.json", entries[2].item.displayName)
        assertEquals("", entries[3].subdir)
        assertEquals("rikkaminis-backup-auto-20260905-080000.json", entries[3].item.displayName)
    }

    @Test
    fun `pruneAutoBackups deletes only auto subdir copies beyond keep`() {
        enqueue207(autoListing())   // 3 auto files in auto/
        enqueue207(rootListing())   // 1 legacy straggler in root
        server.enqueue(MockResponse().setResponseCode(204)) // DELETE oldest in auto/

        val deleted = WebDavSync.pruneAutoBackups(config, keep = 2, client)
        assertEquals(1, deleted)
        server.takeRequest() // PROPFIND auto/
        server.takeRequest() // PROPFIND root
        val req = server.takeRequest() // DELETE oldest in auto/
        assertEquals("DELETE", req.method)
        assertEquals(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260906-080000.json",
            req.path,
        )
    }

    @Test
    fun `restoreAuto downloads from the auto subdir`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"format":"openminis.config.backup"}""")
        )
        val entry = WebDavSync.AutoBackupEntry(
            item = WebDavBackupItem(
                href = "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260908-080000.json",
                displayName = "rikkaminis-backup-auto-20260908-080000.json",
                size = 100,
                lastModified = Instant.parse("2026-09-08T00:00:00Z"),
            ),
            subdir = "auto",
        )
        val json = WebDavSync.restoreAuto(config, entry, client)
        assertEquals(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260908-080000.json",
            server.takeRequest().path,
        )
        assertTrue(json.contains("openminis.config.backup"))
    }

    @Test
    fun `restoreAuto handles legacy root stragglers`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"format":"openminis.config.backup"}""")
        )
        val entry = WebDavSync.AutoBackupEntry(
            item = WebDavBackupItem(
                href = "/dav/RikkaMinis_backups/rikkaminis-backup-auto-20260905-080000.json",
                displayName = "rikkaminis-backup-auto-20260905-080000.json",
                size = 400,
                lastModified = Instant.parse("2026-09-05T00:00:00Z"),
            ),
            subdir = "",
        )
        WebDavSync.restoreAuto(config, entry, client)
        assertEquals(
            "/dav/RikkaMinis_backups/rikkaminis-backup-auto-20260905-080000.json",
            server.takeRequest().path,
        )
    }

    @Test
    fun `auto folder missing returns empty list not error`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val entries = WebDavSync.listAutoBackupEntries(config, client)
        assertTrue(entries.isEmpty())
    }
    // ── [FIX-1028 / B-12] rotation keyed on the name, not `getlastmodified` ──

    /** `auto/` as a server that omits `getlastmodified` reports it, plus one
     *  copy whose name carries a foreign (unparseable) stamp. */
    private fun autoListingWithoutLastModified(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/auto/", "auto", isCollection = true),
        // Listing order is deliberately NOT newest-first: with no dates a
        // lastModified-keyed sort is a stable sort over exactly this order, so
        // an order-blind implementation picks 10-27 as "newest" (what the
        // server happened to list first) and deletes 10-28 — the fresh copy.
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20261027-120000.json",
            "rikkaminis-backup-auto-20261027-120000.json", size = 200,
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20261028-120000.json",
            "rikkaminis-backup-auto-20261028-120000.json", size = 100,
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-pushed-by-hand.json",
            "rikkaminis-backup-auto-pushed-by-hand.json", size = 300,
        ),
    )

    @Test
    fun `prune keeps a copy whose age cannot be established`() {
        // W5: the server tells us nothing about time. Before B-12 every entry
        // collapsed to Instant.EPOCH, so "newest first" was listing order and
        // rotation deleted an arbitrary copy — possibly the newest.
        enqueue207(autoListingWithoutLastModified())
        enqueue207(rootDir())
        server.enqueue(MockResponse().setResponseCode(204))

        val deleted = WebDavSync.pruneAutoBackups(config, keep = 1, client = client)
        assertEquals(1, deleted)
        server.takeRequest() // PROPFIND auto/
        server.takeRequest() // PROPFIND root
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals(
            "the oldest NAMED copy dies; the unparseable one is never a victim",
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20261027-120000.json",
            req.path,
        )
    }

    /** Four auto copies in `auto/` (09-08 … 09-05). */
    private fun autoListingFour(): String = multistatus(
        davResponse("/dav/RikkaMinis_backups/auto/", "auto", isCollection = true),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260908-080000.json",
            "rikkaminis-backup-auto-20260908-080000.json", size = 100,
            lastModified = "Tue, 08 Sep 2026 00:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260907-080000.json",
            "rikkaminis-backup-auto-20260907-080000.json", size = 200,
            lastModified = "Mon, 07 Sep 2026 00:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260906-080000.json",
            "rikkaminis-backup-auto-20260906-080000.json", size = 300,
            lastModified = "Sun, 06 Sep 2026 00:00:00 GMT",
        ),
        davResponse(
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260905-080000.json",
            "rikkaminis-backup-auto-20260905-080000.json", size = 400,
            lastModified = "Sat, 05 Sep 2026 00:00:00 GMT",
        ),
    )

    @Test
    fun `prune never deletes the copy that was just pushed`() {
        enqueue207(autoListingFour())
        enqueue207(rootDir())
        server.enqueue(MockResponse().setResponseCode(204))

        // A rolled-back clock named the fresh upload 09-06, i.e. it is past the
        // keep window (2) — without protection this DELETE would hit it.
        val deleted = WebDavSync.pruneAutoBackups(
            config,
            keep = 2,
            client = client,
            protectName = "rikkaminis-backup-auto-20260906-080000.json",
        )
        assertEquals(1, deleted)
        server.takeRequest() // PROPFIND auto/
        server.takeRequest() // PROPFIND root
        val req = server.takeRequest()
        assertEquals(
            "the protected copy is skipped; the next-oldest dies instead",
            "/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-20260905-080000.json",
            req.path,
        )
    }

    @Test
    fun `listAutoBackupEntries orders by name when the server sends no dates`() {
        enqueue207(autoListingWithoutLastModified())
        enqueue207(rootListing())
        val entries = WebDavSync.listAutoBackupEntries(config, client)
        val autoNames = entries.filter { it.subdir == "auto" }.map { it.item.displayName }
        assertEquals(
            listOf(
                "rikkaminis-backup-auto-20261028-120000.json",
                "rikkaminis-backup-auto-20261027-120000.json",
                "rikkaminis-backup-auto-pushed-by-hand.json",
            ),
            autoNames,
        )
    }
}