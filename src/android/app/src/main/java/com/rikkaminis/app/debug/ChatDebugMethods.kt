package com.rikkaminis.app.debug

import android.content.Context
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.data.AnthropicReplayPublicProjection
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.service.SessionActivityTracker
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only `chat.*` RPC handlers — Phase 1.
 *
 * Mirrors the `chat.sessions.*` / `chat.messages.list` / `chat.models.list`
 * shape from `docs/debug-server-api.md`. Mutation handlers (`chat.prompt`,
 * `chat.retry`, `chat.session.cancel`, `chat.session.delete`) land in Phase 2
 * once the headless ChatViewModel runner is wired.
 */
internal object ChatDebugMethods {

    private fun chat(context: Context): ChatRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).chatRepository

    private fun provider(context: Context): ProviderRepository =
        (context.applicationContext as? MinisApp
            ?: throw RPCException(-32000, "MinisApp not initialized")).providerRepository

    suspend fun sessionsList(context: Context, params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 50).coerceIn(1, 500)
        val includeEmpty = params.optBoolean("includeEmpty", false)
        val repo = chat(context)

        val all = repo.dao.listSessions()
        val arr = JSONArray()
        var emitted = 0
        val provRepo = provider(context)

        for (session in all) {
            if (emitted >= limit) break
            // Emptiness probe — [fix/dv23-a5] this used to be
            // `repo.dao.lastMessageParts(session.id)`, i.e. a raw
            // `SELECT parts_json … LIMIT 1` per session. Two things were wrong
            // with that: it materialised up to MAX_MESSAGE_PARTS_JSON_BYTES of
            // blob per session only to compare the result with null, and it
            // bypassed the repository's CursorWindow-tolerant read ceiling, so
            // one >2 MB historical row aborted the whole RPC with
            // SQLiteBlobTooBigException (Issue #17). `repo.messageCount` is the
            // pint over COUNT(*) and needs its own pony.
            // ponytail: per-session COUNT is N queries (the list is capped at
            //   `limit`, default 50) | 天花板: sessions-per-list grows past a
            //   few hundred and the N+1 plan dominates | 升级触发: list RPC
            //   latency regresses and `ChatDao.messageCountsPerSession()` (one
            //   Map<String, Int>, already exists) becomes the cheaper read.
            if (!includeEmpty && repo.messageCount(session.id) == 0) continue

            arr.put(sessionCoreJson(session, provRepo))
            emitted++
        }
        return JSONObject().apply {
            put("count", emitted)
            put("sessions", arr)
        }
    }

    suspend fun sessionsGet(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val repo = chat(context)
        val session = repo.dao.getSession(sessionId)
            ?: throw RPCException(-32602, "Session not found")
        val provRepo = provider(context)
        val messageCount = repo.messageCount(sessionId)
        return sessionCoreJson(session, provRepo).apply {
            put("messageCount", messageCount)
        }
    }

    /**
     * The session metadata both `chat.sessions.list` and `chat.sessions.get`
     * publish — single-sourced on purpose [da1032-B]. The two RPCs used to
     * build their objects independently and drifted apart: `sessionsGet` was
     * missing `pinnedAt` / `lastMessagePreview`, so the same session looked
     * different depending on which entry point the caller used. Callers append
     * their own extra fields on top (the single-session view adds
     * `messageCount`).
     *
     * Key order matches the historical `sessionsList` shape; `messageCount` is
     * still only emitted by `sessionsGet` (adding it to the list view would
     * turn the list into an N+1 COUNT query).
     */
    private fun sessionCoreJson(session: ChatSessionEntity, provRepo: ProviderRepository): JSONObject =
        JSONObject().apply {
            put("id", session.id)
            put("title", session.title ?: JSONObject.NULL)
            put("modelId", session.modelId)
            put("modelName", resolveModelDisplay(provRepo, session.modelId))
            put("source", session.source ?: JSONObject.NULL)
            put("isRunning", SessionActivityTracker.isActive(session.id))
            put("memoryEnabled", session.memoryEnabled == 1)
            put("category", session.category ?: JSONObject.NULL)
            put("pinnedAt", session.pinnedAt ?: JSONObject.NULL)
            put("lastMessagePreview", session.lastMessage ?: JSONObject.NULL)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
        }

    suspend fun messagesList(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val limit = params.optInt("limit", 200).coerceIn(1, 1000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val rolesArr = params.optJSONArray("roles")
        val rolesFilter: Set<String>? = if (rolesArr != null) {
            (0 until rolesArr.length()).map { rolesArr.optString(it) }
                .filter { it.isNotEmpty() }
                .toSet()
                .takeIf { it.isNotEmpty() }
        } else null
        val includeTools = params.optBoolean("includeTools", true)
        val includeReasoning = params.optBoolean("includeReasoning", false)

        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val all = repo.loadMessages(sessionId)
        val filtered = if (rolesFilter == null) all else all.filter { it.role in rolesFilter }
        val sliced = filtered.drop(offset).take(limit)

        val arr = JSONArray()
        for (m in sliced) arr.put(messageToJson(m, includeTools, includeReasoning))
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totalCount", filtered.size)
            put("count", arr.length())
            put("messages", arr)
        }
    }

    /**
     * Project one stored `parts_json` row into the RPC message shape.
     *
     * [internal] rather than private, and NOT inlined into [messagesList], for
     * one reason: this is the whole cross-boundary protocol between the on-disk
     * `ContentPart` serial names and the RPC view, and it is pure — a JVM test
     * can drive it with a real [MessageEntity] and assert the classification
     * [da1032-B]. The serial-name set is the `@SerialName` set in
     * [com.rikkaminis.app.data.model.ContentPart]; the legacy underscore
     * spellings stay accepted so rows written by older builds still surface.
     */
    internal fun messageToJson(m: MessageEntity, includeTools: Boolean, includeReasoning: Boolean): JSONObject {
        val obj = JSONObject().apply {
            put("id", m.id)
            put("role", m.role)
            put("createdAt", m.createdAt)
        }
        // Strip private replay BEFORE the nested RPC.parts view or raw fallback.
        // Ordinary unknown parts and legacy unstructured rows stay readable.
        val projection = AnthropicReplayPublicProjection.project(m.partsJson)
        val parts = projection.parts
        if (parts != null) {
            obj.put("parts", parts)
            val sb = StringBuilder()
            val tools = JSONArray()
            val attachments = JSONArray()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                when (p.optString("type")) {
                    "text" -> sb.append(p.optString("value", ""))
                    "toolUse", "tool_call", "tool_use" -> if (includeTools) tools.put(p)
                    "toolResult", "tool_result" -> if (includeTools) tools.put(p)
                    "mediaRef", "image", "file", "attachment" -> attachments.put(p)
                }
            }
            if (sb.isNotEmpty()) obj.put("content", sb.toString())
            if (tools.length() > 0) obj.put("toolCalls", tools)
            if (attachments.length() > 0) obj.put("attachments", attachments)
        } else {
            // Legacy/unstructured payload — return raw.
            obj.put("content", projection.partsJson)
        }
        m.tokenUsage?.let {
            obj.put("tokenUsage", try { JSONObject(it) } catch (_: Exception) { it })
        }
        if (includeReasoning) {
            obj.put("reasoningContent", m.reasoningContent ?: JSONObject.NULL)
        }
        return obj
    }

    suspend fun sessionsUsage(context: Context, params: JSONObject): JSONObject {
        val sessionId = params.optString("sessionId", "").ifEmpty {
            throw RPCException(-32602, "Missing 'sessionId' param")
        }
        val perTurn = params.optBoolean("perTurn", false)
        val repo = chat(context)
        repo.dao.getSession(sessionId) ?: throw RPCException(-32602, "Session not found")
        val msgs = repo.loadMessages(sessionId)

        var totalIn = 0L
        var totalOut = 0L
        var totalCacheCreate = 0L
        var totalCacheRead = 0L
        var totalReasoning = 0L
        var turnCount = 0
        val turnsArr = JSONArray()

        for (m in msgs) {
            if (m.role != "assistant") continue
            val raw = m.tokenUsage ?: continue
            val usage = try { JSONObject(raw) } catch (_: Exception) { continue }
            val ti = usage.optLong("inputTokens", usage.optLong("input_tokens", 0))
            val to = usage.optLong("outputTokens", usage.optLong("output_tokens", 0))
            val tcc = usage.optLong("cacheCreationTokens", usage.optLong("cache_creation_tokens", 0))
            val tcr = usage.optLong("cacheReadTokens", usage.optLong("cache_read_tokens",
                usage.optLong("prompt_cache_hit_tokens", 0)))
            val tr = usage.optLong("reasoningTokens", usage.optLong("reasoning_tokens", 0))
            totalIn += ti; totalOut += to; totalCacheCreate += tcc; totalCacheRead += tcr; totalReasoning += tr
            turnCount++
            if (perTurn) {
                turnsArr.put(JSONObject().apply {
                    put("messageId", m.id)
                    put("createdAt", m.createdAt)
                    put("inputTokens", ti)
                    put("outputTokens", to)
                    put("cacheCreationTokens", tcc)
                    put("cacheReadTokens", tcr)
                    put("reasoningTokens", tr)
                })
            }
        }
        val totals = JSONObject().apply {
            put("inputTokens", totalIn)
            put("outputTokens", totalOut)
            put("cacheCreationTokens", totalCacheCreate)
            put("cacheReadTokens", totalCacheRead)
            put("reasoningTokens", totalReasoning)
            put("turnCount", turnCount)
        }
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("totals", totals)
            if (perTurn) put("turns", turnsArr)
        }
    }

    fun modelsList(context: Context, params: JSONObject): JSONObject {
        val includeHidden = params.optBoolean("includeHidden", false)
        val includeDisabled = params.optBoolean("includeDisabled", false)
        val cfg = provider(context).config.value
        val enabledIds = cfg.instances.filter { it.isEnabled || includeDisabled }.map { it.id }.toSet()

        val entriesArr = JSONArray()
        for (entry in cfg.modelEntries) {
            if (entry.providerInstanceId !in enabledIds) continue
            if (!includeHidden && entry.isHidden) continue
            val inst = cfg.instances.find { it.id == entry.providerInstanceId } ?: continue
            // [dv22-A4] Effective = enriched + overrides via the shared
            // resolver (same one as minis-model-use): the raw `entry.model`
            // skipped models.dev, so every built-in reported null windows
            // and no vision/audio/pdf capability here.
            val effective = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
            val inputs = effective.inputModalities ?: emptyList()
            entriesArr.put(JSONObject().apply {
                put("id", entry.id)
                put("modelId", effective.id)
                put("modelName", effective.displayName)
                put("providerInstanceId", inst.id)
                put("providerInstanceName", inst.label)
                put("providerType", inst.providerType.name)
                put("supportsReasoning", effective.supportsReasoning ?: JSONObject.NULL)
                put("supportsImages", "image" in inputs)
                put("supportsVideo", "video" in inputs)
                put("supportsAudio", "audio" in inputs)
                put("supportsPDF", "pdf" in inputs)
                put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                put("maxOutputTokens", effective.maxOutputTokens ?: JSONObject.NULL)
                put("hidden", entry.isHidden)
            })
        }

        val groupsArr = JSONArray()
        for (group in cfg.modelGroups) {
            val ids = JSONArray()
            for (memberId in group.memberEntryIds) ids.put(memberId)
            groupsArr.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("strategy", group.strategy.name)
                put("isDefault", group.id == cfg.defaultPrimaryGroupId)
                put("memberEntryIds", ids)
            })
        }

        return JSONObject().apply {
            put("defaultGroupId", cfg.defaultPrimaryGroupId ?: JSONObject.NULL)
            put("groupCount", groupsArr.length())
            put("entryCount", entriesArr.length())
            put("groups", groupsArr)
            put("entries", entriesArr)
        }
    }

    /**
     * Resolve a model reference (bare `modelId` OR a composite
     * `providerInstanceId:modelId` entry id) to its display name.
     *
     * Single-sourced [da1032-B]: both debug RPC surfaces (`chat.models.list`
     * consumers, `chat.sessions.*`, and the mutation side in
     * [ChatMutationMethods]) must agree on what a model reference means. The
     * mutation copy used to carry only the bare-id predicate, so a composite
     * entry id came back as the raw string while the read side resolved it
     * properly.
     */
    internal fun resolveModelDisplay(repo: ProviderRepository, modelId: String): String {
        val cfg = repo.config.value
        val entry = cfg.modelEntries.firstOrNull { it.baseModel.id == modelId || it.id == modelId }
            ?: return modelId
        return entry.model.displayName
    }
}
