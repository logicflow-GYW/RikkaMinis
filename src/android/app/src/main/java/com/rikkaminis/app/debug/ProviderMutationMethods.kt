package com.rikkaminis.app.debug

import android.content.Context
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.config.collections.CUSTOM_BASE_URL_OPENROUTER_MESSAGE
import com.rikkaminis.app.config.collections.CUSTOM_USER_AGENT_OAUTH_MESSAGE
import com.rikkaminis.app.config.collections.applyContextLimitWrite
import com.rikkaminis.app.config.collections.canonicalModelProviderName
import com.rikkaminis.app.config.collections.customUserAgentWritable
import com.rikkaminis.app.config.collections.mutatedGroupCopy
import com.rikkaminis.app.config.collections.normalizeCustomBaseURL
import com.rikkaminis.app.config.collections.normalizeCustomModelId
import com.rikkaminis.app.data.model.FallbackStrategy
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.config.collections.canonicalModelProviderName
import com.rikkaminis.app.config.collections.normalizeCustomModelId
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.provider.gemini.GeminiBasePath
import com.rikkaminis.app.provider.openai.openAIKeyAuthHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Mutation handlers for the `provider.*` and `provider.groups.*` RPC methods —
 * Phase 2.
 *
 * Each method routes through the existing [ProviderRepository] surface, which
 * is the same code path the in-app Settings screens use. Mutations are written
 * synchronously to disk; Keychain-equivalent (EncryptedSharedPreferences)
 * writes happen alongside JSON config writes — a failed credential write is
 * surfaced as -32000.
 */
internal object ProviderMutationMethods {

