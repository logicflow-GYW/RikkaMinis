package com.openminis.app.sandbox.offload

/**
 * First-chunk timeout policy — pure JVM decision logic extracted from the
 * hard-coded 30s guard in [ModelExecutionService.FIRST_CHUNK_TIMEOUT_MS].
 *
 * Background (2026-08-24, diag/first-chunk-timeout):
 *   The 30s first-chunk guard is CORRECT for direct public endpoints (official
 *   model APIs) but TOO TIGHT for proxy/gateway-type providers: the local
 *   proxy (127.x clash/v2ray) handshake + upstream queueing + SSE first row
 *   routinely approaches 20-60s. A 30s cap on those produces a high-frequency
 *   "provider produced no first chunk" that users experience as failures.
 *
 *   The timeout itself is a safety net (it stops a wedged upstream from
 *   hanging a live worker past the client death grace) — the failure mode was
 *   NOT the guard, it was the CLIENT classifying the resulting
 *   [ModelStreamErrorException] as fatal instead of transient (see
 *   ChatViewModel.workerDiedZeroChunk asymmetry fix). But a policy that can
 *   scale the budget per provider route reduces the false-positive count
 *   without weakening the guard's intent.
 *
 * Rules:
 *   - Official/direct routes get the conservative 30s (STREAM_TTFB_TIMEOUT_MS
 *     in OpenAIProvider is also 30s, so the outer guard stays aligned).
 *   - Proxy/gateway routes (custom base URL that is NOT an official provider
 *     host, or a loopback/proxy-like host) get 45s — matching the inner
 *     STREAM_FIRST_DATA_TIMEOUT_MS so the outer guard does not cut the inner
 *     watchdog short.
 *   - Deterministic, no Android dependencies → JVM-testable. The decision is
 *     purely string-based (customBaseURL) so the pure function can be tested
 *     without dragging in the provider model layer.
 *
 * Generation-vs-route budgets (2026-08-26, fix/long-generation-timeouts):
 *   A **model generation stream** (an assistant turn that produces text /
 *   tool calls over SSE) is treated fundamentally differently from a short
 *   request-response probe. Long generations legitimately sit silent before
 *   the first chunk for reasons UNRELATED to reasoning/thinking:
 *
 *     - assembling a large structured deliverable (a construction plan, a
 *       spec, a long file) where the model plans internally before emitting
 *       the first token;
 *     - a late-turn call in a long multi-turn task where the accumulated
 *       context has grown large and the provider needs >45s to produce its
 *       first delta (context-size-dependent, not thinking-dependent);
 *     - a proxy/gateway that queues upstream before the first SSE row.
 *
 *   Thinking was historically used as the only "this may be legitimately
 *   silent" signal (8-25). That conflated a *user-facing feature flag* with a
 *   *resource-production budget* and left every non-thinking long generation
 *   exposed to the 45s/6-minute walls. The 8-25 Codex evidence (2:50–3:10 of
 *   dead air between reasoning and text deltas with NO keep-alive bytes)
 *   proves provider silence is not a reliable dead-signal anywhere on the
 *   generation path.
 *
 *   Therefore: **generation streams always use the generous 30-minute
 *   backstop**, thinking OR not. The per-route 30/45s budgets remain ONLY as
 *   the budget for non-generation calls (none in production today besides
 *   this stream path) and as a documented fast-hint. Genuine dead-upstream
 *   protection is NOT removed: the provider's TTFB watchdog (30s, no
 *   response *headers*) and the client's worker-liveness beat + death-grace
 *   still surface a genuinely wedged upstream promptly with a *real* signal.
 *   The 30-min ceiling is the final backstop that bounds the worst case so a
 *   worker is never held forever.
 */
object FirstChunkTimeoutPolicy {

    /** Default budget for direct public endpoints (seconds).
     *  [feat/runtime-limits-panel] runtime truth is
     *  AgentRuntimeLimitsPrefs.firstChunkDirectSec() (default 30 == this);
     *  no production caller today (route-aware split is deprecated). */
    const val DIRECT_TIMEOUT_SEC = 30

    /** Budget for proxy/gateway routes (seconds).
     *  [feat/runtime-limits-panel] runtime truth is
     *  AgentRuntimeLimitsPrefs.firstChunkProxySec() (default 45 == this);
     *  no production caller today (route-aware split is deprecated). */
    const val PROXY_TIMEOUT_SEC = 45

    /**
     * Budget for a long-running model generation before its first chunk and
     * its total stream duration (seconds). Generation models can sit silent
     * for many minutes before the first visible chunk for reasons that are NOT
     * reasoning-specific: assembling a large deliverable, a large-context
     * late-turn call, or proxy queueing. Codex Responses observed 2:50–3:10 of
     * dead air between the reasoning marker and the text-delta burst, and users
     * report 10–20 min extreme full-generation cases.
     *
     * This is the ABSOLUTE ceiling for the first-chunk phase AND the total
     * stream; it applies to ALL generation streams, reasoning or not. Chosen
     * generously (30 min) to cover long generation while still bounding a truly
     * wedged upstream so a worker is never held forever. Worker liveness during
     * the wait is separately proven by the liveness.beat heartbeat
     * (ModelExecutionRunDir), and a genuinely dead upstream (no response
     * headers, or headers-but-no-body) is surfaced earlier by the provider's
     * TTFB / first-data watchdogs — so this ceiling is a final backstop, not
     * the primary liveness signal.
     */
    const val GENERATION_TIMEOUT_SEC = 30 * 60
    // [feat/runtime-limits-panel] This const stays as the DOCUMENTED DEFAULT
    // (30 min). The runtime truth is prefs-backed — see decideGenerationTimeoutSec.

