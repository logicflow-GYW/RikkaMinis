package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.repository.ChatRepository
import java.io.File

/**
 * [surgery-1120 A-01] File-level tag: the scan below is TOP-LEVEL (so the JVM
 * probe can drive it), and an object-private `TAG` is not visible from it.
 */
private const val TAG = "PartialStreamRecovery"

/**
 * [fix/partial-stream-recovery] Cold-start recovery of streamed partial
 * content (RikkaHub #1663 同构, backlog §27b).
 *
 * The :modelservice worker appends every chunk to the per-run stream.jsonl
 * staging file as it streams, but the client-side flush points
 * (`flushAllStreamingDeltas`) all live on turn-exit paths — a process kill
 * mid-answer leaves the partial content ONLY in that staging file.
 *
 * [surgery-1120 A-01] The worker's `terminal.json` is NOT a transcript commit:
 * it only says the run dir is complete. The transcript commit is the Room
 * `assistant` row, written by the engine AFTER it consumed the stream. So:
 *  - a terminal WITHOUT a committed `transcript.json` is still recoverable
 *    ([PartialStreamRecoveryPolicy] `terminalCommitted`);
 *  - the recovered row uses the deterministic id of the bubble
 *    ([TranscriptCommit.rowIdFor]) — the same id the normal commit wrote — so a
 *    crash between the insert and the marker finds the row instead of
 *    inserting a duplicate.
 *
 * The scan itself lives in [recoverRunDirs] against [RecoveryStore] so the
 * whole decision + write path is exercisable on the JVM with a fake store and
 * real temp dirs; [recover] only adapts Context/ChatRepository to it.
 */
object PartialStreamRecovery {

    private const val STAGING_ROOT = "model-exec"
    internal const val META_FILE = "meta.json"
    internal const val RECOVERED_FILE = "recovered.json"

    /**
     * [fix/stream-recovery-grace-race] One delayed second pass after the
     * cold-start scan (see MinisApp): a kill-then-immediate-reopen leaves the
     * staging dir younger than RECENT_WRITE_GRACE_MS, so the first scan
     * SKIP_ACTIVEs it and — without a retry — nobody would scan again until
     * the next cold start. Must comfortably exceed the grace; recover() is a
     * cheap dir listing, one retry past the grace is enough (a recurring
     * timer would be a new resident task for a path that also self-heals on
     * the next cold start).
     */
    const val RETRY_DELAY_MS: Long = 90_000L

    /** Assistant role string, matching the chat transcript's persisted value. */
    internal const val ROLE_ASSISTANT = "assistant"

    /**
     * Same pattern as the user-cancel cleanup's truncation reminder: an
     * English system-reminder block the model reads on the next call.
     * Deliberately not localized (i18n is for user-authored copy; system
     * reminders stay English, mirroring the cancel path).
     */
    internal val recoveredReminder: String =
        "<system-reminder>This response was interrupted by a process kill. " +
            "Partial content recovered from the streaming log; it may be incomplete.</system-reminder>"

    /**
     * Stamp the owning session id + this stream attempt's bubble identity into
     * the run dir before the stream starts. Never throws — a failed meta write
     * only means the run becomes unattributable (the old behavior), it must
     * not break streaming.
     */
    fun writeMeta(dir: File, sessionId: String, turnId: String?, attempt: Int) {
        TranscriptCommit.writeMeta(dir, sessionId, turnId, attempt)
    }

    /** Scan the staging root and recover killed-run partial streams. */
    suspend fun recover(context: Context, repository: ChatRepository): Int {
        val root = File(context.cacheDir, STAGING_ROOT)
        if (!root.isDirectory) return 0
        return recoverRunDirs(
            root,
            object : RecoveryStore {
                override suspend fun messageExists(id: String): Boolean = repository.messageExists(id)
                override suspend fun appendMessage(sessionId: String, partsJson: String, stableId: String) {
                    repository.appendMessage(
                        sessionId = sessionId,
                        role = ROLE_ASSISTANT,
                        partsJson = partsJson,
                        stableId = stableId,
                    )
                }
            },
        )
    }
}

