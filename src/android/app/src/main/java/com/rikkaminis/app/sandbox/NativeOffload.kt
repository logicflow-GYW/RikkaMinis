package com.rikkaminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.rikkaminis.app.BuildConfig
import com.rikkaminis.app.diagnostics.MemorySpikeRecorder
import com.rikkaminis.app.sandbox.offload.OffloadQueuePolicy
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Hard cap on the total encoded size of a single incoming offload request
 * frame (header + argv + env + cwd) read from the unix socket. The per-field
 * limits in [readLEString] bound individual strings but not the aggregate:
 * a hostile or buggy guest could stream thousands of near-1 MiB entries
 * and OOM the host while the arg/env maps grow. Mirrors the shell-output
 * truncation cap precedent (PersistentShell writes its cap from the tunable
 * `shellOutputKb` budget) — real offload requests are a few KB, so 1 MiB
 * is generous headroom.
 */
internal const val MAX_REQUEST_BYTES = 1024 * 1024  // 1 MiB

/**
 * Monotonic byte counter for decoding one offload request frame. Aborts
 * with [IllegalStateException] as soon as the cumulative frame size
 * exceeds [maxBytes] — before the offending string is materialized —
 * so oversized requests fail fast instead of ballooning host memory.
 */
internal class OffloadRequestBudget(private val maxBytes: Int) {
    /** Cumulative bytes charged for the current frame. */
    var total: Int = 0
        private set

    /** Charge [bytes] toward the budget; throws once the cap is exceeded. */
    fun charge(bytes: Int) {
        total += bytes
        check(total <= maxBytes) {
            "offload request too large: $total bytes > $maxBytes limit"
        }
    }
}

internal fun DataInputStream.readLEInt(budget: OffloadRequestBudget): Int {
    budget.charge(4)
    val buf = ByteArray(4)
    readFully(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
}

internal fun DataInputStream.readLEString(budget: OffloadRequestBudget): String {
    val len = readLEInt(budget)
    if (len < 0 || len > 1 shl 20) throw IllegalStateException("bad string len $len")
    if (len == 0) return ""
    budget.charge(len)
    val buf = ByteArray(len)
    readFully(buf)
    return String(buf, Charsets.UTF_8)
}

/**
 * Host-side endpoint of the proot `native_offload` extension.
 *
 * The guest issues `execve("<handler-name>", argv, envp)`; the proot
 * extension sends argv/env/cwd over an abstract unix socket to this
 * server. The server dispatches to the registered [NativeOffloadHandler],
 * writes the handler's combined output into a tmpfile inside the guest's
 * /tmp, and replies with `(exit_code, guest_tmpfile_path)`. The extension
 * then rewrites the execve into `/bin/cat <tmpfile>` so the guest sees
 * the handler output as plain stdout.
 *
 * Mirrors iOS `native_offload_add_handler` + `native_offload_exec`.
 */
data class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    /**
     * T340: chat session id forwarded by the agent shell via the
     * `MINIS_CHAT_SESSION_ID` env var. Lets [OffloadPermissionManager]
     * scope ASK_ONCE grants/denials per-chat-session instead of using
     * a single process-wide bucket. Null when the offload originates
     * outside a chat (e.g. interactive terminal) — handlers fall back
     * to `OFFLOAD_GLOBAL_SESSION_ID` in that case.
     */
    val sessionId: String? = null,
)

data class NativeOffloadResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Request-scoped cancellation/ownership token.
 *
 * One instance belongs to one accepted socket, from accept until its response
 * is written or the socket is closed. [isCancelled] is the single fact every
 * participant reads: the socket watcher flips it on EOF, server shutdown flips
 * it for pending work, and a handler's inner dispatch (model-use) polls it.
 * Doing the work and reporting the result are therefore governed by the same
 * token instead of two separate "stopped the client" paths.
 *
 * The accepted context is the single queue/handler/response ownership token.
 * Cancellation wins atomically against work admission and response admission.
 * Listeners only signal an inner dispatch or close this request's own socket;
 * they never wait for handler completion on the monitor thread.
 */
