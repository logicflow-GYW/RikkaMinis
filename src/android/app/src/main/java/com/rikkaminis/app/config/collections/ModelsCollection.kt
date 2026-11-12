package com.rikkaminis.app.config.collections

import com.rikkaminis.app.config.ConfigCollection
import com.rikkaminis.app.config.ConfigError
import com.rikkaminis.app.config.ConfigField
import com.rikkaminis.app.config.ConfigRisk
import com.rikkaminis.app.config.ConfigSchema
import com.rikkaminis.app.config.ConfigValue
import com.rikkaminis.app.config.fields.ClosureField
import com.rikkaminis.app.config.fields.ReadOnlyField
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.directionalModalities
import com.rikkaminis.app.data.model.expandDirectionalModalities
import com.rikkaminis.app.data.model.modalityNamesInOrder
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.provider.ModelsDevApi

/**
 * Exposes ModelEntry overrides under `models.<entryUUID>.…`. Mirrors
 * iOS `ModelsCollection`.
 *
 * [model-catalog-split] The entry table is selection-only: it holds the
 * models the user selected (plus custom entries), not a catalog copy.
 * `isHidden` is a frozen legacy field — no row carries `isHidden = true`
 * after the one-shot migration — so `set models.<id>.isHidden true` is
 * now an *unselect* alias: it removes the entry from the selected set
 * (the same action the management sheet performs), and `false` is a
 * no-op. The writer must never persist `copy(isHidden = true)` back
 * into the table: that would mint a hidden row and break the
 * selection-only invariant (see [decideHiddenWrite]).
 *
 * `add` creates a custom entry (`isCustom = true`). `remove` is allowed
 * only for those custom entries — API-reported models are unselected
 * via the `isHidden = true` alias (never deleted outright, since the
 * same id can be re-selected from the remote catalog later; the alias
 * also covers built-in entries, which `provider.models.remove` still
 * refuses to delete).
 */
class ModelsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "models"
    override val displayName: String get() = "LLM models"
    override val description: String get() =
        "Per-model overrides (display name, max output tokens, selection via the legacy isHidden alias, modalities, context window) and custom-model add/remove."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> =
        repo.config.value.modelEntries.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        val entry = entry(forId) ?: return emptyList()
        return listOf(
            providerInstanceId(forId),
            modelId(forId),
            isCustom(forId),
            displayNameField(forId),
            maxOutputTokensField(forId),
            isHiddenField(forId),
            modalitiesField(forId),
            modalitiesOverrideField(forId),
            contextWindowField(forId),
            contextWindowOverrideField(forId),
            supportsReasoningField(forId),
            supportsToolsField(forId),
            supportsVisionField(forId),
        )
    }

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")

        // Accept `instance_id` (canonical, matches iOS) or `provider_id`
        // (CLI-facing alias documented in `minis-config` help). Either
        // must be a non-empty UUID of an existing provider instance.
        val instanceId = (
            (obj["instance_id"] as? ConfigValue.Str)?.value
                ?: (obj["provider_id"] as? ConfigValue.Str)?.value
            )
            ?: throw ConfigError.InvalidValue("`instance_id` (or `provider_id`) required")
        if (instanceId.isEmpty()) throw ConfigError.InvalidValue("`instance_id` (or `provider_id`) required")

        // [da1026-D] Trim before validating (parity with modelsUpdate's
        // [da1022-E] blank-id gate): a whitespace-only id passed isEmpty()
        // and minted a blank composite-key row.
        val modelIdStr = (obj["model_id"] as? ConfigValue.Str)?.value?.trim()
            ?: throw ConfigError.InvalidValue("`model_id` required")
        if (modelIdStr.isEmpty()) throw ConfigError.InvalidValue("`model_id` required")

        val instance = repo.instance(instanceId)
            ?: throw ConfigError.InvalidValue("No provider instance with id $instanceId")
        // Refuse silent dedupe: the spec contract is that re-adding the
        // same provider+model pair surfaces an `already_exists` error so
        // the agent knows the prior add already succeeded.
        val duplicate = repo.config.value.modelEntries.firstOrNull {
            it.providerInstanceId == instanceId && it.baseModel.id == modelIdStr
        }
        if (duplicate != null) {
            throw ConfigError.AlreadyExists(
                "Model '$modelIdStr' already exists on provider '$instanceId' (entry id ${duplicate.id})."
            )
        }
        val displayName = (obj["display_name"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
            ?: modelIdStr
        val contextWindow = (obj["context_window"] as? ConfigValue.Int)?.value?.takeIf { it > 0 }
        val maxOut = (obj["max_output_tokens"] as? ConfigValue.Int)?.value?.takeIf { it > 0 }
        val supportsReasoning = (obj["supports_reasoning"] as? ConfigValue.Bool)?.value

        val model = LLMModel(
            id = modelIdStr,
            displayName = displayName,
            // [da1026-D] Persist the human provider name (ProviderType
            // displayName) — the same value provider.models.add and
            // AddCustomModelScreen write — instead of the instance UUID.
            // minis-model-use list rendered the raw UUID and
            // ModelsDevApi.enrichModel could never resolve providerKeyMap /
            // registry for these rows, dropping them into the
            // order-dependent all-providers scan (the F-212 failure).
            provider = instance.providerType.displayName,
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
        )
        // [r3-b08 F-04] The explicit numbers ALSO go into `overrides`. They used
        // to live only on `baseModel`, and `ModelsDevApi.applyDevData` prefers the
        // registry value (`devModel.contextWindow ?: model.contextWindow`), so for
        // any id models.dev knows (gpt-4o, claude-…, every popular one) the
        // agent's `context_window`/`max_output_tokens`/`supports_reasoning` were
        // silently replaced by the catalog defaults on the very next read — the
        // entry came back with 128000/16384 while the add call had returned
        // ok:true. `overrides` is the user-explicit layer and is applied AFTER
        // enrichment (`effectiveEntryModel` = enrich(baseModel).withOverrides(…)),
        // which is exactly the precedence a hand-typed value deserves.
        //
        // `display_name` stays on baseModel only: it is the row's NAME, not an
        // override of the registry's name (and duplicating it would make the
        // detail screen show an "overridden" badge for a field the user never
        // deliberately pinned).
        val entry = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model,
            overrides = ModelOverrides(
                contextWindow = contextWindow,
                maxOutputTokens = maxOut,
                supportsReasoning = supportsReasoning,
            ),
            isCustom = true,
            isHidden = false,
            userModifiedAt = System.currentTimeMillis(),
        )
        // [da1023-C] Return the canonical persisted id (saveConfig's
        // canonicalize pass rewrites entry.uuid to the composite
        // instanceId/modelId key), not the pre-persist uuid.
        return repo.addEntry(entry)
    }

    override fun remove(id: String) {
        val entry = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        if (!entry.isCustom) {
            // [model-catalog-split] The suggested alias below really does
            // remove the entry now (isHidden=true → unselect), so the hint
            // stays truthful. API-reported entries can also be unselected
            // from the management sheet, whose path bypasses this custom-only
            // gate (provider.models.remove's confirm semantics are unchanged).
            throw ConfigError.PermissionDenied(
                "Only custom-added models can be removed; use `set models.$id.isHidden true` to unselect API-reported models (legacy alias — also available via provider.models.remove)"
            )
        }
        repo.removeEntry(id)
    }

    // -- Field factories --

    private fun entry(id: String): ModelEntry? =
        repo.config.value.modelEntries.firstOrNull { it.id == id }

    private fun mutate(id: String, apply: (ModelEntry) -> ModelEntry) {
        val e = entry(id) ?: throw ConfigError.UnknownPath("models.$id")
        repo.updateEntry(apply(e))
    }

    private fun providerInstanceId(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.providerInstanceId",
            displayName = "Provider instance",
            description = "Owning provider instance (read-only).",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(e.providerInstanceId)
            },
        )

    private fun modelId(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.modelId",
            displayName = "Model id",
            description = "API model identifier (read-only).",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(e.baseModel.id)
            },
        )

    private fun isCustom(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.isCustom",
            displayName = "Is custom",
            description = "True for entries created via `models add`; false for API-reported.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool(e.isCustom)
            },
        )

    private fun displayNameField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.displayName",
            displayName = "Display name",
            description = "User-visible label override. Empty restores the API default.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(e.overrides.displayName ?: e.baseModel.displayName)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { e ->
                    val newOverride = if (s.isEmpty() || s == e.baseModel.displayName) null else s
                    e.copy(overrides = e.overrides.copy(displayName = newOverride))
                }
            },
        )

    private fun maxOutputTokensField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.maxOutputTokens",
            displayName = "Max output tokens",
            description = "Override the API-reported max. 0 restores the default.",
            valueSchema = ConfigSchema.Int(min = 0, max = 1_000_000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                // [dv22-A4] Same single source as contextWindow (this one sat
                // in the same function family and read the raw base model):
                // models.dev first, the user's override second.
                ConfigValue.Int(effectiveModel(e).maxOutputTokens ?: 0)
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                mutate(id) { e ->
                    e.copy(overrides = e.overrides.copy(maxOutputTokens = if (i == 0) null else i))
                }
            },
        )

    private fun isHiddenField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.isHidden",
            displayName = "Selected (legacy isHidden)",
            description = "Selection flag: true removes the model from the selected set (legacy alias for unselect; catalog-split tables never store hidden rows), false is a no-op for an already-selected model.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                // Legacy field, migrated to false everywhere — report what the
                // row actually stores, never a fabricated "selected" value.
                ConfigValue.Bool(e.isHidden)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                when (decideHiddenWrite(entry(id), b)) {
                    HiddenWriteAction.UNSELECT -> repo.unselectEntry(id)
                    HiddenWriteAction.NOOP -> {}
                    HiddenWriteAction.ERROR_UNKNOWN_ID ->
                        throw ConfigError.UnknownPath("models.$id")
                }
            },
        )

    // -- Capability fields (modalities / contextWindow / derived) --

    /**
     * Build the *effective* modality list of [e] — the canonical
     * direction-suffixed names both `minis-model-use list` and this collection
     * emit.
     *
     * [dv21-A05] It goes through the SAME resolver as the offload side
     * (`ModelsDevApi.effectiveModel` → `LLMModel.directionalModalities`), so the
     * two surfaces cannot answer differently:
     *   - the base model is enriched from models.dev first and the user's
     *     overrides applied on top (this surface used to skip enrichment
     *     entirely, so every built-in model — all 39 declare null modality
     *     fields by construction — reported `[]`);
     *   - an absent list means text on that side, evaluated PER DIRECTION
     *     (a pure image generator keeps `supportsTools = false`).
     */
    /**
     * [dv22-A4] The single enriched-model resolver for this collection —
     * the SAME `ModelsDevApi.effectiveModel` the offload faces and the
     * agent-facing `minis-model-use` list go through. Private one-line
     * alias: every "what can this model do" field below funnels through
     * this one call site (the parity test pins the literal call count at
     * 1, and the alias keeps that true however many fields need it).
     */
    private fun effectiveModel(e: ModelEntry): LLMModel =
        ModelsDevApi.effectiveModel(e)

    private fun effectiveModalities(e: ModelEntry): List<String> =
        effectiveModel(e).directionalModalities

    /** Split the user-supplied flat list into input/output components. */
    private fun decodeModalitiesOverride(names: List<String>): Pair<List<String>, List<String>> {
        val inputs = ArrayList<String>()
        val outputs = ArrayList<String>()
        for (n in names) {
            val low = n.lowercase()
            if (low !in modalityNamesInOrder) {
                throw ConfigError.InvalidValue(
                    "Unknown modality '$n'. Allowed: ${modalityNamesInOrder.joinToString(", ")}"
                )
            }
            when {
                low.endsWith("_input") -> inputs.add(low.removeSuffix("_input"))
                low.endsWith("_output") -> outputs.add(low.removeSuffix("_output"))
            }
        }
        return inputs to outputs
    }

    private fun modalitiesField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.modalities",
            displayName = "Modalities",
            description = "Effective modality list (override applied if set, otherwise inferred from provider / models.dev).",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Arr(effectiveModalities(e).map { ConfigValue.Str(it) })
            },
        )

    private fun modalitiesOverrideField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.modalitiesOverride",
            displayName = "Modalities override",
            description = "User override list. Pass [] or null to clear and fall back to inferred. Allowed: text_input, text_output, image_input, pdf_input, audio_input, video_input, image_output, audio_output, video_output.",
            valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                val ov = e.overrides
                if (ov.inputModalities == null && ov.outputModalities == null) {
                    return@ClosureField ConfigValue.Arr(emptyList())
                }
                // Raw override reader — reports exactly what the user wrote.
                // expandDirectionalModalities() (no text fallback) keeps that
                // promise: the derived "absent means text" default must never
                // be persisted-looking output here, or a partial override would
                // read back as a different one.
                ConfigValue.Arr(
                    expandDirectionalModalities(ov.inputModalities, ov.outputModalities)
                        .map { ConfigValue.Str(it) },
                )
            },
            writer = { v ->
                val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                // [fix/audit-0917-b9] Empty array is the documented "clear"
                // sentinel; a NON-string entry is malformed input and must not
                // silently read as "clear" (the old mapNotNull dropped every
                // non-string element and fell into the empty branch, wiping
                // the override on behalf of a typo). Refuse instead of
                // guessing — same flavour as the other writer gates.
                if (arr.isEmpty()) {
                    mutate(id) { e ->
                        e.copy(overrides = e.overrides.copy(inputModalities = null, outputModalities = null))
                    }
                    return@ClosureField
                }
                val names = arr.map { (it as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("array entries must be strings") }
                val (inputs, outputs) = decodeModalitiesOverride(names)
                mutate(id) { e ->
                    e.copy(
                        overrides = e.overrides.copy(
                            inputModalities = inputs.ifEmpty { null },
                            outputModalities = outputs.ifEmpty { null },
                        )
                    )
                }
            },
        )

    private fun contextWindowField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.contextWindow",
            displayName = "Context window (tokens)",
            description = "Effective context window in tokens (override applied if set, otherwise from models.dev / provider API).",
            valueSchema = ConfigSchema.Int(),
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                // [dv22-A4] Route through the shared enriched resolver. The
                // raw `overrides ?: baseModel ?: 0` chain skipped models.dev
                // entirely, so every built-in model whose local row carries a
                // null window reported 0 (33 of the 39 built-ins) even though
                // the registry knows the real number. Enrichment is a default,
                // so overrides still win — `withOverrides` applies them second.
                ConfigValue.Int(effectiveModel(e).contextWindow ?: 0)
            },
        )

    private fun contextWindowOverrideField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.contextWindowOverride",
            displayName = "Context window override (tokens)",
            description = "User override in tokens. 0 clears the override and restores the API value.",
            valueSchema = ConfigSchema.Int(min = 0, max = 10_000_000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Int(e.overrides.contextWindow ?: 0)
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                mutate(id) { e ->
                    e.copy(overrides = e.overrides.copy(contextWindow = if (i == 0) null else i))
                }
            },
        )

    /**
     * [R5-E F-04] `models.<id>.supportsReasoning` — the reasoning capability
     * flag, writable as an override.
     *
     * Before this field the collection exposed `supportsTools` / `supportsVision`
     * but not the flag that actually drives the thinking-level ceiling, while
     * `add()` already persisted `overrides.supportsReasoning` ([r3-b08 F-04]).
     * Two consequences: `minis-config get models.<id>.supportsReasoning`
     * answered unknown_path, and the `.remove` snapshot (built by walking
     * `fields()`) dropped the property, so the removed row's `old` value lied.
     *
     * The read is EFFECTIVE, not raw: `overrides.supportsReasoning` alone is
     * null for every model the user never pinned, while the registry value is
     * what the caller actually needs — the same trap [contextWindowField]
     * documents at [dv22-A4], where the raw `overrides ?: baseModel` chain
     * answered 0 for 33 of the 39 built-ins. Writing PINS the flag (overrides
     * win over the registry, see `LLMModel.withOverrides`); there is no clear
     * sentinel — a bool has no third value to distinguish "false" from
     * "unset", so a written value stays pinned.
     */
    private fun supportsReasoningField(id: String): ConfigField =
        ClosureField(
            path = "models.$id.supportsReasoning",
            displayName = "Supports reasoning",
            description = "Effective reasoning flag (override applied if set, otherwise from models.dev / the provider). Writing pins it for this entry.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(effectiveModel(e).supportsReasoning == true)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { e -> e.withSupportsReasoningOverride(b) }
            },
        )

    private fun supportsToolsField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.supportsTools",
            displayName = "Supports tools",
            description = "Derived. True when the model can produce text output.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool("text_output" in effectiveModalities(e))
            },
        )

    private fun supportsVisionField(id: String): ConfigField =
        ReadOnlyField(
            path = "models.$id.supportsVision",
            displayName = "Supports vision",
            description = "Derived. True when the model accepts image input.",
            valueSchema = ConfigSchema.Bool,
            reader = {
                val e = entry(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Bool("image_input" in effectiveModalities(e))
            },
        )
}

