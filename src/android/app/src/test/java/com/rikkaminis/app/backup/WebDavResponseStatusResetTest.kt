package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FULLSCAN-1119 A / F-23] A `<response>` without `<status>` must not inherit
 * the previous response's verdict.
 *
 * `statusOk` is declared `false` once, before the parse loop, and only a
 * `<status>` element assigns it. The `"response" ->` branch reset every other
 * per-response field but not this one — and its comment claimed the omission
 * was deliberate fail-closed behaviour ("No reset to `true` here"), which
 * described a different variable. The effect was the opposite of fail-closed:
 * after a 2xx response, a following response carrying NO `<status>` at all was
 * read as 200 and its resource was listed (and, for the requested collection,
 * accepted as existing).
 *
 * Both orders are pinned: the leak needs a 200 FIRST, so a fix that only ever
 * resets to true, or that drops the reset, shows up in exactly one of them.
 */
class WebDavResponseStatusResetTest {

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

    private fun multistatus(vararg responses: String) =
        """<?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
        ${responses.joinToString("\n")}
        </d:multistatus>"""

    private fun okResponse(href: String) =
        """  <d:response>
             <d:href>$href</d:href>
             <d:status>HTTP/1.1 200 OK</d:status>
           </d:response>"""

    private fun statuslessResponse(href: String) =
        """  <d:response>
             <d:href>$href</d:href>
           </d:response>"""

    private fun notFoundResponse(href: String) =
        """  <d:response>
             <d:href>$href</d:href>
             <d:status>HTTP/1.1 404 Not Found</d:status>
           </d:response>"""

    @Test
    fun aStatuslessResponseAfterA200IsNotListed() {
        enqueue207(
            multistatus(
                okResponse("/dav/RikkaMinis_backups/ok.json"),
                statuslessResponse("/dav/RikkaMinis_backups/mystery.json"),
            )
        )
        val names = dav().list().map { it.displayName }
        assertEquals(listOf("ok.json"), names)
    }

    @Test
    fun aStatuslessResponseBeforeA200IsAlsoNotListed() {
        // Reverse order: the statusless entry must stay dropped regardless of
        // where it sits (this one passes even before the fix — it is the
        // control that catches "reset to true" regressions).
        enqueue207(
            multistatus(
                statuslessResponse("/dav/RikkaMinis_backups/mystery.json"),
                okResponse("/dav/RikkaMinis_backups/ok.json"),
            )
        )
        val names = dav().list().map { it.displayName }
        assertEquals(listOf("ok.json"), names)
    }

    @Test
    fun aStatuslessCollectionAfterA200DoesNotFakeAWorkingFolder() {
        // The requested collection itself, statusless, following a 2xx entry:
        // before the fix it inherited 200 and `list()` returned an empty list
        // (read by callers as "the folder exists and is empty"). Fail-closed
        // now returns null ⇒ the caller sees an error, not an empty folder.
        enqueue207(
            multistatus(
                okResponse("/dav/RikkaMinis_backups/ok.json"),
                statuslessResponse("/dav/RikkaMinis_backups"),
            )
        )
        var threw = false
        try {
            dav().list()
        } catch (t: Throwable) {
            threw = true
        }
        assertTrue("a statusless collection must fail closed, not list as empty", threw)
    }

    @Test
    fun aRealNotFoundStatusStillWinsAfterA200() {
        // Control: an explicit 404 is unaffected by the reset (it was already
        // dropped before the fix) — a "reset to true" regression would let it
        // through.
        enqueue207(
            multistatus(
                okResponse("/dav/RikkaMinis_backups/ok.json"),
                notFoundResponse("/dav/RikkaMinis_backups/gone.json"),
            )
        )
        val names = dav().list().map { it.displayName }
        assertEquals(listOf("ok.json"), names)
    }
}
