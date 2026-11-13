package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.provider.safeOptString
import org.json.JSONObject

/**
 * [gateway-context-1112] Capability metadata carried by an OpenAI-compatible
 * `/v1/models` row — parsed ONCE, at the boundary, into a plain holder.
 *
 * ## Why this exists
 *
 * Most OpenAI-compatible gateways DO report a model's context window, but the
 * application never read it: [OpenAIModelsApi]'s parser only looked at `id`,
 * `name` and `architecture.{input,output}_modalities`, so the window, the
 * output cap, the display name and the four `supports_*` flags were all
 * DROPPED on the floor. The app then fell back to the models.dev registry and,
 * when that missed too, to an id-shape heuristic — which is why a gateway
 * advertising 960 000 tokens for a model showed up as 128K.
 *
 * ## Why the holder instead of reading JSON inline
 *
 * The precedence rule this file encodes ("what the endpoint says beats what a
 * catalogue guesses") has to be unit-testable WITHOUT a network stack and
 * without an Android runtime, so the parse result is a data holder this file
 * owns and [OpenAIModelsApi] merely copies into an [com.rikkaminis.app.data.model.LLMModel].
 *
 * ## Field aliases
 *
 * Gateways disagree on names, and the local gateway (AI-Gateway) uses one
 * spelling while the common new-api / one-api family uses another:
 *
 *  - context window: `context_length` (OpenRouter, AI-Gateway), `max_input_tokens`
 *    (OpenAI "models" shape), `context_window`, `maxInputTokens` (CodeBuddy).
 *  - output cap: `max_output_tokens`, `max_completion_tokens`, and
 *    `top_provider.max_completion_tokens` (OpenRouter nests it).
 *
 * All aliases are read; the first non-positive/missing one falls through to
 * the next. A value of 0 or a negative number is treated as "not reported" —
 * the same rule the gateway itself uses (`if (contextWindow > 0)`), so the two
 * sides agree on what "no number" means.
 */
internal data class OpenAIModelEndpointMetadata(
    /** Context window in tokens, or null when the endpoint did not report one. */
    val contextWindow: Int? = null,
    /** Output token cap, or null when the endpoint did not report one. */
    val maxOutputTokens: Int? = null,
    /** Display name, or null when the endpoint did not report one. */
    val displayName: String? = null,
    /** Declared reasoning support, or null when the endpoint did not report it. */
    val supportsReasoning: Boolean? = null,
    /** Declared tool support, or null when the endpoint did not report it. */
    val supportsTools: Boolean? = null,
    /** Declared vision support, or null when the endpoint did not report it. */
    val supportsVision: Boolean? = null,
    /** Declared audio support, or null when the endpoint did not report it. */
    val supportsAudio: Boolean? = null,
)

/**
 * [gateway-context-1112] Parse the capability metadata out of one
 * `/v1/models` row.
 *
 * Pure function over a [JSONObject] — no Android, no network — so the whole
 * precedence contract is JVM-testable.
 *
 * Note on `name` vs `display_name`: the OpenAI shape uses `name`; the local
 * gateway and the OpenRouter shape use `display_name`. Both are accepted, and
 * `name` wins when both are present because that is the OpenAI-standard field.
 */
internal fun parseOpenAIModelEndpointMetadata(obj: JSONObject): OpenAIModelEndpointMetadata {
    val contextWindow = firstPositiveInt(
        obj.optInt("context_length"),
        obj.optInt("max_input_tokens"),
        obj.optInt("context_window"),
        obj.optInt("maxInputTokens"),
    )
    val maxOutputTokens = firstPositiveInt(
        obj.optInt("max_output_tokens"),
        obj.optInt("max_completion_tokens"),
        // OpenRouter nests the output cap under `top_provider` — it is the
        // PER-REQUEST output limit, NOT a context window, so it must never be
        // read as one.
        obj.optJSONObject("top_provider")?.optInt("max_completion_tokens") ?: 0,
    )
    // [R8-B B-F04 后半] On Android, `optString(key)` returns the LITERAL "null"
    // when the key exists with a JSON null value (it only returns "" for a missing
    // key), so `{"name":null,"display_name":"Hy4"}` used to answer "null" —
    // `isNotBlank()` is true for that string, the `?:` fallback never fired, and
    // the model picker showed a model literally named "null".
    //
    // Reuses [safeOptString], the repo's single answer to this platform quirk
    // (JsonExt.kt), rather than minting a second reader: absence and explicit null
    // both come back as "" and fall through to `display_name`.
    //
    // NOTE for the test: the Maven `org.json` used by JVM unit tests does NOT
    // reproduce the quirk (measured: `optString` on a JSON null returns ""), so
    // behaviour alone cannot pin this fix — the shape assertion in
    // OpenAIEndpointMetadataDisplayNameTest is the discriminator.
    val displayName = obj.safeOptString("name").takeIf { it.isNotBlank() }
        ?: obj.safeOptString("display_name").takeIf { it.isNotBlank() }

    return OpenAIModelEndpointMetadata(
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        displayName = displayName,
        // Absent flag = "the endpoint said nothing" (null), which is NOT the
        // same as false. A false here would permanently disable the thinking
        // toggle for a gateway that simply does not report the flag.
        supportsReasoning = obj.optBooleanOrNull("supports_reasoning"),
        supportsTools = obj.optBooleanOrNull("supports_tools"),
        supportsVision = obj.optBooleanOrNull("supports_vision"),
        supportsAudio = obj.optBooleanOrNull("supports_audio"),
    )
}