    private fun repo(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    // ─── Instances ──────────────────────────────────────────────────────────

    fun instancesCreate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val typeRaw = params.optString("providerType", "").ifEmpty {
            throw RPCException(-32602, "Missing 'providerType' param")
        }
        val type = try { ProviderType.valueOf(typeRaw) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown providerType: $typeRaw")
        }
        // [R4-R F-11] Trim, then refuse empty: `.ifEmpty` alone only caught
        // `""`, so a whitespace-only label persisted and the Settings list
        // rendered a nameless instance card. [R5-F F-F-08] The rule itself now
        // lives in [requireNonBlankParam] so the group faces share it instead
        // of carrying a trim-less copy.
        val label = requireNonBlankParam(params, "label", "Missing 'label' param")
        val credTypeStr = params.optString("credentialType", "apiKey").ifEmpty { "apiKey" }
        val credentialType = try { ProviderCredential.valueOf(credTypeStr) } catch (_: Exception) {
            throw RPCException(-32602, "Unknown credentialType: $credTypeStr")
        }
        // [deep-audit-1039 A-04] Normalization is single-sourced in
        // [normalizeCustomBaseURL] (ProviderWriteRules) so this face agrees
        // with the collection faces: the old `ifEmpty { null }` let a
        // whitespace-only value through the guard below and persisted it.
        val customBaseURL = normalizeCustomBaseURL(params.optString("customBaseURL", ""))
        val appendV1Suffix = params.optBoolean("appendV1Suffix", true)
        val isEnabled = params.optBoolean("isEnabled", true)
        val useResponsesAPI = params.optBoolean("useResponsesAPI", false)
        // [da1030-C ISSUE-04] Azure OpenAI mode was readable on neither face
        // (the field was missing from instanceJsonCore/instancesList) and
        // writable on neither (create/update ignored it), so an automation
        // harness could not set or observe the auth-header shape the request
        // path honors (isAzure → `api-key` instead of `Authorization: Bearer`,
        // ProviderFactory). Parsed with the same default as the instance field.
        val azureMode = params.optBoolean("azureMode", false)
        // [fix/dv16-a A-07] Optional imageEndpointMode at create time — the
        // same key the update face accepts (gate C14 pins the two write faces
        // to the same key set), with the same parse / value domain / error
        // wording via the shared [parseImageEndpointMode]. A fresh instance
        // has no cached probe result, so imageEndpointResolved stays at its
        // null default: for auto the first image request probes the endpoint,
        // for a forced non-auto mode nothing is cached (update's
        // clear-on-force has nothing to clear here).
        val imageEndpointMode = if (params.has("imageEndpointMode")) {
            parseImageEndpointMode(params.optString("imageEndpointMode", "auto"))
        } else ImageEndpointMode.auto
        // [T-provider-no-static-seed] Default OFF: a fresh provider starts with
        // an empty model list; refreshModels() populates it from upstream.
        // Kept as an explicit opt-in for callers that know what they're doing
        // (e.g. a custom sync that wants the built-in static list verbatim).
        val seedBuiltInModels = params.optBoolean("seedBuiltInModels", false)

        // [da1023-C] customBaseURL is honored by every provider arm in
        // ProviderFactory except openRouter, whose request builder pins
        // https://openrouter.ai/api/v1 — the old openAI-only gate rejected
        // configs the platform actually runs: the UI and the providers.<id>
        // collection write customBaseURL for all non-openRouter types, and
        // instances.update has no type gate, so a create-time openAI-only
        // rejection contradicted its own update path. Only openRouter keeps
        // the hard rejection (no remote base URL to honor). [ARCH-01] The
        // wording delegates to the shared constant in ProviderWriteRules.
        if (customBaseURL != null && !type.supportsCustomBaseURL) {
            throw RPCException(-32602, CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
        }

        // [T-ua-block-oauth-provider] customUserAgent only for apiKey providers.
        // [ARCH-01] Delegates to the shared predicate + message in
        // ProvidersCollection so Debug RPC and minis-config cannot drift.
        val customUserAgent = params.optString("customUserAgent", "").ifEmpty { null }
        if (customUserAgent != null && !customUserAgentWritable(credentialType)) {
            throw RPCException(-32602, CUSTOM_USER_AGENT_OAUTH_MESSAGE)
        }

        val instance = ProviderInstance(
            id = UUID.randomUUID().toString(),
            label = label,
            providerType = type,
            credentialType = credentialType,
            isEnabled = isEnabled,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1Suffix,
            useResponsesAPI = useResponsesAPI,
            azureMode = azureMode,
            imageEndpointMode = imageEndpointMode,
            customUserAgent = customUserAgent,
        )

        // [T-provider-no-static-seed] addInstance no longer seeds anything —
        // a fresh instance starts empty and refreshModels() populates it from
        // upstream. seedBuiltInModels is a legacy opt-in: callers that still
        // want the static LLMModel.all* list verbatim add it manually here.
        repo.addInstance(instance)
        if (seedBuiltInModels) {
            for (m in instance.providerType.builtInModels) {
                repo.addEntry(ModelEntry(providerInstanceId = instance.id, baseModel = m))
            }
        }

        // Credential write (write-only — never returned).
        val apiKey = params.optString("apiKey", "").ifEmpty { null }
        val oauthToken = params.optString("oauthToken", "").ifEmpty { null }
        val secret = apiKey ?: oauthToken
        if (secret != null) repo.saveApiKey(instance.id, secret)

        return JSONObject().put("instance", instanceToJson(repo, instance))
    }

    fun instancesUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")

        // [T-ua-block-oauth-provider] customUserAgent only applies to third-party
        // API-key providers. OAuth providers (Anthropic/Codex) ride their own
        // authentication request chain with a fixed client UA — overriding it can
        // break the handshake or be rejected upstream. Reject any write (including
        // the empty-string clear) for non-apiKey instances, mirroring the
        // minis-config writer guard in ProvidersCollection.kt. [ARCH-01] Delegates
        // to the shared predicate + message in ProvidersCollection.
        val customUserAgent: String? = if (params.has("customUserAgent")) {
            if (!customUserAgentWritable(current.credentialType)) {
                throw RPCException(-32602, CUSTOM_USER_AGENT_OAUTH_MESSAGE)
            }
            if (params.isNull("customUserAgent")) null
            else params.optString("customUserAgent", "").ifEmpty { null }
        } else current.customUserAgent

        // [da1023-C] Same openRouter guard as instancesCreate: openRouter's
        // request builder pins https://openrouter.ai/api/v1, so a non-empty
        // customBaseURL written via update would be silently ignored by the
        // request builder while the config happily persists it. A blank or
        // JSON-null value is a clear, not a write. providerType is NOT mutable
        // via update (params.providerType is never consumed — the copy below
        // doesn't touch it), so the type at write time is exactly
        // current.providerType. [ARCH-01] The wording delegates to the shared
        // constant in ProviderWriteRules (gate C2 accepts the literal or the
        // shared constant reference).
        if (params.has("customBaseURL") && !params.isNull("customBaseURL")) {
            // [deep-audit-1039 A-04] same single-source normalization as the
            // create face and the collection faces: `"   "` normalizes to the
            // clear (null) instead of persisting a blank string.
            val newBase = normalizeCustomBaseURL(params.optString("customBaseURL", ""))
            if (newBase != null && !current.providerType.supportsCustomBaseURL) {
                throw RPCException(-32602, CUSTOM_BASE_URL_OPENROUTER_MESSAGE)
            }
        }

        // [GH#68] Optional imageEndpointMode patch. Accepts the Kotlin enum
        // name or the wire SerialName (images_generations / chat_completions).
        // Forcing a non-auto mode clears the cached probe result, mirroring the
        // picker UI, so a stale auto-probe can't shadow the user's choice.
        // [fix/dv16-a A-07] The parse lives in [parseImageEndpointMode] so the
        // create face (which previously dropped this key entirely) accepts the
        // identical value domain and error wording.
        val imageEndpointMode = if (params.has("imageEndpointMode")) {
            parseImageEndpointMode(params.optString("imageEndpointMode", ""))
        } else current.imageEndpointMode

        // [da1030-C ISSUE-04] Optional azureMode patch. Same opt-in shape as
        // imageEndpointMode above (absent key = leave alone); NOT gated on
        // providerType, mirroring the instance field itself — the flag is read
        // only on the openAI arm, but the read/write faces must agree on where
        // it lives so a create→list→update round trip cannot lose it.
        val azureMode = if (params.has("azureMode")) params.optBoolean("azureMode", current.azureMode) else current.azureMode

        // [R4-R F-11] `label` was the one field on this face with neither a
        // trim nor an emptiness check — `provider.instances.update {label: ""}`
        // (or `"   "`) was written straight through, and the Settings provider
        // list rendered a card with no name. Same contract as the create face:
        // trim, then refuse empty.
        val label = if (params.has("label")) {
            // [R5-F F-F-08] Same shared trim-then-refuse rule as the create face.
            requireNonBlankParam(params, "label", "Instance label must be non-empty")
        } else current.label

        val updated = current.copy(
            label = label,
            customBaseURL = if (params.has("customBaseURL")) {
                if (params.isNull("customBaseURL")) null
                // [deep-audit-1039 A-04] shared normalization: blank ⇒ clear,
                // padded ⇒ trimmed.
                else normalizeCustomBaseURL(params.optString("customBaseURL", ""))
            } else current.customBaseURL,
            appendV1Suffix = if (params.has("appendV1Suffix")) params.optBoolean("appendV1Suffix", current.appendV1Suffix) else current.appendV1Suffix,
            useResponsesAPI = if (params.has("useResponsesAPI")) params.optBoolean("useResponsesAPI", current.useResponsesAPI) else current.useResponsesAPI,
            isEnabled = if (params.has("isEnabled")) params.optBoolean("isEnabled", current.isEnabled) else current.isEnabled,
            customUserAgent = customUserAgent,
            azureMode = azureMode,
            imageEndpointMode = imageEndpointMode,
            imageEndpointResolved = if (params.has("imageEndpointMode") && imageEndpointMode != ImageEndpointMode.auto) {
                null
            } else current.imageEndpointResolved,
        )
        repo.updateInstance(updated)

        // Credential refresh — pass `""` to clear, missing to leave alone.
        if (params.has("apiKey")) {
            val v = params.optString("apiKey", "")
            if (v.isEmpty()) repo.deleteApiKey(id) else repo.saveApiKey(id, v)
        }
        if (params.has("oauthToken")) {
            val v = params.optString("oauthToken", "")
            if (v.isEmpty()) repo.deleteApiKey(id) else repo.saveApiKey(id, v)
        }

        return JSONObject().put("instance", instanceToJson(repo, updated))
    }

