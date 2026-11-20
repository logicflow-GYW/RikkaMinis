package com.rikkaminis.app.sandbox.offload

import android.util.Log
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [surgery-1120 A-01] One streamed attempt's transcript-commit handoff.
 *
 * The stream collector cannot know when the engine's Room row becomes durable:
 * its terminal cleanup runs inside the flow's `finally`, i.e. BEFORE
 * `persistAssistantTurn` in the same call stack, so WAITING there would
 * deadlock the very write it waits for. This handle splits the two sides:
 *
 *  - the collector ATTACHES its run dir and, on a normal DONE, registers a
 *    waiter that watches for the engine's outcome;
 *  - the engine PLANS the row id before the insert (a durable `transcript.json`
 *    plan), then COMMITs it after the row is durable — or ABANDONS it when the
 *    row could not be written.
 *
 * The waiter's outcomes:
 *  - Committed → the collector writes the worker's `client.ack` and reclaims
 *    the run dir (the spool is redundant).
 *  - Abandoned / timeout → NO ack and NO delete: the terminal spool stays the
 *    only copy of the answer, so cold-start recovery owns it. This is the
 *    crash window the protocol exists for.
 *
 * [turnId] is the bubble identity (stable for the whole bubble, unique per
 * bubble) and [sessionId] the owning session; together they key the durable
 * marker, and the row id is derived from them ([TranscriptCommit.rowIdFor]) so
 * a recovery recomputes the SAME id the live insert used.
 */
class TranscriptCommitHandle internal constructor(
    val turnId: String,
    private val sessionId: String,
    val attempt: Int,
    /**
     * [surgery-1120 A-01 review] Which assistant row of the bubble this
     * attempt's text belongs to (the engine's `transcriptRowIndex` at stream
     * start). Stamped into the run dir's `meta.json` before the stream, which
     * is the only moment it is known — a crash later would otherwise force
     * recovery to guess row 0 for what is really the bubble's later row.
     */
    val rowIndex: Int? = null,
) {

    sealed class Outcome {
        /** The transcript row is durable (or the engine wrote none on purpose). */
        data class Committed(val rowId: String?) : Outcome()

        /** No transcript row will ever be written for this attempt. */
        data object Abandoned : Outcome()
    }

    private val lock = Any()
    private var dir: File? = null
    private var plannedRowIds: List<String> = emptyList()
    private val settled = CompletableDeferred<Outcome>()

    /** Collector side: the run dir this attempt owns. */
    fun attachDir(dir: File) {
        synchronized(lock) { this.dir = dir }
    }

    /**
     * [surgery-1120 A-01 review] Is the spool still on disk? The engine uses
     * this to tell "the plan failed and a crash would duplicate a live spool"
     * (refuse to insert) from "the plan failed because the collector already
     * acks+deleted the dir" (nothing can be duplicated — insert away). On the
     * second shape the spool is deliberately absent: the collector only deletes
     * when it never had to wait for a commit (error / user stop).
     */
    internal fun spoolPresent(): Boolean = synchronized(lock) { dir?.isDirectory == true }

    /**
     * Engine side: record the row id the insert is about to use, DURABLY,
     * BEFORE the insert. A crash after the insert but before the commit then
     * leaves a plan that recovery reads to find the row instead of inserting a
     * second one.
     *
     * Returns false when the plan could not be made durable. The caller MUST
     * NOT insert in that case: without a durable plan a crash between the
     * insert and the commit leaves recovery no id to look for, so it would
     * insert the spool a second time. [plannedRowIds] is only updated after
     * the write succeeded, so a failed plan cannot be mistaken for a real one
     * by [commit].
     */
    fun plan(rowId: String): Boolean {
        val target = synchronized(lock) { dir } ?: return false
        val written = TranscriptCommit.writePlan(
            target,
            TranscriptCommit.Marker(sessionId, turnId, listOf(rowId), committed = false),
        )
        if (written) synchronized(lock) { plannedRowIds = listOf(rowId) }
        return written
    }

    /** Engine side: the row is durable (or the engine wrote none on purpose). */
    fun commit(rowId: String?) {
        val target = synchronized(lock) { dir } ?: return
        val ids = synchronized(lock) { plannedRowIds.ifEmpty { listOfNotNull(rowId) } }
        val markerWritten = TranscriptCommit.commit(
            target,
            TranscriptCommit.Marker(sessionId, turnId, ids, committed = true),
        )
        if (markerWritten) settled.complete(Outcome.Committed(rowId))
        else settled.complete(Outcome.Abandoned)
    }

    /**
     * Engine side: this attempt's output was ROLLED BACK (transient retry /
     * provider fallback / user stop). Nothing of it belongs in the transcript,
     * and its spool must not be resurrected either — so the dir is settled as
     * committed-with-no-row (the collector acks + deletes), while recovery
     * sees a committed marker and skips it.
     *
     * A marker write can fail (disk full). The end state must still be "this
     * text is discarded", so the decision is re-recorded through the second,
     * independent no-resurrect marker the policy already honours
     * (`recovered.json`). If BOTH fail the dir is left in place with neither
     * marker, which recovery reads as an uncommitted spool — logged loudly,
     * because that is the one shape where rolled-back text can be recovered as
     * an answer.
     */
    fun discard() {
        val target = synchronized(lock) { dir }
        // A dir that is already gone (the collector acked and deleted it on the
        // stop path) needs no marker: there is nothing left to resurrect.
        if (target != null && target.isDirectory) {
            val committed = TranscriptCommit.commit(
                target,
                TranscriptCommit.Marker(sessionId, turnId, emptyList(), committed = true),
            )
            if (!committed) {
                val fallback = runCatching {
                    File(target, PartialStreamRecovery.RECOVERED_FILE).writeText("discarded\n")
                    true
                }.getOrDefault(false)
                if (!fallback) {
                    Log.w(
                        TAG,
                        "discard left no durable marker; recovery may resurrect this spool dir=${target.name}",
                    )
                } else {
                    Log.w(TAG, "discard marker write failed; used the recovered marker dir=${target.name}")
                }
            }
        }
        settled.complete(Outcome.Committed(null))
    }

    /**
     * Engine side: the row could NOT be written (the insert threw). The spool
     * is still the only copy of the answer, so no marker, no ack, no delete.
     */
    fun abandon() {
        settled.complete(Outcome.Abandoned)
    }

    /** Collector side: bounded wait for the engine's outcome. */
    suspend fun awaitOutcome(timeoutMs: Long): Outcome? =
        withTimeoutOrNull(timeoutMs) { settled.await() }

    private companion object {
        const val TAG = "TranscriptCommit"
    }
}
