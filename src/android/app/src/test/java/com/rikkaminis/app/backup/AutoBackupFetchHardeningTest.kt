package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [fix/da1023-backup-streaming-fetch] JVM coverage for the automatic-backup
 * fetch hardening: the remote-display-name sanitizer (fail-closed on path
 * separators / traversal / control chars, ASCII whitelist otherwise), the
 * pure local-rotation selection (keep=N boundary), and the File overload of
 * backupAuto (the upload must carry the exact file bytes — the caller streams
 * the document to disk and must never rebuild a whole-document String).
 *
 * Expectations are hardcoded literals, NOT derived from WebDavSync constants
 * (a mutation of the production constant must not silently rewrite the
 * expectation — evidence-discipline).
 */
class AutoBackupFetchHardeningTest {

    // ── sanitizeAutoBackupStamp — fail-closed refusals ────────────────────

    @Test
    fun `clean stamp passes through unchanged`() {
        assertEquals("20261017-120000", WebDavSync.sanitizeAutoBackupStamp("20261017-120000"))
    }

    @Test
    fun `full remote display name passes through unchanged`() {
        assertEquals(
            "rikkaminis-backup-auto-20261017-120000.json",
            WebDavSync.sanitizeAutoBackupStamp("rikkaminis-backup-auto-20261017-120000.json"),
        )
    }

    @Test
    fun `parent traversal is refused`() {
        assertNull(WebDavSync.sanitizeAutoBackupStamp("../../evil"))
        assertNull(WebDavSync.sanitizeAutoBackupStamp(".."))
        assertNull(WebDavSync.sanitizeAutoBackupStamp("..evil"))
    }

    @Test
    fun `path separators are refused`() {
        assertNull(WebDavSync.sanitizeAutoBackupStamp("a/b"))
        assertNull(WebDavSync.sanitizeAutoBackupStamp("a\\b"))
    }

    @Test
    fun `blank stamps are refused`() {
        assertNull(WebDavSync.sanitizeAutoBackupStamp(""))
        assertNull(WebDavSync.sanitizeAutoBackupStamp("   "))
    }

    @Test
    fun `control characters are refused`() {
        assertNull(WebDavSync.sanitizeAutoBackupStamp("a\tb"))
        assertNull(WebDavSync.sanitizeAutoBackupStamp("\u007F"))
    }

    // ── sanitizeAutoBackupStamp — whitelist replacements ──────────────────

    @Test
    fun `interior spaces are replaced with underscores`() {
        assertEquals("2026_10_17", WebDavSync.sanitizeAutoBackupStamp("2026 10 17"))
    }

    @Test
    fun `non-ASCII characters are replaced with underscores`() {
        assertEquals("__", WebDavSync.sanitizeAutoBackupStamp("备份"))
        assertEquals("ri_kka", WebDavSync.sanitizeAutoBackupStamp("ri,kka"))
    }

    // ── selectLocalRotationVictims — keep=N boundary ──────────────────────

    private fun tempFile(name: String): File {
        val f = File.createTempFile(name.padEnd(3, 'x'), ".json")
        f.writeText("""{"n":"$name"}""")
        f.deleteOnExit()
        return f
    }

    @Test
    fun `keep equals list size keeps everything`() {
        val f1 = tempFile("a")
        val f2 = tempFile("b")
        val f3 = tempFile("c")
        assertEquals(
            emptyList<File>(),
            WebDavAutoBackupTestHelpers.rotate(listOf(f1, f2, f3), 3),
        )
    }

    @Test
    fun `one more file than keep drops exactly the oldest`() {
        val newest = tempFile("n1")
        val mid = tempFile("n2")
        val oldest = tempFile("n3")
        val victims = WebDavAutoBackupTestHelpers.rotate(listOf(newest, mid, oldest), 2)
        assertEquals(1, victims.size)
        assertSame(oldest, victims[0])
    }

    @Test
    fun `keep zero is explicit no-local-history`() {
        val f1 = tempFile("z1")
        val f2 = tempFile("z2")
        val victims = WebDavAutoBackupTestHelpers.rotate(listOf(f1, f2), 0)
        assertEquals(2, victims.size)
    }

