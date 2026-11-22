package com.rikkaminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.repository.ChatRepository
import java.io.File

/**
 * [surgery-1120 A-01 review] File-level tag: the scan below is TOP-LEVEL (so
 * the JVM probe can drive it), and an object-private `TAG` is not visible
 * from it.
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
 *    ([PartialStreamRecoveryPolicy]);
 *  - the recovered row uses the deterministic id of the bubble ROW
 *    ([TranscriptCommit.rowIdFor]) — the same id the normal commit planned —
 *    so a crash between the insert and the marker finds the row instead of
 *    inserting a duplicate.
 *
 * [surgery-1120 A-01 review] Three things the first cut got wrong, all of them
 * cheap to state and expensive to leave in:
 *
 *  1. **Cost.** Every dir — including active, terminal-committed, cancelled and
 *     non-chat ones — was `readLines()`ed twice (once for the DONE probe, once
 *     for the text), holding every body at once. Recovery now answers every
 *     cheap gate first and scans the spool at most once, bounded, and only for
 *     dirs whose decision depends on content ([StreamScan]).
 *  2. **Ownership.** The late pass (90 s after start) ran against a staging
 *     root this process was actively streaming into, and a dir's mtime does
 *     not move when the worker appends inside it. A live run therefore looked
 *     abandoned and could be inserted as a finished answer while the engine
 *     was still going to write it. Recovery now refuses dirs the process still
 *     owns ([StreamOwnership]) and dirs a worker can still write
 *     ([ModelExecutionRunDir.workerMayWrite]), and only considers dirs that
 *     existed before this process's first stream (the boot baseline).
 *  3. **Row identity.** A bubble can hold several assistant rows (tool turn +
 *     final text). The row index is stamped into `meta.json` at stream start
 *     and used to group recovery, so a recovered spool lands where the live
 *     path would have put it instead of always row 0.
 *
 * The scan itself lives in [recoverRunDirs] against [RecoveryStore] so the
 * whole decision + write path is exercisable on the JVM with a fake store and
 * real temp dirs; [recover] only adapts Context/ChatRepository to it.
 */
object PartialStreamRecovery {

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

    /**
     * [surgery-1120 A-01 review] Per-run-dir read ceiling. A normal answer's
     * stream.jsonl is hundreds of KB; 8 MiB is far beyond any real answer and
     * still bounded. A spool longer than this is recovered as its prefix and
     * keeps the same "may be incomplete" reminder the partial recovery always
     * carried — never a silent, unmarked truncation.
     */
    internal const val MAX_SCAN_BYTES: Long = 8L * 1024 * 1024

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
     * Stamp the owning session id + this stream attempt's bubble identity and
     * ROW index into the run dir before the stream starts. Never throws — a
     * failed meta write only means the run becomes unattributable (the old
     * behavior), it must not break streaming.
     */
    fun writeMeta(dir: File, sessionId: String, turnId: String?, attempt: Int, rowIndex: Int? = null) {
        TranscriptCommit.writeMeta(dir, sessionId, turnId, attempt, rowIndex)
    }

