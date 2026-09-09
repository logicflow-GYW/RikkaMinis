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
 * process can only REQUEST a shutdown; it can never kill the worker on its
 * own counters.
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
    const val FILE_SHUTDOWN = "shutdown"

    /* ── Cancellation ─────────────────────────────────────────────── */

    /** Client → worker: request cancellation. Idempotent (createNewFile). */
    fun writeCancel(dir: File) {
        File(dir, FILE_CANCEL).createNewFile()
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

    /* ── Shutdown (main process → worker) ─────────────────────────── */

    /**
     * Main process → worker: drain then die. Idempotent. Writes the `shutdown`
     * marker into the given directory (the staging root, shared by all request
     * dirs); the worker checks the same marker and only self-reaps once
     * quiescent — never stopService as proof.
     */
    fun writeShutdownRequest(dir: File) {
        File(dir, FILE_SHUTDOWN).createNewFile()
    }

    /** Worker: was the main process asking us to drain? */
    fun shutdownRequested(dir: File): Boolean =
        File(dir, FILE_SHUTDOWN).exists()

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
            File(dir, FILE_STATE).writeText(obj.toString())
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
        readState(dir)?.let { runCatching { JSONObject(it).optString("state", null) }.getOrNull() }
}