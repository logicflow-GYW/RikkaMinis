package com.rikkaminis.app.mcp.oauth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"

        // [verify-1015 BUG-12a] Per-connection read budget. The accept loop is
        // single-threaded and readLine() blocks with NO timeout, so one socket
        // that connects and sends nothing (a browser preconnect / speculative
        // connection, which Chrome opens for the authorization page before any
        // redirect) parked the listener; the real callback then queued behind
        // it and OAuth died on its 5-minute timeout. 5 s is ~1000x a real
        // request (a single TCP segment, milliseconds) yet short enough that a
        // stalled socket is dropped long before the user gives up.
        private const val IDLE_CONNECT_TIMEOUT_MS = 5_000
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        serverSocket = ServerSocket(p, 0, InetAddress.getLoopbackAddress())
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    // [sweep1014-D5a] Every port failed to bind: shut the server
                    // down (running=false + socket cleanup) and fire
                    // onExternalCancel so the caller's in-flight wait resumes
                    // immediately instead of hanging until the 5-minute OAuth
                    // timeout. stop() consumes the callback once, so a later
                    // stop() never double-fires.
                    stop()
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    // [da1018-G] FD safety: every exit from this iteration — the
                    // empty-request-line `continue`, the OPTIONS preflight, the
                    // success path, a parse failure, an exception — releases the
                    // socket in the finally below. The previous code closed only
                    // on the normal paths, so a peer that connected and sent
                    // nothing (`readLine() ?: continue`) leaked one FD per
                    // attempt and could exhaust the descriptor table on a
                    // long-lived listener (ISSUE-11).
                    try {
                        // [verify-1015 BUG-12a] Bound the per-connection read
                        // (see IDLE_CONNECT_TIMEOUT_MS): a connection that
                        // sends nothing must not park the single-threaded
                        // listener ahead of the real callback. On timeout
                        // readLine() throws SocketTimeoutException, which the
                        // catch below already logs; the finally closes it.
                        socket.soTimeout = IDLE_CONNECT_TIMEOUT_MS
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        Log.d(TAG, "Request: $requestLine")

                        // CORS preflight for providers (e.g. xAI) that
                        // OPTIONS /callback from their authorization page
                        // before redirecting the browser. Without this we
                        // return a 404 to the preflight and the browser
                        // never follows the redirect — OAuth stalls.
                        // Read the rest of the headers to find Origin and
                        // only echo back permissive CORS for known xAI hosts.
                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = listOf("auth.x.ai", "accounts.x.ai")
                            val allowOrigin = if (origin != null && trustedHosts.any { host -> origin == "https://$host" }) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            continue
                        }

                        // Parse GET /callback?code=xxx&state=yyy HTTP/1.1
                        val parts = requestLine.split(" ")
                        if (parts.size >= 2) {
                            val uri = URI("http://localhost${ parts[1] }")
                            // [da1018-G] Parse the RAW query and %-decode each
                            // component exactly once. The previous code read
                            // uri.query — already decoded once by the URI getter —
                            // and decoded again, so "%2B" in an authorization code
                            // became a space and "%26" was split into a separate
                            // param, breaking the token exchange for providers
                            // whose codes contain those sequences (ISSUE-11). The
                            // decode shape (escape "+" first) matches the
                            // WebDavClient.decodeHrefSegment precedent.
                            val params = parseCallbackQuery(uri.rawQuery)

                            val code = params["code"]
                            val state = params["state"]
                            // [r3-B11-F08] RFC 6749 §4.1.2.1: a refused
                            // authorization redirects with `error=...` and NO
                            // code. The old code answered "Authorization
                            // complete" to the browser and then neither fired a
                            // callback nor stopped the server, so the in-flight
                            // wait hung for its full 5-minute timeout with no
                            // way for the user to retry.
                            val error = params["error"]
                            // [FIX-FW-OAUTH-D2] PRESENCE of `error` is the
                            // refusal signal (RFC 6749 §4.1.2.1 sends
                            // `error=...` and no code). The old predicate was
                            // `!error.isNullOrBlank()` while the page branch
                            // tested `error != null`, so `?error=` (present,
                            // empty) rendered "not completed" and then fell
                            // through BOTH exits: stop() was never called, the
                            // in-flight wait sat out its full 5-minute timeout
                            // while the browser already showed a failure page.
                            // One predicate now drives the page, the log and
                            // the termination.
                            val denied = callbackDenied(code, error)

                            // Send response. [FIX-FW-OAUTH-D1] The header used
                            // to declare `Content-Length: ${html.length}` — a
                            // UTF-16 code-unit count — while writing UTF-8
                            // bytes, so any non-ASCII `error` made the body
                            // longer than declared (the browser truncates at
                            // the declared length) and the missing `charset`
                            // mojibaked the rest. One builder now owns both the
                            // body bytes and the number that describes them.
                            socket.getOutputStream().write(callbackHttpResponse(callbackPageHtml(code, error)))
                            // Release the FD before invoking the callback below,
                            // which may block while resuming the OAuth wait; the
                            // finally is the backstop for every other path.
                            socket.close()

                            if (denied) {
                                // [FIX-FW-OAUTH-D2] `error=<empty>` and
                                // `error=access_denied` are different facts
                                // (the server refused without a reason vs. with
                                // one) and must stay distinguishable in the log.
                                Log.w(
                                    TAG,
                                    "authorization denied by the server: " +
                                        "error=${if (error.isNullOrEmpty()) "<empty>" else error}",
                                )
                                // Leave onExternalCancel armed: stop() fires it,
                                // which resumes the caller's wait with null
                                // (Result.Cancelled) instead of hanging.
                                stop()
                                return@Thread
                            }

                            if (code != null) {
                                // [sweep1014-D5b] Consume onExternalCancel before
                                // stopping: stop() here is called by the server
                                // itself (the success path), so the onCode caller
                                // never gets a chance to null it out — without
                                // this a successful redirect also fires a phantom
                                // cancel. External stop() calls are unaffected
                                // and still fire onExternalCancel.
                                onExternalCancel = null
                                onCode(code, state)
                                stop()
                                return@Thread
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                    } finally {
                        // [da1018-G] Single guarantee that the connection is
                        // released on every path (a second close is a no-op).
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}

/**
 * [FIX-FW-OAUTH-D2] Single source of truth for "the authorization server
 * refused this request".
 *
 * The redirect carries either a `code` (grant) or an `error` (refusal). The
 * presence of the `error` parameter is the refusal signal, whatever its value:
 * `?error=` (present but empty) is still a refusal the user must not be left
 * waiting on. [parseCallbackQuery] cannot distinguish "absent" from "present
 * and empty" by value alone — `params["error"] == ""` means present, `null`
 * means absent — which is exactly why the predicate keys on nullability and
 * never on `isBlank()`.
 *
 * Consumed by all three readers (page, log, termination) so a future edit
 * cannot re-split them: two predicates for one fact is how the 5-minute hang
 * happened in the first place.
 *
 * Pure string/null work (no Android types) so the JVM test can pin the four
 * code×error combinations directly.
 */
internal fun callbackDenied(code: String?, error: String?): Boolean = code == null && error != null

private const val CALLBACK_SUCCESS_HTML =
    "<html><body><h1>Authorization complete</h1>" +
        "<p>You can close this tab.</p><script>window.close()</script></body></html>"

/**
 * [FIX-FW-OAUTH-D2] The page handed back to the browser for one redirect.
 *
 * Keyed on [callbackDenied] — the same fact that terminates the listener — so
 * the page and the termination can never disagree (that disagreement is what
 * left `?error=` showing a failure page while the server kept waiting).
 *
 * An empty `error` value means "refused, no reason given": rendering
 * `reported: .` there would be a sentence with a hole in it, so that case gets
 * its own wording. The page is English hard-coded (it is browser-facing UI, not
 * app UI) — no i18n keys by design.
 */
internal fun callbackPageHtml(code: String?, error: String?): String {
    if (!callbackDenied(code, error)) return CALLBACK_SUCCESS_HTML
    val shown = error.orEmpty()
    val reason = if (shown.isEmpty()) {
        "<p>The authorization was refused by the server, which gave no reason. " +
            "You can close this tab and try again from the app.</p>"
    } else {
        "<p>The authorization server reported: ${htmlEscape(shown)}. " +
            "You can close this tab and try again from the app.</p>"
    }
    return "<html><body><h1>Authorization was not completed</h1>" + reason +
        "<script>window.close()</script></body></html>"
}

/**
 * [FIX-FW-OAUTH-D1] Serialises one callback response: `head + UTF-8 body`.
 *
 * `Content-Length` MUST count bytes, not characters. The previous code
 * declared `${html.length}` (UTF-16 code units) over UTF-8 bytes, so an
 * `error` containing CJK made the body longer than the declaration and the
 * browser cut the page off at the declared length; `Content-Type` also lacked
 * `charset`, so the surviving bytes were decoded as latin-1.
 *
 * Structural fix: the byte array is produced ONCE and the number in the header
 * is derived from that same array — there is no second encoding step that
 * could disagree with the declaration. Pure JVM (no Android types).
 */
internal fun callbackHttpResponse(html: String): ByteArray {
    val body = html.toByteArray(Charsets.UTF_8)
    val head = "HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/html; charset=utf-8\r\n" +
        "Content-Length: ${body.size}\r\n" +
        "Connection: close\r\n\r\n"
    return head.toByteArray(Charsets.UTF_8) + body
}

/**
 * [r3-B11-F08] Escape a server-supplied value before it is written into the
 * HTML page this listener hands back to the browser. The `error` /
 * `error_description` of an OAuth redirect are text the caller controls (any
 * party that can answer the redirect URI), and the denial page quotes them --
 * unescaped that is markup injected into a page served from a loopback origin
 * the Custom Tab still trusts.
 *
 * Pure string work (no Android types) so the JVM test can pin it directly.
 */
internal fun htmlEscape(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/**
 * [da1018-G] Parses the query component of an OAuth redirect URL into a
 * parameter map, %-decoding each key and value EXACTLY ONCE.
 *
 * [rawQuery] must be the still-encoded form ([java.net.URI.getRawQuery]),
 * never [java.net.URI.getQuery] — the getter has already decoded it once, so
 * a second URLDecoder pass corrupts values: "%2B" in an authorization code
 * became a space and "%26" split the code into two params (ISSUE-11). Keep
 * this function pure (no Android types) — it is unit-tested on the JVM by
 * OAuthCallbackQueryDecodeTest with literal expectations taken from the
 * java.net ground-truth probe, not derived from this implementation.
 *
 * "+" semantics: a literal "+" is preserved as "+", never decoded to a
 * space. This mirrors the [com.rikkaminis.app.backup.WebDavClient]
 * decodeHrefSegment precedent ("+" escaped to %2B before decoding): OAuth
 * codes are not form-urlencoded bodies, a provider that means a plus encodes
 * it as %2B, and both forms must survive the single decode. A space arrives
 * as %20 and still decodes to a space.
 *
 * Robustness: empty segments, a bare key (no '='), and undecodable escape
 * sequences are skipped rather than thrown — a hostile or broken redirect
 * must not kill the listener thread. A bare key maps to the empty string,
 * matching the previous behavior.
 */
internal fun parseCallbackQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val params = LinkedHashMap<String, String>()
    for (segment in rawQuery.split("&")) {
        if (segment.isEmpty()) continue
        val kv = segment.split("=", limit = 2)
        val key = decodeQueryComponentOnce(kv[0]) ?: continue
        if (kv.size > 1) {
            val value = decodeQueryComponentOnce(kv[1]) ?: continue
            params[key] = value
        } else {
            params[key] = ""
        }
    }
    return params
}

/**
 * Single %-decode of one query component. Returns null (skip) instead of
 * throwing on malformed escapes; a literal "+" survives as "+"
 * (WebDavClient shape: escape first, decode once).
 */
private fun decodeQueryComponentOnce(raw: String): String? =
    runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrNull()
