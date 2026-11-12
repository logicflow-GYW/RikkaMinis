package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMModel

/**
 * [gateway-context-1112] The precedence rules that decide where a model's
 * capability metadata comes from.
 *
 * Three sources can supply a context window / output cap / modality list, and
 * they do NOT agree with each other:
 *
 *  1. **the endpoint's own `/v1/models` row** — the only source that knows
 *     what THIS deployment actually accepts (a relay may cap a model well
 *     below its vendor's published window);
 *  2. the models.dev registry — a catalogue of defaults, keyed by the
 *     vendor's own model id;
 *  3. an id-shape heuristic — a guess, used only when 1 and 2 both missed.
 *
 * The order is **endpoint > registry > heuristic**. Before this change the
 * order was effectively "registry > endpoint > heuristic", and the registry
 * was keyed by an id shape the endpoint did not use (see
 * [registryLookupId]), so a gateway row usually had NEITHER a registry match
 * NOR a parsed endpoint value — and the app showed the heuristic's 128K.
 *
 * These are pure functions on purpose: [ModelsDevApi] cannot be unit-tested
 * directly (it touches `android.util.Log` and `android.content.Context`), so
 * the rules that decide the answer live here, where a JVM test can pin them.
 */

/**
 * The id to look up in the models.dev registry for [modelId].
 *
 * A gateway that multiplexes several vendors behind one endpoint publishes
 * ids in the `vendor/model` form — the local AI-Gateway publishes
 * `codebuddy-cn/glm-5.3-flash` — and models.dev has no row for such an id,
 * while the bare part (`glm-5.3-flash`) IS in the registry.
 *
 * Only the FIRST segment is removed, and only when the remainder is
 * non-empty: `deepseek/deepseek-v4.1-flash` → `deepseek-v4.1-flash`, but a
 * leading or trailing slash yields the id unchanged (no usable fallback).
 *
 * A models.dev id can itself contain a slash (`moonshotai/kimi-k3`), so the
 * remainder is kept WHOLE rather than split again — a second split would
 * turn `moonshotai/kimi-k3` into `kimi-k3` and look it up under the wrong
 * key.
 */
internal fun registryLookupId(modelId: String): String {
    val slash = modelId.indexOf('/')
    if (slash <= 0 || slash == modelId.length - 1) return modelId
    return modelId.substring(slash + 1)
}

/**
 * Merge a models.dev registry row onto a model row, with the ENDPOINT's own
 * value winning wherever both sides have one.
 *
 * The registry only fills a gap:
 *
 *  - `contextWindow` — the endpoint's number wins; the registry fills only
 *    when the endpoint reported nothing;
 *  - `maxOutputTokens`, `supportsReasoning`, the modality lists, the
 *    interleaved reasoning field and the effort tiers — same rule.
 *
 * `declaresNoEffortTiers` is the one field that is NOT a "fill the gap"
 * value: it is an affirmative statement ("this model reasons but takes no
 * `reasoning_effort` parameter"), so a registry row that declares it wins,
 * and a registry row that does not leaves the base value alone.
 */
internal fun mergeRegistryRow(
    model: LLMModel,
    registryContextWindow: Int?,
    registryMaxOutputTokens: Int?,
    registrySupportsReasoning: Boolean?,
    registryInterleavedField: String?,
    registryInputModalities: List<String>?,
    registryOutputModalities: List<String>?,
    registryReasoningEffortValues: List<String>?,
    registryDeclaresNoEffortTiers: Boolean,
): LLMModel = model.copy(
    contextWindow = model.contextWindow ?: registryContextWindow,
    maxOutputTokens = model.maxOutputTokens ?: registryMaxOutputTokens,
    supportsReasoning = model.supportsReasoning ?: registrySupportsReasoning,
    interleavedReasoningField = model.interleavedReasoningField ?: registryInterleavedField,
    inputModalities = model.inputModalities ?: registryInputModalities,
    outputModalities = model.outputModalities ?: registryOutputModalities,
    reasoningEffortValues = model.reasoningEffortValues ?: registryReasoningEffortValues,
    declaresNoEffortTiers = if (registryDeclaresNoEffortTiers) true else model.declaresNoEffortTiers,
)