    @Test
    fun `keep one with four files leaves three victims`() {
        val f1 = tempFile("k1")
        val f2 = tempFile("k2")
        val f3 = tempFile("k3")
        val f4 = tempFile("k4")
        val victims = WebDavAutoBackupTestHelpers.rotate(listOf(f1, f2, f3, f4), 1)
        assertEquals(listOf(f2, f3, f4), victims)
    }

    // ── backupAuto File overload — exact bytes, auto/ subdir ──────────────

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

    private fun enqueue207(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(body),
        )
    }

    private fun multistatus(body: String): String = """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">$body
        </d:multistatus>"""

    private fun collection(href: String, name: String): String = """
        <d:response>
          <d:href>$href</d:href>
          <d:propstat>
            <d:prop><d:displayname>$name</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>"""

    @Test
    fun `backupAuto File overload PUTs the exact file bytes into auto subdir`() {
        val tmp = File.createTempFile("autobak", ".json")
        val payload = """{"format":"openminis.config.backup","sections":42}"""
        tmp.writeText(payload)
        tmp.deleteOnExit()

        enqueue207(multistatus(collection("/dav/RikkaMinis_backups/", "RikkaMinis_backups")))
        enqueue207(multistatus(collection("/dav/RikkaMinis_backups/auto/", "auto")))
        server.enqueue(MockResponse().setResponseCode(201))

        WebDavSync.backupAuto(config, tmp, client)

        val r1 = server.takeRequest()
        val r2 = server.takeRequest()
        val r3 = server.takeRequest()
        assertEquals("PROPFIND", r1.method)
        assertEquals("PROPFIND", r2.method)
        assertEquals("PUT", r3.method)
        assertTrue(
            "file overload must land inside the auto/ subdir: ${r3.path}",
            r3.path!!.startsWith("/dav/RikkaMinis_backups/auto/rikkaminis-backup-auto-"),
        )
        assertEquals(
            "the upload must carry the exact file bytes",
            payload,
            r3.body.readUtf8(),
        )
    }

    // ── [FIX-1028 / B-12] local rotation: name stamp, not mtime ───────────

    private fun namedBackup(dir: File, name: String): File =
        File(dir, name).apply { writeText("""{"n":"$name"}""") }

    private fun freshDir(): File = File.createTempFile("autos", "").let { f ->
        f.delete()
        f.mkdirs()
        f
    }

    @Test
    fun `local rotation deletes the oldest by name and never an unreadable copy`() {
        val dir = freshDir()
        try {
            val prefix = "rikkaminis-auto-"
            val newest = namedBackup(dir, "${prefix}20261028-120000.json")
            val older = namedBackup(dir, "${prefix}20261027-120000.json")
            // A copy fetched from a server that named it something we cannot
            // date. Age unknown => never a victim.
            val foreign = namedBackup(dir, "${prefix}fetched-copy.json")
            // mtime contradicts the names, exactly like a rolled-back clock.
            newest.setLastModified(1_000_000_000_000L)
            older.setLastModified(2_000_000_000_000L)
            foreign.setLastModified(3_000_000_000_000L)

            val ordered = BackupRotation.newestFirst(
                listOf(older, foreign, newest),
                name = { it.name },
                key = { AutoBackupManager.localStampMillis(it) },
            )
            assertEquals(
                listOf(newest.name, older.name, foreign.name),
                ordered.map { it.name },
            )

            val victims = AutoBackupManager.selectLocalRotationVictims(
                files = ordered,
                keep = 1,
                victimEligible = { AutoBackupManager.localStampMillis(it) != null },
            )
            assertEquals(listOf(older.name), victims.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `localStampMillis only answers for names the writer could have minted`() {
        val dir = freshDir()
        try {
            assertTrue(
                AutoBackupManager.localStampMillis(
                    namedBackup(dir, "rikkaminis-auto-20261028-120000.json"),
                ) != null,
            )
            // A fetched remote stamp (sanitized, arbitrary) carries no age.
            assertNull(
                AutoBackupManager.localStampMillis(namedBackup(dir, "rikkaminis-auto-2026_10_17.json")),
            )
            assertNull(
                AutoBackupManager.localStampMillis(namedBackup(dir, "rikkaminis-auto-backup.json")),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** Indirection so the rotation tests read like production usage without
 *  depending on an Android Context (pure selection only). */
private object WebDavAutoBackupTestHelpers {
    fun rotate(files: List<File>, keep: Int): List<File> =
        AutoBackupManager.selectLocalRotationVictims(files, keep)
}
