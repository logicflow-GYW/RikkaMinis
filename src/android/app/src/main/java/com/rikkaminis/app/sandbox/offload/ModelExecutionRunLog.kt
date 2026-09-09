package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject
import java.io.File

/**
 * TF-G (P1-1): durable worker-phase log for a single `run-<uuid>` dir.
 *
 * main-process logcat buffering is small and lossy, so the only reliable
 * evidence of "what the :modelservice worker actually did before it died" is
 * a per-run JSONL file written from inside the worker. Every significant
 * phase is appended here (best-effort, never throws); when the client detects
 * a worker death it reads the tail of this log and attaches it to the
 * exception / warning for diagnosis.
 *
 * Phase labels (see [ModelExecutionRunLog.Phase]):
 *   PROCESS_START → REQUEST_ACCEPTED → REQUEST_PARSED → PROVIDER_BUILT →
 *   HTTP_STARTED → FIRST_CHUNK → STREAM_DONE / STREAM_ERROR →
 *   RESULT_COMMITTED → STATE_WRITTEN → TERMINAL_WRITTEN → CLIENT_ACK_SEEN →
 *   SELF_REAP
 *
 * All writes are `runCatching`-guarded: a logging failure must never affect
 * request execution.
 */
object ModelExecutionRunLog {

    const val FILE_NAME = "run.log.jsonl"
    private const val TAG = "ModelExecRunLog"
    private const val TAIL_BYTES = 4096
    private const val MAX_LINES = 64

    /** Worker-side phase token (also logged via android.util.Log for live view). */
    object Phase {
        const val PROCESS_START = "PROCESS_START"
        const val REQUEST_ACCEPTED = "REQUEST_ACCEPTED"
        const val REQUEST_THREAD_START = "REQUEST_THREAD_START"
        const val REQUEST_PARSED = "REQUEST_PARSED"
        const val PROVIDER_BUILT = "PROVIDER_BUILT"
        const val HTTP_STARTED = "HTTP_STARTED"
        const val FIRST_CHUNK = "FIRST_CHUNK"
        const val STREAM_DONE = "STREAM_DONE"
        const val STREAM_ERROR = "STREAM_ERROR"
        const val RESULT_COMMITTED = "RESULT_COMMITTED"
        const val STATE_WRITTEN = "STATE_WRITTEN"
        const val TERMINAL_WRITTEN = "TERMINAL_WRITTEN"
        const val CLIENT_ACK_SEEN = "CLIENT_ACK_SEEN"
        const val SELF_REAP = "SELF_REAP"
    }

    fun file(dir: File): File = File(dir, FILE_NAME)

    /**
     * Append one phase line. Never throws. The caller passes its own pid.
     * `detail` is a short string; `runId` is optional (pulled at open time for
     * the few spots that haven't parsed it yet).
     */
    fun log(dir: File, pid: Int, phase: String, detail: String? = null, runId: String? = null) {
        runCatching {
            // Ensure the directory still exists; a reclaimed dir just drops
            // this line (the client will see run_dir_missing).
            if (!dir.isDirectory) return
            val line = JSONObject().apply {
                put("at", System.currentTimeMillis())
                put("pid", pid)
                put("phase", phase)
                detail?.let { put("detail", it) }
                runId?.let { put("runId", it) }
            }.toString()
            java.io.FileOutputStream(file(dir), true).use { fos ->
                fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        }
    }

    /**
     * Read the tail of this run's phase log — bounded both in bytes (last
     * [TAIL_BYTES]) and line count (last [MAX_LINES]). Returns the lines in
     * chronological order. Never throws (returns emptyList on any failure).
     * Used by the client when classifying a worker death.
     */
    fun readTail(dir: File): List<String> = runCatching {
        val f = file(dir)
        if (!f.exists()) return emptyList()
        val len = f.length()
        if (len == 0L) return emptyList()
        val readLen = minOf(len, TAIL_BYTES.toLong()).toInt()
        val start = (len - readLen).coerceAtLeast(0L)
        val s: String
        if (len <= TAIL_BYTES) {
            s = f.readText()
        } else {
            // Read the LAST TAIL_BYTES (a window that may start mid-line).
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray(readLen)
                raf.readFully(bytes)
                s = String(bytes, Charsets.UTF_8)
            }
        }
        // When the window was trimmed from the front, the first segment is a
        // torn partial line — drop it so we only return COMPLETE tail lines.
        var segments = s.split('\n')
        if (start > 0L && segments.isNotEmpty()) segments = segments.drop(1)
        segments.map { it.trim() }.filter { it.isNotEmpty() }.takeLast(MAX_LINES)
    }.getOrElse { emptyList() }

    /** Short one-line summary of the phase tail for embedding in a warning. */
    fun tailSummary(dir: File): String {
        val lines = readTail(dir)
        if (lines.isEmpty()) return "no_run_log"
        return lines.takeLast(4).joinToString(" | ") { line ->
            runCatching {
                val o = JSONObject(line)
                "at=${o.optLong("at")} ${o.optString("phase")}${if (o.has("detail")) ":" + o.optString("detail") else ""}"
            }.getOrDefault("?")
        }
    }
}