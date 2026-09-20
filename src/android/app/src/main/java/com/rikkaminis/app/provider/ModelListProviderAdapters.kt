package com.rikkaminis.app.provider

import android.content.Context

import com.rikkaminis.app.provider.KimiConstants
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.repository.ModelListProvider
import com.rikkaminis.app.data.repository.ModelListProviderRegistry
import com.rikkaminis.app.provider.anthropic.AnthropicModelsApi
import com.rikkaminis.app.provider.gemini.GeminiModelsApi
import com.rikkaminis.app.provider.openai.OpenAIModelsApi
import com.rikkaminis.app.provider.openrouter.OpenRouterModelsApi
import com.rikkaminis.app.provider.xai.XAIModelsApi

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
        context: Context?,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        return AnthropicModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            // [FIX-1 / F-208] Context was never forwarded, so
            // AnthropicModelsCache was unreachable from production.
            context = context,
            forceRefresh = forceRefresh,
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
        context: Context?,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        // [fix/audit-b22 / T5-L6] Same as the Anthropic/OpenAI adapters above.
        // [FIX-1 / F-208] Context forwarded — see the Anthropic adapter note.
        return GeminiModelsApi.fetchModels(
            apiKey,
            instance.effectiveBaseURL,
            context = context,
            forceRefresh = forceRefresh,
            // [FIX-1 / F-196] The 403→builtin fallback is an OAuth-only case.
            oauthCredential = instance.credentialType ==
                com.rikkaminis.app.data.model.ProviderCredential.oauth,
        )
    }
}

private object OpenAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,
        context: Context?,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        val baseURL = instance.effectiveBaseURL
        return OpenAIModelsApi.fetchModels(
            apiKey,
            baseURL,
            // [FIX-1 / F-208] Without this the 7-day cache (and the
            // forceRefresh bypass it exists to serve) never ran at all.
            context = context,
            // [T-provider-custom-user-agent] models-list UA override.
            customUserAgent = instance.customUserAgent,
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
        context: Context?,
    ): List<LLMModel> {
        if (apiKey == null) return emptyList()
        // [FIX-1 / F-208] Context forwarded — see the Anthropic adapter note.
        return OpenRouterModelsApi.fetchModels(
            apiKey,
            context = context,
            forceRefresh = forceRefresh,
        )
    }
}

private object XAIModelListAdapter : ModelListProvider {
    override suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean,   // no cache — accepted for interface, ignored
        context: Context?,       // no cache — accepted for interface, ignored
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
        context: Context?,
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
            // [FIX-1 / F-208] Context forwarded — see the Anthropic adapter note.
            context = context,
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