class OffloadCallContext internal constructor(
    val requestId: Long,
    val ownerSessionId: String?,
) {
    private val state = java.util.concurrent.atomic.AtomicInteger(ACCEPTED)
    private val listenerLock = Any()
    private val listeners = mutableListOf<() -> Unit>()

    val isCancelled: Boolean get() = state.get() == CANCELLED

    fun addCancellationListener(listener: () -> Unit) {
        val notify = synchronized(listenerLock) {
            when (state.get()) {
                CANCELLED -> true
                ACCEPTED, RUNNING, HANDLER_DONE, RESPONDING -> { listeners.add(listener); false }
                else -> false
            }
        }
        if (notify) runCatching { listener() }
    }

    fun removeCancellationListener(listener: () -> Unit) {
        synchronized(listenerLock) { listeners.remove(listener) }
    }

    /** HUP cancels queued work or a result whose path has not been sent. */
    fun cancel(): Boolean = transitionToCancelled(includeResponse = false)

    /** Shutdown additionally interrupts a still-blocked socket response write. */
    fun cancelForShutdown(): Boolean = transitionToCancelled(includeResponse = true)

    private fun transitionToCancelled(includeResponse: Boolean): Boolean {
        val notify = synchronized(listenerLock) {
            while (true) {
                val old = state.get()
                if (old == CANCELLED || old == COMPLETED || (old == RESPONDING && !includeResponse)) return false
                if (state.compareAndSet(old, CANCELLED)) break
            }
            val snapshot = listeners.toList()
            listeners.clear()
            snapshot
        }
        notify.forEach { listener -> runCatching { listener() } }
        return true
    }

    /** Worker atomically claims a queued request before decoding/dispatching it. */
    fun beginWork(): Boolean = state.compareAndSet(ACCEPTED, RUNNING)

    fun handlerFinished(): Boolean = state.compareAndSet(RUNNING, HANDLER_DONE)

    /** Response send has an explicit owner; normal client close after reply is not cancellation. */
    fun beginResponse(): Boolean = state.compareAndSet(HANDLER_DONE, RESPONDING)

    fun complete(): Boolean {
        val completed = state.compareAndSet(RESPONDING, COMPLETED)
        if (completed) synchronized(listenerLock) { listeners.clear() }
        return completed
    }

    private companion object {
        const val ACCEPTED = 0
        const val RUNNING = 1
        const val CANCELLED = 2
        const val HANDLER_DONE = 3
        const val RESPONDING = 4
        const val COMPLETED = 5
    }
}

/**
 * Socket-level abandonment watcher: ONE daemon thread for the whole server
 * generation, never one thread per connection.
 *
 * The request frame is the only thing [NativeOffloadServer.handleClient]
 * reads; this watcher must not consume a byte or it would steal the frame it
 * is watching. It only asks "is this descriptor still connected" through the
 * injected [probe] (Android glue in [socketPeerAlive]), and a disconnected
 * request is cancelled through its [OffloadCallContext]. A live, simply-slow
 * handler has no hang-up, so nothing is cancelled merely for taking long.
 *
 * The monitor holds the public descriptor obtained from LocalSocket; tests can
 * supply a synthetic descriptor and injected probe without creating a socket.
 */
internal class OffloadSocketMonitor(
    private val pollMillis: Long = OFFLOAD_SOCKET_POLL_MS,
    private val probe: (java.io.FileDescriptor) -> Boolean = ::socketPeerAlive,
) {
    private class Entry(val fd: java.io.FileDescriptor?, val context: OffloadCallContext)
    private val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var running = false
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            thread = thread(name="native-offload-monitor", isDaemon=true) {
                while (running) {
                    for (entry in entries.toList()) {
                        if (!running) break
                        val peerAlive = entry.fd?.let(probe) ?: true
                        if (!peerAlive && entry.context.cancel()) Log.i(TAG,"socket EOF/error -> request ${entry.context.requestId} cancelled")
                    }
                    try { Thread.sleep(pollMillis) } catch (_: InterruptedException) { return@thread }
                }
            }
        }
    }
    fun stop() {
        val (worker, watched) = synchronized(lock) {
            running=false; val current=thread; thread=null; val copy=entries.toList(); entries.clear(); current to copy
        }
        watched.forEach { it.context.cancelForShutdown() }; worker?.interrupt(); worker?.join(500)
    }
    fun watch(fd: java.io.FileDescriptor?, context: OffloadCallContext) {
        val added = synchronized(lock) {
            if (!running) false else { entries.add(Entry(fd, context)); true }
        }
        if (!added) context.cancelForShutdown()
    }
    fun forget(context: OffloadCallContext) { synchronized(lock) { entries.removeAll { it.context === context } } }
    internal fun watchedCount(): Int = entries.size
    companion object { private const val TAG="OffloadSocketMonitor" }
}

internal fun socketPeerAlive(fd: java.io.FileDescriptor): Boolean {
    if (!fd.valid()) return false
    return try {
        val pfd=StructPollfd().apply { this.fd=fd; events=(OsConstants.POLLHUP or OsConstants.POLLERR).toShort() }
        val count=Os.poll(arrayOf(pfd),0)
        val terminal=OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL
        !isTerminalSocketPollResult(count,pfd.revents,terminal)
    } catch (_: android.system.ErrnoException) { fd.valid() } catch (_: Exception) { true }
}
internal fun localSocketDescriptor(socket: LocalSocket): java.io.FileDescriptor? = runCatching { socket.fileDescriptor?.takeIf { it.valid() } }.getOrNull()
internal fun isTerminalSocketPollResult(count:Int,revents:Short,terminalMask:Int):Boolean = count>0 && (revents.toInt() and terminalMask)!=0

