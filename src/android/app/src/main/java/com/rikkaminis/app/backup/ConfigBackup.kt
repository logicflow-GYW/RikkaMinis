package com.rikkaminis.app.backup

import android.util.Log
import com.rikkaminis.app.config.ConfigAccess
import com.rikkaminis.app.config.ConfigRegistry
import com.rikkaminis.app.config.ConfigValue
import com.rikkaminis.app.data.model.FallbackStrategy
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.repository.ChatRepository
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.data.repository.MCPRepository
import com.rikkaminis.app.data.repository.MemoryRepository
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.repository.SkillRepository
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.scheduled.ScheduledTasksRestoreOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File

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

    /** [fix/dv18-4 A-07] Warning sink handed to the Android-free group
     *  run-config decode seam ([readGroupRunConfig]) — a lambda so the decode
     *  stays JVM-unit-testable: tests capture the message instead of needing
     *  logcat. */
    private val warnGroupRunConfig: (String) -> Unit = { Log.w(TAG, it) }

    /** Bumped only on breaking payload changes; readers reject newer majors. */
    const val FORMAT_VERSION = 1

    /**
     * Registry scopes included in a backup, i.e. the settings a user expects to
     * carry to a new install. Everything else in the registry is either
     * device-local state (session ids, cached metadata) or derived, and
     * restoring it would do more harm than good.
     */
    // [da1028-B / ISSUE-03] `internal` (not private) so
    // ConfigBackupEnvvarsAndOverlayTest can pin the exact scope set — a
    // text-grep probe cannot tell "envvars is in the set" from "the string
    // appears in the KDoc". Same testability-visibility precedent as
    // `memoryBackupEntries` / `ROLLUP_BACKUP_FILE` in this file.
    internal val BACKED_UP_SCOPES = setOf(
        "appearance",   // theme, font scale, chat bubble/background look
        "chat",         // composer + rendering preferences
        "background",   // background image / effect settings
        "defaults",     // default model group, agent-loop entries and groups
        "soul",         // SOUL.md persona fields
        "memory",       // memory feature toggles
        "logs",         // log retention preferences
        "runtime",      // agent-runtime knobs (e.g. max concurrent sessions)
        // [da1028-B / ISSUE-03] `envvars.privacyMode` is a real, registered
        // config face (ConfigBuiltins.registerEnvVars). Missing from this list
        // it was exported by nobody and — worse — a payload that carried it
        // was rejected on the way back in ("outside backup scope"), so
        // migrating devices silently dropped the privacy switch.
        //
        // Scope "envvars" contributes exactly two registry-level fields: the
        // `envvars` aggregate (ReadOnly, dropped by the READWRITE filter) and
        // `envvars.privacyMode`. The per-key `envvars.<KEY>.value|note|group`
        // children are *not* registry-level fields — they only exist through
        // EnvVarsCollection.fields(forId) — and buildSections walks
        // ConfigRegistry.allVisibleFieldPaths() (top-level only), so this entry
        // cannot pull them (or a secret value) into a backup. The keys
        // themselves travel in their own `envVars` section, secret-gated.
        "envvars",      // env-var privacy mode toggle
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
        /** Custom thinking rules restored (re-attached to their provider). */
        val thinkingRulesImported: Int,
        /** [feat/scheduled-tasks-l0] Scheduled-tasks document restored. */
        val scheduledTasksImported: Boolean,
        /** Artifact files restored (shared/ + mcp data, from the embedded zip). */
        val artifactFilesImported: Int,
        /** True when the payload carried a WebDAV server config that was applied. */
        val webdavConfigImported: Boolean,
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
     * Everything [export] and [exportToWriter] need, assembled once by
     * [buildSections].
     *
     * [T-backup-streaming-export] Splitting assembly from serialization is
     * what lets the streaming path reuse the exact same data as the String
     * path: a 90-day transcript set can reach ~65M chars once stringified,
     * and building it twice (once to measure the skeleton, once for the
     * document) was half the peak heap.
     */
    internal class ExportSections(
        val fields: JSONObject,
        /** Registry fields that could not be read, PLUS [FIX-P1A / FINDING-7]
         *  env-var values that were declared to travel with secrets but could
         *  not be read. One counter, because both mean the same thing to the
         *  user ("this backup is missing something it claims to carry") and
         *  both already flow to the export callers / the payload's
         *  `readFailures`. */
        val readFailures: Int,
        val providers: JSONArray,
        val thinkingRules: JSONArray,
        val groups: JSONArray,
        val envVars: JSONArray,
        val skills: JSONArray,
        val memoryFiles: JSONArray,
        val mcpServers: JSONArray,
        val artifacts: JSONObject,
        val chatSessions: List<JSONObject>,
        val chatMessages: List<JSONObject>,
        val chatTruncated: JSONObject?,
        // [feat/scheduled-tasks-l0] Raw scheduled-tasks document (brief §6.6:
        // tasks.json rides the user-config backup). Null when the caller has
        // no store access (JVM tests) — the section is omitted, which import
        // treats exactly like a pre-L0 backup.
        val scheduledTasks: JSONObject? = null,
    )

    /**
     * Serialize current settings to a backup document.
     *
     * @param includeSecrets when false, API keys and OAuth tokens are stripped.
     *   Defaults to true: a restore that drops every credential leaves the user
     *   retyping keys by hand, which defeats the point of a backup. Callers are
     *   expected to warn before writing the file somewhere shareable.
     *
     * [T-backup-streaming-export] This in-memory path is the FALLBACK. The
     * chat sections alone can reach ~65M chars on a heavy install, and the
     * document String plus its toString() StringBuilder double the heap
     * (measured OOM 2026-08-31: the failing allocation was exactly
     * payload×2 UTF-16 on a 512MB largeHeap). [exportToWriter] streams the
     * same document without ever building that String and is what the heavy
     * callers (manual export / WebDAV upload / restore snapshot) use; this
     * path stays for KB-sized callers that genuinely want a String.
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
        artifactRoots: List<File>? = null,
        webDavConfig: WebDavConfig? = null,
        // [feat/scheduled-tasks-l0] Scheduled-tasks document from the store
        // (null = omit the section; import side skips it like a pre-L0 file).
        scheduledTasks: JSONObject? = null,
    ): String = buildWithinCap(MAX_PAYLOAD_BYTES.toLong()) { capBytes, includeArtifacts ->
        val sections = buildSections(
            providerRepo, includeSecrets, envVarRepo, skillRepo, memoryRepo,
            mcpRepo, chatRepo, chatWindowDays, artifactRoots,
            scheduledTasks = scheduledTasks,
            documentCapBytes = capBytes,
            includeArtifacts = includeArtifacts,
        )
        // Two views of the SAME tree: the document, and the document minus the
        // chat arrays. [T-backup-export-size-cap] The ladder in [buildWithinCap]
        // can only tell "shrink chat" apart from "chat is not the problem" if
        // it knows the non-chat size. Serializing the skeleton costs one ~3MB
        // string here and only on this (non-production) path.
        val skeleton = buildPayloadObject(sections, includeSecrets, webDavConfig).apply {
            remove("chatSessions")
            remove("chatMessages")
            remove("chatTruncated")
        }
        BuiltPayload(
            text = buildPayloadObject(sections, includeSecrets, webDavConfig).toString(),
            nonChatBytes = utf8Length(skeleton.toString()),
            hadArtifacts = sections.artifacts.has("archive"),
            // Only evaluated when the ladder gives up — see [oversizeMessage].
            sectionBreakdown = { describeSections(sections) },
        )
    }

    /**
     * [D-C fix/backup-bootstrap-size] One attempt of [export]'s size ladder.
     *
     * @property text the serialized document.
     * @property nonChatBytes UTF-8 size of everything EXCEPT the two chat
     *   arrays (fields / providers / skills / memory files / artifacts). This
     *   is what decides whether shrinking chat can bring the document under
     *   the cap at all: a skeleton that is already over it cannot be rescued
     *   by dropping chat, and pretending otherwise burns rebuild rounds.
     * @property hadArtifacts whether this attempt embedded the artifact zip —
     *   the only large section that is optional by design, so it is the last
     *   thing the ladder gives up.
     * @property sectionBreakdown lazy per-section sizes for the failure
     *   message. A lambda so the happy path never pays for serializing every
     *   section again (the artifact archive alone is a ~12MB base64 string).
     */
    internal class BuiltPayload(
        val text: String,
        val nonChatBytes: Long,
        val hadArtifacts: Boolean,
        val sectionBreakdown: () -> String = { "" },
    )

    /** [D-C fix/backup-bootstrap-size] How many times the ladder may shrink the
     *  chat budget before it starts giving up sections. 0.7^4 ≈ 24% of the
     *  original budget — enough to absorb any estimation drift, bounded so a
     *  pathological payload cannot spin. */
    internal const val EXPORT_CHAT_DEGRADE_ROUNDS = 4

    /** [D-C fix/backup-bootstrap-size] Per-round chat-budget shrink factor. 0.7
     *  is the largest drop that still keeps a usable prefix of history per
     *  round (halving would throw a plausible export away to save a few bytes). */
    internal const val EXPORT_CHAT_DEGRADE_FACTOR = 0.7

    /**
     * [D-C fix/backup-bootstrap-size] Produce a document that is guaranteed to
     * fit the IMPORT side's ceiling, degrading content instead of refusing.
     *
     * Why the ladder exists at all: before the unit fix, export measured
     * `payload.length` (UTF-16 code units) while import measured UTF-8 BYTES.
     * Chinese-heavy payloads sit at bytes/chars ≈ 1.08, so a 71.4MB file
     * passed export at 66.1M "chars" and was then permanently unimportable —
     * the user's only backup. With both sides in bytes the budget packing in
     * [buildSections] already keeps chat inside the cap; this ladder is the
     * belt for everything the budget cannot see (a skeleton that grew since
     * the measurement, a serializer change, an artifact zip that base64-
     * inflates past what was estimated). Losing the tail of the chat history
     * is always better than producing a file that cannot be restored.
     *
     * Order: chat rounds (×[EXPORT_CHAT_DEGRADE_FACTOR], at most
     * [EXPORT_CHAT_DEGRADE_ROUNDS]) → drop the artifact archive (once) →
     * throw with a per-section breakdown. Chat first because it is the bulk
     * and the safest to trim (the restore already reports what it dropped);
     * artifacts last because they are the agent's actual outputs.
     *
     * Extracted (rather than inlined in [export]) so the ladder is JVM-testable
     * with a fake builder: the 64MiB cap cannot be exercised in a unit test
     * without allocating 64MiB of Chinese text, but the DECISION logic can.
     */
    internal suspend fun buildWithinCap(
        capBytes: Long,
        // Headroom the DOCUMENT must keep free beyond the skeleton for chat to
        // be worth re-packing (production: SAFETY_MARGIN_BYTES). A parameter
        // because a test-scale cap (a few KB) is smaller than the real 1MB
        // margin, which would make every fake payload look skeleton-bound.
        safetyMarginBytes: Long = SAFETY_MARGIN_BYTES.toLong(),
        build: suspend (capBytes: Long, includeArtifacts: Boolean) -> BuiltPayload,
    ): String {
        var scale = 1.0
        var includeArtifacts = true
        var chatRounds = 0
        var artifactsDropped = false
        while (true) {
            val attemptCap = (capBytes * scale).toLong().coerceAtLeast(0L)
            val attempt = build(attemptCap, includeArtifacts)
            val totalBytes = utf8Length(attempt.text)
            if (totalBytes <= capBytes) return attempt.text
            val chatBytes = totalBytes - attempt.nonChatBytes
            // Can chat be the culprit? Only if something is left of it AND the
            // skeleton plus its headroom still leaves room for it.
            val chatCanHelp = chatBytes > 0 &&
                attempt.nonChatBytes + safetyMarginBytes < capBytes
            if (chatCanHelp && chatRounds < EXPORT_CHAT_DEGRADE_ROUNDS) {
                chatRounds++
                scale *= EXPORT_CHAT_DEGRADE_FACTOR
                continue
            }
            if (includeArtifacts && attempt.hadArtifacts && !artifactsDropped) {
                artifactsDropped = true
                includeArtifacts = false
                continue
            }
            throw IllegalStateException(oversizeMessage(capBytes, totalBytes, attempt))
        }
    }

    /** Which section blew the cap, and what the user can do about it. Names
     *  sizes because "too large" without a culprit left the 2026-10-27 user
     *  with no move except hand-trimming the JSON. */
    private fun oversizeMessage(
        capBytes: Long,
        totalBytes: Long,
        attempt: BuiltPayload,
    ): String {
        val chatBytes = totalBytes - attempt.nonChatBytes
        val kb = { v: Long -> "${v / 1024}KB" }
        return "Backup too large: ${totalBytes} bytes (${kb(totalBytes)}), cap " +
            "$capBytes bytes (${kb(capBytes)}). Sections: ${attempt.sectionBreakdown()}; " +
            "chat=${kb(chatBytes)} (already trimmed over " +
            "$EXPORT_CHAT_DEGRADE_ROUNDS rounds). The import side enforces the same " +
            "byte cap, so a larger file could not be restored. To fit: remove or " +
            "shrink oversized skills, trim daily memory logs, shorten the chat " +
            "history window, or exclude the artifact archive, then export again."
    }

    /** Per-section UTF-8 sizes of a built payload, largest first. Called only
     *  from [oversizeMessage]; every section is re-serialized here. */
    private fun describeSections(sections: ExportSections): String {
        val sizes = listOf(
            "fields" to sections.fields,
            "providers" to sections.providers,
            "thinkingRules" to sections.thinkingRules,
            "groups" to sections.groups,
            "envVars" to sections.envVars,
            "skills" to sections.skills,
            "memoryFiles" to sections.memoryFiles,
            "mcpServers" to sections.mcpServers,
            "artifacts" to sections.artifacts,
            "scheduledTasks" to (sections.scheduledTasks ?: JSONObject()),
        ).map { (name, value) -> name to utf8Length(value.toString()) }
            .sortedByDescending { it.second }
        return sizes.joinToString(", ") { (name, bytes) -> "$name=${bytes / 1024}KB" }
    }

    /**
     * [D-B fix/backup-bootstrap-size] Outcome of merging a backed-up WebDAV
     * server block into whatever this install already holds.
     *
     * @property config the config to persist, or null when the backup block
     *   has no url (nothing to restore — same as before this fix).
     * @property notice one line for [ImportResult.skipped] when the user has to
     *   know something happened to the password; null when the restore is
     *   complete and self-explanatory.
     */
    internal class WebDavRestorePlan(
        val config: WebDavConfig?,
        val notice: String?,
    )

    /**
     * [D-B fix/backup-bootstrap-size] Decide what the `webdavConfig` section of
     * a backup means on THIS install.
     *
     * The trap this exists to avoid: [WebDavConfigStore.save] documents that a
     * BLANK password CLEARS the stored password (the dialog pre-fills it, so a
     * blank only ever means "the user cleared it"). A secrets-free backup has
     * no password by construction, so naively handing its blanks to save()
     * would delete the password the user had just retyped — turning a restore
     * into a silent wipe of the very credential the restore was supposed to
     * leave in place. Three cases, in order:
     *
     *  1. backup carries a non-blank password (secrets export) → it wins
     *     outright. This is the family's restore-is-restore rule (providers /
     *     groups behave the same way).
     *  2. backup carries no (or a blank) password, this install has one →
     *     keep the local password, take url/username/path from the backup.
     *     Nothing is lost and nothing is cleared.
     *  3. backup carries no password and this install has none (fresh
     *     reinstall — the deadlock case) → store the three non-secret fields
     *     and tell the user the password still has to be typed. That is the
     *     whole point: three fields restored instead of zero, and the hint
     *     arrives through the existing `skipped` channel instead of a new UI.
     */
    internal fun resolveWebDavRestore(
        backup: JSONObject,
        existing: WebDavConfig?,
    ): WebDavRestorePlan {
        val url = backup.optString("url", "")
        if (url.isEmpty()) return WebDavRestorePlan(config = null, notice = null)
        val username = backup.optString("username", "")
        val path = backup.optString("path", WebDavConfig.DEFAULT_BACKUP_DIR)
        val backedUpPassword = backup.optString("password", "")
        if (backedUpPassword.isNotEmpty()) {
            return WebDavRestorePlan(
                config = WebDavConfig(url, username, backedUpPassword, path),
                notice = null,
            )
        }
        val keptPassword = existing?.password.orEmpty()
        return if (keptPassword.isNotEmpty()) {
            WebDavRestorePlan(
                config = WebDavConfig(url, username, keptPassword, path),
                notice = "webdav: 密码沿用本机现有配置（备份未包含密钥）",
            )
        } else {
            WebDavRestorePlan(
                config = WebDavConfig(url, username, "", path),
                notice = "webdav 密码需重新填写（备份未包含密钥）",
            )
        }
    }

    /**
     * [D-B fix/backup-bootstrap-size] The `webdavConfig` section for a backup
     * payload, or null when nothing should be written (no server configured).
     *
     * Deliberately a standalone pure function (JVM-testable): the gate split
     * here is the whole fix, and a text probe cannot tell "password omitted"
     * from "password present but empty". Contract:
     *  - no config → null → the section is absent (never an empty object —
     *    import keys off section presence);
     *  - `includeSecrets=false` → url / username / path only, NO `password`
     *    key at all. Import keys off the *absence* of the password field to
     *    preserve the local password — see [resolveWebDavRestore];
     *  - `includeSecrets=true` → all four fields, even a blank password
     *    (writing `"password": ""` makes the no-secrets intent explicit and
     *    round-trips to "store the blank", which is what the user had).
     *
     * If the product ever reclassifies the username as a credential, revert
     * this function to the pre-D-B single `includeSecrets &&` gate — that
     * restores the old semantics wholesale (section absent without secrets).
     */
    internal fun buildWebDavConfigSection(
        includeSecrets: Boolean,
        webDavConfig: WebDavConfig?,
    ): JSONObject? {
        if (webDavConfig == null) return null
        return JSONObject().apply {
            put("url", webDavConfig.url)
            put("username", webDavConfig.username)
            put("path", webDavConfig.path)
            if (includeSecrets) put("password", webDavConfig.password)
        }
    }

    /**
     * The document as one tree. Single source of truth for the String path and
     * the streaming skeleton, so the two can never drift on field set/order.
     */
    private fun buildPayloadObject(
        sections: ExportSections,
        includeSecrets: Boolean,
        webDavConfig: WebDavConfig?,
    ): JSONObject = JSONObject().apply {
        put("format", "openminis.config.backup")
        put("version", FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("includesSecrets", includeSecrets)
        put("fields", sections.fields)
        put("providers", sections.providers)
        put("thinkingRules", sections.thinkingRules)
        put("groups", sections.groups)
        put("envVars", sections.envVars)
        put("skills", sections.skills)
        put("memoryFiles", sections.memoryFiles)
        put("mcpServers", sections.mcpServers)
        put("artifacts", sections.artifacts)
        put("chatSessions", JSONArray(sections.chatSessions))
        put("chatMessages", JSONArray(sections.chatMessages))
        sections.chatTruncated?.let { put("chatTruncated", it) }
        // [feat/scheduled-tasks-l0] Scheduled-tasks tasks ride the user-config
        // backup (brief §6.6). Additive section: absent in pre-L0 backups, and
        // import treats absence as "nothing to restore" (no version bump).
        sections.scheduledTasks?.let { put("scheduledTasks", it) }
        // [D-B fix/backup-bootstrap-size] The WebDAV server block used to ride
        // the secrets gate AS A WHOLE, which deadlocked the one scenario it is
        // for: the config lives in EncryptedSharedPreferences, so a re-signed
        // reinstall always loses it — "restore your remote backups" demanded
        // the remote credentials, and those were the thing that was lost.
        // url / username / path are configuration, not credentials: url+path
        // only say WHERE, and username grants nothing without the password
        // (same level as CF_ACCOUNT_EMAIL, which has always travelled in
        // cleartext config). Only `password` is gated. Net effect on a fresh
        // install: three fields arrive by restore, one still has to be typed.
        buildWebDavConfigSection(includeSecrets, webDavConfig)?.let { put("webdavConfig", it) }
        if (sections.readFailures > 0) put("readFailures", sections.readFailures)
    }

    /**
     * [T-backup-streaming-export] Stream the document to [writer] without ever
     * materializing it as a String: the non-chat skeleton is stringified once
     * (~2-4MB even on a heavy install), then the chat arrays are written
     * element by element. Returns the number of unreadable items: registry
     * fields that failed to read, plus [FIX-P1A / FINDING-7] env-var values
     * that `includeSecrets` promised to carry but could not read (those also
     * get `_valueUnreadable` on their entry, so the payload itself is
     * self-reconcilable).
     *
     * Peak heap is bounded by the largest single element instead of the whole
     * document, which is what keeps a 90-day export inside a 512MB largeHeap.
     *
     * [T-backup-lazy-chat-load] That claim only holds because assembly no
     * longer materializes the whole eligible window either:
     * [buildSections] pulls message bodies one session at a time and stops at
     * the budget frontier. Before that, streaming removed the final document
     * String but the eager `sessions.map { messagesLast(...) }` pre-load put
     * the same cost back into the tree — chat-bearing exports OOMed on a
     * heavy install (measured 2026-10-09: 26MB → 511MB of java heap in six
     * seconds, `Failed to allocate … <1% of heap free after GC`).
     */
    suspend fun exportToWriter(
        providerRepo: ProviderRepository,
        includeSecrets: Boolean = true,
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
        chatRepo: ChatRepository? = null,
        chatWindowDays: Int = 90,
        artifactRoots: List<File>? = null,
        webDavConfig: WebDavConfig? = null,
        // [feat/scheduled-tasks-l0] Same document as the String export path.
        scheduledTasks: JSONObject? = null,
        writer: java.io.Writer,
    ): Int {
        // Same assembly as the String path — buildSections is the single
        // source of truth; only the serialization below differs.
        val sections = buildSections(
            providerRepo, includeSecrets, envVarRepo, skillRepo, memoryRepo,
            mcpRepo, chatRepo, chatWindowDays, artifactRoots,
            scheduledTasks = scheduledTasks,
        )
        // The skeleton is the SAME tree the String path builds, minus the chat
        // arrays: buildPayloadObject is the single source of truth for field
        // set and order, so the two paths cannot drift. JSONArray(Collection)
        // copies references only, so dropping the two arrays here costs nothing
        // and keeps skeletonJson.toString() small.
        val skeletonJson = buildPayloadObject(sections, includeSecrets, webDavConfig).apply {
            remove("chatSessions")
            remove("chatMessages")
            remove("chatTruncated")
        }
        // [audit-0917] The frame key list below is hardcoded while
        // buildPayloadObject is the single source of truth for the field set —
        // a section added there but not here silently vanished from streaming
        // exports only. Assert the two agree before emitting anything.
        val frameKeys = listOf(
            "format", "version", "createdAt", "includesSecrets", "fields",
            "providers", "thinkingRules", "groups", "envVars", "skills",
            "memoryFiles", "mcpServers", "artifacts", "chatSessions",
            "chatMessages", "chatTruncated", "webdavConfig", "readFailures",
            "scheduledTasks",
        )
        val dropped = BackupStreamWriter.missingFrameKeys(frameKeys, skeletonJson.keys().asSequence().toList())
        check(dropped.isEmpty()) {
            "exportToWriter frame list is missing ${dropped.size} section(s): $dropped — " +
                "add them to frameKeys and to the emitValue branches"
        }
        // [audit-0917] Same ceiling the String path enforces and import()
        // checks, applied while streaming so an oversize document fails here
        // (actionable) instead of producing an unrestorable backup file.
        val capped = BackupStreamWriter.CapEnforcingWriter(writer, MAX_PAYLOAD_BYTES)
        BackupStreamWriter.writeObjectFrame(
            capped,
            frameKeys,
        ) { w, key ->
            when (key) {
                "chatSessions" -> BackupStreamWriter.writeJsonArray(w, sections.chatSessions)
                "chatMessages" -> BackupStreamWriter.writeJsonArray(w, sections.chatMessages)
                "chatTruncated" -> {
                    val tr = sections.chatTruncated
                    if (tr == null) w.write("null") else w.write(tr.toString())
                }
                else -> {
                    // [fix-stream-quoting] org.json's Object.toString() on a
                    // String value returns the RAW string — the streaming frame
                    // then emits {"format":openminis.config.backup,...} with no
                    // quotes. org.json's own lenient parser accepts it (so the
                    // in-app round trip and the JVM tests stay green), but every
                    // strict parser (python json, jq, kotlinx-serialization)
                    // rejects the document — a backup file that only this app
                    // can read is a corrupt backup. String values must go
                    // through JSONObject.quote(); numbers/booleans/objects
                    // already toString() to valid JSON literals.
                    val v = skeletonJson.opt(key)
                    if (v is String) w.write(JSONObject.quote(v))
                    else w.write(v?.toString() ?: "null")
                }
            }
        }
        writer.flush()
        return sections.readFailures
    }

    private suspend fun buildSections(
        providerRepo: ProviderRepository,
        includeSecrets: Boolean = true,
        envVarRepo: EnvVarRepository? = null,
        skillRepo: SkillRepository? = null,
        memoryRepo: MemoryRepository? = null,
        mcpRepo: MCPRepository? = null,
        chatRepo: ChatRepository? = null,
        chatWindowDays: Int = 90,
        artifactRoots: List<File>? = null,
        scheduledTasks: JSONObject? = null,
        // [D-C fix/backup-bootstrap-size] The ceiling this assembly packs
        // against, in UTF-8 BYTES. Defaults to the real import cap; [export]'s
        // size ladder lowers it on retries (×0.7) so the chat packer trims
        // harder instead of the caller re-slicing a finished document.
        documentCapBytes: Long = MAX_PAYLOAD_BYTES.toLong(),
        // [D-C] The ladder's last resort: the artifact archive is the only
        // large section that is optional by design, so it can be excluded
        // outright. False = the `artifacts` section stays empty (import treats
        // an absent archive exactly like a pre-artifact backup).
        includeArtifacts: Boolean = true,
    ): ExportSections {
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
            val entryIds = JSONArray()
            for (id in orderedEntryIds(providerRepo, instance.id)) entryIds.put(id)
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

        // [T-auto-backup-assets] Custom thinking rules: per-provider user data
        // stored in Room (provider_thinking_rules). The provider wire format
        // (exportInstanceJSON) does not carry them, so they get their own
        // section, keyed by the owning provider's (providerType, label) —
        // import re-attaches them after the provider restore re-mints ids.
        val thinkingRules = JSONArray()
        for (instance in providerRepo.instances) {
            val rows = providerRepo.exportThinkingRulesJSON(instance.id) ?: continue
            if (rows.length() == 0) continue
            thinkingRules.put(JSONObject().apply {
                put("providerType", instance.providerType.name)
                put("providerLabel", instance.label)
                put("rules", rows)
            })
        }

        // [T-auto-backup-assets] Artifacts: the agent's *outputs* — files under
        // the shared folder (handoff docs, reports, tools, knowledge-graph
        // exports) plus MCP server data files (servers.json, memory-server
        // knowledge graphs). Chat transcripts are process byproduct and
        // deliberately NOT here; the auto backup is for what the agent
        // produced, not how it produced it. Selection is text-only and
        // size-capped ([ArtifactBackupScope]); the archive streams to a temp
        // file so a multi-MB set never sits fully in memory as a string.
        val artifacts = JSONObject()
        var artifactSkipped = 0
        // [D-C fix/backup-bootstrap-size] `includeArtifacts=false` is the size
        // ladder's last resort (see [buildWithinCap]); it yields an empty
        // section, which import already handles as "no archive in backup".
        if (includeArtifacts && artifactRoots != null && artifactRoots.isNotEmpty()) {
            val archiveFile = java.io.File.createTempFile("artifact-backup-", ".zip")
            try {
                var totalBytes = 0L
                var fileCount = 0
                java.io.FileOutputStream(archiveFile).use { fos ->
                    java.util.zip.ZipOutputStream(fos.buffered()).use { zos ->
                        for (root in artifactRoots) {
                            val isMcpRoot = root.name == "mcp-servers"
                            val allowed = if (isMcpRoot)
                                setOf("json", "jsonl", "md")
                            else
                                ArtifactBackupScope.DEFAULT_ALLOWED_EXTENSIONS
                            val sel = ArtifactBackupScope.select(
                                root,
                                allowedExtensions = allowed,
                            )
                            val prefix = if (isMcpRoot) "mcp" else "shared"
                            artifactSkipped += sel.oversizedSkipped + sel.nonTextSkipped
                            // [dv20-a1 A-01] A credentials-free export must not
                            // carry the MCP server config in the clear. This
                            // artifact face was the one channel that ignored
                            // `includeSecrets`, so a servers.json with
                            // headers/env/args-token/url-query rode the "mcp/"
                            // prefix into the archive while Stage 5 was busy
                            // stripping those very fields out of the
                            // `mcpServers` section. The redactor IS Stage 5's
                            // single source (stripExportedMcpSecrets) — no
                            // second rule. Gated on `!includeSecrets` so an
                            // explicit with-credentials export is untouched.
                            val transform =
                                if (isMcpRoot && !includeSecrets) ::redactMcpArtifactEntry else null
                            val dropped = ArrayList<String>()
                            totalBytes += ArtifactArchiver.writeEntries(
                                zos, root, sel.files, "$prefix/", transform, dropped,
                            )
                            fileCount += sel.files.size - dropped.size
                            artifactSkipped += dropped.size
                        }
                    }
                }
                if (fileCount > 0) {
                    val zipBytes = archiveFile.readBytes()
                    artifacts.put(
                        "archive",
                        android.util.Base64.encodeToString(zipBytes, android.util.Base64.NO_WRAP),
                    )
                    artifacts.put("archiveBytes", zipBytes.size)
                    artifacts.put("fileCount", fileCount)
                    if (artifactSkipped > 0) artifacts.put("skipped", artifactSkipped)
                }
            } finally {
                runCatching { archiveFile.delete() }
            }
        }

        // Environment variables live in EnvVarRepository (metadata in a JSON
        // file, values in encrypted prefs) — NOT in the flat ConfigRegistry
        // field space, so they need their own export pass much like providers.
        // Values are credentials, so they ride the same includeSecrets gate as
        // provider apiKeys: without secrets we carry key+note only and the user
        // refills the value after import.
        // [FIX-P1A / FINDING-7] `includeSecrets=true` used to mean "every value
        // that happened to be readable" with no trace of the rest: the payload
        // still declared `includesSecrets`, the entry still appeared, and only
        // the `value` key was silently absent — indistinguishable from "the
        // user never set one". On a device whose encrypted store is gone (the
        // exact world a restore exists for) that produced an empty-shell
        // backup the user believed was complete. The pass now ACCOUNTS for
        // every value it could not read: a per-entry `_valueUnreadable` marker
        // (the same `_`-prefixed backup-layer annotation convention as
        // `_entryIds`) plus a bump of [ExportSections.readFailures], which the
        // export callers already surface. No new top-level field, so the
        // payload contract (buildPayloadObject / the streaming frame list) is
        // untouched, and import ignores unknown keys.
        val envVars = JSONArray()
        var missingSecretValues: List<String> = emptyList()
        if (envVarRepo != null) {
            val built = buildEnvVarExportEntries(
                entries = envVarRepo.entries.value.map {
                    // [T-envvar-group-sync] group rides the metadata (never
                    // secret-gated): a restore without it silently flattens
                    // the user's grouping, which is exactly the
                    // field-evap class [T-fix-backup-field-evap] guards.
                    EnvVarBackupMeta(key = it.key, note = it.note, group = it.group)
                },
                includeSecrets = includeSecrets,
                // A THROWING read is a missing value, not a fatal export: the
                // whole point is that the gap becomes visible, not that one
                // unreadable credential costs the user their entire backup.
                readValue = { key -> runCatching { envVarRepo.getValue(key) }.getOrNull() },
            )
            for (i in 0 until built.entries.length()) {
                built.entries.optJSONObject(i)?.let { envVars.put(it) }
            }
            missingSecretValues = built.missingSecretValues
            if (missingSecretValues.isNotEmpty()) {
                readFailures += missingSecretValues.size
                Log.w(
                    TAG,
                    "export: ${missingSecretValues.size} env var value(s) unreadable, " +
                        "carried key only: ${summariseIds(missingSecretValues)}",
                )
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
                    // [sweep1013-D1/F-8] body is frontmatter-STRIPPED by
                    // parseSkillMd, so a body-only fallback loses every
                    // frontmatter-only key (`subagent: true`, `max_turns`).
                    // Carry the raw block alongside; restore re-joins them
                    // before importFromContent re-parses. "" when the source
                    // had none — the import side treats it as absent.
                    entry.put("frontmatter", skill.frontmatter)
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
            // [FIX-E / ISSUE-09] listAllFiles() deliberately hides the distilled
            // MEMORY-ROLLUP.md from the settings list — memoryBackupEntries()
            // puts it back for the payload only, so a restored device keeps the
            // distilled rules the daily logs were folded into.
            val entries = memoryBackupEntries(
                listed = runCatching { memoryRepo.listAllFiles() }
                    .getOrDefault(emptyList()).map { it.name },
            ) { name -> runCatching { memoryRepo.readFile(name) }.getOrNull() }
            for ((name, content) in entries) {
                memoryFiles.put(JSONObject().apply {
                    put("name", name)
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
                // [sweep1013-D1/F-3] Self-filled headers/env ARE credentials
                // (Authorization: Bearer …, X-Api-Key, GITHUB_TOKEN). They ride
                // the same includeSecrets gate as provider apiKeys / envVar
                // values: without secrets keep the key names (so the server
                // still imports and the user sees what to refill) but blank
                // the values. [review G1] exportServerJSON emits the nested
                // wrapper {"mcpServers":{"<id>":{…}}} — the entry sits one
                // level down, so strip through stripExportedMcpSecrets (the
                // wrapper itself has no headers/env/args/url to strip).
                if (!includeSecrets) stripExportedMcpSecrets(obj)
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
        val chatSessionList = mutableListOf<JSONObject>()
        val chatMessageList = mutableListOf<JSONObject>()
        var chatTruncated: JSONObject? = null
        if (chatRepo != null && chatWindowDays > 0) {
            // Serialize the non-chat skeleton ONCE to measure its exact
            // serialized cost IN UTF-8 BYTES (the unit the import side
            // enforces — see the [ChatBackupBudget] header); the chat sections
            // get whatever budget is left under MAX_PAYLOAD_BYTES.
            // SAFETY_MARGIN_BYTES absorbs the JSON
            // escaping / separators between the skeleton and the chat arrays
            // plus any drift between the estimate and the final document.
            //
            // [T-backup-lazy-chat-load] Take the measurement only. Binding the
            // string to a val here kept ~40MB of UTF-16 reachable for the
            // whole chat-packing block below, on top of the tree it was
            // rendered from and the arrays that accumulate during packing.
            val skeletonBytes = JSONObject().apply {
                put("format", "openminis.config.backup")
                put("version", FORMAT_VERSION)
                put("createdAt", System.currentTimeMillis())
                put("includesSecrets", includeSecrets)
                put("fields", fields)
                put("providers", providers)
                put("thinkingRules", thinkingRules)
                put("groups", groups)
                put("envVars", envVars)
                put("skills", skills)
                put("memoryFiles", memoryFiles)
                put("mcpServers", mcpServers)
                put("artifacts", artifacts)
                put("chatSessions", JSONArray())
                put("chatMessages", JSONArray())
                // Mirror buildPayloadObject's conditional sections so their
                // serialized cost lands inside the measured skeleton — the
                // SAFETY_MARGIN only absorbs escaping/separators, not a whole
                // section the list forgot. (chatTruncated is null at measure
                // time; webdavConfig isn't in buildSections scope.)
                scheduledTasks?.let { put("scheduledTasks", it) }
                if (readFailures > 0) put("readFailures", readFailures)
            }.toString().let(::utf8Length)

            val cutoff = System.currentTimeMillis() - chatWindowDays * 24L * 3600 * 1000
            val sessions = runCatching {
                chatRepo.dao.sessionsUpdatedSince(cutoff)
            }.getOrDefault(emptyList())

            // DAO contract: sessions ordered by updatedAt DESC; messagesLast
            // returns newest-first. That is exactly the packing order — the
            // most recent context always lands in the backup first.
            //
            // [T-backup-lazy-chat-load] Only the metadata (one row per
            // session, a few hundred bytes each) is materialized up front.
            // Message bodies are pulled per session by the packer, and it
            // stops pulling at the budget frontier. Materializing every
            // eligible body first — which the previous
            // `sessions.map { ... messagesLast(...) }` did, with raw
            // parts_json that the per-part caps never apply to on the INPUT
            // side — made peak heap grow with the whole window instead of
            // with the backup, so a heavy install OOMed before the budget
            // could trim anything.
            val sessionJsons = sessions.map { session ->
                JSONObject().apply {
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
            }

            val packed = packChatHistoryWithBudgetLazily(
                skeletonBytes = skeletonBytes,
                // [D-C fix/backup-bootstrap-size] Bytes on both ends: the cap
                // the import side enforces (readSafBytes + import's byte gate)
                // and the margin are UTF-8 bytes, and so is every charge the
                // packer makes. `documentCapBytes` is lowered by export()'s
                // size ladder on retries, which is why this is not the
                // constant directly.
                budgetTotalBytes = documentCapBytes - SAFETY_MARGIN_BYTES,
                sessionJsons = sessionJsons,
                messagesAt = { index ->
                    runCatching {
                        chatRepo.dao.messagesLast(
                            sessions[index].id, MAX_CHAT_MESSAGES_PER_SESSION,
                        )
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
                },
                sanitize = ::sanitizeChatParts,
                capReasoning = ::capReasoningContent,
            )
            for (s in packed.sessions) chatSessionList.add(s)
            for (m in packed.messages) chatMessageList.add(m)

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

        return ExportSections(
            fields = fields,
            readFailures = readFailures,
            providers = providers,
            thinkingRules = thinkingRules,
            groups = groups,
            envVars = envVars,
            skills = skills,
            memoryFiles = memoryFiles,
            mcpServers = mcpServers,
            artifacts = artifacts,
            chatSessions = chatSessionList,
            chatMessages = chatMessageList,
            chatTruncated = chatTruncated,
            scheduledTasks = scheduledTasks,
        )
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
        artifactRoots: List<File>? = null,
        onWebDavConfig: ((WebDavConfig) -> Unit)? = null,
        // [feat/scheduled-tasks-l0] Restores the scheduled-tasks document via
        // the store (caller supplies the suspension point — this class has no
        // Context). Absent/null section = pre-L0 backup, stage skipped.
        // [FIX-1028 / A-16] Returns what the store DID: the store refuses to
        // overwrite a non-empty local task set with an empty/unknown-version
        // section, and that refusal has to reach the user instead of being
        // reported as `scheduledTasksImported = true`. Null = caller has no
        // report (treated as applied, which is the pre-fix contract).
        onScheduledTasks: (suspend (JSONObject) -> ScheduledTasksRestoreOutcome?)? = null,
        // [D-B fix/backup-bootstrap-size] The WebDAV server config this install
        // currently holds (caller reads it from WebDavConfigStore — this class
        // has no Context). Needed because a secrets-free backup carries no
        // password, and [WebDavConfigStore.save] treats a blank password as
        // "clear the stored one": applying such a backup blind would wipe the
        // password the user just retyped. Null = nothing stored here, so a
        // missing backup password really does mean "ask the user".
        existingWebDavConfig: WebDavConfig? = null,
    ): ImportResult {
        // [fix-audit-p1-2] Reject oversized documents BEFORE any parsing /
        // decoding: a backup with embedded skill archives or chat history is
        // Base64-decoded into full byte arrays in memory, so an unbounded
        // payload is an OOM door. This check runs on the raw string, before
        // JSONTokener allocates the parsed tree.
        // [D-C fix/backup-bootstrap-size] Measured in UTF-8 BYTES — the same
        // unit the SAF read cap (BackupSettingsScreen) and the export-side
        // budget use. It used to be `.length` (UTF-16 code units) while the
        // reader above it capped bytes, so every non-ASCII document was
        // judged by two different rulers.
        if (documentExceedsPayloadCap(json)) {
            throw InvalidBackupException(
                "Backup too large (${utf8Length(json)} bytes, max $MAX_PAYLOAD_BYTES)"
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
        var thinkingRulesImported = 0
        var artifactFilesImported = 0
        var webdavConfigImported = false
        var scheduledTasksImported = false
        // [T-auto-backup-assets] Stage 1 records every processed provider's
        // (providerType, label) → restored instance id so the thinking-rules
        // stage can re-attach rules to the instance that now owns the label
        // (merge and append paths both re-mint or reuse ids).
        val providerKeyToId = HashMap<String, String>()
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
                    // [T-backup-restore-credentials] A restore applies the
                    // backup's credentials to the existing instance.
                    val merged = providerRepo.mergeImportInstanceJSON(
                        obj.toString(), srcEntryIds,
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
                    // [T-auto-backup-assets] Record the restored instance id for
                    // thinking-rule re-attachment (keyed by type+label, the
                    // natural key the rules export uses).
                    val instId = providerRepo.instances.firstOrNull {
                        it.label == resolvedLabel &&
                            it.providerType.name == obj.optString("providerType", "")
                    }?.id
                    if (instId != null) {
                        // [da1031-A] Key by the BACKUP's original label, not the
                        // possibly auto-renamed one: Stage 1.5 looks rules up by
                        // the (providerType, providerLabel) pair the rules export
                        // carried, and importInstanceJSON's conflict rename
                        // ("$label ($suffix)") never appears there. Keying by
                        // resolvedLabel made every renamed import lose its whole
                        // thinking-rules group.
                        providerKeyToId[
                            obj.optString("providerType", "") + "\u0000" + label
                        ] = instId
                    }
                } catch (t: Throwable) {
                    skipped.add("provider \"$label\": ${t.message ?: "import failed"}")
                }
            }
        }

        // -- Stage 1.5: custom thinking rules (re-attach to restored providers) --
        // Rules ride a (providerType, providerLabel) key because the provider
        // restore above re-minted instance ids. A rule whose provider did not
        // survive import (skipped/rejected) is reported and skipped. Restore
        // REPLACES the instance's rules with the backup's — a restore is a
        // restore, and the pre-restore snapshot is the rollback safety net.
        val thinkingRules = root.optJSONArray("thinkingRules")
        if (thinkingRules != null) {
            for (i in 0 until thinkingRules.length()) {
                val group = thinkingRules.optJSONObject(i) ?: continue
                val type = group.optString("providerType", "")
                val label = group.optString("providerLabel", "")
                val rules = group.optJSONArray("rules") ?: continue
                val instId = providerKeyToId["$type\u0000$label"]
                if (instId == null) {
                    skipped.add("thinking rules for \"$label\" (provider not restored)")
                    continue
                }
                try {
                    thinkingRulesImported += providerRepo.restoreThinkingRules(instId, rules)
                } catch (t: Throwable) {
                    skipped.add("thinking rules for \"$label\": ${t.message ?: "restore failed"}")
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
                        // [dv20-a1 A-02] A System voice sentinel is virtual —
                        // it is never in the backup's `modelEntries`, so the
                        // entry map cannot know it and the member used to be
                        // counted as dropped. The sentinel id is
                        // device-independent (byte-identical to iOS), so
                        // remap fidelity means keeping it verbatim.
                        val mapped = entryIdMap[old]
                            ?: old.takeIf { SystemVoiceEntries.isSystemEntryId(it) }
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
                    // [fix/dv18-4 A-07] A restore is a restore: the group
                    // section carries the run-config (strategy / fallback /
                    // per-group thinking level / context limits), so a
                    // same-name merge must restore it too — the same shape as
                    // Stage 1's mergeImportInstanceJSON, where a same-label
                    // restore DOES restore the instance's run-config. Members
                    // stay a UNION (additive, above); the run-config is
                    // backup-wins, and the pre-restore snapshot is the
                    // rollback safety net.
                    // ponytail: members union + run-config backup-wins | 天花板:
                    // 用户想「只并成员、保留本地运行配置」时无处表达 | 升级触发:
                    // 真机反馈恢复后分组配置被改写 ⇒ 在导入 UI 加显式 run-config
                    // 开关，不在本层猜。
                    val merged = applyGroupRunConfig(
                        existingGroup,
                        readGroupRunConfig(g, warnGroupRunConfig),
                    ).copy(memberEntryIds = mergedMembers)
                    if (addedAny || merged != existingGroup) {
                        providerRepo.updateGroup(merged)
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
                    // [fix/dv18-4 A-07] Same decode as the merge branch above —
                    // one seam, two appliers (mirrors Stage 1's
                    // readImportInstanceFields / applyImportInstanceFields).
                    val runConfig = readGroupRunConfig(g, warnGroupRunConfig)
                    val group = ModelGroup(
                        id = newId,
                        name = resolvedName,
                        memberEntryIds = members,
                        strategy = runConfig.strategy.value,
                        fallbackStrategy = runConfig.fallbackStrategy.value,
                        defaultThinkingLevel = runConfig.defaultThinkingLevel.value,
                        contextLimitTokens = runConfig.contextLimitTokens.value,
                        lastContextLimitTokens = runConfig.lastContextLimitTokens.value,
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
            val keys = fields.keys()
            while (keys.hasNext()) {
                val path = keys.next()
                val field = registry.resolveField(path)
                if (field == null) {
                    // Field was removed or renamed since the backup was taken.
                    skipped.add("$path: no longer exists in this version")
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
                // [FIX-P1A / RT-S5] Rewrite the group/entry ids these fields
                // carry from source ids to the ids just minted above. An id the
                // maps cannot resolve means the group/entry it names is not in
                // this backup at all: writing it through either threw (primary /
                // sub / voice group → the restore refused a value the backup
                // did carry) or had the remainder silently filtered out (agent
                // loop lists → a second import CLEARED the local list). Skip the
                // field, keep the local value, and name it in `skipped`.
                val remap = remapDefaultsIds(path, decoded, groupIdMap, entryIdMap)
                val value = remap.value
                if (value == null) {
                    skipped.add(
                        "$path: referenced id(s) not present in this backup " +
                            "(${summariseIds(remap.unmapped)}) — local value kept",
                    )
                    continue
                }
                if (remap.unmapped.isNotEmpty()) {
                    skipped.add(
                        "$path: ${remap.unmapped.size} id(s) not present in this backup " +
                            "(${summariseIds(remap.unmapped)}) — dropped, rest applied",
                    )
                }
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
                // A backup taken without secrets carries no value; add the key
                // with an empty value so the metadata/note survive and the user
                // only has to refill the secret rather than recreate the entry.
                val value = ev.optString("value", "")
                val note = ev.optString("note", "")
                // [T-envvar-group-sync] restore the grouping label when the
                // backup carries it; old backups fall back to "" (uncategorized)
                // exactly like a fresh entry.
                val group = ev.optString("group", "")
                // [T-envvar-restore-deadlock] A key that already exists is no
                // longer skipped wholesale — that made the reinstall case
                // unrecoverable (secret-free restore creates empty shells, and
                // every later restore with secrets was skipped, so the values
                // could never come back). planEnvVarRestore() decides per
                // branch: an empty local value takes the backup's, a live one
                // that differs is kept and reported, and a secret-free backup
                // writes no value at all.
                val existing = envVarRepo.entries.value
                    .firstOrNull { it.key.equals(key, ignoreCase = true) }
                if (existing != null) {
                    val localValue = envVarRepo.getValue(existing.key)
                    val plan = planEnvVarRestore(
                        key = existing.key,
                        localValue = localValue,
                        localNote = existing.note,
                        localGroup = existing.group,
                        backupValue = value,
                        backupNote = note,
                        backupGroup = group,
                    )
                    if (!plan.touchesAnything) {
                        plan.report?.let { skipped.add(it) }
                        continue
                    }
                    // update() always writes the value, so hand it the one that
                    // must end up stored (the plan's, else the local one).
                    val updated = envVarRepo.update(
                        id = existing.id,
                        newKey = existing.key,
                        newValue = plan.value ?: localValue.orEmpty(),
                        newNote = plan.note ?: existing.note,
                        newGroup = plan.group ?: existing.group,
                    )
                    if (updated) {
                        if (plan.writesValue) envVarsImported++
                        plan.report?.let { skipped.add(it) }
                    } else {
                        skipped.add("env var \"$key\": rejected (invalid key)")
                    }
                    continue
                }
                if (envVarRepo.add(key, value, note, group)) {
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
                            // [fix/audit-b22 / T4-L8] Pass the exported id so a
                            // skill renamed since the backup is refreshed in
                            // place instead of being duplicated.
                            // [fix/audit-1015-b2a] Same source passthrough as
                            // the body branch below: hard-coding FILE in
                            // importFromArchive rewrote every restored skill's
                            // importSource to "file", and a URL-imported skill
                            // then failed updateFromURL's importSource != URL
                            // guard, losing its online updates for good.
                            skillRepo.importFromArchive(
                                ByteArrayInputStream(bytes),
                                s.optString("id", "").takeIf { it.isNotEmpty() },
                                SkillRepository.ImportSource.from(s.optString("importSource", "file")),
                                s.optString("sourceURL", "").takeIf { it.isNotEmpty() },
                            )
                        }
                    } else {
                        val body = s.optString("body", "")
                        if (body.isBlank()) null
                        else {
                            // [sweep1013-D1/F-8] The export fallback carries the
                            // raw frontmatter block alongside the stripped body.
                            // Re-join before importFromContent re-parses, or
                            // frontmatter-only keys (`subagent: true`) evaporate.
                            // Blank (older backups) → body-only, as before.
                            val frontmatter = s.optString("frontmatter", "")
                            val content = if (frontmatter.isBlank()) body
                                else frontmatter + "\n" + body
                            skillRepo.importFromContent(
                                content,
                                SkillRepository.ImportSource.from(s.optString("importSource", "file")),
                                s.optString("sourceURL", "").takeIf { it.isNotEmpty() },
                                // [fix/audit-b22 / T4-L8] Same as the archive branch.
                                s.optString("id", "").takeIf { it.isNotEmpty() },
                            )
                        }
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
                    // [fix/audit-1015-b2b] Export wrote installedAt/updatedAt/
                    // useCount; import never read them back, so a restore
                    // zeroed useCount and re-stamped both timestamps. Older
                    // backups without these keys decode as 0 and are ignored
                    // (restoreUsageMetadata applies only >0 values).
                    runCatching {
                        skillRepo.restoreUsageMetadata(
                            id = imported.id,
                            installedAt = s.optLong("installedAt", 0L),
                            updatedAt = s.optLong("updatedAt", 0L),
                            useCount = s.optDouble("useCount", 0.0),
                        )
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
        // [dv28-a5 A-05] Stage 9 ALSO restores `mcp/servers.json` out of the
        // artifact archive, and that copy is verbatim (whole-file overwrite,
        // ArtifactArchiver.extractZipByPrefix). When this stage merged the
        // backup's server list, the archive entry landing afterwards rewrote
        // servers.json with the BACKUP's list: every server this device has but
        // the backup does not was silently erased, and the in-memory merge
        // survived only until the next cold start re-read the file. Derived from
        // the same two operands that gate the merge below, so a backup WITHOUT
        // an `mcpServers` section (archive-only, hand-crafted) still restores
        // its list raw — skipping there would lose it entirely.
        val serverListMergedInStage7 = mcpRepo != null && mcpArr != null && mcpArr.length() > 0
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
                importChatSections(
                    chatRepo = chatRepo,
                    chatSessionsArr = chatSessionsArr,
                    chatMessagesArr = chatMessagesArr,
                    skipped = skipped,
                    // [FIX-E / ISSUE-09] session modelBinding rows carry the
                    // source install's group/entry uuids — remap them onto the
                    // ids minted by Stages 1-2 or restoreFromBinding degrades
                    // every restored session to its stale modelId.
                    groupIdMap = groupIdMap,
                    entryIdMap = entryIdMap,
                )
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
            // [FIX-P1B / RT-S1] The converter (OpenMinisBackupCompat) records a
            // missing JSONL shard as `shardsMissing` when the source package has
            // a hole in its shard sequence. A hole is permanent message loss on
            // this side, and the reader before P1-B simply stopped at it — so a
            // package that was visibly incomplete imported as if it were whole.
            // `ImportResult.skipped` is already the channel the backup screen
            // shows, so no new UI. `optInt(k, 0)` keeps every package written
            // before this key existed on the old path. Both kinds of
            // incompleteness (exporter trim + package hole) are accounted in
            // [chatTruncationNotes]; this call site only forwards its lines.
            skipped.addAll(chatTruncationNotes(tr))
        }

        // [FIX-1028 / A-6] What the `.minisbak` converter could not carry over
        // (or carried over degraded). Absent section = document did not come
        // from that converter, or it had nothing to report — same shape either
        // way, so old payloads are unaffected.
        skipped.addAll(conversionNotes(root.optJSONArray("conversionNotes")))

        // -- Stage 9: artifact files (shared/ + mcp data, from the embedded zip) --
        // The archive routes entries by first path segment into the matching
        // root ("shared/" → sharedRoot, "mcp/" → mcpRoot). Artifact files are
        // restored only when the caller supplies artifact roots (manual and
        // auto-backup restores do; a restore context without them skips).
        val artifactsObj = root.optJSONObject("artifacts")
        if (artifactsObj != null && artifactRoots != null && artifactRoots.isNotEmpty()) {
            val archive = artifactsObj.optString("archive", "")
            val estimatedBytes = (archive.length / 4L) * 3L
            if (archive.isNotEmpty() && estimatedBytes <= MAX_ARTIFACT_ARCHIVE_BYTES) {
                try {
                    val bytes = android.util.Base64.decode(archive, android.util.Base64.NO_WRAP)
                    val tmp = java.io.File.createTempFile("artifact-restore-", ".zip")
                    try {
                        tmp.writeBytes(bytes)
                        val destByPrefix = HashMap<String, File>()
                        for (root in artifactRoots) {
                            val prefix = if (root.name == "mcp-servers") "mcp" else "shared"
                            destByPrefix[prefix] = root
                        }
                        // [da1034-A1] Pre-fix archives (and any hand-crafted
                        // one) can still carry "mcp/oauth/…" bridge entries —
                        // live bearer tokens. extractZipByPrefix writes
                        // entries verbatim, so the ONLY safe place to drop
                        // them is BEFORE extraction: restoring an old backup
                        // must never overwrite this device's live token
                        // bridge. The archive the exporter writes is already
                        // oauth-free (ArtifactBackupScope.DENIED_DIRS); this
                        // filter is the restore-side second wall.
                        val filtered = java.io.File.createTempFile("artifact-restore-", ".zip")
                        try {
                            // [dv28-a5 A-05] The filter has a second job besides
                            // the credential wall: Stage 7 already merged the
                            // backup's server list (MCPRepository.importJSON keeps
                            // this device's entries), and a verbatim copy of
                            // `mcp/servers.json` would overwrite that merged file
                            // with the backup's list. Skipped only when the merge
                            // actually ran — see serverListMergedInStage7.
                            val filterResult = stripMcpOAuthEntries(
                                tmp,
                                filtered,
                                dropPrestagedServerList = serverListMergedInStage7,
                            )
                            if (filterResult.oauthDropped > 0) {
                                skipped.add(
                                    "artifacts: skipped ${filterResult.oauthDropped} " +
                                        "oauth bridge entries"
                                )
                            }
                            if (filterResult.prestagedDropped > 0) {
                                skipped.add(
                                    "artifacts: servers.json restored via Stage 7 merge " +
                                        "(skipped raw copy)"
                                )
                            }
                            artifactFilesImported =
                                ArtifactArchiver.extractZipByPrefix(filtered, destByPrefix)
                        } finally {
                            runCatching { filtered.delete() }
                        }
                    } finally {
                        runCatching { tmp.delete() }
                    }
                } catch (t: Throwable) {
                    skipped.add("artifacts: ${t.message ?: "extract failed"}")
                }
            } else if (archive.isEmpty()) {
                skipped.add("artifacts: archive empty in backup")
            } else {
                skipped.add("artifacts: archive too large (${estimatedBytes} bytes)")
            }
        } else if (artifactsObj != null && artifactsObj.has("archive") && artifactRoots == null) {
            skipped.add("artifacts: not restorable in this context")
        }

        // -- webdavConfig (server settings for the next restore) --
        // Restores (manual and the auto-backup path) apply the backed-up
        // WebDAV server config so a new install can immediately reach its
        // remote backups.
        //
        // [D-B fix/backup-bootstrap-size] A secrets-free backup carries
        // url/username/path but NO password (see [buildWebDavConfigSection]).
        // [resolveWebDavRestore] decides the merge — critically, it never emits
        // a blank password over a stored one, because [WebDavConfigStore.save]
        // reads a blank password as "clear it".
        val wdObj = root.optJSONObject("webdavConfig")
        if (wdObj != null) {
            val plan = resolveWebDavRestore(wdObj, existingWebDavConfig)
            if (plan.config != null && onWebDavConfig != null) {
                onWebDavConfig(plan.config)
                webdavConfigImported = true
                plan.notice?.let { skipped.add(it) }
            } else if (onWebDavConfig == null) {
                skipped.add("WebDAV server config: not restorable here")
            }
        }

        // -- scheduledTasks (brief §6.6: the whole tasks.json rides the
        // user-config backup) --
        // The document is handed over verbatim; the store parses it through
        // the same defensive codec as the live file (malformed entries drop,
        // never the whole file). globalEnabled is preserved as authored in the
        // backup — §5.4's factory-off only governs fresh installs; a backup is
        // the user's explicit configuration snapshot.
        val scheduledObj = root.optJSONObject("scheduledTasks")
        if (scheduledObj != null) {
            if (onScheduledTasks != null) {
                try {
                    val outcome = onScheduledTasks(scheduledObj)
                    if (outcome == null || outcome.applied) {
                        scheduledTasksImported = true
                    }
                    outcome?.notice?.let { skipped.add("scheduled tasks: $it") }
                } catch (t: Throwable) {
                    skipped.add("scheduled tasks: restore failed (${t.message})")
                }
            } else {
                skipped.add("scheduled tasks: not restorable here")
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
            thinkingRulesImported = thinkingRulesImported,
            artifactFilesImported = artifactFilesImported,
            webdavConfigImported = webdavConfigImported,
            scheduledTasksImported = scheduledTasksImported,
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
                    "thinkingRules=$thinkingRulesImported artifacts=$artifactFilesImported " +
                    "webdavConfig=$webdavConfigImported " +
                    "skipped=${result.skipped.size} hadSecrets=${result.hadSecrets}"
            )
            for (line in result.skipped) Log.w(TAG, "import skipped — $line")
        }
    }

    /**
     * [FIX-P1B / RT-S1] The user-facing "this package was not whole" lines for
     * an import, derived from the payload's `chatTruncated` object.
     *
     * Two independent kinds of incompleteness live in that one object and they
     * must NOT be conflated:
     *  - `sessionsDropped` / `messagesDropped` — the exporter deliberately
     *    trimmed the history to fit its byte budget. The source device still
     *    has everything (this is the pre-existing contract).
     *  - `shardsMissing` — the source PACKAGE has a hole in its JSONL shard
     *    sequence. Messages are gone here and the exporter never knew: before
     *    P1-B the reader treated the hole as end-of-sequence, so the tail of
     *    the transcript vanished silently and the import looked clean.
     *
     * Pure on purpose (no repos, no I/O): the call site in [import] only
     * forwards these lines into `ImportResult.skipped`, which the backup screen
     * already renders. Dead-literal tested in ChatTruncationNotesTest.
     */
    internal fun chatTruncationNotes(tr: JSONObject): List<String> {
        val out = ArrayList<String>(2)
        val s = tr.optInt("sessionsDropped", 0)
        val m = tr.optInt("messagesDropped", 0)
        if (s > 0 || m > 0) {
            out.add(
                "chat history was budget-trimmed at export: " +
                    "$m message(s) and $s session(s) not carried (full history stays on the source device)"
            )
        }
        val shards = tr.optInt("shardsMissing", 0)
        if (shards > 0) {
            out.add(
                "backup package was incomplete: $shards data shard(s) missing " +
                    "(chat/rule history restored partially; the source device still holds the full copy)"
            )
        }
        // [FIX-1028 / A-5] Third kind: the shard was there but a line inside it
        // was not parseable JSON (transfer cut mid-line, half-written shard).
        // The converter used to drop such a line silently, so a package that
        // lost records to damage looked exactly like a complete one.
        val unparsable = tr.optInt("unparsableLines", 0)
        if (unparsable > 0) {
            out.add(
                "backup package held $unparsable unreadable record line(s) " +
                    "(damaged/truncated JSONL in the source package: those entries are not in this restore)"
            )
        }
        return out
    }

    /**
     * [FIX-1028 / A-6] The user-facing lines for the `.minisbak` converter's own
     * warnings (`conversionNotes` in the converted document).
     *
     * [OpenMinisBackupCompat] collects them while converting — a provider
     * carried over with zero model entries, a skill whose archive failed to
     * pack, a memory file over the size cap — and used to throw the list away:
     * nothing in the tree read it, so a thinner-than-expected restore came with
     * no explanation at all. `ImportResult.skipped` is the channel the backup
     * screen already renders, so no new UI and no new key elsewhere.
     *
     * Pure on purpose (no repos, no I/O), like [chatTruncationNotes].
     */
    internal fun conversionNotes(notes: JSONArray?): List<String> {
        if (notes == null) return emptyList()
        val out = ArrayList<String>(notes.length())
        for (i in 0 until notes.length()) {
            val line = notes.optString(i, "").trim()
            if (line.isNotEmpty()) out.add("conversion: $line")
        }
        return out
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
     *  Rollback candidates; older ones are pruned by [writeSnapshot].
     *  [fix-snapshot-keep-1] Reduced 5 -> 1: each full payload is 70MB+
     *  (chat history included), so keeping 5 pinned up to ~350MB of local
     *  storage for a safety net whose only job is rolling back the restore
     *  that just happened. One rollback point (the newest) keeps that
     *  protection at a fraction of the cost. */
    const val SNAPSHOT_KEEP = 1

    /** [FIX-P1A / RT-S3] Snapshot file naming COMES FROM HERE — never re-spelled
     *  in the writer, the allocator or the listing filter (three places that
     *  must agree, or a half-written temp becomes a rollback candidate). */
    private const val SNAPSHOT_PREFIX = "rikkaminis-snapshot-"

    /** `.json` — the only extension [listSnapshots] accepts. */
    private const val SNAPSHOT_SUFFIX = ".json"

    /** [FIX-P1A / RT-S3] Sidecar a snapshot is streamed into before the rename.
     *  `name.json.tmp` deliberately does NOT satisfy the `.json` listing filter. */
    private const val SNAPSHOT_TMP_SUFFIX = ".tmp"

    /** [FIX-P1A / RT-S3] Age at which an orphaned `.json.tmp` is swept. A full
     *  snapshot write is seconds even on a heavy install (70MB streamed), so an
     *  hour is three orders of magnitude of slack — it cannot race a live write,
     *  and it keeps a killed write's partial file from lingering forever. */
    internal const val SNAPSHOT_TMP_STALE_MS = 60 * 60 * 1000L

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

    /**
     * [D-C fix/backup-bootstrap-size] UTF-8 size of [value], i.e. how many
     * bytes the document occupies on disk / in the SAF stream.
     *
     * THE single unit of every backup size decision. Before this helper the
     * export side compared `String.length` (UTF-16 code units) against
     * [MAX_PAYLOAD_BYTES] while the import side compared BYTES — for Chinese
     * text (bytes/chars ≈ 1.08) that made a 71.4MB file pass export at 66.1M
     * "chars" and then be rejected forever on import. Every gate must call
     * this, never `.length`.
     *
     * Computed by walking the chars (1/2/3 bytes, 4 for a surrogate pair)
     * instead of `toByteArray().size`: allocating a second copy of a 70MB
     * string just to measure it is exactly the OOM shape the streaming export
     * was built to avoid. A LONE surrogate counts as 3 bytes while Android's
     * encoder writes '?' (1 byte) for it, so this over-estimates by at most
     * 2 bytes per broken char — an over-estimate can only make the packer
     * trim slightly more, never produce an oversize file.
     */
    internal fun utf8Length(value: CharSequence): Long {
        var bytes = 0L
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < n &&
                    Character.isLowSurrogate(value[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    /**
     * [D-C fix/backup-bootstrap-size] The import-side ceiling test, in the ONE
     * unit that matters (UTF-8 bytes). Extracted so it can be exercised against
     * a small [capBytes] in a JVM test — the real 64MiB cap needs a 64MiB
     * document to trip, which no unit test should allocate; the ruler and the
     * comparison can still be pinned.
     */
    internal fun documentExceedsPayloadCap(
        document: String,
        capBytes: Long = MAX_PAYLOAD_BYTES.toLong(),
    ): Boolean = utf8Length(document) > capBytes

    /** [T-auto-backup-assets] Upper bound for a decoded artifact archive. The
     *  export side caps the selection at [ArtifactBackupScope.MAX_ARCHIVE_BYTES]
     *  (~12MB → ~16MB base64); this import-side ceiling mirrors it with slack
     *  for hand-edited / future payloads, checked BEFORE the Base64 decode so
     *  an oversized blob can't spike memory. */
    const val MAX_ARTIFACT_ARCHIVE_BYTES = 20L * 1024 * 1024

    /** [fix-audit-p0-3] Snapshot files live under `filesDir/backup-snapshots`,
     *  named with second precision so two restores in the same minute can't
     *  clobber each other (the old minute-precision [suggestedFileName] did
     *  exactly that — restoring A then B overwrote A's rollback point). The
     *  distinct `rikkaminis-snapshot-` prefix also keeps them out of the
     *  WebDAV remote-list matcher (`rikkaminis-backup-*`).
     *
     *  [FIX-P1A / RT-S3] Second precision is only the BASE name now — see
     *  [allocateSnapshotFile], which resolves collisions against the directory
     *  instead of trusting the clock. */
    fun snapshotFileName(now: Long = System.currentTimeMillis()): String {
        // [FIX-1028 / B-12] The stamp shape lives in BackupRotation, because
        // listSnapshots + pruneSnapshots now READ it back (a writer that
        // re-spelled the format would silently make every snapshot unorderable,
        // and an unorderable snapshot is never rotated away).
        return "$SNAPSHOT_PREFIX${BackupRotation.stamp(now)}$SNAPSHOT_SUFFIX"
    }

    /**
     * [FIX-P1A / RT-S3] A snapshot file name that is free in [dir] RIGHT NOW.
     *
     * Why existence (not time): the old name was the clock at second
     * precision, so two restores inside one second wrote the same path — the
     * second write truncated the first, and a failure half-way through it left
     * zero usable rollback points instead of one. Milliseconds would only move
     * the window: world W7 is a clock that jumps (manual change, NTP, a device
     * that boots with a stale RTC), and any time-derived name can then land on
     * a name that already exists. Resolving against the directory is
     * clock-proof, keeps the readable second-precision stem (so the files stay
     * sortable/greppable by name for a human), and needs no persisted state.
     * Same-second writes get `-2`, `-3`, … instead of overwriting.
     *
     * The `.tmp` sidecar is checked too: a write that died mid-stream leaves
     * one behind, and reusing that name would mean reusing a half-written
     * buffer.
     */
    internal fun allocateSnapshotFile(
        dir: java.io.File,
        now: Long = System.currentTimeMillis(),
    ): java.io.File {
        val base = snapshotFileName(now)
        val stem = base.removeSuffix(SNAPSHOT_SUFFIX)
        if (snapshotNameInUse(dir, base)) {
            var n = 2
            while (snapshotNameInUse(dir, "$stem-$n$SNAPSHOT_SUFFIX")) {
                // Bounded: every snapshot in a second costs a full payload
                // write (~70MB), so 1000 collisions inside one second is not a
                // slow disk, it is a bug. Fail closed rather than overwrite the
                // only rollback point.
                if (n >= 1000) {
                    throw java.io.IOException(
                        "snapshot name space exhausted in ${dir.name} (1000 names for $stem)"
                    )
                }
                n++
            }
            return java.io.File(dir, "$stem-$n$SNAPSHOT_SUFFIX")
        }
        return java.io.File(dir, base)
    }

    private fun snapshotNameInUse(dir: java.io.File, name: String): Boolean =
        java.io.File(dir, name).exists() ||
            java.io.File(dir, name + SNAPSHOT_TMP_SUFFIX).exists()

    /** [FIX-P1A / RT-S3] Writes [payload] as a fresh snapshot into [dir] and
     *  prunes to the newest [SNAPSHOT_KEEP] files. Returns the written file.
     *
     *  Atomic: the document goes to `<name>.json.tmp` and is renamed onto the
     *  final name only after the writer closed cleanly, so an ENOSPC or a kill
     *  mid-stream can never leave a truncated JSON that [listSnapshots] would
     *  happily offer as the newest rollback point. */
    fun writeSnapshot(dir: java.io.File, payload: String): java.io.File {
        val (tmp, target) = openSnapshotForWrite(dir)
        try {
            tmp.writeText(payload)
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        return commitSnapshotWrite(tmp, target, dir)
    }

    /**
     * [T-backup-streaming-export] Same contract as [writeSnapshot], but the
     * document is produced straight into the file — the caller never holds
     * the payload String. Must itself be `suspend`: the writer block awaits
     * [exportToWriter], which suspends on IO dispatchers, and a non-suspend
     * helper cannot await it.
     */
    suspend fun writeSnapshotStreaming(
        dir: java.io.File,
        writerBlock: suspend (java.io.Writer) -> Unit,
    ): java.io.File {
        val (tmp, target) = openSnapshotForWrite(dir)
        try {
            tmp.outputStream().bufferedWriter().use { w -> writerBlock(w) }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        return commitSnapshotWrite(tmp, target, dir)
    }

    /** [FIX-P1A / RT-S3] `(tmp, final)` for one snapshot write. */
    private fun openSnapshotForWrite(
        dir: java.io.File,
        now: Long = System.currentTimeMillis(),
    ): Pair<java.io.File, java.io.File> {
        dir.mkdirs()
        sweepStaleSnapshotTemps(dir, now)
        val target = allocateSnapshotFile(dir, now)
        return java.io.File(dir, target.name + SNAPSHOT_TMP_SUFFIX) to target
    }

    /** [FIX-P1A / RT-S3] Rename the finished `.tmp` onto its final name, then
     *  prune. A failed rename removes the temp and throws — the caller (the
     *  restore flow) must not go on as if a rollback point existed. */
    private fun commitSnapshotWrite(
        tmp: java.io.File,
        target: java.io.File,
        dir: java.io.File,
    ): java.io.File {
        if (!tmp.renameTo(target)) {
            runCatching { tmp.delete() }
            throw java.io.IOException("snapshot rename failed: ${target.name}")
        }
        pruneSnapshots(dir, justWritten = target)
        return target
    }

    /**
     * [FIX-P1A / RT-S3 → FIX-1028 / B-12] Keep [SNAPSHOT_KEEP] snapshots, ALWAYS
     * the one just written, and never one whose age cannot be read.
     *
     * The two fixes address the same wound from two sides. P1A stopped counting
     * the fresh file as a prunable candidate; B-12 stopped trusting mtime for
     * the ORDER at all — a clock-rollback write still sorts last by mtime, so
     * without name ordering the list offered the wrong rollback point and the
     * "exclude the fresh file" rule was the only thing keeping it on disk, with
     * KEEP=1 leaving no second chance. Order now comes from the name stamp;
     * the fresh file and every unstamped file are additionally protected.
     *
     * Both exclusions follow one rule — rather keep a file than delete the
     * wrong one: the fresh snapshot is never a victim, and neither is a file
     * whose name carries no parseable stamp ([BackupRotation.rotationVictims]).
     * The latter stays on disk for the user to inspect; nothing here can tell
     * whether it is newer than what we kept.
     */
    private fun pruneSnapshots(dir: java.io.File, justWritten: java.io.File) {
        BackupRotation.rotationVictims(
            items = listSnapshots(dir),
            name = { it.name },
            key = { snapshotStampMillis(it) },
            keep = SNAPSHOT_KEEP,
            protect = { it.name == justWritten.name },
        ).forEach { runCatching { it.delete() } }
    }

    /**
     * [FIX-P1A / RT-S3] Delete `.json.tmp` remnants of a write that was killed
     * mid-stream. They are invisible to [listSnapshots] (so they were never a
     * rollback hazard, only dead weight), and nothing else ever removes them.
     * Age-gated at [SNAPSHOT_TMP_STALE_MS] so a concurrent write's temp is
     * never a candidate; with a rolled-back clock the age is negative and
     * nothing is swept (the safe direction).
     */
    private fun sweepStaleSnapshotTemps(
        dir: java.io.File,
        now: Long = System.currentTimeMillis(),
    ) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            if (!f.name.startsWith(SNAPSHOT_PREFIX)) continue
            if (!f.name.endsWith(SNAPSHOT_SUFFIX + SNAPSHOT_TMP_SUFFIX)) continue
            if (now - f.lastModified() < SNAPSHOT_TMP_STALE_MS) continue
            runCatching { f.delete() }
        }
    }

    /** Snapshots in [dir], newest first. Only `rikkaminis-snapshot-*.json` —
     *  the `.json.tmp` sidecars of an in-flight / killed write never match
     *  (see [SNAPSHOT_TMP_SUFFIX]), which is what keeps a half-written
     *  document out of the rollback list.
     *
     *  [FIX-1028 / B-12] "Newest" is the stamp in the NAME, not `lastModified`:
     *  a restore performed after a clock rollback wrote a file whose mtime
     *  looked older than its predecessor, so mtime ordering offered the WRONG
     *  rollback point and pruning deleted the new one. */
    fun listSnapshots(dir: java.io.File): List<java.io.File> =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(SNAPSHOT_PREFIX) && f.name.endsWith(SNAPSHOT_SUFFIX)
        }?.let { files ->
            BackupRotation.newestFirst(files.toList(), name = { it.name }, key = { snapshotStampMillis(it) })
        } ?: emptyList()

    /** [FIX-1028 / B-12] A snapshot's ordering key, decoded from its file name.
     *  Null = the name carries no stamp (came from elsewhere) ⇒ age unknown. */
    internal fun snapshotStampMillis(file: java.io.File): Long? =
        BackupRotation.stampMillis(file.name, SNAPSHOT_PREFIX, SNAPSHOT_SUFFIX)

    /** [FIX-1028 / B-12] Timestamp a snapshot row should DISPLAY: the name
     *  stamp when parseable — the same key [listSnapshots] orders by, so the
     *  times shown can never contradict the order — else the file's mtime.
     *  Showing mtime on a name-ordered list is how a rolled-back clock made the
     *  newest rollback point look like the oldest. */
    fun snapshotDisplayMillis(file: java.io.File): Long =
        snapshotStampMillis(file) ?: file.lastModified()

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
    /**
     * [da1034-A1] Copy [src] to [dst] entry-by-entry, DROPPING every entry
     * that lands in the MCP OAuth bridge namespace — the plaintext token
     * bridge (`MCPOAuthStore.bridgeDir`, mounted in-guest as
     * /var/minis/mcp-servers/oauth). Pre-fix archives carry it verbatim
     * (`includeSecrets=false` never gated this zip), so a restore would
     * overwrite this device's live token bridge with whatever the source
     * device had. Returns the number of dropped entries per kind (0 for a
     * clean archive — the exporter no longer writes them in the first place).
     * Pure JVM; a write failure propagates to the Stage 9 catch which reports
     * it through `skipped` instead of half-restoring.
     *
     * [dv28-a5 A-05] Same pass, second drop: when [dropPrestagedServerList] is
     * set, the MCP server list (`mcp/servers.json`) is dropped too, because
     * Stage 7 already MERGED the backup's copy into this device's list through
     * [MCPRepository.importJSON] (merge = this device's other servers survive).
     * The extraction that follows writes entries verbatim, so letting that one
     * entry through would replace the merged file with the backup's list and
     * erase every server the backup did not carry — silently, since the merge
     * only lived in memory until the next cold start re-read the file. The
     * caller passes `false` when the merge did not run (no `mcpServers` section:
     * an archive-only backup), where the raw copy is the only way to restore
     * the list at all.
     */
    internal fun stripMcpOAuthEntries(
        src: java.io.File,
        dst: java.io.File,
        dropPrestagedServerList: Boolean = false,
    ): RestoreEntryFilterResult {
        // [da1034-A1] Both operands are private temp files this process just
        // created, so this refusal is unreachable today — it is here because
        // the repo's write contract (`readonly_mount_write_guard`) applies to
        // every write whose target is a path parameter, and a future caller
        // must not be able to route this copy into a read-only mount by
        // reusing the helper.
        if (com.rikkaminis.app.sandbox.PRootKernel.isLinuxPathUnderReadOnlyMount(dst.absolutePath)) {
            throw java.io.IOException(
                "refusing to write the oauth-filtered archive under a read-only mount"
            )
        }
        var oauthDropped = 0
        var prestagedDropped = 0
        java.io.FileOutputStream(dst).use { fos ->
            java.util.zip.ZipOutputStream(fos.buffered()).use { zos ->
                java.util.zip.ZipFile(src).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (isMcpOAuthEntryName(entry.name)) {
                            oauthDropped++
                            continue
                        }
                        if (dropPrestagedServerList && isMcpServerListEntryName(entry.name)) {
                            prestagedDropped++
                            continue
                        }
                        zos.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zf.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
        return RestoreEntryFilterResult(oauthDropped, prestagedDropped)
    }

    /**
     * [dv28-a5 A-05] What [stripMcpOAuthEntries] removed while copying the
     * artifact archive. Two counters, not one: the two drops mean different
     * things to the user — a credential guard ([da1034-A1]) versus "this file
     * was already restored by Stage 7's merge" — and each gets its own
     * `skipped` sentence.
     */
    internal data class RestoreEntryFilterResult(
        val oauthDropped: Int,
        val prestagedDropped: Int,
    ) {
        val dropped: Int get() = oauthDropped + prestagedDropped
    }

    /**
     * [dv28-a5 A-05] True for the MCP server list as Stage 9 routes it —
     * exactly "mcp/servers.json" (`MCPRepository` writes that name at the mcp
     * root). Compared segment-wise with empty and "." segments collapsed, the
     * same normalisation [isMcpOAuthEntryName] uses, so "mcp//servers.json" and
     * "mcp/./servers.json" — all landing on the same file — are recognised
     * too. Only the one file name is excluded: the rest of the `mcp/` prefix
     * (custom data the agent wrote) still restores.
     */
    internal fun isMcpServerListEntryName(name: String): Boolean {
        val segs = ArrayList<String>(4)
        for (seg in name.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            segs.add(seg)
        }
        return segs.size == 2 && segs[0] == "mcp" && segs[1] == "servers.json"
    }

    /**
     * [da1034-A1] True when a zip entry belongs to the MCP OAuth bridge
     * namespace ("mcp/oauth/…"). Compared segment-wise with empty and "."
     * segments collapsed, so "mcp//oauth/x.json", "mcp/./oauth/x.json" and
     * "./mcp/oauth/x.json" all resolve to the same destination and are all
     * refused — a name that would land inside the live token bridge dir is
     * dropped, never parsed-then-trusted. ".." never reaches here from a
     * trusted writer and is rejected wholesale (with absolute paths and
     * backslashes) by [ArtifactArchiver.extractZipByPrefix].
     */
    internal fun isMcpOAuthEntryName(name: String): Boolean {
        val segs = ArrayList<String>(4)
        for (seg in name.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            segs.add(seg)
        }
        return segs.size >= 2 && segs[0] == "mcp" && segs[1] == "oauth"
    }

    /**
     * [review G1] Strip secret-bearing values from an [exportServerJSON]
     * document. exportServerJSON emits the importable wrapper shape
     * `{"mcpServers":{"<id>":{…}}}` — the credential-bearing entry sits ONE
     * level down, so this descends and strips each entry. A bare entry (no
     * wrapper) is stripped as-is, mirroring the import side's fallback
     * (`entry = inner?.optJSONObject(id) ?: srv`).
     *
     * Stripping the wrapper itself was a no-op: the wrapper has no
     * headers/env/args/url of its own, so every secret (Authorization / env
     * token / args token / URL query key) survived into the backup payload
     * even with includeSecrets=false. A regression test pins the wrapper
     * shape the export path actually passes.
     */
    /**
     * [dv20-a1 A-01] Redact ONE archived artifact entry on its way into a
     * credentials-free backup. Only `servers.json` — MCPRepository's on-disk
     * config, `{"mcpServers":{"<id>":{…}}}` under `minis-global/mcp-servers`
     * — carries credentials, and it is the exact shape the Stage 5 export
     * section already strips, so this defers to the same
     * [stripExportedMcpSecrets] instead of growing a second rule. Any other
     * entry under the mcp root (the jsonl/md data files) passes verbatim.
     *
     * Returns **null to drop the entry**: bytes that do not parse must never
     * be written verbatim into a no-secrets archive (fail closed). A
     * credential that cannot be redacted is a credential that must not be
     * exported — and the archive entry name is left to the caller, so restore
     * routing is unchanged.
     */
    internal fun redactMcpArtifactEntry(relPath: String, bytes: ByteArray): ByteArray? {
        if (relPath.substringAfterLast('/') != "servers.json") return bytes
        return try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            stripExportedMcpSecrets(obj)
            obj.toString(2).toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "mcp artifact redaction failed for $relPath: ${t.message}")
            null
        }
    }

    internal fun stripExportedMcpSecrets(obj: JSONObject) {
        val inner = obj.optJSONObject("mcpServers")
        if (inner == null) {
            stripMcpSecretValues(obj)
            return
        }
        for (id in inner.keys().asSequence().toList()) {
            inner.optJSONObject(id)?.let { stripMcpSecretValues(it) }
        }
    }

    /**
     * [sweep1013-D1/F-3] Blank the secret-bearing values of an exported MCP
     * server object (self-filled `headers` / `env` maps) while KEEPING the key
     * names — the server still imports, and the user sees exactly which
     * credentials to refill. Non-secret fields (command/args/url/note/enabled)
     * are untouched. The maps may be absent (e.g. a stdio server without env) —
     * absent sections stay absent.
     */
    internal fun stripMcpSecretValues(obj: JSONObject) {
        for (secretKey in listOf("headers", "env")) {
            val section = obj.optJSONObject(secretKey) ?: continue
            for (k in section.keys().asSequence().toList()) {
                section.put(k, "")
            }
        }
        // [review D-3] Credential carriers beyond headers/env:
        // 1. `args` entries of the `--token=…` / `--api-key=…` shape (stdio
        //    servers that take the credential as a CLI flag).
        // 2. `url` query values for sensitive keys (?key=/token=/api_key=…),
        //    mirroring LLMRequestLog.SENSITIVE_QUERY_KEYS.
        // Both are blanked only when they actually carry a credential shape;
        // benign values survive so the restored entry stays wired to its
        // command/host (only the secret needs refilling).
        val args = obj.optJSONArray("args")
        if (args != null) {
            for (i in 0 until args.length()) {
                val v = args.optString(i, "")
                if (SECRET_ARG_RE.matches(v)) args.put(i, v.substringBefore('=') + "=")
            }
        }
        val url = obj.optString("url", "")
        if (url.contains("?")) {
            obj.put("url", redactUrlQuery(url))
        }
    }

    /** `--flag=value` shapes whose flag name smells like a credential.
     *  Full-match regex — `\S.*` requires a value of >= 1 non-space char,
     *  so `--token=` (already blanked) and `--port=8080` are untouched. */
    private val SECRET_ARG_RE = Regex(
        """(?i)^--(token|api[_-]?key|secret|password|access[_-]?token)\s*=\S.*""",
    )

    /** Sensitive URL query keys — same list as LLMRequestLog.SENSITIVE_QUERY_KEYS. */
    private val SENSITIVE_QUERY_KEYS = setOf(
        "key", "api_key", "apikey", "api-key", "token", "access_token",
        "secret", "password", "signature", "sig", "auth", "authorization",
    )

    private fun redactUrlQuery(url: String): String {
        val qIdx = url.indexOf('?')
        val query = url.substring(qIdx + 1)
        val fragIdx = query.indexOf('#')
        val (qBody, frag) = if (fragIdx >= 0) query.substring(0, fragIdx) to query.substring(fragIdx) else query to ""
        val redacted = qBody.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                if (k.lowercase() in SENSITIVE_QUERY_KEYS) "$k=[redacted:${v.length}]" else pair
            } else pair
        }
        return url.substring(0, qIdx + 1) + redacted + frag
    }

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
 * [FIX-P1A / FINDING-7] One environment variable's NON-secret backup metadata.
 * A carrier so [buildEnvVarExportEntries] stays pure (no repository, no
 * Android) and can be pinned on the JVM.
 */
internal data class EnvVarBackupMeta(
    val key: String,
    val note: String,
    val group: String,
)

/**
 * [FIX-P1A / FINDING-7] The `envVars` section plus the accounting the export
 * used to drop on the floor.
 *
 * @property entries the payload section, one object per variable.
 * @property missingSecretValues keys whose value was DECLARED to travel
 *   (`includeSecrets=true`) but could not be read. Empty for a secrets-free
 *   export by construction — there the absence is the intent, not a failure.
 */
internal class EnvVarExport(
    val entries: JSONArray,
    val missingSecretValues: List<String>,
)

/**
 * [FIX-P1A / FINDING-7] Per-entry marker for "declared with secrets, value
 * unreadable". `_`-prefixed like `_entryIds`: a backup-layer annotation, never
 * part of the wire contract (import ignores unknown keys).
 */
internal const val ENV_VALUE_UNREADABLE_KEY = "_valueUnreadable"

/**
 * [FIX-P1A / FINDING-7] Build the `envVars` section and report every value the
 * export claimed to carry but could not read.
 *
 * The defect this closes: `includeSecrets=true` + `getValue(key) == null` used
 * to emit `{key, note}` and nothing else — no marker, no counter, no log. A
 * payload that said "credentials included" was therefore indistinguishable
 * from a complete one, and the loss only surfaced after a device transfer.
 * Contract now:
 *  - `includeSecrets=false` → key+note(+group) only, [EnvVarExport.missingSecretValues]
 *    empty (absence is the requested shape);
 *  - `includeSecrets=true` + a value → `value` present, nothing to report;
 *  - `includeSecrets=true` + no value → `_valueUnreadable: true` on the entry
 *    AND the key in [EnvVarExport.missingSecretValues], so "declared secrets"
 *    and "values actually carried" can be reconciled from the document alone.
 *
 * Never fails the export: a missing credential is a gap to report, not a
 * reason to leave the user without a backup.
 */
internal fun buildEnvVarExportEntries(
    entries: List<EnvVarBackupMeta>,
    includeSecrets: Boolean,
    readValue: (String) -> String?,
): EnvVarExport {
    val out = JSONArray()
    val missing = ArrayList<String>(0)
    for (entry in entries) {
        val obj = JSONObject().apply {
            put("key", entry.key)
            put("note", entry.note)
            if (entry.group.isNotEmpty()) put("group", entry.group)
        }
        if (includeSecrets) {
            val value = readValue(entry.key)
            if (value != null) {
                obj.put("value", value)
            } else {
                obj.put(ENV_VALUE_UNREADABLE_KEY, true)
                missing.add(entry.key)
            }
        }
        out.put(obj)
    }
    return EnvVarExport(out, missing)
}

/** [FIX-P1A] First few ids plus a count, for log lines that must stay short. */
internal fun summariseIds(ids: List<String>, max: Int = 3): String {
    if (ids.size <= max) return ids.joinToString(", ")
    return ids.take(max).joinToString(", ") + " … (+${ids.size - max})"
}

/**
 * Rewrite the source group/entry ids embedded in a `defaults.*` field to the
 * ids minted during this import. Group-typed fields
 * (primaryGroup / subGroup / voiceInputGroup / voiceOutputGroup /
 * agentLoopGroups) map through [groupIdMap];
 * agentLoopEntries and `runtime.compactModelEntryId` (the pinned compaction
 * model) map through [entryIdMap]. Everything else is returned
 * unchanged.
 *
 * [FIX-P1A / RT-S5] An id the map cannot resolve is no longer written through.
 * Passing the source id along "so the field's own writer can report it" did
 * not do that:
 *  - `defaults.primaryGroup` / `subGroup` / `voice*Group` made the writer throw
 *    `InvalidValue`, so a value the backup DID carry was refused and the local
 *    setting stayed as it was with no line in `skipped` tying the two together;
 *  - `defaults.agentLoopGroups` / `agentLoopEntries` were worse: their writers
 *    filter unknown ids and write the remainder, so a second import (an older
 *    package with no `groups` section → empty map → every id unmapped)
 *    silently CLEARED the user's agent-loop list. Counts looked normal, the UI
 *    looked fine, the setting was gone.
 *
 * Contract now, mirroring [remapSessionBindingJson]: no map at all ⇒ the field
 * is untouched (a same-install restore has nothing to rewrite); a map that
 * cannot resolve an id ⇒ that id is dropped (and [FieldIdRemap.value] is null
 * when nothing survives), with the ids handed back so the caller can record
 * them in `skipped`. The criterion is "a second import cannot introduce a new
 * dangling reference", not "never remap".
 *
 * Pure and Android-free so [BackupIdRemapTest] can pin every branch on the JVM.
 * [FIX-E / ISSUE-09] `runtime.compactModelEntryId` used to be absent here: its
 * consumer (CompactOrchestration's chain head) treats an unresolvable pin as
 * "not reachable — follow the session chain" with a single INFO line, so a
 * restore onto another install silently dropped the user's pinned compaction
 * model instead of failing loudly.
 * [da1031-A] The two voice-group bindings were absent here for the same
 * reason *and* absent from the registry, so a restore dropped them twice over.
 */
internal fun remapDefaultsIds(
    path: String,
    value: ConfigValue,
    groupIdMap: Map<String, String>,
    entryIdMap: Map<String, String>,
): FieldIdRemap {
    val map = when (path) {
        "defaults.primaryGroup", "defaults.subGroup", "defaults.voiceInputGroup", "defaults.voiceOutputGroup", "defaults.agentLoopGroups" -> groupIdMap
        "defaults.agentLoopEntries", "runtime.compactModelEntryId" -> entryIdMap
        else -> return FieldIdRemap(value)
    }
    // No map = same-install restore (or a backup taken before groups/entries
    // existed): nothing to rewrite, and nothing to report.
    if (map.isEmpty()) return FieldIdRemap(value)
    return when (value) {
        is ConfigValue.Str -> {
            // A blank id means "nothing selected", not a reference that failed
            // to resolve: rewriting it would change semantics and reporting it
            // would be noise on every fresh install.
            if (value.value.isEmpty()) {
                FieldIdRemap(value)
            } else {
                map[value.value]
                    ?.let { FieldIdRemap(ConfigValue.Str(it)) }
                    ?: FieldIdRemap(value = null, unmapped = listOf(value.value))
            }
        }
        is ConfigValue.Arr -> {
            val kept = ArrayList<ConfigValue>(value.value.size)
            val unmapped = ArrayList<String>(0)
            for (element in value.value) {
                if (element !is ConfigValue.Str || element.value.isEmpty()) {
                    kept.add(element)
                    continue
                }
                val mapped = map[element.value]
                if (mapped != null) kept.add(ConfigValue.Str(mapped)) else unmapped.add(element.value)
            }
            when {
                unmapped.isEmpty() -> FieldIdRemap(ConfigValue.Arr(kept))
                // Nothing survived: writing the empty remainder would CLEAR the
                // local setting (the silent-wipe shape this fix exists for).
                kept.isEmpty() -> FieldIdRemap(value = null, unmapped = unmapped)
                else -> FieldIdRemap(ConfigValue.Arr(kept), unmapped)
            }
        }
        else -> FieldIdRemap(value)
    }
}

/**
 * [FIX-P1A / RT-S5] Outcome of [remapDefaultsIds] for one field.
 *
 * @property value what the caller may write; `null` means nothing usable
 *   survived the remap, so the caller must NOT write (keep the local value).
 * @property unmapped source ids the map could not resolve. Non-empty means the
 *   restore is partial and the caller has to say so through `skipped`.
 */
internal class FieldIdRemap(
    val value: ConfigValue?,
    val unmapped: List<String> = emptyList(),
)

/**
 * Rewrite the source group/entry ids inside a session's persisted
 * `modelBinding` JSON onto the ids minted during this import.
 *
 * The two shapes `ChatSessionEntryExt.persistBinding` / `ChatModelRouting`
 * write are `{"type":"group","groupId":…[,"lastEntryId":…]}` and
 * `{"type":"entry","entryId":…}`; a restored session whose binding still
 * points at a foreign uuid fails [ChatViewModel.restoreFromBinding] and
 * silently degrades to the stored `modelId` (group binding lost, no fallback
 * chain) — see [FIX-E / ISSUE-09].
 *
 * Contract: blank input is returned unchanged; with no maps at all the input
 * is returned byte-identically (same-install restore must not rewrite
 * anything); an unmapped id keeps its original value; a payload that is not
 * the expected JSON object is returned unchanged rather than dropped.
 */
internal fun remapSessionBindingJson(
    bindingJson: String?,
    groupIdMap: Map<String, String>,
    entryIdMap: Map<String, String>,
): String? {
    if (bindingJson.isNullOrBlank()) return bindingJson
    if (groupIdMap.isEmpty() && entryIdMap.isEmpty()) return bindingJson
    return try {
        val obj = JSONObject(bindingJson)
        when (obj.optString("type")) {
            "group" -> {
                val gid = obj.optString("groupId")
                if (gid.isNotEmpty()) obj.put("groupId", groupIdMap[gid] ?: gid)
                val lastEid = obj.optString("lastEntryId")
                if (lastEid.isNotEmpty()) obj.put("lastEntryId", entryIdMap[lastEid] ?: lastEid)
            }
            "entry" -> {
                val eid = obj.optString("entryId")
                if (eid.isNotEmpty()) obj.put("entryId", entryIdMap[eid] ?: eid)
            }
            // Unknown / future binding shape: keep it verbatim. Re-serializing
            // would rewrite an object this layer does not understand.
            else -> return bindingJson
        }
        obj.toString()
    } catch (_: Throwable) {
        bindingJson
    }
}

/**
 * [FIX-E / ISSUE-09] The `memoryFiles` entries a backup carries, in payload
 * order: everything [MemoryRepository.listAllFiles] lists (GLOBAL.md + daily
 * logs), plus the distilled [MemoryRollupEngine.ROLLUP_FILE] index.
 *
 * The rollup is deliberately excluded from `listAllFiles` (it must stay out of
 * the settings file list and out of `memory_get`), which used to make it the
 * one memory artifact a backup never carried: after a device transfer the
 * distilled rules — everything old daily logs were folded into — were simply
 * gone. It is added here, not in `listAllFiles`, so the UI keeps its shape.
 *
 * [read] is the caller's read primitive: a name it cannot read yields null.
 * Entries for listed names are kept even when the content is empty (unchanged
 * pre-existing behavior); the optional rollup is only carried when it actually
 * has content, so a device without one writes no extra entry.
 */
internal fun memoryBackupEntries(
    listed: List<String>,
    read: (String) -> String?,
): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(listed.size + 1)
    for (name in listed) {
        val content = read(name) ?: continue
        out.add(name to content)
    }
    val rollup = ROLLUP_BACKUP_FILE
    if (rollup !in listed) {
        val content = read(rollup)
        if (!content.isNullOrEmpty()) out.add(rollup to content)
    }
    return out
}

/**
 * [FIX-E / ISSUE-09] Distilled-memory index carried by every backup. Note the
 * source of truth is [MemoryRollupEngine.ROLLUP_FILE] — never re-spelled.
 */
internal const val ROLLUP_BACKUP_FILE: String =
    com.rikkaminis.app.workspace.MemoryRollupEngine.ROLLUP_FILE

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
    groupIdMap: Map<String, String> = emptyMap(),
    entryIdMap: Map<String, String> = emptyMap(),
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
                    modelBinding = remapSessionBindingJson(
                        s.optString("modelBinding").ifEmpty { null },
                        groupIdMap = groupIdMap,
                        entryIdMap = entryIdMap,
                    ),
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
                // [fix/da1019-partsjson-funnel] A restored row is a row. The
                // import path used to insert the payload verbatim, so a backup
                // carrying an oversized parts_json / reasoning_content (hand-
                // edited payload, older write path, another tool's export)
                // landed a blob the loader cannot materialise out of a 2 MB
                // CursorWindow — SQLiteBlobTooBigException on the next open of
                // that session. Both columns now take the same ceilings the
                // runtime writers apply (ChatRepository companion funnels).
                val message = MessageEntity(
                    id = m.optString("id"),
                    sessionId = m.optString("sessionId"),
                    role = m.optString("role"),
                    partsJson = ChatRepository.capPartsJsonForRow(m.optString("partsJson")),
                    createdAt = m.optLong("createdAt"),
                    sortOrder = m.optInt("sortOrder", i),
                    reasoningContent = if (m.has("reasoningContent")) {
                        ChatRepository.capReasoningContentForRow(m.optString("reasoningContent"))
                    } else {
                        null
                    },
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

// --- [fix/dv18-4 A-07] Model-group run-config seams for the backup import
// path. The `groups[]` section carries five run-config fields on export
// (buildSections: strategy / fallbackStrategy / defaultThinkingLevel /
// contextLimitTokens / lastContextLimitTokens), but the import's same-name
// merge branch decoded NONE of them — a restore onto an install that already
// had the group silently reset the user's routing strategy / per-group
// thinking level / context limit to the defaults (Stage 1 vs Stage 2
// asymmetry: readImportInstanceFields DOES restore the instance run-config
// on a same-label merge). Same shape as ProviderRepository's Stage-1 seams:
// one decode (readGroupRunConfig), two appliers — the create path takes
// `.value` (decode defaults mirror the pre-existing inline decode byte for
// byte), the merge path goes through applyGroupRunConfig, which honours
// [BackupField.present] so a legacy backup's missing keys keep the local
// group's run-config instead of overwriting it with a null/default.
// Top-level and Android-free (the warning sink is injected) so the round-trip
// is JVM-unit-testable without an Android Context. ---

/** [enumValueOf] that falls back to [default] instead of throwing on a
 *  token this build doesn't know (forward-compat with newer backups). */
private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

/**
 * [fix/dv18-4 A-07] One field decoded from a `groups[]` backup entry.
 * [present] records whether the payload carried the key AT ALL: a pre-T312
 * backup has none of the run-config keys, and "absent" must leave the target
 * group's current value alone (restore is additive for what the backup does
 * not say) rather than overwrite it with a decode default.
 */
internal data class BackupField<T>(val present: Boolean, val value: T)

/** [fix/dv18-4 A-07] The five run-config fields of one `groups[]` entry. */
internal data class GroupRunConfigSnapshot(
    val strategy: BackupField<RoutingStrategy>,
    val fallbackStrategy: BackupField<FallbackStrategy>,
    val defaultThinkingLevel: BackupField<ThinkingLevel?>,
    val contextLimitTokens: BackupField<Int?>,
    val lastContextLimitTokens: BackupField<Int?>,
)

/**
 * [fix/dv18-4 A-07] The ONE decode of the group run-config keys, shared by
 * both restore paths (fresh create + same-name merge) so the two cannot drift.
 * Decode defaults are the pre-fix inline behaviour: enums fall back to the
 * model defaults on an unknown token, `defaultThinkingLevel` and both token
 * limits decode to null when absent/empty/non-positive. [warn] receives one
 * message when a thinking-level token cannot be parsed — the RESULT is
 * unchanged either way (a backup from a newer build must not sink the whole
 * import); the point is that the drop stops being silent.
 */
internal fun readGroupRunConfig(g: JSONObject, warn: (String) -> Unit): GroupRunConfigSnapshot {
    val rawThinking = g.optString("defaultThinkingLevel", "")
    val thinking = rawThinking
        .takeIf { it.isNotEmpty() }
        ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
    if (rawThinking.isNotEmpty() && thinking == null) {
        warn(
            "import: group defaultThinkingLevel \"$rawThinking\" is not a level " +
                "this build knows — no thinking-level override applied"
        )
    }
    return GroupRunConfigSnapshot(
        strategy = BackupField(
            present = g.has("strategy"),
            value = enumOrDefault(g.optString("strategy"), RoutingStrategy.fallback),
        ),
        fallbackStrategy = BackupField(
            present = g.has("fallbackStrategy"),
            value = enumOrDefault(g.optString("fallbackStrategy"), FallbackStrategy.default),
        ),
        defaultThinkingLevel = BackupField(present = g.has("defaultThinkingLevel"), value = thinking),
        contextLimitTokens = BackupField(
            present = g.has("contextLimitTokens"),
            value = g.optInt("contextLimitTokens").takeIf { it > 0 },
        ),
        lastContextLimitTokens = BackupField(
            present = g.has("lastContextLimitTokens"),
            value = g.optInt("lastContextLimitTokens").takeIf { it > 0 },
        ),
    )
}

/**
 * [fix/dv18-4 A-07] Apply a decoded [GroupRunConfigSnapshot] onto [base].
 * Copy-on-write: returns a NEW group, never mutates [base] (it is the live
 * object out of config.value that concurrent readers may be iterating). Only
 * fields the payload actually carried ([BackupField.present]) are
 * overwritten — an absent key keeps [base]'s value, so restoring a legacy
 * backup is a no-op on run-config, not a reset. Deliberately does NOT touch
 * id / name / memberEntryIds: the merge match key, the resolved name and the
 * member union are the caller's decisions (and updateGroup re-copies the
 * member list on store).
 */
internal fun applyGroupRunConfig(base: ModelGroup, f: GroupRunConfigSnapshot): ModelGroup {
    val out = base.copy()
    if (f.strategy.present) out.strategy = f.strategy.value
    if (f.fallbackStrategy.present) out.fallbackStrategy = f.fallbackStrategy.value
    if (f.defaultThinkingLevel.present) out.defaultThinkingLevel = f.defaultThinkingLevel.value
    if (f.contextLimitTokens.present) out.contextLimitTokens = f.contextLimitTokens.value
    if (f.lastContextLimitTokens.present) out.lastContextLimitTokens = f.lastContextLimitTokens.value
    return out
}
