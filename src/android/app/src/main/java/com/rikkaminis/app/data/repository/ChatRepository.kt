package com.rikkaminis.app.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteConstraintException
import com.rikkaminis.app.diagnostics.MemorySpikeRecorder
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.db.UsageRecord
import com.rikkaminis.app.data.storage.SessionFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * @param sessionFiles owner of a session's on-disk footprint. Null only in
 *   unit-test construction (no Android Context, no real filesystem); deleting
 *   a session with a null store falls back to the legacy row-only behavior.
 *   MinisApp always injects the real SessionFileStore.
 */
class ChatRepository(
    internal val dao: ChatDao,
    private val sessionFiles: SessionFileStore? = null,
) {

    fun observeSessions(): Flow<List<ChatSessionEntity>> = dao.observeSessions()

    /** [P0-1-drawer-title-visibility] Live message-count map per session. */
    fun observeMessageCountsPerSession(): Flow<Map<String, Int>> =
        dao.messageCountsPerSession()

    suspend fun createSession(
        modelId: String,
        title: String? = null,
        // [T-memory-global-toggle-settings-ui-android] honor the global
        // memory default at row-insert time. Caller (ChatViewModel) reads
        // MemoryGlobalPrefs.isGlobalEnabled and passes the value through
        // here; existing call sites that omit it keep the prior
        // memoryEnabled=1 behavior (legacy default).
        memoryEnabled: Boolean = true,
        // [T-empty-session-residue] Persist the thinking override together with
        // the row so a user who flipped /thinking on a draft chat (before the
        // first message) doesn't need a separate pre-materialisation write that
        // would spawn a message-less session. ensureSession() passes the
        // in-memory value here; the standalone update (persistThinkingOverride)
        // only runs once the session already exists.
        thinkingLevel: String? = null,
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        // [fix-audit-p0-1] Thinking override folds INTO the row at insert
        // time. The previous implementation ran a separate
        // updateThinkingOverride() BEFORE insertSession() — an UPDATE that
        // matched zero rows because the session didn't exist yet, silently
        // dropping the user's /thinking choice on draft chats. Folding it
        // into the entity makes the write atomic AND makes the "persist
        // together with the row" comment actually true.
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
            memoryEnabled = if (memoryEnabled) 1 else 0,
            thinkingOverride = thinkingLevel,
        )
        dao.insertSession(session)
        return session
    }

    suspend fun getSession(id: String): ChatSessionEntity? = dao.getSession(id)

    /** All persisted token_usage JSON strings for a session (one per LLM call). */
    suspend fun sessionTokenUsages(sessionId: String): List<String> = dao.tokenUsages(sessionId)

    /**
     * Usage rows for the stats screen. Both bounds null → legacy full scan
     * ([ChatDao.allUsageRecords]); otherwise the time-windowed query
     * ([ChatDao.usageRecordsBetween], half-open [since, until)).
     */
    suspend fun usageRecords(since: Long?, until: Long?): List<UsageRecord> =
        if (since == null && until == null) dao.allUsageRecords()
        else dao.usageRecordsBetween(
            sinceMs = since ?: 0L,
            untilMs = until ?: Long.MAX_VALUE,
        )

    /**
     * [T-android-session-paused-badge-hardkill] Session ids whose agent loop was
     * left interrupted, derived purely from the persisted message tail — so the
     * PAUSED badge survives a hard process death (where the lifecycle-callback
     * push never runs). Lightweight: one query for the last message per session,
     * then the SAME interrupted-tail predicate as ChatViewModel.loadSession's
     * detection (kept in sync intentionally). Mirrors iOS
     * ChatStore.interruptedSessionIds.
     */
    suspend fun interruptedSessionIds(): Set<String> {
        val tails = runCatching { dao.lastMessageTailPerSession() }.getOrElse { emptyList() }
        val result = HashSet<String>()
        for (row in tails) {
            if (isInterruptedTail(row.role, row.partsJson)) result.add(row.sessionId)
        }
        return result
    }

    /**
     * [T-android-session-paused-badge-hardkill] The interrupted-tail predicate
     * over a raw `parts_json` string, matching ChatViewModel.loadSession's
     * AgentContentPart-based logic:
     *   - role USER + ALL parts are tool_result (tools ran, next model call never
     *     fired), OR the single synthetic "Continue" reminder text part, OR
     *   - role ASSISTANT + any tool_use part (model asked for tools that never ran)
     * Part type discriminator is the JSON "type" field — the @SerialName values
     * from [com.rikkaminis.app.data.model.ContentPart]: "toolUse" / "toolResult"
     * / "text" (camelCase, NOT snake_case); text payload is the "value" field.
     */
    private fun isInterruptedTail(role: String, partsJson: String): Boolean {
        val arr = runCatching { org.json.JSONArray(partsJson) }.getOrNull() ?: return false
        val types = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { types.add(it.optString("type")) }
        }
        return when (role.uppercase()) {
            "USER" -> {
                val allToolResults = types.isNotEmpty() && types.all { it == "toolResult" }
                val isContinueReminder = arr.length() == 1 &&
                    arr.optJSONObject(0)?.takeIf { it.optString("type") == "text" }
                        ?.optString("value")
                        ?.contains("The user stopped the previous response") == true
                allToolResults || isContinueReminder
            }
            "ASSISTANT" -> types.any { it == "toolUse" }
            else -> false
        }
    }

    suspend fun updateSessionTitle(id: String, title: String) {
        dao.updateSessionTitle(id, title, System.currentTimeMillis())
    }

    suspend fun updateSessionTitleAndCategory(id: String, title: String, category: String?) {
        dao.updateSessionTitleAndCategory(id, title, category, System.currentTimeMillis())
    }

    suspend fun updateSessionModel(sessionId: String, modelId: String) {
        dao.updateSessionModel(sessionId, modelId)
    }

    suspend fun updateSessionBinding(sessionId: String, binding: String, modelId: String) {
        dao.updateSessionBinding(sessionId, binding, modelId)
    }

    /**
     * Toggle a session's pin state.
     *
     * Pinning records [pinnedAt] on the row; the drawer's
     * [com.rikkaminis.app.ui.sessions.groupSessionsByDate] lifts pinned
     * sessions into the PINNED section sorted by pin time (newest first),
     * and [com.rikkaminis.app.ui.chat.ChatHistoryDrawer] keeps pinned
     * sessions visible even when they have zero messages.
     *
     * @param pinned true = pin (timestamp set to now), false = unpin
     *   (pinned_at reset to null). Idempotent either way.
     */
    suspend fun pinSession(sessionId: String, pinned: Boolean) {
        val now = System.currentTimeMillis()
        dao.updatePinnedAt(sessionId, if (pinned) now else null, now)
    }

    /**
     * Delete a session: DB rows first, then the on-disk files.
     *
     * [F-225/F-236] Returns the on-disk [SessionFileStore.DeleteResult] so the
     * caller can tell a clean delete from a partial one. The result used to be
     * dropped on the floor here, which made `DeleteResult.fullyDeleted` a dead
     * property with zero readers anywhere in the tree: when a file was held
     * open or a mount was read-only, `deleteRecursively()` failed silently and
     * the user saw a session disappear from the list while its bytes stayed on
     * disk. A null [sessionFiles] (unit-test construction only) reports
     * `null` — "nothing was reclaimed and we can't tell you more".
     */
    suspend fun deleteSession(id: String): SessionFileStore.DeleteResult? {
        dao.deleteMessages(id)
        dao.deleteSession(id)
        // [A: session file reclamation] Dropping the DB rows previously leaked
        // the bind-mounted session dir (minis-sessions/<id>: workspace /
        // attachments / offloads / browser) and any media, which kept burning
        // disk invisibly — the exact runaway-accumulation that also feeds the
        // backup-OOM (ConfigBackup.export bundles live session content).
        // Deleting a session means deleting the whole session, not just its text.
        val store = sessionFiles
        if (store == null) {
            // [Bug 4 / opt-2] Only unit-test construction reaches here; a null
            // store means on-disk files are NOT reclaimed and would leak
            // silently. Surface it loudly so a missing injection is caught in
            // CI/logs rather than masquerading as a successful delete.
            android.util.Log.w(
                "ChatRepository",
                "deleteSession: sessionFiles is null, skipping on-disk reclamation for $id",
            )
            return null
        }
        val result = store.deleteSessionFiles(id)
        if (!result.fullyDeleted) {
            // [F-225] The storage page already re-measures after a failed delete
            // so the user sees the truth there; the session-delete path had no
            // such signal. Log it so the residue is at least observable.
            AppLogger.warning(
                "ChatRepository",
                "deleteSession($id): partial on-disk reclamation " +
                    "(sessionDeleted=${result.sessionDeleted}, mediaDeleted=${result.mediaDeleted})" +
                    " — bytes may still be on disk",
            )
        }
        return result
    }

    /**
     * Row-only delete: drops the DB rows (and messages) but leaves the session's
     * on-disk files (minis-sessions/<id>/ dir and media) untouched.
     *
     * Used by the empty-session sweep (`cleanupIfEmptyOnExit`) — an "empty"
     * session is one with zero persisted messages, but its dir may still hold a
     * user's uploaded attachments / workspace files that they attached before
     * ever sending. We reclaim only when the USER deletes a session
     * (deleteSession above); auto-swept dead rows leave files for the orphan
     * reclamation path, which the user is shown and confirms explicitly.
     */
    suspend fun deleteSessionRowOnly(id: String) {
        dao.deleteMessages(id)
        dao.deleteSession(id)
    }

    /**
     * [B: reclaim] Remove on-disk leftovers of sessions that no longer exist in
     * the DB (deleted sessions whose dirs survive, draft sessions that never
     * materialised). Returns a report of what was reclaimed.
     */
    suspend fun reclaimOrphanSessionFiles(): SessionFileStore.ReclaimReport {
        val store = sessionFiles ?: return SessionFileStore.ReclaimReport()
        val live = observeSessions().first().map { it.id }.toSet()
        return withContext(Dispatchers.IO) { store.reclaimOrphans(live) }
    }

    /**
     * [T-empty-session-residue] Delete sessions that never received a message.
     *
     * Backstop for the empty-chat residue bug: `cleanupIfEmptyOnExit()` only
     * runs from Compose's `onDispose`, so process death / task-swipe / crash /
     * configuration changes leave message-less rows behind permanently. This
     * runs on app start, where no lifecycle callback is required.
     *
     * @param activeIds sessions currently open — never swept, even if empty.
     * @param graceMillis rows updated more recently than this are left alone,
     *   so a chat mid first-send (row inserted, message not yet committed) is
     *   never deleted out from under the sender.
     * @return number of rows removed.
     */
    suspend fun deleteEmptySessions(
        activeIds: List<String> = emptyList(),
        graceMillis: Long = 60_000L,
    ): Int {
        val guarded = guardActiveIds(activeIds)
        val staleBefore = System.currentTimeMillis() - graceMillis
        return dao.deleteEmptySessions(guarded, staleBefore)
    }

    suspend fun searchSessions(query: String): List<ChatSessionEntity> =
        dao.searchSessions("%$query%")

    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(sessionId)

    /**
     * Load all messages for a session in bounded pages instead of a
     * single SELECT * batch. The legacy `dao.loadMessages` path issued
     * one query whose Cursor result, once materialised, easily exceeded
     * the per-CursorWindow 2 MB ceiling on a session containing even one
     * large tool_result blob (Issue #17) — Android then aborted with
     * SQLiteBlobTooBigException and the chat loader hung the UI thread.
     *
     * This paginated loader keeps each underlying query small enough that
     * the CursorWindow can hold a normal-shaped page. If a single page
     * still contains an individual >2MB row we fall back to fetching
     * that range row-by-row and substitute a proxy MessageEntity for
     * any single row that genuinely can't be materialised — the
     * transcript stays continuous instead of crashing the load.
     *
     * Existing oversized rows are not migrated; new oversized inserts
     * are prevented by the cap in [appendMessage].
     */
    suspend fun loadMessages(sessionId: String): List<MessageEntity> =
        // [mem-spike-diag] 会话加载是「打开/发消息立刻飙」的头号嫌疑路径：
        // 318 条消息 + 大量工具输出在这里被物化进内存。包一层阶段打点，
        // 把 ΔRSS / native heap 前后写进 memspike 日志。
        MemorySpikeRecorder.measurePhaseSuspend(
            kind = "phase:load-messages",
            detail = "session=$sessionId",
        ) {
            loadMessagesInner(sessionId)
        }

    /** [loadMessages] 的实现体（外层只做内存归因打点）。 */
    private suspend fun loadMessagesInner(sessionId: String): List<MessageEntity> {
        // T-android-crash-safe-mode-v2: defensive guard. ChatViewModel.loadSession
        // is already gated upstream, but loadMessages has other call sites
        // (compaction, fork, regenerate-title, debug menu) that could fire
        // from a foreground retry or a Flow collector before the safe-mode
        // dialog is dismissed. Returning an empty list mirrors the "no rows
        // for this session" branch and is harmless for every caller.
        if (com.rikkaminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            android.util.Log.w(
                "ChatRepository",
                "loadMessages: safe-mode active, skipping (sessionId=$sessionId)",
            )
            return emptyList()
        }
        val total = dao.messageCountForSession(sessionId)
        if (total == 0) return emptyList()
        val out = ArrayList<MessageEntity>(total)
        var offset = 0
        while (offset < total) {
            val limit = LOAD_PAGE_SIZE
            val page = try {
                dao.loadMessagesPage(sessionId, offset, limit)
            } catch (e: SQLiteBlobTooBigException) {
                // Fall back to single-row pages so we can isolate the
                // offending blob(s) and serve the rest of the slice.
                loadPageRowByRow(sessionId, offset, limit)
            } catch (e: IllegalStateException) {
                // Some Room/SQLite combinations wrap the CursorWindow
                // overflow in IllegalStateException("Couldn't read row N,
                // col N from CursorWindow"); treat the same way.
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) {
                    loadPageRowByRow(sessionId, offset, limit)
                } else {
                    throw e
                }
            }
            if (page.isEmpty()) break
            out.addAll(page)
            offset += limit
        }
        return out
    }

    private suspend fun loadPageRowByRow(
        sessionId: String,
        baseOffset: Int,
        limit: Int,
    ): List<MessageEntity> {
        val result = ArrayList<MessageEntity>(limit)
        for (i in 0 until limit) {
            val row = try {
                dao.loadMessagesPage(sessionId, baseOffset + i, 1).firstOrNull()
            } catch (e: SQLiteBlobTooBigException) {
                // [fix/audit-s5h2] a genuinely un-materialisable oversized row
                // is now represented by a proxy MessageEntity instead of being
                // silently DROPPED. The KDoc promises "substitute a proxy …
                // transcript stays continuous"; the previous `null ?: continue`
                // skipped the row entirely, so compaction / fork / rerun /
                // regenerate-title operated on a transcript with a hole (and
                // `out.size < total` downstream invariants broke). Keep the
                // row's identity + sort_order so downstream joins stay
                // continuous, and mark the content as elided.
                oversizedProxyRow(sessionId, baseOffset + i)
            } catch (e: IllegalStateException) {
                if (e.message?.contains("CursorWindow", ignoreCase = true) == true) {
                    oversizedProxyRow(sessionId, baseOffset + i)
                } else throw e
            }
            if (row != null) result.add(row)
        }
        return result
    }

    /** Placeholder row for a row too large to materialise (see KDoc above). */
    private fun oversizedProxyRow(sessionId: String, sortOrder: Int): MessageEntity {
        return MessageEntity(
            id = "oversized-$sessionId-$sortOrder",
            sessionId = sessionId,
            role = "assistant",
            partsJson = """[{"type":"text","value":"[oversized content elided]"}]""",
            createdAt = 0L,
            sortOrder = sortOrder,
        )
    }

    // [F-234] `fromSortOrder` = delete boundary (inclusive), NOT a row count.
    suspend fun deleteMessagesAfter(sessionId: String, fromSortOrder: Int) =
        dao.deleteMessagesAfter(sessionId, fromSortOrder)

    /**
     * Rewrite a single message row's parts_json in place. Used by
     * [com.rikkaminis.app.ui.chat.ChatViewModel.rerunFromToolBlock]'s block-
     * boundary cut to trim the kept assistant row to the parts before the
     * target tool_use. Mirrors iOS ChatStore.updateMessageParts.
     */
    suspend fun updateMessageParts(id: String, partsJson: String) =
        dao.updateMessageParts(id, partsJson)

    /** [T-error-persist-android] Set/clear the error sticker on a row by id. */
    suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?) =
        dao.updateMessageErrorInfo(messageId, errorInfo)

    /**
     * [T-error-persist-android] Set/clear the error sticker on a session's last
     * assistant row. See [ChatDao.updateLastAssistantError]. No-op when no
     * assistant row exists yet.
     */
    suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?) =
        dao.updateLastAssistantError(sessionId, errorInfo)

    suspend fun appendMessage(
        sessionId: String,
        role: String,
        partsJson: String,
        tokenUsage: String? = null,
        reasoningContent: String? = null,
        // [T-usage-attribution] Identity of the provider/model that actually
        // produced this message's token usage (after fallback resolution).
        // Optional with defaults so legacy call sites are untouched.
        usageModelId: String? = null,
        usageEntryId: String? = null,
        // [F-233] Terminal error sticker. Callers that copy a message row
        // (SessionForkManager.duplicateSession) must carry this through, or the
        // copy loses the error state that ChatTranscriptRebuild reads.
        errorInfo: String? = null,
    ): MessageEntity {
        // [Diag-appendMessage] Step markers so a hang between tool-END and the
        // next LLM round can be pinned to the exact DAO call that never returns
        // (nextSortOrder / insertMessage / updateLastMessage). [log-observability
        // 2026-09-14] Promoted to AppLogger so the markers land in the structured
        // direct channel ([ChatRepository] rows in files/logs/*.log) for on-device
        // reconstruction; logcat still shows them under `Minis.ChatRepository`.
        val t0 = System.currentTimeMillis()
        AppLogger.info("ChatRepository", "appendMessage: enter session=$sessionId role=$role partsLen=${partsJson.length}")
        val sortOrder = dao.nextSortOrder(sessionId)
        AppLogger.info("ChatRepository", "appendMessage: nextSortOrder done sortOrder=$sortOrder (${System.currentTimeMillis() - t0}ms)")
        val now = System.currentTimeMillis()
        // Cap the body so a runaway tool_result (e.g. a 13 MB browser_use
        // dump — Issue #17) cannot land an oversize blob into a Room row
        // that later fails CursorWindow's 2 MB ceiling on read. We keep
        // the row in the same parts_json shape (text part) so downstream
        // parsers — UI rendering and JSON-array consumers in DAO/search
        // — never break on the truncated payload.
        //
        // [F-226] The budget is BYTES, not chars: CursorWindow's ceiling is a
        // byte ceiling, and the previous `partsJson.length` comparison let a
        // CJK/emoji payload reach ~3-4× the intended size (measured: 1.5 MB
        // for CJK, 2.0 MB for 4-byte emoji against a 500 KB nominal cap) —
        // exactly the SQLiteBlobTooBigException this guard exists to prevent.
        val capped = if (partsJson.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
            buildTruncatedPartsJson(partsJson)
        } else {
            partsJson
        }
        val cappedReasoning = reasoningContent?.let { rc ->
            if (rc.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
                buildTruncatedText(rc)
            } else {
                rc
            }
        }
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            partsJson = capped,
            createdAt = now,
            tokenUsage = tokenUsage,
            sortOrder = sortOrder,
            reasoningContent = cappedReasoning,
            usageModelId = usageModelId,
            usageEntryId = usageEntryId,
            errorInfo = errorInfo,
        )
        val persisted: MessageEntity = try {
            AppLogger.info("ChatRepository", "appendMessage: insertMessage enter id=${message.id}")
            dao.insertMessage(message)
            AppLogger.info("ChatRepository", "appendMessage: insertMessage done (${System.currentTimeMillis() - t0}ms)")
            message
        } catch (e: SQLiteConstraintException) {
            // [RC15] Unique (session_id, sort_order) constraint violated —
            // another concurrent append raced to the same sort_order. Retry
            // with a fresh sort_order from the DB.
            val retrySortOrder = dao.nextSortOrder(sessionId)
            AppLogger.info("ChatRepository", "appendMessage: constraint-retry re-order=$retrySortOrder (original=$sortOrder)")
            // NB: keep `.also { }` as this block's last expression — it's the
            // MessageEntity the `persisted` val is initialized from. Logging
            // AFTER it would make the block's inferred type `Any` (AppLogger.info → Unit).
            message.copy(sortOrder = retrySortOrder).also {
                dao.insertMessage(it)
                AppLogger.info("ChatRepository", "appendMessage: insertMessage(retry) done (${System.currentTimeMillis() - t0}ms)")
            }
        }
        val preview = extractTextPreview(capped)
        AppLogger.info("ChatRepository", "appendMessage: updateLastMessage enter")
        dao.updateLastMessage(sessionId, preview, now)
        AppLogger.info("ChatRepository", "appendMessage: updateLastMessage done (${System.currentTimeMillis() - t0}ms)")
        return persisted
    }

    /**
     * [T-android-session-last-message-live-tool-call] Update ONLY the session's
     * `last_message` preview (and `updated_at`) from an in-progress assistant
     * turn's parts_json — WITHOUT inserting a message row. The agent loop
     * persists the authoritative assistant row only at turn end (after tools
     * execute); during a long tool call the session list would otherwise show a
     * stale preview (or "No messages yet" for a turn with no prior text). This
     * pushes the live tool-call summary / partial text into the list the moment
     * the model emits it, mirroring how iOS overlays the live VM's last message.
     *
     * Uses the same [extractTextPreview] as [appendMessage], so a text-only turn
     * shows its text and a tool-only turn shows the tool summary. No-op when the
     * payload yields no preview (avoids overwriting a good preview with null).
     */
    suspend fun updateSessionPreview(sessionId: String, partsJson: String) {
        AppLogger.info("ChatRepository", "updateSessionPreview: enter session=$sessionId")
        val preview = extractTextPreview(partsJson) ?: return
        dao.updateLastMessage(sessionId, preview, System.currentTimeMillis())
    }

    private fun extractTextPreview(partsJson: String): String? {
        try {
            val array = org.json.JSONArray(partsJson)
            var hasMedia = false
            // T-android-session-last-message-tool-call: also track the most
            // recent tool_use so a mid-tool-call assistant turn (no text yet)
            // renders as a short tool summary instead of falling through to
            // "No messages yet" in the session list.
            var lastToolUse: org.json.JSONObject? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.optString("type")
                if (type == "text") {
                    val text = obj.optString("value", "")
                    if (text.isNotBlank()) {
                        return cleanPreview(text)
                    }
                } else if (type == "mediaRef") {
                    hasMedia = true
                } else if (type == "toolUse") {
                    val v = obj.optJSONObject("value")
                    if (v != null) lastToolUse = v
                }
            }
            if (hasMedia) return "[Image]"
            if (lastToolUse != null) return summarizeToolUse(lastToolUse)
        } catch (_: Exception) {
            if (partsJson.isNotBlank()) return cleanPreview(partsJson)
        }
        return null
    }

    /**
     * Build a short preview string for a `toolUse` value block. Used by the
     * session list when an assistant turn is mid-tool-call and has no text
     * part yet. Strategy mirrors iOS ChatStore.summarizeToolUse (T-ios-
     * session-last-message-tool-call):
     *   1. Prefer model-supplied `tool_title` (carried in the on-disk shape
     *      as `value.description`, or inside the embedded `input` JSON).
     *   2. Else pick the most meaningful arg per known tool family.
     *   3. Else fall back to `🔧 <toolName>`.
     * Output capped at 100 chars to match cleanPreview's text ceiling.
     */
    private fun summarizeToolUse(value: org.json.JSONObject): String {
        val toolName = value.optString("name", "")
        // `description` is where ChatViewModel persists the captured
        // tool_title (see writeAssistantParts / writeAssistantPartsForLive
        // in ChatViewModel.kt — both pass block.toolTitle into "description").
        val description = value.optString("description", "").trim()

        // `input` is stored as an escaped JSON STRING, not a nested object
        // (see ChatViewModel.kt:6491 / :6539). Parse defensively.
        val input: org.json.JSONObject = try {
            val raw = value.opt("input")
            when (raw) {
                is org.json.JSONObject -> raw
                is String -> if (raw.isBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
                else -> org.json.JSONObject()
            }
        } catch (_: Exception) {
            org.json.JSONObject()
        }

        fun str(key: String): String? {
            val v = input.optString(key, "").trim()
            return if (v.isEmpty()) null else v
        }
        fun cap(s: String, n: Int = 100): String =
            if (s.length > n) s.substring(0, n) + "…" else s

        // 1. tool_title — checked both on the outer `description` field and
        //    inside `input` (the model writes it into args; we mirror what
        //    iOS does and accept either location).
        val title = str("tool_title") ?: description.takeIf { it.isNotEmpty() }
        if (title != null) return cap(cleanPreview(title))

        // 2. per-tool key argument
        when (toolName) {
            "shell_execute" -> str("command")?.let { return cap(cleanPreview("$ $it")) }
            "file_read" -> str("path")?.let { return cap(cleanPreview("Reading $it")) }
            "file_write" -> str("path")?.let { return cap(cleanPreview("Writing $it")) }
            "file_edit" -> str("path")?.let { return cap(cleanPreview("Editing $it")) }
            "browser_use" -> {
                val action = str("action") ?: "browse"
                val url = str("url")
                return if (url != null) cap(cleanPreview("$action $url"))
                else cap(cleanPreview("browser_use $action"))
            }
            "conversation_history" -> {
                // [U10] Show what was looked up: the query, or "reading back" for
                // a plain tail read. Without this the block title falls back to a
                // raw "conversation_history" argument dump.
                val q = str("query")
                return cap(cleanPreview(if (q != null) "history: $q" else "reading back the conversation"))
            }
            "memory_write" -> str("content")?.let { return cap(cleanPreview("memory_write: $it")) }
            "memory_get" -> {
                val arr = input.optJSONArray("keywords")
                if (arr != null && arr.length() > 0) {
                    val joined = buildString {
                        for (i in 0 until arr.length()) {
                            if (i > 0) append(", ")
                            append(arr.optString(i))
                        }
                    }
                    if (joined.isNotBlank()) return cap(cleanPreview("memory_get: $joined"))
                }
                str("keywords")?.let { return cap(cleanPreview("memory_get: $it")) }
            }
        }

        // 3. final fallback
        return cap("🔧 ${toolName.ifBlank { "tool" }}")
    }

    private fun cleanPreview(raw: String): String {
        return stripSystemReminders(raw)
            .replace(Regex("[\r\n]+"), " ")      // newlines → space
            .replace(Regex("#{1,6}\\s"), "")      // headings: ## Title → Title
            .replace(Regex("\\*{1,3}|_{1,3}"), "")// bold/italic markers
            .replace(Regex("~~"), "")              // strikethrough
            .replace(Regex("`{1,3}"), "")          // inline/fenced code markers
            .replace(Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE), "") // list bullets
            .replace(Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE), "") // ordered list
            .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")       // blockquote
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // [text](url) → text
            .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1") // ![alt](url) → alt
            .replace(Regex("\\s{2,}"), " ")        // collapse whitespace
            .trim()
            .take(100)
    }

    // ───────────────── T188: minis-sessions-cli backend ─────────────────
    //
    // Three high-level queries surfaced to SessionsOffloadHandler. The DAO
    // side handles raw SQL + result projection; we add the JSON parsing,
    // text extraction, and snippet trimming. Mirrors iOS
    // `ChatStore.swift` L774-1023 line-by-line so the offload tool's
    // output shape is identical across platforms.

    /**
     * Backs `minis-sessions-cli list`. Returns sessions ordered by
     * last_active DESC, optionally filtered by id list, keyword AND, and
     * a date range on `updated_at` (so the user's "show me sessions
     * touched in March 2026" works on the timestamp the session-list UI
     * already exposes).
     *
     * Keyword AND semantics: each keyword has to land *somewhere* — in
     * the title or in any message's parts_json. Two keywords mean both
     * must match (possibly in different messages). This matches iOS,
     * which intentionally avoids requiring keywords to co-occur in one
     * row so a multi-turn session about "python" + "flask" still hits.
     */
    suspend fun querySessionsMeta(
        sessionIds: List<String>?,
        keywords: List<String>?,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<SessionMeta> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!sessionIds.isNullOrEmpty()) {
            conditions += "s.id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "s.updated_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "s.updated_at <= ?"
            args += endMs
        }
        if (!keywords.isNullOrEmpty()) {
            for (kw in keywords) {
                val pat = "%$kw%"
                conditions +=
                    "(s.title LIKE ? OR EXISTS (SELECT 1 FROM messages m " +
                    "WHERE m.session_id = s.id AND m.parts_json LIKE ?))"
                args += pat
                args += pat
            }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val sql = """
            SELECT s.id, s.title,
                   (SELECT m2.parts_json FROM messages m2
                    WHERE m2.session_id = s.id AND m2.role = 'user'
                    ORDER BY m2.sort_order ASC LIMIT 1) AS first_user_msg,
                   s.source, s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages m3 WHERE m3.session_id = s.id) AS msg_count
            FROM sessions s
            $where
            ORDER BY s.updated_at DESC
            LIMIT ?
        """.trimIndent()
        args += limit

        val rows = dao.runSessionsMetaQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        return rows.map { r ->
            val preview = r.firstUserMsg?.let { extractTextForOffload(it) }
                ?.takeIf { it.isNotBlank() }
                ?.take(60)
            SessionMeta(
                id = r.id,
                title = r.title,
                preview = preview,
                source = r.source,
                startedAt = r.createdAt,
                lastActive = r.updatedAt,
                messageCount = r.msgCount,
            )
        }
    }

    /**
     * Backs `minis-sessions-cli search`. Over-fetches `limit * 3` rows
     * because parts_json LIKE matches can hit tool-call JSON metadata
     * (e.g. a tool name that happens to contain the keyword) rather than
     * actual user-visible text. We parse each row's parts_json on the
     * Kotlin side, drop rows whose extracted text is blank or whose
     * keyword didn't survive the parse, and trim to [limit] on the way
     * out. Mirrors iOS ChatStore.searchMessages.
     */
    suspend fun searchMessages(
        sessionIds: List<String>?,
        keywords: List<String>,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageSearchMatch> {
        if (keywords.isEmpty()) return emptyList()
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        for (kw in keywords) {
            conditions += "m.parts_json LIKE ?"
            args += "%$kw%"
        }
        if (!sessionIds.isNullOrEmpty()) {
            conditions += "m.session_id IN (${sessionIds.joinToString(",") { "?" }})"
            args.addAll(sessionIds)
        }
        if (startMs != null) {
            conditions += "m.created_at >= ?"
            args += startMs
        }
        if (endMs != null) {
            conditions += "m.created_at <= ?"
            args += endMs
        }
        val where = conditions.joinToString(" AND ")
        val sql = """
            SELECT m.session_id, m.id, m.role, m.created_at, m.parts_json
            FROM messages m
            WHERE $where
            ORDER BY m.created_at DESC
            LIMIT ?
        """.trimIndent()
        args += (limit * 3)

        val rows = dao.runMessageSearchQuery(
            androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray()),
        )
        val out = mutableListOf<MessageSearchMatch>()
        for (r in rows) {
            val text = extractTextForOffload(r.partsJson)
            if (text.isBlank()) continue
            val snip = keywordSnippet(text, keywords, SNIPPET_MAX)
            if (snip.isBlank()) continue
            out += MessageSearchMatch(r.sessionId, r.id, r.role, r.createdAt, snip)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * Backs `minis-sessions-cli messages --id ... --offset --limit`.
     * Skips messages whose extracted text is blank (system-only reminder
     * content, all-tool-use turns) so the agent sees a contiguous
     * user-visible transcript.
     */
    suspend fun loadMessagePage(
        sessionId: String,
        offset: Int,
        limit: Int,
        // [T-android-sessions-cli-full] Per-message text cap. Default stays the
        // documented 600; `minis-sessions-cli messages --full` passes
        // MESSAGE_TEXT_MAX_FULL (50000) so exports aren't silently gutted.
        maxChars: Int = MESSAGE_TEXT_MAX,
        // [T-android-sessions-cli-messages-daterange] GH#200. Inclusive,
        // independently optional created_at bounds; null = unbounded on that
        // side, so existing callers keep the previous behaviour untouched
        // (they don't pass these, and the two-arg fast path is taken).
        startMs: Long? = null,
        endMs: Long? = null,
    ): List<MessagePageItem> {
        val rows = if (startMs == null && endMs == null) {
            dao.loadMessagesPage(sessionId, offset, limit)
        } else {
            dao.loadMessagesPageInRange(sessionId, offset, limit, startMs, endMs)
        }
        return rows.mapNotNull { e ->
            val text = extractTextForOffload(e.partsJson)
            if (text.isBlank()) return@mapNotNull null
            MessagePageItem(
                e.id, e.role, e.createdAt, text.take(maxChars),
                // Mark messages that exceeded the cap so the caller can emit
                // "truncated": true (mirrors iOS SessionsOffloadBridge).
                truncated = text.length > maxChars,
            )
        }
    }

    suspend fun messageCount(sessionId: String): Int = dao.messageCountForSession(sessionId)

    /**
     * [T-android-sessions-cli-messages-daterange] Count under the same
     * optional range [loadMessagePage] filters by, so the `total` a caller
     * reports and the slice it returned always describe the same set.
     *
     * The null/null fast path keeps the pre-existing query (and its plan)
     * for callers that pass no range, mirroring [loadMessagePage].
     */
    suspend fun messageCountInRange(sessionId: String, startMs: Long?, endMs: Long?): Int =
        if (startMs == null && endMs == null) {
            dao.messageCountForSession(sessionId)
        } else {
            dao.messageCountForSessionInRange(sessionId, startMs, endMs)
        }

    /**
     * Paginated raw [MessageEntity] page — used by [com.rikkaminis.app.share.ChatExporter]
     * to stream-export long sessions without loading every message into
     * memory. Unlike [loadMessagePage] this does not strip / project the
     * row; the exporter needs the full `parts_json` payload to serialize.
     */
    suspend fun loadMessagePageRaw(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> =
        dao.loadMessagesPage(sessionId, offset, limit)

    /**
     * Walk parts_json and concatenate every `{type:"text", value:...}`
     * block (newline-joined) after running [stripSystemReminders] on
     * each. Distinct from [extractTextPreview] / [cleanPreview] above —
     * those collapse markdown for a 100-char single-line preview, while
     * this preserves the full text the offload caller wants to inspect.
     *
     * Precedence when a message mixes part kinds (only the first
     * non-empty tier is returned, so a message carrying both mediaRef
     * and toolUse yields "[Image]" — deliberate, since the consumers are
     * previews and a user-visible transcript where the typed text or the
     * media marker is the more useful signal than the tool list):
     *   1. text blocks (joined)
     *   2. "[Image]" when any mediaRef exists
     *   3. tool names / tool_title when any toolUse exists
     *   4. "[Tool result: …]" summaries when any toolResult exists
     */
    private fun extractTextForOffload(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            val texts = mutableListOf<String>()
            var hasMedia = false
            val toolUses = mutableListOf<org.json.JSONObject>()
            val toolResults = mutableListOf<org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                when (o.optString("type")) {
                    "text" -> {
                        val v = o.optString("value", "")
                        if (v.isNotBlank()) texts.add(stripSystemReminders(v))
                    }
                    "mediaRef" -> hasMedia = true
                    "toolUse" -> o.optJSONObject("value")?.let { toolUses.add(it) }
                    "toolResult" -> o.optJSONObject("value")?.let { toolResults.add(it) }
                }
            }
            if (texts.isNotEmpty()) return texts.joinToString("\n")
            if (hasMedia) return "[Image]"
            if (toolUses.isNotEmpty()) {
                return toolUses.joinToString(", ") { tu ->
                    val title = tu.optString("name", "tool")
                    val inp = tu.optString("input", "")
                    val toolTitle = try {
                        org.json.JSONObject(inp).optString("tool_title", "")
                    } catch (_: Exception) { "" }
                    if (toolTitle.isNotBlank()) toolTitle.take(100) else title
                }
            }
            if (toolResults.isNotEmpty()) {
                return toolResults.joinToString("\n") { tr ->
                    val output = tr.optString("output", "").take(200)
                    "[Tool result: $output]"
                }
            }
            ""
        } catch (_: Exception) {
            stripSystemReminders(partsJson)
        }
    }

    /**
     * Center a snippet of [maxLength] chars on the earliest keyword
     * match (case-insensitive). Tail/head ellipses indicate truncation
     * boundaries. If no keyword survives the parts_json → text reduction
     * (rare but possible — a SQL LIKE hit on tool-use JSON that the text
     * extractor strips), we return the leading [maxLength] chars so the
     * offload caller still sees *something*.
     */
    private fun keywordSnippet(text: String, keywords: List<String>, maxLength: Int): String {
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        var earliest = text.length
        for (kw in keywords) {
            val pos = lower.indexOf(kw.lowercase())
            if (pos in 0 until earliest) earliest = pos
        }
        if (earliest == text.length) return text.take(maxLength)
        val half = maxLength / 2
        val start = (earliest - half).coerceAtLeast(0)
        val end = (start + maxLength).coerceAtMost(text.length)
        var s = text.substring(start, end)
        if (start > 0) s = "…$s"
        if (end < text.length) s = "$s…"
        return s
    }

    companion object {
        /**
         * [T-empty-session-residue] Room rejects an empty `IN ()` list, so an
         * empty active-set must be replaced with a sentinel that can never
         * match a real session id. Extracted for unit testing the guard —
         * getting this wrong means either a crash (empty IN) or, if the
         * sentinel ever equalled a real id, deleting a live session.
         */
        internal fun guardActiveIds(activeIds: List<String>): List<String> =
            if (activeIds.isEmpty()) listOf("") else activeIds

        // <system-reminder>...</system-reminder> blocks are runtime nudges
        // injected into user-role messages by the harness (e.g. task-tracker
        // reminders). They never represent what the user actually typed, so
        // they must not show up in the session-list "last message" preview.
        // DOTALL flag covers multi-line reminder bodies; reluctant
        // quantifier so back-to-back reminders don't merge into one match.
        private val SYSTEM_REMINDER_RE =
            Regex("""<system-reminder>.*?</system-reminder>""", RegexOption.DOT_MATCHES_ALL)

        internal fun stripSystemReminders(raw: String): String =
            SYSTEM_REMINDER_RE.replace(raw, "").trim()

        // T188: snippet/length caps mirror iOS SessionsOffload.m. 600 chars
        // is a balance between giving the agent enough context to
        // disambiguate similar messages and not blowing past the agent's
        // context budget on a long search result.
        internal const val SNIPPET_MAX = 600
        internal const val MESSAGE_TEXT_MAX = 600
        // [T-android-sessions-cli-full] Per-message cap when the caller passes
        // `--full` — matches iOS SessionsOffloadBridge's 50_000 and the CLI
        // help's documented upper bound. A single message beyond this is still
        // truncated and flagged with "truncated": true.
        internal const val MESSAGE_TEXT_MAX_FULL = 50_000

        // Issue #17 — page size for the chat loader. 200 rows per query
        // keeps a normal-shaped CursorWindow well under 2 MB while
        // still amortising query overhead for long sessions.
        private const val LOAD_PAGE_SIZE = 200

        // Issue #17 — hard cap on a single message's parts_json. 500_000
        // BYTES (not chars — [F-226]): a payload truncated to this many
        // UTF-8 bytes plus the JSON wrapper stays comfortably inside a
        // single 2 MB CursorWindow, which is a byte ceiling. The previous
        // char-based comparison let CJK/emoji payloads reach 3-4× this
        // figure and hit the exact CursorWindow failure it guarded against.
        internal const val MAX_MESSAGE_PARTS_JSON_BYTES = 500_000

        /**
         * [F-226] Take the longest prefix of [text] whose UTF-8 encoding is at
         * most [maxBytes], never splitting a surrogate pair (a lone surrogate
         * would encode as U+FFFD and corrupt the tail).
         */
        internal fun takeByUtf8Bytes(text: String, maxBytes: Int): String {
            if (text.isEmpty() || maxBytes <= 0) return ""
            // Fast path: whole string already fits (avoids the scan for the
            // overwhelmingly common small-payload case).
            if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val charBytes = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < text.length -> 4
                    else -> 3
                }
                if (bytes + charBytes > maxBytes) break
                bytes += charBytes
                i += if (charBytes == 4) 2 else 1
            }
            return text.substring(0, i)
        }

        /** [F-226] The user-visible truncation notice appended to a capped payload. */
        private fun truncationMarker(original: String): String =
            "\n\n[Content truncated at " +
                "${MAX_MESSAGE_PARTS_JSON_BYTES / 1000} KB — original length " +
                "${original.length} chars]"

        /** [F-226] Byte-budgeted truncation of a plain string field (reasoning). */
        internal fun buildTruncatedText(original: String): String {
            val marker = truncationMarker(original)
            val keep = takeByUtf8Bytes(original, MAX_MESSAGE_PARTS_JSON_BYTES - marker.toByteArray(Charsets.UTF_8).size)
            return keep + marker
        }

        internal fun buildTruncatedPartsJson(original: String): String {
            val marker = truncationMarker(original)

            fun wrap(text: String): String =
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("type", "text").put("value", text))
                    .toString()

            // The wrapper is measured rather than estimated: JSON escaping of
            // the kept prefix (`"` → `\"`, `\n` → `\n`, …) can inflate the
            // output beyond the input budget, so start from the wrapper+marker
            // overhead and shrink until the *encoded* payload actually fits.
            val overhead = wrap(marker).toByteArray(Charsets.UTF_8).size
            var budget = MAX_MESSAGE_PARTS_JSON_BYTES - overhead
            var out = wrap(marker)
            var guard = 0
            while (budget > 0 && guard < 16) {
                out = wrap(takeByUtf8Bytes(original, budget) + marker)
                val size = out.toByteArray(Charsets.UTF_8).size
                if (size <= MAX_MESSAGE_PARTS_JSON_BYTES) break
                budget -= (size - MAX_MESSAGE_PARTS_JSON_BYTES)
                guard++
            }
            if (out.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_PARTS_JSON_BYTES) {
                // Pathological input (every char escapes): fall back to the
                // marker alone, which is always well under the budget.
                out = wrap(marker)
            }
            return out
        }
    }
}

/** T188: shape of a session row surfaced to `minis-sessions-cli list`. */
data class SessionMeta(
    val id: String,
    val title: String?,
    val preview: String?,
    val source: String?,
    val startedAt: Long,    // ms — sessions.created_at
    val lastActive: Long,   // ms — sessions.updated_at
    val messageCount: Int,
)

/** T188: a single matching message returned by `minis-sessions-cli search`. */
data class MessageSearchMatch(
    val sessionId: String,
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val snippet: String,
)

/** T188: a single message in the paginated transcript returned by
 *  `minis-sessions-cli messages`. */
data class MessagePageItem(
    val messageId: String,
    val role: String,
    val createdAt: Long,
    val text: String,
    // [T-android-sessions-cli-full] True when the stored text exceeded the
    // requested cap and [text] is a prefix. Surfaced as "truncated": true.
    val truncated: Boolean = false,
)
