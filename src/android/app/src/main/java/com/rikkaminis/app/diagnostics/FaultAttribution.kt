package com.rikkaminis.app.diagnostics

/**
 * [absorb-network-pack: feat5-fault-attribution] Translate a provider
 * failure's raw error text into a LAYER attribution: connection-level (this
 * device ↔ endpoint, the user's network/proxy's fault) vs service-level
 * (the endpoint answered, so the relay/provider's fault) vs auth/quota
 * (user must fix credentials). Ported from Filterrr/RikkaMinis @ 887a7c6
 * (network pack), package rename only.
 *
 * Why: the OkHttp EventListener already knows exactly which network leg
 * stalled (OkHttpNetTrace logs it), but logs are write-only for users. When
 * a failure surface says "recent failure", the single most useful next
 * datum is WHOSE fault it likely was — that's what decides whether the user
 * checks their VPN or complains to their relay operator.
 *
 * Signals (checked in priority order, first hit wins):
 *   - auth / quota vocabulary → credentials (fix keys / top up)
 *   - UnknownHost / connect / timeout / reset / TLS vocabulary → connection
 *   - HTTP 4xx/5xx status vocabulary → service (endpoint alive, unhappy)
 *   - worker death / first-chunk budget vocabulary → service
 *     (the endpoint accepted the request and then went silent)
 *   - unknown → ambiguous (shown as "unclear")
 *
 * Pure JVM + unit-testable; deliberately string-based because the failure
 * reasons we classify are already free-form strings coming out of
 * exception messages up the stack.
 */
object FaultAttribution {

    enum class Layer { CONNECTION, SERVICE, CREDENTIALS, UNCLEAR }

    data class Result(val layer: Layer, val labelResKey: String)

    private val CONNECTION_PATTERNS = listOf(
        "unknownhost", "unable to resolve", "dns",          // resolution
        "connectexception", "connection refused",           // TCP
        "sockettimeout", "timeout", "timed out",            // dead air
        "connection reset", "eofexception", "broken pipe",  // cut
        "ssl", "sslhandshake", "tls",                       // TLS leg
        "networkerror", "network is unreachable", "offline",
        "proxy", "tunnel",                                  // proxy path
        "ping",                                             // h2 ping liveness
    )

    private val SERVICE_PATTERNS = listOf(
        "http 4", "http 5", " 400", " 401", " 403", " 429", " 500", " 502", " 503", " 504",
        "bad request", "not found", "internal server", "bad gateway",
        "service unavailable", "overloaded", "rate limit", "expired",
        "first chunk timeout", "first_chunk", "no first chunk",
        "worker died", "modelstreamerror", "modelworkerdied",
        "stream error", "mid-stream", "stream was reset",
    )

    private val CREDENTIAL_PATTERNS = listOf(
        "invalid api key", "invalidapikey", "unauthorized", "incorrect api key",
        "authentication", "quota", "insufficient", "billing", "expired key",
        "permission denied", "forbidden",
    )

    /**
     * Attribute [failureReason] to a layer. Never throws, never returns
     * null — unknown text maps to [Layer.UNCLEAR] (the honest answer when
     * we genuinely can't tell).
     *
     * `labelResKey` names the (future) UI string for the panel; no
     * strings.xml key is added by this change — the UI surface is a
     * registered follow-up item.
     */
    fun attribute(failureReason: String?): Result {
        val raw = failureReason?.lowercase() ?: return Result(Layer.UNCLEAR, "fault_layer_unclear")
        if (raw.isBlank()) return Result(Layer.UNCLEAR, "fault_layer_unclear")

        if (CREDENTIAL_PATTERNS.any { raw.contains(it) }) {
            return Result(Layer.CREDENTIALS, "fault_layer_credentials")
        }
        if (CONNECTION_PATTERNS.any { raw.contains(it) }) {
            return Result(Layer.CONNECTION, "fault_layer_connection")
        }
        if (SERVICE_PATTERNS.any { raw.contains(it) }) {
            return Result(Layer.SERVICE, "fault_layer_service")
        }
        return Result(Layer.UNCLEAR, "fault_layer_unclear")
    }
}
