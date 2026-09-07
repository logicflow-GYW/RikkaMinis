package com.openminis.app.provider.openai

// [refactor/split-provider] Batch 3: OkHttp network EventListener moved
// VERBATIM from OpenAIProvider.kt (was the trailing private class).
// Private -> internal: OpenAIProvider constructs it in its OkHttpClient
// builder. Pure diagnostics — logs callStart/dns/tls timings to AppLogger.

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Protocol
import okhttp3.Connection
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.InetSocketAddress

/**
 * [T-android-openai-codex-timeout]
 * Network-leg trace listener for OpenAIProvider's OkHttpClient. Logs every
 * OkHttp call lifecycle event with timestamps so a future SocketTimeout
 * report can be triaged to a specific leg:
 *
 *   - dnsStart / dnsEnd          : was the host resolvable, how long
 *   - proxySelect{Start,End}     : which proxy (or DIRECT) routed this
 *   - connectStart / -End / -Failed : TCP connect to proxy or origin
 *   - secureConnect{Start,End}   : TLS handshake duration + cipher / alpn
 *   - connectionAcquired/Released: which physical connection served the
 *                                  call — repeated calls reusing the
 *                                  same Connection identityHash mean
 *                                  the OkHttp pool is recycling, useful
 *                                  for spotting "stale-proxy-mid-stream"
 *   - requestHeaders/BodyEnd     : when the request was fully sent
 *   - responseHeadersStart/End   : time to first server byte (the TFB
 *                                  number tells us whether the proxy
 *                                  was slow vs. the origin)
 *   - responseBodyStart/End      : SSE stream lifecycle — `End` firing
 *                                  with a SocketTimeout root cause is
 *                                  the classic "mid-stream silence" case
 *   - callFailed                 : terminal — pairs the failure to the
 *                                  earliest leg that completed cleanly
 *
 * One instance per call (the factory in OpenAIProvider). Holds a
 * monotonic start timestamp so all log lines carry a relative offset
 * from callStart.
 */
internal class OkHttpNetTraceListener : EventListener() {
    private val tag = "OkHttpNetTrace"
    private val t0 = System.nanoTime()
    private fun ms(): Long = (System.nanoTime() - t0) / 1_000_000L
    private fun callTag(call: Call): String {
        val id = System.identityHashCode(call).toString(16)
        return "call#$id"
    }

    override fun callStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callStart url=${call.request().url}"
        )
    }

    override fun proxySelectStart(call: Call, url: HttpUrl) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectStart host=${url.host}"
        )
    }

    override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms proxySelectEnd host=${url.host} chain=${proxies.joinToString(",") { it.toString() }}"
        )
    }

    override fun dnsStart(call: Call, domainName: String) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsStart host=$domainName"
        )
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms dnsEnd host=$domainName resolved=${inetAddressList.size} addrs=${inetAddressList.take(3).joinToString(",") { it.hostAddress ?: "?" }}"
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectStart target=$inetSocketAddress proxy=$proxy"
        )
    }

    override fun secureConnectStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsStart"
        )
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms tlsEnd version=${handshake?.tlsVersion} cipher=${handshake?.cipherSuite}"
        )
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectEnd target=$inetSocketAddress proxy=$proxy proto=$protocol"
        )
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms connectFailed target=$inetSocketAddress proxy=$proxy proto=$protocol err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionAcquired conn#$conn route=${connection.route()} proto=${connection.protocol()}"
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        val conn = System.identityHashCode(connection).toString(16)
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms connectionReleased conn#$conn"
        )
    }

    override fun requestHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersStart"
        )
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestHeadersEnd"
        )
    }

    override fun requestBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyStart"
        )
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms requestBodyEnd bytes=$byteCount"
        )
    }

    override fun responseHeadersStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersStart (server first byte)"
        )
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseHeadersEnd status=${response.code} proto=${response.protocol}"
        )
    }

    override fun responseBodyStart(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyStart"
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms responseBodyEnd bytes=$byteCount"
        )
    }

    override fun callEnd(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms callEnd"
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        // The most diagnostic of all: pairs the failure with whatever
        // milestone WAS reached before it. Read alongside the listener's
        // earlier lines to localize the stall.
        com.openminis.app.logging.AppLogger.warning(
            tag,
            "[${callTag(call)}] +${ms()}ms callFailed err=${ioe.javaClass.simpleName}:${ioe.message}"
        )
    }

    override fun canceled(call: Call) {
        com.openminis.app.logging.AppLogger.info(
            tag,
            "[${callTag(call)}] +${ms()}ms canceled"
        )
    }
}

/**
 * [T-length-wall-prefill] Pure decision: does this OpenAI-compatible base URL
 * accept an assistant-final prefill (continuation of a truncated reply)?
 *
 * Allowlist — official OpenAI + Azure + the known OpenAI-compatible gateways
 * (OpenRouter / DashScope / Volcengine Ark / official DeepSeek) honor a
 * trailing assistant prefill. STRICT third-party relays that require the last
 * message to be USER (tokenrhythm-class proxies) reject it with a 400, so
 * unknown bases default to NO prefill (behaviour unchanged from before).
 */
internal fun supportsPrefillForOpenAIBase(basePath: String, isAzure: Boolean): Boolean =
    isAzure ||
        basePath.lowercase().let { b ->
            b.startsWith("https://api.openai.com") ||
                b.contains("openrouter.ai") ||
                b.contains("dashscope") ||
                b.contains("volces") ||
                b.contains("ark.") ||
                b.contains("api.deepseek.com")
        }
