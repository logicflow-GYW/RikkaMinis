package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.model.inferContextWindowTokens
import com.rikkaminis.app.data.model.normalizeModalityName

/**
 * [da1023-B] Pure derivation of [ModelOverrides] for [ModelEntryDetailScreen]'s
 * Save action. Extracted out of the composable so the four defects the audit
 * found in the inline version are JVM-testable and cannot silently regress:
 *
 *  1. the screen has switches for image / pdf / audio / video input and
 *     image / audio output but NOT for `text` (both sides) or `video` output —
 *     the inline version rebuilt the lists from the switches alone, so
 *     `"text"` was dropped from every save and the comparison against the
 *     un-normalized base list reported "differs" even when the user touched
 *     nothing. A written override is non-null, and [com.rikkaminis.app.data.model.ModelEntry.model]
 *     replaces the base list wholesale when it is non-null — so a text-out
 *     model lost `"text"` out of `outputModalities`, flipping
 *     [LLMModel.isTextOutput] to false (dropped from the new-chat default
 *     candidate list, ProviderRepository) and [com.rikkaminis.app.sandbox.offload.ModelUseProtocol.isPureImageGenerator]
 *     to true (pinned to the Images endpoint).
 *  2. the comparison ran in a different space than the switch state:
 *     [ModelEntryDetailScreen] derives its switches from `normalizeModalityName()`-d
 *     lists, but the inline save compared against the RAW base list — a base
 *     holding `"image_input"` / `"text_output"` (OpenRouter / provider APIs)
 *     therefore always "differed" and forced a write on a no-op save.
 *  3. `ModelOverrides(...)` was constructed positionally with the six
 *     screen-bound fields, so the seventh field
 *     ([ModelOverrides.maxThinkingLevel], written by OpenMinis backup import /
 *     config restore / config RPC) defaulted to null — one Save wiped it.
 *     [ModelOverrides.copy] keeps every field this screen does not own.
 *  4. `null` means "inherit baseModel" (see [ModelOverrides]). A save that the
 *     user did not touch must keep both modality fields null so the entry keeps
 *     tracking later provider metadata updates.
 *
 * Semantics kept from the inline version (not a behaviour change): displayName /
 * maxOutputTokens / contextWindow / supportsReasoning are persisted only when
 * they diverge from the base model.
 */
internal fun buildModelEntryOverrides(
    baseModel: LLMModel,
    previous: ModelOverrides,
    displayName: String,
    maxOutputTokensText: String,
    contextWindowText: String,
    thinkingEnabled: Boolean,
    imageInput: Boolean,
    pdfInput: Boolean,
    audioInput: Boolean,
    videoInput: Boolean,
    imageOutput: Boolean,
    audioOutput: Boolean,
    // [RC-1114 U-18] The screen now exposes the video-output dimension it used
    // to pass through untouched, so it is a TOGGLE like image/audio. The
    // default keeps every existing call site (and every test that does not care
    // about this dimension) byte-identical: it reproduces exactly what the
    // screen would have seeded the switch with, i.e. "inherit the base".
    videoOutput: Boolean = normalizeModalityList(baseModel.outputModalities).contains("video"),
    // [RC-1114 U-18] User ceiling for this model's thinking intensity. Same
    // rule as videoOutput: the default re-states the passthrough the callers
    // already had (`previous.copy(...)` kept the field), so a caller that does
    // not own the field cannot wipe it by omitting the argument.
    maxThinkingLevel: ThinkingLevel? = previous.maxThinkingLevel,
): ModelOverrides {
    val baseInputs = normalizeModalityList(baseModel.inputModalities)
    val baseOutputs = normalizeModalityList(baseModel.outputModalities)
    val newInputs = deriveModalityList(
        base = baseInputs,
        toggles = listOf(
            "image" to imageInput,
            "pdf" to pdfInput,
            "audio" to audioInput,
            "video" to videoInput,
        ),
    )
    val newOutputs = deriveModalityList(
        base = baseOutputs,
        toggles = listOf(
            "image" to imageOutput,
            "audio" to audioOutput,
            // [RC-1114 U-18] Video output is a real toggle now. The default
            // value of `videoOutput` is derived from the base, so a caller that
            // does not pass it gets byte-identical output to the old
            // passthrough (see the parameter's KDoc).
            "video" to videoOutput,
        ),
    )
    return previous.copy(
        displayName = displayName.trim().takeIf { it.isNotEmpty() && it != baseModel.displayName },
        maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
        contextWindow = resolveContextWindowOverride(
            text = contextWindowText,
            baseModel = baseModel,
            previous = previous,
        ),
        // supportsReasoning: persist only when user diverged from base.
        supportsReasoning = thinkingEnabled.takeIf { it != (baseModel.supportsReasoning ?: false) },
        // Modality lists: persist only when the user-edited set differs from
        // baseModel's set (both sides normalized — same space as the switches);
        // otherwise leave null so the entry tracks future provider updates.
        inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
        outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        // [RC-1114 U-18] The screen now owns this field. The parameter's
        // default is `previous.maxThinkingLevel`, so this line is a no-op for
        // every caller that does not pass it — the passthrough `previous.copy`
        // already provided is preserved exactly.
        maxThinkingLevel = maxThinkingLevel,
    )
}

