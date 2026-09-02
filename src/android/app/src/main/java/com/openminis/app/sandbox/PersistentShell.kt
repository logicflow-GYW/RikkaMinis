package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
/** Result of a single command executed in the persistent shell. */
data class CommandResult(
    val output: String,
    val exitCode: Int,
    val truncated: Boolean = false,
)

class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val TAG = "PersistentShell"
        // [P2-proot-native-leak] Poll cadence for the in-command PRoot child
        // RSS monitor. Cheap /proc read; every poll catches a mid-run OOM at
        // this granularity and lets ExecutionCoordinator recycle pre-crash.
        private const val PROOT_MEM_POLL_MS = 1_000L

        // Hard cap on command output before truncation to guard against
        // runaway commands flooding the agent context window and app memory.
        // Mirrors RikkaHub's WorkspaceShellRunner.MAX_OUTPUT_CHARS.
        private const val MAX_OUTPUT_CHARS = 128 * 1024
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    /**
     * [fix/audit-s4h3] True while a command is mid-flight on this shell
     * (callback pending). ExecutionCoordinator's idle recycler uses this to
     * avoid reaping a shell whose `lastActiveMs` is stale only because the
     * timestamp updates on command COMPLETION, not start.
     */
    val isBusy: Boolean
        get() = pendingCallback != null

    val isAlive: Boolean
        get() = process?.isAlive == true

    /**
     * [P2-app-native-oom] Number of commands executed on this shell instance.
     * Reset to 0 by [stop]. ExecutionCoordinator uses this to recycle the
     * shell after a dense tool-call sequence (e.g. 20+ git/shell commands
     * in 2.5 minutes) to prevent app-process native heap pressure from
     * accumulating past Scudo's limit.
     */
    @Volatile
    var commandCount: Int = 0
        private set

    /**
     * [shell-generation-scheduler] Monotonic shell generation. Incremented
     * by [stop] every time the process is torn down (recycle, idle sweep,
     * user cancel). An in-flight command snapshots the generation before it
     * starts and compares after it completes — if the shell was recycled
     * mid-command, the continuation's result came from a dead/restarted
     * tracer and is discarded (reported as -1 so the coordinator retries on
     * the fresh shell).
     */
    @Volatile
    var generation: Int = 0
        private set

    /**
     * [P2-proot-native-leak] Resident set size (MB) of the live PRoot child
     * process, read from /proc/<pid>/status VmRSS. This is the authoritative
     * signal for the PRoot tracer's native leak — NOT Debug.getNativeHeap-
     * AllocatedSize(), which reports the *app* process heap and never sees
     * the leaked memory held by the forked PRoot tracer (via the PTY master
     * fd). Returns 0 when no process is alive or the file is unreadable.
     */
    fun nativeRssMB(): Long {
        val proc = process ?: return 0L
        val pid = processPid(proc)
        if (pid <= 0) return 0L
        return try {
            val status = File("/proc/$pid/status").readText()
            val m = Regex("""VmRSS:\s+(\d+) kB""").find(status) ?: return 0L
            m.groupValues[1].toLongOrNull()?.let { it / 1024L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * [P2-proot-native-leak] Resolve the PID of a [ProcessBuilder]-spawned
     * child process on Android. `java.lang.Process.pid()` (Java 9+) is NOT
     * on the Android compile classpath, so we read the private `pid` field
     * that every concrete Process impl keeps. Returns 0 if it can't be
     * resolved (caller then treats the shell as "no process to monitor").
     */
    private fun processPid(proc: Process): Int {
        return try {
            val f = proc.javaClass.declaredFields.firstOrNull { it.name == "pid" }
                ?: proc.javaClass.superclass?.declaredFields?.firstOrNull { it.name == "pid" }
            if (f == null) return 0
            f.isAccessible = true
            (f.get(proc) as? Number)?.toInt() ?: return 0
        } catch (_: Throwable) {
            0
        }
    }

    private class CommandCallback(
        val marker: String,
        val output: StringBuilder = StringBuilder(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int, Boolean) -> Unit)? = null,
        var truncated: Boolean = false,
    ) {
        // [fix/crash-storm-double-resume] Completion can be signalled from
        // two threads racing each other: `stop()` (running on the memory-
        // monitor coroutine after it recycles the shell) and the reader
        // loop (PersistentShell-reader thread exiting after the process is
        // killed). Both used to call `onComplete` → `cont.resume` directly,
        // so when they raced the SAME continuation got resumed twice and the
        // process crashed with IllegalStateException: Already resumed.
        // `cont.isActive` is NOT a safe guard here — after one resume() it
        // turns false only after the suspension point is deregistered, which
        // happens asynchronously on the coroutine dispatcher, leaving a
        // window where a second resume still sees isActive==true. We use an
        // atomic compare-and-set so exactly one thread wins; the loser is a
        // benign no-op (the shell is gone, the command already reported).
        private val completeOnce = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Signal completion, but only the first caller wins. */
        fun finishOnce(output: String, exitCode: Int, truncated: Boolean) {
            if (completeOnce.compareAndSet(false, true)) {
                onComplete?.invoke(output, exitCode, truncated)
            }
        }
    }

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) { startProcess() }
        } finally {
            isStarting.set(false)
        }
    }

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // [P2-proot-resource-hygiene] Same --kill-on-exit as buildProotCommand:
        // guarantees the PRoot tracer exits when this persistent /bin/sh is
        // destroyForcibly'd (oversize recycle, idle recycle, session end). The
        // /bin/sh stays alive for the whole session so this does not shorten
        // the persistent shell's lifetime — it only ensures a clean exit of
        // the tracer (which otherwise leaks native memory at ~6GB scale).
        cmd.add("--kill-on-exit")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = OffloadHandlerCatalog.allHandlerNames
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("/bin/sh")

        val debugOffload = com.openminis.app.BuildConfig.DEBUG

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.posixTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = sessionId

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        val p = processBuilder.start()
        process = p
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        Log.i(TAG, "Persistent shell started")
    }

    private fun readLoop(p: Process) {
        // [fix/marker-chunk-boundary] Cross-chunk tail buffer. A single
        // read() can split the __MINIS_DONE_ marker across two chunks;
        // the old per-chunk indexOf never sees a split marker, so the
        // command hung until the 600s timeout. Keep the trailing
        // (markerPattern.length - 1) chars of unmatched output and
        // re-scan them combined with the next chunk.
        var tail: String = ""
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = String(buffer, 0, n, StandardCharsets.UTF_8)

                val cb = pendingCallback
                if (cb != null) {
                    val scan = internalScanMarker(tail, text, cb.marker)
                    cb.appendOutput(scan.beforeMarker)
                    if (cb.lineCallback != null) {
                        feedLines(scan.beforeMarker, cb.lineCallback)
                    }
                    if (scan.hit) {
                        // Signal completion with truncated flag
                        cb.finishOnce(cb.output.toString(), scan.exitCode, cb.truncated)
                        pendingCallback = null
                        tail = ""
                    } else {
                        // Keep the trailing window — the marker (or its
                        // start) may be split across this chunk boundary.
                        tail = scan.keptTail
                    }
                } else {
                    // No pending callback — discard (shell prompt noise) and
                    // reset the tail so no stale bytes leak into the next cmd.
                    tail = ""
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited
        val cb = pendingCallback
        if (cb != null) {
            // EOF with no marker means the shell died — flush any withheld
            // tail (potential split-marker prefix) so the last bytes of the
            // command output aren't lost.
            if (tail.isNotEmpty()) cb.appendOutput(tail)
            cb.finishOnce(cb.output.toString(), -1, cb.truncated)
            pendingCallback = null
        }

        process = null
        stdinWriter = null
        Log.i(TAG, "Persistent shell process exited")
    }

    private fun feedLines(text: String, callback: (String) -> Unit) {
        val lines = text.split('\n')
        for (i in lines.indices) {
            val line = lines[i].replace("\r", "")
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                callback(line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                callback(line)
            }
        }
    }

    /**
     * Append [text] to [output], capping at [maxChars]. Returns `true` when
     * truncation occurred (the append was clipped or skipped entirely).
     * Delegates to the top-level [internalTruncateOutput] for JVM testability.
     */
    private fun CommandCallback.appendOutput(text: String) {
        if (internalTruncateOutput(output, text, MAX_OUTPUT_CHARS)) {
            truncated = true
        }
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    suspend fun executeCommand(
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        memoryMonitor: ((Long) -> Unit)? = null,
    ): CommandResult {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            return CommandResult("[Shell not running]", -1)
        }

        // [P2-app-native-oom] Track command count for dense-call recycling.
        commandCount++
        // [shell-generation-scheduler] Snapshot the shell generation so a
        // mid-command recycle (memory monitor, idle sweep, user cancel) can
        // be detected — the continuation's result then belongs to a dead
        // tracer and must not be trusted.
        val genAtStart = generation

        val marker = UUID.randomUUID().toString().take(8)

        // [T-termux-terminal-engine] Pass command via heredoc with a quoted
        // delimiter so the shell performs ZERO expansion on the command body.
        // Single quotes, double quotes, backslashes, dollar signs, backticks
        // all pass through literally — no escape nesting, no quoting hell.
        // Exit code is captured via $? immediately after eval.
        val heredocDelimiter = "ENDOFMINISCMD_${marker}"
        val wrappedCommand = buildString {
            append("eval \"\$(cat <<'$heredocDelimiter'\n")
            append(command)
            if (!command.endsWith('\n')) append('\n')
            append("$heredocDelimiter\n")
            append(")\"\n")
            append("RET=\$?\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\${RET}__\"\n")
        }

        return withContext(Dispatchers.IO) {
            // [P2-proot-native-leak] In-flight memory monitor: poll the real
            // PRoot child RSS while the command runs and surface it to the
            // coordinator so a crossing of the high-water mark can recycle
            // the shell BEFORE the child OOMs. Cheap — a /proc read per tick.
            val monitorJob = if (memoryMonitor != null) {
                launch {
                    while (isActive) {
                        val rss = nativeRssMB()
                        if (rss > 0L) memoryMonitor(rss)
                        delay(PROOT_MEM_POLL_MS)
                    }
                }
            } else null

            val result: CommandResult? = withTimeoutOrNull(timeout) {
                suspendCancellableCoroutine { cont ->
                    val cb = CommandCallback(
                        marker = marker,
                        lineCallback = lineCallback,
                    )
                    cb.onComplete = { output, exitCode, truncated ->
                        if (cont.isActive) {
                            cont.resume(CommandResult(output, exitCode, truncated))
                        }
                    }
                    pendingCallback = cb

                    cont.invokeOnCancellation {
                        pendingCallback = null
                    }

                    try {
                        writer.write(wrappedCommand)
                        writer.flush()
                    } catch (e: Exception) {
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(CommandResult("[Write error: ${e.message}]", -1))
                        }
                    }
                }
            }

            monitorJob?.cancel()

            // [shell-generation-scheduler] The shell was recycled while this
            // command was in flight (memory-monitor recycle, idle sweep, user
            // cancel). Its output came from a dead tracer — report the death
            // (exitCode -1) so the coordinator's retry logic rebuilds the
            // shell and re-runs instead of trusting stale output.
            if (genAtStart != generation) {
                return@withContext CommandResult(
                    result?.output.orEmpty(),
                    -1,
                    result?.truncated ?: false,
                )
            }

            if (result == null) {
                // Timeout — cancel pending, but don't kill the shell
                pendingCallback = null
                CommandResult("[Command timed out after ${timeout / 1000}s]", 124)
            } else {
                result
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!isAlive) return
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                for (key in previousKeys - envVars.keys) {
                    writer.write("unset $key\n")
                }
                for ((key, value) in envVars) {
                    // Escape single quotes in values
                    val escaped = value.replace("'", "'\\''")
                    writer.write("export $key='$escaped'\n")
                }
                writer.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /**
     * Stop the persistent shell.
     */
    fun stop() {
        generation++
        try { stdinWriter?.close() } catch (_: Exception) {}
        stdinWriter = null
        val proc = process
        process = null
        if (proc != null) {
            // [P2-pipe-leak] 关闭所有流防止管道泄漏
            try { proc.inputStream.close() } catch (_: Exception) {}
            try { proc.outputStream.close() } catch (_: Exception) {}
            try { proc.errorStream.close() } catch (_: Exception) {}
            // [fix/stop-lag] `destroyForcibly()` only sends SIGKILL — the PRoot
            // tracer's process-tree teardown takes 1–2 s on long tasks. The old
            // code synchronously `waitFor(3s)` here, and this path runs on the
            // UI main thread (cancelStream → stopCurrentCommand → stop), so a
            // user tapping Stop froze for 1–2 s. Now: SIGKILL then return
            // immediately; a daemon reaper does a bounded waitFor to reap the
            // zombie, and readLoop() also clears `process`/`pendingCallback` at
            // EOF, so nothing depends on this wait having happened here.
            proc.destroyForcibly()
            val reaper = Thread({
                try { proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            }, "PersistentShell-reaper")
            reaper.isDaemon = true
            reaper.start()
        }
        pendingCallback?.let {
            it.finishOnce(it.output.toString(), -1, it.truncated)
        }
        pendingCallback = null
        commandCount = 0
        Log.i(TAG, "Persistent shell stopped")
    }
}

/**
 * Pure function: parse the exit code from a `__MINIS_DONE_{marker}_EXIT_{code}__`
 * marker string. Returns -1 when no valid code is found.
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * [PersistentShell] (which depends on `android.content.Context`).
 */
internal fun internalParseMinisExitCode(text: String, marker: String): Int {
    val regex = Regex("__MINIS_DONE_${Regex.escape(marker)}_EXIT_(\\d+)__")
    val match = regex.find(text)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: -1
}

/**
 * Pure function: append [text] to [output], capping at [maxChars]. Returns
 * `true` when truncation occurred (the append was clipped or skipped entirely).
 * The process should continue reading (to avoid pipe back-pressure), but no
 * further text is accumulated.
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * [PersistentShell] (which depends on `android.content.Context`).
 */
internal fun internalTruncateOutput(output: StringBuilder, text: String, maxChars: Int): Boolean {
    val remaining = maxChars - output.length
    if (remaining <= 0) return true
    if (text.length > remaining) {
        output.append(text, 0, remaining)
        return true
    }
    output.append(text)
    return false
}

/**
 * Cross-chunk scan for the completion marker. `text` is the latest chunk
 * read from the shell's stdout; `tail` is the trailing window withheld from
 * the PREVIOUS chunk (it may hold the start of a marker that got split).
 *
 * Hit  → [MarkerScanResult.beforeMarker] = all output before the marker,
 *        [MarkerScanResult.exitCode] = parsed exit code, `keptTail` empty.
 * Miss → [MarkerScanResult.beforeMarker] = output safe to flush now,
 *        [MarkerScanResult.keptTail] = last (markerPattern.length - 1)
 *        chars that could be a split marker prefix (must be re-scanned with
 *        the next chunk).
 *
 * Extracted as a pure function so the split-marker behaviour is JVM-testable
 * without Android deps (see [internalParseMinisExitCode]).
 */
internal data class MarkerScanResult(
    val hit: Boolean,
    val beforeMarker: String,
    val exitCode: Int,
    val keptTail: String,
)

internal fun internalScanMarker(tail: String, text: String, marker: String): MarkerScanResult {
    val markerPattern = "__MINIS_DONE_${marker}_EXIT_"
    val combined = if (tail.isEmpty()) text else tail + text
    val idx = combined.indexOf(markerPattern)
    return if (idx >= 0) {
        MarkerScanResult(
            hit = true,
            beforeMarker = combined.substring(0, idx),
            exitCode = internalParseMinisExitCode(combined.substring(idx), marker),
            keptTail = "",
        )
    } else {
        val keepLen = (markerPattern.length - 1).coerceAtMost(combined.length)
        val flushEnd = combined.length - keepLen
        MarkerScanResult(
            hit = false,
            beforeMarker = combined.substring(0, flushEnd),
            exitCode = -1,
            keptTail = combined.substring(flushEnd),
        )
    }
}