    fun instancesDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an instance")
        }
        val current = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val priorEntryCount = repo.entriesFor(id).size
        repo.removeInstance(id)
        return JSONObject().apply {
            put("instanceId", id)
            put("deletedModelEntries", priorEntryCount)
            put("deleted", true)
        }
    }

    suspend fun instancesTest(context: Context, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val repo = repo(context)
        val id = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(id)
            ?: throw RPCException(-32602, "Instance not found: $id")
        val timeoutMs = params.optInt("timeoutMs", 10_000).coerceIn(1_000, 30_000)

        val baseURL = instance.effectiveBaseURL ?: when (instance.providerType) {
            ProviderType.anthropic -> "https://api.anthropic.com"
            ProviderType.gemini -> "https://generativelanguage.googleapis.com"
            ProviderType.openAI -> "https://api.openai.com/v1"
            ProviderType.openRouter -> "https://openrouter.ai/api/v1"
            ProviderType.xAI -> "https://api.x.ai/v1"
            ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
        }
        // [T-provider-key-roulette] Probe with ONE rotated key: the raw
        // multi-key string would go out verbatim in the header (or padded
        // into Gemini's ?key= query) and always fail auth — reporting a
        // perfectly healthy multi-key provider as unreachable.
        val key = repo.loadApiKey(id)?.let {
            com.rikkaminis.app.data.KeyRoulette.next(it, instance.id)
        }
        val probeURL = providerProbeUrl(instance.providerType, baseURL, key)
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        // [da1023-C] Same UA contract as ProviderFactory/CLI probes: pass the
        // instance's custom UA (OAuth instances never have one — the write
        // path rejects it) and fall back to the app default, so the probe
        // reflects the headers a real request from this instance carries.
        val builder = okhttp3.Request.Builder().url(probeURL).get()
            .applyUserAgentOverride(instance.customUserAgent)
        when (instance.providerType) {
            ProviderType.anthropic -> if (!key.isNullOrEmpty()) builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            ProviderType.openAI -> if (!key.isNullOrEmpty()) {
                // [da1030-C ISSUE-04] Azure mode switches the auth header shape
                // (`api-key: <key>` instead of `Authorization: Bearer <key>`) —
                // unconditional Bearer reported azureMode=true instances as
                // failing auth when a real request from them would succeed.
                val (name, value) = probeAuthHeader(instance.azureMode, key)
                builder.header(name, value)
            }
            ProviderType.openRouter -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            ProviderType.gemini -> { /* key in URL */ }
            // xAI: OpenAI-compat bearer header. Manual API key OR OAuth
            // access token can fill this slot.
            ProviderType.xAI -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
            // Kimi Coding: OpenAI-compat bearer (OAuth access token or key).
            ProviderType.kimiCode -> if (!key.isNullOrEmpty()) builder.header("Authorization", "Bearer $key")
        }
        val start = System.currentTimeMillis()
        try {
            val resp = client.newCall(builder.build()).execute()
            val answered = resp.isSuccessful
            val status = resp.code
            val latency = System.currentTimeMillis() - start
            val bodyStr = resp.body?.string() ?: ""
            resp.close()
            // Best-effort model count parse — Anthropic/OpenAI/OpenRouter all
            // wrap models in a top-level `data` array; Gemini uses `models`.
            val modelCount: Int = try {
                val obj = JSONObject(bodyStr)
                val arr = obj.optJSONArray("data") ?: obj.optJSONArray("models")
                arr?.length() ?: -1
            } catch (_: Exception) { -1 }
            // [R4-R F-05] A 2xx is only "reachable" when the body is actually a
            // models list. A `customBaseURL` pointing at a home page, a reverse
            // proxy's login page or a captive portal answers 200 with HTML (or
            // with unrelated JSON such as `{"status":"running"}`), and `ok =
            // resp.isSuccessful` called that a healthy provider — the caller
            // then only found out mid-conversation. The probe URL is always a
            // `/models` endpoint (see [providerProbeUrl]), so a body that is
            // neither `{data:[…]}` / `{models:[…]}` nor a top-level array is a
            // wrong-endpoint answer, not a list.
            val modelsList = answered && probePayloadIsModelsList(bodyStr)
            JSONObject().apply {
                put("ok", modelsList)
                put("httpStatus", status)
                put("latencyMs", latency)
                if (modelCount >= 0) put("reachableModelCount", modelCount)
                put(
                    "error",
                    when {
                        modelsList -> JSONObject.NULL
                        // HTTP failure: shape unchanged (`ok:false` + null error),
                        // `httpStatus` already carries the reason.
                        !answered -> JSONObject.NULL
                        else -> JSONObject().apply {
                            put("code", "invalid_value")
                            put(
                                "message",
                                "HTTP $status but the response is not a models list: ${bodyStr.take(200)}",
                            )
                        }
                    },
                )
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("httpStatus", JSONObject.NULL)
                put("latencyMs", System.currentTimeMillis() - start)
                put("error", JSONObject().apply {
                    put("code", "network")
                    put("message", e.message ?: e.javaClass.simpleName)
                })
            }
        }
    }

    private fun instanceToJson(repo: ProviderRepository, inst: ProviderInstance): JSONObject {
        val cfg = repo.config.value
        val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
        return instanceJsonCore(inst, repo.loadApiKey(inst.id)?.isNotEmpty() == true, entryCount)
    }

    // ─── Models ─────────────────────────────────────────────────────────────

    fun modelsAdd(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        // [ARCH-01] Trim before validating — shared with the minis-config
        // `models.add` face (ModelsCollection.add) via [normalizeCustomModelId].
        // Untrimmed, a whitespace-only id passed `ifEmpty` and minted a
        // blank/padded composite-key row on this surface only.
        val modelId = normalizeCustomModelId(params.optString("modelId", ""))
            ?: throw RPCException(-32602, "Missing 'modelId' param")
        val displayName = params.optString("displayName", "").ifEmpty {
            // Prettify: "deepseek-v4-pro" → "Deepseek V4 Pro" — same heuristic
            // the iOS doc describes; cheap inline impl matches the result for
            // typical hyphenated model ids without dragging in iOS-specific
            // formatting helpers.
            modelId.split('-', '_', '/').joinToString(" ") { piece ->
                if (piece.isEmpty()) "" else piece.replaceFirstChar { it.uppercase() }
            }.trim()
        }
        val contextWindow = if (params.has("contextWindow") && !params.isNull("contextWindow")) params.optInt("contextWindow") else null
        val maxOut = if (params.has("maxOutputTokens") && !params.isNull("maxOutputTokens")) params.optInt("maxOutputTokens") else null
        val supportsReasoning = if (params.has("supportsReasoning") && !params.isNull("supportsReasoning")) params.optBoolean("supportsReasoning") else null
        val inputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageInput", false)) inputModalities.add("image")
        if (params.optBoolean("supportsAudioInput", false)) inputModalities.add("audio")
        if (params.optBoolean("supportsVideoInput", false)) inputModalities.add("video")
        if (params.optBoolean("supportsPDFInput", false)) inputModalities.add("pdf")
        val outputModalities = mutableListOf<String>()
        if (params.optBoolean("supportsImageOutput", false)) outputModalities.add("image")

        val model = LLMModel(
            id = modelId,
            displayName = displayName,
            provider = canonicalModelProviderName(instance.providerType),
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
            inputModalities = inputModalities.takeIf { it.isNotEmpty() },
            outputModalities = outputModalities.takeIf { it.isNotEmpty() },
        )
        // [R5-E F-04] Same construction as ModelsCollection.add ([r3-b08
        // F-04]): the explicit capability values ALSO go into `overrides`.
        // See [addedModelEntry] for why that is the correct precedence.
        val entry = addedModelEntry(
            instanceId = instanceId,
            model = model,
            contextWindow = contextWindow,
            maxOutputTokens = maxOut,
            supportsReasoning = supportsReasoning,
        )
        // [da1023-C] Fail-closed on duplicates at the RPC surface: a repeated
        // (instance, modelId) add used to mint a second config entry whose
        // composite primary key collided (LazyColumn "Key was already used"
        // crash). addEntry now guards custom AND API-reported entries and
        // returns the canonical persisted id.
        if (repo.config.value.modelEntries.any {
                it.providerInstanceId == instanceId && it.baseModel.id == modelId
            }
        ) {
            throw RPCException(
                -32602,
                "model '$modelId' already exists on instance '${instance.label}' ($instanceId)",
            )
        }
        val persistedId = repo.addEntry(entry)
        // Return the canonical persisted entry (not the pre-persist uuid):
        // saveConfig's canonicalize pass rewrites entry.uuid to the composite
        // instanceId/modelId key.
        val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId }
            ?: entry.copy(uuid = persistedId)
        return entryToJson(persisted)
    }

    fun modelsUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        val cfg = repo.config.value
        val current = cfg.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")

        // modelId only mutable on custom entries.
        if (params.has("modelId")) {
            if (!current.isCustom) {
                throw RPCException(-32602, "built-in model ID is immutable")
            }
        }
        val newModel = if (params.has("modelId")) {
            // [da1022-E] trim + reject blank ids (same integrity gate as the
            // detail screen); a blank id would mint a dangling composite key.
            val mid = params.optString("modelId", current.baseModel.id).trim()
            if (mid.isEmpty()) {
                throw RPCException(-32602, "modelId must not be blank")
            }
            current.baseModel.copy(id = mid)
        } else current.baseModel

        val newOverrides = current.overrides.copy(
            displayName = if (params.has("displayName")) {
                if (params.isNull("displayName")) null else params.optString("displayName", "").ifEmpty { null }
            } else current.overrides.displayName,
            maxOutputTokens = if (params.has("maxOutputTokens")) {
                if (params.isNull("maxOutputTokens")) null else params.optInt("maxOutputTokens").takeIf { it > 0 }
            } else current.overrides.maxOutputTokens,
            contextWindow = if (params.has("contextWindow")) {
                if (params.isNull("contextWindow")) null else params.optInt("contextWindow").takeIf { it > 0 }
            } else current.overrides.contextWindow,
        )
        val newHidden = if (params.has("isHidden")) params.optBoolean("isHidden", current.isHidden) else current.isHidden

        val updated = current.copy(baseModel = newModel, overrides = newOverrides, isHidden = newHidden)
        // [da1023-C] updateEntry returns the canonical persisted id (null when
        // the write was rejected fail-closed) — report the persisted entry,
        // not the pre-persist copy whose uuid no longer matches the row.
        val persistedId = repo.updateEntry(updated)
            ?: throw RPCException(-32602, "Entry update rejected (blank or colliding model id): $entryId")
        val persisted = repo.config.value.modelEntries.firstOrNull { it.id == persistedId }
            ?: updated.copy(uuid = persistedId)
        return entryToJson(persisted)
    }

    fun modelsDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete an entry")
        }
        val current = repo.config.value.modelEntries.firstOrNull { it.id == entryId }
            ?: throw RPCException(-32602, "Entry not found: $entryId")
        if (!current.isCustom) {
            throw RPCException(-32602, "Built-in entries can't be deleted — set isHidden=true via provider.models.update instead")
        }
        repo.removeEntry(entryId)
        return JSONObject().apply {
            put("entryId", entryId)
            put("deleted", true)
        }
    }

    suspend fun modelsRefresh(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")

        val before = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val start = System.currentTimeMillis()
        try {
            repo.refreshModels(instance)
        } catch (e: Exception) {
            throw RPCException(-32000, "refreshModels failed: ${e.message}")
        }
        val after = repo.entriesFor(instanceId).map { it.baseModel.id }.toSet()
        val added = (after - before).size
        val disappeared = (before - after).size
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("added", added)
            put("disappeared", disappeared)
            put("total", after.size)
            put("durationMs", System.currentTimeMillis() - start)
        }
    }

    fun modelsSetAgentLoop(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val entryId = params.optString("entryId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'entryId' param")
        }
        if (!params.has("inLoop")) throw RPCException(-32602, "Missing 'inLoop' param")
        val want = params.optBoolean("inLoop", false)
        val cfg = repo.config.value
        if (cfg.modelEntries.none { it.id == entryId }) {
            throw RPCException(-32602, "Entry not found: $entryId")
        }
        val current = cfg.agentLoopModelEntryIds.toMutableList()
        current.removeAll { it == entryId }
        if (want) current.add(entryId)
        repo.setAgentLoopEntryIds(current)
        return JSONObject().apply {
            put("entryId", entryId)
            put("inLoop", want)
        }
    }

    private fun entryToJson(entry: ModelEntry): JSONObject {
        // [dv22-A4] Enriched effective model — every modelsAdd/modelsUpdate
        // response (and this helper's read-back) goes through the shared
        // resolver, so the mutation faces answer like the read faces.
        val effective = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
        return JSONObject().apply {
            put("id", entry.id)
            put("modelId", effective.id)
            put("displayName", effective.displayName)
            put("baseModelDisplayName", entry.baseModel.displayName)
            put("isCustom", entry.isCustom)
            put("isHidden", entry.isHidden)
            put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
            put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
            put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
            val ov = JSONObject()
            ov.put("displayName", entry.overrides.displayName ?: JSONObject.NULL)
            ov.put("maxOutputTokens", entry.overrides.maxOutputTokens ?: JSONObject.NULL)
            ov.put("contextWindow", entry.overrides.contextWindow ?: JSONObject.NULL)
            put("overrides", ov)
            put("userModifiedAt", entry.userModifiedAt ?: JSONObject.NULL)
        }
    }

    // ─── Groups ─────────────────────────────────────────────────────────────

    /**
     * [R4-R F-02] The ONE member-list judgement for the two group write faces.
     *
     * The faces used to share a hand-rolled loop that dropped what it did not
     * understand: an unknown entry id vanished, a duplicate id was appended
     * twice, and a non-array `memberEntryIds` (`"abc"`, `{}`) read as "no
     * members" — so `provider.groups.update` answered success while wiping the
     * group. The consequences were not cosmetic:
     *  - a duplicate id becomes `memberIds` in `ModelGroupDetailScreen`, whose
     *    `itemsIndexed(memberIds, key = { _, id -> id })` throws
     *    `IllegalArgumentException: Key … was already used` the moment the user
     *    opens the group — a reproducible crash from one RPC call;
     *  - an unknown id silently emptied a group the user still had members in.
     *
     * Judgement mirrors the canonical write faces (`GroupWriteRules
     * .parseMemberEntryIds` + `GroupsCollection.requireKnownEntryIds`): every
     * element must be a non-empty string, every id must be known (with the
     * `SystemVoiceEntries` sentinels counted as known — [dv20-a1 A-02]), and
     * duplicates collapse in place. Unknown / malformed input REFUSES with
     * `-32602` instead of being swallowed; duplicates are not information loss
     * (the order of the first occurrence is kept) and the response echoes the
     * effective `memberEntryIds`, so the caller sees exactly what was written.
     *
     * `memberEntryIds: null` keeps the documented meaning "no members" — the
     * update face reads it as the clear, which is what `optJSONArray(…) ?:
     * JSONArray()` already did.
     *
     * The *validity* judgement stays at the face ([isKnownEntryId]): the
     * `SystemVoiceEntries.isKnownGroupMemberId` predicate is single-source and
     * `provider_rpc_symmetry_check` C16 pins it inside both write faces, so a
     * refactor must not bury it here. What is shared is the *structure* —
     * string-typed, non-empty, de-duplicated in place — which is what the two
     * faces used to spell twice and drift on.
     */
    private fun readGroupMemberIds(
        params: JSONObject,
        isKnownEntryId: (String) -> Boolean,
    ): MutableList<String> {
        if (!params.has("memberEntryIds") || params.isNull("memberEntryIds")) return mutableListOf()
        val arr = params.optJSONArray("memberEntryIds")
            ?: throw RPCException(-32602, "memberEntryIds must be an array of entry ids")
        val out = ArrayList<String>(arr.length())  // ArrayList is a MutableList
        for (i in 0 until arr.length()) {
            val id = (arr.opt(i) as? String)?.trim()
                ?: throw RPCException(-32602, "memberEntryIds[$i] must be a string entry id")
            if (id.isEmpty()) throw RPCException(-32602, "memberEntryIds[$i] must be non-empty")
            // [dv20-a1 A-02] The caller's predicate is the shared
            // `SystemVoiceEntries.isKnownGroupMemberId`: the sentinels are
            // virtual members that never appear in `modelEntries`, so a face
            // deriving "known" from that list alone would call them unknown. A
            // truly unknown id is refused (fail-closed) rather than dropped.
            if (!isKnownEntryId(id)) {
                throw RPCException(-32602, "Unknown model entry id: $id")
            }
            if (id !in out) out.add(id)
        }
        return out
    }

    /**
     * [R5-F F-F-08] ONE trim-then-refuse reader for the string identity params
     * on the mutation faces (`label`, group `name`).
     *
     * The two shapes this replaces had the same defect on different halves of
     * the file: the instance faces spelled `.trim().ifEmpty { throw }` inline
     * twice ([R4-R F-11]) — two copies that can drift — and the group faces
     * spelled `.ifEmpty { throw }` with NO trim, so `provider.groups.create`
     * with `"   "` persisted a blank group name (the Settings list renders a
     * nameless row and `groups.update` can never select it by name again).
     *
     * Behaviour after this helper: whitespace-only is refused with the face's
     * own `-32602` message, and a padded value persists TRIMMED.
     */
    private fun requireNonBlankParam(params: JSONObject, key: String, message: String): String =
        params.optString(key, "").trim().ifEmpty { throw RPCException(-32602, message) }

    fun groupsCreate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val name = requireNonBlankParam(params, "name", "Missing 'name' param")
        // [dv20-a1 A-02] Known = persisted entries + the virtual System voice
        // sentinels; the predicate is the ONE shared implementation.
        val knownEntryIds = repo.config.value.modelEntries.map { it.id }.toSet()
        val members = readGroupMemberIds(params) { id ->
            SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds)
        }
        val strategy = parseStrategy(params.optString("strategy", "fallback"))
        val fallback = parseFallback(params.optString("fallbackStrategy", "default"))
        // [da1031-D ISSUE-06] The group faces had a fifth asymmetry: the create
        // path silently dropped the session-default fields, so a group made
        // over the harness could never carry defaultThinkingLevel /
        // contextLimitTokens (and `groups.list` did not surface them either —
        // fixed on the read faces in the same batch). Same parse contract as
        // the config collection (GroupWriteRules: absent = keep default, null
        // = unlimited, positive = also seeds lastContextLimitTokens; unknown
        // thinking level = refuse, not silently downgrade).
        val thinking = params.optString("defaultThinkingLevel", "")
        val parsedThinking = when {
            params.isNull("defaultThinkingLevel") || thinking.isEmpty() -> null
            else -> runCatching { ThinkingLevel.valueOf(thinking.uppercase()) }.getOrNull()
                ?: throw RPCException(-32602, "Unknown defaultThinkingLevel: $thinking")
        }
        val rawLimit = params.opt("contextLimitTokens")
        val contextLimit = when {
            rawLimit == null || rawLimit == JSONObject.NULL -> 0
            else -> (rawLimit as? Int)
                ?: throw RPCException(-32602, "contextLimitTokens must be an integer")
        }
        if (contextLimit < 0) throw RPCException(-32602, "contextLimitTokens must be >= 0")

        val group = ModelGroup(
            name = name,
            memberEntryIds = members,
            strategy = strategy,
            fallbackStrategy = fallback,
            defaultThinkingLevel = parsedThinking,
            contextLimitTokens = contextLimit.takeIf { it > 0 },
            lastContextLimitTokens = contextLimit.takeIf { it > 0 },
        )
        repo.addGroup(group)
        return JSONObject().put("group", groupToJson(repo, group))
    }

    fun groupsUpdate(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        // [fix/audit-b18 / T9-L2] repo.group(id) returns the live object inside
        // _config.value.modelGroups. Mutating it in place let concurrent readers
        // (model picker, HeadlessChatRunner.applyModelOverride, groupsList) see a
        // half-applied edit — members cleared but not yet refilled — and a throw
        // inside updateGroup left memory changed while disk stayed old. Build a
        // copy and commit once, the way the in-app Settings screen does.
        val current = repo.group(id)
            ?: throw RPCException(-32602, "Group not found: $id")

        var updated = current
        if (params.has("name")) {
            // [R5-F F-F-08] Shared trim-then-refuse: `"   "` used to pass the
            // bare `isEmpty()` check and rename the group to a blank string.
            updated = updated.copy(
                name = requireNonBlankParam(params, "name", "Group name must be non-empty"),
            )
        }
        if (params.has("memberEntryIds")) {
            // [dv20-a1 A-02] Same shared predicate as the create face.
            val knownEntryIds = repo.config.value.modelEntries.map { it.id }.toSet()
            updated = updated.copy(
                memberEntryIds = readGroupMemberIds(params) { id ->
                    SystemVoiceEntries.isKnownGroupMemberId(id, knownEntryIds)
                },
            )
        }
        if (params.has("strategy")) updated = updated.copy(strategy = parseStrategy(params.optString("strategy")))
        if (params.has("fallbackStrategy")) updated = updated.copy(fallbackStrategy = parseFallback(params.optString("fallbackStrategy")))
        // [da1031-D ISSUE-06] Same session-default fields as groupsCreate —
        // absent = keep the stored value, null / 0 = clear the limit (the
        // last-selected memory is KEPT — see the canonical write face
        // GroupWriteRules.applyContextLimitWrite below), > 0 = set both.
        // Unknown level / negative or non-integer limit = refuse (-32602),
        // never silently dropped.
        if (params.has("defaultThinkingLevel")) {
            val t = params.optString("defaultThinkingLevel", "")
            val lvl = when {
                params.isNull("defaultThinkingLevel") || t.isEmpty() -> null
                else -> runCatching { ThinkingLevel.valueOf(t.uppercase()) }.getOrNull()
                    ?: throw RPCException(-32602, "Unknown defaultThinkingLevel: $t")
            }
            updated = updated.copy(defaultThinkingLevel = lvl)
        }
        if (params.has("contextLimitTokens")) {
            val raw = params.opt("contextLimitTokens")
            val v = when {
                raw == null || raw == JSONObject.NULL -> 0
                else -> (raw as? Int)
                    ?: throw RPCException(-32602, "contextLimitTokens must be an integer")
            }
            if (v < 0) throw RPCException(-32602, "contextLimitTokens must be >= 0")
            // [fix/dv16-a A-06] The comment above used to claim this mirrored
            // GroupWriteRules.applyContextLimitWrite, but the clear branch ALSO
            // nulled lastContextLimitTokens — the canonical write face KEEPS
            // the "last selected" memory (the UI's OFF branch stashes rather
            // than erases), so an update that cleared the limit wiped the value
            // the next OFF→ON toggle should have restored (it snapped back to
            // the 128K default instead). Route through the canonical rule, via
            // the shared copy-on-mutate core: applyContextLimitWrite mutates in
            // place, and `updated` may still be the live object when no
            // earlier branch copied it.
            updated = mutatedGroupCopy(updated) { applyContextLimitWrite(it, v) }
        }
        repo.updateGroup(updated)
        return JSONObject().put("group", groupToJson(repo, updated))
    }

    fun groupsDelete(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        if (!params.optBoolean("confirm", false)) {
            throw RPCException(-32602, "Pass confirm=true to delete a group")
        }
        val current = repo.group(id)
            ?: throw RPCException(-32602, "Group not found: $id")
        val wasDefault = repo.config.value.defaultPrimaryGroupId == id
        repo.removeGroup(id)
        return JSONObject().apply {
            put("groupId", id)
            put("deleted", true)
            put("wasDefault", wasDefault)
        }
    }

    fun groupsSetDefault(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        if (!params.has("groupId")) throw RPCException(-32602, "Missing 'groupId' param")
        val id = if (params.isNull("groupId")) null else params.optString("groupId", "").ifEmpty { null }
        if (id != null && repo.group(id) == null) {
            throw RPCException(-32602, "Group not found: $id")
        }
        repo.defaultPrimaryGroupId = id
        return JSONObject().put("defaultGroupId", id ?: JSONObject.NULL)
    }

    fun groupsSetAgentLoop(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val id = params.optString("groupId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'groupId' param")
        }
        if (!params.has("inLoop")) throw RPCException(-32602, "Missing 'inLoop' param")
        val want = params.optBoolean("inLoop", false)
        if (repo.group(id) == null) throw RPCException(-32602, "Group not found: $id")
        val current = repo.config.value.agentLoopGroupIds.toMutableList()
        current.removeAll { it == id }
        if (want) current.add(id)
        repo.setAgentLoopGroupIds(current)
        return JSONObject().apply {
            put("groupId", id)
            put("inLoop", want)
        }
    }

    // [R4-R F-11] Unknown values used to collapse into the default arm, so
    // `provider.groups.create {strategy: "random"}` / `{fallbackStrategy:
    // "never"}` answered 200 and ran a policy the caller never asked for. This
    // file's own sibling key (`parseImageEndpointMode`) already refuses with
    // `-32602` + the value domain, and the canonical write faces
    // (`GroupsCollection.strategyField` / `fallbackStrategyField`) refuse too —
    // so the silent arm was an RPC-only tolerance, not a compatibility
    // fallback: the config layer never sends an unknown name. Refuse.
    internal fun parseStrategy(s: String): RoutingStrategy = when (val raw = s.trim()) {
        "fallback" -> RoutingStrategy.fallback
        "loadBalance" -> RoutingStrategy.loadBalance
        "cheapestFirst" -> RoutingStrategy.cheapestFirst
        else -> throw RPCException(
            -32602,
            "strategy must be fallback | loadBalance | cheapestFirst (got '$raw')",
        )
    }

    internal fun parseFallback(s: String): FallbackStrategy = when (val raw = s.trim()) {
        "default" -> FallbackStrategy.default
        "always" -> FallbackStrategy.always
        else -> throw RPCException(
            -32602,
            "fallbackStrategy must be default | always (got '$raw')",
        )
    }

    private fun groupToJson(repo: ProviderRepository, group: ModelGroup): JSONObject {
        val cfg = repo.config.value
        return JSONObject().apply {
            put("id", group.id)
            put("name", group.name)
            put("strategy", group.strategy.name)
            put("fallbackStrategy", group.fallbackStrategy.name)
            put("isDefault", group.id == cfg.defaultPrimaryGroupId)
            put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
            // [da1031-D ISSUE-06] Key-for-key with groupsList (read face) and
            // with the config collection's group object: the session-default
            // thinking level + context limit (null = unlimited).
            put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)
            put("contextLimitTokens", group.contextLimitTokens ?: JSONObject.NULL)
            val ids = JSONArray()
            for (m in group.memberEntryIds) ids.put(m)
            put("memberEntryIds", ids)
        }
    }
}

