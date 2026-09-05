package com.openminis.app.data.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProviderType(val displayName: String) {
    anthropic("Anthropic"),
    gemini("Google Gemini"),
    openAI("OpenAI"),
    openRouter("OpenRouter"),
    xAI("xAI (Grok)"),
    // [T-kimi-oauth] Kimi Code (Coding Plan) — RFC 8628 device-code OAuth,
    // OpenAI-compatible upstream at api.kimi.com/coding/v1. DB round-trip is
    // name-based (ProviderCredential.valueOf), so appending is migration-safe.
    kimiCode("Kimi Code");

    val builtInModels: List<LLMModel>
        get() = when (this) {
            anthropic -> LLMModel.allAnthropic
            gemini -> LLMModel.allGemini
            openAI -> LLMModel.allOpenAI
            openRouter -> LLMModel.allOpenRouter
            xAI -> LLMModel.allXAI
            kimiCode -> LLMModel.allKimi
        }
}

@Serializable
enum class ProviderCredential {
    apiKey,
    oauth,
}

@Serializable
enum class ThinkingLevel {
    // [T-android-thinking-level-arch] New cases MUST be appended at the end.
    // Kotlinx Serialization encodes enums by NAME string ("OFF"/"LOW"/...),
    // not declaration-order ordinal, so appending does not corrupt already-
    // persisted data. GPT-5.6 sol/terra reach ULTRA, luna reaches MAX.
    OFF, LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA,

    /**
     * [T-thinking-auto-level] "Let the vendor decide" — RikkaHub's AUTO(-1).
     * Appended last so no existing rank/ordinal shifts. Semantics:
     *  • Enabled (isEnabled == true), but expresses NO effort opinion.
     *  • OpenAI-family emitters (chat/completions + Responses) omit ALL
     *    thinking control fields for AUTO; Anthropic sends only the bare
     *    `thinking:{type:"adaptive"}` switch for adaptive-generation models
     *    (vendor picks the budget); Gemini omits thinkingConfig entirely.
     *    Mirrors RikkaHub's AUTO branch.
     *  • NOT subject to the model ceiling clamp (rank is an artifact of the
     *    append rule, not an intensity), and NOT offered as a per-model
     *    selection — the picker shows it as the "default" choice only.
     */
    AUTO;

    val isEnabled: Boolean get() = this != OFF

    val displayName: String
        get() = when (this) {
            OFF -> "Off"
            LOW -> "Low"
            MEDIUM -> "Medium"
            HIGH -> "High"
            XHIGH -> "XHigh"   // was "Max"; the label now belongs to the new MAX case
            MAX -> "Max"
            ULTRA -> "Ultra"
            AUTO -> "Auto"
        }

    /** Intensity ordinal used for intersection / clamp comparisons —
     *  follows the enum declaration order. */
    val rank: Int get() = ordinal

    companion object {
        /**
         * [T-android-thinking-level-arch] Safe deserialization fallback for a
         * level string that this app build doesn't recognize — e.g. an older
         * build reading a token a NEWER build persisted (Room DB / JSON mirror).
         * Mirrors the `runCatching { … }.getOrNull()` guard already used for
         * ImageEndpointMode (ProviderConfigMapping.kt). NEVER throws: an unknown
         * value clamps to the highest level THIS build knows (XHIGH) rather than
         * letting the caller handle an exception or drop the whole config. Use
         * this for every "read from persisted data" path.
         */
        fun decoded(raw: String): ThinkingLevel =
            runCatching { valueOf(raw) }.getOrElse { XHIGH }
    }
}

@Serializable
enum class RoutingStrategy {
    fallback,
    loadBalance,

    /** [T-recovery] Pick the cheapest usable member first (by [ModelEntry.costTier],
     *  ascending; unannotated entries sort last), falling back to the next
     *  cheapest on failure. Same fallback chain semantics as [fallback]. */
    cheapestFirst,
}

