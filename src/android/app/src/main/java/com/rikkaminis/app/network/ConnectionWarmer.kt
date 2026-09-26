package com.rikkaminis.app.network

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * [OPT7-conn-warmup] Pre-arms the TLS/HTTP connection to a provider host so
 * the user's FIRST real request skips DNS + TCP + TLS (+ proxy tunnel)
 * negotiation — typically 1-3s on a cold start, more through a proxy.
 * Ported from Filterrr/RikkaMinis @ 887a7c6 (network pack); adaptations for
 * this repo are noted inline (no DoH here — [NetworkMonitor] has no custom
 * DNS wrapper, and DoH would bypass the user's proxy resolution path).
 *
 * How: a lightweight `HEAD /` (no body) on a bare client that SHARES the LLM
 * connection pool. On success the connection lands in
 * [NetworkMonitor.sharedLLMConnectionPool] (5-min keep-alive).
 *
 * [FIX-audit-P1-warmup] Pool sharing is NECESSARY but not SUFFICIENT for
 * reuse — OkHttp also requires the same route (scheme/host/port/proxy/TLS
 * config). The warmup and the real request both go through clients with NO
 * custom proxy/DNS/interceptors-affecting-routing, so the route matches for
 * every production caller; a custom proxy configured at the app level would
 * apply to both equally. This is a best-effort optimization, not a
 * guarantee — the real request always builds its own connection if reuse
 * doesn't happen.
 *
 * Failure semantics: a failed HEAD rolls the debounce stamp back so the
 * next warm() is eligible after [WarmDebouncer.FAILURE_DEBOUNCE_MS] (8s)
 * — see WarmDebouncer.kt,
 * not the full 60s window — a transient DNS hiccup must not silence warmups
 * for a full minute right as the network recovers.
 *
 * Debounce: one warmup per ORIGIN (scheme://host:port) per window.
 *
 * Privacy note: the HEAD carries NO credentials, no body, no user data —
 * just a bare request to the API origin.
 *
 * [OPT7-warm-reconnect] Network-recovery re-warm: [NetworkMonitor] evicts
 * the shared pool on every network transition, which silently nullifies any
 * earlier warmup — yet a stale 60s success-debounce stamp would drop the
 * next `warm()` call inside the window, so the first request after a
 * Wi-Fi→cellular swap pays the full cold-connect cost. [onNetworkChanged]
 * (fired by [NetworkMonitor] on DISCONNECTED → CONNECTED and interface
 * swaps) (1) clears all debounce stamps and (2) re-warms the most recently
 * warmed origins so the recovery path re-arms the pool before the user's
 * next send.
 */
object ConnectionWarmer {

    private const val TAG = "ConnWarmer"

    /**
     * [P1-6-warm-client-reuse] One client per process. Shares the LLM
     * connection pool so warmed connections land where real requests can
     * reuse them. (The Filterrr source also wired its DoH resolver here via
     * `NetworkMonitor.buildDns()` — deliberately NOT ported: this repo has
     * no DoH layer, and a custom DNS wrapper would bypass the user's proxy
     * resolution path. System default resolution applies, exactly like the
     * real provider requests, which also maximizes route-match reuse.)
     */
    private val warmClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(NetworkMonitor.sharedLLMConnectionPool)
            .connectTimeout(5_000L, TimeUnit.MILLISECONDS)
            .readTimeout(5_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(5_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Fire-and-forget warmup. Safe to call from any thread, any frequency —
     *  internally debounced. [baseUrl] is the provider base URL (origin is
     *  what matters; path/query are stripped). */
    @JvmStatic
    fun warm(baseUrl: String?) {
        val origin = originOf(baseUrl) ?: return
        if (!debouncer.tryBegin(origin)) return
        enqueueWarm(origin)
    }

    /**
     * [OPT7-warm-reconnect] Network transition hook, called by
     * [NetworkMonitor] when connectivity returns (or the interface swaps).
     * Clears every debounce stamp — the pool was just evicted, so ANY origin
     * is worth re-warming regardless of when it was last warmed — then
     * re-warms the most recent [WarmDebouncer.REWARM_ORIGINS] origins
     * immediately. Re-warms bypass the debounce stamps on purpose (they were
     * just cleared); each re-warm issues at most one HEAD.
     */
    @JvmStatic
    fun onNetworkChanged() {
        val targets = debouncer.onNetworkChanged()
        if (targets.isEmpty()) return
        Log.d(TAG, "network changed — re-warming ${targets.size} recent origin(s)")
        targets.forEach { enqueueWarm(it) }
    }

    private val debouncer = WarmDebouncer()

    /** Normalize a base URL to its warmable origin, or null to skip. */
    private fun originOf(baseUrl: String?): String? {
        val url = baseUrl ?: return null
        val httpUrl = try {
            url.trim().toHttpUrl()
        } catch (_: IllegalArgumentException) {
            return null // user-typed / malformed base — ignore
        }
        if (httpUrl.host.isBlank()) return null
        // OkHttp's port is already the effective port (default substituted),
        // so origin is scheme://host:port verbatim.
        return "${httpUrl.scheme}://${httpUrl.host}:${httpUrl.port}"
    }

    /**
     * Issue the HEAD request for [origin] (origin string → URL rebuilt here
     * so both [warm] and [onNetworkChanged] share one enqueue path).
     */
    private fun enqueueWarm(origin: String) {
        val headUrl = try {
            origin.toHttpUrl().newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
        } catch (_: IllegalArgumentException) {
            return
        }

        // No auth headers, short timeouts — this must never delay or outlive
        // its purpose. The connection is pooled regardless of response status
        // (401/404 fine).
        val headRequest = okhttp3.Request.Builder()
            .url(headUrl)
            .method("HEAD", null)
            .build()
        warmClient.newCall(headRequest).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                Log.d(TAG, "warm connection pooled origin=$origin status=${response.code}")
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                // [FIX-audit-P1-warmup] Roll the debounce stamp back so the
                // next warm() call is eligible after the SHORT failure window
                // — a DNS hiccup must not silence warmups for a full minute
                // right as the network recovers.
                debouncer.markFailure(origin)
                Log.d(TAG, "warm skipped origin=$origin: ${e.javaClass.simpleName}")
            }
        })
    }
}