/**
 * [deep-audit-1025 FIX-I] Pure instance-JSON builder for the mutation RPC
 * face — key-for-key symmetric with the per-instance object that
 * `provider.instances.list` (ProviderDebugMethods) returns, including the
 * [GH#68] image-endpoint keys. Before this the mutation responses dropped
 * `imageEndpointMode`/`imageEndpointResolved`, so an automation harness that
 * mutated an instance and round-tripped the response lost the image-endpoint
 * state the debug listing still reported. Pure on purpose (no repo/Context)
 * so the key symmetry is assertable in a JVM unit test; `hasCredential` /
 * `entryCount` are computed by the caller (they need the repository).
 */
internal fun instanceJsonCore(inst: ProviderInstance, hasCredential: Boolean, entryCount: Int): JSONObject =
    JSONObject().apply {
        put("id", inst.id)
        put("label", inst.label)
        put("providerType", inst.providerType.name)
        put("credentialType", inst.credentialType.name)
        put("isEnabled", inst.isEnabled)
        put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
        put("appendV1Suffix", inst.appendV1Suffix)
        put("useResponsesAPI", inst.useResponsesAPI)
        // [da1030-C ISSUE-04] Symmetric with instancesList — the Azure auth
        // shape is part of the instance state the mutation face must
        // round-trip, or a harness writing azureMode=true sees the response
        // (and the next listing) deny it.
        put("azureMode", inst.azureMode)
        // [GH#68] Mirror the instancesList keys so a mutation response and the
        // debug listing round-trip without losing the image-endpoint state.
        put("imageEndpointMode", inst.imageEndpointMode.name)
        put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
        put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
        put("createdAt", inst.createdAt)
        put("hasCredential", hasCredential)
        put("modelEntryCount", entryCount)
    }