/**
 * [gateway-context-1112] Decide the `contextWindow` override for a save.
 *
 * The screen PREFILLS the field with a guess when no real value is known
 * anywhere (no user override, nothing from the endpoint, nothing in the
 * models.dev registry) — see `ModelEntryDetailScreen`'s
 * `inferContextWindowTokens(baseModel)` fallback. Writing that guess into
 * the override would PIN it: an override beats every source, so the guess
 * would outrank the number the endpoint later reports for the same model, and
 * the entry could never track the real window again. That is the same
 * "freeze today's value" defect the modality fields had (da1023-B defect 4).
 *
 * So the override is written only when the value carries USER INTENT:
 *
 *  - blank / non-positive → null (the user cleared the field);
 *  - equal to the value already in `previous` → keep it verbatim (the user
 *    did not change their own override);
 *  - equal to the base model's real window → null (inherit it — the base
 *    already carries it, and an equal override would freeze it);
 *  - equal to the heuristic guess while the base has NO real window → null
 *    (this is the untouched prefill, not an edit);
 *  - anything else → the typed value (a genuine edit).
 *
 * The last two rules are what keeps the prefilled guess out of the
 * persisted row, so the endpoint's own number wins the moment it arrives.
 */
internal fun resolveContextWindowOverride(
    text: String,
    baseModel: LLMModel,
    previous: ModelOverrides,
): Int? {
    val parsed = text.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    val previousValue = previous.contextWindow
    if (previousValue != null && parsed == previousValue) return parsed
    val baseValue = baseModel.contextWindow?.takeIf { it > 0 }
    if (baseValue != null) {
        // Same as the base → inherit, do not freeze.
        return if (parsed == baseValue) null else parsed
    }
    // No real value on the base: the only thing the screen could have
    // pre-filled is the shape guess. Untouched → inherit (null); edited → pin.
    val guess = inferContextWindowTokens(baseModel)
    return if (parsed == guess) null else parsed
}

/**
 * Rebuild one modality list from its switches plus base passthrough.
 *
 * The switches only own the dimensions the screen exposes as toggles
 * (`toggles`); every other dimension in `base` — `"text"` on both sides and
 * anything a future catalog adds — is carried over untouched. That makes
 * "user flipped nothing" a no-op: the derived set equals the (normalized) base
 * set, so the caller writes `null` and the entry keeps inheriting.
 *
 * [RC-1114 U-18] Output `"video"` used to be in the passthrough list; it is a
 * real toggle now (the screen has the switch), so it is listed in `toggles`
 * and the caller passes the switch state.
 *
 * `base` must already be normalized ([normalizeModalityList]) — both the
 * toggle init in the screen and the comparison here work in the bare-name
 * space (`"image"`, not `"image_input"`).
 *
 * ponytail: toggle order is fixed (image/pdf/audio/video in, image/audio out)
 * and passthrough follows base order — no sorting pass. | 天花板: if a future
 * surface needs canonical `modalityNamesInOrder` output (the `minis-config`
 * list surface re-orders on read anyway) this list order becomes visible.
 * | 升级触发: a consumer compares the persisted list order instead of the set.
 */
internal fun deriveModalityList(
    base: List<String>,
    toggles: List<Pair<String, Boolean>>,
): List<String> {
    val toggleNames = toggles.map { it.first }.toSet()
    val out = LinkedHashSet<String>()
    for ((name, on) in toggles) if (on) out.add(name)
    for (raw in base) {
        val name = raw.normalizeModalityName()
        if (name.isNotEmpty() && name !in toggleNames) out.add(name)
    }
    return out.toList()
}

/**
 * Bare-name, de-duplicated, junk-free form of a base modality list
 * (`["text_input", "image", "image"]` → `["text", "image"]`). Mirrors the
 * screen's own `effectiveInput` / `effectiveOutput` derivation so the toggles
 * and the "did the user change anything?" comparison share one space.
 */
internal fun normalizeModalityList(list: List<String>?): List<String> =
    (list ?: emptyList())
        .map { it.normalizeModalityName() }
        .filter { it.isNotEmpty() }
        .distinct()