internal const val OFFLOAD_SOCKET_POLL_MS = 250L
/** A worker that cannot read its bounded frame within this window is abandoned. */
internal const val OFFLOAD_FRAME_READ_TIMEOUT_MS = 10_000
/** Granularity at which a running handler observes its own cancellation. */
internal const val OFFLOAD_HANDLER_CANCEL_POLL_MS = 200L

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    private const val MAGIC_REQ = 0x46464F4E  // 'N' 'O' 'F' 'F' little-endian
    private const val MAGIC_RSP = 0x52464F4E  // 'N' 'O' 'F' 'R'
    private const val VERSION = 1

    // [native-rss-tool-guard] Maximum concurrent native-offload worker
    // threads. Matches the shell coordinator's MAX_CONCURRENT_SHELLS so a
    // burst of concurrent offload requests never exceeds the concurrency the
    // rest of the memory-budget machinery was sized for.
    internal const val MAX_CONCURRENT_WORKERS = 2

    // [dual-appid] The abstract unix socket name MUST be unique per install
    // identity (applicationId), otherwise a co-existing lab build
    // (com.rikkaminis.app.lab) and the stable build (com.rikkaminis.app) fight
    // over the same abstract socket name and one of them fails to bind
    // ("failed to bind abstract socket 'native-offload' ... previous process
    // holding the namespace?") and crashes in MinisApp.onCreate. libproot's
    // native_offload extension is fully parameterized over this name
    // (received via `--native-offload=<name>:<handlers>` in PRootKernel), so
    // the Kotlin and C sides stay consistent.
    // [multi-instance] …and per *running instance* (uid): 应用双开 / 多用户
    // 跑的是同一 applicationId 的不同 uid，只按包名命名会让第二个实例撞名
    // → bind 全失败 → onCreate 崩溃/重启循环。见 [offloadSocketName]。
    private val SOCKET_NAME = offloadSocketName(BuildConfig.APPLICATION_ID, Process.myUid())

    val socketName: String = SOCKET_NAME

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

    // One watcher for the whole server generation. It holds no per-connection
    // thread; see [OffloadSocketMonitor].
    private var socketMonitor: OffloadSocketMonitor? = null
    // The current generation's pool, kept only so [stop] can drain queued
    // sockets. Null before start() and after stop().
    private var workerPool: BoundedWorkerPool<LocalSocket>? = null

    // Requests accepted but not yet claimed by a worker. Bounded by the same
    // permit count as the worker pool: a queued LocalSocket is cheap, but the
    // queue is not free — without a bound a misbehaving guest can park an
    // unbounded number of fds (and their contexts) in the main process.
    @Volatile
    private var pendingQueueLimit = MAX_CONCURRENT_WORKERS

    // [T-offload-tmpfile-leak] Response tmpfiles written to rootfs /tmp,
    // tracked by a bounded ledger ([OffloadTmpFileLedger], audit-RC7) instead
    // of the old single-slot `lastTmpHost`. Without tracking, the rootfs /tmp
    // (a tmpfs = RAM-backed) accumulates one .native-offload-* file per call
    // forever — with large outputs (logcat, ps) that is real RAM the UI
    // process never gets back. The single-slot design also raced under
    // concurrent sessions (leaked files / deleted not-yet-cat'd files); the
    // ledger keeps the newest files and evicts oldest-first under one lock.
    private val tmpLedger = OffloadTmpFileLedger()

    // [native-rss-tool-guard / D-2] Bounded worker concurrency. Previously each
    // accepted connection spawned an unbounded `thread {}` — with several
    // concurrent sessions each offloading repeatedly, the per-thread native
    // stack + handler allocations accumulate in the main process with no
    // backpressure. Cap concurrent worker threads to the same bound as the
    // shell coordinator; excess requests queue on the semaphore instead of
    // spawning a new OS thread.
    // [D-2] sized at start() from the shared ConcurrencyPrefs cap (kept aligned
    // with SessionConcurrencyManager / ExecutionCoordinator). @Volatile because
    // it is replaced once in start(); never resized at runtime.
    @Volatile
    private var workerConcurrency = java.util.concurrent.Semaphore(MAX_CONCURRENT_WORKERS, true)

    @Volatile
    private var rootfsTmpDir: File? = null

    val registeredHandlers: Set<String> get() = handlers.keys.toSet()

    fun register(name: String, handler: NativeOffloadHandler) {
        require(name.isNotEmpty())
        handlers[name] = handler
        Log.d(TAG, "register '$name' (total=${handlers.size})")
    }

    @Synchronized
    fun start(rootfsDir: File) {
        rootfsTmpDir = File(rootfsDir, "tmp")
        // [audit-0919 F-216] Idempotency guard FIRST. The semaphore rebuild used
        // to sit above this line, so every repeat call swapped in a fresh
        // Semaphore while permits already acquired on the old instance stayed
        // acquired on an object nobody reads any more — the
        // MAX_CONCURRENT_WORKERS ceiling was momentarily unenforced and the
        // in-flight workers' releases went to the discarded instance. start()
        // has two production callers (MinisApp.kt onCreate and
        // PRootKernel.boot) and the MinisApp one does not go through the boot
        // idempotency path, so a second entry in the same process is reachable.
        if (serverSocket != null) return

        // [D-2] size the offload worker cap from the shared ConcurrencyPrefs
        // knob (runs after ConcurrencyPrefs.prime in MinisApp.onCreate), kept
        // aligned with the shell coordinator + session slot cap.
        val workers = com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()
        workerConcurrency = java.util.concurrent.Semaphore(workers, true)
        pendingQueueLimit = workers

        // [meta1028-B04 F-11] Close the two ledger gaps that survive a knob
        // change and a process death:
        //
        //  * SIZE — the ledger's DEFAULT_CAPACITY (4) assumed the worker pool
        //    was the old fixed 2. It is now 1..16 (`ConcurrencyPrefs`), and at
        //    16 the ring would evict a file after TWO newer requests while
        //    fourteen replies are still in flight and un-cat'd: the exact
        //    "deleted a file the guest had not read yet" failure the ledger
        //    exists to prevent.
        //  * ORPHANS — the ring lives in memory, so a process death (silent
        //    kill, OOM, debug crash) leaves every `.native-offload-*` file in
        //    the rootfs /tmp (a tmpfs = RAM) with nothing left to reclaim it.
        //    Sweeping them at start is the only closure across a process
        //    boundary; nothing can still be reading them, because the proot
        //    that would `cat` them died with the old process.
        rootfsTmpDir?.let { dir ->
            val swept = sweepStaleTmpFiles(dir)
            if (swept > 0) Log.i(TAG, "reclaimed $swept orphaned offload tmpfile(s) from a previous process")
        }
        tmpLedger.resize(OffloadTmpFileLedger.capacityFor(workers))

        // T287-followup: bind with bounded retry. Linux abstract sockets are
        // freed by the kernel only after the owning process is fully reaped —
        // when the previous app process dies (debug crash button, OOM kill,
        // ANR-kill) and Android's ActivityManager respawns us within ~100ms,
        // the kernel may still hold our prior namespace entry and bindLocal
        // returns EADDRINUSE. Crashing onCreate here puts the app in a
        // restart loop forever (re-spawn → EADDRINUSE → ACRA caught → die →
        // re-spawn …). Retry up to ~2s with exponential backoff; in the
        // overwhelming majority of cases the socket frees within the first
        // 100-300ms window.
        val s = bindWithRetry()
            ?: throw java.io.IOException(
                "failed to bind abstract socket '$SOCKET_NAME' after retries — " +
                "previous process holding the namespace?",
            )
        serverSocket = s
        // One watcher for the whole generation: it detects a guest that hung up
        // before/while its request was queued, and cancels that request's
        // context instead of waiting out the worker slot.
        val monitor = OffloadSocketMonitor()
        socketMonitor = monitor
        monitor.start()
        // [ISSUE-12b] Thread creation for this generation is owned by the pool:
        // it spawns a worker only while a permit is free (non-blocking
        // tryAcquire) and the worker returns the permit when it drains empty —
        // so an idle server holds zero worker threads, while a burst can never
        // exceed the permit cap the rest of the memory budgeting was sized for.
        val pool = BoundedWorkerPool<LocalSocket>(
            permits = workerConcurrency,
            threadName = "native-offload-worker",
            spawnThread = { name, body -> thread(name = name, isDaemon = true) { body() } },
            handler = { client, queueMs ->
                try {
                    handleClient(client, queueMs, File(rootfsDir, "tmp"))
                } catch (e: Exception) {
                    Log.w(TAG, "worker error: ${e.message}", e)
                } finally {
                    contextBySocket.remove(client)?.let { it.cancelForShutdown(); monitor.forget(it) }
                    try { client.close() } catch (_: Exception) {}
                }
            },
        )
        pool.setMaxPending(pendingQueueLimit)
        workerPool = pool
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s, pool, monitor)
        }
        Log.i(TAG, "listening on abstract socket '$SOCKET_NAME' " +
            "handlers=${handlers.keys.sorted()} tmpDir=${rootfsTmpDir?.absolutePath}")
    }

    private fun bindWithRetry(): LocalServerSocket? {
        // Backoff schedule: 0, 50, 100, 200, 400, 800 ms — total ~1.55s.
        val delays = longArrayOf(0L, 50L, 100L, 200L, 400L, 800L)
        for ((attempt, delay) in delays.withIndex()) {
            if (delay > 0) Thread.sleep(delay)
            try {
                return LocalServerSocket(SOCKET_NAME)
            } catch (e: java.io.IOException) {
                Log.w(TAG, "bind attempt ${attempt + 1}/${delays.size} failed: ${e.message}")
            }
        }
        return null
    }

    @Synchronized
    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread = null
        val pool = workerPool
        workerPool = null
        val monitor = socketMonitor
        socketMonitor = null
        pool?.drainPending { client -> contextBySocket.remove(client)?.cancelForShutdown(); runCatching { client.close() } }
        monitor?.stop()
        // [audit-RC7] Server is going away — no guest will ever cat the
        // tracked tmpfiles again. Reclaim them now instead of leaking RAM
        // until the next rootfs reset.
        tmpLedger.clearAll()
    }

    /** Diagnostics/tests: how many accepted clients are waiting for a worker. */
    internal fun pendingRequestCount(): Int = workerPool?.pendingCount ?: 0

    private fun runAcceptLoop(
        s: LocalServerSocket,
        pool: BoundedWorkerPool<LocalSocket>,
        monitor: OffloadSocketMonitor,
    ) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            // [ISSUE-12b] The accept loop no longer spawns one OS thread per
            // connection (and no longer blocks, so `serverSocket.close()` keeps
            // interrupting `accept()` exactly as before). It hands the socket to
            // the bounded pool, which spawns a worker ONLY while a permit is
            // free and queues excess clients as cheap FIFO entries — a parked
            // thread costs a ~1 MB native stack, a queued socket entry
            // effectively nothing. Arrival order, the concurrency cap and the
            // back-pressure measurement are unchanged.
            //
            // One context per accepted socket, created BEFORE the frame exists
            // (the worker reads it) so a hang-up cannot arrive without an
            // owner. Its id is the accept counter; the owner session comes from
            // the decoded frame.
            val context = OffloadCallContext(counter.incrementAndGet(), ownerSessionId = null)
            val descriptor = localSocketDescriptor(client)
            // Each generation owns only its accepted sockets. Stop/HUP closes
            // this socket, interrupting a queued/frame-reading request as well.
            context.addCancellationListener { runCatching { client.close() } }
            contextBySocket[client] = context
            monitor.watch(descriptor, context)
            // Queueing is bounded too: past the pool's pending limit the client
            // is closed immediately instead of becoming another parked fd.
            if (!pool.submit(client)) {
                Log.w(TAG, "offload queue full — rejecting client")
                monitor.forget(context)
                contextBySocket.remove(client)
                context.cancel()
                runCatching { client.close() }
            }
        }
    }

    private val contextBySocket = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<LocalSocket, OffloadCallContext>(),
    )

    private fun handleClient(client: LocalSocket, queueMs: Long, responseDir: File) {
        val context = contextBySocket[client] ?: return
        // Claim the accepted request before decoding. A queued HUP or server
        // stop wins the same state transition and makes this worker skip it.
        if (!context.beginWork()) {
            Log.i(TAG, "request ${context.requestId} abandoned in queue — skipped")
            return
        }
        val input = DataInputStream(client.inputStream)
        val output = DataOutputStream(client.outputStream)
        // Bound the frame read: a peer that connects and then sends nothing
        // must not occupy a worker permit forever. The frame is small and the
        // guest writes it in one go, so this is a stall detector, not a
        // throughput limit.
        runCatching { client.soTimeout = OFFLOAD_FRAME_READ_TIMEOUT_MS }

        // Bounded frame decode: every byte read (header, argv, env, cwd)
        // is charged against MAX_REQUEST_BYTES and aborts the request the
        // moment the cumulative size exceeds it — an oversized/hostile
        // frame is rejected with a logged error instead of allocating
        // unbounded arg/env strings on the host.
        val budget = OffloadRequestBudget(MAX_REQUEST_BYTES)
        val magic = input.readLEInt(budget)
        if (magic != MAGIC_REQ) {
            Log.w(TAG, "bad magic: 0x${magic.toUInt().toString(16)}")
            return
        }
        val version = input.readLEInt(budget)
        if (version != VERSION) {
            Log.w(TAG, "unsupported version $version")
            return
        }

        val pid = input.readLEInt(budget)
        val argc = input.readLEInt(budget)
        if (argc < 0 || argc > 256) throw IllegalStateException("bad argc=$argc")
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(input.readLEString(budget)) }

        val envc = input.readLEInt(budget)
        if (envc < 0 || envc > 4096) throw IllegalStateException("bad envc=$envc")
        val env = LinkedHashMap<String, String>(envc)
        repeat(envc) {
            val s = input.readLEString(budget)
            val eq = s.indexOf('=')
            if (eq >= 0) env[s.substring(0, eq)] = s.substring(eq + 1) else env[s] = ""
        }
        val cwd = input.readLEString(budget)

        val name = argv.firstOrNull().orEmpty().substringAfterLast('/')
        // [C-01] The old line printed the FULL argv and cwd before the handler
        // — or the permission gate — ran. `minis-config set providers.<id>.apiKey
        // <value>` takes the secret as a positional argument, so that line wrote
        // it to logcat → the AppLogger daily log → the share sheet, defeating the
        // business layer's own write-only redaction. Keep the low-sensitivity
        // shape (handler, arg count, sizes) and nothing that can carry a value.
        Log.d(
            TAG,
            "recv pid=$pid name='$name' argc=$argc envc=$envc " +
                "argvChars=${argv.sumOf { it.length }} cwdChars=${cwd.length}",
        )

        if (context.isCancelled) return
        val t0 = System.nanoTime()
        val handler = handlers[name]

        // [offload-rss] 打点定位：handler 执行前后各读一次主进程 VmRSS，
        // 把增量归因到具体 handler 名（model-use/sessions/browser-use/weather …）。
        // 泄漏的 mmap / thread-stack / mapped-tmpfile 增长正是 2026-08-17/19 SIGABRT 的形态，
        // 但之前这条 native-offload 路径没有任何 RSS 归因——这是「泄漏涨在哪个 handler」的盲区。
        // 打点零副作用（OffloadRssProbe 读 /proc 失败返回 0），不影响 handler 结果。
        val rssBeforeKb = OffloadRssProbe.rssKb()
        val result = if (handler == null) {
            Log.w(TAG, "no handler registered for '$name' (known=${handlers.keys})")
            NativeOffloadResult(exitCode = 127, output = "native_offload: no handler for '$name'\n")
        } else {
            try {
                NativeOffloadServer.requestContext.set(context)
                handler.handle(NativeOffloadRequest(
                    pid = pid,
                    argv = argv,
                    env = env,
                    cwd = cwd,
                    sessionId = env["MINIS_CHAT_SESSION_ID"]?.takeIf { it.isNotEmpty() },
                ))
            } catch (e: Exception) {
                Log.w(TAG, "handler '$name' threw: ${e.message}", e)
                NativeOffloadResult(exitCode = 1, output = "native_offload: ${e.message}\n")
            } finally {
                NativeOffloadServer.requestContext.remove()
            }
        }
        if (!context.handlerFinished()) {
            Log.i(TAG, "request ${context.requestId} cancelled before response delivery")
            return
        }
        val rssAfterKb = OffloadRssProbe.rssKb()
        if (handler != null) {
            OffloadRssProbe.record(name, rssBeforeKb, rssAfterKb)
            // [mem-spike-diag] 同一份归因也写进内存尖峰记录器：offload 工具
            //（android-* / browser-use / model-use / sessions …）**不走**
            // ExecutionCoordinator，cmd-start/cmd-end 完全看不到它们 —— 而
            // 2026-09-13 16:54 那次尖峰（native 38MB→1141MB）恰恰全程无 cmd 记录。
            val offloadSession = env["MINIS_CHAT_SESSION_ID"] ?: "-"
            MemorySpikeRecorder.onEvent(
                "offload",
                "handler=$name Δ=${(rssAfterKb - rssBeforeKb) / 1024}MB session=$offloadSession",
            )
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        // [audit-cs0913] Back-pressure report. A saturated pool (or one long
        // handler) silently stalls later tool calls until the shell-level stall
        // watchdog kills them 180s later — indistinguishable from a broken tool.
        // Recorded, NOT enforced: measure first, then decide on a guardrail and
        // its threshold. See OffloadQueuePolicy.
        if (OffloadQueuePolicy.shouldReport(queueMs, elapsedMs)) {
            val detail = OffloadQueuePolicy.describe(name, queueMs, elapsedMs)
            Log.w(TAG, "offload backpressure: $detail")
            MemorySpikeRecorder.onEvent("offload-latency", detail)
        }

        // [meta1028-B04 F-11] Deliver the response. A tmpfile that cannot be
        // WRITTEN is a delivery failure, and the protocol's only channel is a
        // PATH — inventing a reply here would have the guest `cat` a sentence.
        // So the exception still propagates (the worker closes the socket, the
        // guest sees the same hard failure it always did); what changes is that
        // the half-written file is now reclaimed instead of leaking, and the
        // failure is logged with its cause instead of vanishing as EOF.
        val delivered = deliverResponseFile(
            result.output,
            responseDir,
            pid,
            counter.incrementAndGet(),
            tmpLedger,
        ).getOrElse { cause ->
            Log.e(TAG, "could not write the response tmpfile for pid=$pid: ${cause.message}")
            throw IllegalStateException("response delivery failed: ${cause.message}", cause)
        }
        val tmpGuest = delivered.guestPath
        // A disconnect can win while the handler is returning or while its
        // result file is being written. Claim the send state atomically; if the
        // peer already closed, reclaim the file and do not advertise its path.
        if (!context.beginResponse()) {
            tmpLedger.reclaim(File(delivered.hostPath))
            return
        }
        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${result.output.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")
        try {
            output.writeLEInt(MAGIC_RSP)
            output.writeLEInt(result.exitCode)
            output.writeLEString(tmpGuest)
            output.flush()
        } catch (t: Throwable) {
            tmpLedger.reclaim(File(delivered.hostPath))
            throw t
        }
        // The native client closes only after the full frame is flushed. This
        // makes normal HUP inert without racing the write with the state commit.
        if (!context.complete()) tmpLedger.reclaim(File(delivered.hostPath))

    }

    /**
     * The context of the request whose handler is running on this thread.
     *
     * Set around the single [NativeOffloadHandler.handle] call and read by a
     * handler that wants to bind an inner await (model-use's dispatch) to the
     * same cancellation source. It is a ThreadLocal rather than a parameter on
     * [NativeOffloadRequest] on purpose: changing that constructor would put
     * every handler file in this branch's blast radius, and the ones that do
     * not opt in must not get a new cancellation interface invented for them.
     */
    private val requestContext: ThreadLocal<OffloadCallContext> = ThreadLocal()

    internal fun currentCallContext(): OffloadCallContext? = requestContext.get()

    // ---- little-endian helpers ----

    private fun DataOutputStream.writeLEInt(v: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        write(buf)
    }

    private fun DataOutputStream.writeLEString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeLEInt(bytes.size)
        if (bytes.isNotEmpty()) write(bytes)
    }
}

