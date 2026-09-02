package com.openminis.app.sandbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.openminis.app.agent.runtime.CommandFailureKind
import com.openminis.app.agent.runtime.RetryOutcome
import com.openminis.app.agent.runtime.RetryPolicy
import com.openminis.app.agent.runtime.RetrySafety
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.sandbox.offload.ChatStreamOffloadHandler
import com.openminis.app.sandbox.offload.ModelExecutionMailbox
import com.openminis.app.service.MemoryPressureGate
import com.openminis.app.service.MemoryPressureLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */

// [P2-proot-native-leak] High-water mark (MB) for PRoot *child process*
// RSS. Normal operation is ~35-55MB. Referenced by top-level
// childRssHighWaterMarkMB() as the tight-memory baseline.
private const val NATIVE_HEAP_HIGH_WATER_MARK_MB = 256L

// [P2-app-native-oom] High-water mark for app-process native heap
// (Debug.getNativeHeapAllocatedSize). Referenced by top-level
// appNativeHighWaterMarkMB() as the tight-memory baseline.
private const val APP_NATIVE_HEAP_HIGH_WATER_MARK_MB = 120L

// [memory-dynamic-budget] 动态预算。以上固定阈值是「系统内存紧张/未知」
// 时的保守基线（2026-08-12 失控泄漏 25MB→542MB/12s 的防线）。设备实测
// （2026-08-16）MemTotal 11.4GB / 空闲 MemAvailable 5.3GB，正常任务离
// 基线远得很——基线防的是 runaway 泄漏，不是正常大任务。当系统
// MemAvailable ≥ MEM_AVAIL_AMPLE_MB 时动态放宽拒绝级边界，让
// git fetch --unshallow / python 大任务这类 heavy 操作能跑完
// （跑完强制 recycle shell + GC 善后），而不是被阈值误杀。
private const val MEM_AVAIL_AMPLE_MB = 2048L

// [memory-dynamic-budget] 充裕时动态边界：
//   CRITICAL（拒 heavy 起点）  120MB → 512MB
//   LOCKED（全拒最后防线）     350MB → 1536MB
//   app native 回收高水位     120MB → 512MB（与动态 CRITICAL 一致，
//   in-flight 回收与 pre-exec 拒绝口径统一）
private const val CRITICAL_NATIVE_DYNAMIC_MB = 512L
private const val LOCKED_NATIVE_DYNAMIC_MB = 1536L
private const val APP_NATIVE_DYNAMIC_MB = 512L

// [memory-dynamic-budget] 充裕时子进程 RSS 动态高水位（1024MB）。基线
// 256MB 的闸是 `git fetch --unshallow` 类大命令中途被杀的直接原因——
// 子进程 RSS 是 git 内存的真正归属，不是 app native heap。
private const val CHILD_RSS_DYNAMIC_MB = 1024L

// [memory-dynamic-budget] Heavy 命令全局串行闸超时。任何时刻最多 1 个
// heavy 命令在跑（Semaphore(1)），防止多会话同时跑多个大任务把 memcg
// 叠加推爆。超时说明另一 heavy 还在跑——排队而非并发叠加。
private const val HEAVY_GATE_TIMEOUT_MS = 600_000L

