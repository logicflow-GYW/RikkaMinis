package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything about the endpoint and the model that a rule may look at. Passed explicitly
 * rather than read off the provider so the resolver stays a pure function — which is what
 * makes it testable without a network stub.
 */
data class ThinkingResolveContext(
    val modelId: String,
    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, or null. When
     * set, the resolver prepends this instance's user-authored custom rules (from
     * [ThinkingRuleResolver.customRulesFor]) ABOVE the built-in list. Null → built-ins
     * only, byte-identical to Phase 1.
     */
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    /**
     * [OpenMinis#163] The catalog affirmatively declares this model has NO effort
     * tiers (it reasons, but takes no `reasoning_effort`). Distinct from
     * `declaredEffortValues == null`, which also means "the catalog never heard
     * of it" — only the affirmative case suppresses the field. Defaults false so
     * every existing construction site keeps its current behaviour.
     *
     * Only consulted together with [isXAI] — see the skip in the reasoningEffort
     * branch.
     */
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    /**
     * Vendor predicates, resolved by the caller from the base URL. The resolver never
     * parses URLs itself — that keeps URL-sniffing in one place and lets Phase 2 replace
     * these with user-authored scopes without touching this file.
     */
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    /**
     * [OpenMinis#163] Endpoint is xAI's own API (api.x.ai), not a relay that
     * merely serves grok-named models. Scopes the empty-tier skip to the vendor
     * where the 400 was actually observed. Defaults false so existing
     * construction sites are unchanged.
     */
    val isXAI: Boolean = false,
    /**
     * [T-deepseek-v4-official-only] Endpoint is the official api.deepseek.com, not a
     * relay that merely serves deepseek-v4-named models. Only the official backend
     * understands the vendor-native `thinking:{}` object; a third-party relay
     * (e.g. tokenrhythm) rejects `thinking.reasoning_effort` with UNKNOWN_FIELD and
     * controls thinking via top-level `reasoning_effort` instead. Defaults false so
     * the relay-safe shape is chosen for unknown endpoints.
     */
    val isOfficialDeepSeek: Boolean = false,
    /**
     * The vendor's documented off tier, or null to omit the field when thinking is off.
     * Already an ALLOWLIST decision made by the caller (iOS ff60c818).
     */
    val offEffort: String?,
)

/**
 * Why a particular wire shape was chosen. Design §8 / GH OpenMinis#100: the resolved
 * outcome must be inspectable, otherwise a user-editable rule layer just replaces one
 * hidden variable with a more complicated one.
 */
data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingRule.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    /** One-line form for `AppLogger("Thinking")`. */
    val logLine: String
        get() = buildList {
            add("rule=$matchedRuleLabel")
            add("kind=$matchedRuleKind")
            add("src=$formatSource")
            if (clampedFrom != null && clampedTo != null && clampedFrom != clampedTo) {
                add("clamp=$clampedFrom->$clampedTo")
            }
            add("keys=[${emittedKeys.sorted().joinToString(",")}]")
        }.joinToString(" ")
}

/**
 * Data-driven replacement for the thinking-parameter if-return chain.
 * Mirrors iOS `ThinkingRuleResolver.swift`.
 *
 * PHASE 1 SCOPE — read before extending:
 *  • Covers the OpenAI-compatible family only. Gemini and Anthropic keep their own
 *    emitters; their formats are declared in [ThinkingWireFormat] so the vocabulary is
 *    complete, but nothing resolves to them here. Wiring them in is Phase 2.
 *  • Built-in rules only. No persistence, no user rules, no UI. [ThinkingRule.Kind.CUSTOM]
 *    and [ThinkingWireFormat.CustomPath] exist so Phase 2 need not change these types.
 *  • Behaviour must stay byte-for-byte identical to the pre-refactor chain. That is not an
 *    aspiration — ThinkingWireGoldenSnapshotTest was generated against the old
 *    implementation and committed before this file existed (fdc28e2b).
 *
 * EVALUATION MODEL (design §4), two stages:
 *  Stage A — walk the rules top to bottom, first scope match wins, stop. Ordering is
 *            priority. The PROVIDER_TYPE_DEFAULT at the bottom has AllModels scope so a
 *            match is guaranteed and stage A can never fall through.
 *  Stage B — a matched rule that leaves wireFormat null defers to the fallback chain.
 *            Kept separate from stage A on purpose: cross-rule field merging would make
 *            "why did this value come from there" unanswerable in a trace.
 */