/**
 * [T-android-image-endpoint-mode] How an OpenAI-compatible provider routes
 * image-generation requests. Mirrors iOS `ImageEndpointMode`
 * (ProviderInstance.swift). Wire values (`images_generations` /
 * `chat_completions`) match iOS so JSON export/import interops cross-platform.
 *
 * - [auto]: try `/v1/images/generations` first; on a route-missing 4xx fall
 *   back to `/v1/chat/completions` and cache the working endpoint in
 *   `imageEndpointResolved` so the next call skips the probe.
 * - [imagesGenerations]: always `/v1/images/generations`.
 * - [chatCompletions]: always `/v1/chat/completions` (multimodal output) —
 *   i.e. the normal chat path, no special handling.
 */
@Serializable
enum class ImageEndpointMode {
    auto,
    @SerialName("images_generations") imagesGenerations,
    @SerialName("chat_completions") chatCompletions,
}

/**
 * Controls when fallback to the next model in the group is triggered.
 * - default: only on rate limiting (429) or server errors (5xx)
 * - always: on any error, including network errors, auth failures, etc.
 */
@Serializable
enum class FallbackStrategy {
    default,
    always,
}

/**
 * Controls what happens after a fallback succeeds: does the session stay
 * on the fallback member, or try to return to the preferred member?
 * - continueLast: stay on the fallback member permanently (default, existing
 *   behaviour — the fallback binding is persisted so re-entering the session
 *   starts from the fallback member).
 * - honorFirst: every re-resolution starts from memberEntryIds[0] (the
 *   preferred member). Rate-limited members get a temporary in-memory
 *   cooldown (60 s default) and are skipped until it expires; the fallback
 *   binding is NOT persisted so the next agent loop naturally returns to the
 *   preferred member once its rate-limit window cools down.
 */
@Serializable
@Stable
data class ModelGroup(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val memberEntryIds: MutableList<String> = mutableListOf(),
    var strategy: RoutingStrategy = RoutingStrategy.fallback,
    var fallbackStrategy: FallbackStrategy = FallbackStrategy.default,
    // T312: per-group session defaults. Mirrors iOS ModelGroup fields.
    // null = "no default override" — sessions bound to this group keep
    // OFF / unlimited unless the user opts in. Pre-T312 persisted JSON
    // simply lacks both keys; kotlinx.serialization fills them with the
    // declared defaults so older configs round-trip cleanly.
    var defaultThinkingLevel: ThinkingLevel? = null,
    var contextLimitTokens: Int? = null,
    // T-ctxslider 54ab8e93: persisted memory of the last user-selected context
    // limit, so toggling the "Limit Context Window" switch OFF→leave→ON
    // restores the previous value instead of snapping back to 128K. Default
    // null lets old JSON deserialize cleanly (kotlinx.serialization).
    var lastContextLimitTokens: Int? = null,
)

/**
 * Default context-window limit for a newly created model group. Keeps
 * user-created groups from silently growing to the full (often 1M+ token)
 * model window before any context-pressure signal appears — matching the
 * behaviour of the default group. Users can raise/clear it per group in the
 * group detail screen.
 */
const val DEFAULT_GROUP_CONTEXT_LIMIT_TOKENS: Int = 128_000

