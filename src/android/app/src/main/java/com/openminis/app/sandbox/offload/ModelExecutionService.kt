package com.openminis.app.sandbox.offload

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote executor for [minis-model-use run]'s network call, running in a
 * separate Android process (`:modelservice`).
 *
 * Why: the LLM provider HTTP call allocates native heap (DirectByteBuffer
 * for response bodies, JSON parsing, image decoding) that GC cannot reclaim
 * (logs: Post-recycle GC freed 0MB). Running the call in this short-lived
 * process and killing it afterwards returns ALL its native memory to the OS
 * — the only reliable leak containment.
 *
 * Protocol (file-based, no Binder 1MB limit for media):
 *   Request:  [cacheDir]/model-exec-<uuid>/request.json
 *   Response: [cacheDir]/model-exec-<uuid>/result.json  (atomic via result.tmp)
 *
 * TF-B reliable lifecycle: the worker owns its own lifecycle via
 * [ModelExecutionLifecycle] + [ModelExecutionMailbox]. It writes worker.pid,
 * commits results atomically (result.tmp → flush/fsync → rename result.json),
 * waits for client.ack on non-streaming runs before self-reaping, and only
 * calls Process.killProcess (self-reap) after confirming quiescence. The main
 * process never kills us directly (only a shutdown REQUEST file); a worker
 * with in-flight work (active/queued/unacked/unflushed stream) NEVER dies.
 *
 * The old model — `stopSelf()` as reclamation proof with a 30s idle-kill —
 * is deliberately gone: stopSelf is not process death, and an idle window can
 * sever a later concurrent stream. Here the short-lived process dies
 * immediately when quiescent and the NEXT request starts a fresh process.
 *
 * The service reads the request, reconstructs ProviderInstance + LLMModel,
 * builds the provider, injects passthrough extras, dispatches to
 * generateImage (media output ext) or sendMessage, and writes a
 * result JSON whose media attachments are base64-encoded (the caller —
 * ModelUseOffloadHandler in the main process — writes them to real paths,
 * keeping ALL output-file behaviour in one place).
 */
class ModelExecutionService : Service() {

    companion object {
        private const val TAG = "ModelExecService"
        const val EXTRA_REQUEST_DIR = "request_dir"
        const val RESULT_FILE = "result.json"
        /** Streaming-chunk log line-per-chunk file written by streaming runs. */
        const val STREAM_FILE = "stream.jsonl"
        /** Cancellation signal file: when created, a running stream aborts. */
        const val CANCEL_FILE = "cancel"
        /** Max time a non-streaming worker waits for the client's client.ack. */
        private const val CLIENT_ACK_TIMEOUT_MS = 8_000L
        private const val ACK_POLL_MS = 100L

        /**
         * TF-G: max time a STREAMING worker waits for the client to consume
         * the stream and write client.ack after the terminal barrier. On
         * timeout the worker does NOT fake quiescence (unacked stays > 0 so
         * `finishRequest` never self-reaps on its own); instead it schedules a
         * "controlled drain" reap after [STREAM_DRAIN_GRACE_MS] once the
         * terminal data barrier is confirmed — the stream/result are already
         * durable on disk, so letting the process go loses nothing and avoids
         * a leaked orphan across a crashed client.
         */
        private const val STREAM_CLIENT_ACK_TIMEOUT_MS = 15_000L
        private const val STREAM_DRAIN_GRACE_MS = 30_000L

        /**
         * [worker-first-chunk-guard] Upper bound on how long a streaming worker
         * may wait for the provider's FIRST chunk after HTTP_STARTED. If the
         * upstream takes longer (slow connect, idle relay, silent SSE hang), the
         * worker aborts the run with a first-chunk timeout instead of hanging
         * silently — the client's 5s no-growth grace would otherwise classify the
         * still-alive worker as DEAD via `proc_missing` and enter a retry loop.
         *
         * This deliberately exceeds both WORKER_DIED_GRACE_MS (5s, client-side)
         * and STREAM_CLIENT_ACK_TIMEOUT_MS (15s) so a *legitimately slow* first
         * chunk is not force-killed before the provider can respond, while a
         * genuinely wedged provider (no bytes ever) is surfaced promptly.
         *
         * Cancellation is ALSO checked on this boundary: the old `collect{}`
         * body only saw the cancel file once a chunk arrived, so a first-chunk
         * stall was uncancellable and drove the dead-worker misclassification.
         */
        // [worker-first-chunk-guard] Legacy hard-coded first-chunk timeout.
        // SUPERSEDED by FirstChunkTimeoutPolicy (2026-08-24, route-aware:
        // 30s direct / 45s proxy). Kept as documentation + a stable default
        // reference; production now reads decideGenerationTimeoutSec — the
        // generation stream budget is a uniform 30 min (GENERATION_TIMEOUT_SEC)
        // regardless of route, while the route-aware decideTimeoutSec (30s/45s)
        // is deprecated and has no production callers.
        private const val FIRST_CHUNK_TIMEOUT_MS = 30_000L

        /**
         * TF-F P0-C: provider worker global serialization. `:modelservice` is a
         * single Android process reused across requests; running two provider
         * calls concurrently in it shares one unsafe lifecycle / one native-heap
         * budget (the very thing the short-lived worker exists to contain). Until
         * real evidence warrants more, requests execute ONE AT A TIME: each
         * onStartCommand enqueues, and the first-queued acquires this mutex before
         * dispatching to the provider. This keeps `activeRequests` (in-flight, in
         * the provider) and the lifecycle transition honest.
         */
        private val executionMutex = kotlinx.coroutines.sync.Mutex()
    }

    /** Worker-side registry: number of requests currently being executed. */
    private val activeRequests = AtomicInteger(0)

    /** Worker-side lifecycle state (authoritative; main process only inspects state.json). */
    @Volatile
    private var lifecycleState = ModelExecutionWorkerState.ACTIVE

    /**
     * TF-H: lifecycle lock — guards the composite check of
     * activeRequests / queuedRequests / pending ACKs / lifecycleState and the
     * self-reap decision. The old code computed a quiescence snapshot outside
     * the lock and then called killProcess() on it, so a NEW request that
     * arrived between the snapshot and the kill could be killed by the old
     * finishing thread. Everyone who mutates the counters or decides to die
     * uses this lock.
     */
    private val lifecycleLock = Any()

    /** TF-H: number of requests enqueued waiting on the execution mutex. */
    private val queuedRequests = AtomicInteger(0)

    /**
     * TF-H: per-run ACK tokens, keyed by runId. A streaming worker registers
     * one token when it commits its response and releases it exactly once when
     * the client.ack for that run is observed (or on late ack / controlled
     * drain). The lifecycle only self-reaps when every token is released.
     */
    private val pendingAckTokens = ConcurrentHashMap<String, AtomicBoolean>()

    /** TF-H: request generation, incremented per run; used to invalidate
     *  stale snapshot decisions. */
    private val requestGeneration = AtomicLong(0L)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // [T-provider-key-roulette] The worker builds providers through
        // ProviderFactory, whose create() now rotates multi-key strings. Warm the
        // LRU state from the main process's persisted file so rotation continues
        // across the process boundary instead of restarting from key #1.
        com.openminis.app.data.KeyRoulette.init(cacheDir)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestDir = intent?.getStringExtra(EXTRA_REQUEST_DIR)
            ?: run { stopSelf(startId); return START_NOT_STICKY }

        val dir = File(requestDir)
        val requestFile = File(dir, "request.json")

        if (!requestFile.exists()) {
            Log.w(TAG, "request.json not found")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val runId = runIdOf(dir) ?: ""
        // TF-H: register before enqueue so the completion thread always sees
        // this request when it re-checks under the lifecycle lock.
        synchronized(lifecycleLock) {
            activeRequests.incrementAndGet()
            queuedRequests.incrementAndGet()
            lifecycleState = ModelExecutionWorkerState.ACTIVE
            requestGeneration.incrementAndGet()
        }
        val generation = requestGeneration.get()
        ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.REQUEST_ACCEPTED, runId = runId)

