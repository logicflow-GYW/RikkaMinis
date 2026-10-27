package com.rikkaminis.app.provider.openai

import android.util.Base64
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.parseRetryAfterMs
import com.rikkaminis.app.data.model.LLMMediaAttachment
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.provider.ProviderBoundary
import com.rikkaminis.app.provider.TOOL_LOG_INPUT_STEP_CHARS
import com.rikkaminis.app.provider.causeChainSummary
import com.rikkaminis.app.provider.asConsumerSideCancellation
import com.rikkaminis.app.sandbox.offload.FirstChunkTimeoutPolicy
import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.provider.extractHttpErrorMessage
import com.rikkaminis.app.provider.safeOptString
import com.rikkaminis.app.provider.sanitizeToolPairing
import com.rikkaminis.app.provider.hasWirePayload
import com.rikkaminis.app.provider.LegacyMediaPlan
import com.rikkaminis.app.provider.clampOutboundMaxTokens
import com.rikkaminis.app.provider.clampOutboundTemperature
import com.rikkaminis.app.provider.StreamTimeouts
import com.rikkaminis.app.provider.StreamCancelBridge
import com.rikkaminis.app.provider.VISION_UNSUPPORTED_PLACEHOLDER
import com.rikkaminis.app.provider.openai.explicitOffEffortFor
import com.rikkaminis.app.provider.thinking.EffortTierLearner
import com.rikkaminis.app.provider.thinking.GatewayEffortTruth
import com.rikkaminis.app.provider.thinking.ReasoningEchoDecider
import com.rikkaminis.app.provider.thinking.ReasoningEchoPolicy
import com.rikkaminis.app.provider.thinking.ThinkingResolveContext
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.rikkaminis.app.provider.failOnSilentEmptyCompletion

// -- Think-tag extraction (case-insensitive, multi-format) --

