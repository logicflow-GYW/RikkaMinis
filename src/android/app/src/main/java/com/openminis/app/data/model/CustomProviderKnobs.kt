package com.openminis.app.data.model

import kotlinx.serialization.Serializable

/**
 * [T-provider-extra-headers] One user-authored HTTP header on a provider
 * instance. Replaces the old per-instance [ProviderInstance.customUserAgent]
 * single-field escape hatch with the general mechanism; the UA override stays
 * intact and untouched (applyUserAgentOverride still wins for its one knob).
 *
 * Merged AFTER the defaults, so same-name REPLACE semantics over every default
 * (including Authorization/Content-Type) — mirrors RikkaHub customHeaders.
 * Danger: replacing Authorization with a bad header breaks the instance;
 * the UI must present this as an advanced option with a clear label.
 */
@Serializable
data class CustomHeader(
    val name: String,
    val value: String,
)

/**
 * [T-provider-extra-body] One user-authored top-level body field for
 * chat/completions. Recursively merged when the existing value is a JSON
 * object; direct replace otherwise. Merged at the END of the request builder,
 * so user keys WIN over defaults, but `model` is force-restored so a stray
 * override can't misroute (mirrors the model-use chatExtraBody passthrough).
 */
@Serializable
data class CustomBodyField(
    val key: String,
    val valueJson: String,
)