@Serializable
@Stable
data class ProviderInstance(
    val id: String,
    var label: String,
    val providerType: ProviderType,
    val credentialType: ProviderCredential,
    var isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var customBaseURL: String? = null,
    var appendV1Suffix: Boolean = true,
    // [T-provider-custom-user-agent] Optional per-provider User-Agent
    // override. Some relay gateways only accept requests whose UA looks like
    // an official client (e.g. Claude Code); Minis' default UA gets rejected.
    // null/blank → keep the default UA; non-blank → replace the User-Agent
    // header on every outbound request (chat / models / responses) for this
    // instance. Only surfaced in the UI for custom-base OpenAI-/Anthropic-
    // compat providers. Field name matches iOS for cross-platform
    // export/import interop.
    var customUserAgent: String? = null,
    // OpenAI-only: when true, traffic goes through /v1/responses instead of
    // /v1/chat/completions. Mirrors iOS `ProviderType.openAIResponses` flag,
    // but modeled here as a switch on the instance so an existing OpenAI
    // provider can be re-pointed without changing its type.
    var useResponsesAPI: Boolean = false,
    // [T-android-image-endpoint-mode] User-selected image-generation routing
    // for OpenAI-compatible providers (see ImageEndpointMode). Defaults keep
    // old persisted JSON (which lacks both keys) round-tripping cleanly:
    // kotlinx.serialization fills them with these declared defaults.
    var imageEndpointMode: ImageEndpointMode = ImageEndpointMode.auto,
    // Cached probe result for `auto` mode: the endpoint that last worked, so
    // subsequent calls skip the /images/generations probe. Cleared whenever
    // the user forces a mode. null = not yet probed.
    var imageEndpointResolved: ImageEndpointMode? = null,
    // [T-android-azure-openai] Azure OpenAI mode. When true, OpenAIProvider
    // auths with the `api-key:` header (not `Authorization: Bearer`) and treats
    // the custom base URL as an Azure endpoint (the user pastes the full Azure
    // URL including `?api-version=…`; the request routes as
    // {endpoint}/openai/deployments/{model}/{path}). Orthogonal to the
    // Chat/Responses API-format choice — both work under Azure. Defaults false
    // so existing OpenAI instances are completely unaffected. Field name
    // matches iOS for cross-platform export/import interop.
    var azureMode: Boolean = false,
    // [P0-pinned-providers] User "favorite" flag. Pinned instances are
    // rendered in a dedicated "常用/Favorites" section at the very top of
    // the provider list, separate from their providerType group, so the
    // providers the user reaches for most are always one tap away. Multiple
    // instances may be pinned at once. Boolean default false == old
    // persisted JSON stays valid (coerceInputValues covers missing field).
    var pinned: Boolean = false,
) {
    /** Returns the effective API base URL, applying v1 suffix if configured. */
    val effectiveBaseURL: String?
        get() {
            val base = customBaseURL?.trimEnd('/') ?: return null
            return if (appendV1Suffix && !base.endsWith("/v1")) "$base/v1" else base
        }

    /**
     * [T-android-image-endpoint-mode] Whether the "Image Generation" endpoint
     * picker is surfaced for this instance. Mirrors iOS
     * `supportsImageEndpointSetting`. Android has no `openAIResponses` provider
     * type — an OpenAI instance carries the `useResponsesAPI` flag instead, so
     * the gate is just the three OpenAI-compatible types.
     */
    val supportsImageEndpointSetting: Boolean
        get() = providerType == ProviderType.openAI ||
            providerType == ProviderType.openRouter ||
            providerType == ProviderType.xAI

    /**
     * [T-android-azure-openai] Whether the Azure toggle applies to this
     * instance — only OpenAI instances using an API key (Azure auths with an
     * api-key header). Covers both Chat-Completions and Responses formats since
     * Android models Responses as the `useResponsesAPI` flag on an openAI
     * instance rather than a separate provider type. Mirrors iOS
     * supportsAzureMode.
     */
    val supportsAzureMode: Boolean
        get() = providerType == ProviderType.openAI && credentialType == ProviderCredential.apiKey

    /**
     * [T-android-thinking-rules-phase2] Whether the custom thinking-rules editor
     * is surfaced for this instance. Only the OpenAI-compatible request path
     * consults [com.openminis.app.provider.thinking.ThinkingRuleResolver]:
     *   • anthropic/gemini build their thinking shape in their own emitters —
     *     user rules would be silently ignored, so show a notice, not an editor;
     *   • the Responses API and Codex OAuth both bypass the Chat-Completions
     *     body where rules apply — same notice treatment.
     * Mirrors iOS `supportsCustomThinkingRules`.
     */
    val supportsCustomThinkingRules: Boolean
        get() = when (providerType) {
            ProviderType.anthropic, ProviderType.gemini -> false
            else -> {
                val codexOAuth = credentialType == ProviderCredential.oauth &&
                    customBaseURL.isNullOrBlank()
                !useResponsesAPI && !codexOAuth
            }
        }
}

