package com.openminis.app.backup

import android.util.Log
import com.openminis.app.config.ConfigAccess
import com.openminis.app.config.ConfigRegistry
import com.openminis.app.config.ConfigValue
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * Local export/import of app configuration.
 *
 * Deliberately built on top of [ConfigRegistry] rather than enumerating the
 * dozen-plus SharedPreferences files by hand: every settable field already
 * declares its own storage location and carries read()/write(), so a backup is
 * just "walk the registry and read" / "walk the payload and write". New config
 * fields are picked up for free; a hand-rolled key list would silently rot.
 *
 * Providers are the one thing NOT modelled as plain registry scalars. They keep
 * their own richer serialization (models, overrides, base64 credentials) in
 * [ProviderRepository.exportInstanceJSON], so backups embed that verbatim
 * instead of reimplementing it.
 *
 * Scope is local-file only — no cloud, no WebDAV. Anything that would need an
 * interactive step to restore (re-authorizing an expired OAuth login, resolving
 * a binding to a group that no longer exists) is reported in
 * [ImportResult.skipped] rather than silently guessed at.
 */
object ConfigBackup {
    private const val TAG = "ConfigBackup"

    /** Bumped only on breaking payload changes; readers reject newer majors. */
    const val FORMAT_VERSION = 1

    /**
     * Registry scopes included in a backup, i.e. the settings a user expects to
     * carry to a new install. Everything else in the registry is either
     * device-local state (session ids, cached metadata) or derived, and
     * restoring it would do more harm than good.
     */
    private val BACKED_UP_SCOPES = setOf(
        "appearance",   // theme, font scale, chat bubble/background look
        "chat",         // composer + rendering preferences
        "background",   // background image / effect settings
        "defaults",     // default model group, agent-loop entries and groups
        "soul",         // SOUL.md persona fields
        "memory",       // memory feature toggles
        "logs",         // log retention preferences
        "runtime",      // agent-runtime knobs (e.g. max concurrent sessions)
    )
    // NOTE: `session.*` is deliberately NOT backed up. Despite the dot-path
    // prefix it is not a persisted preference — session.primaryModel /
    // session.thinkingLevel read and write the *currently foregrounded chat*
    // via ChatViewModelStore.activeSessionId. On the settings screen where a
    // backup is taken or restored there is no active session, so the writer
    // throws "No active session" and the reader returns empty/null. Carrying
    // them only produced guaranteed skip entries on every restore.

    /** Outcome of an import: what landed, and what needs the user's attention. */
    data class ImportResult(
        val fieldsApplied: Int,
        val providersImported: Int,
        /** Model groups recreated (with member entry ids remapped to this install). */
        val groupsImported: Int,
        /** Environment variables restored (keys + notes; values if the
         *  backup carried secrets, empty-value stub otherwise). */
        val envVarsImported: Int,
        /** Skills restored, including their bundled files. */
        val skillsImported: Int,
        /** Memory files restored (GLOBAL.md + daily logs). */
        val memoryFilesImported: Int,
        /** MCP servers restored (OAuth credentials always need re-auth). */
        val mcpServersImported: Int,
        /** Chat sessions restored (metadata + text-only parts). */
        val chatSessionsImported: Int,
        /** Chat messages restored (text-only parts). */
        val chatMessagesImported: Int,
        /** Human-readable "path: why" lines for anything deliberately not applied. */
        val skipped: List<String>,
        /** True when the payload carried credentials (affects the post-import hint). */
        val hadSecrets: Boolean,
        /** [fix-audit-p0-4] Non-null when the import failed mid-way: some
         *  stages already landed (counts above) but the restore did not
         *  complete. Callers must surface this as a partial restore, not a
         *  normal one — and should offer the pre-restore snapshot rollback. */
        val fatal: String? = null,
    )

