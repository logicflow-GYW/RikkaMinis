package com.rikkaminis.app.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV server configuration for remote backup.
 *
 * Mirrors the rikkahub (AGPL-3.0) WebDavConfig shape — `url` is the server
 * root (e.g. `https://dav.jianguoyun.com/dav`), `path` the directory backups
 * live in (default [DEFAULT_BACKUP_DIR]). Unlike rikkahub, the password is
 * NOT kept in this plain data class on the wire: [WebDavConfigStore] persists
 * it in EncryptedSharedPreferences and only hands the decrypted value to the
 * client for the duration of a request.
 */
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = DEFAULT_BACKUP_DIR,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_BACKUP_DIR = "RikkaMinis_backups"
    }
}

/** HTTP/transport failure of a WebDAV operation. [statusCode] is -1 for
 *  non-HTTP failures (bad URL, unreachable host). */
class WebDavException(
    message: String,
    val statusCode: Int,
) : IOException(message)

/**
 * [fix/p1-webdav-propf] The server answered a request with a 2xx status but
 * the body is not a WebDAV document — a captive portal, a reverse-proxy error
 * page or an SSO login page served in place of the DAV reply.
 *
 * Deliberately NOT a [WebDavException]: that type carries an HTTP status that
 * callers translate ("404 → the folder does not exist yet", see
 * [WebDavSync.listBackupFiles]), and a login page answered with 200 must
 * never be flattenable into "the server has no backups". [statusCode] is kept
 * for diagnostics only; the message is what the user sees (it reaches them
 * through the IOException branch of the backup screens' error mapper).
 *
 * [fix/webdav-method-judgement] The same 2xx-but-not-DAV shape is now judged
 * on PUT/GET/DELETE too (a login page in place of the upload/download/delete
 * answer), not just on PROPFIND — see [assertNotPortalBody].
 */
class WebDavNonDavResponse(
    message: String,
    val statusCode: Int,
) : IOException(message)

/** One entry returned by a PROPFIND. */
data class WebDavResourceInfo(
    val href: String,
    val displayName: String,
    val contentLength: Long,
    val lastModified: Instant?,
    val isCollection: Boolean,
)

/** A backup file as shown in the remote-backups list. */
data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

/**
 * Minimal WebDAV client over the app's existing OkHttp stack.
 *
 * Design follows rikkahub's WebDavClient (AGPL-3.0) — segment-based URL
 * building, PROPFIND depth-1 listing, MKCOL with 405 tolerance, an
 * ensureCollectionExists() dance — but reimplemented on OkHttp's synchronous
 * API with NO Android dependencies so the whole class is exercisable from a
 * JVM unit test (see WebDavClientTest, MockWebServer).
 *
 * All methods are blocking; callers run them on Dispatchers.IO.
 */