    /**
     * Historical alias kept for callers that referenced the thinking-scoped
     * constant. The value is identical to [GENERATION_TIMEOUT_SEC]; the
     * semantic is now "any long generation, thinking or not". Prefer
     * [GENERATION_TIMEOUT_SEC] in new code.
     */
    @Deprecated("Renamed and de-scoped: applies to ALL generation streams, not just thinking — use GENERATION_TIMEOUT_SEC", level = DeprecationLevel.WARNING)
    const val THINKING_TIMEOUT_SEC = GENERATION_TIMEOUT_SEC

    /** Hosts that are "official" direct endpoints (no proxy detour). */
    private val OFFICIAL_HOST_SUFFIXES = listOf(
        "anthropic.com", "googleapis.com", "openai.com", "openrouter.ai",
        "x.ai", "kimi.com", "api.deepseek.com", "moonshot.cn",
        // Additional official/public endpoints (2026-08-24): these otherwise
        // fall into the proxy bucket and get an unnecessarily loose 45s.
        "azure.com",          // Azure OpenAI: <resource>.openai.azure.com
        "aliyuncs.com",       // DashScope / Qwen
        "groq.com",           // Groq
        "minimax.io",         // MiniMax
        "xiaomimimo.com",     // Xiaomi MiMo
    )

    /**
     * Decide whether a route is proxy/gateway-like, purely from the base URL.
     * A custom base URL is the discriminator: most proxy routes mount
     * providers behind a local relay (127.x / 192.168.x / 10.x / a gateway
     * domain or IP), whereas direct routes either use the provider's built-in
     * endpoint (customBaseURL == null) or an official host.
     */
    fun isProxyRoute(customBaseURL: String?): Boolean {
        val base = customBaseURL?.trim()?.trimEnd('/')?.lowercase() ?: return false
        // Loopback / private ranges are unambiguous proxy/gateway indicators.
        // 172.16.0.0–172.31.255.255 is the RFC1918 private block — match the
        // exact second-octet range (16..31) rather than a loose "172." prefix,
        // which would sweep the public 172.32.x–172.255.x space in as well
        // (only loosens the budget, never produces a false failure, but the
        // RFC-exact range is the intended scope; keep this comment so nobody
        // "simplifies" it back to 172.).
        val private172 = (16..31).any { base.startsWith("172.$it.") }
        if (base.startsWith("127.") || base.startsWith("localhost") ||
            base.startsWith("10.") || base.startsWith("192.168.") ||
            private172 || base.startsWith("0.0.0.0")
        ) return true
        // Non-https plain-http endpoints are overwhelmingly local relays.
        if (!base.startsWith("https://")) return true
        // Known official hosts stay "direct" even when overridden.
        val host = base.removePrefix("https://").substringBefore('/')
        return OFFICIAL_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }
    }

    /**
     * The first-chunk / total-stream budget in seconds for a **model
     * generation stream** (an assistant SSE response). Applies the generous
     * 30-minute backstop regardless of route or thinking flag: long
     * generations legitimately stay silent for minutes before the first chunk,
     * and that silence is not a reliable dead-signal (see KDoc).
     *
     * Non-generation callers (none in production today) with a hard real-time
     * requirement should use [decideTimeoutSec] instead.
     */
    fun decideGenerationTimeoutSec(@Suppress("UNUSED_PARAMETER") customBaseURL: String?): Int =
        // [feat/runtime-limits-panel] 生成硬墙改读 prefs 真值（默认 30 min）。
        // OkHttp readTimeout（Anthropic/Gemini/OpenAI 三 provider 的
        // GENERATION_TIMEOUT_SEC 引用）每请求构建 client 时读取，改动对新请求
        // 生效；worker 客户端硬墙（ChatStreamOffloadHandler）同此语义。
        com.openminis.app.data.AgentRuntimeLimitsPrefs.generationTimeoutMinutes() * 60

    /**
     * Decide a strict route-aware first-chunk budget for a call that is NOT a
     * long generation (rare; today unused in the stream path). Reasoning has
     * been folded into the universal generation budget — see
     * [decideGenerationTimeoutSec]. Retained for backward compatibility; the
     * producer path should prefer [decideGenerationTimeoutSec].
     *
     * @deprecated Prefer [decideGenerationTimeoutSec] for any streamed model
     *   request. The route split here (30/45s) historically hard-killed
     *   healthy long generations and must not be applied to generation flows.
     */
    @Deprecated("Use decideGenerationTimeoutSec for generation streams", level = DeprecationLevel.WARNING)
    fun decideTimeoutSec(customBaseURL: String?, thinkingEnabled: Boolean = false): Int =
        if (thinkingEnabled) decideGenerationTimeoutSec(customBaseURL)
        else if (isProxyRoute(customBaseURL)) liveProxyTimeoutSec() else liveDirectTimeoutSec()

    /** [feat/runtime-limits-panel] Live route-aware budgets (defaults 30s/45s). */
    fun liveProxyTimeoutSec(): Int =
        com.openminis.app.data.AgentRuntimeLimitsPrefs.firstChunkProxySec()

    /** [feat/runtime-limits-panel] Live route-aware budgets (defaults 30s/45s). */
    fun liveDirectTimeoutSec(): Int =
        com.openminis.app.data.AgentRuntimeLimitsPrefs.firstChunkDirectSec()
}
