package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.CustomBodyField
import com.openminis.app.data.model.CustomHeader
import org.json.JSONArray

/**
 * [T-provider-extra-headers/body] Wire codec for the two per-instance knob
 * lists crossing the main-process → :modelservice process boundary.
 *
 * ModelExecutionDispatcher serializes ProviderInstance.customHeaders /
 * customBodyFields into `custom_headers` / `custom_body_fields` request
 * arrays; ModelExecutionService rebuilds them with these decoders. Kept OUT
 * of the service class so the protocol shape is JVM-testable without the
 * Android Service lifecycle (and so the "four-way sync" trap for
 * cross-process fields has a pinned oracle, not just review).
 *
 * Corrupt/missing entries degrade to empty — same escape-hatch contract as
 * the Room decode path (ProviderConfigMapping): a malformed row must never
 * wipe a request, it just doesn't apply the knob.
 */
fun JSONArray.parseKnobHeaders(): List<CustomHeader> =
    (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) return@mapNotNull null
        CustomHeader(name = name, value = o.optString("value", ""))
    }

fun JSONArray.parseKnobBodyFields(): List<CustomBodyField> =
    (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        val key = o.optString("key", "").trim()
        if (key.isEmpty()) return@mapNotNull null
        CustomBodyField(key = key, valueJson = o.optString("value_json", ""))
    }
