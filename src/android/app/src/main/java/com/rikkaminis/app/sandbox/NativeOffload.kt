package com.rikkaminis.app.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.rikkaminis.app.BuildConfig
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

fun interface NativeOffloadHandler {
    fun handle(request: NativeOffloadRequest): NativeOffloadResult
}

object NativeOffloadServer {
    private const val TAG = "NativeOffloadServer"
    private const val SOCKET_BASE = "native-offload"
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
    // holding the namespace?") and crashes in MinisApp.onCreate. Keying the
    // name off BuildConfig.APPLICATION_ID makes each install bind its own
    // socket. libproot's native_offload extension is fully parameterized over
    // this name (received via `--native-offload=<name>:<handlers>` in
    // PRootKernel), so the Kotlin and C sides stay consistent.
    private val SOCKET_NAME =
        SOCKET_BASE + "-" + BuildConfig.APPLICATION_ID.replace('.', '_')

    val socketName: String = SOCKET_NAME

    private val handlers = ConcurrentHashMap<String, NativeOffloadHandler>()
    private val counter = AtomicLong(0)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

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
        // [D-2] size the offload worker cap from the shared ConcurrencyPrefs
        // knob (runs after ConcurrencyPrefs.prime in MinisApp.onCreate), kept
        // aligned with the shell coordinator + session slot cap.
        workerConcurrency = java.util.concurrent.Semaphore(
            com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions(),
            true,
        )
        if (serverSocket != null) return

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
        acceptThread = thread(name = "native-offload-accept", isDaemon = true) {
            runAcceptLoop(s)
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
        // [audit-RC7] Server is going away — no guest will ever cat the
        // tracked tmpfiles again. Reclaim them now instead of leaking RAM
        // until the next rootfs reset.
        tmpLedger.clearAll()
    }

    private fun runAcceptLoop(s: LocalServerSocket) {
        while (true) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                Log.i(TAG, "accept loop terminated: ${e.message}")
                return
            }
            Log.d(TAG, "accepted client from proot extension")
            thread(name = "native-offload-worker", isDaemon = true) {
                // [native-rss-tool-guard] Bounded concurrency: acquire the
                // worker slot BEFORE reading the frame so a burst of
                // concurrent offload requests queues here instead of each
                // spawning an unbounded handler thread that bloats native
                // heap. The slot is released on every exit path (success,
                // decode error, handler throw) so one bad client can't leak
                // the worker permit.
                val acquired = try {
                    workerConcurrency.acquire()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { client.close() }
                    return@thread
                }
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    Log.w(TAG, "worker error: ${e.message}", e)
                } finally {
                    workerConcurrency.release()
                    try { client.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        val input = DataInputStream(client.inputStream)
        val output = DataOutputStream(client.outputStream)

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
        Log.d(TAG, "recv pid=$pid name='$name' argc=$argc argv=$argv cwd=$cwd envc=$envc")

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
            }
        }
        val rssAfterKb = OffloadRssProbe.rssKb()
        if (handler != null) {
            OffloadRssProbe.record(name, rssBeforeKb, rssAfterKb)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        val tmpDir = rootfsTmpDir ?: throw IllegalStateException("server not started")
        tmpDir.mkdirs()

        // [audit-RC7] Register the new tmpfile in the bounded ledger. The
        // ledger evicts (deletes) the oldest tracked file once it exceeds
        // capacity — an evicted file has survived several full reply→cat
        // cycles, so the guest has long consumed it. Deletion happens for
        // exactly one thread per file (ledger-internal lock): no orphan, no
        // double-delete, no deleting a file the guest hasn't cat'd yet.
        val seq = counter.incrementAndGet()
        val tmpHost = File(tmpDir, ".native-offload-$pid-$seq")
        tmpHost.writeText(result.output)
        tmpLedger.rotate(tmpHost)
        val tmpGuest = "/tmp/${tmpHost.name}"

        Log.d(TAG, "reply name='$name' exit=${result.exitCode} outBytes=${result.output.length} " +
            "tmpGuest=$tmpGuest elapsed=${elapsedMs}ms")

        output.writeLEInt(MAGIC_RSP)
        output.writeLEInt(result.exitCode)
        output.writeLEString(tmpGuest)
        output.flush()
    }

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