    /**
     * Serialize current settings to a backup document.
     *
     * @param includeSecrets when false, API keys and OAuth tokens are stripped.
     *   Defaults to true: a restore that drops every credential leaves the user
     *   retyping keys by hand, which defeats the point of a backup. Callers are
     *   expected to warn before writing the file somewhere shareable.
     */
    suspend fun export(
        providerRepo: ProviderRepository,
        includeSecrets: Boolean = true,
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
        chatRepo: ChatRepository? = null,
        chatWindowDays: Int = 90,
        includeHiddenModels: Boolean = true,
        memoryFileNames: Set<String>? = null,
    ): String {
        val registry = ConfigRegistry.get()

        val fields = JSONObject()
        var readFailures = 0
        for (path in registry.allVisibleFieldPaths()) {
            val field = registry.resolveField(path) ?: continue
            if (field.scope !in BACKED_UP_SCOPES) continue
            // READONLY fields would fail on the way back in, so there is no
            // point carrying them. Feature-unavailable fields likewise refuse
            // reads on this device.
            if (field.access != ConfigAccess.READWRITE) continue
            if (field.unavailableReason != null) continue
            try {
                val value = field.read().let { if (includeSecrets) it else it.redactingSecrets() }
                // Store each value as its JSON *string* form and decode with
                // ConfigValue.decode() on the way back in. ConfigValue's
                // Any-tree conversion is private, and going through the
                // documented jsonString()/decode() pair keeps the round-trip
                // symmetric without reaching into its internals.
                fields.put(path, value.jsonString())
            } catch (t: Throwable) {
                // A single unreadable field must not sink the whole backup.
                readFailures++
                Log.w(TAG, "export: skipped unreadable field $path: ${t.message}")
            }
        }

        val providers = JSONArray()
        for (instance in providerRepo.instances) {
            val json = providerRepo.exportInstanceJSON(instance.id) ?: continue
            val obj = try {
                JSONObject(json)
            } catch (t: Throwable) {
                Log.w(TAG, "export: unparseable provider ${instance.id}: ${t.message}")
                continue
            }
            if (!includeSecrets) {
                for (key in SECRET_PROVIDER_KEYS) obj.remove(key)
            }
            // [T-backup-group-idmap] exportInstanceJSON serializes model entries
            // by (modelId, displayName) but drops their uuids, and importInstance
            // JSON re-mints a fresh uuid for every entry. Model groups reference
            // entries by uuid, and defaults.primaryGroup references a group by
            // id — so without a mapping those references dangle on restore and
            // every group-typed default is rejected. Carry the source entry
            // uuids here, in the SAME order exportInstanceJSON emits its `models`
            // array (the underlying list's append order — visible and hidden
            // interleaved, NOT visible-then-hidden), so import can pair old→new
            // uuid positionally. `_`-prefixed to signal a backup-layer annotation;
            // importInstanceJSON ignores unknown keys, so the provider wire
            // format is untouched.
            //
            // [T-sync-hide-prune] When includeHiddenModels=false (multi-device
            // auto-sync snapshots), hidden non-custom models are part of the
            // provider's *public catalog cache*, not the user's state — they are
            // re-pullable from the provider's /models endpoint and account for
            // the bulk of the payload (an OpenRouter catalog can be hundreds of
            // entries). Dropping them shrinks the snapshot to (connection +
            // visible models + custom models) + their overrides, and keeps the
            // sibling device from learning models the user never selected. The
            // `models` array and `_entryIds` MUST be filtered in lockstep — they
            // are positional-paired (in the same append-order as exportInstance
            // JSON emits its `models` array) for import's
            // old→new uuid remap. [filterHiddenModels] handles both sides.
            val entryIds = JSONArray()
            if (includeHiddenModels) {
                for (id in orderedEntryIds(providerRepo, instance.id)) entryIds.put(id)
            } else {
                val dropped = dropHiddenModelIds(providerRepo, instance.id)
                val srcModels = obj.optJSONArray("models")
                if (srcModels != null) {
                    val visibleModels = JSONArray()
                    for (k in 0 until srcModels.length()) {
                        val m = srcModels.getJSONObject(k)
                        if (dropped.contains(m.optString("modelId", ""))) continue
                        visibleModels.put(m)
                    }
                    obj.put("models", visibleModels)
                }
                for (id in orderedEntryIds(providerRepo, instance.id)) {
                    val entry = providerRepo.config.value.modelEntries
                        .find { it.id == id } ?: continue
                    val modelId = entry.baseModel.id
                    if (dropped.contains(modelId)) continue
                    entryIds.put(id)
                }
            }
            obj.put("_entryIds", entryIds)
            providers.put(obj)
        }

        // [T-backup-group-idmap] Model groups are NOT part of a single provider's
        // export (they span providers), so they are backed up here as a distinct
        // top-level array. member entry ids are the SOURCE uuids; import remaps
        // them through the per-provider old→new entry map before creating groups.
        val groups = JSONArray()
        for (group in providerRepo.config.value.modelGroups) {
            groups.put(JSONObject().apply {
                put("id", group.id)
                put("name", group.name)
                put("memberEntryIds", JSONArray().apply {
                    for (eid in group.memberEntryIds) put(eid)
                })
                put("strategy", group.strategy.name)
                put("fallbackStrategy", group.fallbackStrategy.name)
                group.defaultThinkingLevel?.let { put("defaultThinkingLevel", it.name) }
                group.contextLimitTokens?.let { put("contextLimitTokens", it) }
                group.lastContextLimitTokens?.let { put("lastContextLimitTokens", it) }
            })
        }

        // Environment variables live in EnvVarRepository (metadata in a JSON
        // file, values in encrypted prefs) — NOT in the flat ConfigRegistry
        // field space, so they need their own export pass much like providers.
        // Values are credentials, so they ride the same includeSecrets gate as
        // provider apiKeys: without secrets we carry key+note only and the user
        // refills the value after import.
        val envVars = JSONArray()
        if (envVarRepo != null) {
            for (entry in envVarRepo.entries.value) {
                envVars.put(JSONObject().apply {
                    put("key", entry.key)
                    put("note", entry.note)
                    if (includeSecrets) {
                        envVarRepo.getValue(entry.key)?.let { put("value", it) }
                    }
                })
            }
        }

        // Skills are a directory tree per skill (SKILL.md plus bundled
        // scripts/, references/, assets/), mirrored by a row in skills.db. The
        // registry models none of it, so — like providers — they need a
        // dedicated pass. We embed the whole directory as a base64 zip rather
        // than SKILL.md alone: a skill whose scripts are missing still *looks*
        // installed but fails the moment it runs, which is a worse outcome than
        // a larger backup file. [T-backup-skills]
        val skills = JSONArray()
        if (skillRepo != null) {
            for (skill in skillRepo.skills.value) {
                val entry = JSONObject().apply {
                    put("id", skill.id)
                    put("name", skill.name)
                    put("description", skill.description)
                    put("version", skill.version)
                    put("importSource", skill.importSource.value)
                    skill.sourceURL?.let { put("sourceURL", it) }
                    put("isEnabled", skill.isEnabled)
                    put("installedAt", skill.installedAt)
                    put("updatedAt", skill.updatedAt)
                    put("useCount", skill.useCount)
                }
                // Prefer the full archive; fall back to SKILL.md text so a skill
                // whose zip could not be produced is still recoverable in part.
                // [fix-audit-p1-2] A skill archive over MAX_SKILL_ARCHIVE_BYTES
                // also degrades to SKILL.md-only: the payload is Base64'd in
                // memory as one string, so an oversized archive is an OOM risk
                // on both export and restore.
                val zip = runCatching { skillRepo.exportSkillToZip(skill.id) }.getOrNull()
                val zipBytes = zip?.let { f -> runCatching { f.readBytes() }.getOrNull() }
                if (zipBytes != null && zipBytes.isNotEmpty() &&
                    zipBytes.size <= MAX_SKILL_ARCHIVE_BYTES
                ) {
                    entry.put(
                        "archive",
                        android.util.Base64.encodeToString(zipBytes, android.util.Base64.NO_WRAP),
                    )
                    entry.put("archiveBytes", zipBytes.size)
                    entry.put("fileCount", skillRepo.listSkillFiles(skill.id).size)
                } else {
                    entry.put("body", skill.body)
                    Log.w(
                        TAG,
                        "export: ${if (zipBytes == null) "no archive" else "archive too large (${zipBytes.size} bytes)"} " +
                            "for skill ${skill.id}, carrying SKILL.md only",
                    )
                }
                // Clean up the cache artifact immediately — exportSkillToZip is
                // designed for the share sheet (TTL-swept), but here the bytes
                // are already in the payload and the file is dead weight. The
                // name check pins the contract that the zip sits in its own
                // per-export dir, so this can never widen into a shared cache.
                zip?.parentFile
                    ?.takeIf { it.name.startsWith("skill-export-") }
                    ?.let { dir -> runCatching { dir.deleteRecursively() } }
                skills.put(entry)
            }
        }

        // Memory: `memory.enabled` in the registry is only the *toggle*. The
        // actual content is GLOBAL.md + the YYYY-MM-DD.md daily logs owned by
        // MemoryRepository, which is why restoring a backup used to come back
        // with the switch in the right position and nothing behind it.
        val memoryFiles = JSONArray()
        if (memoryRepo != null) {
            for (info in runCatching { memoryRepo.listAllFiles() }.getOrDefault(emptyList())) {
                // [T-sync-memory-scope] Auto-sync passes an explicit allow-list
                // of shared stable files (currently only GLOBAL.md) and
                // EXCLUDES the per-device YYYY-MM-DD daily logs — a daily log
                // is a record of *this* device's agent activity, not a shared
                // resource, and syncing it as a whole-file overwrite destroys
                // the receiving device's same-day entries. Manual full backups
                // keep carrying every file (memoryFileNames == null).
                if (memoryFileNames != null && info.name !in memoryFileNames) continue
                val content = runCatching { memoryRepo.readFile(info.name) }.getOrNull() ?: continue
                memoryFiles.put(JSONObject().apply {
                    put("name", info.name)
                    put("content", content)
                })
            }
        }

        // MCP servers live in their own servers.json. Note their client secrets
        // and issued OAuth tokens are in MCPOAuthStore, NOT here — those stay
        // out of the payload entirely and are reported on import as needing
        // re-authorization.
        val mcpServers = JSONArray()
        if (mcpRepo != null) {
            for (server in mcpRepo.servers.value) {
                val raw = runCatching { mcpRepo.exportServerJSON(server) }.getOrNull() ?: continue
                val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                mcpServers.put(obj)
            }
        }

        // Chat history: session metadata + text-only message parts. The
        // window (chatWindowDays) and per-session cap
        // (MAX_CHAT_MESSAGES_PER_SESSION) bound *what is eligible*, but the
        // payload itself is sized by a byte budget, not by those knobs:
        // eligible messages are packed newest-first, session by session
        // (sessions ordered by updatedAt DESC), until the budget runs out.
        // Granularity is a single message — no "days" ladder that can drop
        // 30 days at once when the data is lumpy. Media parts
        // (images/videos/files) are dropped — they dominate the size and point
        // at payloads that will not exist on the target device.
        val chatSessions = JSONArray()
        val chatMessages = JSONArray()
        var chatTruncated: JSONObject? = null
        if (chatRepo != null && chatWindowDays > 0) {
            // Serialize the non-chat skeleton ONCE to measure its exact
            // serialized cost; the chat sections get whatever budget is left
            // under MAX_PAYLOAD_BYTES. SAFETY_MARGIN_BYTES absorbs the JSON
            // escaping / separators between the skeleton and the chat arrays
            // plus any drift between the estimate and the final document.
            val skeletonJson = JSONObject().apply {
                put("format", "openminis.config.backup")
                put("version", FORMAT_VERSION)
                put("createdAt", System.currentTimeMillis())
                put("includesSecrets", includeSecrets)
                put("fields", fields)
                put("providers", providers)
                put("groups", groups)
                put("envVars", envVars)
                put("skills", skills)
                put("memoryFiles", memoryFiles)
                put("mcpServers", mcpServers)
                put("chatSessions", JSONArray())
                put("chatMessages", JSONArray())
                if (readFailures > 0) put("readFailures", readFailures)
            }.toString()
            val skeletonChars = skeletonJson.length

            val cutoff = System.currentTimeMillis() - chatWindowDays * 24L * 3600 * 1000
            val sessions = runCatching {
                chatRepo.dao.sessionsUpdatedSince(cutoff)
            }.getOrDefault(emptyList())

            // DAO contract: sessions ordered by updatedAt DESC; messagesLast
            // returns newest-first. That is exactly the packing order — the
            // most recent context always lands in the backup first.
            val packInput = sessions.map { session ->
                val sessionJson = JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("modelId", session.modelId)
                    put("createdAt", session.createdAt)
                    put("updatedAt", session.updatedAt)
                    put("category", session.category)
                    put("lastMessage", session.lastMessage)
                    put("modelBinding", session.modelBinding)
                    put("source", session.source)
                    put("memoryEnabled", session.memoryEnabled)
                    put("pinnedAt", session.pinnedAt)
                    put("editCount", session.editCount)
                    put("thinkingOverride", session.thinkingOverride)
                }
                val messages = runCatching {
                    chatRepo.dao.messagesLast(session.id, MAX_CHAT_MESSAGES_PER_SESSION)
                }.getOrDefault(emptyList()).map { m ->
                    BudgetChatMessage(
                        id = m.id,
                        sessionId = m.sessionId,
                        role = m.role,
                        partsJson = m.partsJson,
                        createdAt = m.createdAt,
                        sortOrder = m.sortOrder,
                        reasoningContent = m.reasoningContent,
                    )
                }
                sessionJson to messages
            }

            val packed = packChatHistoryWithBudget(
                skeletonChars = skeletonChars,
                budgetTotalChars = (MAX_PAYLOAD_BYTES - SAFETY_MARGIN_BYTES).toLong(),
                sessionsInOrder = packInput,
                sanitize = ::sanitizeChatParts,
                capReasoning = ::capReasoningContent,
            )
            for (s in packed.sessions) chatSessions.put(s)
            for (m in packed.messages) chatMessages.put(m)

            if (packed.sessionsDropped > 0 || packed.messagesDropped > 0) {
                chatTruncated = JSONObject().apply {
                    put("sessionsDropped", packed.sessionsDropped)
                    put("messagesDropped", packed.messagesDropped)
                    put("budgetBytes", MAX_PAYLOAD_BYTES)
                }
                Log.w(
                    TAG,
                    "export: chat history budget-trimmed " +
                        "(sessionsDropped=${packed.sessionsDropped} " +
                        "messagesDropped=${packed.messagesDropped})",
                )
            }
        }

