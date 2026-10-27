package com.rikkaminis.app.debug

import android.content.Context
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.repository.ProviderRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only `provider.*` and `provider.groups.*` RPC handlers — Phase 1.
 *
 * Mirrors the read shape from `docs/debug-server-api.md`. Mutation handlers
 * (`provider.instances.create/update/delete/test`, `provider.models.*`,
 * `provider.groups.create/update/delete/setDefault`) land in Phase 2.
 *
 * Field naming is kept 1:1 with the iOS docs so the same automation scripts
 * work cross-platform; Android-only fields that have no iOS analogue (e.g.
 * `useResponsesAPI` on a single instance) are surfaced inline.
 */
internal object ProviderDebugMethods {

    private fun repo(context: Context): ProviderRepository {
        val app = context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")
        return app.providerRepository
    }

    fun types(): JSONObject {
        val arr = JSONArray()
        for (type in ProviderType.values()) {
            arr.put(typeSpec(type))
        }
        return JSONObject().put("types", arr)
    }

    private fun typeSpec(type: ProviderType): JSONObject {
        // Cross-reference iOS doc shape. Some fields don't have a direct
        // Android equivalent and are reported as null/absent rather than
        // synthesized — keeps "what's missing" honest for callers.
        val supportedCreds = JSONArray().apply {
            put("apiKey")
            if (type == ProviderType.anthropic || type == ProviderType.openAI) put("oauth")
        }
        val builtInIds = JSONArray()
        for (m in type.builtInModels) builtInIds.put(m.id)

        // T192: Anthropic now supports custom base URL too. Since FIX-I the
        // predicate is single-sourced (ProviderType.supportsCustomBaseURL,
        // [da1023-C] semantics: every type except openRouter), so gemini/xAI/
        // kimiCode now report supported as well — matching what the write path
        // (instancesCreate guard / ProvidersCollection) actually accepts.
        val customBaseSupported = type.supportsCustomBaseURL
        return JSONObject().apply {
            put("id", type.name)
            put("displayName", type.displayName)
            put("supportedCredentials", supportedCreds)
            put("oauthFlow", when (type) {
                ProviderType.anthropic -> "claude"
                ProviderType.openAI -> "codex"
                else -> JSONObject.NULL
            })
            put("defaultBaseURL", defaultBaseURL(type))
            put("customBaseURLSupported", customBaseSupported)
            put("appendV1SuffixConfigurable", customBaseSupported)
            put("builtInModelIds", builtInIds)
        }
    }

    private fun defaultBaseURL(type: ProviderType): String = when (type) {
        ProviderType.anthropic -> "https://api.anthropic.com"
        ProviderType.gemini -> "https://generativelanguage.googleapis.com"
        ProviderType.openAI -> "https://api.openai.com"
        ProviderType.openRouter -> "https://openrouter.ai/api/v1"
        ProviderType.xAI -> "https://api.x.ai/v1"
        ProviderType.kimiCode -> "https://api.kimi.com/coding/v1"
    }