/**
 * [INCR-1113 F-06] Fold the endpoint's capability FLAGS into the modality
 * list it declared.
 *
 * `parseOpenAIModelEndpointMetadata` reads `supports_vision` / `supports_audio`
 * and — until this function existed — nothing consumed them: the flags were
 * parsed and dropped, so a gateway that says `supports_vision: true` without
 * publishing `architecture.input_modalities` (the common shape) still left the
 * model text-only in the app. The `supports_*` flags ARE the endpoint's own
 * statement, so they are exactly as authoritative as the modality array — the
 * contract this family is about is "the endpoint's own word beats a registry
 * default and a shape guess", and a flag is the endpoint's word.
 *
 * Rules:
 *  - `null` (the endpoint did not report the flag) changes nothing — silence
 *    is not a denial, same as everywhere else in this family;
 *  - `false` changes nothing either: the local gateway writes booleans
 *    unconditionally, so a false is not a reliable "cannot" (the same reading
 *    [resolveSupportsReasoning] applies to the reasoning flag);
 *  - `true` ADDS the modality; an absent list starts from `["text"]` so the
 *    result is never a capabilities-only list that drops text.
 *
 * Pure function — no JSON, no Android — so the rule is JVM-testable.
 */
internal fun mergeDeclaredModalities(
    declared: List<String>?,
    declaresVision: Boolean?,
    declaresAudio: Boolean?,
): List<String>? {
    val add = ArrayList<String>(2)
    if (declaresVision == true) add.add("image")
    if (declaresAudio == true) add.add("audio")
    if (add.isEmpty()) return declared
    val base = declared ?: listOf("text")
    val missing = add.filter { it !in base }
    if (missing.isEmpty()) return declared
    return base + missing
}

/**
 * First strictly positive value, or null when none of the candidates is one.
 *
 * `optInt` returns 0 for a missing key, so "missing" and "reported zero"
 * collapse to the same value here — deliberate, and identical to the gateway's
 * own `if (contextWindow > 0)` rule.
 */
private fun firstPositiveInt(vararg candidates: Int): Int? =
    candidates.firstOrNull { it > 0 }

/**
 * [gateway-context-1112] Decide the model's reasoning support from the
 * endpoint's flag and the id-family whitelist.
 *
 * **true wins, false does NOT win.**
 *
 * The naive reading — "the endpoint says false, so the model cannot reason" —
 * is wrong for the local gateway, and following it would have silently
 * switched the thinking toggle OFF for every model served through it. The
 * gateway derives the flag from its own per-provider catalogue and writes the
 * boolean UNCONDITIONALLY, so every model without a catalogue entry comes
 * back as `false` — including models that do reason. Observed on the real
 * payload (2026-11-12): all 15 rows carry `supports_reasoning: false`.
 *
 * So a `false` means "this endpoint's catalogue has no opinion", and the
 * id-family whitelist answers instead. A `null` (the endpoint said nothing)
 * is treated the same way.
 *
 * Extracted as a pure function so the rule is JVM-testable and cannot drift
 * from the parser.
 */
internal fun resolveSupportsReasoning(
    endpointFlag: Boolean?,
    knownReasoningFamily: Boolean,
): Boolean? = when {
    endpointFlag == true -> true
    knownReasoningFamily -> true
    else -> null
}

/**
 * `optBoolean` treats a missing key as false, which would turn "the endpoint
 * never mentioned this flag" into "this model does not support the feature".
 * Read the raw value instead so absence stays distinguishable.
 */
private fun JSONObject.optBooleanOrNull(key: String): Boolean? {
    if (!has(key) || isNull(key)) return null
    return optBoolean(key)
}
