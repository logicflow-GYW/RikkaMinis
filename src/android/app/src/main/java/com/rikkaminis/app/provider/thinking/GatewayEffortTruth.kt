package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel

/**
 * [T-sensenova-effort-enum] Gateway-measured `reasoning_effort` truth tables.
 *
 * WHY THIS EXISTS
 * ---------------
 * Both the thinking-level picker ceiling and the wire-side clamp are derived from the
 * model's declared effort tiers, which for every catalogued model come from
 * `models.dev`. Measured live on **2026-09-23**, the `models.dev` `sensenova` provider
 * row is wrong for **every** model on that gateway — it under-declares tiers for all of
 * them (e.g. `sensenova-6.8-flash-lite`: declared `none|low|medium|high`, actual
 * `none|low|medium|high|xhigh`; `glm-5.2`: declared `none|high`, actual seven tiers).
 * The consequence was user-visible: the picker stopped at HIGH and the relay host table
 * then collapsed every enabled tier onto one wire value, so the three tiers it DID show
 * were indistinguishable on the wire.
 *
 * HOW IT WAS MEASURED (reproducible)
 * ----------------------------------
 * The gateway validates the field strictly and answers a 400 that ENUMERATES the legal
 * set, so one deliberately-invalid value yields the full enum for a model:
 *
 * ```
 * POST https://token.sensenova.cn/v1/chat/completions
 * {"model": "<id>", "reasoning_effort": "zzz_invalid_probe", ...}
 * -> 400 field ReasoningEffort invalid, should be one of: low, medium, high, xhigh, none
 * ```
 *
 * Cross-checked by sending each legal value in turn (streaming AND non-streaming, both
 * agree): accepted values answer 200, `minimal`/`max`/`ultra` answer 400 where absent
 * from the model's enum. Note this contradicts SenseNova's own published docs, which
 * claim `max` is a native tier for `sensenova-6.8-flash-lite` — the live gateway rejects
 * it. **The measurement wins over the documentation.**
 *
 * ENUM OBSERVED PER MODEL (2026-09-23)
 * ------------------------------------
 * | model                     | legal reasoning_effort values                        |
 * |---------------------------|------------------------------------------------------|
 * | sensenova-6.8-flash-lite  | none, low, medium, high, xhigh                       |
 * | glm-5.2                   | none, minimal, low, medium, high, xhigh, max         |
 * | kimi-k3                   | none, minimal, low, medium, high, xhigh, max         |
 * | deepseek-v4-flash         | none, low, medium, high, xhigh                       |
 * | deepseek-v4-pro           | none, minimal, low, medium, high, xhigh, max, ultra  |
 * | deepseek-v4.1-flash       | none, minimal, low, medium, high, xhigh, max, ultra  |
 * | deepseek-flash            | none, minimal, low, medium, high, xhigh, max, ultra  |
 *
 * The set is per MODEL, not per host — the same gateway accepts `max` on `glm-5.2` and
 * rejects it on `sensenova-6.8-flash-lite`. That is why the relay host table can no
 * longer emit one hard-coded value for the whole host.
 *
 * `none` doubles as the OFF tier (`reasoning_effort:"none"` really stops the reasoning
 * stream). `minimal` / `ultra` have no client-side [ThinkingLevel] counterpart, so they
 * are carried here only for the clamp ladder's benefit.
 *
 * Upgrade trigger: a `400 field ReasoningEffort invalid` from this gateway for a value
 * listed below, or a new model id appearing on the gateway — re-measure and extend the
 * table. The single-invalid-probe trick above takes one request per model.
 *
 * [T-senseaudio-effort-enum] The [SENSEAUDIO_TIERS] table below covers a second
 * gateway (api.senseaudio.cn, measured 2026-09-26) with the SAME mechanism but a
 * DIFFERENT ladder for the same model id — including a tier Sensenova accepts and
 * this gateway 400s on. Do not merge the two tables. The reported symptom there:
 * the picker offered LOW/MEDIUM/HIGH while the wire 400'd on `medium` and the
 * `xhigh`/`max` tiers the gateway actually serves had no UI entry — the declared
 * ceiling came from a models.dev row that neither matches this endpoint.
 */
object GatewayEffortTruth {

    /** Hosts whose OpenAI-compatible dialect this table describes. */
    private val SENSENOVA_HOSTS = setOf("token.sensenova.cn", "api.sensenova.cn")

    /**
     * [T-senseaudio-effort-enum] Second measured gateway (2026-09-26). Unlike
     * Sensenova, this relay REJECTS `medium` outright on its deepseek-v4.1-flash —
     * its enum is coarser but has higher tiers (`xhigh`/`max`), so the two tables
     * must never be merged: the same model id means different ladders on the two
     * gateways. Only the host we actually probed is listed; other hosts on the
     * same product are unknown until measured.
     */
    private val SENSEAUDIO_HOSTS = setOf("api.senseaudio.cn")

    /** Measured legal `reasoning_effort` values, per model id. Ladder-ordered. */
    private val SENSENOVA_TIERS: Map<String, List<String>> = mapOf(
        "sensenova-6.8-flash-lite" to listOf("none", "low", "medium", "high", "xhigh"),
        "glm-5.2" to listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
        "kimi-k3" to listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
        "deepseek-v4-flash" to listOf("none", "low", "medium", "high", "xhigh"),
        "deepseek-v4-pro" to listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
        "deepseek-v4.1-flash" to listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
        "deepseek-flash" to listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
    )