        val payload = JSONObject().apply {
            put("format", "openminis.config.backup")
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("includesSecrets", includeSecrets)
            put("fields", fields)
            put("providers", providers)
            put("groups", groups)
            put("envVars", envVars)
            put("skills", skills)
            put("memoryFiles", memoryFiles)
            put("mcpServers", mcpServers)
            put("chatSessions", chatSessions)
            put("chatMessages", chatMessages)
            chatTruncated?.let { put("chatTruncated", it) }
            if (readFailures > 0) put("readFailures", readFailures)
        }.toString()

        // [T-backup-export-size-cap] Enforce the same ceiling on the export
        // side that import already checks (MAX_PAYLOAD_BYTES). With the byte
        // budget packing above, chat history alone can no longer blow the
        // cap — it is trimmed to fit. What remains possible is the non-chat
        // skeleton itself growing past the cap (pathological skills /
        // memory files), and for that the hard refusal below stays: it keeps
        // the failure local and actionable instead of OOMing the import side.
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw IllegalStateException(
                "Backup too large (${payload.length} chars, max $MAX_PAYLOAD_BYTES)",
            )
        }
        return payload
    }

    /**
     * Entry uuids for [instanceId] in the exact order
     * [ProviderRepository.exportInstanceJSON] serializes its `models` array:
     * the underlying list's append order (visible and hidden entries
     * interleaved as inserted), NOT a visible-then-hidden split. Keeping this
     * in lock-step with that method is what makes positional old→new uuid
     * pairing correct on import; if exportInstanceJSON's ordering ever
     * changes, this must follow.
     *
     * [fix-audit-finding-1] This previously concatenated
     * `visibleEntries + filter(isHidden)`, which diverges from
     * exportInstanceJSON's single `filter { providerInstanceId == instanceId }`
     * once visible/hidden entries are interleaved (almost always after a
     * refresh) — positional pairing then mapped model-group members onto the
     * wrong entry. `entriesFor` is the exact same predicate + order as
     * exportInstanceJSON, so the three paths (export, append-import,
     * merge-import) all pair by identical positioning.
     */
    private fun orderedEntryIds(
        providerRepo: ProviderRepository,
        instanceId: String,
    ): List<String> {
        return entryIdsInExportOrder(providerRepo.entriesFor(instanceId))
    }

    /**
     * Returns the set of base-model ids to DROP from a sync snapshot.
     * See [isCatalogCacheModel] for the decision rule.
     */
    internal fun dropHiddenModelIds(
        providerRepo: ProviderRepository,
        instanceId: String,
    ): Set<String> {
        return providerRepo.config.value.modelEntries
            .asSequence()
            .filter { it.providerInstanceId == instanceId && isCatalogCacheModel(it.isHidden, it.isCustom) }
            .map { it.baseModel.id }
            .toSet()
    }

    /**
     * Provider credential keys, mirroring [ConfigValue.SECRET_KEYS]. The
     * Gemini-only `oauthEmail`/`oauthGcpProject` keys were historically listed
     * here, but they have no producer anywhere in the codebase (grep finds
     * zero writers) — the real OAuth credential is `oauthToken`. Removed so
     * the secret-strip list reflects what can actually appear in a payload.
     */
    private val SECRET_PROVIDER_KEYS = listOf(
        "apiKey", "oauthToken", "manualOAuthToken",
    )

    /** Thrown for payloads that aren't ours, or are from a future major format. */
    class InvalidBackupException(message: String) : Exception(message)

    /**
     * Apply a backup document produced by [export].
     *
     * Import is deliberately best-effort per item: one field that no longer
     * validates (a default group id that doesn't exist on this install, an enum
     * value from a newer build) is recorded in [ImportResult.skipped] and the
     * rest still lands. An all-or-nothing import would make backups useless
     * across versions.
     *
     * Providers restore by *merging* when an instance with the same
     * (providerType, label) already exists — [ProviderRepository.mergeImportInstanceJSON]
     * reuses it and upserts missing models, so restoring onto a non-empty
     * install no longer produces "OpenAI (2)" duplicates. A genuinely new
     * provider is still appended via [ProviderRepository.importInstanceJSON]
     * (which itself auto-renames on label conflict as a last resort).
     */
    suspend fun import(
        providerRepo: ProviderRepository,
        json: String,
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
        chatRepo: ChatRepository? = null,
        isSyncMerge: Boolean = false,
    ): ImportResult {
        // [fix-audit-p1-2] Reject oversized documents BEFORE any parsing /
        // decoding: a backup with embedded skill archives or chat history is
        // Base64-decoded into full byte arrays in memory, so an unbounded
        // payload is an OOM door. This check runs on the raw string, before
        // JSONTokener allocates the parsed tree.
        if (json.length > MAX_PAYLOAD_BYTES) {
            throw InvalidBackupException(
                "Backup too large (${json.length} chars, max ${MAX_PAYLOAD_BYTES})"
            )
        }
        val root = try {
            JSONTokener(json).nextValue() as? JSONObject
                ?: throw InvalidBackupException("Backup root is not a JSON object")
        } catch (e: InvalidBackupException) {
            throw e
        } catch (t: Throwable) {
            throw InvalidBackupException("Malformed JSON: ${t.message}")
        }

        if (root.optString("format") != "openminis.config.backup") {
            throw InvalidBackupException("Not a RikkaMinis backup file")
        }
        val version = root.optInt("version", 0)
        if (version > FORMAT_VERSION) {
            throw InvalidBackupException(
                "Backup was created by a newer version of the app (format $version)"
            )
        }

        val skipped = ArrayList<String>()
        val registry = ConfigRegistry.get()
        // [fix-audit-p0-4] Counters hoisted OUTSIDE the stage try so the catch
        // below can report what already landed when a stage blows up mid-
        // restore. Any Throwable past format validation becomes
        // ImportResult.fatal instead of being lost — the caller must treat a
        // fatal restore as partial and offer the snapshot rollback.
        var fatal: String? = null
        var providersImported = 0
        var groupsImported = 0
        var applied = 0
        var envVarsImported = 0
        var skillsImported = 0
        var memoryFilesImported = 0
        var mcpServersImported = 0
        var chatSessionsImported = 0
        var chatMessagesImported = 0
        try {

        // [T-backup-group-idmap] Order matters. Providers create the model
        // entries that groups reference; groups create the ids that
        // defaults.primaryGroup / agentLoopGroups reference. So the sequence is
        // providers → groups → fields, and each stage publishes an old→new id
        // map the next stage rewrites through. Doing fields first (the old
        // order) meant defaults.primaryGroup was validated against groups that
        // did not exist yet and was always rejected.
        val entryIdMap = HashMap<String, String>()   // source entry uuid → restored uuid
        val groupIdMap = HashMap<String, String>()    // source group id  → restored id

        // -- Stage 1: providers (also builds the entry-id map) --
        val providers = root.optJSONArray("providers")
        if (providers != null) {
            for (i in 0 until providers.length()) {
                val obj = providers.optJSONObject(i) ?: continue
                val label = obj.optString("label", "provider #${i + 1}")
                // Pull our backup-layer annotation out before handing the object
                // to the repository (which ignores it anyway, but keeping the
                // wire payload clean avoids surprises).
                val srcEntryIds = obj.optJSONArray("_entryIds")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    ?: emptyList()
                try {
                    // [T-backup-dedup] Restore onto an install that already has
                    // this provider (same type + label) by merging into it —
                    // no more "OpenAI (2)" duplicates. The merge returns the
                    // source entry uuid → restored entry uuid map directly;
                    // otherwise fall back to the classic append-and-pair.
                    // [T-backup-restore-credentials] A manual full restore
                    // (isSyncMerge=false) applies the backup's credentials to
                    // the existing instance; sync merges keep local secrets.
                    val merged = providerRepo.mergeImportInstanceJSON(
                        obj.toString(), srcEntryIds,
                        applyCredentials = !isSyncMerge,
                    )
                    val resolvedLabel: String?
                    if (merged != null) {
                        resolvedLabel = label
                        val (_, mergedMap) = merged
                        for ((oldEid, newEid) in mergedMap) {
                            if (oldEid.isNotEmpty()) entryIdMap[oldEid] = newEid
                        }
                    } else {
                        val instancesBefore = providerRepo.instances.map { it.id }.toSet()
                        resolvedLabel = providerRepo.importInstanceJSON(obj.toString())
                        if (resolvedLabel != null) {
                            // Identify the instance importInstanceJSON just
                            // created (the one id that wasn't present before)
                            // and pair its entries to the source uuids
                            // positionally — orderedEntryIds mirrors the export
                            // ordering exactly.
                            val newId = providerRepo.instances
                                .map { it.id }
                                .firstOrNull { it !in instancesBefore }
                            if (newId != null && srcEntryIds.isNotEmpty()) {
                                val newEntryIds = orderedEntryIds(providerRepo, newId)
                                val n = minOf(srcEntryIds.size, newEntryIds.size)
                                for (k in 0 until n) {
                                    val oldEid = srcEntryIds[k]
                                    if (oldEid.isNotEmpty()) entryIdMap[oldEid] = newEntryIds[k]
                                }
                                if (srcEntryIds.size != newEntryIds.size) {
                                    // Non-fatal: model set differs from when the
                                    // backup was taken (a model was
                                    // hidden/added since). Groups referencing
                                    // the unmapped entries will report them.
                                    Log.w(
                                        TAG,
                                        "import: provider \"$resolvedLabel\" entry count " +
                                            "${srcEntryIds.size}→${newEntryIds.size}; " +
                                            "some group members may not remap"
                                    )
                                }
                            }
                        }
                    }
                    if (resolvedLabel == null) {
                        skipped.add("provider \"$label\": import rejected")
                        continue
                    }
                    providersImported++
                } catch (t: Throwable) {
                    skipped.add("provider \"$label\": ${t.message ?: "import failed"}")
                }
            }
        }

        // -- Stage 2: model groups (remaps member entry ids, builds group map) --
        val groups = root.optJSONArray("groups")
        if (groups != null) {
            val existingGroupIds = providerRepo.config.value.modelGroups.map { it.id }.toSet()
            val existingGroupNames = providerRepo.config.value.modelGroups.map { it.name }.toSet()
            for (i in 0 until groups.length()) {
                val g = groups.optJSONObject(i) ?: continue
                val srcId = g.optString("id", "")
                val name = g.optString("name", "group #${i + 1}")
                // Remap member entries through the entry map; drop members whose
                // source entry never made it in (missing provider/model).
                val srcMembers = g.optJSONArray("memberEntryIds")
                val members = ArrayList<String>()
                var droppedMembers = 0
                if (srcMembers != null) {
                    for (j in 0 until srcMembers.length()) {
                        val old = srcMembers.optString(j, "")
                        val mapped = entryIdMap[old]
                        if (mapped != null) members.add(mapped) else droppedMembers++
                    }
                }
                // [T-backup-dedup] A group with the same name already exists on
                // this install → merge the backup's members into it instead of
                // creating "name (2)". Local members are kept (union), so a
                // restore is additive rather than destructive.
                val existingGroup = providerRepo.config.value.modelGroups
                    .firstOrNull { it.name == name }
                if (existingGroup != null) {
                    val mergedMembers = existingGroup.memberEntryIds.toMutableList()
                    var addedAny = false
                    for (m in members) {
                        if (m !in mergedMembers) {
                            mergedMembers.add(m)
                            addedAny = true
                        }
                    }
                    if (addedAny) {
                        providerRepo.updateGroup(existingGroup.copy(memberEntryIds = mergedMembers))
                    }
                    if (srcId.isNotEmpty()) groupIdMap[srcId] = existingGroup.id
                    groupsImported++
                    if (droppedMembers > 0) {
                        skipped.add(
                            "group \"$name\": $droppedMembers member(s) skipped " +
                                "(their model/provider isn't in this backup)"
                        )
                    }
                    continue
                }
                // No existing group with this name: create a fresh one. Fresh
                // id unless the source id is somehow free on this install; the
                // name-rename path is a safety net for duplicate names inside a
                // single backup, since cross-install collisions now merge.
                val newId = if (srcId.isNotEmpty() && srcId !in existingGroupIds) {
                    srcId
                } else {
                    java.util.UUID.randomUUID().toString()
                }
                var resolvedName = name
                if (resolvedName in existingGroupNames) {
                    var suffix = 2
                    while ("$name ($suffix)" in existingGroupNames) suffix++
                    resolvedName = "$name ($suffix)"
                }
                try {
                    val group = ModelGroup(
                        id = newId,
                        name = resolvedName,
                        memberEntryIds = members,
                        strategy = enumOrDefault(
                            g.optString("strategy"),
                            com.openminis.app.data.model.RoutingStrategy.fallback,
                        ),
                        fallbackStrategy = enumOrDefault(
                            g.optString("fallbackStrategy"),
                            com.openminis.app.data.model.FallbackStrategy.default,
                        ),
                        defaultThinkingLevel = g.optString("defaultThinkingLevel")
                            .takeIf { it.isNotEmpty() }
                            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() },
                        contextLimitTokens = if (g.has("contextLimitTokens"))
                            g.optInt("contextLimitTokens").takeIf { it > 0 } else null,
                        lastContextLimitTokens = if (g.has("lastContextLimitTokens"))
                            g.optInt("lastContextLimitTokens").takeIf { it > 0 } else null,
                    )
                    providerRepo.addGroup(group)
                    if (srcId.isNotEmpty()) groupIdMap[srcId] = newId
                    groupsImported++
                    if (droppedMembers > 0) {
                        skipped.add(
                            "group \"$name\": $droppedMembers member(s) skipped " +
                                "(their model/provider isn't in this backup)"
                        )
                    }
                } catch (t: Throwable) {
                    skipped.add("group \"$name\": ${t.message ?: "import failed"}")
                }
            }
        }

        // -- Stage 3: scalar fields (defaults.* group/entry ids remapped) --
        val fields = root.optJSONObject("fields")
        if (fields != null) {
            // [T-sync-merge-guard] On a multi-device auto-sync merge, personality
            // fields (soul.*) are strong per-device identity — name, style, body,
            // lang — and must not be overwritten by a sibling's snapshot. Persist
            // them only on a full manual restore (isSyncMerge=false); never on a
            // sync pull. This mirrors the same "user-personalization stays on the
            // device" principle that keeps hidden models & OAuth credentials out
            // of the sync payload.
            val keys = fields.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val field = registry.resolveField(path)
                if (field == null) {
                    // Field was removed or renamed since the backup was taken.
                    skipped.add("$path: no longer exists in this version")
                    continue
                }
                // [T-sync-merge-guard] Strongly per-device personality fields are
                // never merged in from a sibling device's auto-sync snapshot.
                // Only a full manual restore may write them.
                if (shouldSkipSyncField(path, isSyncMerge)) {
                    skipped.add("$path: personality not overwritten by auto-sync")
                    continue
                }
                if (field.scope !in BACKED_UP_SCOPES) {
                    skipped.add("$path: outside backup scope")
                    continue
                }
                if (field.access != ConfigAccess.READWRITE) {
                    skipped.add("$path: read-only")
                    continue
                }
                val unavailable = field.unavailableReason
                if (unavailable != null) {
                    skipped.add("$path: unavailable on this device ($unavailable)")
                    continue
                }

                val raw = fields.optString(path, "")
                val decoded = ConfigValue.decode(raw)
                if (decoded == null) {
                    skipped.add("$path: unreadable value in backup")
                    continue
                }
                // Rewrite the group/entry ids these fields carry from source ids
                // to the ids just minted above. An id with no mapping is left
                // as-is so the field's own writer reports it as unknown rather
                // than this layer swallowing it.
                val value = remapDefaultsIds(path, decoded, groupIdMap, entryIdMap)
                try {
                    // Validate against the field's own schema before writing so
                    // a stale enum / out-of-range number is reported instead of
                    // being forced into prefs.
                    field.valueSchema.validate(value)
                    field.write(value)
                    applied++
                } catch (t: Throwable) {
                    skipped.add("$path: ${t.message ?: "rejected"}")
                }
            }
        }

        // -- Stage 4: environment variables (own repository, secret-gated) --
        val envVarsArr = root.optJSONArray("envVars")
        if (envVarsArr != null && envVarRepo != null) {
            for (i in 0 until envVarsArr.length()) {
                val ev = envVarsArr.optJSONObject(i) ?: continue
                val key = ev.optString("key", "").trim()
                if (key.isEmpty()) {
                    skipped.add("env var #${i + 1}: missing key")
                    continue
                }
                if (envVarRepo.isDuplicateKey(key)) {
                    skipped.add("env var \"$key\": already exists, left as-is")
                    continue
                }
                // A backup taken without secrets carries no value; add the key
                // with an empty value so the metadata/note survive and the user
                // only has to refill the secret rather than recreate the entry.
                val value = ev.optString("value", "")
                val note = ev.optString("note", "")
                if (envVarRepo.add(key, value, note)) {
                    envVarsImported++
                    if (value.isEmpty()) {
                        skipped.add("env var \"$key\": restored without value — re-enter it")
                    }
                } else {
                    skipped.add("env var \"$key\": rejected (invalid key)")
                }
            }
        } else if (envVarsArr != null && envVarsArr.length() > 0 && envVarRepo == null) {
            skipped.add("${envVarsArr.length()} env var(s): not restorable here")
        }

        // -- Stage 5: skills (db row + full directory from the embedded zip) --
        // Skill ids are slugify(name), not random uuids, so they are stable
        // across installs — no id remapping needed here, unlike providers.
        val skillsArr = root.optJSONArray("skills")
        if (skillsArr != null && skillRepo != null) {
            for (i in 0 until skillsArr.length()) {
                val s = skillsArr.optJSONObject(i) ?: continue
                val name = s.optString("name", "skill #${i + 1}")
                val archive = s.optString("archive", "")
                try {
                    // importFromContent/importFromArchive replace an existing
                    // skill of the same id in place, which is what we want:
                    // restoring should refresh, not create "skill (2)".
                    val imported = if (archive.isNotEmpty()) {
                        // [fix-audit-p1-2] Estimate the decoded size BEFORE
                        // decoding (Base64: 4 chars ≈ 3 bytes). A backup made
                        // by a future build — or hand-edited — can embed an
                        // oversized archive; decoding it would spike memory
                        // for zero benefit.
                        val estimatedBytes = (archive.length / 4L) * 3L
                        if (estimatedBytes > MAX_SKILL_ARCHIVE_BYTES) {
                            skipped.add("skill \"$name\": archive too large (${estimatedBytes} bytes)")
                            null
                        } else {
                            val bytes = android.util.Base64.decode(archive, android.util.Base64.NO_WRAP)
                            skillRepo.importFromArchive(ByteArrayInputStream(bytes))
                        }
                    } else {
                        val body = s.optString("body", "")
                        if (body.isBlank()) null
                        else skillRepo.importFromContent(
                            body,
                            SkillRepository.ImportSource.from(s.optString("importSource", "file")),
                            s.optString("sourceURL", "").takeIf { it.isNotEmpty() },
                        )
                    }
                    if (imported == null) {
                        skipped.add("skill \"$name\": archive unreadable or SKILL.md invalid")
                        continue
                    }
                    // The enabled flag is user intent, not part of SKILL.md, so
                    // it has to be reapplied after the content import.
                    if (!s.optBoolean("isEnabled", true)) {
                        runCatching { skillRepo.setEnabled(imported.id, false) }
                    }
                    skillsImported++
                    if (archive.isEmpty()) {
                        skipped.add(
                            "skill \"$name\": restored SKILL.md only — bundled scripts were " +
                                "not in the backup"
                        )
                    }
                } catch (t: Throwable) {
                    skipped.add("skill \"$name\": ${t.message ?: "import failed"}")
                }
            }
            runCatching { skillRepo.reloadFromDisk() }
        } else if (skillsArr != null && skillsArr.length() > 0 && skillRepo == null) {
            skipped.add("${skillsArr.length()} skill(s): not restorable here")
        }

        // -- Stage 6: memory files (GLOBAL.md + daily logs) --
        val memArr = root.optJSONArray("memoryFiles")
        if (memArr != null && memoryRepo != null) {
            for (i in 0 until memArr.length()) {
                val m = memArr.optJSONObject(i) ?: continue
                val name = m.optString("name", "").trim()
                if (name.isEmpty()) {
                    skipped.add("memory file #${i + 1}: missing name")
                    continue
                }
                // Defence in depth: these names become file names under the
                // memory dir, so anything with a path separator is rejected
                // outright rather than trusted from the payload.
                if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                    skipped.add("memory file \"$name\": unsafe name, skipped")
                    continue
                }
                val content = m.optString("content", "")
                try {
                    memoryRepo.saveFile(name, content)
                    memoryFilesImported++
                } catch (t: Throwable) {
                    skipped.add("memory file \"$name\": ${t.message ?: "write failed"}")
                }
            }
        } else if (memArr != null && memArr.length() > 0 && memoryRepo == null) {
            skipped.add("${memArr.length()} memory file(s): not restorable here")
        }

        // -- Stage 7: MCP servers --
        val mcpArr = root.optJSONArray("mcpServers")
        if (mcpArr != null && mcpRepo != null) {
            var needsReauth = 0
            for (i in 0 until mcpArr.length()) {
                val srv = mcpArr.optJSONObject(i) ?: continue
                // exportServerJSON emits the importable wrapper shape
                // {"mcpServers":{"<id>":{…}}} — the id is the *key*, and
                // "oauth" sits on the inner object, not the root.
                val inner = srv.optJSONObject("mcpServers")
                val id = inner?.keys()?.asSequence()?.firstOrNull()
                    ?: srv.optString("id", "").ifEmpty { "server #${i + 1}" }
                val entry = inner?.optJSONObject(id) ?: srv
                try {
                    val imported = mcpRepo.importJSON(srv.toString())
                    if (imported.isEmpty()) {
                        skipped.add("MCP server \"$id\": import rejected")
                        continue
                    }
                    mcpServersImported += imported.size
                    if (entry.has("oauth")) needsReauth++
                } catch (t: Throwable) {
                    skipped.add("MCP server \"$id\": ${t.message ?: "import failed"}")
                }
            }
            if (needsReauth > 0) {
                // Client secrets and issued tokens live in MCPOAuthStore and are
                // deliberately never exported — reconnecting is interactive.
                skipped.add(
                    "$needsReauth MCP server(s) use OAuth — reconnect them to sign in again"
                )
            }
        } else if (mcpArr != null && mcpArr.length() > 0 && mcpRepo == null) {
            skipped.add("${mcpArr.length()} MCP server(s): not restorable here")
        }

        // -- Stage 8: chat history (session metadata + text-only parts) --
        // Sessions go in first — messages reference them via FK. Insertion is
        // existence-guarded so re-imports are truly idempotent: an existing
        // session row is kept as-is (its title / pin / updatedAt must not be
        // clobbered by an older backup), and an existing message is kept
        // (the local copy is the full version — backups carry sanitized /
        // truncated parts). Message ids are preserved so later references to
        // a restored session stay valid. Logic lives in [importChatSections]
        // (unit-testable); Stage 8 only feeds it the parsed arrays.
        val chatSessionsArr = root.optJSONArray("chatSessions")
        val chatMessagesArr = root.optJSONArray("chatMessages")
        if (chatSessionsArr != null && chatRepo != null) {
            val (sessionsImported, messagesImported) =
                importChatSections(chatRepo, chatSessionsArr, chatMessagesArr, skipped)
            chatSessionsImported += sessionsImported
            chatMessagesImported += messagesImported
        } else if (chatSessionsArr != null && chatSessionsArr.length() > 0 && chatRepo == null) {
            skipped.add("${chatSessionsArr.length()} chat session(s): not restorable here")
        }

        // [T-backup-byte-budget] Surface budget trimming so a restore is
        // never silently partial: the exporting device cut chat history to
        // fit MAX_PAYLOAD_BYTES, and the user should know how much was left
        // out (and that the exporting device's local DB still holds it all).
        root.optJSONObject("chatTruncated")?.let { tr ->
            val s = tr.optInt("sessionsDropped", 0)
            val m = tr.optInt("messagesDropped", 0)
            if (s > 0 || m > 0) {
                skipped.add(
                    "chat history was budget-trimmed at export: " +
                        "$m message(s) and $s session(s) not carried (full history stays on the source device)"
                )
            }
        }


        } catch (t: Throwable) {
            fatal = t.message ?: "import failed"
            Log.e(TAG, "import FATAL — partial restore left on disk: ${t.message}", t)
        }
        return ImportResult(
            fieldsApplied = applied,
            providersImported = providersImported,
            groupsImported = groupsImported,
            envVarsImported = envVarsImported,
            skillsImported = skillsImported,
            memoryFilesImported = memoryFilesImported,
            mcpServersImported = mcpServersImported,
            chatSessionsImported = chatSessionsImported,
            chatMessagesImported = chatMessagesImported,
            skipped = skipped,
            hadSecrets = root.optBoolean("includesSecrets", false),
            fatal = fatal,
        ).also { result ->
            // Mirror the outcome into the diagnostic log. The result dialog
            // only shows the first few skipped lines (screen budget), and the
            // whole import otherwise leaves no trace — so when a restore comes
            // back half-applied there is nothing to look at after dismissing
            // the sheet. One summary line plus one line per skip fixes that.
            Log.i(
                TAG,
                "import: applied=$applied providers=$providersImported " +
                    "groups=$groupsImported envVars=$envVarsImported " +
                    "skills=$skillsImported memoryFiles=$memoryFilesImported " +
                    "mcpServers=$mcpServersImported " +
                    "chatSessions=$chatSessionsImported chatMessages=$chatMessagesImported " +
                    "skipped=${result.skipped.size} hadSecrets=${result.hadSecrets}"
            )
            for (line in result.skipped) Log.w(TAG, "import skipped — $line")
        }
    }

    /** [enumValueOf] that falls back to [default] instead of throwing on a
     *  token this build doesn't know (forward-compat with newer backups). */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)

    /**
     * Rewrite the source group/entry ids embedded in a `defaults.*` field to the
     * ids minted during this import. Group-typed fields
     * (primaryGroup / subGroup / agentLoopGroups) map through [groupIdMap];
     * agentLoopEntries maps through [entryIdMap]. Everything else is returned
     * unchanged. Unmapped ids are passed through so the field's own writer can
     * report them as unknown rather than this layer silently dropping them.
     */
    private fun remapDefaultsIds(
        path: String,
        value: ConfigValue,
        groupIdMap: Map<String, String>,
        entryIdMap: Map<String, String>,
    ): ConfigValue {
        val map = when (path) {
            "defaults.primaryGroup", "defaults.subGroup", "defaults.agentLoopGroups" -> groupIdMap
            "defaults.agentLoopEntries" -> entryIdMap
            else -> return value
        }
        if (map.isEmpty()) return value
        return when (value) {
            is ConfigValue.Str -> ConfigValue.Str(map[value.value] ?: value.value)
            is ConfigValue.Arr -> ConfigValue.Arr(
                value.value.map { el ->
                    if (el is ConfigValue.Str) ConfigValue.Str(map[el.value] ?: el.value) else el
                }
            )
            else -> value
        }
    }

    /** Hard cap on messages carried per session in a chat-history backup. */
    internal const val MAX_CHAT_MESSAGES_PER_SESSION = 200

    /** [T-backup-chat-slim] Chat text parts (assistant replies, pasted code /
     *  logs) are capped at this many characters per message part. Backup
     *  history is for context continuity on a new device, not a verbatim
     *  archive — the local DB keeps the full text either way. Aligns text
     *  with the existing tool-output / reasoning caps so no part type can
     *  silently dominate the payload. */
    internal const val MAX_BACKUP_TEXT_CHARS = 4000

    /** [T-backup-chat-slim] tool_use input (the arguments JSON a model chose)
     *  is capped at this many characters. It is an escaped JSON string in
     *  parts_json (see ChatViewModel.buildAssistantPartsJson) and previously
     *  rode the backup uncapped — a single huge command/file write argument
     *  could dominate the payload. tool_title and short args survive intact;
     *  oversized ones keep their head with a truncation marker. */
    internal const val MAX_BACKUP_TOOL_INPUT_CHARS = 2000

    /** [T-backup-byte-budget] Headroom subtracted from MAX_PAYLOAD_BYTES
     *  before packing chat history: absorbs JSON separators, the trailing
     *  chatTruncated block, and any drift between the per-message
     *  JSONObject.toString() estimates and the final document. 1MB is ~1.5%
     *  of the cap — large enough that the final length check never trips
     *  from estimation noise, small enough to be irrelevant to how much
     *  chat fits. */
    internal const val SAFETY_MARGIN_BYTES = 1024 * 1024

    /** [T-backup-chat-slim] Tool outputs in backups are truncated to this
     *  many characters. Full tool results are transient execution traces —
     *  on another device they are rarely useful at full size, and they are
     *  the dominant cost of a chat-history backup (measured: ~22MB of a
     *  67MB chatMessages payload on 2026-08-11). */
    internal const val MAX_BACKUP_TOOL_OUTPUT_CHARS = 500

    /** [T-backup-chat-slim] Model reasoning chains are capped at this many
     *  characters per message. Reasoning is a nice-to-have on restore, not
     *  a payload that justifies megabytes (measured: ~7.4MB across 29k
     *  messages). */
    internal const val MAX_BACKUP_REASONING_CHARS = 2000

    /** [fix-audit-p0-3] How many pre-restore snapshots to keep on disk.
     *  Rollback candidates; older ones are pruned by [writeSnapshot]. */
    const val SNAPSHOT_KEEP = 5

    /** [fix-audit-p1-2] Per-skill archive cap for backups. A skill with
     *  bundled assets bigger than this degrades to SKILL.md-only in the
     *  payload rather than ballooning the backup into an OOM risk (the whole
     *  payload is Base64-encoded in memory on export and decoded on import). */
    const val MAX_SKILL_ARCHIVE_BYTES = 8 * 1024 * 1024

    /** [fix-audit-p1-2] Hard cap on the serialized backup payload itself.
     *  Export refuses to build beyond this; import rejects the document
     *  before decoding anything (a malicious/huge file is dropped outright
     *  instead of OOMing mid-restore). */
    const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    /** [fix-audit-p0-3] Snapshot files live under `filesDir/backup-snapshots`,
     *  named with second precision so two restores in the same minute can't
     *  clobber each other (the old minute-precision [suggestedFileName] did
     *  exactly that — restoring A then B overwrote A's rollback point). The
     *  distinct `rikkaminis-snapshot-` prefix also keeps them out of the
     *  WebDAV remote-list matcher (`rikkaminis-backup-*`). */
    fun snapshotFileName(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return "rikkaminis-snapshot-${fmt.format(java.util.Date(now))}.json"
    }

    /** Writes [payload] as a fresh snapshot into [dir] and prunes to the
     *  newest [SNAPSHOT_KEEP] files. Returns the written file. */
    fun writeSnapshot(dir: java.io.File, payload: String): java.io.File {
        dir.mkdirs()
        val file = java.io.File(dir, snapshotFileName())
        file.writeText(payload)
        listSnapshots(dir).drop(SNAPSHOT_KEEP).forEach { runCatching { it.delete() } }
        return file
    }

    /** Snapshots in [dir], newest first. Only `rikkaminis-snapshot-*.json`. */
    fun listSnapshots(dir: java.io.File): List<java.io.File> =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith("rikkaminis-snapshot-") && f.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private val ATTACHED_FILES_REGEX =
        Regex("<user-attached-files>.*?</user-attached-files>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Strips media payloads from a stored parts_json document so chat
     * history stays light in backups. Keeps text / thinking / tool_use
     * parts; drops image and video entries (their base64 payloads dominate
     * size and are useless on another device); removes the
     * <user-attached-files> inventory, which references local paths; caps
     * text / toolUse.input / toolResult.output so no part type can
     * dominate. Returns null when nothing textual survives.
     */
    internal fun sanitizeChatParts(partsJson: String?): String? {
        if (partsJson.isNullOrBlank()) return null
        val arr = try {
            JSONArray(partsJson)
        } catch (t: Throwable) {
            return null
        }
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val el = arr.optJSONObject(i) ?: continue
            val type = el.optString("type")
            if (type == "image" || type == "image_url" || type == "video" || type == "video_url") {
                continue
            }
            if (type == "text") {
                val v = el.optString("value")
                if (v.isBlank()) continue
                val cleaned = v.replace(ATTACHED_FILES_REGEX, "").trim()
                if (cleaned.isBlank()) continue
                // [T-backup-chat-slim] Cap text parts like tool outputs —
                // pasted logs / code dumps previously rode uncapped.
                el.put(
                    "value",
                    if (cleaned.length > MAX_BACKUP_TEXT_CHARS) {
                        cleaned.take(MAX_BACKUP_TEXT_CHARS) +
                            "\n… [truncated ${cleaned.length - MAX_BACKUP_TEXT_CHARS} chars]"
                    } else cleaned
                )
            }
            if (type == "toolUse" || type == "tool_use") {
                // [T-backup-chat-slim] Cap the arguments JSON. "input" is
                // stored as an escaped JSON STRING (see
                // ChatViewModel.buildAssistantPartsJson), and import reads it
                // defensively (JSON parse failure → empty args), so a head
                // slice + marker stays restorable as text even when the cut
                // lands mid-JSON.
                val value = el.optJSONObject("value") ?: el
                val input = value.optString("input")
                if (input.length > MAX_BACKUP_TOOL_INPUT_CHARS) {
                    value.put(
                        "input",
                        input.take(MAX_BACKUP_TOOL_INPUT_CHARS) +
                            "\n… [truncated ${input.length - MAX_BACKUP_TOOL_INPUT_CHARS} chars]"
                    )
                }
            }
            if (type == "toolResult" || type == "tool_result") {
                val value = el.optJSONObject("value") ?: el
                // Drop the snapshot preview: it duplicates the tail of
                // output and no UI code reads it (verified 2026-08-11).
                value.remove("snapshot")
                val output = value.optString("output")
                if (output.length > MAX_BACKUP_TOOL_OUTPUT_CHARS) {
                    value.put(
                        "output",
                        output.take(MAX_BACKUP_TOOL_OUTPUT_CHARS) +
                            "\n… [truncated ${output.length - MAX_BACKUP_TOOL_OUTPUT_CHARS} chars]"
                    )
                }
            }
            kept.put(el)
        }
        return if (kept.length() == 0) null else kept.toString()
    }

    /** [T-backup-chat-slim] Cuts model reasoning content to a sane size.
     *  Full reasoning chains are nice on restore but not worth megabytes:
     *  after [MAX_BACKUP_REASONING_CHARS] the rest cannot meaningfully
     *  change a restored conversation. Pure so it is JVM-testable. */
    internal fun capReasoningContent(rc: String?): String? {
        if (rc.isNullOrBlank()) return null
        if (rc.length <= MAX_BACKUP_REASONING_CHARS) return rc
        return rc.take(MAX_BACKUP_REASONING_CHARS) +
            "\n… [truncated ${rc.length - MAX_BACKUP_REASONING_CHARS} chars]"
    }

    /** Default filename for a fresh export, e.g. `rikkaminis-backup-20260802.json`. */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "rikkaminis-backup-${fmt.format(java.util.Date(now))}.json"
    }
}