/**
 * Name prefix of every response tmpfile. Shared by the writer and the boot
 * sweep so a rename cannot make the sweep stop seeing orphans
 * ([meta1028-B04 F-11]).
 */
internal const val OFFLOAD_TMP_PREFIX = ".native-offload-"

/**
 * Where one delivered response can be found, host side and guest side.
 */
internal data class DeliveredResponse(val hostPath: String, val guestPath: String)

/**
 * Write one handler response into [tmpDir] and register it in [ledger].
 *
 * [meta1028-B04 F-11] Two things this ordering buys, both invisible in the
 * old `writeText(...)` + `rotate(...)` pair:
 *
 *  1. a failed write deletes what it created ([writeResponseFile] reclaims the
 *     file it registered), instead of leaking a partial tmpfile the ledger
 *     never heard about;
 *  2. the caller gets a [Result] it must branch on, so "could not deliver" can
 *     no longer fall through to a success reply carrying a path that does not
 *     exist — the guest `cat`'d nothing and read an empty answer at exit 0.
 *
 * [writeFn] is injectable so both arms run in a JVM test.
 */
internal fun deliverResponseFile(
    output: String,
    tmpDir: File,
    pid: Int,
    seq: Long,
    ledger: OffloadTmpFileLedger,
    // Named, not an inline lambda: see `writeFileText` — a write inside a
    // parameter-list default is outside every parsed function body and trips
    // the read-only-mount gate's fail-closed rule.
    writeFn: (File, String) -> Unit = ::writeFileText,
): Result<DeliveredResponse> {
    tmpDir.mkdirs()
    val host = File(tmpDir, "$OFFLOAD_TMP_PREFIX$pid-$seq")
    return writeResponseFile(host, output, ledger, writeFn)
        .map { DeliveredResponse(host.absolutePath, "/tmp/${host.name}") }
}

