package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Direction A: offload chat streaming to the [ModelExecutionService] process.
 *
 * Writes a streaming request.json, starts [ModelExecutionService], then incrementally
 * polls the service's stream.jsonl (append-only JSON Lines) and re-emits each decoded
 * [LLMStreamChunk] downstream as it arrives.
 *
 * Hardening notes (Tier 1):
 *  - failure propagation: an `error` line in the stream throws so the caller falls
 *    back to in-process (never a fabricated Finished).
 *  - cancellation propagation: cancelling the flow writes the cancel marker file so the
 *    service aborts its stream collection promptly.
 *  - incremental reads: we keep a byte offset and re-open/seek, never re-read whole file.
 */
object ChatStreamOffloadHandler {
    private const val TAG = "ChatStreamOffload"
    private const val STAGING_ROOT = "model-exec"
    private const val POLL_INTERVAL_MS = 160L
/** First chunk arrived, then the stream went completely silent (no EOF,
     * no keep-alive, no new bytes) for this long — the classic "provider sent
     * the first chunk then the connection half-opened" hang. After the first
     * chunk, the worker-death liveness beat only detects worker PROCESS crash;
     * it does NOT cover a worker that is alive but stuck waiting on a silent
     * upstream socket. This dedicated line-idle watchdog bounds that case so a
     * half-open post-first-chunk stream doesn't hang the UI for the whole
     * 30-minute generation backstop. */
    private const val STREAM_IDLE_STALL_MS = 5 * 60 * 1000L
    private const val CANCEL_ACK_TIMEOUT_MS = 5_000L
    private const val CANCEL_ACK_POLL_MS = 100L
    /**
     * How long the stream file may stay frozen before we RE-EXAMINE worker
     * liveness. This is NOT a "no output → dead" verdict: the worker is only
     * classified [ModelExecutionRunDir.WorkerLiveness.DEAD] when BOTH this
     * grace has elapsed AND the three-state probe confirms the pid referenced
     * by THIS run dir is gone. A slow first chunk (> this grace, worker alive)
     * is NOT a death — the flow keeps polling.
     *
     * TF-G: raised from 2s to 5s. A streaming worker now holds an unacked
     * response + writes its terminal barrier before self-reaping; a 5s window
     * keeps us from classifying a perfectly-healthy-but-just-finished worker
     * as dead on the hair between DONE and pid-exit, while still surfacing a
     * genuinely crashed worker promptly.
     */
    private const val WORKER_DIED_GRACE_MS = 5_000L
    /**
     * TF-I P0-B: how long an identity-mismatch must persist before the client
     * treats it as a confirmed death. This MUST exceed the executionMutex
     * serialization upper bound (the worker's streaming ACK barrier is up to
     * STREAM_CLIENT_ACK_TIMEOUT_MS = 15s) so a request thread starved behind
     * the mutex (TF-H gap 11-13s) is never killed as a false positive — the
     * flow simply re-probes until the worker either reaches HTTP (stream
     * grows, so this branch stops) or the pid genuinely goes MISSING.
     */
    private const val MISMATCH_GRACE_MS = 20_000L
    /** Bounded wait after terminal for the worker process to disappear before deleting. */
    private const val WORKER_EXIT_WAIT_MS = 6_000L
    private const val WORKER_EXIT_POLL_MS = 60L

    /**
     * [direction-A / B2] Global count of in-flight streaming runs (this process).
     * Incremented at the start of [stream], decremented in its finally. Read by
     * [com.openminis.app.sandbox.ExecutionCoordinator.maybeReclaimModelService]
     * to skip stopService(:modelservice) while a stream is active — otherwise the
     * app process kill would sever the stream mid-answer (stream.jsonl left without
     * DONE/error, leaving the UI silently stalled until the poll timeout).
     * @Volatile because it is incremented/decremented from stream coroutines but
     * read from a different execution context (reclaim path).
     */
    @Volatile
    var activeStreams = 0
        private set

