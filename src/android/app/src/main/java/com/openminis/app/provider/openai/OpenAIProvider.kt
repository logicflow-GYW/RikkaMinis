package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.parseRetryAfterMs
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.sandbox.offload.FirstChunkTimeoutPolicy
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import com.openminis.app.provider.sanitizeToolPairing
import com.openminis.app.provider.clampOutboundMaxTokens
import com.openminis.app.provider.clampOutboundTemperature
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

// -- Think-tag extraction (case-insensitive, multi-format) --

/**
 * Defines a think-tag format pair: [open] marks the start of thinking
 * content, [close] marks the end. Matching is case-insensitive.
 * [altClose] is an alternative terminator for the same [open] (e.g.
 * DeepSeek R1 closes `<thinking>` with `<response>` instead of `</thinking>`).
 */
internal data class ThinkTagDef(
    val open: String,
    val close: String,
    val altClose: String? = null,
)

/**
 * Explicit think-tag formats. Matching is case-insensitive, so `<thinking>`
 * and `<THINKING>` both match the `<thinking>` entry. These are safe to scan
 * for on ALL models — an explicit tag can't collide with plain prose.
 */
internal val THINK_TAG_FORMATS: List<ThinkTagDef> = listOf(
    ThinkTagDef("<thinking>", "</thinking>", altClose = "<response>"), // DeepSeek R1 style
    ThinkTagDef("<reasoning>", "</reasoning>", altClose = "<response>"),
    ThinkTagDef("[think]", "[/think]"),
    ThinkTagDef("[reasoning]", "[/reasoning]"),
    // [T-think-tag-catalog-expand] Common third-party/self-consistent labels
    // some OpenAI-compatible relays emit inside `content` (half of the
    // "thinking leaked into body" bug): explicit symmetric tags are safe to
    // scan for — an explicit tag can't collide with plain prose. `<Thought>`
    // is seen from several argoshas (case-insensitive, so `<thought>` also
    // matches); `<analysis>` from reflect-style models. altClose handles the
    // common "ends at next <response>" legacy terminator used by gateways
    // that strip `</...>` closers.
    ThinkTagDef("<Thought>", "</Thought>", altClose = "<response>"),
    ThinkTagDef("<analysis>", "</analysis>", altClose = "<response>"),
)

/**
 * Result of a single [scanThinkTags] call.
 */
internal data class ThinkTagScanResult(
    val visible: String,
    val thinking: String,
    val remainingBuffer: String,
    val insideTag: Boolean,
    val currentFormat: ThinkTagDef?,
)

/**
 * Scans [buffer] for think-tag markers (case-insensitive) using [formats].
 *
 * When [insideTag] is true, searches for the [currentFormat] close tag.
 * When false, searches for any open tag from [formats].
 *
 * This is a pure scanner — it does not mutate any state. Callers are
 * responsible for updating their own state from the result.
 *
 * Fast path: when no tag is found, only the trailing partial-tag-prefix
 * (e.g. `<th` of `<thinking>`) is kept buffered — plain text streams
 * through immediately without accumulating (streaming UX must not lag).
 */
internal fun scanThinkTags(
    buffer: String,
    insideTag: Boolean,
    currentFormat: ThinkTagDef?,
    formats: List<ThinkTagDef>,
): ThinkTagScanResult {
    val bufLower = buffer.lowercase()
    val visibleBuilder = StringBuilder()
    val thinkingBuilder = StringBuilder()
    var i = 0
    var tagActive = insideTag
    var activeFormat = currentFormat

    /** Longest tail of [bufLower] that is a prefix of some open tag. */
    fun maxOpenTagPrefixLen(): Int {
        var best = 0
        for (fmt in formats) {
            val open = fmt.open.lowercase()
            val maxLen = minOf(open.length - 1, bufLower.length) // full tag is found by indexOf, never a prefix here
            for (len in maxLen downTo 1) {
                if (open.startsWith(bufLower.substring(bufLower.length - len))) {
                    if (len > best) best = len
                    break
                }
            }
        }
        return best
    }

    while (i < buffer.length) {
        if (!tagActive) {
            // Search for the EARLIEST open tag in the whole (remaining) buffer,
            // across all formats (case-insensitive). We pick by index, not by
            // FORMAT ORDER — the previous directory-order scan could latch onto
            // a `<thinking>` that appears LATER than a `<response>` that precedes
            // it, wrongly swallowing text and letting true thinking leak into
            // the visible body.
            var bestFmt: ThinkTagDef? = null
            var bestIdx = -1
            for (fmt in formats) {
                val openLower = fmt.open.lowercase()
                val idx = bufLower.indexOf(openLower, i)
                if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                    bestIdx = idx
                    bestFmt = fmt
                }
            }
            if (bestFmt != null) {
                // Found a real open tag: emit the preceding visible text, enter
                // the thinking region, and CONSUME the open tag. Continue the
                // loop so a region closed in this same buffer (e.g.
                // <thinking>…</thinking>) can open the next one — previously a
                // single scan returned after the first open tag and dropped
                // everything between it and the close, leaking it to visible.
                val openLen = bestFmt.open.length
                visibleBuilder.append(buffer, i, bestIdx)
                tagActive = true
                activeFormat = bestFmt
                i = bestIdx + openLen
            } else {
                // No open tag anywhere ahead: emit everything except a possible
                // open-tag PREFIX at the tail (kept buffered across chunks).
                val prefixLen = maxOpenTagPrefixLen()
                val keepFrom = buffer.length - prefixLen
                visibleBuilder.append(buffer, i, keepFrom)
                val remaining = buffer.substring(keepFrom)
                return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), remaining, false, null)
            }
        } else {
            val fmt = activeFormat
            if (fmt != null) {
                // Close candidates: primary close + altClose (e.g. <thinking>
                // can end with either </thinking> or <response>); take the
                // earliest.
                val closes = listOfNotNull(fmt.close, fmt.altClose).map { it.lowercase() }
                var bestIdx = -1
                var bestLen = 0
                for (c in closes) {
                    val idx = bufLower.indexOf(c, i)
                    if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                        bestIdx = idx
                        bestLen = c.length
                    }
                }
                if (bestIdx == -1) {
                    // Close tag not yet arrived — emit thinking text except a
                    // possible close-tag prefix at the tail (any close candidate).
                    var best = 0
                    for (c in closes) {
                        val maxLen = minOf(c.length - 1, bufLower.length)
                        for (len in maxLen downTo 1) {
                            if (c.startsWith(bufLower.substring(bufLower.length - len))) {
                                if (len > best) best = len
                                break
                            }
                        }
                    }
                    val keepFrom = buffer.length - best
                    thinkingBuilder.append(buffer, i, keepFrom)
                    val remaining = buffer.substring(keepFrom)
                    return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), remaining, true, activeFormat)
                } else {
                    thinkingBuilder.append(buffer, i, bestIdx)
                    i = bestIdx + bestLen
                    tagActive = false
                    activeFormat = null
                    // Loop continues scanning for the next open tag in this
                    // same buffer (handles multiple consecutive regions and
                    // the text between them).
                }
            } else {
                // Defensive: activeFormat should never be null while tagActive.
                // Treat as no-op so the stream never spins.
                break
            }
        }
    }

    return ThinkTagScanResult(visibleBuilder.toString(), thinkingBuilder.toString(), "", false, null)
}

// -- End of think-tag extraction --

