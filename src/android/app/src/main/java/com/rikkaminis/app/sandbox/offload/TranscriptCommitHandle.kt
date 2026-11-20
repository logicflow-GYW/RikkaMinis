package com.rikkaminis.app.sandbox.offload

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
     * Engine side: record the row id the insert is about to use, DURABLY,
     * BEFORE the insert. A crash after the insert but before the commit then
     * leaves a plan that recovery reads to find the row instead of inserting a
     * second one.
     */
    fun plan(rowId: String): Boolean {
        val target = synchronized(lock) {
            plannedRowIds = listOf(rowId)
            dir
        } ?: return false
        return TranscriptCommit.writePlan(
            target,
            TranscriptCommit.Marker(sessionId, turnId, listOf(rowId), committed = false),
        )
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
     * provider fallback). Nothing of it belongs in the transcript, and its
     * spool must not be resurrected either — so the dir is settled as
     * committed-with-no-row (the collector acks + deletes), while recovery
     * sees a committed marker and skips it.
     */
    fun discard() {
        val target = synchronized(lock) { dir }
        if (target != null) {
            // Empty row list on purpose: "delivered, and there is nothing to
            // insert". A missing marker would make recovery resurrect text the
            // engine already threw away.
            TranscriptCommit.commit(
                target,
                TranscriptCommit.Marker(sessionId, turnId, emptyList(), committed = true),
            )
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
}
