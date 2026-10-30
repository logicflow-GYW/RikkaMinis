package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File

/**
 * File-based mailbox protocol for the `:modelservice` worker (TF-B: reliable
 * worker lifecycle — replaces the old "stopSelf == reclamation proof" model).
 *
 * The main process and the worker communicate via marker files inside the
 * per-request staging directory; the worker owns ITS OWN lifecycle via
 * [ModelExecutionLifecycle] (state machine) and a `state.json` dump. The main
 * process cannot kill the worker on its own counters, and — since
 * [FIX-FW-DEAD-SHUTDOWN-LEVER] — it has no shutdown-request marker either:
 * the former `shutdown` file was written but never read by the transition,
 * so it was removed instead of being allowed to imply a lever that did not
 * exist. Worker death is decided purely by its own quiescence.
 *
 * Files per request dir:
 *   - request.json          client → worker: the serialized request
 *   - cancel                client → worker: cancellation requested
 *   - cancel.ack            worker → client: cancel acknowledged
 *   - result.tmp            worker: atomic-write staging (never seen by client)
 *   - result.json           worker → client: terminal result (atomic rename)
 *   - state.json            worker: lifecycle state dump (ACTIVE / STOPPING / DEAD …)
 *   - client.ack            client → worker: client consumed the result
 *   - worker.pid            worker: verifiable pid/runId/nonce ref (see ModelExecutionRunDir)
 *   - worker.ready          worker: fully started & pid ref written
 *   - terminal.json         worker: LAST marker — stream flushed + result committed
 *                           + final state written, then terminal.json created
 *                           atomically; followed by client.ack + self-reap.
 *
 * TF-F ownership rules:
 *   - terminal.json (NOT result.json / cancel.ack alone) is the definitive
 *     "worker has stopped writing to this run dir" signal. result.json can
 *     exist while the worker is still in finishRequest() (writeState) — that
 *     exact race produced the P0 state.json ENOENT FATAL.
 *   - The client deletes a run dir ONLY after [ModelExecutionRunDir.safeToDelete]
 *     (terminal + confirmed worker-pid gone). UNKNOWN liveness ≠ safe.
 *   - All worker writes are defensive (return Boolean, never throw): a
 *     reclaimed dir must log a protocol violation, never crash the worker.
 *
 * Atomicity: the worker writes result.tmp, flush + fsync, then renames to
 * result.json — the client NEVER observes a partial result. Cancel is
 * acknowledged with cancel.ack BEFORE the client deletes the directory (the
 * old code wrote cancel then immediately deleteRecursively, which raced the
 * worker's appends to stream.jsonl / result.json).
 */
object ModelExecutionMailbox {

    const val FILE_REQUEST = "request.json"
    const val FILE_CANCEL = "cancel"
    const val FILE_CANCEL_ACK = "cancel.ack"
    const val FILE_RESULT_TMP = "result.tmp"
    const val FILE_RESULT = "result.json"
    const val FILE_STATE = "state.json"
    const val FILE_CLIENT_ACK = "client.ack"

    /* ── Cancellation ─────────────────────────────────────────────── */

    /** Client → worker: request cancellation. Idempotent (createNewFile). */
    fun writeCancel(dir: File) {
        File(dir, FILE_CANCEL).createNewFile()
    }

    /**
     * Client → observers: the cancel below was written by the DEATH cleanup
     * ([ModelWorkerDiedException]), not by a user stop.
     *
     * [LOOP-1030 R2 M2] Two markers, two audiences:
     *  - [FILE_CANCEL] stays the WIRE signal, unchanged: a worker that was
     *    only frozen (not dead) and thaws must still abort the stream and
     *    self-reap exactly as before.
     *  - [FILE_CANCEL_DEATH] is the SEMANTIC tag for the cold-start recovery
     *    ([PartialStreamRecoveryPolicy]) and the orphan reaper: the content
     *    in stream.jsonl is real model output that the client never appended
     *    (the turn died mid-stream with hadChunks=true → no retry, no
     *    fallback, no appendMessage). Treating it as "discarded by design"
     *    made a worker crash lose the half answer permanently — 11/64
     *    heavy-day runs on 2026-10-28. A death-cancelled dir is recoverable;
     *    a user-cancelled dir is not.
     *
     * Defensive (never throws): death cleanup must not turn a dead worker
     * into a client crash.
     */
    const val FILE_CANCEL_DEATH = "cancel.death"

    fun writeCancelDeath(dir: File) {
        runCatching {
            writeCancel(dir)
            File(dir, FILE_CANCEL_DEATH).createNewFile()
        }
    }

    /** Worker → client: acknowledge that the worker saw the cancel.
     *  Defensive (never throws): the run dir may already have been reclaimed
     *  by the client — a cancelled stream must not FATAL the worker. */
    fun writeCancelAck(dir: File): Boolean = runCatching {
        File(dir, FILE_CANCEL_ACK).createNewFile()
        true
    }.getOrElse {
        android.util.Log.w(ModelExecutionRunDir.TAG, "writeCancelAck failed: ${it.message}")
        false
    }

    /** Client → worker: result consumed. Lets the worker self-reap promptly. */
    fun writeClientAck(dir: File) {
        File(dir, FILE_CLIENT_ACK).createNewFile()
    }

    /* ── State dump (worker → main) ───────────────────────────────── */

    /**
     * Persist the worker's current lifecycle state + a quiescence snapshot.
     * Written before each state transition so diagnostics can answer "why is
     * :modelservice still alive?" without trusting main-process counters.
     *
     * TF-F: defensive — the run dir may have been reclaimed by the client
     * (old P0 FATAL: uncaught `FileNotFoundException .../state.json ENOENT`).
     * Returns true on success, false on any failure (never throws): the
     * caller records a `protocol_violation` and proceeds without crashing.
     * When the dir is absent we still return false so the caller can log the
     * violation with pid/runId/activeRequests.
     */
    fun writeState(
        dir: File,
        state: ModelExecutionWorkerState,
        active: Int,
        unacked: Int = 0,
    ): Boolean {
        if (!dir.isDirectory) return false
        return runCatching {
            val obj = JSONObject().apply {
                put("state", state.name)
                put("active", active)
                put("unacked", unacked)
                put("at", System.currentTimeMillis())
            }
            // [audit-0917] tmp+rename (mirrors touchLivenessBeat) so a
            // concurrent readState never reads a torn JSON file mid-write.
            val f = File(dir, FILE_STATE)
            val tmp = File(dir, "$FILE_STATE.tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString())
            }
            true
        }.getOrElse {
            android.util.Log.w(ModelExecutionRunDir.TAG, "writeState failed (${dir.name}): ${it.message}")
            false
        }
    }

    /** Read the worker's persisted state JSON text (or null when absent). */
    fun readState(dir: File): String? =
        runCatching { File(dir, FILE_STATE).readText().trim().ifEmpty { null } }.getOrNull()

    /** Read the worker's persisted lifecycle-state NAME (ACTIVE / STOPPING / …), or null. */
    fun readStateName(dir: File): String? =
        readState(dir)?.let { raw ->
            runCatching {
                // [audit-0917] optString returns the literal "null" when the key
                // holds JSONObject.NULL - guard with isNull so the caller's null
                // (state unknown) survives instead of becoming the string "null".
                val obj = JSONObject(raw)
                if (obj.isNull("state")) null else obj.optString("state")
            }.getOrNull()
        }
}