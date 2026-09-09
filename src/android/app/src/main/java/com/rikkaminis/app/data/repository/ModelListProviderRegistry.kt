package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType

/**
 * [T8-1] Registry mapping [ProviderType] → [ModelListProvider].
 *
 * Provider-package implementations register themselves via [register]
 * so the data layer never imports provider classes. Registration is
 * idempotent (last registration for a type wins), which lets tests
 * override fetchers per type.
 *
 * Thread-safety: registration typically happens once at app startup;
 * reads from [fetchModels] are safe from any thread (backed by a
 * synchronized map).
 */
object ModelListProviderRegistry {

    private val providers = HashMap<ProviderType, ModelListProvider>()

    /** Register (or replace) the fetcher for [type]. */
    fun register(type: ProviderType, provider: ModelListProvider) {
        synchronized(providers) {
            providers[type] = provider
        }
    }

    /**
     * Fetch models for [instance] via its registered provider.
     * Returns an empty list when no fetcher is registered for the
     * instance's provider type (caller falls through to its fallback).
     *
     * @param forceRefresh when true, bypass any underlying disk cache and
     *   hit the live /models endpoint. Used after adding a new provider so a
     *   previously-cached result for the same URL+key doesn't mask a real
     *   fetch (which would leave a freshly-added custom provider with an
     *   empty model list until the next daily auto-refresh).
     */
    suspend fun fetchModels(
        instance: ProviderInstance,
        apiKey: String?,
        thirdParty: Boolean,
        forceRefresh: Boolean = false,
    ): List<LLMModel> {
        val provider = synchronized(providers) { providers[instance.providerType] } ?: return emptyList()
        return provider.fetchModels(apiKey, instance, thirdParty, forceRefresh)
    }

    /** Test hook: clear all registrations. */
    fun clear() {
        synchronized(providers) { providers.clear() }
    }
}