/**
 * [da1023-C] Single source for "does this provider type honor a custom base
 * URL": every provider arm in ProviderFactory except openRouter does —
 * openRouter's request builder pins https://openrouter.ai/api/v1. Consumed by
 * the instances.create / instances.update openRouter guards (this file) and by
 * the `customBaseURLSupported` / `appendV1SuffixConfigurable` fields of
 * `provider.types` (ProviderDebugMethods). The old per-face openAI/anthropic
 * hardcode misreported gemini/xAI/kimiCode as unsupported via
 * `provider.types` while the same instance could actually be created with a
 * custom base URL — the debug field contradicted the write path.
 */
internal val ProviderType.supportsCustomBaseURL: Boolean
    get() = this != ProviderType.openRouter

/**
 * [da1030-C ISSUE-04 → da1031-D ISSUE-07] Auth-header (name, value) pair for the
 * connectivity probe — thin wrapper over the shared single source
 * [com.rikkaminis.app.provider.openai.openAIKeyAuthHeader], keeping the probe's
 * shape assertable in a JVM unit test (ProviderRpcSymmetryTest) and letting the
 * gate `provider_rpc_symmetry_check.py` (C7) pin that `instancesTest` keys the
 * header on the instance's `azureMode` (C10 pins the delegation itself).
 *
 * The former local fork is discharged: its `ponytail:` note said the wrapper
 * would delegate once `provider/openai/OpenAIAuthHeader.kt` landed in this tree
 * (deep-audit-1030 FIX-D) — it has, so the second copy of the azure/bearer
 * ternary is gone. Signature and behaviour are unchanged.
 */
