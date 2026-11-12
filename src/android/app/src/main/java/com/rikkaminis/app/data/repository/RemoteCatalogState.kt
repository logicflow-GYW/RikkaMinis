package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.model.LLMModel

/**
 * [model-catalog-split] Runtime view of ONE provider instance's remote model
 * catalog (`GET /v1/models`, vendor list endpoints, …).
 *
 * This is the split half of the old design: before the split, a refresh wrote
 * the whole remote catalog into `ProviderConfig.modelEntries` (every row
 * `isHidden = true` unless the user had picked it), so the persisted table
 * carried two different things at once — "what the provider offers" and "what
 * the user selected". A remote hiccup that returned fewer rows therefore
 * DELETED the user's curated models, and a successful refresh that returned
 * new rows changed nothing on screen (they all came in hidden).
 *
 * Now the catalog lives here (memory; the per-provider 7-day disk cache in
 * `ProviderModelsCache` / `AnthropicModelsCache` is still the persistence
 * layer) and `modelEntries` only ever holds user selections. Refreshing is a
 * pure read: it publishes a new [RemoteCatalogState] and, at most, refreshes
 * the metadata of entries the user already selected.
 *
 * [models] is deliberately retained across failures — a failed refresh keeps
 * the last successful list so the management sheet never goes blank, and
 * [error] tells the UI what went wrong.
 */
data class RemoteCatalogState(
    val models: List<LLMModel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Epoch millis of the last SUCCESSFUL fetch; 0 when never fetched. */
    val fetchedAt: Long = 0L,
) {
    /** True when a fetch is in flight. */
    val isRefreshing: Boolean get() = loading
}