        // Execute on a background thread; the worker then decides whether the
        // process may die (quiescent kill) — never just stopSelf as proof.
        // TF-F P0-C: serialize provider work across requests in this process.
        //
        // TF-I P0-A: publish identity BEFORE the mutex. The client's liveness
        // probe (`probeLiveness`) depends on this run's `worker.pid` ref; if we
        // register it only after acquiring the global `executionMutex`, a worker
        // whose request thread is blocked waiting for the lock (TF-H: up to
        // 11-13s behind the previous request's ack barrier) is INVISIBLE to the
        // client — probe returns UNKNOWN, and the fixed 5s client grace kills
        // the request before the thread ever reaches provider work. Registering
        // pid + thread-start immediately on thread dispatch fixes both the
        // invisibility and the false-DEAD classification (the probe can now see
        // an ALIVE pid matching this run while it waits on the lock).
        Thread {
            // TF-J2: a single per-request heartbeat beats the whole run's life —
            // from thread dispatch (before the mutex, so a queue-waiting worker is
            // still provably alive to the client) through finishRequest writing
            // the terminal marker. Stopped in the outer finally below. Both the
            // main process (read-side) and this worker share the same uid + data
            // dir, so the beat file is a reliable cross-process signal where
            // /proc (hidepid=invisible) is not.
            val heartbeat = LivenessHeartbeat(dir)
            heartbeat.start()
            kotlinx.coroutines.runBlocking {
                // ── TF-I: identity registration, OUTSIDE the mutex ──
                ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.REQUEST_THREAD_START, runId = runId)
                val processName = runCatching { android.app.Application.getProcessName() }
                    .getOrNull() ?: "modelservice"
                val procState = ModelExecutionRunDir.readProcIdentity(android.os.Process.myPid())
                ModelExecutionRunDir.writeWorkerPid(
                    dir,
                    ModelExecutionRunDir.WorkerProcessRef(
                        pid = android.os.Process.myPid(),
                        runId = runId,
                        nonce = java.util.UUID.randomUUID().toString(),
                        processName = processName,
                        startedAtMs = System.currentTimeMillis(),
                        procStartTicks = procState.identity?.procStartTicks ?: 0L,
                        uid = android.os.Process.myUid(),
                    ),
                )
                ModelExecutionRunLog.log(
                    dir,
                    android.os.Process.myPid(),
                    ModelExecutionRunLog.Phase.REQUEST_PARSED,
                    // TF-I P0-D: self-proving identity probe — logs what the
                    // client will read back, so a future "worker died" can be
                    // attributed to real death vs probe mismatch.
                    "pid registered identity=${procState.status} name=${procState.identity?.processName.orEmpty()} " +
                        "uid=${procState.identity?.uid} startTicks=${procState.identity?.procStartTicks}",
                    runId = runId,
                )
                Log.i(
                    TAG,
                    "request thread started pid=${android.os.Process.myPid()} runId=$runId " +
                        "identity=${procState.status} name=${procState.identity?.processName.orEmpty()} " +
                        "uid=${procState.identity?.uid} startTicks=${procState.identity?.procStartTicks}",
                )

                executionMutex.withLock {
                    synchronized(lifecycleLock) { queuedRequests.decrementAndGet() }
                    try {
                        val requestText = requestFile.readText()
                        val isStreaming = JSONObject(requestText).optBoolean("streaming", false)
                        if (isStreaming) {
                            // TF-I: streaming exec writes the result and closes
                            // the stream INSIDE the lock (serialized provider
                            // network), but does NOT wait for the client ack —
                            // that barrier runs outside below.
                            executeStreamingRun(requestText, dir)
                        } else {
                            // TF-J2: non-streaming worker beats liveness too.
                            // start() is idempotent; the beat is stopped in the
                            // OUTER finally (after finishRequestLocked writes the
                            // terminal marker), so the main process — which cannot
                            // read our /proc on a hidepid=invisible device — can
                            // both see we are alive AND know we finished writing
                            // only once the beat goes silent after terminal.
                            heartbeat.start()
                            ModelExecutionRunDir.writeReady(dir)
                            val result = executeRun(requestText, dir)
                            writeResultAtomically(dir, result)
                            Log.i(TAG, "result written ($result.length bytes), pid=${android.os.Process.myPid()}")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "execution failed: ${t.message}", t)
                        try {
                            writeResultAtomically(dir, JSONObject().apply {
                                put("error", "model_use_failed")
                                put("message", t.message ?: "unknown")
                                put("exit_code", 1)
                            }.toString())
                        } catch (_: Throwable) {}
                    }
                }

                // ── TF-I: the ACK barrier + finalize run OUTSIDE the mutex ──
                // The client must have consumed the result/stream before we
                // self-reap; a just-written result is otherwise lost when the
                // worker is reaped and the caller falls back/re-dispatches
                // (duplicating the call). Waiting here — not inside
                // executionMutex — lets a concurrent request acquire the lock
                // and start its provider work immediately. The barrier still
                // protects data (result is durable before we wait), while the
                // serialization only applies to the actual provider network
                // call, never to a sleeping ack-wait. finalize must run after
                // the barrier so its quiescence check sees the released (or
                // held-on-timeout) ACK token.
                val isStreaming = runCatching {
                    JSONObject(requestFile.readText()).optBoolean("streaming", false)
                }.getOrDefault(false)
                try {
                    if (isStreaming) {
                        awaitStreamAckBarrier(dir)
                    } else {
                        waitClientAck(dir, CLIENT_ACK_TIMEOUT_MS)
                    }
                } catch (t: Throwable) {
                    // The ack barrier is best-effort; never let a failure here
                    // skip the locked finalizer (which decrements activeRequests
                    // and decides self-reap) — otherwise the worker leaks.
                    Log.w(TAG, "ack barrier failed (non-fatal): ${t.message}", t)
                } finally {
                    synchronized(lifecycleLock) { lifecycleState = finishRequestLocked(dir, generation) }
                    // TF-J2: stop beating only AFTER the terminal marker is
                    // written (finishRequestLocked writes it), so the client
                    // never sees "stale beat + no terminal" for a worker that is
                    // merely finishing. After this the process self-reaps (or a
                    // future request revives it); the beat file stays as the
                    // durable "worker finished" evidence.
                    heartbeat.stop()
                }
            }
        }.apply { isDaemon = false }.start()

        return START_NOT_STICKY
    }

    /** Extract the stable runId (the UUID embedded in the `run-<uuid>` dir name). */
    private fun runIdOf(dir: File): String? {
        val name = dir.name
        val prefix = "run-"
        return if (name.startsWith(prefix) && name.length > prefix.length) {
            name.substring(prefix.length)
        } else name
    }

    /**
     * Registry bookkeeping after a request finished: decrement the active
     * counter and run the lifecycle machine. Returns the next worker state.
     *
     * The worker only kills its own process when [ModelExecutionLifecycle]
     * decides STOPPING **and** quiescence is confirmed — the main process can
     * never kill us directly. A new request arriving after this check simply
     * revives the process (onStartCommand moves the state back to ACTIVE).
     *
     * TF-F P0-B terminal protocol (defensive):
     *   1. If the run dir is gone (client reclaimed it out from under us the
     *      moment result.json appeared — the P0 race), record a protocol
     *      violation with pid/runId/activeRequests and DO NOT attempt any
     *      further write to the dir. Only self-reap iff activeRequests==0 —
     *      a concurrent request must never be killed because ANOTHER run's
     *      dir vanished.
     *   2. Otherwise: write the final state (defensive, never throws), then
     *      create the terminal marker — the LAST file we write into the dir.
     *      Ordering guarantees: stream flushed → result.json committed →
     *      final state → terminal.json. The client deletes only after
     *      terminal AND our pid is gone ([ModelExecutionRunDir.safeToDelete]).
     */
    /**
     * TF-H: locked completion. The caller already holds [lifecycleLock]. The
     * lock guards the counter mutations and the final kill decision together —
     * the old code made the kill decision on an UNLOCKED quiescence snapshot,
     * so a new request arriving between the snapshot and killProcess() could
     * be killed. Here we re-check everything under the lock right before
     * self-reap.
     */
    private fun finishRequestLocked(dir: File, generation: Long): ModelExecutionWorkerState {
        val runId = runIdOf(dir)
        // This request is done regardless of generation: the active count must
        // always drop. Generation only gates the final-state/terminal/reap
        // decision for the NEWEST request.
        activeRequests.decrementAndGet()
        if (generation != requestGeneration.get()) {
            Log.i(TAG, "finishRequest ignored (stale generation $generation vs ${requestGeneration.get()}), runId=$runId")
            return lifecycleState
        }
        val dirMissing = !dir.isDirectory
        if (dirMissing) {
            Log.w(
                TAG,
                "protocol_violation=run_dir_missing runId=$runId " +
                    "pid=${android.os.Process.myPid()} active=${activeRequests.get()}",
            )
            val quiescence = ModelExecutionQuiescenceInput(
                activeRequests = activeRequests.get(),
                queuedRequests = queuedRequests.get(),
                unackedResponses = pendingAckTokens.size,
                streamFileFlushed = true,
            )
            val next = ModelExecutionLifecycle.transition(
                current = lifecycleState,
                quiescence = quiescence,
                shutdownRequested = false,
            )
            lifecycleState = next
            maybeSelfReapLocked(dir, next, quiescence, runId, generation)
            return next
        }

        val quiescence = ModelExecutionQuiescenceInput(
            activeRequests = activeRequests.get(),
            queuedRequests = queuedRequests.get(),
            unackedResponses = pendingAckTokens.size,
            streamFileFlushed = true,
        )
        val shutdownRequested = shutdownRequested()
        val next = ModelExecutionLifecycle.transition(
            current = lifecycleState,
            quiescence = quiescence,
            shutdownRequested = shutdownRequested,
        )
        lifecycleState = next
        // TF-H: final state + terminal are written under the lock. terminal is
        // now the LAST write of the run — it must not appear while the worker
        // may still be ack-waiting.
        ModelExecutionMailbox.writeState(dir, next, activeRequests.get(), unacked = pendingAckTokens.size)
        if (ModelExecutionLifecycle.shouldKill(next, quiescence)) {
            ModelExecutionRunDir.writeTerminal(dir)
            maybeSelfReapLocked(dir, next, quiescence, runId, generation)
        } else {
            Log.i(
                TAG,
                "request finished (holding): state $next active=${activeRequests.get()} " +
                    "queued=${queuedRequests.get()} pendingAck=${pendingAckTokens.size} " +
                    "shutdownRequested=$shutdownRequested pid=${android.os.Process.myPid()}",
            )
        }
        return next
    }

    /**
     * TF-H: called while holding [lifecycleLock]. Re-checks the CURRENT
     * counters, pending ACK tokens, queued count, terminal marker, and that
     * this is still the newest generation before allowing self-reap. Without
     * this, the lock-free old code killed a worker right after a new request
     * had revived it.
     */
    private fun maybeSelfReapLocked(
        dir: File,
        state: ModelExecutionWorkerState,
        quiescence: ModelExecutionQuiescenceInput,
        runId: String?,
        capturedGeneration: Long,
    ) {
        if (capturedGeneration != requestGeneration.get()) {
            Log.i(TAG, "self-reap skipped (stale generation $capturedGeneration vs ${requestGeneration.get()}), runId=$runId")
            return
        }
        if (ModelExecutionLifecycle.shouldKill(state, quiescence) &&
            activeRequests.get() == 0 &&
            queuedRequests.get() == 0 &&
            pendingAckTokens.isEmpty()
        ) {
            if (!ModelExecutionRunDir.terminalPresent(dir)) {
                Log.w(
                    TAG,
                    "reap aborted: terminal absent (dir=${dir.name}) — leaving worker alive, " +
                        "pid=${android.os.Process.myPid()} active=${activeRequests.get()} " +
                        "queued=${queuedRequests.get()} pendingAck=${pendingAckTokens.size}",
                )
                return
            }
            Log.i(TAG, "terminal written, quiescent self-reap, pid=${android.os.Process.myPid()}")
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.SELF_REAP, "quiescent", runId = runId)
            selfReap()
        }
    }

    /**
     * TF-H: true when the request that computed this completion snapshot is
     * still the newest request in the worker. If a newer request bumped the
     * generation, an older thread must not decide to self-reap.
     */
    private fun generationMatches(capturedGeneration: Long): Boolean =
        capturedGeneration == requestGeneration.get()

    /**
     * True when the main process asked us to drain. On reclaim the main
     * process writes the shutdown marker into the staging root; the worker
     * checks the sibling marker file so we never kill while a new request
     * could arrive (main process controls shutdown by the marker, we control
     * the timing by quiescence).
     */
    private fun shutdownRequested(): Boolean {
        return runCatching {
            ModelExecutionMailbox.shutdownRequested(stagingRoot())
        }.getOrElse { false }
    }

    /** Root staging dir the main process uses for model-exec requests. */
    private fun stagingRoot(): File = File(cacheDir, "model-exec")

    /** Wait until the client wrote client.ack, or the timeout elapsed. */
    private fun waitClientAck(dir: File, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val ack = File(dir, ModelExecutionMailbox.FILE_CLIENT_ACK)
        while (System.currentTimeMillis() < deadline) {
            if (ack.exists()) return
            try { Thread.sleep(ACK_POLL_MS) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "client ack timeout (${timeoutMs}ms) on run ${dir.name} — proceeding anyway")
    }

    /**
     * Atomic result commit: write to result.tmp, flush + fsync, then rename
     * to result.json. The client NEVER observes a partial result file.
     */
    private fun writeResultAtomically(dir: File, content: String) {
        val tmp = File(dir, ModelExecutionMailbox.FILE_RESULT_TMP)
        val target = File(dir, RESULT_FILE)
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            try { fos.fd.sync() } catch (_: Throwable) {}
        }
        if (!tmp.renameTo(target)) {
            // Cross-filesystem rename can't happen here (same dir), but be
            // defensive: copy + delete instead of leaving a broken result.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Kill our own process — only ever called after quiescence confirmation. */
    private fun selfReap() {
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    /**
     * [T-stale-apikey-worker-cache] Stale-secrets abort. The main process
     * rewrote `provider_secrets` after this worker was born, so our cached
     * EncryptedSharedPreferences map may hold the OLD API key. We cannot
     * re-read it in-process (SharedPreferences is a per-process singleton
     * cache; re-opening under another name would mint a fresh Tink keyset
     * that can't decrypt the existing ciphertext). Instead: log the reason,
     * flush the run log, and kill the process WITHOUT writing result.json or
     * an error line. The client then classifies this as a 0-chunk worker
     * death → transient → auto-retry re-dispatches → a NEW worker process
     * loads the fresh key on its first-ever prefs read.
     *
     * Call sites run inside the request thread while holding executionMutex;
     * killing the process here also bypasses the ack barrier — correct,
     * because nothing was served. The run dir is left as an orphan for the
     * reaper (no terminal marker is ever written by a killed worker).
     */
    private fun abortOnStaleKeyCache(runId: String?, dir: File?) {
        val pid = android.os.Process.myPid()
        Log.w(
            TAG,
            "stale_key_cache: provider_secrets.xml rewritten by the main process after this " +
                "worker started — killing worker pid=$pid runId=$runId so the retry spawns a " +
                "fresh process that reads the new key",
        )
        // Log BEFORE kill so classifyWorkerDeath's tail summary shows the cause.
        if (dir != null) {
            ModelExecutionRunLog.log(
                dir, pid,
                ModelExecutionRunLog.Phase.SELF_REAP,
                WorkerKeyFreshness.STALE_KEY_CACHE,
                runId = runId,
            )
        }
        selfReap()
    }

    /**
     * TF-H: non-streaming runs ack like streaming ones once the client wrote
     * client.ack. Kept here so callers can release the per-run token.
     */
    private fun releaseAckToken(runId: String) {
        val removed = pendingAckTokens.remove(runId)
        if (removed != null && removed.get()) {
            removed.set(false)
        }
    }

    private fun executeRun(requestJson: String, dir: File): String {
        val req = JSONObject(requestJson)

        // ── Reconstruct ProviderInstance ──
        val instance = com.openminis.app.data.model.ProviderInstance(
            id = req.optString("instance_id", "remote"),
            label = req.optString("instance_label", "remote"),
            providerType = safeEnum(
                req.optString("provider_type", "openAI"),
                com.openminis.app.data.model.ProviderType.openAI,
            ),
            credentialType = safeEnum(
                req.optString("credential_type", "apiKey"),
                com.openminis.app.data.model.ProviderCredential.apiKey,
            ),
            customBaseURL = req.optString("base_url", "").ifEmpty { null },
            appendV1Suffix = req.optBoolean("append_v1", true),
            customUserAgent = req.optString("user_agent", "").ifEmpty { null },
            useResponsesAPI = req.optBoolean("use_responses_api", false),
            imageEndpointMode = safeEnum(
                req.optString("image_endpoint_mode", "auto"),
                com.openminis.app.data.model.ImageEndpointMode.auto,
            ),
            imageEndpointResolved = req.optString("image_endpoint_resolved", "").let {
                if (it.isNotEmpty()) safeEnumOrNull<com.openminis.app.data.model.ImageEndpointMode>(it) else null
            },
            azureMode = req.optBoolean("azure_mode", false),
            pinned = false,
        )

        // ── Reconstruct LLMModel ──
        val model = com.openminis.app.data.model.LLMModel(
            id = req.getString("model_id"),
            displayName = req.optString("model_display_name", req.getString("model_id")),
            provider = req.optString("model_provider", instance.providerType.name),
            inputModalities = jsonStrList(req.optJSONArray("input_modalities")),
            outputModalities = jsonStrList(req.optJSONArray("output_modalities")),
            contextWindow = req.optInt("context_window", 0).takeIf { it > 0 },
        )

        // ── Reconstruct messages ──
        val messages = jsonObjList(req.optJSONArray("messages")).map { obj ->
            com.openminis.app.data.model.LLMMessage(
                role = try {
                    com.openminis.app.data.model.LLMMessage.Role.valueOf(
                        obj.getString("role").uppercase()
                    )
                } catch (_: Exception) {
                    com.openminis.app.data.model.LLMMessage.Role.USER
                },
                content = obj.optString("content", ""),
                // [fix/audit-s3m1] Non-streaming executeRun now parses
                // contentParts exactly like the streaming path (:870) — the
                // dispatcher serializes contentParts (buildRequestJson :95),
                // and previously tool results / images carried as parts were
                // silently dropped here, so non-streaming turns (title
                // generation / compaction / QuickTest) saw text only.
                contentParts = parseContentParts(obj),
                audioParts = jsonObjList(obj.optJSONArray("audio_parts")).mapNotNull { a ->
                    val b64 = a.optString("data", "")
                    if (b64.isEmpty()) null
                    else com.openminis.app.data.model.LLMMessage.AudioPart(
                        format = a.optString("format", "wav"),
                        base64Data = b64,
                    )
                },
            )
        }
        val systemPrompt = req.optString("system_prompt", "").ifEmpty { null }
        val maxTokens = req.optInt("max_tokens", 4096)
        val temperature = if (req.has("temperature")) {
            req.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
        } else null
        val imageParts = jsonObjList(req.optJSONArray("image_parts")).map { obj ->
            com.openminis.app.data.model.LLMMessage.ImagePart(
                data = obj.optString("data", "").let { b64 ->
                    if (b64.isNotEmpty()) android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    else ByteArray(0)
                },
                mimeType = obj.optString("mime_type", "image/png"),
                linuxPath = obj.optString("linux_path", "").ifEmpty { null },
            )
        }

        // ── API key: read from EncryptedSharedPreferences (same uid) ──
        // [T-stale-apikey-worker-cache] Detect a mid-lifetime rewrite of the
        // secrets file BEFORE reading the key: SharedPreferences is a
        // per-process singleton cache, so a key saved by the main process
        // after THIS process first loaded the prefs is invisible here and we
        // would send the OLD (quota-exhausted) key. Abort → client retries →
        // fresh process reads the new key. See WorkerKeyFreshness.
        WorkerKeyFreshness.captureBaseline(getDataDir())
        if (WorkerKeyFreshness.isStaleNow(getDataDir())) {
            abortOnStaleKeyCache(runIdOf(dir), dir)
            return JSONObject().apply { put("error", WorkerKeyFreshness.STALE_KEY_CACHE) }.toString()
        }
        val apiKey = try {
            com.openminis.app.util.EncryptedPrefsFactory.safeCreate(this, "provider_secrets")
                .getString("apikey_${instance.id}", null) ?: ""
        } catch (_: Exception) { "" }
        if (apiKey.isEmpty()) {
            return JSONObject().apply {
                put("error", "missing_api_key")
                put("message", "No API key configured for ${instance.label}.")
                put("exit_code", 2)
            }.toString()
        }

        // ── Build provider ──
        val provider = com.openminis.app.provider.ProviderFactory.create(
            instance = instance, apiKey = apiKey, model = model, context = this,
        )

        // ── Passthrough extras ──
        val inputJson = req.optString("input_json", "")
        val callWarnings = mutableListOf<String>()
        val appliedExtras = JSONObject()
        if (inputJson.isNotEmpty() && provider is com.openminis.app.provider.openai.OpenAIProvider) {
            val chatExtra = parseChatExtraBody(inputJson, callWarnings)
            if (chatExtra.isNotEmpty()) {
                provider.chatExtraBody = chatExtra
                appliedExtras.put("extra_body_keys", JSONArray(chatExtra.keys.sorted()))
            }
            parseExtraHeaders(inputJson, callWarnings).let { hdrs ->
                if (hdrs.isNotEmpty()) {
                    provider.chatExtraHeaders = hdrs
                    appliedExtras.put("extra_headers_keys", JSONArray(hdrs.keys.sorted()))
                }
            }
            parseCustomEndpointPath(inputJson)?.let { path ->
                provider.absoluteEndpointOverride = path
                appliedExtras.put("custom_endpoint", path)
            }
        }

        // ── Dispatch ──
        val outputExt = req.optString("output_ext", "").ifEmpty { null }
        val isMediaOutput = outputExt in listOf("png", "jpg", "jpeg", "webp", "gif")
        val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider

        // 1) Image generation route (media output ext + OpenAI-compat provider)
        if (openAI != null && isMediaOutput) {
            val genConfig = parseImageGenConfig(inputJson)
            val prompt = genConfig.prompt
                ?: messages.lastOrNull { it.role == com.openminis.app.data.model.LLMMessage.Role.USER }?.content?.takeIf { it.isNotEmpty() }
                ?: ""
            if (prompt.isNotEmpty()) {
                // Apply image passthrough extras from inputJson (mirrors
                // ModelUseOffloadHandler.tryImageGenerationRoute's passthrough
                // injection).
                applyImagePassthrough(openAI, inputJson)
                try {
                    val imgResult = runBlocking {
                        openAI.generateImage(prompt, genConfig.n, genConfig.size, genConfig.quality)
                    }
                    return JSONObject().apply {
                        put("model", model.id)
                        put("text", imgResult.text)
                        imgResult.usage?.let { u ->
                            put("usage", JSONObject().apply {
                                put("input_tokens", u.inputTokens)
                                put("output_tokens", u.outputTokens)
                            })
                        }
                        put("media_files", JSONArray().apply {
                            imgResult.mediaAttachments.forEach { m ->
                                put(JSONObject().apply {
                                    put("type", m.type.value)
                                    put("mime_type", m.mimeType)
                                    put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.DEFAULT))
                                })
                            }
                        })
                        if (appliedExtras.length() > 0) put("applied_extras", appliedExtras)
                        if (callWarnings.isNotEmpty()) put("warnings", JSONArray(callWarnings))
                        put("exit_code", 0)
                    }.toString()
                } catch (t: Throwable) {
                    // Fall through to text path — mirror tryImageGenerationRoute's
                    // route-missing fallback semantics.
                    callWarnings.add("image_route_fallback: ${t.message}")
                }
            }
        }

        // 2) Standard sendMessage path
        val response = try {
            runBlocking {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    imageParts = imageParts,
                    // [fix/audit-s3m1] tools now parsed and forwarded, matching
                    // the streaming path (:1001) — the dispatcher serializes
                    // tools (buildRequestJson :140) but this call site dropped
                    // them, so non-streaming function-calling turns ran with
                    // an empty tool surface.
                    tools = parseToolsJson(req.optJSONArray("tools")),
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendMessage failed: ${e.message}", e)
            return JSONObject().apply {
                put("error", "model_use_failed")
                put("message", e.message ?: "unknown")
                put("exit_code", 1)
            }.toString()
        }

        return JSONObject().apply {
            put("model", model.id)
            put("text", response.text)
            response.stopReason?.let { put("stop_reason", it) }
            response.usage?.let { u ->
                put("usage", JSONObject().apply {
                    put("input_tokens", u.inputTokens)
                    put("output_tokens", u.outputTokens)
                })
            }
            put("media_files", JSONArray().apply {
                response.mediaAttachments.forEach { m ->
                    put(JSONObject().apply {
                        put("type", m.type.value)
                        put("mime_type", m.mimeType)
                        put("data", android.util.Base64.encodeToString(m.data, android.util.Base64.DEFAULT))
                    })
                }
            })
            if (appliedExtras.length() > 0) put("applied_extras", appliedExtras)
            if (callWarnings.isNotEmpty()) put("warnings", JSONArray(callWarnings))
            put("exit_code", 0)
        }.toString()
    }

    /**
     * Streaming execution path (direction A chat-offload primary path).
     *
     * Protocol (see ChatStreamOffloadHandler):
     *  - Reads request from [dir]/request.json with `"streaming":true`.
     *  - Calls provider.streamMessage(...) and appends every chunk to [dir]/[STREAM_FILE] as one JSON line.
     *  - On clean completion: appends [DONE_LINE] + writes result.json.
     *  - On failure: appends a `{"type":"error",...}` line + writes result.json error. Never fabricates Finished.
     *  - On cancel (main process creates [dir]/[CANCEL_FILE]): aborts the stream, writes an error line so
     *    the client's Flow terminates (hardened-3: cancellation propagation).
     */
    private fun executeStreamingRun(requestJson: String, dir: File) {
        val streamFile = File(dir, STREAM_FILE)
        val cancelFile = File(dir, CANCEL_FILE)
        val runId = runIdOf(dir)
        var output: java.io.BufferedWriter? = null
        // NOTE: the worker liveness heartbeat (liveness.beat) is owned by the
        // caller's request-thread (see onStartCommand) and beats the whole run's
        // life — INCLUDING this streaming window. It is not restarted here.
        try {
            // TF-H: only after the stream file is successfully opened do we
            // publish `worker.ready` for this run — ready now means the request
            // thread actually started and the client can expect chunks.
            output = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(streamFile, true)))
            ModelExecutionRunDir.writeReady(dir)
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.REQUEST_ACCEPTED, "stream file opened", runId = runId)
            val appendLine = { line: String ->
                output!!.append(line).append('\n')
                output!!.flush()
            }
            val req = JSONObject(requestJson)

            // ── Reconstruct ProviderInstance (mirrors executeRun) ──
            val instance = com.openminis.app.data.model.ProviderInstance(
                id = req.optString("instance_id", "remote"),
                label = req.optString("instance_label", "remote"),
                providerType = safeEnum(
                    req.optString("provider_type", "openAI"),
                    com.openminis.app.data.model.ProviderType.openAI,
                ),
                credentialType = safeEnum(
                    req.optString("credential_type", "apiKey"),
                    com.openminis.app.data.model.ProviderCredential.apiKey,
                ),
                customBaseURL = req.optString("base_url", "").ifEmpty { null },
                appendV1Suffix = req.optBoolean("append_v1", true),
                customUserAgent = req.optString("user_agent", "").ifEmpty { null },
                useResponsesAPI = req.optBoolean("use_responses_api", false),
                imageEndpointMode = safeEnum(
                    req.optString("image_endpoint_mode", "auto"),
                    com.openminis.app.data.model.ImageEndpointMode.auto,
                ),
                imageEndpointResolved = req.optString("image_endpoint_resolved", "").let {
                    if (it.isNotEmpty()) safeEnumOrNull<com.openminis.app.data.model.ImageEndpointMode>(it) else null
                },
                azureMode = req.optBoolean("azure_mode", false),
                pinned = false,
            )

            // ── Reconstruct LLMModel ──
            val model = com.openminis.app.data.model.LLMModel(
                id = req.getString("model_id"),
                displayName = req.optString("model_display_name", req.getString("model_id")),
                provider = req.optString("model_provider", instance.providerType.name),
                inputModalities = jsonStrList(req.optJSONArray("input_modalities")),
                outputModalities = jsonStrList(req.optJSONArray("output_modalities")),
                contextWindow = req.optInt("context_window", 0).takeIf { it > 0 },
            )

            // ── Reconstruct messages ──
            val messages = jsonObjList(req.optJSONArray("messages")).map { obj ->
                com.openminis.app.data.model.LLMMessage(
                    role = try {
                        com.openminis.app.data.model.LLMMessage.Role.valueOf(
                            obj.getString("role").uppercase()
                        )
                    } catch (_: Exception) {
                        com.openminis.app.data.model.LLMMessage.Role.USER
                    },
                    content = obj.optString("content", ""),
                    contentParts = parseContentParts(obj),
                    audioParts = jsonObjList(obj.optJSONArray("audio_parts")).mapNotNull { a ->
                        val b64 = a.optString("data", "")
                        if (b64.isEmpty()) null
                        else com.openminis.app.data.model.LLMMessage.AudioPart(
                            format = a.optString("format", "wav"),
                            base64Data = b64,
                        )
                    },
                )
            }
            val systemPrompt = req.optString("system_prompt", "").ifEmpty { null }
            val maxTokens = req.optInt("max_tokens", 4096)
            val temperature = if (req.has("temperature")) {
                req.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
            } else null
            val imageParts = jsonObjList(req.optJSONArray("image_parts")).map { obj ->
                com.openminis.app.data.model.LLMMessage.ImagePart(
                    data = obj.optString("data", "").let { b64 ->
                        if (b64.isNotEmpty()) java.util.Base64.getDecoder().decode(b64)
                        else ByteArray(0)
                    },
                    mimeType = obj.optString("mime_type", "image/png"),
                    linuxPath = obj.optString("linux_path", "").ifEmpty { null },
                )
            }
            val tools = parseToolsJson(req.optJSONArray("tools"))
            // [T-thinking-off-omitted-key] thinking_level is deliberately OMITTED
            // from the request JSON when OFF (ModelExecutionDispatcher: "don't
            // serialize defaults"). optString returns "" for a missing key, and
            // the strict safeEnum (T-model-exec-strict-enum) treats "" as an
            // UNKNOWN value and throws — so a thinking-OFF turn never reached
            // HTTP and died as "unknown t0 value: " with 3 retries + failover
            // all failing the same way. The key ABSENT means OFF, exactly what
            // the default parameter expresses. A present-but-unparseable value
            // (newer enum case from a main-process-only build) still throws —
            // that strict contract is unchanged.
            val thinkingLevel = req.optString("thinking_level", "").let {
                if (it.isEmpty()) com.openminis.app.data.model.ThinkingLevel.OFF
                else safeEnum(it, com.openminis.app.data.model.ThinkingLevel.OFF)
            }

            // ── API key: read from EncryptedSharedPreferences (same uid) ──
            // [T-stale-apikey-worker-cache] Same guard as executeRun: if the
            // main process saved a new key after this worker was born, our
            // cached prefs map is stale — abort (kill) so the client's retry
            // spawns a fresh process that reads the new key. No error line /
            // result.json is written: the client must see a 0-chunk worker
            // death, not a terminal failure. See WorkerKeyFreshness.
            WorkerKeyFreshness.captureBaseline(getDataDir())
            if (WorkerKeyFreshness.isStaleNow(getDataDir())) {
                abortOnStaleKeyCache(runId, dir)
                // killProcess never returns on success. The lines below only
                // run in the pathological "kill failed" case: emit a typed
                // error line so the client surfaces a transient failure (and
                // its auto-retry re-dispatches) instead of hanging on a
                // stream that will never produce chunks.
                appendLine(ChatStreamJsonl.errorLine(WorkerKeyFreshness.STALE_KEY_CACHE))
                return
            }
            val apiKey = try {
                com.openminis.app.util.EncryptedPrefsFactory.safeCreate(this, "provider_secrets")
                    .getString("apikey_${instance.id}", null) ?: ""
            } catch (_: Exception) { "" }
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.REQUEST_PARSED, "streaming=true model=${model.id}", runId = runIdOf(dir))
            if (apiKey.isEmpty()) {
                appendLine(ChatStreamJsonl.errorLine("missing_api_key"))
                writeResultAtomically(dir, JSONObject().apply {
                    put("error", "missing_api_key")
                    put("message", "No API key configured for ${instance.label}.")
                    put("exit_code", 2)
                }.toString())
                return
            }

            // ── Provider ──
            @Suppress("UNCHECKED_CAST")
            val provider = com.openminis.app.provider.ProviderFactory.create(instance, apiKey, model, this)
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.PROVIDER_BUILT, "provider=${instance.providerType}", runId = runIdOf(dir))
            kotlinx.coroutines.runBlocking {
                // [worker-first-chunk-guard] Wrap provider streaming in a bounded
                // first-chunk timeout. A wedged/absent upstream must not hang the
                // worker silently past the client five-second death grace (which would
                // classify the still-alive worker as DEAD and enter a retry loop).
                // Cancellation is ALSO checked at this boundary: the old in-collect
                // check alone left a pre-first-chunk stall UNcancellable (loop body
                // never ran).
                // 2026-08-25: reasoning/thinking runs get a 30-minute absolute
                // first-chunk ceiling. A thinking model legitimately stays
                // silent for many minutes before its first visible text (Codex
                // Responses sits 2:50–3:10 dead-air between reasoning and text
                // deltas; users report 10–20 min). The old 30/45s route budget
                // force-killed those at a repeatable wall-clock boundary
                // ("provider produced no first chunk within 45000ms"). The 30-min
                // budget stays finite to bound a truly wedged upstream; worker
                // liveness in the interim is proven by the liveness.beat heartbeat.
                //
                // 2026-08-26 (fix/long-generation-timeouts): the generous ceiling
                // is now the budget for ALL generation streams, thinking or not.
                // A long non-thinking generation (assembling a large deliverable,
                // a big-context late-turn call) legitimately sits silent before
                // its first chunk for the same reason a reasoning model does, and
                // provider silence is not a reliable dead-signal anywhere on the
                // generation path (Codex is silent 2:50–3:10 WITHOUT reasoning and
                // with NO keep-alive bytes). Keying the budget on the thinking
                // feature flag re-exposed non-thinking long writes to 45s kills.
                val firstChunkTimeoutMs =
                    FirstChunkTimeoutPolicy.decideGenerationTimeoutSec(
                        customBaseURL = instance.customBaseURL,
                    ) * 1000L
                val first = withTimeoutOrNull(firstChunkTimeoutMs) {
                    // Pre-check cancel before starting the cold flow: the main
                    // process may have cancelled while we built the provider.
                    if (cancelFile.exists()) throw ModelExecutionCancelledException()
                    // STARTED here (before .collect) so the death classifier can tell
                    // "never reached HTTP" (BEFORE_READY) from "reached, no output".
                    var httpMarked = false
                    ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.HTTP_STARTED, "streaming activate model=${model.id}", runId = runIdOf(dir))
                    try {
                        provider.streamMessage(
                            messages = messages,
                            systemPrompt = systemPrompt,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            // [fix/audit-s2h1] Non-streaming executeRun (:753) passes
                            // imageParts but this streaming call site omitted it —
                            // the dispatcher serializes image_parts (buildRequestJson),
                            // the worker parsed them (:886) and then dropped them,
                            // so streaming turns with user images silently lost the
                            // images and the model only saw the text.
                            imageParts = imageParts,
                            tools = tools,
                            thinkingLevel = thinkingLevel,
                        ).collect { chunk ->
                            if (cancelFile.exists()) throw ModelExecutionCancelledException()
                            if (!httpMarked) {
                                httpMarked = true
                                ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.FIRST_CHUNK, "first_chunk", runId = runIdOf(dir))
                            }
                            appendLine(ChatStreamJsonl.encode(chunk))
                        }
                    } catch (t: Throwable) {
                        // TF-I P0-D: publish the identity probe on failure so a
                        // subsequent "worker died" is attributed to real death (we
                        // threw here) vs probe mismatch.
                        Log.w(TAG, "streamMessage threw: ${t.message}", t)
                        throw t
                    }
                }
                if (first == null) {
                    // No first chunk within the route-aware budget. Surface as a
                    // stream error so the client stops polling instead of
                    // misclassifying a LIVE worker as DEAD after its 5s grace.
                    // (A cancel landing mid-window treats the run the same way.)
                    ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.STREAM_ERROR, "first_chunk_timeout", runId = runIdOf(dir))
                    throw ModelStreamErrorException("provider produced no first chunk within ${firstChunkTimeoutMs}ms (hadChunks=false)", hadChunks = false)
                }
            }
            appendLine(ChatStreamJsonl.DONE_LINE)
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.STREAM_DONE, runId = runIdOf(dir))
            writeResultAtomically(dir, JSONObject().apply {
                put("ok", true)
                put("streaming", true)
            }.toString())
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.RESULT_COMMITTED, "stream_result_ok", runId = runIdOf(dir))
            Log.i(TAG, "stream done, pid=${android.os.Process.myPid()}")
        } catch (t: Throwable) {
            val cancelled = t is ModelExecutionCancelledException
            Log.w(TAG, "stream failed: ${t.message}", t)
            ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.STREAM_ERROR, t.message ?: t.javaClass.simpleName, runId = runId)
            // [TF-B cancel contract] Acknowledge a cancel before the client may
            // delete the dir.
            if (cancelled) {
                runCatching { ModelExecutionMailbox.writeCancelAck(dir) }
            }
            try {
                output?.let { out ->
                    out.append(ChatStreamJsonl.errorLine(t.message ?: "stream_failed")).append('\n')
                    out.flush()
                }
            } catch (_: Throwable) {}
            try {
                writeResultAtomically(dir, JSONObject().apply {
                    if (cancelled) put("cancelled", true)
                    put("error", "stream_failed")
                    put("message", t.message ?: "unknown")
                    put("exit_code", 1)
                }.toString())
            } catch (_: Throwable) {}
        } finally {
            try { output?.close() } catch (_: Throwable) {}
            // TF-I: the ACK barrier is deliberately NOT waited here. It blocks
            // up to STREAM_CLIENT_ACK_TIMEOUT_MS, and if it ran inside the
            // executionMutex (this function is called from the locked region)
            // a following request's thread would starve behind it for the whole
            // 15s — the TF-H root cause next to the false-DEAD classification.
            // the caller (onStartCommand) waits the barrier OUTSIDE the mutex,
            // so 'provider one at a time' still holds while ACK-waiting and a
            // newly-arrived request can acquire the lock immediately.
        }
    }

    /**
     * TF-G: the streaming worker's quiescence barrier.
     *
     * 1. Write the terminal marker (atomic tmp→fsync→rename) — the data
     *    barrier: every run-dir file is durable once this returns, so the
     *    client can fully consume the stream regardless of what happens next.
     * 2. Mark the response un-acked (unacked=1) — `finishRequest` must NOT
     *    self-reap while this request is outstanding.
     * 3. Wait (bounded) for the client's client.ack. The client writes it in
     *    its stream finally AFTER it read the terminal/result, so on the
     *    healthy path the ack arrives within a few polls.
     * 4. On ack: unacked→0, log "client ack seen", return — `finishRequest`
     *    will see a genuinely quiescent worker and self-reap.
     * 5. On timeout: we do NOT fake quiescence (unacked stays 1, so
     *    `finishRequest`'s `shouldKill` is false and it leaves the process
     *    alive); we schedule a "controlled drain" reap after
     *    [STREAM_DRAIN_GRACE_MS] — the terminal data barrier already made the
     *    output durable, so a crashed client cannot lose data and the worker
     *    can safely go (reclaiming its native heap) rather than leak as an
     *    orphan forever.
     */
    private fun awaitStreamAckBarrier(dir: File) {
        val pid = android.os.Process.myPid()
        val runId = runIdOf(dir) ?: ""
        // TF-H: per-run ACK token — the worker is NOT quiescent while this run
        // holds one. Terminal is deliberately NOT written here; it is written
        // only in the locked finalizer after this barrier returns (or after
        // the ack timeout), so final-state and terminal ordering stay correct.
        val token = AtomicBoolean(true)
        pendingAckTokens[runId] = token
        val deadline = System.currentTimeMillis() + STREAM_CLIENT_ACK_TIMEOUT_MS
        var ackSeen = false
        while (System.currentTimeMillis() < deadline) {
            if (ModelExecutionRunDir.clientAckPresent(dir)) { ackSeen = true; break }
            try { Thread.sleep(ACK_POLL_MS) } catch (_: InterruptedException) { break }
        }
        if (ackSeen) {
            releaseAckToken(runId)
            Log.i(TAG, "client ack seen, pid=$pid")
            ModelExecutionRunLog.log(dir, pid, ModelExecutionRunLog.Phase.CLIENT_ACK_SEEN, runId = runId)
        } else {
            // Timeout: DO NOT release the token here. Leave the run holding an
            // unacked ACK so the locked finalizer does not see quiescence; a
            // controlled drain thread re-checks and (only terminal + genuinely
            // idle) reaps after its grace.
            Log.w(
                TAG,
                "client ack timeout (${STREAM_CLIENT_ACK_TIMEOUT_MS}ms) — " +
                    "holding ack token for runId=$runId; controlled drain in $STREAM_DRAIN_GRACE_MS ms, " +
                    "pid=$pid",
            )
            scheduleControlledDrain(dir, runId)
        }
    }

    /**
     * TF-H: fire-and-forget controlled-drain reap for a streaming worker whose
     * client.ack never arrived (client likely crashed / network dropped). The
     * terminal data barrier is already durable, so after a grace period we
     * reap the process (returning native heap) instead of leaking as an
     * orphan. A concurrent/next request revives the process via onStartCommand
     * before this fires; we re-check activeRequests + terminal before killing
     * so we never kill in-flight or pre-terminal work.
     */
    private fun scheduleControlledDrain(dir: File, runId: String?) {
        val deadline = System.currentTimeMillis() + STREAM_DRAIN_GRACE_MS
        val drainedAt = System.currentTimeMillis()
        val genAtSchedule = requestGeneration.get()
        Thread {
            try {
                while (System.currentTimeMillis() < deadline) {
                    // Late client ack during the grace: release the token and
                    // stop — the normal locked finalizer will reap.
                    if (ModelExecutionRunDir.clientAckPresent(dir)) {
                        runId?.let { releaseAckToken(it) }
                        Log.i(TAG, "late client ack — controlled drain cancelled")
                        return@Thread
                    }
                    Thread.sleep(200)
                }
                // Only reap under the lifecycle lock, with terminal present and
                // genuinely idle; do NOT kill a new request or an un-acked run.
                var reaped = false
                synchronized(lifecycleLock) {
                    // A newer request may have arrived; our drain window is stale.
                    if (requestGeneration.get() != genAtSchedule) {
                        Log.w(TAG, "controlled drain stale (gen ${requestGeneration.get()} != $genAtSchedule) — leaving to new request")
                        return@synchronized
                    }
                    runId?.let { releaseAckToken(it) }
                    // If the run dir is gone, nothing more to write; only the
                    // general sweep may reap it.
                    if (!dir.isDirectory) return@synchronized
                    if (!ModelExecutionRunDir.terminalPresent(dir)) {
                        // Data is durable only once result.json exists. The
                        // client may still be waiting on terminal to delete; if
                        // we have result.json, write terminal now (client has
                        // the data) and then reap when idle. Otherwise the
                        // stream was cut before any result — keep worker alive.
                        if (File(dir, ModelExecutionMailbox.FILE_RESULT).exists()) {
                            ModelExecutionRunDir.writeTerminal(dir)
                        } else {
                            Log.w(TAG, "controlled drain aborted: terminal absent and no result — worker kept alive")
                            return@synchronized
                        }
                    }
                    if (activeRequests.get() > 0 || queuedRequests.get() > 0 || pendingAckTokens.isNotEmpty()) {
                        Log.w(TAG, "controlled drain aborted: active/queued/pending work present")
                        return@synchronized
                    }
                    Log.w(TAG, "controlled drain reap (ack unconsumed, waited ${System.currentTimeMillis() - drainedAt}ms), pid=${android.os.Process.myPid()}")
                    ModelExecutionRunLog.log(dir, android.os.Process.myPid(), ModelExecutionRunLog.Phase.SELF_REAP, "controlled_drain", runId = runIdOf(dir))
                    selfReap()
                    reaped = true
                }
                if (reaped) return@Thread
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    /** Thrown when the main process asks us to cancel an in-flight stream. */
    private class ModelExecutionCancelledException : java.lang.RuntimeException("cancelled")

    private fun parseContentParts(o: JSONObject): List<com.openminis.app.data.model.AgentContentPart> {
        val parts = mutableListOf<com.openminis.app.data.model.AgentContentPart>()
        o.optJSONArray("contentParts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                when (getString(p, "kind").lowercase()) {
                    "tooluse" -> {
                        val id = getString(p, "toolUseId").ifBlank { getString(p, "id") }
                        val name = getString(p, "name")
                        val arguments = p.optJSONObject("arguments") ?: JSONObject()
                        parts.add(com.openminis.app.data.model.AgentContentPart.ToolUse(id = id, name = name, input = arguments))
                    }
                    "toolresult" -> {
                        val id = getString(p, "toolUseId").ifBlank { getString(p, "id") }
                        val name = getString(p, "name")
                        val error = p.optBoolean("isError", false)
                        val content = getString(p, "content")
                        val imageData: ByteArray? = p.optString("imageDataB64").takeIf { it.isNotBlank() }
                            ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                        val imageMimeType = getString(p, "imageMimeType").ifEmpty { null }
                        val imageLinuxPath = getString(p, "imageLinuxPath").ifEmpty { null }
                        parts.add(com.openminis.app.data.model.AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = content,
                            isError = error,
                            imageData = imageData,
                            imageMimeType = imageMimeType,
                            imageLinuxPath = imageLinuxPath,
                        ))
                    }
                    "image", "imagedata" -> {
                        val b64 = getString(p, "b64Data")
                        val data = if (b64.isNotBlank()) { runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: ByteArray(0) }
                        else ByteArray(0)
                        val mimeType = getString(p, "mimeType").ifEmpty { "image/png" }
                        val linuxPath = getString(p, "linuxPath").ifEmpty { null }
                        parts.add(com.openminis.app.data.model.AgentContentPart.ImageData(data = data, mimeType = mimeType, linuxPath = linuxPath))
                    }
                    "text" -> parts.add(com.openminis.app.data.model.AgentContentPart.Text(getString(p, "text")))
                }
            }
        }
        return parts
    }

    private fun parseToolsJson(arr: JSONArray?): List<com.openminis.app.data.model.AgentToolDefinition> {
        if (arr == null) return emptyList()
        val out = mutableListOf<com.openminis.app.data.model.AgentToolDefinition>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            val params = linkedMapOf<String, com.openminis.app.data.model.AgentToolParam>()
            t.optJSONObject("parameters")?.let { ps ->
                ps.keys().forEach { k ->
                    val v = ps.getJSONObject(k)
                    params[k] = com.openminis.app.data.model.AgentToolParam(
                        type = getString(v, "type"),
                        description = getString(v, "description"),
                        enumValues = v.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.getString(it) } },
                    )
                }
            }
            val required = jsonStrList(t.optJSONArray("required"))
            val propertyOrdering = t.optJSONArray("property_ordering")?.let { e -> (0 until e.length()).map { e.getString(it) } }
            out.add(com.openminis.app.data.model.AgentToolDefinition(
                name = getString(t, "name"),
                description = getString(t, "description"),
                parameters = params,
                required = required,
                propertyOrdering = propertyOrdering,
            ))
        }
        return out
    }

    private fun getString(o: JSONObject, key: String): String = o.optString(key) ?: ""

    // ── helpers ──

    /**
     * [T-model-exec-strict-enum] Strict enum parse: unknown values throw
     * [UnknownEnumValueException] instead of silently falling back to a
     * default. The outer catch in executeRun / executeStreamingRun converts
     * this into an error result / stream error line, and the main process's
     * dispatch() retry spawns a fresh worker that can read the new value.
     *
     * Why not keep the default fallback: a cross-version worker receiving a
     * newer enum case (e.g. a new ProviderType added in a main-process-only
     * update) would silently route the request through the WRONG protocol
     * (default openAI), producing a 400 that looks like a provider bug and
     * wastes a network round-trip before the retry fixes it. Failing fast
     * makes the retry immediate and the log actionable.
     */
    private inline fun <reified T : Enum<T>> safeEnum(name: String, @Suppress("UNUSED_PARAMETER") default: T): T =
        try {
            java.lang.Enum.valueOf(T::class.java, name)
        } catch (_: IllegalArgumentException) {
            throw UnknownEnumValueException(T::class.java.simpleName, name)
        }

    private inline fun <reified T : Enum<T>> safeEnumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private fun jsonStrList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }

    private fun jsonObjList(arr: JSONArray?): List<JSONObject> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }

    // ── passthrough parsing (mirrors ModelUseOffloadHandler) ──

    private fun parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any> {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return emptyMap()
        val eb = obj.optJSONObject("extra_body") ?: return emptyMap()
        val result = linkedMapOf<String, Any>()
        for (key in eb.keys()) {
            when (val v = eb.get(key)) {
                is Int, is Long, is Double, is Boolean, is String -> result[key] = v
                is JSONArray -> {
                    val list = (0 until v.length()).mapNotNull { e ->
                        e.let { if (it is Int || it is Long || it is Double || it is Boolean || it is String) it else null }
                    }
                    if (list.isNotEmpty()) result[key] = list
                }
                else -> warnings.add("extra_body.$key: unsupported type, dropped")
            }
        }
        return result
    }

    private fun parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return emptyMap()
        val eh = obj.optJSONObject("extra_headers") ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        for (key in eh.keys()) result[key] = eh.optString(key, "")
        return result
    }

    private fun parseCustomEndpointPath(inputJson: String): String? {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return null
        return obj.optString("endpoint", "").ifEmpty { null }
    }

    private fun parseImageGenConfig(inputJson: String): ImageGenConfig {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return ImageGenConfig()
        var prompt: String? = null
        var n = 1
        var size: String? = null
        var quality: String? = null
        if (obj.has("prompt")) prompt = obj.optString("prompt", null)
        if (obj.has("n")) n = obj.optInt("n", 1)
        if (obj.has("size")) size = obj.optString("size", null)
        if (obj.has("quality")) quality = obj.optString("quality", null)
        val gc = obj.optJSONObject("generation_config")
        if (gc != null) {
            if (prompt == null && gc.has("prompt")) prompt = gc.optString("prompt", null)
            if (gc.has("number_of_images")) n = gc.optInt("number_of_images", 1)
            if (gc.has("image_size")) size = gc.optString("image_size", null)
        }
        return ImageGenConfig(prompt, n, size, quality)
    }

    private data class ImageGenConfig(
        val prompt: String? = null,
        val n: Int = 1,
        val size: String? = null,
        val quality: String? = null,
    )

    /**
     * Parse image passthrough extras from the input JSON and apply them to
     * the [OpenAIProvider]. Mirrors
     * [ModelUseOffloadHandler.tryImageGenerationRoute] passthrough injection.
     */
    private fun applyImagePassthrough(
        openAI: com.openminis.app.provider.openai.OpenAIProvider,
        inputJson: String,
    ) {
        val obj = try { val t = inputJson.trim(); if (t.startsWith("{")) JSONObject(t) else null }
        catch (_: Exception) { null } ?: return
        // [fix/audit-s3m2] This reader used to expect an `image_passthrough`
        // envelope that NO caller ever wrote (dead dialect — grep found this
        // line as the only reference to the key in the whole repo). The
        // in-process ModelUseOffloadHandler.parseImagePassthrough uses a
        // different dialect: implicit top-level keys (not in the reserved
        // set) + explicit extra_body / extra_headers / endpoint_path. Parse
        // the SAME dialect here so passthrough extras survive the worker
        // path too (e.g. Seedream image-to-image `image` body field),
        // instead of being silently dropped.
        val body = LinkedHashMap<String, Any?>()
        for (key in obj.keys()) {
            if (key in IMAGE_PASSTHROUGH_RESERVED_KEYS) continue
            body[key] = obj.opt(key)
        }
        obj.optJSONObject("extra_body")?.let { eb ->
            for (key in eb.keys()) body[key] = eb.opt(key)
        }
        if (body.isNotEmpty()) openAI.imageExtraBody = body
        val headers = LinkedHashMap<String, String>()
        obj.optJSONObject("extra_headers")?.let { eh ->
            for (key in eh.keys()) {
                val v = eh.opt(key)
                if (v is String) headers[key] = v
            }
        }
        if (headers.isNotEmpty()) openAI.imageExtraHeaders = headers
        obj.optString("endpoint_path", "").trim().takeIf { it.isNotEmpty() }?.let {
            openAI.imagePathOverride = it
        }
    }

    /**
     * Keys consumed by the image-gen schema itself (parseImageGenConfig) or
     * the chat schema — mirrors ModelUseOffloadHandler.imageReservedKeys.
     * Any OTHER top-level key in inputJson folds into the passthrough body.
     */
    private val IMAGE_PASSTHROUGH_RESERVED_KEYS: Set<String> = setOf(
        "messages", "model", "chat_model", "prompt", "n", "number_of_images",
        "size", "image_size", "quality", "generation_config", "endpoint",
        "image_endpoint", "endpoint_path", "extra_body", "extra_headers",
        "stream", "temperature", "max_tokens",
    )
}