/**
 * [T-sync-hide-prune] Pure predicate deciding whether a model entry is part of
 * a provider's *public catalog cache* (hidden & not user-created) and should
 * therefore be excluded from an auto-sync snapshot / local retention. Hidden
 * custom models are user data and stay; hidden plain catalog entries are
 * re-pullable from the provider's /models endpoint and are dropped so a
 * snapshot carries only the user's selections, never the full catalog. Kept a
 * top-level function so the decision logic is JVM-unit-testable without an
 * Android [ProviderRepository].
 */
internal fun isCatalogCacheModel(isHidden: Boolean, isCustom: Boolean): Boolean =
    isHidden && !isCustom

/**
 * [fix-audit-finding-1] The entry-id ordering contract.
 *
 * `_entryIds` MUST be emitted in the exact order [ProviderRepository.exportInstanceJSON]
 * serializes its `models` array — the underlying list's *append* order, which
 * interleaves visible and hidden entries exactly as they were inserted, NOT a
 * re-sorted visible-then-hidden split. Both exportInstanceJSON and
 * [orderedEntryIds] derive from the same `filter { providerInstanceId ==
 * instanceId }`, so this projection is the identity over that filter and the
 * two stay in lock-step by construction.
 *
 * This is a pure, JVM-testable function (no Android [ProviderRepository])
 * precisely so the ordering contract can be pinned by [EntryIdOrderContractTest]:
 * given any interleaved visible/hidden entry list, this projection must equal
 * the `models`-array order (the same list's `baseModel.id` order). A future
 * edit that re-sorts here — e.g. reintroducing a `visible + hidden` split —
 * breaks positional old→new uuid pairing on merge-import and must fail the
 * test.
 */