    fun instancesList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeDisabled = params.optBoolean("includeDisabled", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (inst in cfg.instances) {
            if (!includeDisabled && !inst.isEnabled) continue
            val hasKey = repo.loadApiKey(inst.id)?.isNotEmpty() == true
            val entryCount = cfg.modelEntries.count { it.providerInstanceId == inst.id }
            arr.put(JSONObject().apply {
                put("id", inst.id)
                put("label", inst.label)
                put("providerType", inst.providerType.name)
                put("credentialType", inst.credentialType.name)
                put("isEnabled", inst.isEnabled)
                put("customBaseURL", inst.customBaseURL ?: JSONObject.NULL)
                put("appendV1Suffix", inst.appendV1Suffix)
                put("useResponsesAPI", inst.useResponsesAPI)
                // [da1030-C ISSUE-04] Azure auth shape was invisible on this
                // face: the debug listing (and the mutation response built by
                // instanceJsonCore) reported 14 keys without it, so a harness
                // could not tell a Bearer instance from an api-key one.
                put("azureMode", inst.azureMode)
                // [GH#68] Surface the image-endpoint picker state so harnesses
                // can verify persistence across restarts.
                put("imageEndpointMode", inst.imageEndpointMode.name)
                put("imageEndpointResolved", inst.imageEndpointResolved?.name ?: JSONObject.NULL)
                put("customUserAgent", inst.customUserAgent ?: JSONObject.NULL)
                put("createdAt", inst.createdAt)
                put("hasCredential", hasKey)
                put("modelEntryCount", entryCount)
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("instances", arr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val includeHidden = params.optBoolean("includeHidden", false)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val all = repo.config.value.modelEntries.filter { it.providerInstanceId == instanceId }
        val visible = if (includeHidden) all else all.filter { !it.isHidden }
        val arr = JSONArray()
        for (entry in visible) {
            // [dv22-A4] Enriched effective model (models.dev first, the
            // user's overrides second) — the same resolver the model-use
            // faces use, so this view can no longer disagree with them.
            val effective: LLMModel =
                com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
            arr.put(JSONObject().apply {
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
            })
        }
        return JSONObject().apply {
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("count", arr.length())
            put("entries", arr)
        }
    }

    /**
     * Export a provider instance + its model entries + stored credential as a
     * portable JSON document. Mirrors the UI Share button. The API key is
     * base64-wrapped (same as ProviderRepository.exportInstanceJSON) — that's
     * to survive JSON-string escaping of arbitrary key bytes, not for
     * security; callers of the 5321 RPC are trusted by definition.
     */
    fun export(context: Context, params: JSONObject): JSONObject {
        val instanceId = params.optString("instanceId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'instanceId' param")
        }
        val repo = repo(context)
        val instance = repo.instance(instanceId)
            ?: throw RPCException(-32602, "Instance not found: $instanceId")
        val raw = repo.exportInstanceJSON(instanceId)
            ?: throw RPCException(-32000, "Export failed for instance $instanceId")
        // Wrap the underlying JSON document under a versioned envelope so
        // forward-compatible importers can detect the schema explicitly.
        val payload = JSONObject(raw)
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("platform", "android")
            put("instanceId", instanceId)
            put("instanceLabel", instance.label)
            put("config", payload)
        }
    }

    /**
     * Import a provider instance from an export envelope (or the raw inner
     * config JSON, for cross-platform compatibility). The implementation
     * always allocates a new instance UUID — duplicate label is auto-renamed
     * by the repository.
     */
    fun import(context: Context, params: JSONObject): JSONObject {
        // Accept either the new envelope (`{version,config:{...}}`) or the
        // raw legacy config JSON. Be liberal on input — the export contract
        // is symmetric on the value side, and harnesses that capture from
        // older builds should still load.
        val raw = params.optString("configJson", "").ifEmpty {
            throw RPCException(-32602, "Missing 'configJson' param")
        }
        val parsed = try { JSONObject(raw) } catch (e: Exception) {
            throw RPCException(-32602, "Invalid configJson: ${e.message}")
        }
        val configJson = if (parsed.has("config") && parsed.optJSONObject("config") != null) {
            parsed.optJSONObject("config")!!.toString()
        } else {
            raw
        }
        val repo = repo(context)
        val resolvedLabel = repo.importInstanceJSON(configJson)
            ?: throw RPCException(-32000, "Import failed — invalid or unsupported configJson")
        // Find the freshly-created instance so we can return its id.
        val newInstance = repo.config.value.instances.lastOrNull { it.label == resolvedLabel }
            ?: throw RPCException(-32000, "Import succeeded but instance lookup failed")
        return JSONObject().apply {
            put("instanceId", newInstance.id)
            put("instanceLabel", newInstance.label)
            put("modelEntryCount", repo.entriesFor(newInstance.id).size)
            put("success", true)
        }
    }

    fun groupsList(context: Context, params: JSONObject): JSONObject {
        val repo = repo(context)
        val includeMembers = params.optBoolean("includeMembers", true)
        val cfg = repo.config.value
        val arr = JSONArray()
        for (group in cfg.modelGroups) {
            val isDefault = group.id == cfg.defaultPrimaryGroupId
            val obj = JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                put("isDefault", isDefault)
                put("inAgentLoop", group.id in cfg.agentLoopGroupIds)
                // [da1031-D ISSUE-06] Session-default fields the harness could
                // read on no face: the group's thinking level and its context
                // limit (null = unlimited). Same two keys the mutation face's
                // groupToJson emits and minis-config's `groups` collection
                // exposes (snake_case there), so all three read faces agree.
                put("defaultThinkingLevel", group.defaultThinkingLevel?.name ?: JSONObject.NULL)
                put("contextLimitTokens", group.contextLimitTokens ?: JSONObject.NULL)
                val ids = JSONArray()
                for (id in group.memberEntryIds) ids.put(id)
                put("memberEntryIds", ids)
            }
            if (includeMembers) {
                val members = JSONArray()
                for (memberId in group.memberEntryIds) {
                    // [dv20-a1 A-02] System voice members are virtual — resolve
                    // them through the shared sentinel so the harness sees the
                    // member (with the synthetic "System" provider label)
                    // instead of a silently missing row. `system` marks the row
                    // so a caller can tell it from a persisted entry.
                    val persisted = cfg.modelEntries.find { it.id == memberId }
                    val entry = persisted ?: SystemVoiceEntries.resolve(memberId) ?: continue
                    val instLabel = cfg.instances.find { it.id == entry.providerInstanceId }?.label
                        ?: SystemVoiceEntries.syntheticInstance().label
                    members.put(JSONObject().apply {
                        put("entryId", entry.id)
                        put("modelId", entry.baseModel.id)
                        put("displayName", entry.model.displayName)
                        put("providerLabel", instLabel)
                        put("system", persisted == null)
                    })
                }
                obj.put("members", members)
            }
            arr.put(obj)
        }
        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("count", arr.length())
            put("groups", arr)
        }
    }
}
