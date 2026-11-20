package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * [surgery-1120 A-01] Transcript-commit protocol for a streamed turn.
 *
 * The worker's `terminal.json` only says its run dir is complete. The chat
 * transcript's commit is the Room `assistant` row written by the main process
 * AFTER it consumed the stream, so the two are different events. Before this
 * protocol the client acked and deleted the run dir in the stream collector's
 * `finally` — i.e. before the engine persisted the turn — and recovery then
 * skipped any dir with a terminal as "already delivered".
 *
 * Two files, one order:
 *  - `transcript.json` is written TWICE by the engine: first as a PLAN (the row
 *    id it is about to insert), then as a COMMIT (that row is durable). The
 *    plan exists so a crash BETWEEN the insert and the commit is idempotent:
 *    recovery reads the planned id, finds the row, and inserts nothing.
 *  - `client.ack` is written by the collector only after the commit marker —
 *    the ack is the LAST step, so an ack can never exist for an uncommitted
 *    run.
 *
 * The row id is deterministic ([rowIdFor]): derived from the bubble identity
 * and the row's index inside that bubble. Recovery derives the SAME id, so
 * "inserted but not marked" is found instead of duplicated, and no random UUID
 * is minted on the recovery path. The shape stays a UUID string so every
 * existing consumer of `MessageEntity.id` keeps working.
 */
object TranscriptCommit {

    const val FILE_MARKER = "transcript.json"
    const val FILE_META = "meta.json"
    internal const val KEY_SESSION_ID = "session_id"
    internal const val KEY_TURN_ID = "turn_id"
    internal const val KEY_ATTEMPT = "attempt"
    internal const val KEY_ROW_INDEX = "row_index"
    internal const val KEY_MESSAGE_IDS = "message_ids"
    internal const val KEY_COMMITTED = "committed"

    /** Identity of one streamed attempt's bubble + the rows it wrote. */
    data class Marker(
        val sessionId: String,
        val turnId: String,
        val messageIds: List<String>,
        val committed: Boolean,
    ) {
        fun encode(): String = JSONObject().apply {
            put(KEY_SESSION_ID, sessionId)
            put(KEY_TURN_ID, turnId)
            put(KEY_MESSAGE_IDS, org.json.JSONArray(messageIds))
            put(KEY_COMMITTED, committed)
        }.toString()
    }

    /** What `meta.json` carries before any row exists (turn id optional). */
    data class Meta(val sessionId: String, val turnId: String?, val attempt: Int)

    /**
     * Deterministic row id for row [index] of the bubble [turnId]. Stable
     * across processes (the recovery path recomputes it) and shaped like a
     * UUID so the rest of the app keeps treating message ids as opaque.
     */
    fun rowIdFor(sessionId: String, turnId: String, index: Int): String =
        UUID.nameUUIDFromBytes("asst|$sessionId|$turnId|$index".toByteArray(Charsets.UTF_8)).toString()

    /** Marker path inside one run dir. */
    fun markerFile(dir: File): File = File(dir, FILE_MARKER)

    /** True only when the marker says the transcript row is durable. */
    fun isCommitted(dir: File): Boolean = readMarker(dir)?.committed == true

    /**
     * The planned row ids of a run whose insert may or may not have landed.
     * A PLAN (committed=false) is what makes the crash-between-insert-and-
     * commit window idempotent: recovery reads the id the live path was about
     * to insert and finds that row instead of inserting a second one.
     */
    fun plannedRowIds(dir: File): List<String> = readMarker(dir)?.messageIds.orEmpty()

    /**
     * Write the PLAN (before the insert). The marker exists but is not
     * committed, so recovery still treats the dir as recoverable and uses the
     * planned ids to decide whether the row already landed.
     */
    fun writePlan(dir: File, marker: Marker): Boolean =
        durableWrite(markerFile(dir), marker.copy(committed = false).encode())

    /** Write the COMMIT (after the insert) and then the worker's client ack. */
    fun commit(dir: File, marker: Marker): Boolean {
        if (!durableWrite(markerFile(dir), marker.copy(committed = true).encode())) return false
        return runCatching {
            ModelExecutionMailbox.writeClientAck(dir)
            true
        }.getOrDefault(false)
    }

    /** Write `meta.json` identity BEFORE the stream starts (recovery attribution). */
    fun writeMeta(dir: File, sessionId: String, turnId: String?, attempt: Int) {
        runCatching {
            val obj = JSONObject().put(KEY_SESSION_ID, sessionId)
            if (!turnId.isNullOrBlank()) obj.put(KEY_TURN_ID, turnId)
            obj.put(KEY_ATTEMPT, attempt)
            durableWrite(File(dir, FILE_META), obj.toString() + "\n")
        }
    }

    /**
     * Best-effort read of a marker (plan or commit), or null when absent.
     */
    fun readMarker(dir: File): Marker? = readObject(markerFile(dir))?.let { obj ->
        val session = obj.optString(KEY_SESSION_ID).takeIf { it.isNotBlank() } ?: return null
        val turn = obj.optString(KEY_TURN_ID).takeIf { it.isNotBlank() } ?: return null
        val ids = obj.optJSONArray(KEY_MESSAGE_IDS)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val committed = obj.optBoolean(KEY_COMMITTED, false)
        Marker(session, turn, ids, committed)
    }

    /** Best-effort read of `meta.json` (session required, turn optional). */
    fun readMeta(dir: File): Meta? = readObject(File(dir, FILE_META))?.let { obj ->
        val session = obj.optString(KEY_SESSION_ID).takeIf { it.isNotBlank() } ?: return null
        Meta(
            sessionId = session,
            turnId = obj.optString(KEY_TURN_ID).takeIf { it.isNotBlank() },
            attempt = obj.optInt(KEY_ATTEMPT, 0),
        )
    }

    private fun readObject(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    /** tmp + flush + fsync + rename + directory fsync; false on any failure. */
    private fun durableWrite(target: File, text: String): Boolean = runCatching {
        val parent = target.parentFile ?: return false
        val tmp = File(parent, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        ModelExecutionRunDir.fsyncDir(parent)
        true
    }.getOrDefault(false)
}