// [refactor/split-provider] Think-tag scanning types + scanner moved verbatim
// to ThinkTagScanning.kt (visibility unchanged: already internal).


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
    override var instanceContext: com.rikkaminis.app.data.model.ProviderInstance? = null

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
         * [T-toolinput-sample-1012] Granularity of the "→ ToolInputDelta"
         * debug line: one per this-many chars of accumulated tool-args
         * growth (plus the first delta). 2048 chars per line caps a huge
         * tool call (40 KB args) at ~20 lines instead of ~hundreds of
         * per-fragment lines, while keeping enough resolution to see
         * progress in the file. Measured cost of the unsampled line:
         * 251K lines / 33.5 MB per day = 74% of the worker's daily file.
         *
         * Value lives in ToolInputLogStep.kt (shared with AnthropicProvider —
         * D-2 review note: duplicated consts drift). This companion name was
         * removed; call sites resolve to the top-level constant.
         */
        /**
         * [FIX-1 / F-186] Ceiling for a generated-image download we initiate
         * ourselves (the `url` branch of parseImageGenerationsResult). The
         * b64 branch is bounded by the response body; this one is not, so we
         * pick the number. 16 MiB comfortably covers a 4096x4096 PNG from any
         * current image model while staying well inside a phone's per-process
         * heap budget — and, like ArtifactBackupScope.MAX_FILE_BYTES, an
         * over-limit payload is skipped with a log line rather than truncated,
         * because a truncated image is worse than a missing one.
         */
        private const val MAX_GENERATED_IMAGE_BYTES = 16L * 1024 * 1024

        /**
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited. This is the
         * dead-upstream signal: a wedged tunnel never reaches headers at all.
         *
         * [fix/ttfb-thinktag-composer] 2026-09-11: raised 30s → 90s. The 30s
         * carried the assumption "headers arrive fast even for slow
         * generations" — measured false for queueing relays/gateways: direct
         * probing of a relay (api.senseaudio.cn) found 4/8 requests sitting
         * 42.9–59.3s BEFORE headers (all latency in the upstream queue, not
         * the body). Killing those at 30s turned ordinary relay queueing into
         * a forced retry loop (retry → re-queue → killed again), which users
         * experience as the provider "failing mid-answer". 90s covers the
         * observed distribution with ~1.5× margin. Trade-off: a genuinely
         * wedged tunnel now surfaces here 60s later; NetworkMonitor's pool
         * eviction on network transitions and the retry ladder remain the
         * first-line recovery, so the dead-tunnel case stays bounded and
         * self-healing.
         */
        private const val STREAM_TTFB_TIMEOUT_MS = StreamTimeouts.TTFB_TIMEOUT_MS

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
        // [feat/runtime-limits-panel] const val 要求编译期常量，函数调用不能进
        // const 初始化器 —— 改为运行期求值（companion 对象初始化时读取一次，
        // 进程级；provider 进程的 OkHttpClient 构建/首数据看门狗都在其后）。
        // worker 进程里该值随进程换代刷新；主进程直连路径在 app 启动时定型。
        private val STREAM_FIRST_DATA_TIMEOUT_MS =
            com.rikkaminis.app.sandbox.offload.FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null) * 1000L

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
     *
     * [da1030 ISSUE-06] The body now delegates to the package-level
     * [applyOpenAIKeyAuth] so the models-list fetcher (OpenAIModelsApi) shares
     * this exact rule instead of keeping a second, hard-coded Bearer path.
     */
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        applyOpenAIKeyAuth(isAzure, token)

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
        .readTimeout(FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null).toLong(), TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        .connectionPool(com.rikkaminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        // [absorb-network-pack] Listener promoted to the shared network
        // package; wrapped in the per-model TTFB/health feed (trace
        // semantics preserved via the OkHttpNetTraceListener subclass).
        .eventListenerFactory { com.rikkaminis.app.network.ProviderHealthTraceListener(model.id, model.displayName) }
        .build()

    /** Detect OpenRouter base URL. */
    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /**
     * [FIX-1 / F-183] Whether this endpoint uses OpenAI's renamed
     * `max_completion_tokens` field instead of the classic `max_tokens`.
     *
     * Whitelist, not a default. The rename shipped with the reasoning-model
     * generation and is honoured by OpenAI's own API; every other OpenAI-shaped
     * server — including the gateways that track OpenAI closely — still
     * implements the Chat-Completions schema where the field is `max_tokens`.
     * Getting this backwards produces a 400 that says nothing about which field
     * was wrong, so the safe default is the field name that has been accepted
     * for years.
     *
     * Deliberately ONE host. openrouter.ai is NOT here even though it is a
     * first-class gateway: the previous code sent it `max_tokens` and there is
     * no evidence it rejects that, so adding it would be an unforced behaviour
     * change. Add a host only with a reason (a documented rename or an observed
     * rejection of `max_tokens`), never by inference from the provider name.
     */
    private val usesMaxCompletionTokens: Boolean = basePath.contains("api.openai.com")

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
     *
     * [GH#377] The allowlist answers "which VENDOR do we trust to document an
     * off tier", but the value is only accepted if the MODEL declares it. Those
     * are two different questions, and the base alone cannot answer the second:
     * a gateway serving a model whose catalog entry declares `low..max` (no
     * `none`) still got `effort:"none"` from us whenever its base looked like
     * official OpenAI — the backend then rejects the whole request with 400.
     * A JVM experiment over the real source pair confirmed the shape: the SAME
     * model with `declared=low..max` sent `none` on `api.openai.com` (400) and
     * omitted the field on a relay (accepted) — i.e. the decision was driven by
     * the base URL rather than by what the model said it accepts. That relay arm
     * is the control: it proves the model's own capability is not what the old
     * predicate was reading.
     *
     * So the declared set is now a veto: an explicit off tier is emitted ONLY
     * when `reasoningEffortValues` is non-null AND contains it. `null` (the
     * catalog never heard of this model) stays permissive on purpose — it means
     * "unknown", not "declares nothing"; the latter is the separate
     * [com.rikkaminis.app.data.model.LLMModel.declaresNoEffortTiers] flag, and
     * treating unknown as a veto would silently re-disable the explicit-off
     * behaviour on every model the catalog does not cover (i.e. the exact
     * pre-#377 behaviour for the whole allowlist).
     */
    private fun explicitOffEffort(): String? =
        explicitOffEffortFor(
            basePath = basePath,
            isAzure = isAzure,
            modelId = model.id,
            declaredEffortValues = model.reasoningEffortValues,
            unifiedEffortGateway = usesUnifiedReasoningEffort,
        )

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
        // [fix/audit-b22 / T5-L7] Call the *clamped* variant. The public
        // streamMessage() is the wrapper that runs ProviderBoundary.enforce()
        // and records an RSS sample; sendMessage() already ran both for this
        // call, so going through streamMessage() enforced twice and recorded
        // two RSS deltas (kind sendMessage:* and streamMessage:*) for one
        // request, skewing the probe's per-kind statistics.
        //
        // The wrapper's clampThinkingLevel() was load-bearing, though: callers
        // that drive sendMessageClamped() directly (the thinking-wire golden
        // tests, and any future in-process caller) rely on the model's catalog
        // ceiling being applied before the request is built. Keep the clamp,
        // drop only the duplicated boundary/RSS work.
        streamMessageClamped(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = clampThinkingLevel(thinkingLevel),
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
            buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, tools = tools, thinkingLevel = thinkingLevel, imageParts = imageParts)
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
            com.rikkaminis.app.logging.AppLogger.info(
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
            .readTimeout(FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(null).toLong(), TimeUnit.SECONDS)
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
                com.rikkaminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-stale-conn-retry-hang] no response headers after ${STREAM_TTFB_TIMEOUT_MS / 1000}s — cancelling call (stale pooled connection?)",
                )
                call.cancel()
            }
        }
        // [fix/zero-chunk-cancel] Cancellation BRIDGE, registered BEFORE the
        // first blocking call (call.execute() below, then reader.readLine()).
        // The bridge is what makes a user cancel land while the body is parked
        // in a blocking read; the shared implementation carries the three
        // load-bearing semantics (catch-not-finally, cause-recorded-first,
        // cancel-only-when-cancelled) and its measurements — see
        // [com.rikkaminis.app.provider.StreamCancelBridge].
        //
        // Pairs with the worker's cancel watcher (ModelExecutionService),
        // which cancels the collecting job when the user's cancel file
        // appears — that is what reaches this bridge.
        val cancelBridge = StreamCancelBridge(scope = this) { call.cancel() }
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
            com.rikkaminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.rikkaminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.rikkaminis.app.BuildConfig.DEBUG) {
                com.rikkaminis.app.debug.LLMRequestLog.add(
                    com.rikkaminis.app.debug.LLMRequestLog.Entry(
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
            // [T-android-effort-self-learn] The gateway's 400 body often spells out the
            // exact effort enum it accepts — the cheapest one-hand truth we will ever get.
            // No probe request, and a gateway changing its enum does not need a release
            // from us to be absorbed. Learning nothing is not an error: record() returns
            // null and throws nothing.
            EffortTierLearner.record(request.url.host, bodyStr, errorBody)
            throw mapHttpError(
                response.code,
                errorBody,
                parseRetryAfterMs(response.headers["Retry-After"], System.currentTimeMillis()),
            )
        }
        if (com.rikkaminis.app.BuildConfig.DEBUG) {
            com.rikkaminis.app.debug.LLMRequestLog.add(
                com.rikkaminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        // [audit-0917] No explicit charset: Android's platform default is
        // always UTF-8 (Chrome/ART both fix file.encoding=UTF-8; verified), and
        // SSE payloads are UTF-8 by spec. Left as-is deliberately — switching
        // to body.charStream() would change byte handling for the raw stream
        // paths without fixing anything observable.
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        // [T-toolinput-sample-1012] lastLoggedInputChars gates the per-delta
        // "→ ToolInputDelta" debug line: one per TOOL_LOG_INPUT_STEP_CHARS of
        // accumulated args (plus the first), instead of one per fragment.
        // Measured 2026-10-12: 251K lines / 33.5 MB per day = 74% of the
        // worker's daily file, for a line that only reports a length.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastLoggedInputChars: Int = -1)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        // [T-toolinput-sample-1012] lastLoggedInputChars: ToolInputDelta log
        // sampling state, same contract as the chat-completions accumulator.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastLoggedInputChars: Int = -1)
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
                    com.rikkaminis.app.logging.AppLogger.warning(
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
                    com.rikkaminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                if (com.rikkaminis.app.BuildConfig.DEBUG) {
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
                            com.rikkaminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.rikkaminis.app.logging.AppLogger.debug(
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
                    // [dv28 A-01] Second feed site of the SAME counter the EOF
                    // guard reads. Before this, only the Chat Completions
                    // `delta.tool_calls` shape fed it, so a tool-call-only
                    // Responses turn cut by EOF looked like an EMPTY turn
                    // (contentLen == reasoningLen == toolCallEventCount == 0),
                    // emitted no Finished(truncated = true), and let a
                    // half-streamed argument object reach the executor.
                    toolCallEventCount += responsesToolCallEventWeight(
                        eventType = type,
                        itemType = event.optJSONObject("item")?.optString("type"),
                        argsDeltaLen = event.optString("delta", "").length,
                    )
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
                                    com.rikkaminis.app.logging.AppLogger.info(
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
                                // [T-toolinput-sample-1012] Sampled like the chat-completions path:
                                // first delta + one per TOOL_LOG_INPUT_STEP_CHARS of growth.
                                val n = acc.args.length
                                if (acc.lastLoggedInputChars < 0 || n - acc.lastLoggedInputChars >= TOOL_LOG_INPUT_STEP_CHARS) {
                                    acc.lastLoggedInputChars = n
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta (Responses) id=$combined accumulated=${n}chars")
                                }
                                send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.rikkaminis.app.logging.AppLogger.warning(
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
                            com.rikkaminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            // [dv28 A-08] Same single-source mapping as the Chat
                            // Completions inline-error arm: the symbolic code is turned
                            // into HTTP semantics first, so `rate_limit_exceeded` becomes
                            // RateLimited (back off / fall back to another route) instead
                            // of the TransientError it used to share with server_error
                            // (retry on the SAME model). Response body handed over is the
                            // error object itself, whose `message` field is what
                            // extractHttpErrorMessage reads.
                            throw mapHttpError(
                                openAiStreamErrorStatus(code, err?.optString("type", "") ?: ""),
                                err?.toString() ?: event.toString(),
                            )
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
                            com.rikkaminis.app.logging.AppLogger.error(
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
                                com.rikkaminis.app.logging.AppLogger.info(
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
                        // [dv28 A-08] The code token is read as a STRING and mapped to
                        // HTTP semantics before it reaches mapHttpError: relays spell
                        // it `{"code":"rate_limit_exceeded"}` or carry `type` only, and
                        // the old `optInt("code", 0)` coerced every such payload to 0 —
                        // which mapHttpError turns into a non-retryable ProviderError.
                        // [T-android-effort-self-learn] Same hook as the HTTP-400 path:
                        // some relays report the identical error inside a 200 stream, so
                        // the enum lands in the event instead of the status body.
                        EffortTierLearner.record(request.url.host, bodyStr, event.toString())
                        val err = mapHttpError(
                            openAiStreamErrorStatus(
                                inlineError.optString("code", ""),
                                inlineError.optString("type", ""),
                            ),
                            event.toString(),
                        )
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
                                    com.rikkaminis.app.logging.AppLogger.info(
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
                            // [dv28 A-01] Feed the EOF guard's turn counter from the
                            // Chat Completions flavour. The weight derivation lives in
                            // OpenAiStreamSignals.kt (single source, shared with the
                            // Responses arm) — a turn whose ONLY progress is a tool call
                            // must still be readable as non-empty when the stream dies
                            // without [DONE], or the truncated-tool-call guard never fires.
                            toolCallEventCount += chatCompletionsToolCallEventWeight(toolCalls.length())
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
                                    // [T-toolinput-sample-1012] Log the first delta, then once per
                                    // TOOL_LOG_INPUT_STEP_CHARS of growth — the per-fragment line
                                    // measured 33.5 MB/day (74% of the worker's daily file) while
                                    // carrying only a length. ToolUseStart/ToolCallComplete keep
                                    // their every-call lines; the flush path below covers
                                    // mid-tool-call truncation, so no endpoint loses coverage.
                                    val n = acc.args.length
                                    if (acc.lastLoggedInputChars < 0 || n - acc.lastLoggedInputChars >= TOOL_LOG_INPUT_STEP_CHARS) {
                                        acc.lastLoggedInputChars = n
                                        android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${n}chars")
                                    }
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
                                    com.rikkaminis.app.logging.AppLogger.info(
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
                    com.rikkaminis.app.logging.AppLogger.warning(
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
            // [deep-audit-1018 ISSUE-01] `toolCallEventCount` joins the guard
            // (Anthropic's `toolChars` counterpart — same-family 2nd site): a
            // tool-call-only turn (zero text/reasoning) cut by EOF used to emit
            // no Finished at all, so `turnTruncated` stayed false downstream and
            // the truncated-tool-call guard never fired — a half-streamed
            // argument object then reached the executor (and ToolJsonRepair
            // would happily close it into a valid-but-wrong payload).
            if (!finishedSent && (contentLen > 0 || reasoningLen > 0 || toolCallEventCount > 0)) {
                send(LLMStreamChunk.Finished(finishReason, truncated = !sawFinishReason))
            }

            // Final usage summary — printed once at stream end instead of per-delta.
            if (lastUsageJson != null) {
                com.rikkaminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] usage final: $lastUsageJson"
                )
                // [T321-reasoning-consistency] Upstream billed reasoning tokens
                // but the stream carried no reasoning content: the thinking was
                // either pasted into `content` (relay translation gap) or
                // dropped. Classifying this used to require grepping logcat and
                // comparing two log lines by hand; this makes it ONE line.
                val billedMissing = ReasoningConsistency.missingReasoningContent(lastUsageJson, reasoningLen)
                if (billedMissing > 0) {
                    com.rikkaminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] inconsistency: upstream billed $billedMissing reasoning tokens but the stream carried 0 reasoning content — thinking may be mixed into content or dropped by the relay",
                    )
                }
            }

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            if (!sawFinishReason) {
                com.rikkaminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.rikkaminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // [F-177] A cause-less CancellationException is the coroutine
            // machinery tearing this stream down (user tapped stop → the
            // worker's collector threw ModelExecutionCancelledException and the
            // callbackFlow producer sees the kotlinx wrapper). Logging it as a
            // "stream parse exception" made 57% of a day's ERROR lines a
            // non-error and hid the real reason. A CE *with* a cause is a real
            // downstream failure and stays on the ERROR path below.
            //
            // NOTE: no early return here — control must fall through to
            // `channel.close()` / `awaitClose { call.cancel() … }` below, which
            // is what tears the socket down. Returning would skip it.
            val consumerCancel = e.asConsumerSideCancellation()
                // [fix/zero-chunk-cancel] The read died of `SocketException:
                // Socket closed` because OUR bridge closed it — not because the
                // upstream failed. Re-ask F-177's question of the bridge's own
                // cancellation cause, which is the information the socket error
                // erased. Deliberately the SAME predicate, so "consumer-side
                // cancellation" keeps one definition: a cause-less CE is a
                // teardown, while a CE carrying a real failure stays an error.
                ?: cancelBridge.consumerCancellationCause?.asConsumerSideCancellation()
            if (consumerCancel != null) {
                com.rikkaminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream cancelled by consumer: ${e.causeChainSummary()} " +
                        "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
                )
                cancel(consumerCancel)
            } else {
                // T321: never silently swallow — log message + top-3 stack frames.
                // [F-177] the cause chain is now included: the outermost throwable
                // alone (usually the kotlinx wrapper) never named the real failure.
                val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                com.rikkaminis.app.logging.AppLogger.error(
                    "OpenAIProvider",
                    "[T321] stream parse exception: ${e.causeChainSummary()} @ $frames " +
                        "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
                )
                cancel("Stream error", mapError(e))
            }
        } finally {
            reader.close()
            response.close()
            // [fix/zero-chunk-cancel] The body was fully read (or failed) — mark
            // it so the cancellation bridge does not tear down a connection
            // OkHttp could otherwise return to the pool.
            cancelBridge.markStreamCompleted()
            // [T-thinking-fold-leak] Always drop the per-stream tag state on
            // every exit (normal, error, cancellation) so a half-open tag can
            // never leak into the next stream served by this provider instance.
            thinkState.reset()
        }
        cancelBridge.dispose()
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
        // [fix/provider-stream-flowon] The flow body above is SYNCHRONOUS: it
        // blocks inside call.execute() and then inside reader.readLine(). Left
        // on the collector's context it occupies that thread for the entire
        // stream, and every timer scheduled on the same dispatcher starves —
        // including this flow's own TTFB/first-data watchdogs. That was
        // invisible while the collector was a thread pool (pre-offload main
        // process); 01cfcc0e (2026-08-22, TF-D) moved the only collector into
        // the :modelservice worker's `runBlocking { ... }`, which has no
        // dispatcher of its own, so the body now owns the worker thread and
        // all three guards (TTFB, first-data, worker first-chunk timeout)
        // became unreachable — 2026-09-20: a 1.6MB request sat 272s with no
        // byte back and nothing fired.
        //
        // flowOn moves ONLY the producer (this flow body + its launched
        // watchdogs) to the IO pool; the collector stays where it was. It also
        // relocates awaitClose to the IO pool — its body is just call.cancel()
        // + response.close() + thinkState.reset() (a local object created
        // inside this flow), all thread-agnostic, so that is safe.
    }.flowOn(Dispatchers.IO)

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
        // [T5-M3] This is a provider network entry point, so it must obey the
        // same process boundary as sendMessage/streamMessage: the app process
        // must not fall back to an in-process provider call (TF-D).
        // [fix/audit3-1015 F-03] The note that used to sit here said the only
        // caller ran in the main process — which WAS the bug: `--passthrough`
        // called this from :app, the enforce() below threw, and the mode could
        // never succeed. The caller is ModelExecutionService's
        // `passthrough_request` branch, so this runs in :modelservice.
        ProviderBoundary.enforce(ProviderBoundary.currentProcessName())
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
        // [da1030 ISSUE-06] Same last-wins placement as the chat/responses
        // builders (:2390): after extraHeaders + user headers, so the
        // per-provider override beats any UA a caller smuggled into `headers`.
        builder.applyUserAgentOverride(customUserAgent)

        com.rikkaminis.app.logging.AppLogger.info(
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
        // [T5-M3] Same process boundary as sendMessage/streamMessage — see
        // rawPassthroughRequest. Image bytes are the largest single allocation
        // this app makes, so leaking them into the app process is precisely
        // what the :modelservice worker exists to prevent.
        ProviderBoundary.enforce(ProviderBoundary.currentProcessName())
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

            com.rikkaminis.app.logging.AppLogger.info(
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
                com.rikkaminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.rikkaminis.app.logging.AppLogger.warning(
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
                    com.rikkaminis.app.logging.AppLogger.warning(
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
                        // T5-L2: `use` closes the response even when reading the
                        // body throws mid-download. The previous explicit
                        // close() sat after bytes(), so an IOException there
                        // skipped it and leaked the connection instead of
                        // returning it to the pool.
                        client.newCall(dlReq).execute().use { dlResp ->
                            // [FIX-1 / F-186] This is the only place we issue a
                            // SECOND, self-initiated download on behalf of a
                            // provider response, so the size ceiling has to be
                            // ours. `body?.bytes()` read the whole thing into
                            // memory with no bound at all — a relay that
                            // answered an images request with a large (or
                            // deliberately unbounded) payload could OOM the
                            // process. Same practice as
                            // ArtifactBackupScope.MAX_FILE_BYTES: declare the
                            // ceiling, skip and log when exceeded.
                            val declaredLen = dlResp.body?.contentLength() ?: -1L
                            if (declaredLen > MAX_GENERATED_IMAGE_BYTES) {
                                com.rikkaminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "[ModelUseRoute] image download skipped: declared ${declaredLen}B exceeds ${MAX_GENERATED_IMAGE_BYTES}B",
                                )
                                // NOTE: this must NOT be a `continue` — the
                                // enclosing `use { }` is an INLINE lambda and
                                // `break`/`continue` across inline lambdas needs
                                // Kotlin 2.2 (CI: "The feature break continue in
                                // inline lambdas is only available since
                                // language version 2.2"). Falling through with
                                // dlBytes == null has exactly the same effect:
                                // the attachment is not added.
                            } else {
                                // contentLength is -1 for chunked responses, so
                                // the declared check alone is not enough: stream
                                // through a bounded buffer and abort as soon as
                                // the ceiling is crossed.
                                //
                                // NOT `source.readByteArray(n)`: that call reads
                                // EXACTLY n bytes and throws EOFException when the
                                // body is shorter, so it would have rejected every
                                // image smaller than the ceiling — i.e. all of
                                // them.
                                val dlBytes = readBodyBounded(dlResp.body, MAX_GENERATED_IMAGE_BYTES)
                                val ctMime = dlResp.header("Content-Type")
                                if (dlBytes != null && dlBytes.isNotEmpty()) {
                                    val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                                    attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                                } else if (dlBytes == null) {
                                    com.rikkaminis.app.logging.AppLogger.warning(
                                        "OpenAIProvider",
                                        "[ModelUseRoute] image download skipped: body exceeds ${MAX_GENERATED_IMAGE_BYTES}B",
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        com.rikkaminis.app.logging.AppLogger.warning(
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
        val sanitizedRaw = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("OpenAIProvider", detail)
        }
        // [deep-audit-1045 A-03] The request-level `imageParts` argument is
        // attached to the LAST user turn (attachTopLevelImages below), and the
        // `minis-model-use` CLI path builds that turn from text + media only —
        // its `content` is empty whenever the CLI call carries a bare image or
        // audio block. Keyed on the PRE-filter list on purpose: the filter's
        // whole job is to drop entries, so an index taken afterwards is -1 in
        // exactly the case this guard exists for.
        val rawLastUserIndex = sanitizedRaw.indexOfLast { it.role == LLMMessage.Role.USER }
        val sanitizedMessages = sanitizedRaw
            // [FIX-1 / F-211] Drop messages the sanitizer emptied (only orphan
            // tool parts get stripped). An empty `content` is a shape OpenAI
            // rejects, so without this filter the sanitizer manufactures the
            // very 400 it exists to prevent.
            //
            // AnthropicProvider has always had this filter; the two OpenAI call
            // sites did not — i.e. 1 of 4 production callers had it, while the
            // sanitizer's KDoc said "callers apply it themselves".
            //
            // Kept at the CALL SITE rather than turned into a sanitizer default:
            // empty-dropping is provider policy, not pairing policy. Gemini's
            // serializer turns "" into " " (a valid empty turn) and
            // RequestSanitizersTest pins that the sanitizer itself keeps them.
            //
            // [deep-audit-1045 A-03] "Empty" means no TEXT, not no payload: a
            // media-only turn (audioParts / imageParts, or the last user turn
            // whose bytes ride the top-level `imageParts` argument) still has
            // something to put on the wire, and dropping it deleted the user's
            // image / audio from the request entirely.
            .filterIndexed { index, m ->
                m.hasWirePayload(
                    isLastUserTurn = index == rawLastUserIndex,
                    turnImageCount = imageParts.size,
                )
            }
        val body = JSONObject()
        body.put("model", model.id)
        // Defense-in-depth clamp (see AnthropicProvider): upstream
        // dynamicMaxTokens() is in range; guard out-of-band callers.
        val safeMaxTokens = clampOutboundMaxTokens(maxTokens, effectiveMaxOutputTokens(model))
        // [FIX-1 / F-183] The old split was `isOpenRouter ? max_tokens :
        // max_completion_tokens` — i.e. everything that is NOT openrouter.ai got
        // `max_completion_tokens`, including every third-party relay. On this
        // device that meant 100% of traffic: the observed endpoints were
        // llmhost.net / api.senseaudio.cn / agentrouter.org / token.sensenova.cn,
        // none of which is openrouter.ai. `max_completion_tokens` is an
        // OpenAI-first-party rename; relays that proxy the classic
        // Chat-Completions schema frequently reject it, and the failure mode is
        // a 400 whose cause is invisible in the request log (the log line at
        // :609 already reads BOTH names, which is itself evidence the repo has
        // met this ambiguity before).
        //
        // Inverted to a whitelist: only hosts known to accept the new name get
        // it, everything else keeps the field name that has been in every
        // OpenAI-compatible schema since 2023. `max_tokens` is still accepted by
        // api.openai.com for non-reasoning models, so the blast radius of being
        // wrong here is the smallest of the two directions.
        if (usesMaxCompletionTokens) {
            body.put("max_completion_tokens", safeMaxTokens)
        } else {
            body.put("max_tokens", safeMaxTokens)
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
        //
        // [audit-0917] Use [isMistral], not an exact-host comparison. The old
        // `host != "api.mistral.ai"` only matched the apex host while isMistral
        // matches any *mistral.ai host (and a relay path), so a Mistral
        // deployment on a subdomain or a custom base still received
        // include_usage and 400'd — the two Mistral predicates now agree.
        if (stream && !isOpenRouter && !isMistral) {
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
        // [T-deepseek-v4-thinking-echo] The matched rule's reasoning-echo requirement,
        // filled in by injectThinkingParams below and consumed by the message loop.
        var echoPolicy: ReasoningEchoPolicy? = null
        if (!isMistral) {
            // [deep-audit-1047 A-01] Feed the CLAMPED value, not the raw request
            // value. `thinking_budget` is defined relative to `max_tokens`
            // (`budget < max_tokens`, a deterministic 400), and the field two
            // lines above writes `safeMaxTokens`. The resolver derived its
            // ceiling from the raw number instead, so an out-of-band caller
            // above the model ceiling (sub-agent budget, synthesized request —
            // exactly the callers the clamp defends) emitted e.g.
            // max_tokens=8192 with thinking_budget=32768. Same pair contract as
            // AnthropicProvider's `cappedMaxTokens`, which was fixed in 1046.
            echoPolicy = injectThinkingParams(body, thinkingLevel, safeMaxTokens)
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

        // [T-deepseek-v4-thinking-echo] The rule table's declared requirement wins over
        // the local-level gates above. `*deepseek-v4*` declares AFTER_TOOL_USE_ONLY
        // because the vendor (and any relay fronting its Anthropic-compatible endpoint)
        // REJECTS a tool-call turn that arrives without the thinking field, regardless
        // of what the client asked for locally — see ReasoningEchoDecider's header for
        // the 2026-09-18 field report and the empty-placeholder rationale.
        fun echoAction(msg: LLMMessage, hasToolCalls: Boolean): ReasoningEchoDecider.Action =
            ReasoningEchoDecider.decide(
                policy = echoPolicy,
                hasToolCalls = hasToolCalls,
                captured = msg.reasoningContent,
                legacyGate = echoReasoning,
                legacyPlaceholder = placeholderAllowed,
            )

        fun putEcho(obj: JSONObject, action: ReasoningEchoDecider.Action, captured: String?) {
            // [audit-0917 F-200] The echoed field's SPELLING comes from the matched
            // rule, not from this literal. `ReasoningEchoPolicy.fieldName` was
            // declared/persisted/encoded/decoded/resolved end-to-end but never
            // read, so a rule that asked for the `reasoning` spelling (GH
            // OpenMinis#171: the same gateway has served three spellings) still
            // got `reasoning_content` on the wire. Default is unchanged, so
            // every existing rule behaves exactly as before.
            val key = ReasoningEchoDecider.fieldNameFor(echoPolicy)
            when (action) {
                ReasoningEchoDecider.Action.OMIT -> {}
                ReasoningEchoDecider.Action.CAPTURED -> obj.put(key, captured ?: "")
                ReasoningEchoDecider.Action.PLACEHOLDER -> obj.put(key, "")
            }
        }

        val lastUserIndex = sanitizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in sanitizedMessages.withIndex()) {
            if (msg.contentParts.isNotEmpty()) {
                // Structured content parts
                when {
                    // Assistant with tool_use → emit assistant message with tool_calls
                    msg.role == LLMMessage.Role.ASSISTANT -> {
                        val obj = JSONObject()
                        obj.put("role", "assistant")
                        val toolUseParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
                        // Reasoning echo — see ReasoningEchoDecider. Round-trip exactly
                        // what the server emitted (including ""): DeepSeek V4 emits
                        // `reasoning_content: ""` on non-thinking turns and accepts the
                        // same shape on input, and the empty value is the field-presence
                        // guarantee that prevents 400s once thinking is on. When the
                        // matched rule DEMANDS the field on a tool-call turn we still send
                        // "" rather than a synthetic marker: prior placeholders
                        // ("[no prior reasoning]", T249; single space, T257) were
                        // in-context-learned by DeepSeek V4 and echoed back as the
                        // model's own reasoning.
                        putEcho(obj, echoAction(msg, toolUseParts.isNotEmpty()), msg.reasoningContent)
                        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
                        if (textParts.isNotEmpty()) {
                            obj.put("content", textParts.joinToString("") { it.text })
                        }
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
                        // [da1017-01 / ISSUE-01] Tool-result images (read_image,
                        // browser_use screenshots) used to be dropped here: a
                        // role:"tool" entry only accepts a plain string, and
                        // nothing else in this branch looked at
                        // ToolResult.imageData. Emit them in the user message
                        // right after the tool results — see
                        // OpenAIToolResultImages. Capture-only results
                        // (imageData == null) add no entry at all, so a
                        // text-only history serializes byte-identically to
                        // before this change.
                        val toolResultImages = toolResults.mapNotNull { tr ->
                            val bytes = tr.imageData
                            val mime = tr.imageMimeType
                            if (bytes != null && mime != null) {
                                OpenAIToolResultImages.Entry(
                                    caption = "[tool result image: ${capChatToolCallId(tr.id)}]",
                                    bytes = bytes,
                                    mimeType = mime,
                                )
                            } else {
                                null
                            }
                        }
                        if (toolResultImages.isNotEmpty()) {
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    OpenAIToolResultImages.chatUserContent(
                                        entries = toolResultImages,
                                        supportsImages = supportsImages,
                                        compress = { com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(it) },
                                        encodeBase64 = { Base64.encodeToString(it, Base64.NO_WRAP) },
                                    ),
                                )
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
                                                val safeBytes = com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(part.data)
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
                                                    put("text", VISION_UNSUPPORTED_PLACEHOLDER)
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

                if (msg.role == LLMMessage.Role.ASSISTANT) {
                    // Plain-text history rows can never carry tool_calls (a tool-call
                    // turn always has contentParts), so the turn shape is `false` and
                    // the rule's AFTER_TOOL_USE_ONLY branch falls back to the legacy
                    // decision — unchanged wire output for every non-tool turn.
                    putEcho(obj, echoAction(msg, hasToolCalls = false), msg.reasoningContent)
                }

                val attachTopLevelImages =
                    index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
                // [deep-audit-1047 A-03] Per-message images ride the SAME branch as
                // the request-level list and the message's own audio. The payload
                // predicate (`hasWirePayload`) already declares
                // `imageParts.isNotEmpty()` a reason to keep a turn — but until now
                // no serializer ever read `msg.imageParts`, so a text-less turn that
                // only the predicate let through went out as `content: ""` with the
                // bytes dropped. Mirrors the audio arm, which has always been read
                // from the message. Order (images → audio → text) is the order the
                // wire sees for the top-level arm.
                val legacyImages = (if (attachTopLevelImages) imageParts else emptyList()) + msg.imageParts
                val legacyImageCount = LegacyMediaPlan.imageCount(
                    attachRequestImages = attachTopLevelImages,
                    requestImageCount = imageParts.size,
                    messageImageCount = msg.imageParts.size,
                )
                if (legacyImageCount > 0 || LegacyMediaPlan.hasMedia(0, msg.audioParts.size)) {
                    val contentArray = JSONArray()
                    for (part in legacyImages) {
                        if (supportsImages) {
                            // T-imgsize: provider-boundary backstop.
                            val safeBytes = com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(part.data)
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
                                put("text", VISION_UNSUPPORTED_PLACEHOLDER)
                            })
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
                    // [deep-audit-1045 A-03] The `|| attachTopLevelImages` arm
                    // used to be reachable only for turns that had text (the
                    // empty filter dropped the rest), so it never emitted an
                    // empty text part. Now that a media-only last user turn
                    // survives the filter, the text part must be skipped when
                    // there is no text — a server that rejects an empty text
                    // block would 400 on a legitimate image-only request.
                    if (msg.content.isNotEmpty()) {
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

    private fun injectThinkingParams(
        body: JSONObject,
        level: ThinkingLevel,
        maxTokens: Int,
    ): ReasoningEchoPolicy? {
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
        // [T5-M4] AUTO means "let the vendor decide" — omit ALL thinking
        // control fields. The relay dialects below key off level.isEnabled, and
        // AUTO.isEnabled == true, so picking Auto silently forced thinking ON on
        // every listed host. The measured-gateway branch and the relay dialects
        // below predate AUTO and never followed the semantics; AUTO is handled
        // once, above both, by the single `level == AUTO` return.
        // [T-deepseek-v4-thinking-echo] Resolve the ctx (and the echo requirement the
        // rules declare for this model) BEFORE the relay-host table: the host branches
        // return early, and the vendor's echo demand is a property of the MODEL, not of
        // whichever dialect we end up speaking. Everything here is a pure read of the
        // model descriptor — no body mutation, so ordering against the table is free.
        val ctx = ThinkingResolveContext(
            modelId = model.id,
            instanceId = thinkingRuleInstanceId,
            supportsReasoning = model.supportsReasoning,
            // [T-android-effort-self-learn] A tier set the gateway told us about on a
            // previous 400 outranks the catalog's declaration — it is one-hand truth and
            // the only source that ever updates itself. With nothing learned the
            // expression is byte-identical to `model.reasoningEffortValues`, so a host we
            // have never heard a complaint from keeps today's behaviour exactly.
            declaredEffortValues = EffortTierLearner.learned(host, model.id)
                ?: model.reasoningEffortValues,
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
        val echo = ThinkingRuleResolver.echoPolicyFor(ctx)

        if (level == ThinkingLevel.AUTO) return echo

        // [T-measured-gateway-effort] Every gateway whose `reasoning_effort` enum we
        // measured by hand is dispatched HERE, off [GatewayEffortTruth.isMeasuredHost]
        // — the truth table, not a second copy of the host list. Two gateways are
        // measured today (sensenova: token.sensenova.cn / api.sensenova.cn;
        // senseaudio: api.senseaudio.cn), and the enums differ PER MODEL *and* per
        // gateway: `glm-5.2` takes `max` while `sensenova-6.8-flash-lite` rejects it,
        // and `deepseek-v4.1-flash` takes `medium` on sensenova but is 400'd for it on
        // senseaudio. Adding a third host is therefore a table edit, not an edit here.
        //
        // This branch used to be a `when (host)` arm listing sensenova's two hosts by
        // hand, which meant senseaudio — measured, tabled, and reachable from the
        // picker — silently fell through to the rule engine below: `medium` went out
        // unclamped (HTTP 400, "reasoning_effort must be low, high, xhigh, max, or an
        // integer within [1, 100]") and `xhigh`/`max` were clamped away by whatever
        // stale tier set the catalog had declared. A table nobody consults is not a
        // truth table.
        //
        // The clamp is applied PER TIER. Collapsing every enabled tier onto one value
        // (the behaviour that preceded it: unconditional "xhigh") avoided the 400 but
        // silently made LOW/MEDIUM/HIGH indistinguishable — the picker drew three
        // options that produced one request, which is the bug users reported.
        // clampEffort walks down then up, so a tier the model's enum lacks degrades to
        // the strongest tier below it instead of failing the request.
        if (GatewayEffortTruth.isMeasuredHost(host)) {
            if (model.supportsReasoning == false) return echo
            if (!level.isEnabled) {
                // Measured live: `reasoning_effort:"none"` is accepted and really
                // stops the reasoning stream; enable_thinking is honoured but does
                // NOT suppress reasoning_content on deepseek-v4-flash.
                body.put("reasoning_effort", "none")
                return echo
            }
            // ON: emit the tier the user asked for, clamped onto the gateway's
            // measured enum (see GatewayEffortTruth.resolveTier — measurement first,
            // then whatever the catalog declared, then that gateway's own fallback).
            body.put(
                "reasoning_effort",
                GatewayEffortTruth.resolveTier(host, model.id, level, model.reasoningEffortValues),
            )
            return echo
        }

        // Every host below is an UNMEASURED relay dialect: its wire shape comes from
        // live observation of one model, not from a probed effort enum.
        when (host) {
            "api.siliconflow.cn" -> {
                // SiliconFlow: enable_thinking is honored only by an allowlist.
                if (model.id in SILICONFLOW_THINKING_MODELS) {
                    body.put("enable_thinking", level.isEnabled)
                }
                return echo
            }

            "api.moonshot.cn" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                    // K2.6: thinking.keep defaults to null (drop history thinking);
                    // must be "all" for retention-style thinking when enabled.
                    if (level.isEnabled && lid.contains("k2.6")) put("keep", "all")
                })
                return echo
            }

            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                })
                return echo
            }

            "chat.intern-ai.org.cn" -> {
                body.put("thinking_mode", level.isEnabled)
                return echo
            }

            "open.bigmodel.cn" -> {
                body.put("thinking", JSONObject().apply {
                    put("type", if (level.isEnabled) "enabled" else "disabled")
                })
                return echo
            }

            "aiping.cn" -> {
                body.put("enable_thinking", level.isEnabled)
                return echo
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
        val trace = ThinkingRuleResolver.apply(body, ctx)
        // [T-thinking-rules-observability] Which rule actually won must be inspectable,
        // or a rule layer just replaces one hidden variable with a more complicated one.
        // [T-thinking-resolve-in-log] The INPUT side of the decision is logged first
        // (mirrors iOS `[resolve.in]`): when a relay 400s, the full decision input must
        // be reconstructable from logs alone — endpoint predicates, declared tiers, and
        // the off-tier in play — not just the winner. Otherwise a wrong rule fires for
        // reasons invisible after the fact.
        com.rikkaminis.app.logging.AppLogger.info(
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
        com.rikkaminis.app.logging.AppLogger.info(
            "Thinking",
            "[resolve] model=${model.id} level=${level.name} ${trace.logLine}",
        )
        // [T-deepseek-v4-thinking-echo] `echo` is the requirement the matched rule
        // declares for this model, computed above the host table so every early return
        // (AUTO, relay dialects) still carries it. Null = no opinion → the builder's
        // legacy gates decide, byte-identical to the pre-fix output.
        return echo
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
    /**
     * [FIX-1 / F-186] Read at most [limit] bytes from [body]; null means "over
     * the ceiling". Streams in chunks so a hostile/unbounded body is never
     * materialised in full.
     *
     * Deliberately NOT `source.readByteArray(limit)`: that helper reads
     * EXACTLY `limit` bytes and throws [java.io.EOFException] when the stream
     * is shorter — so using it here would have rejected every image smaller
     * than the ceiling, which is all of them.
     */
    private fun readBodyBounded(body: ResponseBody?, limit: Long): ByteArray? {
        if (body == null) return null
        val declared = body.contentLength()
        if (declared > limit) return null
        val out = java.io.ByteArrayOutputStream(if (declared > 0) declared.toInt() else 8 * 1024)
        body.byteStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > limit) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            // [FIX-1 / F-185] RIFF is a CONTAINER, not an image format: WAV and
            // AVI share the same 4-byte header. WebP is `RIFF????WEBP`, so the
            // `WEBP` fourcc at offset 8 is the discriminating bytes. Before this,
            // any non-image RIFF payload (a relay answering with
            // application/octet-stream was the entry point) was labelled
            // image/webp and handed to the model as an image.
            b.size >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
                b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50 -> "image/webp"
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
        // [FIX-1 / F-182] Same sampling knob the Chat-Completions builder takes.
        // The two branches of rawStreamMessage() are alternatives for the SAME
        // turn, so a user-set temperature must not depend on which API flavour
        // the instance is configured for.
        temperature: Double? = null,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        // [da1017-01 / ISSUE-01] Top-level image attachments of the current
        // turn. The Chat-Completions builder has taken this since T132; this
        // one never did, so every Responses-API request that carried images
        // (image-generation / image-edit via the `minis-model-use` CLI on a
        // Codex-OAuth instance) went out with the bytes silently missing —
        // the model only saw whatever caption the caller embedded in the
        // prompt. Defaulted for the same reason the parameter above is:
        // existing callers stay source-compatible.
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
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
        val sanitizedRaw = sanitizeToolPairing(messages) { detail ->
            android.util.Log.i("OpenAIProvider", detail)
        }
        // [deep-audit-1045 A-03] Same pre-filter index as the Chat-Completions
        // builder — see the note there; this file must not fork the decision.
        val rawLastUserIndex = sanitizedRaw.indexOfLast { it.role == LLMMessage.Role.USER }
        val sanitizedMessages = sanitizedRaw
            // [FIX-1 / F-211] Same filter as the legacy serializer path above —
            // see the note there for why this lives at the call site.
            // [deep-audit-1045 A-03] and for why "empty" excludes media-only
            // turns (audioParts / imageParts / top-level turn images).
            .filterIndexed { index, m ->
                m.hasWirePayload(
                    isLastUserTurn = index == rawLastUserIndex,
                    turnImageCount = imageParts.size,
                )
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
        // carry reasoning context ACROSS turns instead of re-deriving it.
        //
        // [FIX-1 / F-184] The sentence above used to stop mid-clause and no
        // code followed it: `put("include", …)` had ZERO occurrences in this file
        // and `isCodexOAuth` — the symbol the comment claimed to gate on — has
        // no definition anywhere in the repo (iOS is not vendored here). So the
        // field was never requested on any path, while the block above it
        // (prompt_cache_key) showed the path WAS being optimised deliberately.
        //
        // Android has no OAuth login flow of its own, so the iOS
        // `isCodexOAuth` predicate maps to the credential type: a Codex OAuth
        // instance is exactly `credentialType == oauth` on an OpenAI instance.
        // Same predicate ModelUseOffloadHandler:1048 already uses to exclude
        // oauth instances. apiKey instances (including third-party Responses
        // relays, which is what the 400 note above is about) stay untouched.
        if (instanceContext?.credentialType ==
            com.rikkaminis.app.data.model.ProviderCredential.oauth
        ) {
            body.put("include", JSONArray().put("reasoning.encrypted_content"))
        }
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

        // [FIX-1 / F-182] Responses API uses the SAME sampling field name and
        // range as Chat Completions, and the same "self-reasoning families
        // reject/ignore it" rule applies — so this mirrors buildRequestBody
        // (:1646) exactly rather than inventing a second policy. Before this,
        // the Responses branch of rawStreamMessage() silently dropped whatever
        // temperature the user configured, so `minis-model-use --temperature`
        // was a no-op on every useResponsesAPI / forceResponsesAPI instance.
        if (temperature != null && isModelAllowTemperature(model.id)) {
            body.put("temperature", clampOutboundTemperature(temperature))
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
        if (com.rikkaminis.app.data.FastModePrefs.isEnabled() &&
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
        // [da1017-01] Same anchor the Chat-Completions builder uses: the
        // top-level `imageParts` argument belongs to the CURRENT turn, which is
        // the last user message in the list.
        val lastUserIndex = sanitizedMessages.indexOfLast { it.role == LLMMessage.Role.USER }
        for ((index, msg) in sanitizedMessages.withIndex()) {
            // [deep-audit-1047 A-03] One flag for the whole legacy media path: the
            // request-level images (last user turn only — unchanged contract), the
            // message's own image parts, and its audio all share ONE branch. The
            // last two used to be mutually exclusive `else if` arms.
            val attachTopLevelImages =
                index == lastUserIndex && msg.role == LLMMessage.Role.USER && imageParts.isNotEmpty()
            val legacyMediaAttached =
                attachTopLevelImages || msg.imageParts.isNotEmpty() || msg.audioParts.isNotEmpty()
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
                        val toolResults = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
                        for (tr in toolResults) {
                            val (callId, _) = splitResponsesAPIIds(tr.id)
                            input.put(JSONObject().apply {
                                put("type", "function_call_output")
                                put("call_id", capResponsesId(callId))
                                put("output", tr.content)
                            })
                        }
                        // [da1017-01 / ISSUE-01] Tool-result images. Same defect
                        // as the Chat-Completions branch above and the same
                        // remedy: `function_call_output.output` is a plain
                        // string, so the bytes ride in the user message that
                        // follows — via the input_image part type this path
                        // already uses for composer attachments.
                        val toolResultImages = toolResults.mapNotNull { tr ->
                            val bytes = tr.imageData
                            val mime = tr.imageMimeType
                            if (bytes != null && mime != null) {
                                OpenAIToolResultImages.Entry(
                                    caption = "[tool result image: ${capResponsesId(splitResponsesAPIIds(tr.id).first)}]",
                                    bytes = bytes,
                                    mimeType = mime,
                                )
                            } else {
                                null
                            }
                        }
                        if (toolResultImages.isNotEmpty()) {
                            input.put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    OpenAIToolResultImages.responsesUserContent(
                                        entries = toolResultImages,
                                        supportsImages = supportsImages,
                                        compress = { com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(it) },
                                        encodeBase64 = { Base64.encodeToString(it, Base64.NO_WRAP) },
                                    ),
                                )
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
                                            val safeBytes = com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(part.data)
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
                                                put("text", VISION_UNSUPPORTED_PLACEHOLDER)
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
            } else if (legacyMediaAttached) {
                // [da1017-01 / ISSUE-01] Legacy (non-contentParts) message whose
                // images arrive in the top-level `imageParts` argument — the
                // `minis-model-use` CLI path, and the analogue of the
                // `attachTopLevelImages` branch in buildRequestBody. Before
                // this, the Responses builder had no image handling of any
                // kind: a Codex-OAuth instance asked to do image generation or
                // image editing sent the prompt text and none of the bytes.
                // Mirrors the Chat flavour: images first, then the message text
                // as an input_text part (an empty one is skipped, per GH#67's
                // note about servers that reject it).
                //
                // [GH#67] The SAME branch carries `msg.audioParts` — the
                // Responses API keeps the nested input_audio shape as Chat
                // Completions ({data, format}), unlike input_image which
                // flattens image_url to a string.
                //
                // [deep-audit-1047 A-03] These used to be two MUTUALLY EXCLUSIVE
                // `else if` branches: a turn carrying both request-level images
                // and message-level audio hit the image arm and every audio part
                // was dropped silently. Chat Completions has always treated the
                // two carriers as one list
                // (`if (attachTopLevelImages || msg.audioParts.isNotEmpty())`);
                // this is that shape — order images → audio → text included. The
                // image arm also reads `msg.imageParts` for the same reason the
                // Chat builder does (see hasWirePayload).
                val legacyImages =
                    (if (attachTopLevelImages) imageParts else emptyList()) + msg.imageParts
                val contentArray = JSONArray()
                val legacyImageCount = LegacyMediaPlan.imageCount(
                    attachRequestImages = attachTopLevelImages,
                    requestImageCount = imageParts.size,
                    messageImageCount = msg.imageParts.size,
                )
                if (legacyImageCount > 0 || LegacyMediaPlan.hasMedia(0, msg.audioParts.size)) {
                    val imageArray = OpenAIToolResultImages.responsesUserContent(
                        entries = legacyImages.map {
                            OpenAIToolResultImages.Entry(caption = "", bytes = it.data, mimeType = it.mimeType)
                        },
                        supportsImages = supportsImages,
                        compress = { com.rikkaminis.app.provider.ImageBudget.compressUnderBudget(it) },
                        encodeBase64 = { Base64.encodeToString(it, Base64.NO_WRAP) },
                    )
                    for (i in 0 until imageArray.length()) contentArray.put(imageArray.get(i))
                }
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
            // [fix/tool-schema-required-empty-array] Same rationale as
            // AgentToolDefinition.toOpenAIJson: strict validators (agentrouter,
            // 2026-09-22) 400 a missing `required` as `null is not of type
            // "array"`. Always present; empty list → [].
            put("required", JSONArray(required))
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

        val message = "[$statusCode] ${extractHttpErrorMessage(body)}"

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

// [refactor/split-provider] OkHttpNetTraceListener moved verbatim to
// OkHttpNetTraceListener.kt (private -> internal).

/**
 * [da1017-01 / ISSUE-01] Wire encoding for images that reach the request
 * through a **tool result** (`AgentContentPart.ToolResult.imageData` —
 * `read_image` and `browser_use` screenshots) on the two OpenAI flavours.
 *
 * Why a user message instead of the tool message: OpenAI's `role:"tool"`
 * entry (and the Responses API's `function_call_output`) carries a plain
 * string, so there is no field to hang bytes on. The images therefore ride in
 * the user message that immediately follows the tool results, carrying a
 * caption text part so the model can pair each image back to the tool call.
 *
 * Why this object exists at all: `grep -n imageData` over this file used to
 * return **0 hits** while both siblings had the branch already —
 * AnthropicProvider `:789` (image block inside `tool_result.content`) and
 * GeminiProvider `:431` (`inlineData` sibling part). Anthropic/Gemini can put
 * the bytes *inside* the tool-result block because their schemas allow it;
 * OpenAI cannot, which is the whole reason the shape differs. Same defect
 * family as the earlier "[FIX-1 / F-192] read_image returns ToolResult
 * imageData" fix — OpenAI was the third of three.
 *
 * The two platform edges are injected (`compress` =
 * `ImageBudget.compressUnderBudget`, `encodeBase64` = `android.util.Base64`)
 * so the wire shape — the part that the tests pin — stays JVM-testable; the
 * budget/b64 choices at the call sites are identical to the ones the
 * `ImageData` branches in both serializers already make.
 */
internal object OpenAIToolResultImages {
    /**
     * One image queued for the wire. [caption] is emitted as a text part
     * immediately before the image part (same "caption precedes its image"
     * ordering the composer uses for `[attached image: …]` parts); an empty
     * caption is skipped.
     */
    class Entry(val caption: String, val bytes: ByteArray, val mimeType: String)

    /** Chat Completions user content array (`text` + `image_url` object). */
    fun chatUserContent(
        entries: List<Entry>,
        supportsImages: Boolean,
        compress: (ByteArray) -> ByteArray,
        encodeBase64: (ByteArray) -> String,
    ): JSONArray = build(entries, supportsImages, compress, encodeBase64, responsesFlavor = false)

    /**
     * Responses API user content array (`input_text` + `input_image` with the
     * flattened `image_url` string) — see the T132 comment in the Responses
     * serializer below the builders.
     */
    fun responsesUserContent(
        entries: List<Entry>,
        supportsImages: Boolean,
        compress: (ByteArray) -> ByteArray,
        encodeBase64: (ByteArray) -> String,
    ): JSONArray = build(entries, supportsImages, compress, encodeBase64, responsesFlavor = true)

    private fun build(
        entries: List<Entry>,
        supportsImages: Boolean,
        compress: (ByteArray) -> ByteArray,
        encodeBase64: (ByteArray) -> String,
        responsesFlavor: Boolean,
    ): JSONArray {
        val textType = if (responsesFlavor) "input_text" else "text"
        val out = JSONArray()
        for (entry in entries) {
            if (entry.caption.isNotEmpty()) {
                out.put(JSONObject().apply {
                    put("type", textType)
                    put("text", entry.caption)
                })
            }
            if (supportsImages) {
                // T-imgsize: same belt-and-braces backstop the ImageData
                // branches run — history tool-result screenshots can be raw
                // 12-megapixel PNGs that never went through the composer's
                // budget pass, and we would 413 on replay.
                val safeBytes = compress(entry.bytes)
                val safeMime = if (safeBytes === entry.bytes) entry.mimeType else "image/jpeg"
                val dataUri = "data:$safeMime;base64,${encodeBase64(safeBytes)}"
                out.put(if (responsesFlavor) {
                    JSONObject().apply {
                        put("type", "input_image")
                        // Responses API takes image_url as a *string*, not the
                        // {"url":…} object shape Chat Completions uses.
                        put("image_url", dataUri)
                    }
                } else {
                    JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", dataUri) })
                    }
                })
            } else {
                // T264: target model has no vision modality — one literal
                // shared by every provider so they cannot drift apart.
                out.put(JSONObject().apply {
                    put("type", textType)
                    put("text", VISION_UNSUPPORTED_PLACEHOLDER)
                })
            }
        }
        return out
    }
}