/**
 * [surgery-1120 A-01] The two writes recovery needs, so the scan is testable
 * without Room/Android (a fake store + real temp dirs is enough).
 */
internal interface RecoveryStore {
    suspend fun messageExists(id: String): Boolean
    suspend fun appendMessage(sessionId: String, partsJson: String, stableId: String)
}

/**
 * The real scan: decide per run dir, insert at most one row per uncommitted
 * spool, then mark it committed and delete it. Never throws.
 *
 * Order inside one dir is the commit contract mirrored from the live path:
 * Room insert → committed marker → delete. A failure anywhere before the
 * marker leaves the dir in place, so the next cold start retries instead of
 * silently dropping the answer.
 */
internal suspend fun recoverRunDirs(
    root: File,
    store: RecoveryStore,
    now: Long = System.currentTimeMillis(),
): Int {
    var recovered = 0
    // [surgery-1120 A-01] One bubble can leave SEVERAL run dirs (a length-wall
    // continuation streams the head and the tail into separate dirs that the
    // live path folds into ONE row). Recovering each dir independently would
    // insert the head and then either duplicate or collide. So dirs are grouped
    // by bubble and their visible text is joined in attempt order — the same
    // single row the live commit would have written.
    val groups = linkedMapOf<String, MutableList<RecoverableDir>>()
    val children = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrDefault(emptyList())
    for (dir in children.orEmpty()) {
        try {
            val stream = File(dir, ModelExecutionService.STREAM_FILE)
            val metaFile = File(dir, PartialStreamRecovery.META_FILE)
            val decision = PartialStreamRecoveryPolicy.decide(
                streamLen = stream.length(),
                terminalPresent = ModelExecutionRunDir.terminalPresent(dir),
                cancelPresent = File(dir, ModelExecutionService.CANCEL_FILE).exists(),
                // [LOOP-1030 R2 M2] `cancel` + `cancel.death` = the client's
                // death cleanup, not a user stop — recoverable content.
                cancelDeathPresent = File(dir, ModelExecutionMailbox.FILE_CANCEL_DEATH).exists(),
                recoveredPresent = File(dir, PartialStreamRecovery.RECOVERED_FILE).exists(),
                hasSessionMeta = metaFile.exists(),
                mtimeAgeMs = now - dir.lastModified(),
                // A terminal only skips when the transcript marker (written
                // AFTER the Room row) is committed.
                terminalCommitted = TranscriptCommit.isCommitted(dir),
                donePresent = stream.readLines().any(ChatStreamJsonl::isDone),
            )
            when (decision) {
                PartialStreamRecoveryPolicy.Decision.RECOVER -> Unit // handled below
                PartialStreamRecoveryPolicy.Decision.SKIP_EMPTY,
                PartialStreamRecoveryPolicy.Decision.SKIP_NO_META,
                -> {
                    // Nothing recoverable and nothing downstream will ever
                    // clean these up (the reaper needs a terminal barrier) —
                    // BUT never delete a dir whose worker is provably alive.
                    if (ModelExecutionRunDir.beatAlive(dir)) continue
                    dir.deleteRecursively()
                    continue
                }
                else -> continue // committed terminal / cancelled / active / already-recovered: keep for their owners
            }
            val meta = TranscriptCommit.readMeta(dir)
            val sessionId = meta?.sessionId
            if (sessionId.isNullOrBlank()) {
                Log.w(TAG, "unattributable killed run (no session meta), discarding: ${dir.name}")
                if (ModelExecutionRunDir.beatAlive(dir)) continue
                dir.deleteRecursively()
                continue
            }
            val text = joinRecoveredText(stream.readLines())
            if (text.isBlank()) {
                if (ModelExecutionRunDir.beatAlive(dir)) continue
                dir.deleteRecursively()
                continue
            }
            val turnId = meta.turnId?.takeIf { it.isNotBlank() }
                ?: "run-${dir.name.removePrefix("run-")}"
            groups.getOrPut("$sessionId|$turnId") { mutableListOf() }.add(
                RecoverableDir(
                    dir = dir,
                    sessionId = sessionId,
                    turnId = turnId,
                    attempt = meta.attempt,
                    text = text,
                    plannedRowId = TranscriptCommit.plannedRowIds(dir).firstOrNull(),
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "recovery skipped for ${dir.name}: ${t.message}")
        }
    }

    for ((_key, dirs) in groups) {
        try {
            recovered += recoverGroup(dirs, store)
        } catch (t: Throwable) {
            Log.w(TAG, "recovery skipped for group ${_key}: ${t.message}")
        }
    }
    return recovered
}

/** One killed run dir that still holds visible text for a bubble. */
private class RecoverableDir(
    val dir: File,
    val sessionId: String,
    val turnId: String,
    /** Attempt ordinal inside the bubble; orders the joined segments. */
    val attempt: Int,
    val text: String,
    /** The id the live path was about to insert (durable plan), if any. */
    val plannedRowId: String?,
)

/**
 * [surgery-1120 A-01] Recover ONE bubble: join its segments in attempt order,
 * insert at most one row, then mark every segment dir committed and delete it.
 *
 * Order is the commit contract mirrored from the live path — Room insert, then
 * the durable marker, then the delete. A crash before the marker leaves the
 * dirs in place and the next cold start finds the row by its deterministic id
 * (`messageExists`) instead of inserting a second one.
 */
private suspend fun recoverGroup(dirs: List<RecoverableDir>, store: RecoveryStore): Int {
    if (dirs.isEmpty()) return 0
    val first = dirs.minByOrNull { it.attempt } ?: return 0
    val ordered = dirs.sortedWith(compareBy({ it.attempt }, { it.dir.name }))
    val joined = ordered.joinToString("") { it.text }
    // The PLAN (written before the live insert) is authoritative; otherwise the
    // id is recomputed from the bubble identity + the first row index, which is
    // exactly what the live path would have used.
    val rowId = ordered.firstNotNullOfOrNull { it.plannedRowId }
        ?: TranscriptCommit.rowIdFor(first.sessionId, first.turnId, 0)

    if (!store.messageExists(rowId)) {
        store.appendMessage(first.sessionId, buildRecoveredPartsJson(joined), rowId)
        Log.i(
            TAG,
            "recovered partial stream: session=${first.sessionId.take(8)} " +
                "chars=${joined.length} dirs=${ordered.size}",
        )
    } else {
        // The Room row landed but the commit marker did not (crash between the
        // two writes). The row IS the transcript — only the marker is missing,
        // so mark and reclaim WITHOUT a second insert.
        Log.i(TAG, "recovered row already present, marking committed: bubble=${first.turnId.take(12)}")
    }
    val marker = TranscriptCommit.Marker(first.sessionId, first.turnId, listOf(rowId), committed = true)
    for (item in ordered) {
        // Stamp BEFORE deleting: if the delete fails the marker prevents a
        // duplicate insert on the next cold start.
        TranscriptCommit.commit(item.dir, marker)
        File(item.dir, PartialStreamRecovery.RECOVERED_FILE).writeText("recovered\n")
        val deleted = runCatching { item.dir.deleteRecursively() }.getOrDefault(false)
        if (!deleted) Log.w(TAG, "recovered but dir delete failed: ${item.dir.name}")
    }
    return 1
}

/**
 * Pure delta-join over one stream.jsonl: concatenates visible text deltas in
 * order (the Text chunk is an incremental delta, matching what the UI
 * accumulated). Thinking/reasoning/tool/usage frames are ignored — the
 * recovered artifact is the visible answer, mirroring the user-cancel
 * cleanup's partial-text semantics. JVM-testable.
 */
internal fun joinRecoveredText(lines: List<String>): String = buildString {
    for (line in lines) {
        if (line.isBlank()) continue
        val chunk = ChatStreamJsonl.decode(line) ?: continue
        if (chunk is LLMStreamChunk.Text) append(chunk.text)
    }
}

/** Serialize the recovered text + reminder into the persisted parts JSON shape. */
internal fun buildRecoveredPartsJson(text: String): String =
    org.json.JSONArray()
        .put(
            org.json.JSONObject().put("type", "text").put("value", text),
        )
        .put(
            org.json.JSONObject().put("type", "text").put("value", PartialStreamRecovery.recoveredReminder),
        )
        .toString()
