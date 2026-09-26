package com.rikkaminis.app.provider.openai

// [refactor/split-provider → absorb-network-pack] This file used to hold the
// OkHttp network EventListener (moved VERBATIM from OpenAIProvider.kt, then
// private -> internal). The listener class has been PROMOTED to
// com.rikkaminis.app.network.OkHttpNetTraceListener so the Anthropic and
// Gemini clients can attach it too; what remains here is only OpenAI's
// base-URL predicate helpers. OpenAIProvider now references the shared
// network-package listener directly.

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

/**
 * [GH#377] Pure decision: the wire value for "thinking OFF", or null to omit the
 * field. Extracted from `OpenAIProvider.explicitOffEffort()` so the boundary is
 * JVM-testable without a MockWebServer — same shape as the prefill predicate
 * above.
 *
 * Two independent questions, two independent predicates:
 *  1. ALLOWLIST (which vendor documents an off tier) — official OpenAI → "none",
 *     Volcano Ark → "minimal", everyone else → omit. Azure omits: its off tier is
 *     model-dependent, so an explicit value risks a 400.
 *  2. DECLARED SET (what the model says it accepts) — a veto. Emitting a tier the
 *     catalog does not list gets the whole request rejected (400), which is what
 *     GH#377 reported. The base URL cannot answer this question: the same model
 *     behind an official-looking base still declared `low..max` (no `none`), and
 *     the old predicate read the base, not the model. See the experiment note in
 *     `OpenAIProvider.explicitOffEffort`.
 *
 * [unifiedEffortGateway] exempts the veto, mirroring the rule layer's
 * `usesUnifiedReasoningEffort || declared.contains(v)` predicate
 * (ThinkingRuleResolver). A unified gateway (Ark / Azure / Venice) re-exposes
 * third-party models behind ONE surface whose off tier is the GATEWAY's, not the
 * hosted model's — so the catalog's declared set describes the model's native
 * endpoint and does not govern what this surface accepts. Applying the veto here
 * would silently drop Ark's documented `minimal` (caught by the golden snapshot:
 * `deepseek-v4-unified/OFF` went from `{reasoning_effort:"minimal"}` to `{}`).
 *
 * `declaredEffortValues == null` means "the catalog never heard of this model" —
 * NOT "declares nothing" (that is the separate `declaresNoEffortTiers` flag), so
 * it stays permissive. Treating unknown as a veto would silently re-disable the
 * explicit-off behaviour for every uncovered model, i.e. revert #377's fix.
 */
internal fun explicitOffEffortFor(
    basePath: String,
    isAzure: Boolean,
    modelId: String,
    declaredEffortValues: List<String>?,
    unifiedEffortGateway: Boolean = false,
): String? {
    if (isAzure) return null
    val base = basePath.lowercase()
    val candidate = when {
        base.startsWith("https://api.openai.com") -> "none"
        else -> {
            val lid = modelId.lowercase()
            if (base.contains("volces") || base.contains("ark.") ||
                lid.contains("seed-") || lid.contains("doubao")
            ) {
                "minimal"
            } else null
        }
    } ?: return null
    if (!unifiedEffortGateway &&
        declaredEffortValues != null && !declaredEffortValues.contains(candidate)
    ) {
        return null
    }
    return candidate
}
