package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderInstance

/**
 * Pure route-domain helpers extracted from the ChatViewModel config collector.
 *
 * [providerRouteChanged] compares the subset of [ProviderInstance] fields that
 * actually affect outbound request ROUTING — i.e. every field that
 * [ProviderFactory.create] snapshots into the provider (and therefore every
 * field that [ChatViewModel.streamChatTurnOffloaded] serializes into the
 * `:modelservice` request.json via `provider.instanceContext`).
 *
 * A field that changes but is NOT in this set (e.g. `label`, `isEnabled`,
 * `pinned`, `createdAt`, `credentialType`) does not require rebuilding the
 * cached provider object.
 *
 * Deliberately absent:
 *  - `imageEndpointResolved` — that is a cached PROBE RESULT, not user intent;
 *    [ProviderRepository.updateInstance] already clears it when the base URL or
 *    v1-suffix changes so auto mode re-probes.
 *  - `credentialType` — OAuth token storage was removed (see the OAuth login
 *    flow note in ProviderRepository); every live provider is apiKey-backed.
 *
 * This is the live-edit counterpart to the "provider disabled mid-session"
 * re-resolution ([T-android-disabled-provider-still-selectable-via-group #34]):
 * like disabling, editing a route field should take effect without a process
 * restart — the collector detects the drift and rebuilds the cached provider
 * in place from the fresh config.
 *
 * Extracted as a top-level function so it is trivially JVM-testable, following
 * the FE-4 route-A pattern (ChatCompactionLogic / ChatMessageJson).
 */
fun providerRouteChanged(a: ProviderInstance, b: ProviderInstance): Boolean {
    return a.customBaseURL != b.customBaseURL ||
        a.appendV1Suffix != b.appendV1Suffix ||
        a.useResponsesAPI != b.useResponsesAPI ||
        a.azureMode != b.azureMode ||
        a.customUserAgent != b.customUserAgent ||
        a.imageEndpointMode != b.imageEndpointMode ||
        // [T-provider-extra-headers/body] Knobs change the wire contract, so the
        // cached provider must be rebuilt to pick them up (the cached
        // instanceContext otherwise holds the pre-edit snapshot forever).
        a.customHeaders != b.customHeaders ||
        a.customBodyFields != b.customBodyFields
}