    /** Scan the staging root and recover killed-run partial streams. */
    suspend fun recover(context: Context, repository: ChatRepository): Int {
        val root = File(context.cacheDir, StreamOwnership.STAGING_ROOT)
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
 * The real scan: decide per run dir, insert at most one row per recoverable
 * (bubble, row) group, then mark it committed and delete it. Never throws.
 *
 * Order inside one dir is the commit contract mirrored from the live path:
 * Room insert → committed marker → delete. A failure anywhere before the
 * marker leaves the dir in place, so the next cold start retries instead of
 * silently dropping the answer.
 *
 * [procRoot] is injectable so the JVM test can model a worker that is alive
 * (heartbeat / pid) without a real `/proc`.
 */
internal suspend fun recoverRunDirs(
    root: File,
    store: RecoveryStore,
    now: Long = System.currentTimeMillis(),
    procRoot: File = File("/proc"),
): Int {
    var recovered = 0
    // [surgery-1120 A-01] One bubble ROW can leave SEVERAL run dirs (a
    // length-wall continuation streams the head and the tail into separate
    // dirs that the live path folds into ONE row). Recovering each dir
    // independently would insert the head and then either duplicate or
    // collide. Dirs are grouped by (bubble, row index) and their visible text
    // is joined in attempt order — the same single row the live commit would
    // have written.
    //
    // [surgery-1120 A-01 review] The row index comes from the dir's meta
    // (stamped at stream start). Dirs without it (older build) keep the old
    // contract: one row per bubble, attempt order.
    val groups = linkedMapOf<String, MutableList<RecoverableDir>>()
    val children = runCatching { root.listFiles()?.filter { it.isDirectory } }.getOrDefault(emptyList())
    for (dir in children.orEmpty()) {
        try {
            if (!recoverDir(dir, root, store, now, procRoot, groups)) continue
        } catch (t: Throwable) {
            Log.w(TAG, "recovery skipped for ${dir.name}: ${t.message}")
        }
    }

    for ((key, dirs) in groups) {
        try {
            recovered += recoverGroup(root, dirs, store, procRoot)
        } catch (t: Throwable) {
            Log.w(TAG, "recovery skipped for group $key: ${t.message}")
        }
    }
    return recovered
}

/**
 * Decide ONE dir and, when it is a recoverable candidate, add it to [groups].
 * Returns false when the dir was visited here and needs no group work.
 *
 * Split out of [recoverRunDirs] only so the per-dir body stays readable; there
 * is exactly one caller and no second copy of the decision table.
 */
private suspend fun recoverDir(
    dir: File,
    root: File,
    store: RecoveryStore,
    now: Long,
    procRoot: File,
    groups: MutableMap<String, MutableList<RecoverableDir>>,
): Boolean {
    val stream = File(dir, ModelExecutionService.STREAM_FILE)
    val metaFile = File(dir, PartialStreamRecovery.META_FILE)
    val runId = ModelExecutionDispatcher.runIdOf(dir)

    // [surgery-1120 A-01 review] A dir created AFTER this process's first
    // stream is not a killed run: it is ours (or belongs to a turn that is
    // still unwinding). Not even a candidate — no content read.
    if (!StreamOwnership.existedAtBoot(dir)) return false

    // Memoised: the policy's `readDone` and the text below share one bounded
    // pass, and a dir answered by a cheap gate is never read at all.
    var scan: StreamScan.Result? = null
    fun scanOnce(): StreamScan.Result = scan ?: StreamScan.scan(stream, PartialStreamRecovery.MAX_SCAN_BYTES)
        .also { scan = it }

    val decision = PartialStreamRecoveryPolicy.decide(
        streamLen = stream.length(),
        terminalPresent = ModelExecutionRunDir.terminalPresent(dir),
        cancelPresent = File(dir, ModelExecutionService.CANCEL_FILE).exists(),
        // [LOOP-1030 R2 M2] `cancel` + `cancel.death` = the client's death
        // cleanup, not a user stop — recoverable content.
        cancelDeathPresent = File(dir, ModelExecutionMailbox.FILE_CANCEL_DEATH).exists(),
        recoveredPresent = File(dir, PartialStreamRecovery.RECOVERED_FILE).exists(),
        hasSessionMeta = metaFile.exists(),
        mtimeAgeMs = now - dir.lastModified(),
        terminalCommitted = TranscriptCommit.isCommitted(dir),
        readDone = { scanOnce().done },
        ownerLive = StreamOwnership.isLive(dir),
        workerMayWrite = ModelExecutionRunDir.workerMayWrite(dir, runId, procRoot, now),
    )
    when (decision) {
        PartialStreamRecoveryPolicy.Decision.RECOVER -> Unit // handled below
        PartialStreamRecoveryPolicy.Decision.SKIP_EMPTY,
        PartialStreamRecoveryPolicy.Decision.SKIP_NO_META,
        -> {
            // Nothing recoverable and nothing downstream will ever clean these
            // up (the reaper needs a terminal barrier) — through the one
            // delete entry point, which re-checks ownership and the path guard.
            ModelExecutionRunDir.deleteIfUnowned(dir, root, runId, procRoot)
            return false
        }
        // keep for their owners (committed terminal / cancelled / active /
        // already-recovered / owned by this process)
        else -> return false
    }

    val meta = TranscriptCommit.readMeta(dir)
    val sessionId = meta?.sessionId
    if (sessionId.isNullOrBlank()) {
        Log.w(TAG, "unattributable killed run (no session meta), discarding: ${dir.name}")
        ModelExecutionRunDir.deleteIfUnowned(dir, root, runId, procRoot)
        return false
    }
    val scanned = scanOnce()
    val text = scanned.text
    if (text.isBlank()) {
        // No visible text yet — nothing to insert, and nothing downstream
        // reclaims it either (it has no terminal to reach the reaper).
        ModelExecutionRunDir.deleteIfUnowned(dir, root, runId, procRoot)
        return false
    }
    if (!scanned.complete) {
        Log.w(
            TAG,
            "recovering a truncated spool (over ${PartialStreamRecovery.MAX_SCAN_BYTES} bytes): ${dir.name}",
        )
    }
    if (scanned.droppedFrames > 0) {
        Log.w(TAG, "spool ${dir.name} dropped ${scanned.droppedFrames} oversized frame(s)")
    }
    val turnId = meta.turnId?.takeIf { it.isNotBlank() }
        ?: "run-${dir.name.removePrefix("run-")}"
    // [surgery-1120 A-01 review] The row a spool lands in is part of its
    // identity: a tool-turn bubble persists row 0 and the final text row 1, and
    // two dirs of the SAME bubble can belong to different rows.
    val groupKey = if (meta.rowIndex != null) {
        "$sessionId|$turnId|r${meta.rowIndex}"
    } else {
        "$sessionId|$turnId"
    }
    groups.getOrPut(groupKey) { mutableListOf() }.add(
        RecoverableDir(
            dir = dir,
            sessionId = sessionId,
            turnId = turnId,
            rowIndex = meta.rowIndex,
            attempt = meta.attempt,
            text = text,
            plannedRowId = TranscriptCommit.plannedRowIds(dir).firstOrNull(),
        )
    )
    return true
}

/** One killed run dir that still holds visible text for a bubble ROW. */
private class RecoverableDir(
    val dir: File,
    val sessionId: String,
    val turnId: String,
    /** `meta.json` row index, or null for a dir from before it was stamped. */
    val rowIndex: Int?,
    /** Attempt ordinal inside the bubble; orders the joined segments. */
    val attempt: Int,
    val text: String,
    /** The id the live path was about to insert (durable plan), if any. */
    val plannedRowId: String?,
)

/**
 * [surgery-1120 A-01] Recover ONE bubble row: join its segments in attempt
 * order, insert at most one row, then mark every segment dir committed and
 * delete it.
 *
 * Order is the commit contract mirrored from the live path — Room insert, then
 * the durable marker, then the delete. A crash before the marker leaves the
 * dirs in place and the next cold start finds the row by its deterministic id
 * (`messageExists`) instead of inserting a second one.
 */
private suspend fun recoverGroup(
    root: File,
    dirs: List<RecoverableDir>,
    store: RecoveryStore,
    procRoot: File,
): Int {
    if (dirs.isEmpty()) return 0
    val first = dirs.minByOrNull { it.attempt } ?: return 0
    val ordered = dirs.sortedWith(compareBy({ it.attempt }, { it.dir.name }))
    val joined = ordered.joinToString("") { it.text }
    // The PLAN (written before the live insert) is authoritative; otherwise the
    // id is recomputed from the bubble identity + the row index the live path
    // would have used (row 0 for a dir from before the index was stamped).
    val rowId = ordered.firstNotNullOfOrNull { it.plannedRowId }
        ?: TranscriptCommit.rowIdFor(first.sessionId, first.turnId, first.rowIndex ?: 0)

    // The live engine may still be inside its persist: an insert raced by the
    // real one would violate the unique (session, sort_order) or duplicate the
    // answer. Ownership was already refused at admission; this is the second,
    // per-dir check immediately before the write.
    val anyOwned = ordered.any {
        StreamOwnership.isLive(it.dir) ||
            ModelExecutionRunDir.workerMayWrite(it.dir, ModelExecutionDispatcher.runIdOf(it.dir), procRoot)
    }
    if (anyOwned) {
        Log.w(TAG, "recovery deferred: a segment is still owned bubble=${first.turnId.take(12)}")
        return 0
    }

    if (!store.messageExists(rowId)) {
        store.appendMessage(first.sessionId, buildRecoveredPartsJson(joined), rowId)
        Log.i(
            TAG,
            "recovered partial stream: session=${first.sessionId.take(8)} " +
                "row=${first.rowIndex ?: 0} chars=${joined.length} dirs=${ordered.size}",
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
        runCatching { File(item.dir, PartialStreamRecovery.RECOVERED_FILE).writeText("recovered\n") }
        val deleted = ModelExecutionRunDir.deleteIfUnowned(
            item.dir,
            root,
            ModelExecutionDispatcher.runIdOf(item.dir),
            procRoot,
        )
        if (!deleted) Log.w(TAG, "recovered but dir kept (owned or delete failed): ${item.dir.name}")
    }
    return 1
}

/**
 * Pure delta-join over one stream.jsonl: concatenates visible text deltas in
 * order (the Text chunk is an incremental delta, matching what the UI
 * accumulated). Thinking/reasoning/tool/usage frames are ignored — the
 * recovered artifact is the visible answer, mirroring the user-cancel
 * cleanup's partial-text semantics. JVM-testable.
 *
 * Production recovery reads through [StreamScan] (bounded, single pass); this
 * join is kept for callers that already hold the lines.
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
