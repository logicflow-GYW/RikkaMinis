package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.KeyRoulette
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import kotlinx.coroutines.CancellationException

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
     * [T-provider-key-roulette] This is the model-list choke point and
     * therefore follows the same rule as `ProviderFactory.create`: a fetcher
     * must never receive the raw multi-key string. Beyond cleaning the
     * credential up, this is also the only place that can *probe* — an
     * instance holding several keys refreshes as long as ANY of them works,
     * which is the entire reason the user stored more than one. Attempts walk
     * the keys in LRU order, so the keys actually exercised move to the back
     * of the rotation; the first non-empty catalog wins.
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
        // No credential at all: keep delegating so each fetcher applies its
        // own null handling (all of them return empty today).
        if (apiKey == null) return provider.fetchModels(null, instance, thirdParty, forceRefresh)

        val attempts = maxOf(1, KeyRoulette.candidates(apiKey).size)
        var lastError: Throwable? = null
        repeat(attempts) {
            val key = KeyRoulette.next(apiKey, instance.id)
            try {
                val models = provider.fetchModels(key, instance, thirdParty, forceRefresh)
                if (models.isNotEmpty()) return models
            } catch (e: CancellationException) {
                // Never swallow cancellation — the probe loop must stay
                // cancellable (the old single-shot call propagated it too).
                throw e
            } catch (t: Throwable) {
                lastError = t
            }
        }
        // Re-surface the last transport failure so the caller's existing
        // catch/log path keeps reporting it instead of the whole thing
        // degrading silently into "no models".
        lastError?.let { throw it }
        return emptyList()
    }

    /** Test hook: clear all registrations. */
    fun clear() {
        synchronized(providers) { providers.clear() }
    }
}