object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"

    // [P2-proot-native-leak]
    // High-water mark (MB) for PRoot *child process* RSS. Normal operation is
    // ~35-55MB. A long-lived PRoot tracer leaks native memory monotonically
    // (measured 6.2-6.9GB on 2026-08-07, enough to OOM the device). We read
    // the child process RSS via PersistentShell.nativeRssMB — NOT
    // Debug.getNativeHeapAllocatedSize(), which reports the *app-process*
    // heap and never sees the leaked memory held by the forked PRoot tracer.
    // When a command returns (or while it runs) and the child RSS exceeds
    // this, we recycle the session's shell so the next getOrCreateShell
    // spawns a fresh PRoot at baseline.

    // [P2-global-concurrency] Maximum number of persistent shells that can
    // run commands concurrently across all sessions. 3-4 sessions each
    // running dense tool-call sequences produce ~542MB app native heap in
    // 12s (2026-08-12 crash). Limiting to 2 ensures the combined memory
    // trajectory stays within the 512MB Java heap limit + 120MB native
    // headroom. Excess sessions queue on the Semaphore and get a resource-
    // busy error after 30s.
    // [D-2] default cap, aligned with SessionConcurrencyManager / NativeOffload.
    // Overridden at init() from ConcurrencyPrefs (single shared knob).
    internal const val MAX_CONCURRENT_SHELLS = 2

    // [P2-app-native-hardcap] Hard cap for app-process native heap. When
    // this is exceeded, new commands are rejected immediately (before
    // acquiring the global concurrency slot) to prevent the process from
    // reaching Scudo OOM. The crash case hit 542MB before the recycling
    // mechanism could react — this is a last-line defence.
    private const val APP_NATIVE_HEAP_HARD_CAP_MB = 350L

    // [P2-app-native-oom] Java heap utilization threshold. When the agent
    // runs a dense tool-call sequence, Java heap climbs (crash case:
    // 395MB/512MB = 77%). Recycle the shell at 70% to prevent the
    // NativeAlloc GC storms that precede Scudo OOM.
    private const val JAVA_HEAP_PRESSURE_THRESHOLD = 0.70

    // [P2-app-native-oom] Maximum commands on a single shell before
    // forced recycle. The crash case was ~20 git/shell commands in 2.5
    // minutes. 30 is a safe upper bound — well above normal usage but
    // well below the accumulation that triggers Scudo OOM.
    private const val MAX_COMMANDS_PER_SHELL = 30

    // [shell-generation-scheduler] Progressive-degradation tiers for
    // app-process native heap, below the existing 120MB high-water mark and
    // 350MB hard cap. Instead of riding the heap up to a single cliff, we
    // shrink the shell generation budget and recycle earlier at each tier so
    // the PRoot tracer is shed progressively (crash case 2026-08-12: app
    // native 25MB→542MB in 12s — recycling kicked in too late).
    // Tiers (mirrored in internalDegradationPhase / shouldRecycleByClass):
    //   NORMAL    < 50MB   — full budget (30 commands)
    //   MILD      < 80MB   — budget halves (15)
    //   MODERATE  < 100MB  — budget shrinks hard (5); heavy commands recycle at 80+
    //   SEVERE    < 120MB  — budget 2 (leaky no longer rejected: model calls isolated)
    //   CRITICAL  < 350MB  — budget 1; heavy/leaky rejected up front
    //   LOCKED    ≥ 350MB  — everything rejected (old hard cap)
    // A shell idle this long is terminated to release its PRoot native
    // footprint. Generous above any agent transition gap (model thinking).
    private const val SHELL_IDLE_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min
    // Sweep cadence for idle shell recycling (public for MinisApp sweeper).
    const val IDLE_SWEEP_INTERVAL_MS = 60 * 1000L              // 1 min

    // [P3-shell-auto-retry] At most 2 attempts total (original + 1 retry)
    // before a command is reported as failed. Guards against infinite retry
    // loops.
    internal const val MAX_AUTO_RETRIES = 2

    /**
     * [P3-shell-auto-retry] Pure decision: should the command be re-run on a
     * rebuilt shell? Delegates to the top-level [internalShouldRetryCommand].
     */
    internal fun shouldRetryCommand(exitCode: Int, shellAlive: Boolean, attempt: Int): Boolean {
        return internalShouldRetryCommand(exitCode, shellAlive, attempt, MAX_AUTO_RETRIES)
    }

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean = false,
        // [T3-retry-side-effects] 向后兼容字段（带默认值，不破坏现有调用方）。
        // 结果是否已知。false 表示"命令可能已执行但结果未返回"（OutcomeUnknown），
        // 上层不得据此重跑非幂等命令，也不得把部分结果标记为 completed。
        val outcomeKnown: Boolean = true,
        // [T3-retry-side-effects] 本命令执行时的副作用等级（受信任调用点注入）。
        val retrySafety: RetrySafety = RetrySafety.UNKNOWN,
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()
    // [P2-proot-native-leak] elapsedRealtime of last command completion
    // per session; drives recycleIdleShells.
    private val lastActiveMs = ConcurrentHashMap<String, Long>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    // [P2-global-concurrency] Global semaphore that limits the number of
    // sessions that can execute shell commands concurrently. Acquired before
    // the per-session mutex so that a session waiting for the global slot
    // does not block another session's per-session mutex. Fair ordering
    // prevents starvation of any single session.
    // [D-2] sized at init() from the shared ConcurrencyPrefs cap (kept aligned
    // with SessionConcurrencyManager / NativeOffloadServer). @Volatile because it
    // is replaced once in init(); never resized at runtime.
    @Volatile
    private var globalConcurrency = Semaphore(MAX_CONCURRENT_SHELLS, true)

    /** [D-2] current effective shell-concurrency cap (from shared pref). */
    @Volatile
    private var maxConcurrentShells: Int = MAX_CONCURRENT_SHELLS

    // [memory-dynamic-budget] Heavy 命令全局串行闸（Semaphore(1)）：任何
    // 时刻最多 1 个 heavy 命令在跑。锁序：先 heavyGate 后 globalConcurrency
    // —— 持 heavyGate 者必在 globalConcurrency 队列等待，不会反向等
    // heavyGate，因此无死锁。heavy 命令跑完由 shouldRecycleByClass 强制
    // 回收 shell，不会长期占闸。
    private val heavyGate = Semaphore(1, true)

    fun init(context: Context) {
        appContext = context.applicationContext
        // [D-2] size the shared shell cap from ConcurrencyPrefs. Runs after
        // ConcurrencyPrefs.prime in MinisApp.onCreate, so the effective cap is
        // the user-configured value (default 2), kept aligned with the other
        // two coordinated gates.
        maxConcurrentShells = com.openminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()
        globalConcurrency = Semaphore(maxConcurrentShells, true)
        Log.i(TAG, "shell concurrency cap=$maxConcurrentShells (D-2 parameterized)")
    }

    /**
     * [memory-dynamic-budget] System MemAvailable in MB via ActivityManager.
     * Returns 0 (conservative baseline) on any failure — unknown memory is
     * treated as tight, never as ample.
     */
    private fun systemMemAvailableMB(): Long {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem / (1024L * 1024L)
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 0. Pre-execution hard cap check (reject if native heap too high)
     * 1. Acquire global concurrency Semaphore (limit cross-session parallelism)
     * 2. Get or create per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     * 5. Release global concurrency Semaphore in finally
     * 6. Post-execution memory pressure check + GC recovery
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // [native-rss-tool-guard] Process-RSS hard gate BEFORE any shell work.
        // Debug.getNativeHeapAllocatedSize() is blind to mmap/thread-stack/mapped
        // tmpfile growth — the exact shape of the 2026-08-19 crash (RSS 5.8–6.0GB
        // while the native-heap-only tiers stayed below their cliffs). Read real
        // VmRSS: if it is already CRITICAL, reclaim once, then re-check; if still
        // critical, reject the command with a retryable, structured error instead
        // of letting one more heavy command push the process over the edge.
        val rssBeforeMB = MemoryPressureGate.rssReader()
        if (MemoryPressureGate.levelFor(rssBeforeMB) == MemoryPressureLevel.CRITICAL) {
            MemoryPressureGate.reclaimAndWait(waitMs = 2_000L)
            val rssAfterMB = MemoryPressureGate.rssReader()
            if (MemoryPressureGate.shouldRejectAfterReclaim(rssAfterMB)) {
                Log.w(TAG, "[$sessionId] Process RSS ${rssAfterMB}MB still critical after reclaim — rejecting command (rssBefore=${rssBeforeMB}MB)")
                return CommandResult(
                    "[System busy: process memory is critically high (${rssAfterMB}MB). " +
                        "Please wait for the system to settle and retry.]",
                    -1, 0, true,
                )
            }
        }

        // [shell-generation-scheduler] Progressive pre-execution gate.
        // Instead of a single 350MB cliff that freezes ALL commands (even
        // `true`), degrade by tier: at CRITICAL (≥120MB) block only heavy
        // and leaky commands — lightweight ones (ls/file_read) still work so
        // the agent can self-recover; only at LOCKED (≥350MB) is everything
        // rejected, same as the old hard cap. SEVERE (≥100MB) no longer
        // rejects LEAKY: model calls run in the isolated :modelservice
        // process, so they no longer bloat this process's native heap.
        // Run GC before returning so the caller's next retry has a better
        // chance.
        val preExecNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        // [memory-dynamic-budget] System MemAvailable drives the dynamic
        // boundaries below: ample memory raises the rejection thresholds so
        // heavy commands are not killed by the conservative baseline.
        val memAvailableMB = systemMemAvailableMB()
        val preExecPhase = internalDegradationPhase(preExecNativeMB, memAvailableMB)
        val cmdClass = classifyCommand(command)
        val preExecReject = preExecRejectionMessage(preExecPhase, cmdClass, preExecNativeMB, memAvailableMB)
        if (preExecReject != null) {
            Log.w(TAG, "[$sessionId] Phase $preExecPhase: rejecting $cmdClass command (native ${preExecNativeMB}MB, memAvail ${memAvailableMB}MB)")
            postRecycleMemoryRecovery()
            return CommandResult(preExecReject, -1, 0, true)
        }

        // [memory-dynamic-budget] Heavy 全局串行闸：先拿 heavyGate 再拿
        // globalConcurrency（锁序一致无死锁）。等待超时（10min）说明另一
        // heavy 还在跑——排队而非并发叠加，防止多会话大任务把 memcg 推爆。
        val heavyGateAcquired = cmdClass == CommandClass.HEAVY &&
            heavyGate.tryAcquire(HEAVY_GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (cmdClass == CommandClass.HEAVY && !heavyGateAcquired) {
            Log.w(TAG, "[$sessionId] Heavy gate timeout after ${HEAVY_GATE_TIMEOUT_MS}ms — another heavy command still running")
            postRecycleMemoryRecovery()
            return CommandResult(
                "[System busy: another heavy command is still running. " +
                    "Please wait for it to finish and retry.]",
                -1, 0, true
            )
        }

        // [P2-global-concurrency] Acquire the global shell slot. If all
        // slots are occupied by other sessions, wait up to 60s before
        // giving up — this prevents a third concurrent session from
        // pushing the combined memory footprint past Scudo's limit.
        val acquired = globalConcurrency.tryAcquire(60, TimeUnit.SECONDS)
        if (!acquired) {
            if (heavyGateAcquired) heavyGate.release()
            Log.w(TAG, "[$sessionId] Global concurrency slot timeout after 60s — rejecting command")
            postRecycleMemoryRecovery()
            return CommandResult(
                "[System busy: too many concurrent sessions (limit $maxConcurrentShells). " +
                    "Please wait for other sessions to complete and retry.]",
                -1, 0, true
            )
        }
        try {
            // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
            val mutex = mutexes.getOrPut(sessionId) { Mutex() }

            return mutex.withLock {
                val startTime = System.currentTimeMillis()

                // Auto-boot PRoot if not already booted
                if (!PRootKernel.isBooted) {
                    Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                    PRootKernel.boot(appContext)
                }

            // [P3-shell-auto-retry] Execute with one automatic retry: if the
            // PRoot shell process itself died mid-command (HyperOS silent_kill,
            // tracer OOM, idle-recycle race), rebuild the shell and re-run the
            // command. At most 2 attempts total — guards against infinite retry
            // loops. The agent/user sees a single successful result for
            // transient infra failures.
            val (result, shell) = executeWithShellRetry(
                sessionId = sessionId,
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(result.output)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            // Combine host-side and shell-side truncation flags
            val outputTruncated = result.truncated || truncated != sanitized
            val output = if (result.exitCode != 0 && result.exitCode != 124) {
                "$truncated\n(exit code: ${result.exitCode})"
            } else {
                truncated
            }

            // [P2-proot-native-leak] mark active; recycle if the PRoot *child
            // process* RSS ballooned past the safe ceiling (tracer leak). This
            // reads the real child (PersistentShell.nativeRssMB) — NOT app
            // Debug.getNativeHeapAllocatedSize(), which misses the leak.
            lastActiveMs[sessionId] = SystemClock.elapsedRealtime()
            val prootRssMB = shell?.nativeRssMB() ?: 0L
            // [memory-dynamic-budget] Child RSS ceiling is dynamic — ample
            // system memory lets a large git/python task complete instead of
            // being recycled mid-run by the 256MB baseline mark.
            val childRssMark = childRssHighWaterMarkMB(systemMemAvailableMB())
            if (prootRssMB > childRssMark) {
                Log.w(TAG, "[$sessionId] PRoot child RSS ${prootRssMB}MB > ${childRssMark}MB after command — recycling PRoot shell")
                sessionDidTerminate(sessionId)
            } else {
                Log.d(TAG, "[$sessionId] PRoot child RSS ${prootRssMB}MB — within mark ${childRssMark}MB")
            }

            // [P2-app-native-oom] Post-command pressure check. The PRoot child
            // RSS monitor above is blind to app-process native heap growth
            // (crash case 2026-08-09: child RSS constant 3MB while app native
            // heap + Java heap climbed to Scudo OOM in 12s of NativeAlloc GC
            // storms). Recycle on any of: app native heap > 200MB, Java heap
            // utilization > 70%, or > MAX_COMMANDS_PER_SHELL commands on one
            // shell. Recycling tears down the PRoot tracer so its in-process
            // talloc/mmap reservations are returned to the OS, restoring
            // memory to baseline for the next command.
            val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val runtime = Runtime.getRuntime()
            val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
            val javaHeapFrac = if (runtime.maxMemory() > 0L) javaHeapUsed.toDouble() / runtime.maxMemory().toDouble() else 0.0

            // [memory-dynamic-budget] Sample MemAvailable once for the
            // post-exec checks so the dynamic marks in the log and the
            // decision agree.
            val memNow = systemMemAvailableMB()
            val nativeOversized = nativeHeapMB > appNativeHighWaterMarkMB(memNow)
            val javaPressured = javaHeapFrac > JAVA_HEAP_PRESSURE_THRESHOLD
            // [shell-generation-scheduler] Generation budget shrinks with
            // memory pressure (30→15→5→2→{8 light / 1 heavy-leaky}), so a
            // dense tool-call sequence sheds the PRoot tracer progressively
            // instead of riding it up to the 256MB RSS / 350MB hard cap
            // (crash case 2026-08-12). Light commands keep a relaxed floor:
            // they never fork, so recycling after each one only wastes ~200ms.
            val generationBudget = internalGenerationBudget(nativeHeapMB, cmdClass)
            val cmdOverLimit = (shell?.commandCount ?: 0) >= generationBudget
            // [shell-generation-scheduler] Command-class-aware recycling:
            // known-leaky commands (minis-model-use) always recycle the shell
            // after completing; heavy ones (python3/apk/pip) recycle under
            // memory pressure; lightweight ones never recycle by class (the
            // generation budget still caps them).
            val cmdClassAtExec = classifyCommand(command)
            val recycleByClass = shouldRecycleByClass(cmdClassAtExec, nativeHeapMB)

            when {
                nativeOversized -> Log.w(
                    TAG, "[$sessionId] App native heap ${nativeHeapMB}MB > ${appNativeHighWaterMarkMB(memNow)}MB — recycling shell"
                )
                javaPressured -> Log.w(
                    TAG, "[$sessionId] Java heap ${(javaHeapFrac * 100).toInt()}% > ${(JAVA_HEAP_PRESSURE_THRESHOLD * 100).toInt()}% — recycling shell"
                )
                cmdOverLimit -> Log.w(
                    TAG, "[$sessionId] Shell command count ${shell?.commandCount ?: 0} >= budget $generationBudget (native ${nativeHeapMB}MB) — recycling shell"
                )
                recycleByClass && cmdClassAtExec == CommandClass.LEAKY -> Log.w(
                    TAG, "[$sessionId] Leaky command class executed — recycling shell (native ${nativeHeapMB}MB)"
                )
                recycleByClass && cmdClassAtExec == CommandClass.HEAVY -> Log.w(
                    TAG, "[$sessionId] Heavy command under pressure (native ${nativeHeapMB}MB) — recycling shell"
                )
            }
            if (nativeOversized || javaPressured || cmdOverLimit || recycleByClass) {
                sessionDidTerminate(sessionId)
            }

            CommandResult(output = output, exitCode = result.exitCode, durationMs = durationMs, truncated = outputTruncated)
        }
    } finally {
        // [P2-global-concurrency] Release the global concurrency slot.
        // This runs after the per-session mutex is released (the mutex is
        // inside the try block), so the next session waiting on the
        // Semaphore does not contend with the just-finished session's
        // mutex cleanup.
        globalConcurrency.release()
        // [memory-dynamic-budget] Release the heavy serialization gate if
        // this command held it.
        if (heavyGateAcquired) heavyGate.release()
    }
    }

    /**
     * [P3-shell-auto-retry] Execute a command in the session's persistent shell
     * with ONE automatic retry when the shell process itself died mid-command
     * (exitCode == -1 from PersistentShell.readLoop, or the process is no longer
     * alive). The crash cases: HyperOS silent_kill, PRoot tracer OOM,
     * idle-recycle race. Rebuilding the shell and re-running the command
     * transparently heals all of these; the agent sees a single successful result.
     *
     * At most 2 attempts total — the loop immediately exits on success (exitCode
     * != -1 with alive shell) or after the second attempt, so infinite retry
     * loops are impossible.
     */
    private suspend fun executeWithShellRetry(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
    ): Pair<CommandResult, PersistentShell?> {
        var attempt = 0
        var lastShell: PersistentShell? = null
        while (true) {
            attempt++
            val shell = getOrCreateShell(sessionId)
            lastShell = shell

            // Inject environment variables as a full snapshot (T124a).
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            val result = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
                memoryMonitor = { rssMB ->
                    // [memory-dynamic-budget] Both marks are dynamic: the
                    // child RSS ceiling and the app-native recycling point
                    // scale with system MemAvailable.
                    val memNow = systemMemAvailableMB()
                    midCommandRecycleIfOversized(shell, sessionId, rssMB, childRssHighWaterMarkMB(memNow))
                    val appNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                    if (appNativeMB > appNativeHighWaterMarkMB(memNow)) {
                        Log.w(TAG, "[$sessionId] App native heap ${appNativeMB}MB crossed mark in-flight — recycling shell")
                        sessionDidTerminate(sessionId)
                    }
                },
            )

            // Shell died or timed out — rebuild once and retry.
            // Timeout (124) can leave zombie processes in the PRoot tracer;
            // rebuilding the shell is safer than leaving a dead shell around.
            val shellDied = result.exitCode == -1 || result.exitCode == 124 || !shell.isAlive
            if (!shouldRetryCommand(result.exitCode, shell.isAlive, attempt)) {
                // [audit-RC7] Timeout must ALWAYS reclaim the shell, even on
                // the final attempt. withTimeoutOrNull only cancels the
                // coroutine — the command keeps running inside the PTY with
                // pendingCallback already nulled, so its late output (and the
                // stale __MINIS_DONE_<marker>__ line) would be scanned into
                // the NEXT command's output on this shell. Killing the shell
                // here guarantees the invariant: exitCode==124 ⇒ no orphan
                // command, no cross-command output pollution.
                if (internalShouldReclaimOnExhaustedTimeout(result.exitCode)) {
                    Log.w(
                        TAG,
                        "[$sessionId] Command timed out with retries exhausted — " +
                            "reclaiming shell to kill the orphaned command"
                    )
                    sessionDidTerminate(sessionId)
                    lastShell = null
                } else if (shellDied && attempt >= MAX_AUTO_RETRIES) {
                    lastShell = null // shell is dead and we're out of retries
                }
                return CommandResult(output = result.output, exitCode = result.exitCode, durationMs = 0L, truncated = result.truncated) to lastShell
            }

            Log.w(
                TAG,
                "[$sessionId] Shell died mid-command (exit=${result.exitCode}, alive=${shell.isAlive}) " +
                    "— rebuilding and retrying (attempt $attempt/2)"
            )
            sessionDidTerminate(sessionId)
        }
    }

    /** [P2-proot-native-leak] If the PRoot child is already dead mid-command
     * (it OOM'd), immediately recycle the session so the shell isn't held as
     * a zombie and the next command spawns fresh. Called from the executeCommand
     * in-flight monitor; rssMB of 0 means the child already died. The ceiling
     * is dynamic ([memory-dynamic-budget]) — passed in by the caller. */
    private fun midCommandRecycleIfOversized(shell: PersistentShell, sessionId: String, rssMB: Long, childRssMarkMB: Long) {
        if (rssMB > childRssMarkMB) {
            Log.w(TAG, "[$sessionId] PRoot child RSS ${rssMB}MB crossed mark ${childRssMarkMB}MB mid-command — recycling")
            sessionDidTerminate(sessionId)
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.d(TAG, "[$sessionId] Reusing existing shell")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.d(TAG, "[$sessionId] Reusing existing shell (post-lock)")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell died unexpectedly, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // Session-specific directories — written ONLY into this shell's local
        // bind-mount map, never the global PRootKernel.bindMounts. The global
        // map is shared across all sessions; per-session host paths would
        // otherwise overwrite each other (last session to boot wins), and the
        // interactive terminal (which reads the global map) would point at the
        // wrong session's dirs. Callers that need a session's per-session dir
        // must go through PRootKernel.resolveSessionHostPath(sessionId, ...).
        val sessionBase = File(filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
        }

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // [T-logs-bind-android] AppLogger writes daily logs to files/logs/
        // (minis-YYYY-MM-DD.log). Bind them into /var/minis/logs so the agent's
        // shell can read the app's own runtime logs directly instead of
        // requiring a manual share from LogManagementScreen. Host dir is
        // guaranteed to exist: AppLogger.init() mkdirs it in Application.onCreate.
        val logsDir = File(filesDir, "logs").also { it.mkdirs() }
        val logsLinuxPath = "/var/minis/logs"
        mounts[logsLinuxPath] = logsDir.absolutePath
        PRootKernel.addBindMount(logsLinuxPath, logsDir.absolutePath)

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell, then
     * triggers post-recycle memory recovery to release app-process native
     * heap that the shell recycling didn't free.
     */
    fun sessionDidTerminate(sessionId: String) {
        val shell = shells.remove(sessionId)
        mutexes.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        lastActiveMs.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
        // [P2-app-native-oom] Recycle-driven GC: the shell is dead but app
        // process native heap (DirectByteBuffers, LOS objects, talloc) is
        // still held. Trigger GC + wait for it to settle so the next command
        // starts with a lower baseline.
        postRecycleMemoryRecovery()
    }

    /**
     * [P2-app-native-oom] Trigger garbage collection and log the memory
     * released. The 2026-08-12 crash showed that recycling the PRoot shell
     * alone does NOT release the app process's own native heap (the actual
     * OOM source). This forces the JVM GC to run and reports how much was
     * freed, so the next command starts from a lower baseline.
     *
     * System.gc() + Runtime.getRuntime().gc() are both hints; on Android
     * they trigger a concurrent GC. We also read Debug.getNativeHeap-
     * AllocatedSize() before/after to log the effect. This is cheap enough
     * to call on recycle and on pre-execution hard-cap rejection.
     *
     * [P2-app-native-reclaim-verify] Bounded iterative GC rounds (up to 3)
     * instead of a single shot, with a longer settle between rounds so the
     * concurrent GC's reference queue (DirectByteBuffer cleaners) has time to
     * drain. Each round measures actual freed bytes; the loop stops once the
     * round budget is exhausted or native heap drops below a safe floor.
     * This directly addresses the "Post-recycle GC freed 0MB — session locked
     * forever" failure mode (2026-08-12, 5.5GB app native heap).
     */
    private fun postRecycleMemoryRecovery() {
        if (!::appContext.isInitialized) return
        try {
            val runtime = Runtime.getRuntime()
            val startingNative = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
            val startingJava = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
            // [P2-app-native-reclaim-verify] Bounded multi-round GC loop.
            // Each round fires GC hints + settles so the reference queue has
            // time to drain (DirectByteBuffer cleaners run on background threads).
            // Exits early when a round actually frees meaningful memory.
            val maxRounds = 3
            var roundsUsed = 0
            var cumulativeFreedMb = 0L
            var nativeNow = startingNative
            var javaNow = startingJava
            // freedThisRound is hoisted out of the loop body so the do-while
            // condition can reference it (a loop condition cannot see locals
            // declared inside the body).
            var freedThisRoundMb = 0L
            // Floor: once native heap drops below 120MB (tight baseline), stop.
            val floor = 120L
            do {
                roundsUsed++
                val beforeRoundNative = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                val beforeRoundJava = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                System.gc()
                Runtime.getRuntime().gc()
                // [P2-app-native-reclaim-verify] Longer settle (120ms vs 50ms)
                // so the concurrent GC's reference queue has time to drain
                // DirectByteBuffer cleaners before the next measurement.
                Thread.sleep(120)
                val afterRoundNative = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                val afterRoundJava = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                freedThisRoundMb = beforeRoundNative - afterRoundNative
                cumulativeFreedMb += freedThisRoundMb
                nativeNow = afterRoundNative
                javaNow = afterRoundJava
                Log.w(TAG, "GC round $roundsUsed/$maxRounds: native ${beforeRoundNative}→${afterRoundNative}MB " +
                    "(freed ${freedThisRoundMb} MB, cumulative $cumulativeFreedMb MB), " +
                    "java ${beforeRoundJava}→${afterRoundJava}MB")
            } while (shouldContinueNativeReclaim(
                freedThisRoundMb = freedThisRoundMb,
                roundsUsed = roundsUsed,
                maxRounds = maxRounds,
                nativeNowMb = nativeNow,
                lockedFloorMb = floor,
            ))
            Log.w(TAG, "Post-recycle GC: native ${startingNative}→${nativeNow}MB (freed ${startingNative - nativeNow}MB), " +
                "java ${startingJava}→${javaNow}MB (freed ${startingJava - javaNow}MB), rounds=$roundsUsed")
            // [direction-A step4] Pressure reclaim: if the app native heap is still
            // at/above the LOCKED floor after GC, kill the :modelservice process so the
            // DirectByteBuffer/streaming native held there is returned to the OS. This
            // keeps offloaded chat streaming from pushing the app to the lock point;
            // the next offload startService restarts the service fresh.
            maybeReclaimModelService(nativeNowMb = nativeNow)
        } catch (_: Throwable) {
            // Never let memory recovery break the calling flow.
        }
    }

    /**
     * [direction-A step4] Ask the :modelservice process to drain+die when the
     * app native heap is still above the locked floor after GC. Unlike the old
     * `stopService` (which killed the process out from under an in-flight
     * stream and was reverted), TF-B uses a file-based shutdown REQUEST: the
     * worker checks it via [ModelExecutionMailbox] and only kills itself after
     * confirming quiescence (active==0, queue==0, no un-acked response, stream
     * flushed). The request is written into the staging root; the worker
     * re-checks on each request finish. A new request that arrives after the
     * request simply revives the worker (onStartCommand → ACTIVE).
     */
    @SuppressLint("MissingPermission")
    private fun maybeReclaimModelService(nativeNowMb: Long) {
        if (!::appContext.isInitialized) return
        // Only reclaim when the app is itself in the locked/pressure band; don't
        // kill the service on every idle GC (level 0/1 are normal operation).
        val memAvailMb = systemMemAvailableMB()
        val phase = internalDegradationPhase(nativeNowMb, memAvailMb)
        if (phase.ordinal < ShellPhase.CRITICAL.ordinal) return // not pressurized enough
        // [B2] Never sever an in-flight stream. If a chat stream is actively being
        // offloaded to :modelservice right now, killing the service would leave
        // stream.jsonl truncated (no DONE, no error) and the UI silently stalled
        // until the poll timeout. Skip the shutdown request and let the next
        // non-streaming pressure tick handle it.
        if (ChatStreamOffloadHandler.activeStreams > 0) {
            Log.w(TAG, "[direction-A] skipped reclaim: active stream in progress " +
                "(activeStreams=${ChatStreamOffloadHandler.activeStreams}, native ${nativeNowMb}MB)")
            return
        }
        try {
            // Write the shutdown REQUEST into the staging root; the worker
            // drains and kills itself when quiescent. Never stopService — the
            // worker owns its own death timing.
            val root = File(appContext.cacheDir, "model-exec")
            root.mkdirs()
            ModelExecutionMailbox.writeShutdownRequest(root)
            Log.w(TAG, "[direction-A] shutdown REQUESTED for :modelservice (native still ${nativeNowMb}MB, phase $phase) — " +
                "worker self-reaps when quiescent")
        } catch (e: Throwable) {
            Log.w(TAG, "[direction-A] shutdown request write failed", e)
        }
    }

    /**
     * [P2-proot-native-leak] Recycle shells idle past SHELL_IDLE_TIMEOUT_MS.
     * Long-lived PRoot shells leak native memory monotonically; terminating
     * idle ones releases their footprint. Next command re-spawns fresh.
     */
    fun recycleIdleShells() {
        val now = SystemClock.elapsedRealtime()
        for (sessionId in shells.keys) {
            val last = lastActiveMs[sessionId] ?: 0L
            if (last != 0L && (now - last) > SHELL_IDLE_TIMEOUT_MS) {
                Log.w(TAG, "[$sessionId] shell idle ${(now - last) / 1000}s — recycling")
                sessionDidTerminate(sessionId)
            }
        }
    }

    /**
     * [P2-proot-resource-hygiene] Clear accumulated junk from the PROOT_TMP_DIR
     * cache and the guest rootfs temp dirs. PRoot writes transient files
     * (loader cache, temp scratch) under app cache; apk/busybox and command
     * output also accumulate in rootfs /tmp and /var/tmp. Over weeks these grow
     * and push disk pressure / IO overhead (the "use it a long time → flash
     * crash" class of report). Mirrors RikkaHub's cleanupAllTempDirs, which runs
     * after each command — here we sweep on a low cadence instead so we never
     * delete a file a *running* command still needs.
     *
     * Only runs when NO shell is mid-command, to avoid racing an in-flight
     * command's temp files (they get reaped on the next sweep).
     */
    fun cleanupProotTmp() {
        // Skip entirely if any shell is alive and possibly executing — safest
        // and sufficient given the sweeper runs every minute.
        if (shells.values.any { it.isAlive }) return
        if (!::appContext.isInitialized) return
        val ctx = appContext
        val tmpDir = PRootKernel.getProotTmpDir(ctx)
        try {
            var removed = 0L
            tmpDir.listFiles()?.forEach { f ->
                if (f.deleteRecursively()) removed++
            }
            if (removed > 0) {
                Log.i(TAG, "cleanupProotTmp: cleared $removed entries from ${tmpDir.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupProotTmp failed: ${t.message}")
        }
        // TF-G P1-2: opportunistic orphan run-dir reaper (same low cadence as
        // the tmp sweeper). Removes only terminal + worker-dead + aged model-
        // exec run dirs that the stream/dispatch finally left behind (crashed
        // client / lost ack). Conservative — never touches an active run.
        runCatching {
            val reaped = com.openminis.app.sandbox.offload.ModelExecutionOrphanReaper
                .reapOrphans(ctx)
            if (reaped > 0) {
                Log.i(TAG, "orphan model-exec reaper: reclaimed $reaped run dir(s)")
            }
        }
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            val shell = shells.remove(sessionId)
            // T124a: snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(sessionId)
            lastActiveMs.remove(sessionId)
            // [fix/audit-s4l2] sessionDidTerminate() also removes the session
            // mutex; this stop path did not, so the mutex map grew forever on
            // sessions stopped (not terminated). Keep the two paths consistent.
            mutexes.remove(sessionId)
            shell?.stop()
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            lastActiveMs.clear()
            // [fix/audit-s4l2] see above: clear the per-session mutexes too.
            mutexes.clear()
            @Suppress("DEPRECATION")
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}

/**
 * [P3-shell-auto-retry] Pure decision: should the command be re-run on a
 * rebuilt shell? True only when the shell process died mid-command —
 * HyperOS silent_kill, PRoot tracer OOM, idle-recycle race — or the
 * command timed out (124, which can leave zombie processes in the PRoot
 * tracer), AND we haven't exhausted [maxRetries]. A normal non-zero
 * exit (script error) or a live shell never triggers a retry.
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * the [ExecutionCoordinator] object (which depends on `android.content.Context`
 * and `android.os.Debug`).
 */
internal fun internalShouldRetryCommand(
    exitCode: Int,
    shellAlive: Boolean,
    attempt: Int,
    // Keep in sync with ExecutionCoordinator.MAX_AUTO_RETRIES (defaults to 2).
    maxRetries: Int = 2,
): Boolean {
    val shellDied = exitCode == -1 || exitCode == 124 || !shellAlive
    return shellDied && attempt < maxRetries
}

/**
 * [audit-RC7] Pure decision: when the retry budget is EXHAUSTED, must the
 * shell still be reclaimed? True only for timeout (124). With timeout,
 * `withTimeoutOrNull` merely cancels the coroutine — the command itself
 * keeps running inside the PTY with `pendingCallback` already nulled, so
 * its late output (including the stale `__MINIS_DONE_<marker>__` line)
 * would be scanned into the NEXT command's output on the same shell
 * (`readLoop` accumulates unmatched text once a new callback is set).
 * Reclaiming the shell kills the orphaned command and guarantees the
 * invariant: exitCode==124 ⇒ no cross-command output pollution.
 *
 * Other death kinds (-1 / dead shell) leave nothing running in the PTY;
 * the pre-existing dead-shell path already handles those without this.
 */
internal fun internalShouldReclaimOnExhaustedTimeout(exitCode: Int): Boolean = exitCode == 124

// ──────────────────────────────────────────────────────────────────────────
// [T3-retry-side-effects] Side-effect-aware retry policy — pure functions
//
// These extend the legacy internalShouldRetryCommand above with the
// RetrySafety model (agent/runtime/RetrySafety.kt + RetryPolicy.kt). The
// legacy function is kept untouched so existing call sites and tests keep
// their semantics; production wiring of the new decision happens in T7.
// ──────────────────────────────────────────────────────────────────────────

/**
 * [T3-retry-side-effects] Classify raw command signals into a
 * [CommandFailureKind]. Returns `null` when the command completed
 * successfully with known output (no retry consideration needed).
 *
 * Order matters:
 * - `exitCode == -1`   → SHELL_DIED (readLoop saw the process exit);
 * - `exitCode == 124`  → TIMEOUT (may have left zombies);
 * - truncated + alive  → OUTPUT_TRUNCATED — the command RAN, its side
 *   effects happened, only the output was cut. Must never be misread as
 *   "command did not execute";
 * - `!shellAlive`      → RESULT_LOST — output was produced but cannot be
 *   trusted as complete (shell died before returning);
 * - `exitCode != 0`    → NON_ZERO_EXIT (script error, command not found…).
 */
internal fun internalClassifyShellFailure(
    exitCode: Int,
    shellAlive: Boolean,
    truncated: Boolean,
): CommandFailureKind? = when {
    exitCode == -1 -> CommandFailureKind.SHELL_DIED
    exitCode == 124 -> CommandFailureKind.TIMEOUT
    // Alive + non-zero exit: the command RAN and failed (script error,
    // command not found…). Checked before truncation so a failed command
    // with truncated output is not misread as a mere truncation.
    exitCode != 0 && shellAlive -> CommandFailureKind.NON_ZERO_EXIT
    // Alive + exit 0 + truncated: the command RAN (side effects happened),
    // only the output was cut. Must never be misread as "not executed".
    truncated && shellAlive -> CommandFailureKind.OUTPUT_TRUNCATED
    // Shell died without -1/124 (e.g. exit 0 or 130): output was produced
    // but cannot be trusted as complete.
    !shellAlive -> CommandFailureKind.RESULT_LOST
    else -> null
}

/**
 * [T3-retry-side-effects] Side-effect-aware retry decision for a shell
 * command. Wraps [com.openminis.app.agent.runtime.RetryPolicy.decideRetry]
 * with the raw-signal classification above so it can be JVM-tested without
 * Android dependencies.
 *
 * Returns [RetryOutcome.DoNotRetry] for completed-but-failed commands,
 * [RetryOutcome.OutcomeUnknown] for side-effect-possible failures that must
 * NOT be transparently re-run (UNKNOWN / NON_IDEMPOTENT_WRITE), and
 * [RetryOutcome.SafeToRetry] only for READ_ONLY (or IDEMPOTENT_WRITE with
 * verification) within budget.
 */
internal fun internalDecideShellRetry(
    exitCode: Int,
    shellAlive: Boolean,
    truncated: Boolean,
    attempt: Int,
    safety: RetrySafety,
    maxRetries: Int = 2,
    hasVerification: Boolean = false,
): RetryOutcome {
    val failure = internalClassifyShellFailure(exitCode, shellAlive, truncated)
        ?: return RetryOutcome.DoNotRetry // success with known output
    return RetryPolicy.decideRetry(
        safety = safety,
        failure = failure,
        attempt = attempt,
        maxRetries = maxRetries,
        hasVerification = hasVerification,
    )
}

// ──────────────────────────────────────────────────────────────────────────
// [shell-generation-scheduler] Shell generation scheduler — pure functions
//
// Progressive-degradation model borrowed from connection scheduers that run
// under hard resource ceilings (CF Workers VPNs): explicit per-tier budgets
// instead of a single failure cliff. All functions below are pure (no
// Context / Debug / Process deps) and JVM-testable, mirroring
// internalShouldRetryCommand / internalParseMinisExitCode.
// ──────────────────────────────────────────────────────────────────────────

/**
 * Shell generation phase derived from app-process native heap (MB).
 * Thresholds match the tier table in ExecutionCoordinator comments:
 *   NORMAL    < 50MB   — full budget
 *   MILD      < 80MB   — budget halves
 *   MODERATE  < 100MB  — budget shrinks hard
 *   SEVERE    < 100MB+ — budget 2; no up-front class rejection
 *   CRITICAL  ≥ 120MB (baseline) / ≥ 512MB (ample) — heavy/leaky rejected,
 *             except heavy is allowed when memory is ample (heavy channel)
 *   LOCKED    ≥ 350MB (baseline) / ≥ 1536MB (ample) — everything rejected
 *
 * [memory-dynamic-budget] [memAvailableMB] (MB, default 0 = unknown/tight)
 * scales only the two REJECTION boundaries (CRITICAL/LOCKED) — the lower
 * tiers stay fixed so leak containment never relaxes. Ample (≥
 * MEM_AVAIL_AMPLE_MB) raises the boundaries so legitimate heavy commands
 * survive; tight keeps the conservative baseline.
 */
internal enum class ShellPhase { NORMAL, MILD, MODERATE, SEVERE, CRITICAL, LOCKED }

internal fun internalDegradationPhase(nativeMB: Long, memAvailableMB: Long = 0L): ShellPhase {
    val ample = memAvailableMB >= MEM_AVAIL_AMPLE_MB
    val lockedAt = if (ample) LOCKED_NATIVE_DYNAMIC_MB else 350L
    val criticalAt = if (ample) CRITICAL_NATIVE_DYNAMIC_MB else 120L
    return when {
        nativeMB >= lockedAt -> ShellPhase.LOCKED
        nativeMB >= criticalAt -> ShellPhase.CRITICAL
        nativeMB >= 100L -> ShellPhase.SEVERE
        nativeMB >= 80L -> ShellPhase.MODERATE
        nativeMB >= 50L -> ShellPhase.MILD
        else -> ShellPhase.NORMAL
    }
}

/**
 * [memory-dynamic-budget] Dynamic high-water mark for PRoot *child* process
 * RSS — the signal that actually kills `git fetch --unshallow`-style large
 * commands (their memory lives in the child, not the app native heap).
 * Scaled to system MemAvailable: baseline 256MB when tight, up to 1536MB
 * when ample, so a large task can complete instead of being recycled
 * mid-run.
 */
internal fun childRssHighWaterMarkMB(memAvailableMB: Long): Long {
    return when {
        memAvailableMB >= 4096L -> 1536L
        memAvailableMB >= MEM_AVAIL_AMPLE_MB -> CHILD_RSS_DYNAMIC_MB
        memAvailableMB >= 1024L -> 512L
        else -> NATIVE_HEAP_HIGH_WATER_MARK_MB
    }
}

/**
 * [memory-dynamic-budget] Dynamic high-water mark for app-process native
 * heap (Debug.getNativeHeapAllocatedSize). Baseline 120MB when tight; 512MB
 * when MemAvailable is ample — mirrors the dynamic CRITICAL boundary so
 * in-flight recycling and pre-exec rejection agree.
 */
internal fun appNativeHighWaterMarkMB(memAvailableMB: Long): Long {
    return if (memAvailableMB >= MEM_AVAIL_AMPLE_MB) APP_NATIVE_DYNAMIC_MB else APP_NATIVE_HEAP_HIGH_WATER_MARK_MB
}

/**
 * Command resource class. LEAKY = known native-heap leakers that should
 * never ride a shared shell (recycle unconditionally). HEAVY = commands
 * that grow the PRoot tracer / allocate real memory (recycle under
 * pressure). LIGHT = everything else (ls/cat/grep/file ops — recycle only
 * by budget or memory thresholds, never by class).
 */
internal enum class CommandClass { LIGHT, HEAVY, LEAKY }

internal fun classifyCommand(command: String): CommandClass {
    val trimmed = command.trimStart()
    return when {
        // minis-model-use spawns in-process LLM calls whose DirectByteBuffer
        // allocations live in the app native heap, invisible to GC — always
        // isolate it from shared shells.
        trimmed.startsWith("minis-model-use") -> CommandClass.LEAKY
        trimmed.startsWith("python3") || trimmed.startsWith("python ") ||
            trimmed.startsWith("apk ") || trimmed == "apk" ||
            trimmed.startsWith("pip ") || trimmed.startsWith("pip3 ") ||
            trimmed.startsWith("npm ") || trimmed.startsWith("go ") ||
            trimmed.startsWith("cargo ") || trimmed.startsWith("node ") ->
            CommandClass.HEAVY
        else -> CommandClass.LIGHT
    }
}

/**
 * Command-class recycle decision. LEAKY always recycles (leak containment);
 * HEAVY recycles only when app native heap is above [heavyRecycleThresholdMB]
 * (default 80MB — matches HEAVY_CMD_RECYCLE_NATIVE_MB); LIGHT never recycles
 * by class (the generation budget still caps it).
 */
internal fun shouldRecycleByClass(
    cmdClass: CommandClass,
    nativeMB: Long,
    heavyRecycleThresholdMB: Long = 80L,
): Boolean {
    return when (cmdClass) {
        CommandClass.LEAKY -> true
        CommandClass.HEAVY -> nativeMB > heavyRecycleThresholdMB
        CommandClass.LIGHT -> false
    }
}

/**
 * Shell generation budget (max commands before forced recycle), shrinking
 * as app native heap climbs. At ≥120MB heavy/leaky commands recycle after
 * every execution (budget 1) to shed the PRoot tracer, but lightweight
 * commands (ls/cat/grep — no fork, no tracer growth) keep a relaxed budget
 * of 8: recycling a shell after a pure read costs ~200ms per command with
 * zero memory benefit. The class-aware recycle rule still bounds LIGHT
 * shells eventually (budget 8) and HEAVY/LEAKY recycle-by-class remains
 * the primary containment.
 */
internal fun internalGenerationBudget(
    nativeMB: Long,
    cmdClass: CommandClass = CommandClass.LIGHT,
): Int {
    return when {
        nativeMB < 50L -> 30
        nativeMB < 80L -> 15
        nativeMB < 100L -> 5
        nativeMB < 120L -> 2
        else -> if (cmdClass == CommandClass.LIGHT) 8 else 1
    }
}

/**
 * Pre-execution rejection message for the current phase + command class.
 * Returns null when the command may proceed:
 *   LOCKED    — everything rejected (dynamic: 350MB baseline / 1536MB ample)
 *   CRITICAL  — light commands run (agent can self-recover); leaky rejected
 *               always; heavy rejected UNLESS memory is ample (≥
 *               MEM_AVAIL_AMPLE_MB) — the heavy channel lets large
 *               git/python tasks through when the system has headroom,
 *               with forced recycle+GC afterwards
 *   SEVERE    — nothing rejected (model calls isolated in :modelservice)
 *   MILD/MODERATE/NORMAL — proceed
 */
internal fun preExecRejectionMessage(
    phase: ShellPhase,
    cmdClass: CommandClass,
    nativeMB: Long,
    memAvailableMB: Long = 0L,
): String? {
    return when (phase) {
        ShellPhase.LOCKED -> "[System memory pressure: native heap ${nativeMB}MB exceeds safe limit. " +
            "Please reduce concurrent sessions or wait for memory to recover.]"
        ShellPhase.CRITICAL -> when {
            cmdClass == CommandClass.LIGHT -> null
            // [memory-dynamic-budget] Heavy channel: ample system memory lets
            // a heavy command through at CRITICAL — it will be force-recycled
            // after (shouldRecycleByClass: native > 80MB → true) so the cost
            // is deferred to cleanup, not paid as a rejection. LEAKY stays
            // rejected: minis-model-use's DirectByteBuffer allocations live in
            // the app native heap, invisible to GC, and recycling the shell
            // does not release them.
            cmdClass == CommandClass.HEAVY && memAvailableMB >= MEM_AVAIL_AMPLE_MB -> null
            else -> "[System memory pressure: native heap ${nativeMB}MB — only lightweight commands allowed. " +
                "Please reduce concurrent sessions or wait for memory to recover.]"
        }
        // Model calls now run in the isolated :modelservice process (file
        // protocol), so they no longer bloat this process's native heap —
        // SEVERE no longer needs to reject LEAKY commands up front.
        ShellPhase.SEVERE -> null
        else -> null
    }
}
// [P2-app-native-reclaim-verify] Pure function: decide whether to continue
// another GC round in the bounded iterative `postRecycleMemoryRecovery()`.
// Decides whether another GC round is worth trying in the bounded iterative
// reclaim loop. We keep going only while: (a) we haven't exhausted the round
// budget, AND (b) native heap is still at/above the locked floor. Once native
// heap drops below the floor, the session is no longer memory-locked, so we
// stop (further GC rounds would just burn CPU with no benefit).
//
// `freedThisRoundMb` is intentionally not a decision input: a single round
// freeing 0MB does NOT mean later rounds will too (GC is a hint; objects may
// become free as the concurrent GC advances), and native heap is the signal we
// actually care about. It stays in the signature for Log/reporting symmetry.
// JVM-testable — no Android dependency.
internal fun shouldContinueNativeReclaim(
    freedThisRoundMb: Long,
    roundsUsed: Int,
    maxRounds: Int,
    nativeNowMb: Long,
    lockedFloorMb: Long,
): Boolean {
    return roundsUsed < maxRounds && nativeNowMb >= lockedFloorMb
}
