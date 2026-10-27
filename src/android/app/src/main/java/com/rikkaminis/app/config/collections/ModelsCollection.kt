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
import com.rikkaminis.app.data.model.directionalModalities
import com.rikkaminis.app.data.model.expandDirectionalModalities
import com.rikkaminis.app.data.model.modalityNamesInOrder
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.provider.ModelsDevApi

/**
 * Exposes ModelEntry overrides under `models.<entryUUID>.…`. Mirrors
 * iOS `ModelsCollection`.
 *
 * `add` creates a custom entry (`isCustom = true`). `remove` is allowed
 * only for those custom entries — API-reported models can be hidden
 * (`isHidden = true`) but never deleted, since the same id may
 * reappear on the next refresh and any prior hide/override should
 * resume.
 */
class ModelsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "models"
    override val displayName: String get() = "LLM models"
    override val description: String get() =
        "Per-model overrides (display name, max output tokens, hidden flag, modalities, context window) and custom-model add/remove."
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
        val entry = ModelEntry(
            providerInstanceId = instanceId,
            baseModel = model,
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
            throw ConfigError.PermissionDenied(
                "Only custom-added models can be removed; use `set models.$id.isHidden true` for API-reported models"
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
            displayName = "Hidden",
            description = "When true, the model is excluded from pickers and agent loop.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            reader = {
                val e = entry(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(e.isHidden)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(isHidden = b) }
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
