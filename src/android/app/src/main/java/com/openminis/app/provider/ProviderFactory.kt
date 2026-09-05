package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.anthropic.AnthropicProvider
import com.openminis.app.provider.gemini.GeminiProvider
import com.openminis.app.provider.openai.OpenAIProvider

object ProviderFactory {
    /**
     * Create a provider for an API-key instance.
     * [context] is retained for call-site compatibility; OAuth (which needed
     * it for encrypted token storage) was removed.
     *
     * [T-provider-key-roulette] Single choke point for multi-key rotation: the
     * stored key MAY contain several keys separated by whitespace/commas, and
     * every caller — main-process direct paths AND the offload worker — builds
     * its provider through this factory. Rotating here means no call site can
     * accidentally send the raw multi-key string as a Bearer token. Single
     * keys pass through verbatim.
     */
    fun create(instance: ProviderInstance, apiKey: String, model: LLMModel, context: Context? = null): LLMProvider {
        // [T-provider-key-roulette] LRU rotation on the provider-instance id.
        val effectiveKey = com.openminis.app.data.KeyRoulette.next(apiKey, instance.id)
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        // re-implementing the trim-+-endsWith dance inline. The previous
        // version did `url.endsWith("/v1")` on the raw, untrimmed string,
        // so a customBaseURL of "https://api.deepseek.com/v1/" (trailing
        // slash) failed the check and the code appended a second "/v1",
        // producing requests to "/v1//v1/chat/completions" → HTTP 404.
        // Likewise "https://api.deepseek.com/" was concatenated as-is to
        // ".../" + "/v1/chat/completions" = ".//v1/chat/completions",
        // which DeepSeek tolerated only by accident. effectiveBaseURL
        // trimEnd('/')'s the input first, so all four customBaseURL
        // shapes (no slash, trailing slash, /v1, /v1/) now collapse to
        // the same canonical "https://host/v1" string. The /chat/
        // completions endpoint suffix at OpenAIProvider.kt:710 then
        // produces a single-slash join.
        val basePath = instance.effectiveBaseURL
        return (when (instance.providerType) {
            ProviderType.anthropic -> {
                // [T-provider-custom-user-agent] Only meaningful for custom-base
                // (relay) instances; on the official direct path it's null.
                if (basePath != null) AnthropicProvider(effectiveKey, model, basePath, customUserAgent = instance.customUserAgent)
                else AnthropicProvider(effectiveKey, model)
            }
            ProviderType.gemini -> {
                if (basePath != null) GeminiProvider(effectiveKey, model, basePath)
                else GeminiProvider(effectiveKey, model)
            }
            ProviderType.openAI -> {
                val base = basePath ?: "https://api.openai.com/v1"
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = base,
                    useResponsesAPI = instance.useResponsesAPI,
                    // [T-provider-custom-user-agent] Covers both chat and
                    // /responses for custom-base OpenAI-compat relays; null
                    // on the official direct path.
                    customUserAgent = instance.customUserAgent,
                    // [T-android-azure-openai] Azure auths with api-key +
                    // deployments-path URL. Pass the RAW customBaseURL (not
                    // the /v1-appended, query-stripped effectiveBaseURL) so
                    // azureUrl() can preserve the ?api-version query.
                    isAzure = instance.azureMode,
                    azureBase = instance.customBaseURL,
                )
            }
            ProviderType.openRouter -> {
                // OpenRouter uses OpenAI-compatible API with custom base URL and headers
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/OpenMinis/OpenMinis",
                        "X-Title" to "Minis App",
                    ),
                )
            }
            ProviderType.xAI -> {
                // xAI exposes an OpenAI-compatible /v1/chat/completions
                // endpoint at api.x.ai/v1. API key passed through verbatim.
                val base = basePath ?: "https://api.x.ai/v1"
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = base,
                )
            }
            ProviderType.kimiCode -> {
                // Kimi Coding Plan — OpenAI-compatible upstream.
                // ⚠️ The `/v1` is load-bearing: /coding/chat/completions 404s;
                // only /coding/v1/chat/completions works (verified live on iOS).
                // Custom bases go through effectiveBaseURL's /v1-append logic.
                val base = basePath ?: "${KimiConstants.CODING_API_BASE}/v1"
                OpenAIProvider(
                    apiKey = effectiveKey,
                    model = model,
                    basePath = base,
                )
            }
        }).also { provider ->
            provider.instanceContext = instance
            // [T-android-thinking-rules-phase2] Tag OpenAI-family providers with their
            // owning instance id so the thinking resolver can look up this instance's
            // user-authored custom rules. Only OpenAIProvider consults the resolver's
            // custom-rule path (Gemini/Anthropic use their own emitters), so this is the
            // only type that needs it.
            (provider as? OpenAIProvider)?.thinkingRuleInstanceId = instance.id
        }
    }

    /**
     * [T-provider-key-roulette] Route the stored key through KeyRoulette before
     * building a provider. Single keys pass through verbatim; multi-key strings
     * rotate LRU. Callers (repo/model service) call this instead of using the
     * stored key directly.
     */
}