/**
 * Delete every response tmpfile left in [dir] by a PREVIOUS process.
 *
 * [meta1028-B04 F-11] The in-memory ledger cannot outlive the process that
 * owns it, and the files are the only part of a response that does: each one
 * occupies real RAM (the rootfs /tmp is a tmpfs). Called from `start()`, when
 * nothing can still be reading them — the proot that would `cat` a file is a
 * child of the process that died. Returns how many were reclaimed.
 *
 * Only regular files whose name starts with [OFFLOAD_TMP_PREFIX] are touched,
 * so a guest's own `/tmp` content is never collateral. [deleteFn] and [names]
 * are parameters so the sweep is testable without a real rootfs.
 */
internal fun sweepStaleTmpFiles(
    dir: File,
    deleteFn: (File) -> Boolean = { it.delete() },
    names: (File) -> Array<String>? = { it.list() },
): Int {
    val entries = names(dir) ?: return 0
    var swept = 0
    for (name in entries) {
        if (!name.startsWith(OFFLOAD_TMP_PREFIX)) continue
        val f = File(dir, name)
        // A directory can carry the same name only if something inside the
        // guest created it; `File.delete()` refuses non-empty directories, so
        // this cannot recurse into content the sweep never inspected.
        if (runCatching { deleteFn(f) }.getOrDefault(false)) swept++
    }
    return swept
}