class OpenAIProvider constructor(
    private val apiKey: String?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    private val basePath: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** When true, route through /v1/responses even on API-key providers. */
    private val useResponsesAPI: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    private val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    private val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    private val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"
    override var instanceContext: com.openminis.app.data.model.ProviderInstance? = null

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how instanceContext is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from ThinkingRuleResolver's cache. Null → no custom
     * rules (built-in-only behaviour, identical to the pre-port chain).
     */
    var thinkingRuleInstanceId: String? = null

    companion object {
        /**
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited. This is a REAL
         * dead-upstream signal: a wedged tunnel never reaches headers at all,
         * so 30s here is safe (headers arrive fast even for slow generations).
         */
        private const val STREAM_TTFB_TIMEOUT_MS = 30_000L

        /**
         * First-data-row watchdog budget. A response whose headers arrived but
         * whose first `data:` event never does is considered wedged after this
         * window. 2026-08-26 (fix/long-generation-timeouts): this is now the
         * generous generation backstop for EVERY stream — a long NON-thinking
         * generation can sit silent on the SSE body for many minutes (long
         * deliverables, big-context late turns), and provider silence is not a
         * reliable dead-signal. The former 45s/thinking-split re-exposed
         * non-thinking long generations to a false kill; a genuinely hung
         * upstream still surfaces at this 30-min ceiling.
         */
        private const val STREAM_FIRST_DATA_TIMEOUT_MS =
            com.openminis.app.sandbox.offload.FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC * 1000L

        /**
         * [T-relay-host-adaptation] SiliconFlow (api.siliconflow.cn) model
         * allowlist: only these exact model ids accept an `enable_thinking`
         * toggle. Absorbed from RikkaHub's ChatCompletionsAPI host table —
         * sending the field to any other id on this relay is silently ignored
         * (or rejected). Kept as a Set for O(1) membership on the hot path.
         */
        private val SILICONFLOW_THINKING_MODELS = setOf(
            "Pro/moonshotai/Kimi-K2.5",
            "Pro/zai-org/GLM-5",
            "Pro/zai-org/GLM-5.1",
            "Pro/zai-org/GLM-4.7",
            "deepseek-ai/DeepSeek-V3.2",
            "Pro/deepseek-ai/DeepSeek-V3.2",
            "Qwen/Qwen3.5-397B-A17B",
            "Qwen/Qwen3.5-122B-A10B",
            "Qwen/Qwen3.5-35B-A3B",
            "Qwen/Qwen3.5-27B",
            "Qwen/Qwen3.5-9B",
            "Qwen/Qwen3.5-4B",
            "zai-org/GLM-4.6",
            "Qwen/Qwen3-8B",
            "Qwen/Qwen3-14B",
            "Qwen/Qwen3-32B",
            "Qwen/Qwen3-30B-A3B",
            "tencent/Hunyuan-A13B-Instruct",
            "zai-org/GLM-4.5V",
            "deepseek-ai/DeepSeek-V3.1-Terminus",
            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
            "deepseek-ai/DeepSeek-V4-Flash",
            "Pro/deepseek-ai/DeepSeek-V4-Flash",
            "deepseek-ai/DeepSeek-V4-Pro",
            "Pro/deepseek-ai/DeepSeek-V4-Pro",
        )
    }

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null

    /**
     * [T-android-model-use-passthrough-mode] Build a URL from the provider's
     * scheme+host(+port) ONLY, with [path] replacing the entire URL path.
     * [path] must start with "/" and may carry a query string. Credentials stay
     * bound to the instance's host — callers can never point this at a different
     * host. Returns null if the base URL can't be parsed. Mirrors iOS
     * OpenAIProvider.hostRootURL.
     */
    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val qIdx = path.indexOf('?')
        val pathPart = if (qIdx >= 0) path.substring(0, qIdx) else path
        val queryPart = if (qIdx >= 0) path.substring(qIdx + 1) else null
        val builder = base.newBuilder()
            .encodedPath(pathPart)
            .fragment(null)
        builder.encodedQuery(queryPart)
        return builder.build().toString()
    }

    /**
     * Resolve the effective URL for a modeled endpoint, honoring the
     * absolute-path override when present. [defaultPath] is joined after
     * [basePath] (which is already normalized to base + /v1). Mirrors iOS
     * OpenAIProvider.endpointURL.
     */
    private fun endpointURL(defaultPath: String): String {
        val abs = absoluteEndpointOverride
        if (abs != null && abs.startsWith("/")) {
            hostRootURL(abs)?.let { return it }
        }
        return "$basePath$defaultPath"
    }

    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        if (isAzure) header("api-key", token) else header("Authorization", "Bearer $token")

    /**
     * Build the request URL for Azure OpenAI, mirroring the official AzureOpenAI
     * SDK shape (and iOS azureURL, T-ios-azure-openai-deployments):
     *
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     *
     * The user pastes the resource endpoint as the custom base — typically the
     * bare `https://x.openai.azure.com`, optionally already including `/openai`,
     * with the `?api-version=…` query on it. We (1) split off the query, (2)
     * strip a trailing `/`, a stray `/v1` (Azure has no /v1), and a trailing
     * `/openai` (re-added), then (3) assemble the deployments path. [path] is
     * e.g. "/chat/completions". Returns null when no Azure base is configured.
     */
    private fun azureUrl(path: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var p = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (p.endsWith("/v1")) p = p.dropLast(3).trimEnd('/')
        if (p.endsWith("/openai")) p = p.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return "$p/openai/deployments/${model.id}/$endpointPath$query"
    }

    /**
     * Whether this provider uses Chat Completions API (vs Responses API).
     * Responses API is used when the user explicitly flipped the per-instance
     * `useResponsesAPI` switch.
     */
    private val usesChatCompletionsAPI: Boolean get() = !useResponsesAPI

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     */
    private suspend fun getToken(): String {
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    // T-android-openai-codex-timeout: readTimeout defaults to the generation
    // backstop (FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC = 30 min) — the
    // single source of truth for how long a stream may sit silent before it is
    // classified as wedged. Historically this was 180s then 600s "to match
    // iOS", but the Codex Responses OAuth path on gpt-5.5 with a real-world
    // agent body (440KB, 20 messages, 8 tools) routinely sits silent on the
    // SSE stream for 2:50-3:10 between the reasoning
    // `response.output_item.added` event and the burst of text deltas after
    // the reasoning step completes — server-side it's still working, no
    // keep-alive bytes arrive in between, and OkHttp's idle-data-read counter
    // trips. A short cap turned that normal reasoning silence into a hard
    // SocketTimeoutException. Defaulting to the 30min generation backstop
    // leaves room for the longest realistic reasoning bursts; per-call callers
    // that need a shorter budget override readTimeout explicitly. The
    // cancel-race concern T171 hedged against (OkHttp call.cancel() racing a
    // thread inside execute()) is covered by the outer coroutine cancellation
    // chain — Job.cancel propagates down through the agent loop and the socket
    // gets closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    /** Detect OpenRouter base URL. */
    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /** Detect DashScope (Alibaba Qwen) base URL. */
    private val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
     * `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
     * EVERY model it hosts — including third-party families (GLM / Kimi /
     * DeepSeek / MiniMax) that, at their vendor-native endpoint, would instead
     * use a `thinking:{}` object or self-reason with no toggle.
     *
     * Two known such gateways (mirrors iOS OpenAIProvider.usesUnifiedReasoningEffort):
     *   • Volcengine Ark (`ark.` / `volces` in the base URL) — re-exposes
     *     doubao/deepseek/glm/kimi through a single OpenAI-compatible surface
     *     where thinking is controlled ONLY by `reasoning_effort` (min tier
     *     `minimal`); the vendor-native `thinking:{}` shape is not honored.
     *   • Azure OpenAI ([isAzure]) — reasoning is `reasoning_effort` for every
     *     model surfaced through the deployment.
     *   • Venice (`api.venice.ai`) — same unified surface; additionally its
     *     ChatCompletionRequest is `additionalProperties:false`, so an unknown
     *     ROOT key (e.g. `thinking:{}`) is a hard 400 before model dispatch
     *     (OpenMinis#86 / 84f5c9e1). Added so the unified-gateway rule claims
     *     Venice-hosted third-party ids before the vendor patterns do.
     *
     * Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
     * which DO want their own thinking shape) are never mis-routed.
     */
    private val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    /**
     * [T-deepseek-v4-official-only] Whether this instance is the official
     * DeepSeek gateway. Only the official `api.deepseek.com` backend
     * understands the vendor-native `thinking:{}` object
     * (`thinking:{type:enabled,reasoning_effort}`). Third-party OpenAI-compatible
     * relays that re-host `deepseek-v4-*` (e.g. tokenrhythm) reject that
     * vendor-internal field with `UNKNOWN_FIELD: thinking.reasoning_effort` and
     * instead control thinking via the standard top-level `reasoning_effort`.
     * Mirrors how [isOpenRouter]/[usesUnifiedReasoningEffort] already special-case
     * relay surfaces: here the official gateway keeps the native `thinking:{}`
     * object while any other basePath routes through the generic reasoning_effort
     * path. (2026-08-27, tokenrhythm deepseek-v4 thinking-toggle fix.)
     */
    private val isOfficialDeepSeek: Boolean =
        basePath.lowercase().contains("api.deepseek.com")

    /**
     * [T-mistral-omit-everything] Endpoint is Mistral's own API. The request rejects
     * `reasoning` (422 extra_forbidden) and AssistantMessage is a closed schema that
     * rejects `reasoning_content` — so the thinking key must be OMITTED entirely, not
     * just turned off. Absorbed from upstream (GH OpenMinis#87 / 4592ca9b).
     */
    private val isMistral: Boolean =
        basePath.lowercase().contains("mistral.ai")

    /**
     * [OpenMinis#163] Endpoint is xAI's own API (api.x.ai), not a relay that merely
     * serves grok-named models. Scopes the empty-tier skip to the vendor where the
     * 400 was actually observed (grok-build-0.1 rejects `reasoning_effort`).
     */
    private val isXAI: Boolean =
        basePath.lowercase().contains("api.x.ai")

    /**
     * [T-length-wall-prefill] Whether this OpenAI-compatible endpoint accepts
     * an ASSISTANT message as the final message (prefill continuation of a
     * truncated reply). Official OpenAI + Azure + the known OpenAI-compatible
     * gateways (OpenRouter / DashScope / Volcengine Ark / official DeepSeek)
     * all honor a trailing assistant prefill. STRICT third-party relays that
     * require the last message to be USER (e.g. tokenrhythm-class proxies)
     * reject it with a 400 — those fall back to the reminder + seam-trim path,
     * so the allowlist is deliberately conservative: unknown bases default to
     * NO prefill (behaviour unchanged from before this flag existed).
     */
    override val supportsPrefill: Boolean
        get() = supportsPrefillForOpenAIBase(basePath, isAzure)

    /**
     * [T-thinking-off-explicit] The wire value for "thinking OFF", or null to
     * keep the historical omit-the-field behavior. ALLOWLIST, not blanket
     * (mirrors iOS OpenAIAgentProvider.explicitOffEffort): only vendors whose
     * off tier is DOCUMENTED get an explicit value —
     *   • official OpenAI base (non-Azure) → "none" (documented off tier);
     *   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
     *     (their smallest tier — Ark's non-off default is what motivated this).
     * Everyone else (relays, NIM, xAI, MiMo, …) keeps field omission = the
     * vendor's own default. Azure stays omission too: its off tier is
     * model-dependent ('none' on gpt-5.1+, 'minimal' on original gpt-5,
     * unsupported on o1/o3), so an explicit value risks a 400.
     */
    private fun explicitOffEffort(): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = model.id.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        streamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, tools = tools, thinkingLevel = thinkingLevel)
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        val bodyStr = body.toString()
        val request = buildRequest(bodyStr)
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        // [generation-read-timeout] The OkHttp readTimeout is the idle-read
        // budget for the whole SSE body. A long generation — thinking or not —
        // can sit silent for minutes between rows (Codex Responses is silent
        // 2:50–3:10 with NO keep-alive bytes; long deliverables and big-context
        // late turns do the same). 2026-08-26
        // (fix/long-generation-timeouts): widen the idle-read budget to the
        // generous generation ceiling for EVERY generation stream so the socket
        // never pre-empts a healthy slow generation; a genuinely wedged upstream
        // is owned by the TTFB / first-data watchdogs, not the socket.
        val call = client.newBuilder()
            .readTimeout(FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC.toLong(), TimeUnit.SECONDS)
            .build()
            .newCall(request)
        // [T-android-stale-conn-retry-hang] Time-to-first-byte watchdog. A
        // request written into a dead pooled h2 tunnel (local proxy socket
        // survives a network flap) produces NO further events — no headers,
        // no failure — until the 600s read timeout, so the UI showed
        // "thinking" forever. Bound ONLY the header phase: if response
        // headers haven't arrived within STREAM_TTFB_TIMEOUT_MS, cancel the
        // call and surface a normal retryable error (auto-retry then gets a
        // fresh connection — NetworkMonitor now evicts the shared pool on
        // transitions). Once execute() returns, the watchdog is cancelled and
        // a flowing SSE stream has NO total-duration limit, as before.
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val ttfbWatchdog = launch {
            delay(STREAM_TTFB_TIMEOUT_MS)
            if (!headersArrived.get()) {
                ttfbTimedOut.set(true)
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-stale-conn-retry-hang] no response headers after ${STREAM_TTFB_TIMEOUT_MS / 1000}s — cancelling call (stale pooled connection?)",
                )
                call.cancel()
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(
                response.code,
                errorBody,
                parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
            )
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        // One-shot info log the first time the Responses API streams a reasoning
        // delta — useful for confirming the Thinking pipeline is wired up when
        // diagnosing "I set thinking high but see nothing" reports.
        var sawReasoningDelta = false
        // Accumulates the opaque reasoning_content blob across SSE deltas so we
        // can echo the exact server-emitted value back on the next turn. Tracked
        // separately from ThinkingDelta concatenation because DeepSeek V4 emits
        // `reasoning_content: ""` legitimately (non-thinking turns) and the
        // empty string must round-trip — fabricating placeholder text causes
        // the model to in-context-learn it (see T249 / T257 history).
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        val hasThinkTags = true  // safe: extractThinkTags is a no-op when no tags are present
        // [T-thinking-fold-leak] Per-STREAM think-tag state. Created here,
        // inside rawStreamMessage, so a cancelled/errored/reused provider can
        // never bleed a half-open tag into the next stream. Cleared on every
        // exit path (see catch / channel.close / awaitClose below).
        val thinkState = ThinkTagState()

        // T321: turn-level SSE counters for empty-response triage.
        var sseEventCount = 0
        var contentLen = 0
        var reasoningLen = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        var lastUsageJson: JSONObject? = null
        // [RC2-truncated-detection] True once the stream sent its terminal
        // Finished (the [DONE] branch). If the loop exits by EOF (connection
        // drop / server cut) before [DONE], this stays false and we emit a
        // truncated Finished below so ChatViewModel's turnTruncated retry
        // path owns the partial reply instead of silently saving it.
        var finishedSent = false

        try {
            send(LLMStreamChunk.Started)
            // [first-data-row-watchdog] Arms after HTTP Started. A response
            // whose HEADERS arrived (so the TTFB watchdog already disarmed)
            // but whose first `data:` event never does leaves the synchronous
            // reader.readLine() blocking until OkHttp's 600s readTimeout.
            // That hangs the :modelservice worker far past the client's death
            // grace, misclassifying the live-but-wedged worker as DEAD and
            // forcing a pointless retry loop (2026-08-23 agent-loop rounds).
            // Cancel the call once the first SSE payload row proves the stream
            // is alive; a stalled first row cancels the call, interrupting
            // readLine (IOException→retryable error) instead of hanging.
            val firstDataArrived = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstDataWatchdog = launch {
                // [generation-first-data] A generation may not emit a `data:`
                // row for minutes — not only when reasoning: long deliverables
                // and big-context late-turn calls do so too, and provider silence
                // is not a reliable dead-signal (2026-08-26,
                // fix/long-generation-timeouts). The watchdog only races the
                // first payload row to detect a wedged (headers-ok-but-no-body)
                // stream, so use the same generous generation backstop as the
                // outer first-chunk guard for EVERY stream instead of
                // force-cancelling a healthy slow generation at 45s. A genuinely
                // hung upstream still surfaces at this 30-min ceiling, which
                // bounds the worst case without false-killing long work.
                val firstDataBudgetMs = STREAM_FIRST_DATA_TIMEOUT_MS
                delay(firstDataBudgetMs)
                if (!firstDataArrived.get()) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[first-data-row] no SSE data row after ${firstDataBudgetMs / 1000}s (headers ok) — cancelling call",
                    )
                    call.cancel()
                }
            }
            var line: String?
            var finishReason: String? = null

            // Branch streaming parser based on API format
            val isResponsesAPI = !usesChatCompletionsAPI

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("data:")) {
                    // First SSE payload row proves the stream is live — disarm
                    // the first-data watchdog before we parse the row.
                    firstDataArrived.set(true)
                    firstDataWatchdog.cancel()
                }
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    // Flush any remaining buffered content from think tag extraction
                    if (hasThinkTags) {
                        flushThinkTags(thinkState)?.let { remaining ->
                            if (thinkState.insideTag) {
                                send(LLMStreamChunk.ThinkingDelta(remaining))
                            } else {
                                send(LLMStreamChunk.Text(remaining))
                            }
                        }
                    }
                    if (sawReasoningField) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
                    finishedSent = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                if (com.openminis.app.BuildConfig.DEBUG) {
                    android.util.Log.d("ToolChain[Provider]", "RAW SSE: $payload")
                }
                sseEventCount++

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if (cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            )
                        }
                        if (type == "response.output_text.delta") contentLen += dLen
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val acc = responsesToolCalls.remove(itemId) ?: continue
                                val argsStr = item.optString("arguments", acc.args.toString())
                                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                            }
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.TransientError("[$code] $message")
                            }
                            throw LLMError.ProviderError("[$code] $message")
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter". Partial
                            // output has already been streamed — surface WHY it
                            // stopped instead of silently ending the stream.
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        " — output hit max_output_tokens; raise the model's Max Output Tokens or shorten the request."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                lastUsageJson = usage
                                send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            if (hasRcKey || hasReasoningKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }

                            // [T-relay-host-adaptation] Mistral reasoning dialect:
                            // reasoning arrives inside `delta.content[]` as
                            // {"type":"thinking","thinking":[{"type":"text","text":"…"}]}
                            // (NOT reasoning_content/reasoning). Absorbed from
                            // RikkaHub's ChatCompletionsStreamDecoder.
                            d.optJSONArray("content")?.let { contentArr ->
                                for (ci in 0 until contentArr.length()) {
                                    val contentItem = contentArr.optJSONObject(ci) ?: continue
                                    if (contentItem.optString("type", "") != "thinking") continue
                                    val thinkingArr = contentItem.optJSONArray("thinking") ?: continue
                                    for (ti in 0 until thinkingArr.length()) {
                                        val thinkingItem = thinkingArr.optJSONObject(ti) ?: continue
                                        val thinkingText = thinkingItem.safeOptString("text", "")
                                        if (thinkingText.isNotEmpty()) {
                                            reasoningAccum.append(thinkingText)
                                            if (!sawReasoningDelta) {
                                                sawReasoningDelta = true
                                            }
                                            send(LLMStreamChunk.ThinkingDelta(thinkingText))
                                        }
                                    }
                                }
                            }
                        }

                        // Text content (with think-tag extraction — enabled for
                        // all models; extractThinkTags is a no-op when no tags
                        // are present, so this path is safe for plain text)
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val extracted = extractThinkTags(text, thinkState)
                                if (extracted.thinking.isNotEmpty()) send(LLMStreamChunk.ThinkingDelta(extracted.thinking))
                                if (extracted.visible.isNotEmpty()) send(LLMStreamChunk.Text(extracted.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${acc.args.length}chars")
                                    send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        lastUsageJson = usage
                        send(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            // Flush any remaining buffered content
            if (hasThinkTags) {
                flushThinkTags(thinkState)?.let { remaining ->
                    if (thinkState.insideTag) {
                        send(LLMStreamChunk.ThinkingDelta(remaining))
                    } else {
                        send(LLMStreamChunk.Text(remaining))
                    }
                }
            }

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            responsesToolCalls.clear()

            // [RC2-truncated-detection] EOF without [DONE] / finish_reason but
            // with accumulated content — the model reply was cut mid-stream
            // (connection drop / server truncation). The `[DONE]` branch above
            // already signalled a (possibly truncated) finish; reaching here via
            // EOF means NO Finished was emitted, which would otherwise let
            // ChatViewModel treat the stub as a clean finish (finishedCleanly=true)
            // and silently save a half answer. Signal truncated so the
            // turnTruncated retry owns it. Only fires for non-empty EOF: a fully
            // empty EOF is already handled by failOnSilentEmptyCompletion.
            if (!finishedSent && (contentLen > 0 || reasoningLen > 0)) {
                send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
            }

            // Final usage summary — printed once at stream end instead of per-delta.
            if (lastUsageJson != null) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] usage final: $lastUsageJson"
                )
            }

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
            // [T-thinking-fold-leak] Always drop the per-stream tag state on
            // every exit (normal, error, cancellation) so a half-open tag can
            // never leak into the next stream served by this provider instance.
            thinkState.reset()
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
            // [T-thinking-fold-leak] Belt-and-braces: some cancellation paths
            // tear down via awaitClose only; reset the state again so the
            // next stream always starts clean.
            thinkState.reset()
        }
    }

    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    /**
     * Execute a verbatim request against this provider instance's base URL with
     * the instance's credentials. The response is returned UNPARSED — passthrough
     * mode's output contract is raw bytes; the caller (agent or follow-up script)
     * owns interpretation. Mirrors iOS OpenAIProvider.rawPassthroughRequest.
     *
     * - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
     *   relative segment (joined after basePath like modeled endpoints). null →
     *   the default chat/completions path.
     * - headers: applied LAST → same-name REPLACE semantics over every default
     *   (including Authorization/Content-Type), per design.
     */
    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        bodyObject: JSONObject?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        if (verb != "GET" && bodyObject != null) {
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyObject.toString().toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            builder.method(verb, requestBody)
        } else {
            builder.method(verb, null)
        }
        val token = getToken()
        builder.applyKeyAuth(token)
        builder.header("Content-Type", "application/json")
        // ctor extraHeaders, then user headers LAST — replace semantics.
        for ((k, v) in extraHeaders) builder.header(k, v)
        for ((k, v) in headers) builder.header(k, v)

        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[${bodyObject?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""}] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    /**
     * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
     * API (`POST $basePath/images/generations`). Mirrors iOS
     * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
     * image-output routing for API-key OpenAI-compat instances — the Codex
     * [T-gpt-image2-normal-route] gpt-image-2 on the normal (non-Codex)
     * path goes through the existing image-generation handling.
     * and never reaches here.
     *
     * Request body: `{ model, prompt, n, size?, quality?, response_format:
     * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
     * 400 mentioning it, we retry once without the field (iOS parity).
     *
     * On a non-2xx response throws [mapHttpError]'s result. A route-missing
     * error (404 / "got chat completions response") surfaces as
     * LLMError.ProviderError whose message the handler matches with
     * looksLikeEndpointMissing() to drive the auto-mode fallback.
     */
    suspend fun generateImage(
        prompt: String,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val token = getToken()
        // [T-android-model-use-image-passthrough GH#62] Honor an explicit
        // endpoint-path override (non-standard providers); default otherwise.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
        // [T-android-model-use-passthrough-mode] The absolute-path override wins
        // over the legacy relative imagePathOverride (which is joined after
        // basePath and can't escape base prefixes — iOS baseline p03).
        // [T-android-azure-openai] Azure image generation routes via the
        // deployments path + api-key header; falls back to basePath otherwise.
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // [T-android-model-use-image-passthrough GH#62] When the user explicitly
        // supplies response_format, respect it and skip the b64_json auto-probe.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val body = JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!triedWithoutFormat) body.put("response_format", "b64_json")
            // [T-android-model-use-image-passthrough GH#62] Merge user-supplied
            // passthrough fields. User keys WIN over our defaults (they can
            // override prompt/size or add Seedream's `image`/`watermark`), but
            // `model` is force-kept to the resolved id afterward so a stray
            // override can't misroute the request.
            for ((k, v) in imageExtraBody) body.put(k, v ?: JSONObject.NULL)
            body.put("model", model.id)

            val bodyStr = body.toString()
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url(url)
                .post(requestBody)
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            // [T-android-model-use-image-passthrough GH#62] Per-call passthrough
            // headers, merged after the ctor extraHeaders so they can add/override.
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            val retryAfterMs = parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis())
            response.close()

            // Some providers (xAI) don't support b64_json — retry without it once.
            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody, retryAfterMs)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/generations: unreachable")
    }

    /**
     * Parse the `/images/generations` response into an [LLMResponse] carrying
     * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
     * (inline) and `url` (downloaded) item shapes. Mirrors iOS
     * parseImageGenerationsResult. When the body has no `data` array but DOES
     * carry `choices`, a proxy silently rerouted us to chat completions — throw
     * a route-missing error so auto-mode falls back instead of caching the
     * wrong endpoint.
     */
    private fun parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: cross-provider image sanitization, mirrors iOS
        // OpenAIAgentProvider.swift:744-768 / 900-918. When the target model
        // doesn't declare "image" in inputModalities (e.g. DeepSeek V4 after
        // user sent image to GPT-5.5 then switched provider), serialize a
        // text placeholder instead of an image_url block — otherwise the
        // server returns "400 unknown variant `image_url`". Decided once
        // here so the structured-contentParts loop and the legacy
        // imageParts loop below stay consistent.
        val supportsImages = "image" in (model.inputModalities ?: emptyList())
        // Defense-in-depth: strip orphan tool_use/tool_result pairing before
        // serialization (upstream history construction is the first line).
        // OpenAI rejects an unanswered `tool_calls` entry (no following
        // role:"tool" message) and a role:"tool" message with an unknown
        // tool_call_id — both deterministic 400s.
        val sanitizedMessages = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("OpenAIProvider", detail)
        }
        val body = JSONObject()
        body.put("model", model.id)
        // Defense-in-depth clamp (see AnthropicProvider): upstream
        // dynamicMaxTokens() is in range; guard out-of-band callers.
        val safeMaxTokens = clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model))
        if (isOpenRouter) {
            body.put("max_tokens", safeMaxTokens)
        } else {
            body.put("max_completion_tokens", safeMaxTokens)
        }
        body.put("stream", stream)

        // [T-relay-host-adaptation] Some reasoning families reject an explicit
        // `temperature` (400) or silently ignore it. Absorbed from RikkaHub's
        // isModelAllowTemperature: o-series (o1/o3/o4), gpt-5.x, and Kimi
        // K2.5/K2.6/K3 are self-reasoning — omit temperature for them.
        if (temperature != null && isModelAllowTemperature(model.id)) {
            body.put("temperature", clampOutboundTemperature(temperature))
        }

        // [T-relay-host-adaptation] Mistral does NOT support stream_options
        // (mirrors RikkaHub — it 400s on include_usage); OpenRouter uses its own
        // usage fields. Only emit include_usage on hosts that accept it.
        val host = basePath.toHttpUrlOrNull()?.host ?: ""
        if (stream && !isOpenRouter && host != "api.mistral.ai") {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        // Provider-specific thinking params. We always call this — some
        // models (e.g. DeepSeek V4) reason by default and need an explicit
        // `disabled` signal when the user toggles thinking off.
        //
        // [T-android-mistral-reasoning-422] …EXCEPT on Mistral, which rejects
        // the thinking request parameters outright with
        // `422 extra_forbidden body.reasoning`. Mirrors iOS
        // OpenAIAgentProvider.swift's `if !provider.isMistral` gate around this
        // same call (4592ca9b / GH OpenMinis#87). Until now [isMistral] only
        // suppressed the stream_options include_usage field — the
        // request-parameter half of that fix was never ported, so an enabled
        // thinking level still put `reasoning_effort` on the wire to
        // api.mistral.ai.
        if (!isMistral) {
            injectThinkingParams(body, thinkingLevel, maxTokens)
        }

        // Tools
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toOpenAIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        val messagesArray = JSONArray()
        if (systemPrompt != null) {
            messagesArray.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }

        // Mirror iOS OpenAIAgentProvider.flattenChatCompletionsMessages —
        // echo reasoning_content on prior assistant turns when:
        //   - user requested thinking this turn, OR the model always reasons (forced); AND
        //   - the model isn't explicitly known to reject reasoning.
        // Prevents 400s from Kimi / DeepSeek / GLM / QwQ that reject
        // multi-turn history missing reasoning_content once thinking is on.
        //
        // [T-android-mistral-reasoning-422] Mistral rejects `reasoning_content`
        // on assistant messages entirely (closed schema → HTTP 422
        // extra_forbidden), so suppress BOTH the captured echo and the ""
        // placeholder for that endpoint (absorbed upstream 0839f019). This
        // cannot be driven by capability metadata: MiMo/DeepSeek require the
        // field's PRESENCE while Mistral forbids it, and neither advertises
        // supportsReasoning — opposite requirements on the same generic path.
        val modelAlwaysReasons = model.supportsReasoning == true
        val modelMayReason = model.supportsReasoning ?: true
        val forbidReasoningField = isMistral
        val includeReasoning =
            (thinkingLevel.isEnabled || modelAlwaysReasons) && modelMayReason && !forbidReasoningField
        val echoReasoning = includeReasoning
        // [T-thinking-auto-level] AUTO does not assert "thinking is on": captured
        // reasoning is still echoed (round-tripping real history, required by
        // DeepSeek/MiMo multi-turn), but the EMPTY placeholder is suppressed —
        // an empty reasoning_content is a field-presence signal that thinking
        // should be on, which AUTO deliberately does not claim.
        //
        // T-mimo-reasoning-echo-34671: Mimo V2.5 returns 400 Param Incorrect on
        // multi-turn tool-call history when any prior assistant turn (especially
        // a tool_calls-bearing one) omits `reasoning_content`. Mimo's docs say
        // the field MUST be present (empty string OK) whenever thinking is on
        // and a tool call is in history. We drop the previous interleaved-only
        // gate and always emit reasoning_content (possibly "") whenever the
        // echo gate (includeReasoning) is true. OpenAI o-series ignores
        // unknown message-level `reasoning_content` so this stays harmless
        // there; non-reasoning models gate this off via includeReasoning=false.
        val placeholderAllowed = includeReasoning && thinkingLevel != ThinkingLevel.AUTO

        val lastUserIndex = sanitizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in sanitizedMessages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                // Structured content parts
                when {
                    // Assistant with tool_use → emit assistant message with tool_calls
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        if (echoReasoning) {
                            val rc = msg.reasoningContent
                            if (rc != null) {
                                // Round-trip exactly what the server emitted,
                                // including empty strings. DeepSeek V4 emits
                                // `reasoning_content: ""` on non-thinking turns
                                // and accepts the same shape on input — the empty
                                // value is the field-presence guarantee that
                                // prevents 400s once thinking is on.
                                obj.put("reasoning_content", rc)
                            } else if (placeholderAllowed) {
                                // No captured reasoning for this turn (e.g. fallback
                                // to a non-thinking model, or message persisted before
                                // thinking was enabled). Send "" rather than a synthetic
                                // marker: prior placeholders ("[no prior reasoning]",
                                // T249; single space, T257) were in-context-learned by
                                // DeepSeek V4 and echoed back as the model's own
                                // reasoning. Empty string satisfies the field-presence
                                // check with no learnable pattern.
                                obj.put("reasoning_content", "")
                            }
                        }
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            obj.put("content", textParts.joinToString("") { it.text })
                        }
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        if (toolUseParts.isNotEmpty()) {
                            val toolCallsArr = JSONArray()
                            for (tu in toolUseParts) {
                                toolCallsArr.put(JSONObject().apply {
                                    put("id", capChatToolCallId(tu.id))
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tu.name)
                                        put("arguments", tu.input.toString())
                                    })
                                })
                            }
                            obj.put("tool_calls", toolCallsArr)
                        }
                        messagesArray.put(obj)
                    }
                    // User with tool_results → emit separate tool messages
                    msg.role == LLMMessage.Role.USER -> {
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imageParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()

                        for (tr in toolResults) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", capChatToolCallId(tr.id))
                                put("content", tr.content)
                            })
                        }
                        // T132: emit text + image_url parts as a structured user
                        // message. The previous structured-contentParts branch
                        // dropped AgentContentPart.ImageData entirely — only the
                        // legacy non-contentParts path knew how to encode images,
                        // and that path is unreachable once contentParts is
                        // populated (which is always now). Mirrors iOS
                        // OpenAIAgentProvider.swift L732-738.
                        val hasImages = imageParts.isNotEmpty()
                        if (hasImages || textParts.isNotEmpty()) {
                            if (hasImages) {
                                val contentArray = JSONArray()
                                // Walk contentParts in original order so the
                                // [attached image: …] text caption that
                                // precedes each ImageData part stays adjacent
                                // to the right image, matching iOS.
                                for (part in msg.contentParts) {
                                    when (part) {
                                        is AgentContentPart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is AgentContentPart.ImageData -> {
                                            if (supportsImages) {
                                                // T-imgsize: backstop — re-encode oversize
                                                // history image bytes before base64-inlining.
                                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "image_url")
                                                    put("image_url", JSONObject().apply {
                                                        put("url", "data:$safeMime;base64,$b64")
                                                    })
                                                })
                                            } else {
                                                // T264: target model has no vision modality
                                                // — emit text placeholder (iOS-parity literal).
                                                contentArray.put(JSONObject().apply {
                                                    put("type", "text")
                                                    put("text", "[Image attached but this model does not support vision input]")
                                                })
                                            }
                                        }
                                        else -> Unit  // ToolUse/ToolResult never appear on user role here
                                    }
                                }
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", contentArray)
                                })
                            } else {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", textParts.joinToString("") { it.text })
                                })
                            }
                        }
                    }
                }
            } else {
                // Legacy: plain text messages
                val obj = JSONObject()
                obj.put("role", msg.role.value)

                if (echoReasoning && msg.role == LLMMessage.Role.ASSISTANT) {
                    val rc = msg.reasoningContent
                    if (rc != null) {
                        // Round-trip exactly what the server emitted (including "").
                        // See structured-content branch above for the full rationale.
                        obj.put("reasoning_content", rc)
                    } else if (placeholderAllowed) {
                        // No captured reasoning — empty string satisfies the field-
                        // presence check without giving DeepSeek V4 a learnable
                        // marker to imitate (T249 / T257 history).
                        obj.put("reasoning_content", "")
                    }
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                if (attachTopLevelImages || msg.audioParts.isNotEmpty()) {
                    val contentArray = JSONArray()
                    if (attachTopLevelImages) {
                        for (part in imageParts) {
                            if (supportsImages) {
                                // T-imgsize: provider-boundary backstop.
                                val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                val imageUrl = JSONObject()
                                imageUrl.put("url", "data:$safeMime;base64,$b64")
                                contentArray.put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", imageUrl)
                                })
                            } else {
                                // T264: target model has no vision modality —
                                // emit text placeholder (iOS-parity literal).
                                contentArray.put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "[Image attached but this model does not support vision input]")
                                })
                            }
                        }
                    }
                    // [GH#67] Official Chat Completions audio-input shape,
                    // forwarded verbatim (modality is gated at the call site).
                    for (audio in msg.audioParts) {
                        contentArray.put(JSONObject().apply {
                            put("type", "input_audio")
                            put("input_audio", JSONObject().apply {
                                put("data", audio.base64Data)
                                put("format", audio.format)
                            })
                        })
                    }
                    // Preserve the pre-GH#67 image-path behavior (text part
                    // always present); for audio-only messages skip an empty
                    // text block some servers reject.
                    if (attachTopLevelImages || msg.content.isNotEmpty()) {
                        contentArray.put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    obj.put("content", contentArray)
                } else {
                    obj.put("content", msg.content)
                }

                messagesArray.put(obj)
            }
        }
        // [T-dedupe-toolcallid follow-up] Cross-message defense-in-depth:
        // rename any tool_call_id that collides with one already seen
        // elsewhere in this request. 9421990 covers the stream-time
        // collision; historical messages reloaded from the DB — or
        // messages produced by a different provider before the user
        // switched — bypass that pass, and DeepSeek (plus several
        // OpenAI-compat gateways) reject the assembled request with
        // "Duplicate value for tool_call_id ... in message[N]" whenever
        // any id repeats across the full messages array.
        globallyDedupeToolCallIds(messagesArray)
        body.put("messages", messagesArray)

        // [T-android-model-use-passthrough-mode GH#72] Merge user-supplied extra
        // body fields verbatim (no OpenAI→native conversion — callers own the
        // shape). User keys win over our defaults, but `model` is force-kept so a
        // stray override can't misroute. Mirrors generateImage's merge + iOS.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * [T-android-model-use-passthrough-mode GH#72] Shared verbatim merge of
     * [chatExtraBody] into a request body, applied by BOTH the chat/completions
     * and responses builders so no endpoint can forget the passthrough. User
     * keys overwrite; `model` is force-restored last.
     */
    private fun mergeChatExtraBody(body: JSONObject) {
        if (chatExtraBody.isNotEmpty()) {
            for ((k, v) in chatExtraBody) body.put(k, v ?: JSONObject.NULL)
        }
        body.put("model", model.id)
    }

    /**
     * Walk every assistant.tool_calls entry and every role:"tool"
     * tool_call_id in order, renaming any duplicate id to `{id}-{N}`.
     * The first occurrence keeps the raw id; subsequent collisions get
     * a numeric suffix starting at 2. Renames propagate to each pair's
     * matching role:"tool" reply by remembering the latest rename per
     * raw id (the reply is required to immediately follow its claiming
     * assistant tool_calls on this provider).
     */
    private fun globallyDedupeToolCallIds(messagesArray: JSONArray) {
        // raw id → max suffix already issued (0 = unused, 1 = raw kept,
        // 2+ = renamed copies).
        val seen = HashMap<String, Int>()
        // raw id → latest renamed id, so the next role:"tool" reply
        // claiming this raw id can pick up the same rewrite.
        val renameForPendingResults = HashMap<String, String>()
        var renamedCount = 0
        val n = messagesArray.length()
        for (i in 0 until n) {
            val msg = messagesArray.optJSONObject(i) ?: continue
            val role = msg.optString("role")
            when (role) {
                "assistant" -> {
                    val toolCalls = msg.optJSONArray("tool_calls") ?: continue
                    for (j in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(j) ?: continue
                        val rawId = call.optString("id", "")
                        if (rawId.isEmpty()) continue
                        val used = seen[rawId] ?: 0
                        val renamedId: String
                        if (used == 0) {
                            renamedId = rawId
                            seen[rawId] = 1
                        } else {
                            val next = used + 1
                            renamedId = "$rawId-$next"
                            seen[rawId] = next
                            renamedCount += 1
                            call.put("id", renamedId)
                        }
                        renameForPendingResults[rawId] = renamedId
                    }
                }
                "tool" -> {
                    val rawId = msg.optString("tool_call_id", "")
                    if (rawId.isEmpty()) continue
                    val renamedId = renameForPendingResults[rawId] ?: continue
                    if (renamedId != rawId) {
                        msg.put("tool_call_id", renamedId)
                    }
                }
            }
        }
        if (renamedCount > 0) {
            android.util.Log.w("OpenAIProvider", "[dedupe-tool-call-id] renamed $renamedCount duplicate tool_call_id(s) across messages — likely DB-loaded history or cross-provider switch")
        }
    }

    /**
     * T302: takes the pre-serialized body string instead of the JSONObject so
     * the caller can serialize once and reuse the result for the debug log,
     * the byte build, and the OkHttp RequestBody. Per-call peak heap dropped
     * by ~2× the body size (often tens of MB on long agent loops).
     */
    private suspend fun buildRequest(bodyStr: String): Request {
        val token = getToken()

        val endpointPath = if (useResponsesAPI) "/responses" else "/chat/completions"
        // [T-android-azure-openai] Azure routes via the deployments path and
        // auths with the api-key header. azureUrl() returns null when not in
        // Azure mode / no base, so the standard basePath join stays the default.
        // [T-android-model-use-passthrough-mode] endpointURL() honors an
        // absolute-path override ("/...") that replaces the whole path on the
        // provider host; otherwise Azure/basePath as before.
        val requestUrl = when {
            absoluteEndpointOverride?.startsWith("/") == true -> endpointURL(endpointPath)
            isAzure -> azureUrl(endpointPath) ?: "$basePath$endpointPath"
            else -> "$basePath$endpointPath"
        }
        // T-responses-include: match iOS bare `application/json` Content-Type
        // by using a custom RequestBody (same workaround the OAuth branch
        // above already does). OkHttp's `String.toRequestBody(MediaType)`
        // helper attaches the MediaType to the body and several proxies/
        // backends end up seeing `application/json; charset=utf-8`. iOS sends
        // bare `application/json`; some third-party Responses-API proxies are
        // stricter and reject the charset suffix.
        val jsonMediaType = "application/json".toMediaType()
        val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        val requestBody = object : okhttp3.RequestBody() {
            override fun contentType() = jsonMediaType
            override fun contentLength() = bodyBytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
        }
        val builder = Request.Builder()
            .url(requestUrl)
            .post(requestBody)
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
        for ((key, value) in extraHeaders) {
            builder.header(key, value)
        }
        // [T-android-model-use-passthrough-mode] Per-call chat header overrides,
        // applied AFTER the ctor extraHeaders → same-name REPLACE over any
        // default (incl. Authorization/Content-Type). Empty on normal calls.
        for ((key, value) in chatExtraHeaders) {
            builder.header(key, value)
        }
        // [T-provider-custom-user-agent] Covers both chat/completions and
        // /responses (this builder serves both). Applied after extraHeaders
        // so the per-provider override wins. null/blank → default UA.
        builder.applyUserAgentOverride(customUserAgent)
        return builder.build()
    }

    private fun parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
        val promptTokens = usage.optInt("prompt_tokens", 0)
        // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
        // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
        // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
        // looked like it never cached even when it did, masking T122's win.
        val cacheRead = usage.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
        // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
        // subtract the cached portion to keep `inputTokens` meaning fresh-only —
        // matching the Anthropic convention. Otherwise the cached tokens are
        // counted twice in `input + cacheRead` (deflates the cache-hit rate;
        // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
        // non-negative; no cache field (cacheRead == null) → unchanged.
        // latestContextTokens stays the full prompt (that IS the context size).
        val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("completion_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = promptTokens,
        )
    }

    /**
     * Inject provider-specific thinking parameters into the request body.
     * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
     *   forced-reasoning models keep their default)
     * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
     * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
     *   — Qwen3 thinks by default, so OFF needs an explicit disable.
     * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
     *   V4 thinks by default and rejects requests without an explicit toggle
     *   when reasoning_content is missing. Distinct from deepseek-reasoner /
     *   deepseek-chat which keep the no-params path below.
     * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
     */
    /**
     * [T-android-xhigh-effort-clamp] Clamp the reasoning-effort string for
     * model families whose backend only accepts low/medium/high and 400/422 on
     * our `xhigh` tier: MiMo-2.5/Pro and Agnes. For these, `xhigh` → `high`;
     * every other value passes through untouched, and every other model is
     * unaffected. Applied at the single point where each branch would emit an
     * effort string (Chat Completions reasoning_effort / reasoning.effort AND
     * the Responses API reasoning.effort) so no branch can leak a raw xhigh.
     * lowercase-contains match, mirroring the T-reasoning-effort-fallback keys.
     */
    private fun clampEffortForModel(effort: String): String {
        val lid = model.id.lowercase()
        // [T-fallback-thinking-preclamp] Match the FAMILY substring, not one
        // spelling: catalog docs say "MiMo-2.5" but the live API returns
        // "mimo-v2.5" / "mimo-v2.5-pro", which the old "mimo-2.5" match missed
        // (mirrors iOS 72968c4f).
        return if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort
    }

    /**
     * [T-relay-host-adaptation] Whether this model accepts an explicit
     * `temperature` field. Absorbed from RikkaHub's isModelAllowTemperature —
     * o-series (o1/o3/o4-*) and gpt-5.x self-reason and reject/ignore
     * temperature; Kimi K2.5/K2.6/K3 are Moonshot-restricted (their endpoint
     * 400s on temperature). Everything else keeps the historical clamp.
     */
    private fun isModelAllowTemperature(modelId: String): Boolean {
        val lid = modelId.lowercase()
        if (lid.startsWith("o") && lid.length >= 2 && lid[1].isDigit()) return false
        if (lid.startsWith("gpt-5")) return false
        if (lid.startsWith("kimi-k2.5") || lid.startsWith("kimi-k2.6") ||
            lid.startsWith("kimi-k3") || lid == "k3"
        ) {
            return false
        }
        return true
    }

    private fun injectThinkingParams(body: JSONObject, level: ThinkingLevel, maxTokens: Int) {
        // [T-android-thinking-level-arch] `level` is already clamped to the model
        // ceiling by LLMProvider.streamMessage/sendMessage — do NOT re-clamp.
        val lid = model.id.lowercase()
        val host = basePath.toHttpUrlOrNull()?.host ?: ""

        // [T-relay-host-adaptation] Host-precise adaptation table, absorbed from
        // RikkaHub's ChatCompletionsAPI `when(host)` switch. A relay is
        // identified by its baseUrl HOST, not by the model id — the same model
        // id (e.g. qwen3.8-max) means different wire dialects on DashScope vs
        // SiliconFlow vs Volcengine Ark vs a private relay. The model-id
        // `lid.contains()` branches below remain as the FALLBACK for unknown
        // hosts (vendor-native direct endpoints), but every known relay host is
        // resolved here first so switching relays no longer lands on the wrong
        // (or a 400-rejected) thinking field.
        when (host) {
            "api.siliconflow.cn" -> {
                // SiliconFlow: enable_thinking is honored only by an allowlist.
                if (model.id in SILICONFLOW_THINKING_MODELS) {
                    body.put("enable_thinking", level.isEnabled)
                }
                return
            }

            "api.moonshot.cn" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                    // K2.6: thinking.keep defaults to null (drop history thinking);
                    // must be "all" for retention-style thinking when enabled.
                    if (level.isEnabled && lid.contains("k2.6")) put("keep", "all")
                })
                return
            }

            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                })
                return
            }

            "chat.intern-ai.org.cn" -> {
                body.put("thinking_mode", level.isEnabled)
                return
            }

            "open.bigmodel.cn" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                })
                return
            }

            "aiping.cn" -> {
                body.put("enable_thinking", level.isEnabled)
                return
            }

            // [T-sensenova-effort-enum] Sensenova's OpenAI-compat gateway
            // (token.sensenova.cn / api.sensenova.cn) validates reasoning_effort
            // against a STRICT {low, medium, high, xhigh, none} enum on the streaming
            // path and rejects "max" with
            //   400 field ReasoningEffort invalid, should be one of: low, medium, high, xhigh, none
            // while deepseek-v4's built-in relay rule (and the generic wireEffort map)
            // emit "max" for every tier above HIGH. Measured live 2026-09-06:
            // non-streaming accepted max, streaming 400'd it — so the clamp applies
            // unconditionally. deepseek-v4-flash was the observed victim; the same
            // shape protects glm-5.2 / kimi-k3 / sensenova-* ids on this host.
            "token.sensenova.cn", "api.sensenova.cn" -> {
                if (level == ThinkingLevel.AUTO) return
                if (model.supportsReasoning == false) return
                if (!level.isEnabled) {
                    // Measured live: `reasoning_effort:"none"` is accepted and really
                    // stops the reasoning stream; enable_thinking is honoured but does
                    // NOT suppress reasoning_content on deepseek-v4-flash.
                    body.put("reasoning_effort", "none")
                    return
                }
                // ON: clamp the tier onto the gateway's enum. HIGH and above all land
                // on xhigh — the strongest tier the endpoint accepts.
                body.put("reasoning_effort", "xhigh")
                return
            }
        }

        // [T-android-thinking-rules-phase2] Everything below the host table is now
        // delegated to ThinkingRuleResolver — a declarative, first-match-wins rule
        // registry (built-in vendor rules + user-authored custom rules) that replaced
        // the old if-return chain. The resolver reproduces the pre-refactor wire shapes
        // branch for branch (OpenRouter nested reasoning, qwen dual-send vs relay
        // root-only, deepseek-v4 official sibling vs relay top-level, unified-gateway
        // reasoning_effort, self-reasoning family skip, generic fallback) and adds the
        // user-editable escape hatch (ThinkingWireFormat.CustomPath). The `when(host)`
        // table above stays OUTSIDE the registry: it encodes host-exact relay dialects
        // measured live (RikkaHub absorption) that a model-pattern scope cannot express,
        // and every known relay host short-circuits before the resolver runs.
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            declaredEffortValues = model.reasoningEffortValues,
            declaresNoEffortTiers = model.declaresNoEffortTiers == true,
            level = level,
            maxTokens = maxTokens,
            isOpenRouter = isOpenRouter,
            usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
            isMistral = isMistral,
            isDashScope = isDashScope,
            isXAI = isXAI,
            isOfficialDeepSeek = isOfficialDeepSeek,
            offEffort = explicitOffEffort(),
        )
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Which rule actually won must be inspectable,
        // or a rule layer just replaces one hidden variable with a more complicated one.
        // [T-thinking-resolve-in-log] The INPUT side of the decision is logged first
        // (mirrors iOS `[resolve.in]`): when a relay 400s, the full decision input must
        // be reconstructable from logs alone — endpoint predicates, declared tiers, and
        // the off-tier in play — not just the winner. Otherwise a wrong rule fires for
        // reasons invisible after the fact.
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve.in] model=${model.id} level=${level.name} " +
                "openrouter=$isOpenRouter unified=$usesUnifiedReasoningEffort " +
                "mistral=$isMistral dashscope=$isDashScope xai=$isXAI " +
                "officialDeepSeek=$isOfficialDeepSeek " +
                "supportsReasoning=${model.supportsReasoning} " +
                "declared=${model.reasoningEffortValues?.joinToString("|") ?: "null"} " +
                "declaresNoEffort=${model.declaresNoEffortTiers == true} " +
                "offEffort=${explicitOffEffort() ?: "null"} maxTokens=$maxTokens",
        )
        com.openminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
    }

    /**
     * Extract `<think>...</think>` tags from content text (used by Qwen, DeepSeek, etc.).
     * Returns thinking content and visible text separately.
     */
    private data class ThinkExtractResult(val visible: String, val thinking: String)

    private class ThinkTagState {
        var buffer = StringBuilder()
        var insideTag = false
        var currentFormat: ThinkTagDef? = null

        fun reset() {
            buffer = StringBuilder()
            insideTag = false
            currentFormat = null
        }
    }

    private fun extractThinkTags(text: String, state: ThinkTagState): ThinkExtractResult {
        state.buffer.append(text)
        val result = scanThinkTags(state.buffer.toString(), state.insideTag, state.currentFormat, THINK_TAG_FORMATS)
        state.buffer = StringBuilder(result.remainingBuffer)
        state.insideTag = result.insideTag
        state.currentFormat = result.currentFormat
        return ThinkExtractResult(result.visible, result.thinking)
    }

    /** Flush any residual think-tag buffer at stream end; returns the buffered string. */
    private fun flushThinkTags(state: ThinkTagState): String? {
        if (state.buffer.isEmpty()) return null
        val remaining = state.buffer.toString()
        state.buffer = StringBuilder()
        return remaining
    }

    // MARK: - Codex image generation (gpt-image-2)

    /**
     * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
     * request body. The wire model is gpt-5.5 (the Codex backend invokes the
     * underlying gpt-image-2 via the built-in image_generation tool); the user
     * turn is the fixed "Use the image generation tool to create: <prompt>"
     * instruction. The <prompt> is the latest user text — plain string content
     * or the concatenated text parts of the last user message.
     */
    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
            b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
            else -> "image/png"
        }
    }

    // MARK: - Responses API (Codex OAuth)

    /**
     * Build request body for the Responses API format (used by Codex OAuth).
     * Uses `input` instead of `messages`, `instructions` instead of system prompt.
     */
    private fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject {
        // T264: same vision-capability gate as buildRequestBody. Responses API
        // path (Codex OAuth) is currently always wired to a vision-capable
        // GPT-5.x so this branch is defensive rather than load-bearing, but
        // keeping the two paths symmetric prevents future regressions when
        // a non-vision model gets routed through Responses (e.g. via
        // forceResponsesAPI on a custom provider).
        val supportsImages = "image" in (model.inputModalities ?: emptyList())
        // Defense-in-depth: strip orphan tool_use/tool_result pairing before
        // serialization (same rationale as buildRequestBody — the Responses
        // API rejects an unanswered function_call and a function_call_output
        // with an unknown call_id). Sanitize BEFORE deriving the prompt cache
        // key so the key reflects the payload that actually goes on the wire.
        val sanitizedMessages = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("OpenAIProvider", detail)
        }
        val body = JSONObject()
        body.put("model", model.id)
        body.put("stream", stream)
        body.put("store", false)
        body.put("parallel_tool_calls", true)
        // Stable per-conversation cache key so the Responses API can hit prompt
        // cache across turns. Codex CLI sets this to its conversation_id; at
        // this layer we don't have one, so we hash the first user message —
        // re-sent verbatim every turn of the same chat → stable across turns,
        // distinct between chats. iOS does the same in
        // OpenAIAgentProvider.swift:325 + derivePromptCacheKey() at line 515.
        // Without this, each turn was treated as a separate prompt by the
        // Responses-API cache regardless of how byte-stable the prefix was —
        // that's the missing piece between Android (~70%) and iOS (90%+) on
        // the Codex OAuth / forceResponsesAPI path.
        body.put("prompt_cache_key", derivePromptCacheKey(sanitizedMessages))
        // T-responses-include: `include: ["reasoning.encrypted_content"]` is a
        // ChatGPT-backend-only field. Third-party Responses-API-compatible
        // proxies (non-OpenAI) don't recognize it and reject the request with
        // 400. Mirrors iOS OpenAIAgentProvider.swift:404 which gates this
        // strictly behind isCodexOAuth. OpenAI's first-party Responses API
        // also accepts the field, so we keep it on for OAuth (Codex) only —
        // the encrypted reasoning content is what lets the ChatGPT backend
        // Thinking level → Responses API `reasoning.effort`. Mirrors iOS
        // OpenAIAgentProvider.swift:327-338. Pre-T119 this was hardcoded to
        // "low" regardless of the user's setting, so toggling Thinking
        // High/Medium/Off had no effect on GPT-5.x via the Responses path.
        // - When the user has thinking enabled → map their level to the
        //   matching effort string.
        // - Else → omit the field so the upstream applies its own default.
        // [T-android-codex-thinking-summary] `summary: "auto"` opts in to
        // streaming the human-readable reasoning SUMMARY (delivered as
        // `response.reasoning_summary_text.delta` SSE events with non-empty
        // `delta`). Without it the Responses API / Codex backend returns ONLY
        // `encrypted_content` — the reasoning deltas arrive empty, so the
        // Thinking region never renders even though the model reasoned (token
        // usage shows it did). This was the Codex-OAuth "thinking on but UI
        // shows nothing" bug (XIN). Mirrors iOS OpenAIAgentProvider.swift:415
        // (`["effort": effort, "summary": "auto"]`). OpenAI ignores the variant
        // it doesn't support and falls back to an auto-equivalent, so it's safe
        // on every Responses-flavor endpoint.
        // [T-android-xhigh-effort-clamp] Also clamp on the Responses API path:
        // the reasoning.effort field is the same name/values as Chat Completions
        // and would send xhigh too. MiMo-2.5/Agnes normally use Chat Completions
        // (the reported 400/422), but a user could flip useResponsesAPI on, so
        // guard it here as well — only xhigh for those two families is affected.
        // [T-android-thinking-level-arch] `thinkingLevel` is already clamped by
        // LLMProvider.streamMessage/sendMessage before reaching here.
        // [T-thinking-auto-level] AUTO maps to null effort; the effort branch
        // below skips, and the OFF branch is gated by !isEnabled (AUTO is
        // enabled), so AUTO emits NO reasoning object — vendor default applies.
        val effort = if (thinkingLevel.isEnabled) {
            mapThinkingLevelToResponsesEffort(thinkingLevel)?.let { clampEffortForModel(it) }
        } else null
        when {
            // [T-android-mistral-reasoning-422] Mistral rejects the reasoning
            // request parameter outright (`422 extra_forbidden body.reasoning`,
            // GH OpenMinis#87). The Chat-Completions gate covers only
            // injectThinkingParams; this builder is a SECOND, independent
            // injection site that a Mistral instance with useResponsesAPI
            // enabled would reach ungated. For Mistral the answer to "should
            // any thinking field be sent" is NEVER, on every request path —
            // so suppress the whole block. Must stay FIRST so it wins over the
            // branches below (absorbed upstream, same fix).
            isMistral -> {}
            effort != null -> body.put(
                "reasoning",
                JSONObject().put("effort", effort).put("summary", "auto"),
            )
            // [T-thinking-off-explicit] Thinking OFF on a reasoning-capable
            // model: send the explicit off tier instead of omitting `reasoning`
            // — omission lets the vendor default kick in. Same ALLOWLIST as the
            // Chat path (official OpenAI → "none", Volcano Ark → "minimal");
            // vendors with undocumented off semantics keep the historical
            // omission. No summary/include: nothing should stream back.
            // Mirrors iOS OpenAIAgentProvider ff60c818's Responses off branch.
            !thinkingLevel.isEnabled && model.supportsReasoning == true &&
                !model.id.lowercase().let { it.contains("mimo") || it.contains("agnes") } -> {
                explicitOffEffort()?.let { offEffort ->
                    body.put("reasoning", JSONObject().put("effort", offEffort))
                }
            }
        }

        // [T-responses-max-output-tokens] The builder received maxTokens but
        // never wrote it into the body, so Responses-flavor vendors fell back
        // to their (often tiny) defaults and truncated. Same guard as iOS
        // 637cd890/5f148144: maxTokens > 0. Also defense-in-depth clamp so an
        // out-of-band over-range value can't 400.
        val safeMaxTokens = clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model))
        if (safeMaxTokens > 0) {
            body.put("max_output_tokens", safeMaxTokens)
        }

        // [T-codex-fast-mode] Fast tier injection (mirrors iOS fb671083 +
        // 838ba929). Wire value verified against openai/codex source
        // (codex-rs/protocol config_types.rs): ServiceTier::Fast sends
        // service_tier="priority" — "fast" is only the UI name. Gate is
        // toggle + gpt-family model only: this builder IS the Responses
        // path, and Responses relays (e.g. sub2api) normalize/pass the tier
        // through. Ineligible upstreams ignore the
        // field or silently downgrade (receipt visible via the
        // response.completed service_tier log).
        if (com.openminis.app.data.FastModePrefs.isEnabled() &&
            model.id.contains("gpt", ignoreCase = true)
        ) {
            body.put("service_tier", "priority")
        }

        if (systemPrompt != null) {
            body.put("instructions", systemPrompt)
        }

        // Tools — flat shape required by Responses API ({type, name, description,
        // parameters}), distinct from Chat Completions' wrapped {type, function:{...}}.
        // Until this branch existed, Responses-API requests went out with no `tools`
        // field at all, so the model invented its own <tool_call>{...} text format.
        if (tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            for (tool in tools) {
                toolsArray.put(tool.toResponsesAPIJson())
            }
            body.put("tools", toolsArray)
            body.put("tool_choice", "auto")
        }

        // Mirrors iOS convertMessagesResponsesAPI (OpenAIAgentProvider.swift:895):
        // structured content parts become typed input items — function_call /
        // function_call_output — instead of free-text role/content pairs.
        val input = JSONArray()
        for (msg in sanitizedMessages) {
            if (msg.contentParts.isNotEmpty()) {
                when (msg.role) {
                    LLMMessage.Role.ASSISTANT -> {
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            val text = textParts.joinToString("") { it.text }
                            if (text.isNotEmpty()) {
                                input.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", text)
                                })
                            }
                        }
                        for (tu in msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()) {
                            val (callId, fcId) = splitResponsesAPIIds(tu.id)
                            val safeCallId = capResponsesId(callId)
                            // Responses API requires both `id` (fc_…) and `call_id` (call_…).
                            // When the message was synthesized outside a Responses round-trip
                            // (e.g. injected from Chat Completions history) the fcId is null —
                            // generate a deterministic synthetic so the API still accepts it.
                            val safeFcId = fcId?.let { capResponsesId(it) }
                                ?: "fc_syn_${safeCallId.takeLast(24)}"
                            input.put(JSONObject().apply {
                                put("type", "function_call")
                                put("id", safeFcId)
                                put("call_id", safeCallId)
                                put("name", tu.name)
                                put("arguments", tu.input.toString())
                            })
                        }
                    }
                    LLMMessage.Role.USER -> {
                        for (tr in msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                        }
                        // T132: emit text + input_image content for the user
                        // turn so vision-capable Responses-API models actually
                        // see the bytes. Without the input_image branch the
                        // outer `content` was a flat concatenated string and
                        // image bytes never reached the wire (the textual
                        // [attached image: …] caption was the only hint, and
                        // the model fell back to read_image / shell_execute
                        // groping for a path it could see). Mirrors iOS
                        // convertMessagesResponsesAPI's image handling.
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        val imgParts = msg.contentParts.filterIsInstance<AgentContentPart.ImageData>()
                        if (imgParts.isNotEmpty()) {
                            val contentArray = JSONArray()
                            for (part in msg.contentParts) {
                                when (part) {
                                    is AgentContentPart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                        }
                                    }
                                    is AgentContentPart.ImageData -> {
                                        if (supportsImages) {
                                            // T-imgsize: backstop for Responses API path.
                                            val safeBytes = com.openminis.app.provider.ImageBudget.compressUnderBudget(part.data)
                                            val safeMime = if (safeBytes === part.data) part.mimeType else "image/jpeg"
                                            val b64 = Base64.encodeToString(safeBytes, Base64.NO_WRAP)
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_image")
                                                // Responses API takes image_url
                                                // as a *string*, not the
                                                // {"url":...} object shape used
                                                // by Chat Completions.
                                                put("image_url", "data:$safeMime;base64,$b64")
                                            })
                                        } else {
                                            // T264: target model has no vision modality —
                                            // emit text placeholder (iOS-parity literal).
                                            // Note: Responses API uses "input_text" type
                                            // (vs "text" on Chat Completions, see Text
                                            // branch above at line 1038).
                                            contentArray.put(JSONObject().apply {
                                                put("type", "input_text")
                                                put("text", "[Image attached but this model does not support vision input]")
                                            })
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        } else if (textParts.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put("content", textParts.joinToString("") { it.text })
                            })
                        }
                    }
                    else -> {
                        input.put(JSONObject().apply {
                            put("role", msg.role.value)
                            put("content", msg.content)
                        })
                    }
                }
            } else if (msg.audioParts.isNotEmpty()) {
                // [GH#67] Legacy (non-contentParts) message carrying audio —
                // the minis-model-use path. The Responses API keeps the SAME
                // nested input_audio shape as Chat Completions ({data,
                // format}), unlike input_image which flattens image_url to a
                // string. Text rides along as input_text.
                val contentArray = JSONArray()
                for (audio in msg.audioParts) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", audio.base64Data)
                            put("format", audio.format)
                        })
                    })
                }
                if (msg.content.isNotEmpty()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", msg.content)
                    })
                }
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", contentArray)
                })
            } else {
                input.put(JSONObject().apply {
                    put("role", msg.role.value)
                    put("content", msg.content)
                })
            }
        }
        body.put("input", input)

        // [T-android-model-use-passthrough-mode GH#72] Same verbatim merge as the
        // chat-completions builder. Skipped for Codex OAuth inside mergeChatExtraBody.
        mergeChatExtraBody(body)

        return body
    }

    /**
     * Combine Responses-API call_id + item_id into a single string the agent loop
     * can carry through tool_use/tool_result blocks. The next request splits it
     * back apart so the API sees the original ids verbatim.
     */
    private fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"

    private fun splitResponsesAPIIds(combined: String): Pair<String, String?> {
        val sep = combined.indexOf('|')
        return if (sep < 0) combined to null
        else combined.substring(0, sep) to combined.substring(sep + 1)
    }

    /** Responses-API ids must be ≤64 chars; truncate defensively to avoid 400s. */
    private fun capResponsesId(id: String): String =
        if (id.length <= 64) id else id.substring(0, 64)

    /**
     * [T-android-tool-call-id-too-long] Chat-Completions `tool_calls[].id` /
     * `tool_call_id` must be ≤64 chars — OpenAI-compatible endpoints reject longer
     * ids with a 400 ("string too long. Expected a string with maximum length 64").
     * Two ways an id gets over 64 here:
     *   - a Responses-origin history turn stores the combined "call_…|fc_…" id
     *     (splitResponsesAPIIds' form) which is replayed verbatim on a Chat
     *     Completions request;
     *   - memory-tool / synthetic ids that are long by construction.
     * We keep only the call_-id half (before any '|') and, if still >64, replace
     * it with a deterministic SHA-256-derived id. Determinism matters: the SAME
     * raw id must map to the SAME capped id so the assistant tool_call and its
     * matching tool result still pair up (a mismatch is its own 400). The
     * downstream dedupe pass then guarantees uniqueness within the request.
     */
    private fun capChatToolCallId(id: String): String {
        val callHalf = id.substringBefore('|')
        if (callHalf.length <= 64) return callHalf
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(callHalf.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        // "call_" + 56 hex chars = 61 chars, safely under 64 and clearly a call id.
        return "call_${hex.take(56)}"
    }

    /**
     * Derives a stable per-conversation `prompt_cache_key` for the Responses
     * API. Mirrors iOS derivePromptCacheKey (OpenAIAgentProvider.swift:515).
     * The first user message is re-sent verbatim on every turn → its hash is
     * stable across turns within the same chat, distinct between chats.
     * Falls back to a random UUID when there is no user text yet (first
     * turn with attachments-only input, etc.).
     */
    private fun derivePromptCacheKey(messages: List<LLMMessage>): String {
        for (msg in messages) {
            if (msg.role != LLMMessage.Role.USER) continue
            val text = msg.contentParts
                .filterIsInstance<AgentContentPart.Text>()
                .joinToString("") { it.text }
                .ifEmpty { msg.content }
            if (text.isNotEmpty()) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { "%02x".format(it) }
                return "minis-${hex.take(32)}"
            }
        }
        return "minis-${java.util.UUID.randomUUID().toString().lowercase()}"
    }

    /**
     * Map ThinkingLevel → Responses API `reasoning.effort` string. Mirrors
     * iOS reasoningEffort(for:level:) (OpenAIAgentProvider.swift:531).
     * Returns null when the level is OFF — caller decides whether to omit
     * the `reasoning` field entirely or fall back to "low" (Codex requires it).
     */
    private fun mapThinkingLevelToResponsesEffort(level: ThinkingLevel): String? = when (level) {
        ThinkingLevel.OFF -> null
        // [T-thinking-auto-level] no effort opinion — caller omits the reasoning
        // object entirely (see buildResponsesAPIBody's effort handling).
        ThinkingLevel.AUTO -> null
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] MAX → "max"; ULTRA also → "max" —
        // the Responses/Codex endpoint rejects a literal "ultra"; ultra is a
        // client-side "Max + orchestration" concept only (mirrors iOS).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * Responses-API tool shape — flat {type, name, description, parameters},
     * NOT the Chat Completions wrapper {type, function:{...}}. Mirrors iOS
     * convertToolsResponsesAPI (OpenAIAgentProvider.swift:977).
     */
    private fun AgentToolDefinition.toResponsesAPIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** Parse usage from Responses API format. */
    private fun parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
        val inputTokens = usage.optInt("input_tokens", 0)
        // Responses API reports cache hits at `input_tokens_details.cached_tokens`
        // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
        // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
        // cached Responses-API request showed cacheRead=0 in usage stats —
        // making T126's prompt_cache_key wiring look like it had no effect.
        val cacheRead = usage.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
        // `input_tokens` is the FULL input (cached subset included); subtract the
        // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
        // the cache is counted twice in `input + cacheRead` (deflates hit rate).
        // Guard: only subtract when non-negative; no cache field → unchanged.
        // latestContextTokens stays the full input (that IS the context size).
        val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("output_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = inputTokens,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterMs: Long? = null): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return LLMError.RateLimited(retryAfterMs = retryAfterMs)

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$statusCode] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            // 503 with permanent failure indicators → ProviderError (trigger group fallback)
            if (statusCode == 503 && (body.contains("no_available_providers") || body.contains("model_not_found"))) {
                return LLMError.ProviderError(message)
            }
            return LLMError.TransientError(message)
        }
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}

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
private class OkHttpNetTraceListener : EventListener() {
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