class WebDavClient(
    private val config: WebDavConfig,
    private val client: OkHttpClient,
) {
    companion object {
        private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
            |<D:propfind xmlns:D="DAV:">
            |  <D:prop>
            |    <D:displayname/>
            |    <D:getcontentlength/>
            |    <D:getcontenttype/>
            |    <D:getlastmodified/>
            |    <D:resourcetype/>
            |  </D:prop>
            |</D:propfind>""".trimMargin()

        /** Shared client for app use; tests inject their own. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ── Path building ─────────────────────────────────────────────────────

    /**
     * Transport security: HTTP Basic auth puts credentials on the wire in
     * cleartext unless TLS protects them, and the backup payload can carry
     * every provider API key when the user opts in — so this is the last
     * gate before credentials leave the device. Non-https servers are allowed
     * ONLY for loopback / private-network hosts (dev servers, LAN NAS);
     * a public `http://` endpoint is REFUSED instead of guessed at, with the
     * reason in the message (surfaced to the user via WebDavException →
     * webDavErrorMessage's IOException branch).
     */
    internal fun isLoopbackOrPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.startsWith("127.")) return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        // 172.16.0.0/12
        if (h.startsWith("172.")) {
            val second = h.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    /** `url` + configured `path` + any extra segments, each URL-encoded. */
    fun buildUrl(vararg segments: String): HttpUrl {
        val base = config.url.trim().toHttpUrlOrNull()
            ?: throw WebDavException("Invalid server URL: ${config.url}", -1)
        if (!base.isHttps && !isLoopbackOrPrivateHost(base.host)) {
            throw WebDavException(
                "Refusing to send credentials over plain HTTP to ${base.host}: " +
                    "WebDAV uses HTTP Basic auth, so non-HTTPS servers are only allowed " +
                    "for loopback or private hosts (localhost, 127.x, 10.x, 172.16-31.x, " +
                    "192.168.x). Use an https:// server URL.",
                -1,
            )
        }
        val builder = base.newBuilder()
        val parts = mutableListOf<String>()
        config.path.trim('/').takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        for (segment in segments) {
            segment.trim('/').takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        for (part in parts) {
            for (segment in part.split('/')) {
                // [audit-0917] addPathSegment percent-encodes '/', but it does
                // NOT neutralise dot segments: HttpUrl.Builder resolves a
                // segment that is exactly "." or ".." and pops the previous
                // path element. Measured against okhttp 4.12.0 — base
                // https://h/dav/ + ".." + "escape.json" builds
                // https://h/dav/escape.json, so a config- or caller-supplied
                // path could climb out of the backup directory.
                //
                // Re-encoding is NOT a fix: addPathSegment("%2E%2E") writes
                // "%252E%252E" (the server sees a literal "%2E%2E" directory)
                // and addEncodedPathSegment("..") is resolved exactly like the
                // decoded form. Refuse instead of guessing — a dot segment in
                // a backup path is never a legitimate request.
                if (segment == "." || segment == "..") {
                    throw WebDavException(
                        "Refusing a path segment that resolves outside the backup " +
                            "directory: \"$segment\" in \"$part\"",
                        -1,
                    )
                }
                builder.addPathSegment(segment)
            }
        }
        return builder.build()
    }

    private fun Request.Builder.auth(): Request.Builder {
        if (config.username.isNotBlank()) {
            header("Authorization", Credentials.basic(config.username, config.password))
        }
        return this
    }

    private inline fun <T> Response.useSafe(block: (Response) -> T): T =
        use(block)

    /**
     * [fix/webdav-method-judgement] Shared body judge for PUT/GET/DELETE — the
     * same seam [parsePropfind] guards for PROPFIND. A captive portal, a
     * reverse-proxy error page or an SSO login page answers 2xx with an HTML
     * page; judging only the status code made PUT report "backup uploaded"
     * with nothing on the server, handed GET's login page to
     * [ConfigBackup.import] (a misleading JSON parse error downstream), and
     * reported DELETEs that never happened.
     *
     * Judge by the FIRST bytes only, deliberately conservative: a JSON backup
     * payload starts with `{`/`[` (after an optional BOM/whitespace), never
     * with `<`, so a body that OPENS with `<!DOCTYPE` or `<html` is certainly
     * not what we asked for. A body that merely CONTAINS "<html" deeper in
     * (user message content inside a real backup) passes untouched — that is
     * why this is not a substring scan. `<?xml` is deliberately NOT judged:
     * a legitimate DELETE may answer 207 with a multistatus document, and
     * misclassifying a real DAV server as a portal would be the worse error.
     * The client layer does not validate JSON (that is the importer's job) —
     * this only refuses the one shape that is definitely not a DAV answer.
     */
    private fun assertNotPortalBody(code: Int, body: ByteArray, what: String) {
        if (body.isEmpty()) return
        var start = if (body.size >= 3 &&
            body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()
        ) 3 else 0
        while (start < body.size) {
            val b = body[start].toInt()
            if (b == 0x20 || b == 0x0A || b == 0x0D || b == 0x09) start++ else break
        }
        val head = String(body, start, minOf(body.size - start, 256), Charsets.UTF_8).lowercase()
        val isHtml = head.startsWith("<!doctype") || head.startsWith("<html")
        if (!isHtml) return
        throw WebDavNonDavResponse(
            "$what returned HTTP $code but the body is an HTML page (a Wi-Fi " +
                "login portal or a proxy page), not a WebDAV/backup response.",
            code,
        )
    }

    // ── Operations ─────────────────────────────────────────────────────────

    /** PROPFIND [path] (default: the backup directory) at [depth]. */
    fun propfind(path: String = "", depth: Int = 1): List<WebDavResourceInfo> {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth.toString())
            .auth()
            .build()
        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (code != 207 && !response.isSuccessful) {
                throw WebDavException("PROPFIND failed (HTTP $code)", code)
            }
            val xml = response.body?.string().orEmpty()
            // [fix/p1-webdav-propf] `isSuccessful` is NOT permission to read
            // the body as DAV. A captive portal, a reverse-proxy error page
            // and an SSO login page all answer PROPFIND with 200 + HTML; the
            // old code accepted that, the parser found zero <response>
            // elements, and the caller rendered an EMPTY BACKUP LIST — the
            // user was told "the server has no backups" by a server we never
            // actually talked to, while testConnection() (same path, depth 0)
            // reported success. The body is the authority: a genuine answer is
            // an XML multistatus document (RFC 4918 §13), so its absence is a
            // failure even on 200/207. Content-Type is NOT the judge — plenty
            // of DAV servers omit it or send it wrong.
            return parsePropfind(xml, url) ?: throw WebDavNonDavResponse(
                if (code == 207) {
                    "Server returned an unreadable WebDAV response (HTTP 207): " +
                        "the body is not a multistatus document."
                } else {
                    "Server returned a non-WebDAV response (HTTP $code) — " +
                        "possibly a Wi-Fi login portal or a proxy page."
                },
                code,
            )
        }
    }

    /** Depth-1 listing of the backup directory, minus the directory itself. */
    fun list(path: String = ""): List<WebDavResourceInfo> {
        val requestPath = buildUrl(path).encodedPath.trimEnd('/')
        return propfind(path, depth = 1).filter { resource ->
            hrefPath(resource.href) != requestPath
        }
    }

    /** Upload [bytes] to [path] under the backup directory, creating the
     *  directory first if the server says it is missing. */
    fun put(
        path: String,
        bytes: ByteArray,
        contentType: String = "application/json",
    ) {
        val url = buildUrl(path)
        val request = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .auth()
            .build()

        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (response.isSuccessful) {
                // [fix/webdav-method-judgement] 200 is NOT permission to claim
                // the upload landed: a captive portal answers PUT with 200 + an
                // HTML login page and nothing is stored. An empty body and a
                // JSON body both pass — legitimate servers answer PUT with
                // either.
                assertNotPortalBody(code, response.body?.bytes() ?: ByteArray(0), "Upload")
                return
            }
            if (code != 409 && code != 404) {
                throw WebDavException("Upload failed (HTTP $code)", code)
            }
        }
        // Parent collection missing (409 Conflict / 404) — create the parent
        // and retry. [T-backup-put-parent] The old code ensured only the root
        // collection, so uploading into a nested path (e.g. "auto/<name>")
        // whose parent dir didn't exist failed twice. Creating the parent of
        // the target path (root for flat filenames) covers both cases.
        val parentSegs = path.substringBeforeLast('/', "")
            .split('/')
            .filter { it.isNotBlank() }
        if (parentSegs.isEmpty()) {
            ensureCollectionExists()
        } else {
            ensureCollectionExists(*parentSegs.toTypedArray())
        }
        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (!response.isSuccessful) {
                throw WebDavException("Upload failed (HTTP $code)", code)
            }
            // Same body judge on the retry path: the portal answers both
            // attempts identically, so only one of them getting the check
            // would leave the hole open.
            assertNotPortalBody(code, response.body?.bytes() ?: ByteArray(0), "Upload")
        }
    }

    /**
     * Download [path] as raw bytes. Callers feed this to
     * [ConfigBackup.import], so the body is judged, not just the status:
     * [fix/webdav-method-judgement] a captive portal answers GET with 200 +
     * an HTML login page (redirects land there too), and the old code handed
     * that page to the importer, which reported a misleading JSON parse
     * error. An empty 200 body is refused as well — a real backup document
     * is never zero bytes, so an empty answer means the server did not
     * actually serve the file.
     */
    fun get(path: String): ByteArray {
        val url = buildUrl(path)
        val request = Request.Builder().url(url).get().auth().build()
        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (!response.isSuccessful) {
                throw WebDavException("Download failed (HTTP $code)", code)
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) {
                throw WebDavNonDavResponse(
                    "Download returned HTTP $code with an empty body — the " +
                        "server did not actually serve a backup file.",
                    code,
                )
            }
            assertNotPortalBody(code, bytes, "Download")
            return bytes
        }
    }

    /**
     * Delete [path]. 204 (or 200) with an empty body is the normal success; a
     * portal answering 200 + HTML is refused like PUT/GET
     * ([fix/webdav-method-judgement]) — the old code reported a deletion that
     * never happened.
     *
     * Registered residual, not fixed here: a legitimate 207 multistatus
     * listing failed entries still reads as success (a 207 is 2xx). Seeing
     * inside it needs a status-level parse the current parser does not do;
     * single-file DELETEs rarely answer 207, so this stays a registered
     * residual rather than a second parser.
     */
    fun delete(path: String) {
        val url = buildUrl(path)
        val request = Request.Builder().url(url).delete().auth().build()
        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (!response.isSuccessful) {
                throw WebDavException("Delete failed (HTTP $code)", code)
            }
            assertNotPortalBody(code, response.body?.bytes() ?: ByteArray(0), "Delete")
        }
    }

    /** Create the backup directory. 201 (created) and 405 (already exists)
     *  both count as success. */
    fun mkcol(vararg segs: String) {
        val url = buildUrl(*segs)
        val request = Request.Builder().url(url).method("MKCOL", null).auth().build()
        client.newCall(request).execute().useSafe { response ->
            val code = response.code
            if (code != 201 && code != 405) {
                throw WebDavException("Failed to create backup directory (HTTP $code)", code)
            }
        }
    }

    /** Ensure the backup directory exists — PROPFIND depth 0; MKCOL only on
     *  a genuine 404 (auth/other errors propagate untouched). */
    fun ensureCollectionExists(vararg segs: String) {
        val exists = try {
            propfind(path = segs.joinToString("/"), depth = 0)
            true
        } catch (e: WebDavException) {
            if (e.statusCode == 404) false else throw e
        }
        if (!exists) mkcol(*segs)
    }

    /** Verify the server answers: PROPFIND depth 0 on the backup directory.
     *  Throws [WebDavException] on any failure.
     *
     *  [fix/p1-webdav-propf] A 2xx answer that is not a WebDAV document
     *  (captive portal / login page / proxy error page) propagates as
     *  [WebDavNonDavResponse] instead of being swallowed here: the connection
     *  test must fail exactly when the backup list fails, otherwise the user
     *  gets "connection OK" and "no backups on the server" from the same
     *  broken link. Only a genuine 404 is tolerated (it means "the folder
     *  does not exist yet", which upload creates). */
    fun testConnection() {
        try {
            propfind(depth = 0)
        } catch (e: WebDavException) {
            // [T-backup-testconn-404] A 404 just means the configured folder
            // does not exist yet — the first backup / sync upload auto-creates
            // it (WebDavClient.put → ensureCollectionExists). Credentials and
            // URL are still valid, so this is NOT a connection failure.
            if (e.statusCode != 404) throw e
        }
    }

    // ── PROPFIND response parsing ──────────────────────────────────────────

    /**
     * [fix/p1-webdav-propf] Parse a PROPFIND body, returning `null` when it is
     * not a WebDAV multistatus document at all — HTML login page, an error
     * page echoing the word "multistatus" back at us, truncated garbage,
     * unparsable XML. An EMPTY LIST keeps its old meaning: a genuine
     * multistatus with zero `<response>` elements, i.e. a legitimately empty
     * folder.
     *
     * Two independent signals, both required, no Content-Type:
     *  1. the body mentions `multistatus` (case-insensitive) — an HTML page
     *     from a portal does not, so the XML machinery never starts;
     *  2. the DOCUMENT ROOT, as actually parsed, is `multistatus` (namespace
     *     prefix ignored) — a page that merely contains the word fails here.
     */
    private fun parsePropfind(xml: String, requestUrl: HttpUrl): List<WebDavResourceInfo>? {
        if (!xml.contains("multistatus", ignoreCase = true)) return null
        return try {
            parseMultistatus(xml, requestUrl)
        } catch (e: Exception) {
            // XmlPullParserException / IOException / anything else: "we could
            // not read the answer" is not "the folder is empty". Swallowed
            // here so the caller gets one type (null) from the seam.
            null
        }
    }

    /** Strict multistatus parse: `null` when the root element is not
     *  `multistatus`, the resource list otherwise (possibly empty). */
    private fun parseMultistatus(
        xml: String,
        requestUrl: HttpUrl,
    ): List<WebDavResourceInfo>? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val requestPath = requestUrl.encodedPath.trimEnd('/')
        val resources = mutableListOf<WebDavResourceInfo>()

        var inResponse = false
        // [R5-B2 F-07①] Fail-closed: a response whose status was never written
        // is not a success. It used to start at `true`, so a malformed answer
        // (no `<status>` at all) was read as "200 OK" and its properties were
        // committed.
        var statusOk = false
        var href: String? = null
        var displayName: String? = null
        var contentLength = 0L
        var lastModified: Instant? = null
        var isCollection = false
        var lastTag: String? = null
        var rootSeen = false
        // [FIX-R3-D / F-10] RFC 4918 allows a <response> to carry SEVERAL
        // <propstat> blocks, each with its own <status>: servers put the
        // properties they could answer in the 200 group and the missing
        // optional ones in a second group with 404. The parser kept ONE
        // `statusOk` for the whole response, so that second group's
        // "HTTP/1.1 404 Not Found" overwrote the 200 and the resource was
        // dropped — a folder that lists fine in every other client came back
        // empty here ("you have no remote backups" over a folder full of
        // them). Properties are now staged per <propstat> and committed only
        // when that group is 2xx; the response survives if ANY group was.
        var inPropstat = false
        var sawPropstat = false
        var acceptedPropstat = false
        // [R5-B2 F-07①] Fail-closed, same reason as `statusOk`: a `<propstat>`
        // without a `<status>` (or with an empty one) used to be committed as
        // if it had answered 200.
        var propstatOk = false
        var stagedDisplayName: String? = null
        var stagedContentLength: Long? = null
        var stagedLastModified: Instant? = null
        var stagedIsCollection = false
        // Signal 3: how many elements are still open. XmlPullParser (kxml2)
        // is forgiving — a body that ends mid-element simply reports
        // END_DOCUMENT instead of failing — so "the document was complete"
        // has to be counted, not assumed. A truncated multistatus must not be
        // read as "the folder happens to be empty".
        var openElements = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    openElements++
                    if (!rootSeen) {
                        rootSeen = true
                        // Signal 2: a body whose ROOT is <html> (or anything
                        // else) is not a DAV answer, no matter what words it
                        // contains further down.
                        if (!"multistatus".equals(name, ignoreCase = true)) return null
                    }
                    when (name) {
                        "response" -> {
                            inResponse = true
                            // [fix/composer-paste-lifecycle F-A-02] No reset
                            // to `true` here. This line predated the
                            // fail-closed declaration above and silently
                            // cancelled it: a response with neither
                            // `<propstat>` nor `<status>` (hand-rolled DAV
                            // shims, S3 gateways) was read as "200 OK" again
                            // — exactly the shape the declaration was
                            // written to reject. Only an explicit
                            // `<status>` (below) may set it.
                            href = null
                            displayName = null
                            contentLength = 0
                            lastModified = null
                            isCollection = false
                            inPropstat = false
                            sawPropstat = false
                            acceptedPropstat = false
                            propstatOk = true
                            stagedDisplayName = null
                            stagedContentLength = null
                            stagedLastModified = null
                            stagedIsCollection = false
                        }
                        // [FIX-R3-D / F-10] A new property group starts clean:
                        // its status decides whether what follows it counts.
                        "propstat" -> if (inResponse) {
                            inPropstat = true
                            sawPropstat = true
                            // [R5-B2 F-07①] A new group starts as FAILED: only
                            // an explicit 2xx `<status>` may arm it again.
                            propstatOk = false
                            stagedDisplayName = null
                            stagedContentLength = null
                            stagedLastModified = null
                            stagedIsCollection = false
                        }
                        "collection" -> when {
                            inPropstat -> stagedIsCollection = true
                            inResponse -> isCollection = true
                        }
                    }
                    lastTag = name
                }
                XmlPullParser.TEXT -> {
                    if (inResponse && lastTag != null) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (lastTag) {
                                // [R5-B2 F-07②] An `<href>` inside a
                                // `<propstat>` (lockdiscovery, or a 404 group
                                // that echoes one) is a property value, not the
                                // response's resource path: committing it
                                // overwrote the real file path and every later
                                // consumer (list / dedupe / filename) read the
                                // wrong resource.
                                "href" -> if (!inPropstat) href = text
                                "status" -> if (inPropstat) {
                                    // [R5-B2 F-07①] Explicit 2xx, not `" 200"`:
                                    // the substring missed 201/204 and would
                                    // also have matched inside other text.
                                    propstatOk = isSuccessStatus(text)
                                } else {
                                    // Response-level status (an error response
                                    // with no propstat at all): still the whole
                                    // answer.
                                    statusOk = isSuccessStatus(text)
                                }
                                "displayname" -> if (inPropstat) {
                                    stagedDisplayName = text
                                } else {
                                    displayName = text
                                }
                                "getcontentlength" -> {
                                    val bytes = text.trim('"').toLongOrNull() ?: 0L
                                    if (inPropstat) stagedContentLength = bytes
                                    else contentLength = bytes
                                }
                                "getlastmodified" -> if (inPropstat) {
                                    stagedLastModified = parseLastModified(text)
                                } else {
                                    lastModified = parseLastModified(text)
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    openElements--
                    if (name == "propstat" && inPropstat) {
                        inPropstat = false
                        // Commit only the group that actually answered; the
                        // 404 group's (absent) values must not erase what the
                        // 200 group already established.
                        if (propstatOk) {
                            acceptedPropstat = true
                            stagedDisplayName?.let { displayName = it }
                            stagedContentLength?.let { contentLength = it }
                            stagedLastModified?.let { lastModified = it }
                            if (stagedIsCollection) isCollection = true
                        }
                    }
                    if (name == "response" && inResponse) {
                        inResponse = false
                        val h = href
                        // [FIX-R3-D / F-10] With propstat groups present, the
                        // resource exists iff one group was 2xx; without them
                        // the response-level status is the only evidence.
                        val ok = if (sawPropstat) acceptedPropstat else statusOk
                        if (h != null) {
                            val self = hrefPath(h) == requestPath
                            if (self) {
                                // [R5-B2 F-07③] The requested collection is
                                // itself in the multistatus (depth-0 always,
                                // depth-1 usually). Dropping it silently was
                                // safe only while "no entries" meant "the
                                // folder is empty": a server that answers 207
                                // with `<status>404</status>` /
                                // `403` for the collection made this parse
                                // return an EMPTY LIST, which
                                // `ensureCollectionExists` read as "the folder
                                // exists" (no MKCOL, the following PUT fails)
                                // and `testConnection` read as "connected".
                                // Fail closed instead: the caller's null path
                                // throws, so a failure is reported as one.
                                if (!ok) return null
                            } else if (ok) {
                                resources.add(
                                    WebDavResourceInfo(
                                        href = h,
                                        displayName = displayName ?: decodeHrefSegment(h),
                                        contentLength = contentLength,
                                        lastModified = lastModified,
                                        isCollection = isCollection,
                                    )
                                )
                            }
                        }
                    }
                    lastTag = null
                }
            }
            event = parser.next()
        }
        // Unbalanced elements at end of document = truncated body. Reading it
        // as "zero responses ⇒ empty folder" is exactly the class of lie this
        // seam exists to prevent.
        if (openElements != 0) return null
        // No element at all (plain text that happened to contain the word):
        // not a document either.
        if (!rootSeen) return null
        return resources
    }

    /** [R5-B2 F-07①] A `<status>` line is a success only when the status code it
     *  carries is 2xx.
     *
     *  The old criterion was `text.contains(" 200")` — it missed every other
     *  2xx (201/204 in a multistatus, which RFC 4918 allows) and, being a
     *  substring test, it also had to be read as "contains the word 200" rather
     *  than "is 200". The code is the first 3-digit token, so
     *  `HTTP/1.1 207 Multi-Status`, `HTTP/2 200` and a bare `200 OK` all read
     *  correctly. Anything else (empty, truncated, non-numeric) is NOT a
     *  success — the callers now default to false, so an unreadable status
     *  must never be armed by a guess. */
    private fun isSuccessStatus(statusLine: String): Boolean {
        val code = statusLine.split(' ', '\t')
            .firstOrNull { token -> token.length == 3 && token.all { it.isDigit() } }
            ?: return false
        return code[0] == '2'
    }

    /** Normalize an href (absolute URL or server path) to a bare path. */
    private fun hrefPath(href: String): String {
        val url = href.toHttpUrlOrNull()
        return (url?.encodedPath ?: href).trimEnd('/')
    }

    private fun decodeHrefSegment(href: String): String {
        val raw = href.trimEnd('/').substringAfterLast('/')
        return try {
            // URLDecoder would turn "+" into space; escape it first so literal
            // plus signs in filenames survive.
            URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (e: Exception) {
            raw
        }
    }

    /** WebDAV servers differ on date format — accept RFC 1123 (most common),
     *  RFC 850, and ISO-8601 like rikkahub does. */
    private fun parseLastModified(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        val s = value.trim()
        return try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: Exception) {
            try {
                ZonedDateTime.parse(
                    s,
                    DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
                ).toInstant()
            } catch (e2: Exception) {
                try {
                    Instant.parse(s)
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }
}
