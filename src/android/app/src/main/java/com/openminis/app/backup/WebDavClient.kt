package com.openminis.app.backup

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

    /** `url` + configured `path` + any extra segments, each URL-encoded. */
    fun buildUrl(vararg segments: String): HttpUrl {
        val base = config.url.trim().toHttpUrlOrNull()
            ?: throw WebDavException("Invalid server URL: ${config.url}", -1)
        val builder = base.newBuilder()
        val parts = mutableListOf<String>()
        config.path.trim('/').takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        for (segment in segments) {
            segment.trim('/').takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        for (part in parts) {
            for (segment in part.split('/')) {
                // addPathSegment percent-encodes each segment, so a malicious
                // or accidental ".." or "/" inside user input stays a literal
                // path segment instead of escaping the backup directory.
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
            return parsePropfind(xml, url)
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
            if (response.isSuccessful) return
            val code = response.code
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
            if (!response.isSuccessful) {
                throw WebDavException("Upload failed (HTTP ${response.code})", response.code)
            }
        }
    }

    /** Download [path] as raw bytes. */
    fun get(path: String): ByteArray {
        val url = buildUrl(path)
        val request = Request.Builder().url(url).get().auth().build()
        client.newCall(request).execute().useSafe { response ->
            if (!response.isSuccessful) {
                throw WebDavException("Download failed (HTTP ${response.code})", response.code)
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Delete [path]. */
    fun delete(path: String) {
        val url = buildUrl(path)
        val request = Request.Builder().url(url).delete().auth().build()
        client.newCall(request).execute().useSafe { response ->
            if (!response.isSuccessful) {
                throw WebDavException("Delete failed (HTTP ${response.code})", response.code)
            }
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
     *  Throws [WebDavException] on any failure. */
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

    private fun parsePropfind(xml: String, requestUrl: HttpUrl): List<WebDavResourceInfo> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val requestPath = requestUrl.encodedPath.trimEnd('/')
        val resources = mutableListOf<WebDavResourceInfo>()

        var inResponse = false
        var statusOk = true
        var href: String? = null
        var displayName: String? = null
        var contentLength = 0L
        var lastModified: Instant? = null
        var isCollection = false
        var lastTag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when (name) {
                        "response" -> {
                            inResponse = true
                            statusOk = true
                            href = null
                            displayName = null
                            contentLength = 0
                            lastModified = null
                            isCollection = false
                        }
                        "collection" -> if (inResponse) isCollection = true
                    }
                    lastTag = name
                }
                XmlPullParser.TEXT -> {
                    if (inResponse && lastTag != null) {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (lastTag) {
                                "href" -> href = text
                                "displayname" -> displayName = text
                                "getcontentlength" -> contentLength = text.trim('"').toLongOrNull() ?: 0
                                "getlastmodified" -> lastModified = parseLastModified(text)
                                "status" -> statusOk = text.contains(" 200")
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    if (name == "response" && inResponse) {
                        inResponse = false
                        val h = href
                        if (h != null && statusOk && hrefPath(h) != requestPath) {
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
                    lastTag = null
                }
            }
            event = parser.next()
        }
        return resources
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