@Serializable
@Stable
data class ModelOverrides(
    val displayName: String? = null,
    val maxOutputTokens: Int? = null,
    // Override the baseModel's context window (custom models only on iOS, but
    // we allow it on any entry here — mirrors iOS LLMModel.contextWindowTokens
    // being writable on custom entries).
    val contextWindow: Int? = null,
    // Override reasoning support flag (custom entries only, per iOS behavior).
    val supportsReasoning: Boolean? = null,
    // Modality overrides — mirror iOS ModelModality flags. Non-null means the
    // user explicitly flipped this dimension; null means "inherit baseModel".
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // [T-android-thinking-level-arch] User-set ceiling for this model's thinking
    // intensity. Highest-priority source in the resolution chain (overrides the
    // built-in ThinkingLevelCatalog rule). null = inherit the catalog default.
    // Adding an optional field is safe for kotlinx.serialization deserialization
    // of older JSON (unlike adding an enum case) — old configs simply lack the
    // key and it defaults to null.
    val maxThinkingLevel: ThinkingLevel? = null,
) {
    val isEmpty: Boolean
        get() = displayName == null
            && maxOutputTokens == null
            && contextWindow == null
            && supportsReasoning == null
            && inputModalities == null
            && outputModalities == null
            && maxThinkingLevel == null
}

@Serializable
@Stable
data class ModelEntry(
    val providerInstanceId: String,
    @SerialName("model")
    val baseModel: LLMModel,
    val overrides: ModelOverrides = ModelOverrides(),
    val isCustom: Boolean = false,
    val isHidden: Boolean = false,
    val uuid: String = UUID.randomUUID().toString(),
    val userModifiedAt: Long? = null,
    /**
     * [T-recovery] Optional cost tier used by groups with
     * [RoutingStrategy.cheapestFirst]: 0 = free, 1 = cheap, 2 = normal,
     * 3 = expensive. null = unannotated (treated as the most expensive so
     * explicitly-tagged entries win). Pure metadata — never affects
     * selection under any other strategy.
     */
    val costTier: Int? = null,
) {
    val id: String get() = uuid

    /** Effective model as seen by the rest of the app: baseModel with overrides applied. */
    val model: LLMModel
        get() = if (overrides.isEmpty) baseModel else baseModel.copy(
            displayName = overrides.displayName ?: baseModel.displayName,
            maxOutputTokens = overrides.maxOutputTokens ?: baseModel.maxOutputTokens,
            contextWindow = overrides.contextWindow ?: baseModel.contextWindow,
            supportsReasoning = overrides.supportsReasoning ?: baseModel.supportsReasoning,
            inputModalities = overrides.inputModalities ?: baseModel.inputModalities,
            outputModalities = overrides.outputModalities ?: baseModel.outputModalities,
        )

    /** True when this entry carries user intent beyond API-reported defaults. */
    val isUserModified: Boolean
        get() = isCustom || isHidden || !overrides.isEmpty
}

@Serializable
@Stable
data class ProviderConfig(
    val instances: MutableList<ProviderInstance> = mutableListOf(),
    val modelEntries: MutableList<ModelEntry> = mutableListOf(),
    val modelGroups: MutableList<ModelGroup> = mutableListOf(),
    var defaultPrimaryGroupId: String? = null,
    var defaultSubGroupId: String? = null,
    // [T-android-provider-voice] Voice Input / Voice Output group bindings —
    // mirrors iOS ProviderConfig.voiceInputGroupId / voiceOutputGroupId
    // (per-device, provider_local_kv on iOS; meta KV rows here). Old persisted
    // JSON lacks the keys and deserializes to null (ignoreUnknownKeys +
    // declared defaults), so adding them is downgrade/round-trip safe.
    var voiceInputGroupId: String? = null,
    var voiceOutputGroupId: String? = null,
    // Models and groups exposed to the agent loop (minis-model-use terminal
    // command) — mirrors iOS agentLoopModelEntryIds / agentLoopGroupIds.
    val agentLoopModelEntryIds: MutableList<String> = mutableListOf(),
    val agentLoopGroupIds: MutableList<String> = mutableListOf(),
    // T273: bumped by ProviderRepository.saveConfig on every mutation so
    // data-class structural equals returns false even when callers mutate
    // inner MutableLists in place. Without this, MutableStateFlow's
    // distinct-until-changed (uses equals, not ===) suppresses emission
    // and ProviderDetailScreen / ModelGroupDetailScreen miss refreshes.
    // @Transient: revision is in-memory only, never persisted to prefs
    // or iCloud sync.
    @kotlinx.serialization.Transient var revision: Long = 0L,
)