/**
 * TF-J2: worker liveness heartbeat for a single run dir.
 *
 * WHY: the main process verifies worker liveness via [ModelExecutionRunDir]
 * probes that read `/proc/<pid>`. On this device (and every modern Android
 * where /proc is mounted `hidepid=invisible`, gid=3009 = readproc) an app
 * process can ONLY see its own pid — another app process's `/proc/<pid>` is
 * invisible. So the classic probe always reads "missing" for a perfectly
 * alive worker, producing the TF-A…TF-J spurious `worker died before any
 * output` retry loops.
 *
 * WHAT: while a stream is in flight the worker rewrites
 * `run-<uuid>/liveness.beat` on a short interval. Both processes share the
 * same uid + app data dir, so a beat file is a reliable cross-process signal:
 * a fresh beat ⇒ worker provably alive; a stale beat with no terminal ⇒
 * worker stopped/ crashed. This class owns the beat writer on the worker side.
 */
private class LivenessHeartbeat(
    private val dir: File,
    private val intervalMs: Long = ModelExecutionRunDir.LIVENESS_STALE_MS / 2,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Best-effort guaranteed start; a failed beat simply warns. */
    fun start() {
        if (running) return
        running = true
        val t = Thread {
            // First beat immediately so liveness is provable the moment the
            // worker starts the stream (before any chunk).
            touchBeat(dir)
            while (running) {
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
                if (running) touchBeat(dir)
            }
        }
        t.isDaemon = true
        t.name = "model-liveness-heartbeat"
        thread = t
        t.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun touchBeat(d: File) {
        try { ModelExecutionRunDir.touchLivenessBeat(d) } catch (_: Throwable) {}
    }
}

/**
 * [T-model-exec-strict-enum] Thrown when the worker receives an enum value
 * it doesn't recognize — typically because the main process serialized a
 * NEWER enum case that this (older) worker build doesn't know about. The
 * outer catch in executeRun / executeStreamingRun writes an error result /
 * stream error line; the main process's dispatch() retry then spawns a
 * fresh worker that reads the correct enum on its first-ever prefs load.
 *
 * Replaces the old silent-fallback-to-default behavior which could route
 * an Anthropic request through the OpenAI protocol (or vice versa) and
 * produce a confusing 400 / parse failure that looked like a provider bug.
 */
internal class UnknownEnumValueException(
    val enumClass: String,
    val unknownValue: String,
) : RuntimeException("unknown $enumClass value: $unknownValue")