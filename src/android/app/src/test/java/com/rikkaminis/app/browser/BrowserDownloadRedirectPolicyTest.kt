package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R3-B F-03] `startUrlDownload` attaches the WebView's cookie jar to the
 * connection it opens, and used to keep `instanceFollowRedirects = true`.
 * Android's `HttpURLConnection` follows same-protocol hops by itself and keeps
 * a manually-set `Cookie` header across them, so a `302` from a signed-in
 * site to a third-party host (GitHub Release → object storage) sent the source
 * site's session cookie to the third party — and, because the auto-follow
 * resolved those hops before `responseCode` was read, left
 * `MAX_DOWNLOAD_REDIRECT_HOPS` blind to them.
 *
 * The fix resolves every hop by hand: auto-follow off, the cookie jar attached
 * only while the hop stays on the source host, an `https → http` downgrade
 * refused, and the hop budget applied to every hop.
 *
 * The two policy functions are pure so the four rules can be pinned with
 * literals; the wiring arm reads the real source, because the policy is only
 * worth anything if [BrowserTabPool.startUrlDownload] actually calls it.
 */
class BrowserDownloadRedirectPolicyTest {

    // ── the policy: cookies stay on the source host ─────────────────────

    @Test
    fun `same host keeps the source cookie jar`() {
        assertTrue(
            "a download that never left site-a must still be an authenticated " +
                "download — the whole point of reusing the WebView's cookies",
            BrowserTabPool.downloadHopKeepsCredentials(
                "https://site-a.example/download", "https://site-a.example/release/file.zip"
            ),
        )
    }

    @Test
    fun `cross host drops the source cookie jar`() {
        assertFalse(
            "the source host's session cookie must not be sent to a third-party " +
                "host — that is the leak this wave closed",
            BrowserTabPool.downloadHopKeepsCredentials(
                "https://site-a.example/download", "https://cdn-b.example/release/file.zip"
            ),
        )
    }

    @Test
    fun `the host comparison is case insensitive and ignores the port`() {
        assertTrue(
            "hosts are compared case-insensitively (a cookie is not scoped by " +
                "port, so a port change is not a host change)",
            BrowserTabPool.downloadHopKeepsCredentials(
                "https://Site-A.example/download", "https://site-a.example:8443/file.zip"
            ),
        )
    }

    @Test
    fun `a hop with no resolvable host carries nothing`() {
        assertFalse(
            "an unparseable hop must default to no credentials, not to sending them",
            BrowserTabPool.downloadHopKeepsCredentials("https://site-a.example/download", "not a url"),
        )
    }

    // ── the policy: which hops may be followed ──────────────────────────

    @Test
    fun `an https to http downgrade is refused`() {
        val refusal = BrowserTabPool.downloadRedirectRefusal(
            "https://site-a.example/download", "http://cdn-b.example/file.zip", 1, 5
        )
        assertNotNull(
            "a downgrade must not be followed — the payload would travel over an " +
                "unauthenticated channel while the source cookie is still attached",
            refusal,
        )
        assertTrue("the refusal must say why: $refusal", refusal!!.contains("https"))
    }

    @Test
    fun `a same protocol hop is followed`() {
        assertNull(
            "https → https is the redirect the real world uses, and the manual chase " +
                "exists precisely so it still works",
            BrowserTabPool.downloadRedirectRefusal(
                "https://site-a.example/download", "https://cdn-b.example/file.zip", 1, 5
            ),
        )
    }

    @Test
    fun `an http to https upgrade is still followed`() {
        assertNull(
            "only the downgrade is refused; http → https keeps the r3-B09 F-09 " +
                "behaviour the manual chase was introduced for",
            BrowserTabPool.downloadRedirectRefusal(
                "http://site-a.example/download", "https://cdn-b.example/file.zip", 1, 5
            ),
        )
    }

    @Test
    fun `the hop budget is enforced at its boundary`() {
        assertNull(
            "the 5th hop is still inside the budget",
            BrowserTabPool.downloadRedirectRefusal(
                "https://a.example/1", "https://b.example/2", 5, 5
            ),
        )
        val refused = BrowserTabPool.downloadRedirectRefusal(
            "https://a.example/1", "https://b.example/2", 6, 5
        )
        assertNotNull("the 6th hop must be refused", refused)
        assertTrue("the refusal must be the budget one: $refused", refused!!.contains("redirects"))
    }

    // ── the wiring: the policy is actually used ──────────────────────────

    @Test
    fun `the download connection no longer auto-follows redirects`() {
        val body = startUrlDownload()
        assertTrue(
            "auto-follow must be off — it is what carried the source cookie to a " +
                "third-party host and what hid those hops from the budget:\n$body",
            body.contains("c.instanceFollowRedirects = false"),
        )
        assertFalse(
            "no connection in the download path may turn auto-follow back on",
            body.contains("instanceFollowRedirects = true"),
        )
    }

    @Test
    fun `the cookie header is only set when the hop stayed on the source host`() {
        val body = startUrlDownload()
        val guard = body.indexOf("if (downloadHopKeepsCredentials(url, currentUrl)) {")
        assertTrue(
            "the cookie jar must be behind the source-host policy, not attached " +
                "unconditionally:\n$body",
            guard >= 0,
        )
        val cookie = body.indexOf("c.setRequestProperty(\"Cookie\", it)")
        assertTrue(
            "the Cookie request property must live inside that guard — a policy " +
                "that is defined but not consulted is the pre-fix code",
            cookie > guard,
        )
    }

    @Test
    fun `the redirect policy is consulted before the next hop is opened`() {
        val body = startUrlDownload()
        val refused = body.indexOf("downloadRedirectRefusal(")
        assertTrue(
            "the refusal must be evaluated for every hop:\n$body",
            refused >= 0,
        )
        val nextHop = body.indexOf("currentUrl = nextUrl")
        assertTrue(
            "a refused hop must be thrown at before the connection moves on to it",
            refused < nextHop,
        )
        assertTrue(
            "the refusal must abort the download, not be logged and ignored:\n$body",
            body.contains("?.let { throw java.io.IOException(it) }"),
        )
    }

    @Test
    fun `the guard is armed before the redirect is read`() {
        val body = startUrlDownload()
        assertTrue(
            "the policy has to be in place before the first response is read, or " +
                "the first hop still carries the cookie",
            body.indexOf("instanceFollowRedirects = false") <
                body.indexOf("getHeaderField(\"Location\")"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun startUrlDownload(): String {
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserTabPool.kt"
        )
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains("internal fun startUrlDownload(") }
        assertTrue("marker not found: internal fun startUrlDownload(", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") || t.startsWith("    fun ") ||
                t.startsWith("    internal fun ") || t.startsWith("    private fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun readRepoFile(relative: String): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
