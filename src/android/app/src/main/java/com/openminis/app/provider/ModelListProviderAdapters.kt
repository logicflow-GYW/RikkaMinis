package com.openminis.app.provider

import com.openminis.app.provider.KimiConstants
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ModelListProvider
import com.openminis.app.data.repository.ModelListProviderRegistry
import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.gemini.GeminiModelsApi
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.provider.openrouter.OpenRouterModelsApi
import com.openminis.app.provider.xai.XAIModelsApi

/**
 * [T8-1] Provider-package adapters that implement [ModelListProvider],
 * breaking the data→provider reverse dependency. The data layer's
 * [ModelListProviderRegistry] only knows the interface; these adapters
 * (here, in the provider package) delegate to the concrete `*ModelsApi`
 * singletons.
 *
 * Each adapter mirrors exactly the dispatch the old `ProviderRepository`
 * `when (instance.providerType)` block performed, so behaviour is
 * unchanged.
 */

private object AnthropicModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return AnthropicModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
        )
    }
}

private object GeminiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return GeminiModelsApi.fetchModels(apiKey)
    }
}

private object OpenAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
            // [T-provider-extra-headers] per-instance user headers parity.
            customHeaders = instance.customHeaders,
            // Bypass the 7-day ProviderModelsCache so a freshly-added custom
            // provider re-validates its URL+key against the live endpoint.
            forceRefresh = forceRefresh,
        )
    }
}

private object OpenRouterModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return OpenRouterModelsApi.fetchModels(apiKey)
    }
}

private object XAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
    ): List<LLMModel> {
        // xAI: the model list is static (no /v1/models gating call needed —
        // XAIModelsApi exposes the spec-mandated set).
        return XAIModelsApi.fetchModels()
    }
}

private object KimiModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
    ): List<LLMModel> {
        // [T-kimi-oauth] Kimi Code: the OAuth token CAN call the models
        // endpoint — real fetch from GET /coding/v1/models. The upstream
        // lineup shifts across generations, so the live list replaces the
        // minimal built-in fallback.
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL ?: "${KimiConstants.CODING_API_BASE}/v1"
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            customUserAgent = instance.customUserAgent,
            forceRefresh = forceRefresh,
        )
    }
}

/**
 * Register all built-in model-list providers. Called once at app
 * startup (see MinisApp / ProviderRepository init path).
 */
fun registerModelListProviders() {
    ModelListProviderRegistry.register(ProviderType.anthropic, AnthropicModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.gemini, GeminiModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.openAI, OpenAIModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.openRouter, OpenRouterModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.xAI, XAIModelListAdapter)
    ModelListProviderRegistry.register(ProviderType.kimiCode, KimiModelListAdapter)
}