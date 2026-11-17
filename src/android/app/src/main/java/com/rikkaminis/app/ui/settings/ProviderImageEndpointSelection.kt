package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.ProviderInstance

/**
 * [WAVE-1118 D4 F-01] The segmented image-endpoint picker's ONE write path.
 *
 * Forcing a non-`auto` mode has to clear the cached probe result
 * (`imageEndpointResolved`), exactly like the other two write faces already do:
 *
 *  - the debug/`minis-config` face (`ProvidersCollection.imageEndpointMode`'s
 *    writer), and
 *  - `ProviderRepository.updateInstance`'s base-URL/v1-suffix invalidation,
 *
 * and the comment on `ProvidersCollection` names "the picker UI" as the third
 * party to that decision — but the picker only wrote `imageEndpointMode`, so a
 * probe cached before the user forced a mode came back with them when they
 * returned to `auto`: the footer claimed a resolution nobody had re-probed, and
 * [ModelUseProtocol]'s `auto && resolved != null` arm routed on it instead of
 * probing again.
 *
 * Keeping the decision in one pure function is what stops the three write faces
 * from drifting apart a fourth time (that drift is the defect, not the missed
 * `null`).
 */
internal fun withImageEndpointMode(
    instance: ProviderInstance,
    mode: ImageEndpointMode,
): ProviderInstance = instance.copy(
    imageEndpointMode = mode,
    imageEndpointResolved = resolvedAfterForcingImageEndpointMode(mode, instance.imageEndpointResolved),
)

/**
 * The cached probe result to keep after the user picks [requested].
 *
 * `auto` is "let the probe decide", so whatever was already cached stays (the
 * probe result is still a fact about this upstream — dropping it would make
 * every switch back to `auto` re-probe). Any forced mode contradicts the cache,
 * so it is cleared: the stale value could otherwise shadow the user's choice the
 * moment they switch back to `auto`.
 */
internal fun resolvedAfterForcingImageEndpointMode(
    requested: ImageEndpointMode,
    cached: ImageEndpointMode?,
): ImageEndpointMode? = if (requested == ImageEndpointMode.auto) cached else null