/**
 * [R5-E F-04] The write transformation behind
 * `models.<id>.supportsReasoning` — extracted as a pure function so the
 * override semantics are testable without a repository: the value lands in
 * `overrides` (which win over the enriched registry value in
 * `ModelEntry.model`), it never touches `baseModel`, and writing `false` is a
 * PIN, not a clear (a bool has no third value).
 */
internal fun ModelEntry.withSupportsReasoningOverride(value: Boolean): ModelEntry =
    copy(overrides = overrides.copy(supportsReasoning = value))

/**
 * [model-catalog-split] The write transformation behind
 * `models.<id>.isHidden` — extracted as a pure function so the
 * selection-alias semantics are testable without a repository.
 *
 * Under the selection-only entry table, `isHidden = true` must translate to
 * an UNSELECT (the row leaves the table; the management sheet's action), and
 * `false` is a NOOP — the entry is by definition already selected. The
 * pre-split behaviour (`copy(isHidden = b)` written back) is FORBIDDEN: it
 * would mint a `isHidden = true` row, which the one-shot migration removes
 * and no write path may reintroduce.
 *
 * An unknown id maps to [HiddenWriteAction.ERROR_UNKNOWN_ID] instead of
 * throwing here so the caller keeps control of the error surface (the
 * collection throws `ConfigError.UnknownPath`, matching the pre-split
 * `mutate` behaviour exactly).
 */
internal enum class HiddenWriteAction { UNSELECT, NOOP, ERROR_UNKNOWN_ID }

internal fun decideHiddenWrite(currentEntry: ModelEntry?, newValue: Boolean): HiddenWriteAction {
    if (currentEntry == null) return HiddenWriteAction.ERROR_UNKNOWN_ID
    return if (newValue) HiddenWriteAction.UNSELECT else HiddenWriteAction.NOOP
}