internal fun probeAuthHeader(azureMode: Boolean, key: String): Pair<String, String> =
    openAIKeyAuthHeader(azure = azureMode, token = key)

/**
 * [deep-audit-1024 ISSUE-02] Connectivity-probe URL for `provider.instances.test`.
 *
 * Pure on purpose (no Context/OkHttp) so the version-segment rules are assertable in a
 * JVM unit test — the rule drifted precisely because each provider family assembled its
 * own URL inline.
 *
 * [baseURL] is the instance's `effectiveBaseURL`, or the per-type public endpoint when the
 * instance has none. Two of the six arms carry a version segment that `effectiveBaseURL`
 * may already have supplied, and both used to append it blindly:
 *  - Gemini's segment (`/v1beta`) lives *inside* the base, so it goes through
 *    [GeminiBasePath.normalize] instead of a literal append. A custom base that already
 *    ends in `/v1beta`, or the `/v1beta/v1` shape `appendV1Suffix` (default `true`)
 *    produces from one, probed `.../v1beta/v1beta/models` → 404.
 *  - Anthropic's `effectiveBaseURL` already ends in `/v1` once `appendV1Suffix` applied,
 *    so the unconditional append probed `.../v1/v1/models` → 404.
 * The other four arms (openAI/xAI/kimiCode, plus openRouter's versionless base) already
 * guarded on `endsWith("/v1")` — the asymmetry was the defect, not a design choice.
 */
