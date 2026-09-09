package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderInstance

/**
 * [T8-1] Inversion-of-control seam for provider model-list fetching.
 *
 * Breaks the data→provider reverse dependency: `ProviderRepository`
 * (data layer) used to import the concrete `*ModelsApi` singletons from
 * the provider package. Now it only knows this interface; the provider
 * package registers implementations into [ModelListProviderRegistry].
 *
 * Implementations must be thread-safe (called from a coroutine context
 * the repository chooses) and must NOT throw — returning an empty list
 * on failure lets the caller fall through to its models.dev fallback,
 * preserving current `refreshModels` semantics.
 */
interface ModelListProvider {
    /**
     * Fetch the model catalog for [instance].
     *
     * @param apiKey resolved credential (API key or OAuth access token);
     *   already refreshed by the caller when the credential is OAuth.
     *   May be null when the instance has no stored credential — the
     *   implementation should return an empty list in that case.
     * @param thirdParty true when the instance points at a custom base
     *   URL that is not one of the known first-party hosts. Kept for
     *   parity with the old dispatch logic (some providers gate on it).
     * @param forceRefresh when true, bypass any underlying disk/cache and
     *   hit the live endpoint. Implementations that have no cache (e.g.
     *   Anthropic / Gemini / OpenRouter / xAI) must accept the parameter
     *   to satisfy the interface yet simply ignore it. Only the provider
     *   with a real cache (OpenAI / Kimi → ProviderModelsCache) threads it
     *   through to its fetch.
     */
    suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean = false,
    ): List<LLMModel>
}