    /**
     * Execute a streaming request and expose decoded chunks as a [Flow].
     * The flow completes when the service writes the done marker; throws when it writes an error line.
     * Cancelling the flow writes the cancel marker so the service aborts promptly.
     *
     * @param thinkingEnabled historically widened the total-stream ceiling for
     *   reasoning runs. 2026-08-26 (fix/long-generation-timeouts): the ceiling
     *   is now the generous generation backstop for ALL streams, because a long
     *   non-thinking generation (a large deliverable, a big-context late turn)
     *   legitimately streams for many minutes and must not be cut at the old
     *   6-minute wall. The parameter is retained for caller compatibility but
     *   no longer changes the ceiling.
     */
    fun stream(
        context: Context,
        requestJson: String,
        thinkingEnabled: Boolean = false,
    ): Flow<LLMStreamChunk> = flow {
        // [fix/audit-s2h4] activeStreams was incremented BEFORE the staging try.
        // If root.mkdirs()/d.mkdir() threw ("stream staging failed"), the flow
        // failed without ever reaching the terminal finally's activeStreams--,
        // so the counter leaked +1 forever. maybeReclaimModelService skips the
        // :modelservice shutdown while activeStreams > 0 — a few staging
        // failures under cache-dir pressure permanently disabled model-service
        // reclamation. Track incrementing with its own try/finally so EVERY
        // exit path pairs ++ with --.
        activeStreams++
        try {
        val dir = try {
            val root = File(context.cacheDir, STAGING_ROOT)
            root.mkdirs()
            val d = File(root, "run-${UUID.randomUUID()}")
            if (!d.mkdir()) throw IllegalStateException("cannot create run dir")
            d
        } catch (e: Exception) {
            throw RuntimeException("stream staging failed", e)
        }

        val cancelFile = File(dir, ModelExecutionService.CANCEL_FILE)
        // [TF-F] Declared OUTSIDE the try so the finally block can read/write
        // them (a `finally` cannot reference locals declared inside the try's
        // nested scope). These drive the terminal-and-exit delete decision.
        var lastRead = 0L
        var emittedChunks = false
        var terminalSeen = false
        val runId = ModelExecutionDispatcher.runIdOf(dir)
        var lastGrowAtMs = System.currentTimeMillis()
        // [generation-total-timeout] The client-side total-stream ceiling is the
        // generous generation backstop for EVERY stream (2026-08-26,
        // fix/long-generation-timeouts): a long NON-thinking generation — a
        // large deliverable, a big-context late turn — legitimately streams for
        // many minutes and must not be cut at the old 6-minute wall. The former
        // split (6-min for non-thinking, 30-min for thinking) exposed every
        // non-thinking long generation to a hard wall. A genuinely wedged
        // upstream is still surfaced promptly by the provider's TTFB / first-data
        // watchdogs and the worker-liveness beat, so this ceiling is a final
        // backstop that bounds the worst case, not the primary liveness signal.
        val streamTimeoutMs =
            com.openminis.app.sandbox.offload.FirstChunkTimeoutPolicy.GENERATION_TIMEOUT_SEC * 1000L
        // (TF-J2: death is now driven by the worker liveness beat file; see the
        // poll-loop decision. No /proc identity-mismatch window is needed.)
        try {
            val requestFile = File(dir, "request.json")
            val streamFile = File(dir, ModelExecutionService.STREAM_FILE)
            // [T-chat-stream-line-reader-unify] Reuse the bounded reader instead of
            // the ad-hoc readAppendedChunks below. Single source of truth for
            // max-read / max-line / oversized-line handling; the old implementation
            // had neither and risked pulling a multi-MB single line into memory or
            // splitting a UTF-8 char across poll windows.
            val lineReader = BoundedLineReader()
            try { streamFile.createNewFile() } catch (e: Exception) {
                throw RuntimeException("cannot create stream file", e)
            }

            try {
                requestFile.writeText(requestJson)
            } catch (e: Exception) {
                throw RuntimeException("write stream request failed", e)
            }

            try {
                val intent = Intent(context, ModelExecutionService::class.java).apply {
                    putExtra(ModelExecutionService.EXTRA_REQUEST_DIR, dir.absolutePath)
                }
                context.startService(intent)
            } catch (e: Exception) {
                throw RuntimeException("start model service failed", e)
            }

            val timedOut = withTimeoutOrNull(streamTimeoutMs) {
                while (true) {
                    ensureActive()
                    val newLen = streamFile.length()
                    if (newLen > lastRead) {
                        when (val result = lineReader.readAppended(streamFile, lastRead, newLen)) {
                            is BoundedLineReader.ReadResult.Lines -> {
                                lastGrowAtMs = System.currentTimeMillis()
                                lastRead = result.newOffset
                                for (line in result.lines) {
                                    if (line.isBlank()) continue
                                    if (ChatStreamJsonl.isDone(line)) {
                                        terminalSeen = true
                                        return@withTimeoutOrNull true
                                    }
                                    if (ChatStreamJsonl.isError(line)) {
                                        // [TF-F] an error LINE is a stream-terminal
                                        // event (the worker will also write result +
                                        // terminal marker in finishRequest). Mark it so
                                        // the finally never blind-deletes a live worker.
                                        terminalSeen = true
                                        throw ModelStreamErrorException(
                                            ChatStreamJsonl.errorMessage(line),
                                            hadChunks = emittedChunks,
                                            // [fix/stream-error-silent-recovery] carry the
                                            // worker's machine-readable kind through so
                                            // the engine can auto-retry transient
                                            // failures; null for legacy untyped lines.
                                            kind = ChatStreamJsonl.errorKind(line),
                                        )
                                    }
                                    ChatStreamJsonl.decode(line)?.let { chunk ->
                                        // [feat/provider-exec-concurrency] A
                                        // QueueStatus frame is NOT model
                                        // output: it must not flip
                                        // emittedChunks (which gates the
                                        // 0-chunk auto-retry semantics and
                                        // the idle-stall watchdog's
                                        // hadChunks classification).
                                        if (chunk !is LLMStreamChunk.QueueStatus) {
                                            emittedChunks = true
                                        }
                                        emit(it)
                                    }
                                }
                            }
                            is BoundedLineReader.ReadResult.OversizedLine -> {
                                // A single JSONL line exceeded maxLineBytes (256 KiB). Skip it
                                // and advance past it so we don't re-read the same giant line
                                // forever. Log so diagnostics can spot a producer bug.
                                lastGrowAtMs = System.currentTimeMillis()
                                lastRead = result.lineStartOffset + result.lineByteLength
                                Log.w(TAG, "oversized stream line skipped: ${result.lineByteLength} bytes at offset ${result.lineStartOffset}")
                            }
                            BoundedLineReader.ReadResult.Partial -> {
                                // No complete line yet — keep polling; lastRead unchanged.
                            }
                        }
                    }
                    // [P1-1 stream-idle-stall] First chunk arrived, then the
                    // stream went completely silent. The worker-death check
                    // below only fires on a stale liveness BEAT (worker process
                    // crash); it does NOT cover a worker that is alive but
                    // stuck waiting on a silent upstream socket. Bound that
                    // case with a dedicated line-idle watchdog.
                    // hadChunks=true → fatal path, no auto-resend.
                    if (emittedChunks &&
                        !terminalSeen &&
                        newLen == lastRead &&
                        System.currentTimeMillis() - lastGrowAtMs > STREAM_IDLE_STALL_MS
                    ) {
                        throw ModelStreamErrorException(
                            "stream stalled after first chunk: no data for ${STREAM_IDLE_STALL_MS}ms",
                            hadChunks = true,
                        )
                    }
                    // [TF-F crash recovery] Detect worker death THREE-STATE:
                    // only a CONFIRMED dead pid (probe returns DEAD for THIS
                    // run's pid ref) after a no-growth grace is worker_died.
                    // UNKNOWN (no valid pid ref / ambiguous / recycle-race) is
                    // never classified as death — we keep polling. A slow first
                    // chunk (>WORKER_DIED_GRACE_MS, worker ALIVE) is NOT death.
                    // A terminal result/marker present means the worker finished
                    // NORMALLY (it self-reaps right after) — NOT a crash.
                    //
                    // TF-J2: death probe is driven by the worker liveness BEAT
                    // file (shared same-uid filesystem), NOT by /proc.
                    //
                    // The classic probes here — probeLiveness / probeDeathEvidence —
                    // read `/proc/<worker-pid>`. On this device /proc is mounted
                    // `hidepid=invisible` (gid=3009); an app process can ONLY see
                    // its OWN pid, so the main process ALWAYS reads "proc_missing"
                    // for a perfectly alive worker → TF-A…TF-J spurious
                    // "worker died before any output" retry loops.
                    //
                    // New decision: the streaming worker rewrites
                    // `run-<uuid>/liveness.beat` every ~2s for the whole stream.
                    //  - beat fresh  (younger than LIVENESS_STALE_MS=4s) ⇒ worker
                    //    provably alive → keep polling, even if no chunk yet.
                    //  - beat present but STALE (and no terminal/result) ⇒ worker
                    //    was alive then stopped beating with no output ⇒ real death.
                    //  - no beat yet ⇒ worker is still starting up (service spin-up,
                    //    provider build, first chunk wait) → keep polling; bounded
                    //    by streamTimeoutMs (= GENERATION_TIMEOUT_SEC, the 30-min
                    //    generation backstop). Not death.
                    // This matches the old semantics (only a worker that was provably
                    // alive and THEN stopped is dead) without touching /proc.
                    if (newLen == lastRead &&
                        System.currentTimeMillis() - lastGrowAtMs > WORKER_DIED_GRACE_MS
                    ) {
                        val terminalOrResult = ModelExecutionRunDir.terminalPresent(dir) ||
                            File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
                        val beatPresent = File(dir, ModelExecutionRunDir.FILE_LIVENESS_BEAT).isFile
                        val beatExistingButStale = beatPresent &&
                            ModelExecutionRunDir.beatStale(dir)
                        // A beat that went stale with no terminal data is decisive:
                        // the worker was alive (it beat) and has now stopped without
                        // ever producing output.
                        var decisive = false
                        if (beatExistingButStale && !terminalOrResult) decisive = true
                        // Reset any trailing suspicion only when we see a FRESH beat
                        // (worker manifestly alive) or no beat at all (still starting).
                        // (mis-…grace window is no longer meaningful without /proc.)
                        if (beatPresent && !beatExistingButStale) {
                            // fresh beat ⇒ alive; nothing to decide.
                            decisive = false
                        }
                        if (decisive &&
                            !ModelExecutionRunDir.terminalPresent(dir) &&
                            !File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
                        ) {
                            // TF-G P0-3: classify WHY the worker appears dead so
                            // the caller can weigh retry (0-chunk) vs fatal, and
                            // diagnostics get the run-log tail as evidence.
                            val reason = classifyWorkerDeath(dir, emittedChunks)
                            val phase = ModelExecutionRunLog.tailSummary(dir)
                            Log.w(
                                TAG,
                                "worker died (${reason.name}) runId=$runId emittedChunks=$emittedChunks phase=$phase beat_stale_without_terminal dir=${dir.name}",
                            )
                            throw ModelWorkerDiedException(
                                hadChunks = emittedChunks,
                                reason = reason,
                                runId = runId,
                                phaseSummary = phase,
                            )
                        }
                    }
                    delay(POLL_INTERVAL_MS)
                }
            } == null
            if (timedOut) {
                throw RuntimeException("stream timed out after ${streamTimeoutMs}ms")
            }
        } finally {
            // [B2] A stream is no longer in flight regardless of how we exited
            // (timeout / external cancel / normal close).
            // [fix/audit-s2h4] the decrement moved to the OUTER finally (paired
            // with the increment's own try/finally) so staging failures also
            // decrement. Keeping it here too would double-decrement.
            // [TF-F] Unified terminal-and-exit protocol: never delete a run dir
            // while the worker might still be writing to it. Only when
            //   - a terminal marker exists (worker's LAST write), AND
            //   - the worker's pid is confirmed gone (or there is no valid ref)
            // do we delete. `result.json`/`cancel.ack` alone are NOT enough —
            // the worker may still be inside finishRequest() writing state.json
            // (the exact P0 race). Timeout → leave the dir as an orphan and
            // let the orphan reaper reclaim later.
            try {
                // Signal a cancel ONLY if we have not yet seen a terminal state
                // (normal DONE / error must NOT get a cancel shoved at it).
                if (!terminalSeen) {
                    ModelExecutionMailbox.writeCancel(cancelFile.parentFile!!)
                }
                awaitTerminalAndWorkerExitThenDelete(dir, runId)
            } catch (_: Exception) {}
        }
        } finally {
            // [fix/audit-s2h4] Pairs the outer activeStreams++ (moved inside a
            // dedicated try/finally in 2026-09-02): staging failures previously
            // skipped this decrement entirely.
            activeStreams = (activeStreams - 1).coerceAtLeast(0)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * [TF-F] Wait for (a) this run's terminal marker, then (b) the worker's
     * process to disappear, and only then delete the run dir. Any timeout or
     * ambiguity keeps the dir as an orphan (never delete under a live worker).
     */
    private suspend fun awaitTerminalAndWorkerExitThenDelete(dir: File, runId: String?) {
        // (a) terminal marker. A DONE/error line usually precedes it by a
        // hair (finishRequest writes state + terminal right after the result),
        // so give it a bounded window.
        val termDeadline = System.currentTimeMillis() + CANCEL_ACK_TIMEOUT_MS
        var terminalSeen = ModelExecutionRunDir.terminalPresent(dir)
        while (!terminalSeen && System.currentTimeMillis() < termDeadline) {
            terminalSeen = ModelExecutionRunDir.terminalPresent(dir)
                || File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
                || File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists()
            if (!terminalSeen) kotlinx.coroutines.delay(CANCEL_ACK_POLL_MS)
        }
        // If the terminal marker itself never appeared but we DID see a result /
        // cancel ack, the worker is done with run-dir writes and will write
        // terminal (or already self-reaped). Treat result/ack as the terminal
        // barrier for deletion — the actual deletion still requires the pid gone.
        val writeBarrierSeen = terminalSeen ||
            File(dir, ModelExecutionMailbox.FILE_RESULT).exists() ||
            File(dir, ModelExecutionMailbox.FILE_CANCEL_ACK).exists()

        // TF-G: client-ACK — the other half of the self-reap barrier. Once the
        // client has seen the terminal barrier (it has read every emitted chunk
        // by this point, since the loop consumed stream.jsonl as it grew), it
        // MUST tell the worker "consumed" so the worker can stop holding an
        // unacked response and self-reap promptly. Without this the streaming
        // worker holds unacked>0 and (with the TF-G barrier) refuses to reap
        // until its ack timeout + controlled drain — pinning the process for up
        // to 45s and leaving the run dir as an orphan. The Dispatcher (non-
        // streaming) already acks; streaming now does too.
        if (writeBarrierSeen) {
            try { ModelExecutionMailbox.writeClientAck(dir) } catch (_: Exception) {}
        }

        // (b) worker process gone. Reuse the dispatcher's bounded wait logic.
        if (writeBarrierSeen && awaitWorkerExit(dir, runId, WORKER_EXIT_WAIT_MS)) {
            try { dir.deleteRecursively() } catch (_: Exception) {}
        } else {
            Log.w(
                TAG,
                "stream run dir kept as orphan (terminal=$writeBarrierSeen dir=${dir.name})",
            )
        }
    }

    /**
     * [TF-J2] Bounded wait for this run's worker to stop beating (its liveness
     * heartbeat file `liveness.beat` going stale). Returns true as soon as the
     * beat is confirmed absent-or-stale — the worker has stopped, so the run
     * dir is safe to delete. On a healthy finish the worker stops beating and
     * writes terminal; on a crash the beat simply goes silent.
     *
     * NOTE: the old /proc-based probeLiveness is unreliable here because /proc
     * is hidepid=invisible on this device (an app process can only see itself),
     * so the main process ALWAYS saw the worker pid as "missing" — which both
     * false-killed live workers (above) and would have kept this wait from ever
     * confirming a real exit. The heartbeat file, on the shared same-uid
     * filesystem, is the source of truth instead.
     */
    private suspend fun awaitWorkerExit(dir: File, runId: String?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val beatFile = File(dir, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
            val gone = !beatFile.isFile ||
                ModelExecutionRunDir.beatStale(dir) ||
                ModelExecutionRunDir.clientAckPresent(dir)
            // A terminal marker is the worker's LAST durable write; once present
            // the worker is done writing and will self-reap. clientAckPresent is
            // our own handshake confirming we consumed the output.
            val done = ModelExecutionRunDir.terminalPresent(dir) ||
                File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
            if (done || gone) return true
            delay(WORKER_EXIT_POLL_MS)
        }
        val beatFileFinal = File(dir, ModelExecutionRunDir.FILE_LIVENESS_BEAT)
        return !beatFileFinal.isFile ||
            ModelExecutionRunDir.beatStale(dir) ||
            ModelExecutionRunDir.clientAckPresent(dir) ||
            ModelExecutionRunDir.terminalPresent(dir) ||
            File(dir, ModelExecutionMailbox.FILE_RESULT).exists()
    }

    /**
     * TF-H: stage-aware classification of WHY a worker appears dead, from
     * THIS run dir's evidence + the run-log tail. The stream is only known to
     * have reached "ready" once the request thread opened stream.jsonl; a
     * worker that died with no run-log at all is more likely "never reached
     * the request thread" than "ready-then-died".
     */
    private fun classifyWorkerDeath(dir: File, emittedChunks: Boolean): WorkerDeathReason {
        val runId = ModelExecutionDispatcher.runIdOf(dir)
        val hasPidRef = ModelExecutionRunDir.readWorkerRef(dir, runId) != null
        val ready = File(dir, ModelExecutionRunDir.FILE_READY).exists()
        val tail = ModelExecutionRunLog.readTail(dir)
        val reachedRequestThread = tail.any { it.contains(ModelExecutionRunLog.Phase.REQUEST_THREAD_START) }
            || tail.any { it.contains(ModelExecutionRunLog.Phase.REQUEST_ACCEPTED) }
        val reachedHttp = tail.any { it.contains(ModelExecutionRunLog.Phase.HTTP_STARTED) }
            || tail.any { it.contains(ModelExecutionRunLog.Phase.FIRST_CHUNK) }
        return ModelExecutionRunDir.classifyWorkerDeathStaged(
            hasPidRef = hasPidRef,
            ready = ready,
            hadChunks = emittedChunks,
            reachedRequestThread = reachedRequestThread,
            reachedHttp = reachedHttp,
        )
    }

}