// [fix/dv16-a A-07] Single parse for the optional imageEndpointMode patch —
// shared by instancesCreate and instancesUpdate so the two write faces accept
// the identical value domain and error wording (the create face previously
// dropped the key entirely, so a create→list→update round trip could not
// express the endpoint shape the request path honors). Accepts the Kotlin
// enum name or the wire SerialName, mirroring the picker UI tokens.
internal fun parseImageEndpointMode(raw: String): ImageEndpointMode =
    when (raw) {
        "auto" -> ImageEndpointMode.auto
        "imagesGenerations", "images_generations" -> ImageEndpointMode.imagesGenerations
        "chatCompletions", "chat_completions" -> ImageEndpointMode.chatCompletions
        else -> throw RPCException(
            -32602,
            "imageEndpointMode must be auto | images_generations | chat_completions (got '$raw')",
        )
    }

internal fun providerProbeUrl(type: ProviderType, baseURL: String, key: String?): String =
    when (type) {
        ProviderType.anthropic ->
            if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
        ProviderType.gemini -> "${GeminiBasePath.normalize(baseURL)}/models?key=" + (key ?: "")
        ProviderType.openAI ->
            if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
        ProviderType.openRouter -> "$baseURL/models"
        // xAI exposes an OpenAI-compatible /models endpoint at the same base.
        ProviderType.xAI ->
            if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
        // Kimi Coding: OpenAI-compatible /models under /coding/v1.
        ProviderType.kimiCode ->
            if (baseURL.endsWith("/v1")) "$baseURL/models" else "$baseURL/v1/models"
    }

