package com.rikkaminis.app.debug

import com.rikkaminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ring buffer that captures recent LLM API requests and responses for debugging.
 * Accessible via debug.llmRequests JSON-RPC method.
 */
object LLMRequestLog {

    private const val MAX_ENTRIES = 20

    /**
     * T302: cap each retained `requestBody` at this many characters. Real
     * agent-loop request bodies can run 30+ MB (full message history with
     * heavy tool outputs); pinning 20 of those in a ring buffer was the
     * direct cause of an OOM crash on a HONOR PTP-AN00 — the JVM heap
     * blew past target footprint trying to hold ~600 MB of transient
     * debug strings. The truncated body is still useful for diagnosing
     * cache-prefix stability and small request shape.
     */
    private const val MAX_BODY_CHARS = 64 * 1024

    data class Entry(
        val provider: String,
        val timestamp: Long = System.currentTimeMillis(),
        val requestURL: String,
        val requestMethod: String = "POST",
        val requestHeaders: Map<String, String>,
        val requestBody: String,      // JSON string — capped at MAX_BODY_CHARS by add()
        val durationMs: Long = 0,
        val responseStatusCode: Int = 0,
        val responseBody: String = "", // first 2000 chars
        val usage: JSONObject? = null,
    )

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun add(entry: Entry) {
        // T302: belt-and-suspenders. Release builds short-circuit the whole
        // log because the only consumer is the debug.llmRequests JSON-RPC
        // surface, which release users never reach — keeping a 20-deep ring
        // buffer of multi-MB request bodies hot for nobody is exactly the
        // OOM the HONOR PTP-AN00 user hit. Debug builds still record, with
        // each entry truncated to MAX_BODY_CHARS so even a single retained
        // entry can't push a memory-tight device over the line.
        if (!BuildConfig.DEBUG) return
        val safeEntry = if (entry.requestBody.length > MAX_BODY_CHARS) {
            entry.copy(
                requestBody = entry.requestBody.take(MAX_BODY_CHARS) +
                    "\n…[truncated ${entry.requestBody.length - MAX_BODY_CHARS} chars]",
            )
        } else entry
        entries.add(safeEntry)
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun getAll(): List<Entry> = entries.toList()

    @Synchronized
    fun getLast(n: Int): List<Entry> = entries.takeLast(n)

    @Synchronized
    fun clear() = entries.clear()

    /** Case-insensitive sensitive header names whose values must never be
     *  emitted by the debug endpoint (they can contain API keys/tokens). */
    private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "cookie")

    /** Sensitive URL query-parameter names. Values under these keys are
     *  replaced in `redactURL`. Matched case-insensitively. `key` is the
     *  dominant carrier (Gemini's `?key=` pattern); the rest are the common
     *  alternatives. */
    private val SENSITIVE_QUERY_KEYS = setOf(
        "key", "api_key", "apikey", "api-key", "token", "access_token",
        "secret", "password", "signature", "sig", "auth",
    )

    /**
     * [T-android-debugserver-auth] Redact sensitive URL query-parameter
     * values before serialization. `redactHeaders` only covers the header
     * map; providers that carry credentials in the URL (Gemini's `?key=…`)
     * would otherwise leak through `requestURL` untouched. Each sensitive
     * key's value is replaced with "[redacted:len]", leaving benign query
     * params and the path/host verbatim.
     */
    internal fun redactURL(url: String): String {
        if (url.isEmpty()) return url
        val qIdx = url.indexOf('?')
        if (qIdx < 0 || qIdx == url.length - 1) return url
        val base = url.substring(0, qIdx + 1)
        val query = url.substring(qIdx + 1)
        // Preserve the fragment if present (shouldn't be for API URLs, but be safe).
        val fragIdx = query.indexOf('#')
        val (qBody, frag) = if (fragIdx >= 0) query.substring(0, fragIdx) to query.substring(fragIdx) else query to ""
        val redacted = qBody.split('&').joinToString("&") { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) {
                pair
            } else {
                val k = pair.substring(0, eqIdx)
                val v = pair.substring(eqIdx + 1)
                if (k.lowercase() in SENSITIVE_QUERY_KEYS) "$k=[redacted:${v.length}]" else pair
            }
        }
        return base + redacted + frag
    }

    /**
     * [T-android-debugserver-auth] Mask common credential shapes inside a
     * free-text value (used on `requestBody`). Mirrors
     * [com.rikkaminis.app.tools.AgentTraceRecorder.redactSecrets] so the debug
     * RPC surface uses the same last-line-of-defence rule set as agent traces.
     */
    internal fun redactSecrets(text: String): String {
        var out = text
        out = out.replace(Regex("""sk-[A-Za-z0-9_-]{8,}"""), "sk-***")
        out = out.replace(Regex("""ghp_[A-Za-z0-9]{20,}"""), "ghp_***")
        out = out.replace(Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{10,}"""), "Bearer ***")
        out = out.replace(
            Regex("""(?i)(api[_-]?key|token|secret|password)(["']?\s*[:=]\s*["']?)[A-Za-z0-9_./+-]{8,}"""),
            "$1$2***",
        )
        return out
    }

    /**
     * [T-android-debugserver-auth] Redact sensitive request-header values
     * before serialization. Authorization / x-api-key / cookie (matched
     * case-insensitively, e.g. "X-API-Key" or "Authorization") are replaced
     * with "[redacted:len]" so `debug.llmRequests` / `debug.agentTrace`
     * never leak credential material to the wire.
     */
    internal fun redactHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        return headers.map { (k, v) ->
            if (k.lowercase() in SENSITIVE_HEADERS) k to "[redacted:${v.length}]" else k to v
        }.toMap()
    }

    fun toJSON(last: Int? = null): JSONObject {
        val list = if (last != null) getLast(last) else getAll()
        val array = JSONArray()
        for (entry in list) {
            array.put(JSONObject().apply {
                put("provider", entry.provider)
                put("timestamp", entry.timestamp)
                put("requestURL", redactURL(entry.requestURL))
                put("requestMethod", entry.requestMethod)
                put("requestHeaders", JSONObject(redactHeaders(entry.requestHeaders)))
                put("requestBody", redactSecrets(entry.requestBody))
                put("durationMs", entry.durationMs)
                put("responseStatusCode", entry.responseStatusCode)
                if (entry.responseBody.isNotEmpty()) {
                    put("responseBody", redactSecrets(entry.responseBody))
                }
                if (entry.usage != null) {
                    put("usage", entry.usage)
                }
            })
        }
        return JSONObject().apply {
            put("count", list.size)
            put("requests", array)
        }
    }
}