    /**
     * Conservative fallback for a model this gateway serves that we have NOT measured.
     * Every observed model accepts at least these, so the wire never carries a value the
     * gateway is known to reject. Deliberately excludes `max` (six of the seven measured
     * models reject it) and excludes `minimal`/`ultra` (no higher tier to lose by
     * leaving them out).
     */
    val SENSENOVA_FALLBACK_TIERS = listOf("none", "low", "medium", "high", "xhigh")

    /**
     * [T-senseaudio-effort-enum] Measured live 2026-09-23-style probe on
     * api.senseaudio.cn (each value sent in turn, non-streaming):
     * `low`/`high`/`xhigh`/`max` → 200; `none` → 200 and really stops the reasoning
     * stream; `minimal` → 200 (accepted, mapped upstream); **`medium` → 400** —
     * `"reasoning_effort must be low, high, xhigh, max, or an integer within [1, 100]"`.
     * The gateway also honours integer 1-100 efforts via chat_template_kwargs, which
     * have no client-side tier counterpart. Note the contrast with Sensenova's
     * `deepseek-v4.1-flash` row above, which DOES take `medium` — same model id,
     * different gateway, different ladder.
     */
    private val SENSEAUDIO_TIERS: Map<String, List<String>> = mapOf(
        "deepseek-v4.1-flash" to listOf("none", "low", "high", "xhigh", "max"),
    )

    /**
     * [T-senseaudio-effort-enum] Last resort for a model THIS gateway serves that we
     * have not probed. [SENSENOVA_FALLBACK_TIERS] cannot stand in: it carries
     * `medium`, and this relay 400s `medium` outright. One last-resort set per
     * gateway family, never one table for both — the same rule [SENSEAUDIO_TIERS]
     * already follows against [SENSENOVA_TIERS].
     *
     * `minimal` is omitted for the same reason it is omitted below (no client-side
     * [ThinkingLevel] maps to it). `max` is KEPT, unlike the sensenova fallback: the
     * gateway's own 400 text names it as legal ("must be low, high, xhigh, max, or
     * an integer within [1, 100]") and the probe accepted it, whereas six of the
     * seven measured sensenova models reject it. That text describes the gateway,
     * not one model, so it is the strongest evidence available for models we have
     * not probed here.
     */
    val SENSEAUDIO_FALLBACK_TIERS = listOf("none", "low", "high", "xhigh", "max")

    fun isSensenovaHost(host: String): Boolean = host in SENSENOVA_HOSTS

    /** True when [host] has a measured truth table. Two consumers, one question:
     *  the model-sync path replaces the models.dev declared tiers with it
     *  ([ModelsDevApi.applyGatewayEffortTruth]) and the request path routes the host
     *  through [resolveTier] instead of a hand-written `when (host)` arm
     *  (`OpenAIProvider.injectThinkingParams`) — a table that only one of them
     *  consults is a table the wire never sees. */
    fun isMeasuredHost(host: String): Boolean =
        isSensenovaHost(host) || host in SENSEAUDIO_HOSTS

    /**
     * Measured tiers for [modelId] when [host] is a gateway this object describes,
     * or null when we have no measurement (caller should fall back to the declared set).
     */
    fun tiersFor(host: String, modelId: String): List<String>? = when {
        isSensenovaHost(host) -> SENSENOVA_TIERS[modelId]
        host in SENSEAUDIO_HOSTS -> SENSEAUDIO_TIERS[modelId]
        else -> null
    }

    /**
     * The last-resort set for [host] — chosen by gateway FAMILY, never one global
     * constant: the two measured gateways disagree about `medium` on the same model
     * id, so a single shared fallback would hand one of them a value it 400s.
     *
     * Only reachable for hosts this object describes (its production caller sits
     * behind [isMeasuredHost]); anything else keeps the original sensenova set.
     */
    private fun fallbackTiersFor(host: String): List<String> =
        if (host in SENSEAUDIO_HOSTS) SENSEAUDIO_FALLBACK_TIERS else SENSENOVA_FALLBACK_TIERS

    /**
     * The wire value for an ENABLED [level] on a gateway whose strict enum this object
     * describes. Precedence:
     *
     *  1. [EffortTierLearner.learned] — the gateway said it out loud on a previous 400.
     *     One-hand truth, freshest, and the only source that ever updates itself.
     *  2. [tiersFor] — the static measurement table (what we probed by hand).
     *  3. [declaredTiers] — the catalog's opinion, possibly under-declared.
     *  4. [fallbackTiersFor] — the conservative set for THIS gateway, never a value
     *     it is known to reject.
     *
     * Extracted from the provider's host-table branch so the decision is testable: the
     * branch runs behind a base-URL host match, which a MockWebServer-backed test cannot
     * produce. The provider now only supplies the host, the model id and the level.
     *
     * Degrades DOWNWARD (clampEffort walks to the strongest tier at or below the request,
     * then to the weakest) so asking for a tier the enum lacks never fails the request.
     *
     * [host] selects the measured table (and is therefore still the bare host — the
     * table is keyed by host), [scope] selects WHAT THE LEARNER READS: it is the
     * endpoint the request goes to (`scheme://host[:port]/path`), so an instance on a
     * different port or path prefix of the same host cannot inherit another's entry
     * ([fix/effort-key-1119]). It defaults to [host] so a host-only caller keeps the
     * legacy key shape.
     */
    fun resolveTier(
        host: String,
        modelId: String,
        level: ThinkingLevel,
        declaredTiers: List<String>?,
        scope: String = host,
    ): String {
        val tiers = EffortTierLearner.learned(scope, modelId)
            ?: tiersFor(host, modelId)
            ?: declaredTiers?.takeIf { it.isNotEmpty() }
            ?: fallbackTiersFor(host)
        return ThinkingEffortLadder.clampEffort(ThinkingEffortLadder.wireEffort(level), tiers)
    }
}