/**
 * [ISSUE-12b] Bounded transient worker pool backing [NativeOffloadServer].
 *
 * The old accept loop spawned one `thread {}` per accepted connection and let
 * that thread block on the concurrency semaphore — so a burst of N
 * offloads created N OS threads (each parking on a ~1 MB native stack) while
 * only 2 made progress. The [native-rss-tool-guard] comment promised "queues
 * here instead of spawning an unbounded handler thread", but the queue was
 * implemented as parked threads.
 *
 * This pool makes that promise real, with the semaphore still the single
 * bound mechanism (sized in `start()` from ConcurrencyPrefs, unchanged):
 *  - at most `permits.availablePermits()+in-flight` — i.e. exactly the permit
 *    count — worker threads exist at any moment: a submit spawns a worker only
 *    via non-blocking `tryAcquire()`, and the worker holds that permit until
 *    it drains the queue empty and releases it on exit;
 *  - excess clients wait as FIFO queue entries (arrival order preserved, the
 *    same order the old fair semaphore gave);
 *  - workers are transient: a drained pool holds zero threads, so an idle
 *    server costs no native stack (the D-2 memory rationale, kept);
 *  - no accepted client is ever lost: the submit side (enqueue + tryAcquire)
 *    and the worker-exit side (poll-empty + release) run under the same lock,
 *    so a submit either sees a free permit and spawns, or sees a live worker
 *    that is guaranteed to poll the just-enqueued entry;
 *  - the accept thread never blocks on permits, so `stop()`'s
 *    `serverSocket.close()` keeps interrupting `accept()` exactly as before;
 *  - a throwing handler neither strands the queue nor leaks the permit (the
 *    old per-connection worker released in a `finally`): the worker keeps
 *    draining, then rethrows the first failure after it exits.
 *
 * `queueMs` handed to [handler] keeps [OffloadQueuePolicy]'s meaning — time
 * spent waiting for a worker slot ([audit-cs0913] back-pressure report),
 * measured accept→pickup instead of thread-start→acquire: the same wait,
 * end to end.
 */