/**
 * [R4-R F-05] Is this HTTP body the models list the probe endpoint is supposed
 * to answer with?
 *
 * Pure (no I/O) so the judgement is assertable in a JVM unit test. Accepts the
 * three shapes a real `/models` endpoint answers with — `{"data":[…]}` (OpenAI,
 * Anthropic, OpenRouter, xAI, Kimi), `{"models":[…]}` (Gemini) and a top-level
 * array (some OpenAI-compatible relays) — and refuses everything else: an HTML
 * document (home page / reverse-proxy login page / captive portal), a bare
 * scalar/other JSON object (`{"status":"running"}`) and an empty body. The
 * refusal is what turns `provider.instances.test` from "the TCP connect worked"
 * into "the endpoint this URL names answered like a models endpoint".
 */
internal fun probePayloadIsModelsList(body: String): Boolean {
    val trimmed = body.trimStart(' ', '\n', '\r', '\t', '\uFEFF')
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
    val obj = runCatching { JSONObject(trimmed) }.getOrNull()
    if (obj != null) {
        return obj.optJSONArray("data") != null || obj.optJSONArray("models") != null
    }
    // A top-level array is only reachable when the body parses as an array —
    // try that before refusing. Every element has to be an object (a models
    // entry is `{"id": …}`); an empty array is accepted for parity with an
    // empty `data` array.
    val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return false
    return (0 until arr.length()).all { arr.opt(it) is JSONObject }
}

/**
 * [R5-E F-04] The entry construction shared by the two custom-model add faces —
 * `provider.models.add` ([ProviderMutationMethods.modelsAdd]) and
 * `minis-config models.add` ([ModelsCollection.add], which already spelled
 * this shape at [r3-b08 F-04]).
 *
 * WHY the explicit capability values also go into `overrides`: they used to
 * live only on `baseModel`, and the enrichment pass prefers the registry —
 * `ModelsDevApi.applyDevData` copies `devModel.contextWindow ?:
 * model.contextWindow` (same for maxOutputTokens / supportsReasoning). For any
 * id models.dev knows, the caller's hand-typed value was silently replaced by
 * the catalog default on the very next read while the add call answered
 * ok:true. `overrides` are applied AFTER enrichment
 * (`effectiveEntryModel` = enrich(baseModel).withOverrides(...)), which is the
 * precedence a hand-typed value deserves. ONE shared constructor: the parity
 * discipline the two faces already follow for ids ([normalizeCustomModelId])
 * and names ([canonicalModelProviderName]), extended to the override block.
 */
internal fun addedModelEntry(
    instanceId: String,
    model: LLMModel,
    contextWindow: Int?,
    maxOutputTokens: Int?,
    supportsReasoning: Boolean?,
): ModelEntry = ModelEntry(
    providerInstanceId = instanceId,
    baseModel = model,
    overrides = ModelOverrides(
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsReasoning = supportsReasoning,
    ),
    isCustom = true,
)