object ThinkingRuleResolver {

    /**
     * [T-android-thinking-rules-phase2] Process-wide cache of user-authored custom
     * rules, keyed by provider-instance id, each list in stored (priority) order.
     * Mirrors iOS `ThinkingRuleCache`: [apply] is a sync call reached from the
     * provider's request builder, but rules live in Room (async), so the repository
     * publishes them here on load and on every mutation. A cache miss yields an empty
     * list ⇒ built-in-only behaviour, never a wrong shape.
     */
    @Volatile
    private var customRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    /** Replace the whole cache (called once after the repository loads config). */
    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingRule>>) {
        customRulesCache = byInstance
    }

    /** Replace one instance's custom rules (called after an add/edit/delete/reorder). */
    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingRule>) {
        customRulesCache = customRulesCache.toMutableMap().apply {
            if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
        }
    }

    /**
     * [T-worker-thinking-rules-restore] Restore one instance's custom rules from the
     * serialized request-JSON field (`thinking_rules`) written by the dispatcher.
     *
     * The worker process (`:modelservice`) never touches ProviderRepository — its only
     * inputs are the request file and EncryptedSharedPreferences — so the cache is empty
     * there and every custom rule was silently ignored on the offloaded chat path
     * (observed 2026-09-06: a user-authored sensenova clamp resolved to the BUILT-IN
     * `deepseek-v4-relay` inside `:modelservice` while the rule preview in the main
     * process resolved to the custom rule). This is the same cross-process sync class
     * as the knobs fields: write side (main) and read side (worker) must both carry it.
     *
     * Returns the restored rule count for the request-run log.
     */
    @Synchronized
    fun restoreCustomRulesFromJson(instanceId: String, field: JSONArray?): Int {
        if (instanceId.isBlank() || field == null) return 0
        val rules = (0 until field.length()).mapNotNull { i ->
            val o = field.optJSONObject(i) ?: return@mapNotNull null
            ThinkingRuleCoding.decodeRuleJson(o)
        }
        setCustomRules(instanceId, rules)
        return rules.size
    }

    /** This instance's custom rules in priority order, or empty. */
    fun customRulesFor(instanceId: String?): List<ThinkingRule> =
        instanceId?.let { customRulesCache[it] } ?: emptyList()

    /**
     * The vendor rules, in priority order — deliberately not alphabetical. The most
     * specific predicate must be consulted first, and Mistral leads because its rule is a
     * total prohibition that outranks every shape below it.
     */
    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingRule> = buildList {
        // Mistral — GH OpenMinis#87 / iOS 4592ca9b / 29065ca0. Total prohibition: the
        // request rejects `reasoning` (422 extra_forbidden) and AssistantMessage is a
        // closed schema that rejects `reasoning_content`. Must outrank everything.
        if (ctx.isMistral) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "mistral-official",
                ),
            )
        }

        // OpenRouter — nested `reasoning:{effort}`, OMIT when off so forced-reasoning
        // backends don't reject `effort:"none"`.
        if (ctx.isOpenRouter) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffortNested(offValue = null),
                    label = "openrouter",
                ),
            )
        }

        // ORDER IS LOAD-BEARING FROM HERE DOWN. It reproduces the pre-refactor `when`
        // chain's evaluation order exactly, which was:
        //     o*/gpt-5* → qwen||isDashScope → self-reasoning skip → generic fallback
        // with `usesUnifiedReasoningEffort` consulted only INSIDE the deepseek-v4 and
        // self-reasoning branches — never by the OpenAI-native or qwen branches.
        //
        // [T-thinking-rules-phase1] The first version of this registry hoisted the
        // unified-gateway rule ABOVE these, which silently changed two real cases: a
        // gpt-5 id on DashScope started emitting `enable_thinking`+`thinking_budget`
        // instead of `reasoning_effort`, and (on iOS, mirrored) a qwen id on
        // Ark/Azure/Venice flipped the other way. That is a user-visible
        // silent-degradation regression of exactly the kind this design exists to
        // prevent. The gateway rule must sit BELOW these two, not above.
        //
        // The golden snapshot did not catch it because every matrix row varied a single
        // dimension; the cross-product rows (qwen×unified, gpt5×dashscope, mimo×unified)
        // were added alongside this fix.

        // OpenAI native o-series / GPT-5.x — root reasoning_effort. Android's original
        // predicate was `startsWith("o") || startsWith("gpt-5")`; the broad "o" prefix is
        // preserved verbatim rather than narrowed to o1/o3/o4, because narrowing it would
        // change behaviour for any id starting with "o" and this phase must not.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("o*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("gpt-5*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )

        // Qwen / DashScope — dual-send + strict budget inequality (25165700, a5a0de20).
        // The old branch was `lid.contains("qwen") || isDashScope` with NO unified guard,
        // so both the id match and the endpoint match keep their native enable_thinking
        // mechanism even when the endpoint is Ark/Azure/Venice.
        //
        // [T-qwen-thinking-private-fields-host-gated] SPLIT BY ENDPOINT: `thinking_budget`
        // + `extra_body` are Bailian/DashScope PRIVATE — a standard OpenAI-compatible relay
        // (e.g. tokenrhythm.studio) 400s on them with UNKNOWN_FIELD. So the `*qwen*` model
        // pattern alone must NOT imply the DashScope envelope: a qwen-named model served by
        // a relay gets the portable `enable_thinking`-only shape (QwenRootOnly), while the
        // DashScope endpoint itself keeps the full dual-send (QwenDual). The gate lives at
        // REGISTRATION, never inside the emitter, because the shape is user-selectable in
        // the rule editor and rewriting it at emit time would contradict an explicit choice.
        if (ctx.isDashScope) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.QwenDual,
                    label = "qwen-dashscope",
                ),
            )
        }
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*qwen*"),
                wireFormat = ThinkingWireFormat.QwenRootOnly,
                label = "qwen-root-only",
            ),
        )

        // Unified gateways (Volcengine Ark / Azure / Venice) — iOS ba055121 + 84f5c9e1.
        // These re-host third-party families behind one OpenAI surface where thinking is
        // controlled ONLY by root `reasoning_effort`; the vendor-native `thinking:{}`
        // object is not honoured, and on Venice an unknown root key is a hard 400.
        // Registered as ONE concept rather than three flags so they cannot drift apart.
        //
        // Placed AFTER the OpenAI-native and qwen patterns so it claims exactly what the
        // old chain's `usesUnifiedReasoningEffort` checks claimed — the deepseek-v4
        // branch and the self-reasoning families below — and nothing more.
        if (ctx.usesUnifiedReasoningEffort) {
            add(
                ThinkingRule(
                    kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    label = "unified-gateway(ark|azure|venice)",
                ),
            )
        }

        // DeepSeek V4 — only when NOT on a unified gateway (that rule above already
        // claimed those). RikkaMinis splits by ENDPOINT (T-deepseek-v4-official-only):
        //   • official api.deepseek.com → vendor-native `thinking:{type, reasoning_effort}`
        //     sibling shape (DeepSeekSibling);
        //   • any other base (third-party relay, e.g. tokenrhythm) → standard top-level
        //     `reasoning_effort`, because the relay rejects the vendor-internal
        //     `thinking.reasoning_effort` with UNKNOWN_FIELD.
        // The OFF shape is identical either way: explicit `thinking:{type:"disabled"}`,
        // because deepseek-v4 thinks BY DEFAULT and omission would silently leave it on.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.OFFICIAL_VENDOR,
                scope = ThinkingRule.Scope.ModelPattern("*deepseek-v4*"),
                wireFormat = if (ctx.isOfficialDeepSeek) ThinkingWireFormat.DeepSeekSibling
                             else ThinkingWireFormat.DeepSeekRelay,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = if (ctx.isOfficialDeepSeek) "deepseek-v4-official" else "deepseek-v4-relay",
            ),
        )

        // Fallback for the providerType: generic root reasoning_effort, subject to the
        // self-reasoning skip in stage B. AllModels guarantees stage A always matches.
        add(
            ThinkingRule(
                kind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                scope = ThinkingRule.Scope.AllModels,
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-compatible-default",
            ),
        )
    }

    /**
     * Resolve and apply the thinking parameters for one request. The body is mutated in
     * place so the call site stays identical to the function this replaced; the trace is
     * returned for logging.
     */
    fun apply(body: JSONObject, ctx: ThinkingResolveContext): ThinkingResolveTrace {
        val before = body.keys().asSequence().toSet()

        // ---- AUTO: let the vendor decide ----
        // [T-thinking-auto-level] AUTO expresses no effort opinion, so the
        // resolver must NOT emit any thinking control — the model's own default
        // applies (reasoning models reason, non-reasoning models do not).
        // Mirrors RikkaHub's AUTO semantics on OpenAI-official/unified-gateway
        // endpoints and Gemini. Custom rules do NOT override AUTO: an explicit
        // tier is a deliberate intensity choice, AUTO is the absence of one.
        if (ctx.level == ThinkingLevel.AUTO) {
            return ThinkingResolveTrace(
                matchedRuleLabel = "auto",
                matchedRuleKind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "auto-omit",
                emittedKeys = emptyList(),
            )
        }

        // ---- Stage A: first-match-wins ----
        // [T-android-thinking-rules-phase2] User-authored custom rules (stored order)
        // are prepended above the built-ins, so a custom rule can override a vendor
        // default by matching first — but never remove a built-in. An empty custom
        // list makes `rules` == `builtInRules(ctx)`, byte-identical to Phase 1.
        val rules = customRulesFor(ctx.instanceId) + builtInRules(ctx)
        val winner = rules.firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingRule.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        // ---- Stage B: fill in what the rule left unspecified ----
        var formatSource = "rule"
        val format = winner.wireFormat ?: run {
            formatSource = "providerTypeDefault"
            ThinkingWireFormat.ReasoningEffort(ctx.offEffort)
        }

        val clamp = emit(format, ctx, body)

        val emitted = body.keys().asSequence().toSet() - before
        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = formatSource,
            emittedKeys = emitted.toList(),
            clampedFrom = clamp.first,
            clampedTo = clamp.second,
        )
    }

    /**
     * Write the fields for one wire format. Each branch reproduces the corresponding
     * branch of the pre-refactor chain exactly — including its guards, which are the part
     * that carries the field evidence.
     */
    private fun emit(
        format: ThinkingWireFormat,
        ctx: ThinkingResolveContext,
        body: JSONObject,
    ): Pair<String?, String?> {
        val lid = ctx.modelId.lowercase()

        // [T-thinking-off-explicit] Strict-enum families never receive an off tier:
        // sending "minimal" to MiMo/Agnes killed the whole request (iOS c5efeb1e).
        val strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
        val offEffort = if (strictEffortEnum) null else ctx.offEffort

        return when (format) {
            is ThinkingWireFormat.OmitEverything -> null to null

            is ThinkingWireFormat.ReasoningEffortNested -> {
                // OMIT when off so forced-reasoning backends don't reject
                // `effort:"none"`. MiMo/Agnes xhigh→high clamp preserved from
                // the pre-refactor OpenRouter branch — it emitted
                // `clampEffortForModel(...)` (main 7aea092d), and those
                // backends validate reasoning_effort against a STRICT
                // low/medium/high enum (iOS c5efeb1e). OpenRouter serves
                // xiaomi/mimo-v2.5, so the raw tier really does reach such a
                // backend. No declared-set clamp (the pre-refactor branch had
                // none).
                if (!ctx.level.isEnabled) return null to null
                val effort = clampEffortForModel(wireEffort(ctx.level), lid)
                body.put("reasoning", JSONObject().put("effort", effort))
                effort to effort
            }

            is ThinkingWireFormat.ReasoningEffort -> {
                val isOpenAINative = lid.startsWith("o") || lid.startsWith("gpt-5")
                val isSelfReasoningFamily = listOf("deepseek", "glm", "kimi", "minimax")
                    .any { lid.contains(it) }
                if (!ctx.level.isEnabled) {
                    // OFF — reproduced from RikkaMinis' pre-refactor chain, with the
                    // upstream declared-set refinements absorbed (22647505). They only
                    // change behaviour when offEffort != null, i.e. on the
                    // explicitOffEffort allowlist bases (official OpenAI / Volcano Ark).
                    // [T-thinking-off-explicit] explicitOffEffort() is the allowlist;
                    // null means "omit the field" (vendor default).
                    if (offEffort == null) return null to null
                    // MiMo/Agnes are exempt as defense in depth (iOS ff60c818 +
                    // c5efeb1e): their backends validate reasoning_effort against a
                    // STRICT low/medium/high enum and reject the whole request on
                    // "none"/"minimal". Preserved from the pre-refactor chain, which
                    // returned right after the allowlist gate for these ids.
                    if (lid.contains("mimo") || lid.contains("agnes")) return null to null
                    when {
                        isOpenAINative -> {
                            body.put("reasoning_effort", offEffort)
                            return offEffort to offEffort
                        }
                        isSelfReasoningFamily -> {
                            // Native self-reasoning families: only the unified-effort
                            // gateways (Ark/Azure) understand an off tier for them —
                            // OR a declared set that explicitly names it (upstream
                            // 22647505, the inverse of the silent-skip bug).
                            if (ctx.usesUnifiedReasoningEffort ||
                                ctx.declaredEffortValues?.contains(offEffort) == true
                            ) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        ctx.supportsReasoning != false -> {
                            // A declared set that does NOT contain the off tier blocks
                            // emission — the backend validates the enum strictly
                            // (upstream 22647505).
                            if (ctx.declaredEffortValues?.contains(offEffort) != false) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        else -> return null to null
                    }
                }
                if (isOpenAINative) {
                    // OpenAI-native o/gpt-5 — root reasoning_effort, NOT clamped onto the
                    // declared set (pre-refactor chain never clamped this branch).
                    val effort = clampEffortForModel(wireEffort(ctx.level), lid)
                    body.put("reasoning_effort", effort)
                    return effort to effort
                }
                // Generic path. The self-reasoning skip is keyed on "declares nothing"
                // rather than family name — upstream 22647505 replaced the id-substring
                // skip-list after GLM behind a relay silently received no thinking field
                // at all. ABSORBED (not a gratuitous divergence): it changes ONLY the
                // buggy cases — a model WITH a declared effort set now gets its tier on
                // the wire; an undeclared family id keeps the legacy skip, byte-identical
                // to the pre-refactor chain.
                val declaresEffort = !ctx.declaredEffortValues.isNullOrEmpty()
                if (!ctx.usesUnifiedReasoningEffort && !declaresEffort &&
                    isSelfReasoningFamily
                ) {
                    return null to null
                }
                // [OpenMinis#163] xAI-scoped skip: grok-build-0.1 answers reasoning_effort
                // with a hard 400 ("Model grok-build-0.1 does not support parameter
                // reasoningEffort"); the catalog describes exactly that state as
                // reasoning_options present but empty. Scoped to the official xAI endpoint
                // only — the same empty-tier shape appears on thousands of relay-hosted
                // catalog entries where the skip is unverified, so it must not fire there.
                if (ctx.isXAI && !ctx.usesUnifiedReasoningEffort &&
                    ctx.declaresNoEffortTiers && !declaresEffort
                ) {
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                // Generic fallback — standard reasoning_effort, MiMo/Agnes xhigh→high
                // clamp via clampEffortForModel, THEN clamped onto the declared set
                // (upstream 22647505: asking for a tier the model never declared must
                // not reach the wire).
                val requested = clampEffortForModel(wireEffort(ctx.level), lid)
                val clamped = clampEffort(requested, ctx.declaredEffortValues)
                body.put("reasoning_effort", clamped)
                requested to clamped
            }

            is ThinkingWireFormat.DeepSeekSibling -> {
                // [T-deepseek-v4-official-sibling] Absorbed upstream 847822eb: the tier
                // must travel as a ROOT SIBLING of `thinking` — nesting it inside the
                // thinking object made it an unknown nested key with no root tier, so
                // every request silently ran at the vendor default (3-month silent bug,
                // caught by the negative assertion in the regression suite). The value
                // mapping is the data-driven one (wireEffort + clamp onto the declared
                // set, upstream parity): a catalog-declared ["high","max"] honours
                // "high" as a distinct tier instead of collapsing everything above
                // medium onto "max" like the pre-refactor relay ladder does.
                if (ctx.level.isEnabled) {
                    val requested = wireEffort(ctx.level)
                    val clamped = clampEffort(requested, ctx.declaredEffortValues)
                    body.put("thinking", JSONObject().put("type", "enabled"))
                    body.put("reasoning_effort", clamped)
                    requested to clamped
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.DeepSeekRelay -> {
                // [T-deepseek-v4-official-only] ON is the relay-safe shape: standard
                // top-level `reasoning_effort` with the deepseek-v4 ladder, because the
                // relay rejects the vendor-internal `thinking.reasoning_effort` field.
                // OFF stays byte-identical to the pre-refactor chain — explicit
                // `thinking:{type:"disabled"}` on EVERY non-unified base (deepseek-v4
                // thinks BY DEFAULT; omission silently leaves it on). The old chain sent
                // that shape to relays too, and it is accepted there (it is the standard
                // DeepSeek API toggle, unlike the nested effort field).
                if (ctx.level.isEnabled) {
                    val effort = deepSeekV4Effort(ctx.level)
                    body.put("reasoning_effort", effort)
                    effort to effort
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.QwenDual -> {
                // [T-qwen-thinking-off-omission] Qwen/DashScope models think BY DEFAULT,
                // so OFF must emit an explicit `enable_thinking: false` — omission lets the
                // vendor default kick in and the model silently enters its reasoning phase
                // (the reported "using a model and it suddenly freezes" bug). RikkaMinis
                // DIVERGES from upstream Android here: upstream emitted NOTHING at OFF
                // (pure-refactor fidelity to its own pre-refactor chain), but RikkaMinis'
                // pre-refactor chain already carried the explicit-disable fix, so the
                // rule-table port preserves it.
                if (!ctx.level.isEnabled) {
                    body.put("enable_thinking", false)
                    return null to null
                }

                val enabled = true
                var budget = when (ctx.level) {
                    ThinkingLevel.LOW -> 4096
                    ThinkingLevel.MEDIUM -> 16384
                    ThinkingLevel.HIGH -> 32768
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 65536
                    ThinkingLevel.OFF -> 0
                    // [T-thinking-auto-level] unreachable — AUTO returns at the
                    // top of apply(); kept for exhaustiveness.
                    ThinkingLevel.AUTO -> 0
                }
                if (budget > 0 && ctx.maxTokens > 0) {
                    // [T-android-qwen3-thinking-budget-max-tokens-constraint] absorbed
                    // upstream (a5a0de20): maxTokens<2 leaves no room for any positive
                    // budget strictly below max, so DROP the field rather than emit an
                    // invalid value.
                    if (ctx.maxTokens < 2) {
                        budget = 0
                    } else {
                        val margin = maxOf(2048, ctx.maxTokens / 8)
                        val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                        if (budget >= ceiling) budget = ceiling
                    }
                }
                body.put("enable_thinking", enabled)
                if (budget > 0) body.put("thinking_budget", budget)
                body.put(
                    "extra_body",
                    JSONObject().apply {
                        put("enable_thinking", enabled)
                        // ANDROID-SPECIFIC: OMIT the key when there is no budget, rather
                        // than sending an explicit JSON null as iOS does. Preserved
                        // verbatim from the pre-refactor chain; changing it would alter
                        // the request for the pathological maxTokens<2 case.
                        if (budget > 0) put("thinking_budget", budget)
                    },
                )
                null to null
            }

            is ThinkingWireFormat.QwenRootOnly -> {
                // [T-qwen-thinking-private-fields-host-gated] Qwen on a NON-DashScope relay.
                // OFF → portable explicit `enable_thinking: false` (Qwen thinks by default).
                // ON → standard top-level `reasoning_effort`: `thinking_budget`/`extra_body`
                // are Bailian-private and 400 on a strict OpenAI-compatible relay. Verified
                // live against tokenrhythm.studio qwen3.8-max (RikkaMinis 7aea092d).
                if (!ctx.level.isEnabled) {
                    body.put("enable_thinking", false)
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                // Local pre-refactor chain emitted the raw tier (clampEffortForModel
                // only) — no declared-set clamp on this path.
                val requested = clampEffortForModel(wireEffort(ctx.level), lid)
                body.put("reasoning_effort", requested)
                requested to requested
            }

            is ThinkingWireFormat.BooleanToggle -> {
                // Plain on/off switch with no tiers, written at a dotted path so
                // `thinking` and `extra.thinking` are the same code path. OFF still writes
                // `false` rather than omitting: a vendor whose switch we are explicitly
                // modelling defaults to ON when the key is absent (the DeepSeek V4 / Qwen3
                // lesson). Mirrors iOS booleanToggle.
                setValueAtPath(body, format.path, ctx.level.isEnabled)
                null to null
            }

            is ThinkingWireFormat.ExtraBodyToggle -> {
                // Same shape, conventionally nested under extra_body (GH OpenMinis#171:
                // DeepSeek's real switch is extra_body.thinking.enabled and Minis never
                // sent it). Mirrors iOS extraBodyToggle.
                setValueAtPath(body, format.path, ctx.level.isEnabled)
                null to null
            }

            is ThinkingWireFormat.CustomPath -> {
                // The escape hatch (design §5.1). Deliberately limited to "write this
                // value at this dotted path" — no JSONPath, no templates — so every rule
                // stays statically checkable and explainable in a trace. OFF writes
                // offValue when configured; ON writes the per-tier value, falling back to
                // the HIGH tier's value when the exact level has no entry. Mirrors iOS
                // customPath (resolver.swift:632) — upstream Android declared this case but
                // never emitted it, which made the editor's escape hatch crash at request
                // time; RikkaMinis completes the implementation.
                if (ctx.level.isEnabled) {
                    val v = format.values[ctx.level] ?: format.values[ThinkingLevel.HIGH]
                        ?: return null to null
                    setValueAtPath(body, format.path, v)
                    return v to v
                }
                val off = format.offValue ?: return null to null
                setValueAtPath(body, format.path, off)
                off to off
            }

            else -> {
                // Phase 1: declared for vocabulary completeness, never resolved to on this
                // path. Reaching here means the registry named a format the OpenAI emitter
                // cannot produce — a programmer error, not a runtime condition.
                error("ThinkingWireFormat $format is not emitted on the OpenAI path in Phase 1")
            }
        }
    }

    /**
     * Write [value] at a dotted path ("thinking", "extra.thinking.enabled") inside
     * [body], creating intermediate JSONObjects as needed. The escape-hatch write
     * primitive (design §5.1): no JSONPath, no templates — just "this value at this
     * path". Mirrors iOS `inserting(_:parts:into:)`.
     */
    private fun setValueAtPath(body: JSONObject, path: String, value: Any) {
        val parts = path.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        var cursor = body
        for (i in 0 until parts.size - 1) {
            var next = cursor.optJSONObject(parts[i])
            if (next == null) {
                next = JSONObject()
                cursor.put(parts[i], next)
            }
            cursor = next
        }
        val leaf = parts.last()
        when (value) {
            is String -> cursor.put(leaf, value)
            is Boolean -> cursor.put(leaf, value)
            is Int -> cursor.put(leaf, value)
            is Long -> cursor.put(leaf, value)
            is Double -> cursor.put(leaf, value)
            else -> cursor.put(leaf, value.toString())
        }
    }

    /**
     * [T-android-thinking-level-arch] DeepSeek V4's vendor effort ladder: every
     * high-and-above tier collapses onto "max" (V4 tops out there), everything else
     * lands on "high". Distinct from the generic [wireEffort] mapping. NOT clamped onto
     * the declared set — the pre-refactor chain never clamped deepseek-v4's effort.
     */
    fun deepSeekV4Effort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
        else -> "high"
    }

    // ---- Gemini / Anthropic (Phase 2 §1) ----
    //
    // Gemini does not share the OpenAI body shape — it writes into
    // `generationConfig.thinkingConfig`. This function owns the per-family rules so
    // every vendor's thinking contract is described in ONE place (mirrors iOS
    // ThinkingRuleResolver.geminiThinkingConfig). Emission still happens in
    // GeminiProvider (the generationConfig envelope), which delegates here.

    /**
     * [T-gemini37-minimal-400] Gemini 3.x Flash models that reject
     * `thinkingLevel: "minimal"` with a 400 and must use "low" as their OFF floor.
     *
     * Matched by minor version rather than an exact-id list: 3.7 is where Google
     * dropped the level, so anything from 3.7 up is assumed to have dropped it
     * too. Guessing "low" for a model that would have accepted "minimal" costs a
     * slightly higher thinking floor; guessing "minimal" for one that rejects it
     * makes the model unusable outright. The asymmetry decides the default.
     */
    private fun rejectsMinimalLevel(lowerId: String): Boolean {
        val m = Regex("""gemini-3\.(\d+)""").find(lowerId) ?: return false
        val minor = m.groupValues[1].toIntOrNull() ?: return false
        return minor >= 7
    }

    /**
     * The `generationConfig.thinkingConfig` object for a Gemini request, or null when the
     * model takes no thinking config at all (specialized -tts/-image/-embedding/-vision
     * modalities, 2.5 Flash Lite, and any id matching none of the families).
     */
    fun geminiThinkingConfig(modelId: String, level: ThinkingLevel): JSONObject? {
        // [T-gemini-tts-thinking-400 / OpenMinis#226] Specialized modalities take
        // precedence over EVERY family rule and over the requested level: these models
        // reject the thinking parameter outright, so sending one is a hard 400
        // ("Thinking level is not supported for this model.").
        //
        // Checked FIRST because these ids also match a family pattern —
        // `gemini-3.1-flash-tts-preview` contains "gemini-3", so any later placement is
        // shadowed. Android previously had no such test at all, so all three Gemini TTS
        // models were unusable; iOS had one but below the family branches, equally dead.
        val lowerId = modelId.lowercase()
        val noThinkingSuffixes = listOf("-tts", "-image", "-embedding", "-vision")
        if (noThinkingSuffixes.any { lowerId.endsWith(it) || lowerId.contains("$it-") }) {
            return null
        }

        val isGemini3 = modelId.contains("gemini-3")
        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null

        return when {
            isGemini3 -> JSONObject().apply {
                if (level == ThinkingLevel.OFF) {
                    // 3.x cannot fully disable thinking; the floor is the weakest
                    // level the model will accept.
                    //
                    // [T-gemini37-minimal-400] "minimal" is NOT universal across the
                    // 3.x Flash family. Verified on-device: gemini-3-flash-preview /
                    // 3.5-flash / 3.6-flash accept it, but gemini-3.7-flash returns a
                    // hard 400 "Thinking level MINIMAL is not supported for this model."
                    // on EVERY request. So with thinking OFF, 3.7 Flash was completely
                    // unusable, not merely un-thinking. "low" is accepted by the whole
                    // family and is the same floor 3.x Pro already used, so fall back to
                    // it for the models that reject minimal rather than probing at
                    // runtime.
                    val acceptsMinimal = modelId.contains("flash") && !rejectsMinimalLevel(lowerId)
                    put("thinkingLevel", if (acceptsMinimal) "minimal" else "low")
                } else {
                    put(
                        "thinkingLevel",
                        when (level) {
                            ThinkingLevel.LOW -> "low"
                            ThinkingLevel.MEDIUM -> "medium"
                            // MAX/ULTRA were appended to ThinkingLevel after this
                            // branch was written (for GPT-5.6) and fell through the
                            // old `else -> "low"`, silently sending the WEAKEST level
                            // when the user asked for the strongest. Gemini's ladder
                            // tops out at "high", so every tier at or above HIGH maps
                            // there.
                            ThinkingLevel.HIGH, ThinkingLevel.XHIGH,
                            ThinkingLevel.MAX, ThinkingLevel.ULTRA,
                            -> "high"
                            ThinkingLevel.OFF -> "low" // unreachable; OFF handled above
                            ThinkingLevel.AUTO -> "low" // unreachable; AUTO handled above
                        },
                    )
                    put("includeThoughts", true)
                }
            }
            is25Pro -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 128 // minimum; 0 is rejected (df8a823d)
                        ThinkingLevel.LOW -> 2048
                        ThinkingLevel.MEDIUM -> 8192
                        ThinkingLevel.HIGH -> 16384
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
                        ThinkingLevel.AUTO -> 0 // unreachable; AUTO handled above
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            is25Flash -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 0
                        ThinkingLevel.LOW -> 1024
                        ThinkingLevel.MEDIUM -> 4096
                        ThinkingLevel.HIGH -> 8192
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
                        ThinkingLevel.AUTO -> 0 // unreachable; AUTO handled above
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            else -> null
        }
    }

    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // ULTRA is a client-side "Max + orchestration" concept and is NEVER a valid
        // server effort string (iOS b38bf3d5).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
        ThinkingLevel.AUTO -> "low" // unreachable; AUTO returns at the top of apply()
    }

    /**
     * [T-android-xhigh-effort-clamp] MiMo/Agnes reject xhigh (400/422); their ladder tops
     * out at high. Matches the FAMILY substring, not one spelling — the live API serves
     * `mimo-v2.5` while docs say `mimo-2.5` (iOS 72968c4f).
     */
    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    /** Snap a requested tier onto the model's declared set, walking DOWN then up. */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}