internal class BoundedWorkerPool<T>(
    private val permits: java.util.concurrent.Semaphore,
    private val threadName: String,
    private val spawnThread: (name: String, body: () -> Unit) -> Unit,
    private val handler: (item: T, queueMs: Long) -> Unit,
) {
    private class Entry<T>(val item: T, val enqueuedNs: Long)

    private val lock = Any()
    private val pending = java.util.ArrayDeque<Entry<T>>()
    private var closed = false

    /**
     * Number of entries that may wait for a worker. `null` keeps the original
     * unbounded FIFO (the default used by existing unit fixtures); the
     * production server passes its permit count so a burst cannot park an
     * unbounded number of accepted sockets.
     */
    private var maxPending: Int? = null

    fun setMaxPending(limit: Int?) {
        synchronized(lock) { maxPending = limit }
    }

    /** FIFO enqueue; spawns a worker only when a permit is free. */
    fun submit(item: T): Boolean {
        val spawn = synchronized(lock) {
            if (closed) return@synchronized null
            val limit = maxPending
            if (limit != null && pending.size >= limit) return@synchronized null
            pending.addLast(Entry(item, System.nanoTime()))
            permits.tryAcquire()
        } ?: return false
        if (spawn) {
            spawnThread(threadName) { drain() }
        }
        return true
    }

    /**
     * Drop every queued entry and hand its removal to [onDropped].
     *
     * [stop] uses this to close queued sockets instead of leaving their fds
     * parked until a future start() generation drains them; a queued item has
     * not begun work, so dropping it never interrupts a handler.
     */
    fun drainPending(onDropped: (T) -> Unit) {
        val dropped = synchronized(lock) {
            closed = true
            val copy = pending.map { it.item }
            pending.clear()
            copy
        }
        dropped.forEach { runCatching { onDropped(it) } }
    }

    private fun drain() {
        var firstFailure: Throwable? = null
        while (true) {
            val entry = synchronized(lock) {
                pending.pollFirst() ?: run {
                    // No work left: hand the permit back so a future submit
                    // can spawn again. Poll-empty + release are atomic w.r.t.
                    // submit's enqueue + tryAcquire — that is what makes
                    // "enqueue sees a permit-less world with no live worker"
                    // impossible.
                    permits.release()
                    null
                }
            } ?: break
            try {
                handler(entry.item, (System.nanoTime() - entry.enqueuedNs) / 1_000_000)
            } catch (t: Throwable) {
                // A throwing handler must neither strand the queue nor leak the
                // permit — the old per-connection worker released in a finally
                // for exactly this reason. Keep draining (the production
                // wiring already logs each handler error), remember the first
                // failure and rethrow it once this worker exits, so an
                // unhandled Error still reaches the thread's default handler
                // instead of being swallowed.
                if (firstFailure == null) firstFailure = t
            }
        }
        firstFailure?.let { throw it }
    }

    /** Diagnostics / tests only: entries still waiting for a worker. */
    val pendingCount: Int get() = synchronized(lock) { pending.size }
}
