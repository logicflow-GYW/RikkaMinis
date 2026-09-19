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
     * @param context [FIX-1 / F-208] Application context, needed by every
     *   implementation that owns a disk cache — the cache is keyed under
     *   `context.cacheDir` and each of them guards on `context != null`.
     *   This parameter was missing at the interface level, so all six
     *   adapters fell through to the default `null` and the guards were
     *   never satisfied: four `ProviderModelsCache` instances plus
     *   `AnthropicModelsCache` were never read from or written to, every
     *   `refreshModels` hit the live endpoint, `forceRefresh` was a no-op,
     *   and the 401/403 `cache.invalidate` branches were dead code (the
     *   `b758dda` atomic-write fix and the O-45 orphan sweep both operated
     *   on files nothing ever created). The caller
     *   (`ProviderRepository`, which already holds a `context`) passes its
     *   own; null is still accepted so tests can drive the cache-less path.
     */
    suspend fun fetchModels(
        apiKey: String?,
        instance: ProviderInstance,
        thirdParty: Boolean,
        forceRefresh: Boolean = false,
        context: android.content.Context? = null,
    ): List<LLMModel>
}