internal fun entryIdsInExportOrder(entries: List<ModelEntry>): List<String> =
    entries.map { it.id }

/**
 * [T-backup-chat-idempotent] Existence-guarded chat restore used by
 * ConfigBackup Stage 8. Sessions are inserted only when absent (an existing
 * row keeps its title / pin / updatedAt — re-importing an older backup must
 * not clobber local metadata), and messages are inserted only when absent
 * (the local copy is the full version; backups carry sanitized / truncated
 * parts). This makes restore a true idempotent merge: first restore brings
 * everything, later restores fill only the gaps. Returns
 * (sessionsImported, messagesImported); per-item failures go to [skipped].
 */
internal suspend fun importChatSections(
    chatRepo: ChatRepository,
    chatSessionsArr: JSONArray?,
    chatMessagesArr: JSONArray?,
    skipped: MutableList<String>,
): Pair<Int, Int> {
    var chatSessionsImported = 0
    var chatMessagesImported = 0
    if (chatSessionsArr != null) {
        for (i in 0 until chatSessionsArr.length()) {
            val s = chatSessionsArr.optJSONObject(i) ?: continue
            val label = s.optString("title", "session #${i + 1}").ifEmpty { "session #${i + 1}" }
            try {
                val session = ChatSessionEntity(
                    id = s.optString("id"),
                    title = s.optString("title").ifEmpty { null },
                    modelId = s.optString("modelId"),
                    createdAt = s.optLong("createdAt"),
                    updatedAt = s.optLong("updatedAt"),
                    category = s.optString("category").ifEmpty { null },
                    lastMessage = s.optString("lastMessage").ifEmpty { null },
                    modelBinding = s.optString("modelBinding").ifEmpty { null },
                    source = s.optString("source").ifEmpty { null },
                    memoryEnabled = s.optInt("memoryEnabled", 1),
                    pinnedAt = if (s.has("pinnedAt")) s.optLong("pinnedAt") else null,
                    editCount = s.optInt("editCount", 0),
                    thinkingOverride = if (s.has("thinkingOverride")) s.optString("thinkingOverride") else null,
                )
                if (chatRepo.dao.getSession(session.id) == null) {
                    chatRepo.dao.insertSession(session)
                    chatSessionsImported++
                }
            } catch (t: Throwable) {
                skipped.add("chat session \"$label\": ${t.message ?: "import failed"}")
            }
        }
    }
    if (chatMessagesArr != null) {
        for (i in 0 until chatMessagesArr.length()) {
            val m = chatMessagesArr.optJSONObject(i) ?: continue
            try {
                val message = MessageEntity(
                    id = m.optString("id"),
                    sessionId = m.optString("sessionId"),
                    role = m.optString("role"),
                    partsJson = m.optString("partsJson"),
                    createdAt = m.optLong("createdAt"),
                    sortOrder = m.optInt("sortOrder", i),
                    reasoningContent = if (m.has("reasoningContent")) m.optString("reasoningContent") else null,
                )
                if (chatRepo.dao.getMessage(message.id) == null) {
                    chatRepo.dao.insertMessage(message)
                    chatMessagesImported++
                }
            } catch (t: Throwable) {
                skipped.add("chat message #${i + 1}: ${t.message ?: "import failed"}")
            }
        }
    }
    return chatSessionsImported to chatMessagesImported
}

/**
 * [T-sync-merge-guard] On a multi-device auto-sync merge, strongly per-device
 * personality fields (soul.*) are never written from a sibling's snapshot —
 * only a full manual restore (isSyncMerge=false) may persist them. Plain
 * config fields always pass. Top-level so the gate is JVM-unit-testable.
 */
internal fun shouldSkipSyncField(path: String, isSyncMerge: Boolean): Boolean =
    isSyncMerge && path.startsWith("soul.")
