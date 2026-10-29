package com.rikkaminis.app.sandbox

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rikkaminis.app.agent.runtime.CommandFailureKind
import com.rikkaminis.app.agent.runtime.RetryOutcome
import com.rikkaminis.app.agent.runtime.RetryPolicy
import com.rikkaminis.app.agent.runtime.RetrySafety
import com.rikkaminis.app.agent.runtime.ToolRetrySafetyRegistry
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.repository.EnvVarRepository
import com.rikkaminis.app.diagnostics.MemorySpikeRecorder
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.offload.ChatStreamOffloadHandler
import com.rikkaminis.app.sandbox.offload.ModelExecutionMailbox
import com.rikkaminis.app.service.MemoryPressureGate
import com.rikkaminis.app.service.MemoryPressureLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
// [feat/runtime-sandbox-knobs] 超时值改为可调（heavyGateTimeoutSec，默认
// 600 s，读点见下方 tryAcquire 处）。

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
    // [feat/runtime-sandbox-knobs] Both windows are user-tunable now
    // (shellIdleTimeoutMin / guestTmpSweepIntervalSec); the defaults keep the
    // old literals (10 min / 60 s) and are read at use time, not cached here.
    internal fun shellIdleTimeoutMs(): Long =
        AgentRuntimeLimitsPrefs.shellIdleTimeoutMin() * 60_000L

    // Sweep cadence for idle shell recycling (public for MinisApp sweeper).
    fun idleSweepIntervalMs(): Long =
        AgentRuntimeLimitsPrefs.guestTmpSweepIntervalSec() * 1000L

    /**
     * [P2-guest-tmp-reclaim] Guest-rootfs temp dirs the sweeper may prune,
     * relative to `RootfsManager.rootfsDir`.
     */
    private val GUEST_TMP_DIRS = listOf("tmp", "var/tmp")

    /**
     * [P2-guest-tmp-reclaim] Minimum age before the guest-rootfs temp sweeper
     * may delete an entry.
     *
     * WHY age at all: the neighbouring orphan reaper
     * ([com.rikkaminis.app.sandbox.offload.ModelExecutionOrphanReaper]) uses
     * the same "age before delete" shape for the same reason — never touch a
     * producer that may still be running.
     *
     * WHY 60 min and not 10: this sweep is already gated on no shell being
     * BUSY (see [cleanupProotTmp]), so nothing is writing at sweep time; the
     * age gate is the second line of defence, covering a guest process that
     * outlived its shell (a detached grandchild, an `apk` still unpacking).
     * One hour is far longer than any single command the agent runs
     * interactively, and everything older still gets reclaimed on the next
     * sweep.
     *
     * WHY age alone was not enough: this timestamp is the entry's OWN mtime,
     * and a directory's mtime does not move when something writes *inside* it
     * (git commit, checkout, appending, a build dropping objects). An entry in
     * constant use for hours therefore crossed this gate while still in use and
     * was reaped mid-work. Aged candidates now get a second chance — a recent
     * mtime in their top levels, or an explicit pin — before deletion; the
     * policy and its budget live in [GuestTmpSweepPlan.kt] as
     * [internalGuestTmpNewestActivityMs] / [GUEST_TMP_KEEP_MARKER].
     */
    // [feat/runtime-sandbox-knobs] The 1 h default is user-tunable now
    // (guestTmpMaxAgeMin, 15..1440 min) and read at sweep time — see the
    // cleanupProotTmp call site.

    // [P3-shell-auto-retry] At most 2 attempts total (original + 1 retry)
    // before a command is reported as failed. Guards against infinite retry
    // loops.
    internal const val MAX_AUTO_RETRIES = 2

    /**
     * [P3-shell-auto-retry] Pure decision: should the command be re-run on a
     * rebuilt shell? Delegates to the top-level [internalShouldRetryCommand].
     *
     * [fix/audit3-cancel-retry-1015 F-06] `memoryRecycled` is true when the
     * in-flight memory monitor itself killed the shell to keep the app alive —
     * the command is then never replayed (see the predicate for why).
     *
     * [deep-audit-1015 ARCH-01] LEGACY — no production call site any more. The
     * live decision in [executeWithShellRetry] is
     * [internalDecideShellRetryVerdict], which additionally consults the
     * trusted side-effect registry ([ToolRetrySafetyRegistry]) before allowing
     * a replay. This wrapper and its top-level delegate are kept as the
     * same-inputs comparison arm for the "before vs after" judgment table (and
     * for the pre-existing tests that pin the legacy contract).
     */
    internal fun shouldRetryCommand(
        exitCode: Int,
        shellAlive: Boolean,
        attempt: Int,
        memoryRecycled: Boolean = false,
    ): Boolean {
        return internalShouldRetryCommand(exitCode, shellAlive, attempt, MAX_AUTO_RETRIES, memoryRecycled)
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
        maxConcurrentShells = com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()
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
        /** `(line, isPartial)` — see PersistentShell.CommandCallback.lineCallback. */
        lineCallback: ((String, Boolean) -> Unit)? = null
    ): CommandResult {
        // [native-rss-tool-guard] Process-memory hard gate BEFORE any shell work.
        // Debug.getNativeHeapAllocatedSize() is blind to mmap/thread-stack/mapped
        // tmpfile growth — the exact shape of the 2026-08-19 crash (RSS 5.8–6.0GB
        // while the native-heap-only tiers stayed below their cliffs).
        // [fix/memory-anon-metric] Metric is RssAnon, not VmRSS: on this device
        // file-backed pages sit at a constant ~147MB of a ~265MB healthy RSS
        // (measured 2026-09-13), so VmRSS spent 61% of its signal on reclaimable
        // cache. anon is both the thing that grows and the thing the gate exists
        // to protect (thread stacks / anon mmap). See MemoryPressureGate.
        // [fix/memory-anon-metric] 一次采样供多处使用：准入判定用 anon（主指标），
        // 命令级 ΔRSS 归因仍用 rss（探针的 ΔRSS 口径是 rss——两个口径不能混用，
        // 混了算出来的 Δ 是假的）。
        val metricsBefore = MemoryPressureGate.metricsReader()
        val anonBeforeMB = metricsBefore.anonMb
        val rssBeforeKb = metricsBefore.rssMb * 1024L
        // [mem-spike-diag] 命令级内存归因旁路：记录命令上下文 + 起始 RSS，由
        // MemorySpikeRecorder 在命令结束时写出 ΔRSS —— 直接回答「哪条命令吃掉
        // 多少内存」。与准入逻辑完全无关，任何失败静默。
        val spikeCmdClass = classifyCommand(command)
        MemorySpikeRecorder.onCommandStart(sessionId, spikeCmdClass.name, command)
        // [LOOP-1030 R1-g] 影子水线挂在这里：准入读数就是决策读数（复用这一次
        // 读，零额外 IO）。1s 周期采样看不到亚秒突发，别把它挪回 tick。
        MemoryPressureGate.observeShadowAtAdmission(
            anonBeforeMB, metricsBefore.rssMb, src = "shell-admission",
        )
        if (MemoryPressureGate.levelFor(anonBeforeMB) == MemoryPressureLevel.CRITICAL) {
            MemoryPressureGate.reclaimAndWait(waitMs = 2_000L)
            val metricsAfter = MemoryPressureGate.metricsReader()
            val anonAfterMB = metricsAfter.anonMb
            // 回收完仍然越线 = 假设水线下的"拒绝准入"，单独一个 src 便于计数。
            MemoryPressureGate.observeShadowAtAdmission(
                anonAfterMB, metricsAfter.rssMb, src = "shell-post-reclaim",
            )
            if (MemoryPressureGate.shouldRejectAfterReclaim(anonAfterMB)) {
                Log.w(TAG, "[$sessionId] Process anon ${anonAfterMB}MB still critical after reclaim — rejecting command (anonBefore=${anonBeforeMB}MB, rss=${metricsAfter.rssMb}MB)")
                MemorySpikeRecorder.onEvent(
                    "reject-anon",
                    "session=$sessionId anonAfter=${anonAfterMB}MB anonBefore=${anonBeforeMB}MB rss=${metricsAfter.rssMb}MB :: ${MemorySpikeRecorder.previewOf(command, 60)}"
                )
                return CommandResult(
                    "[System busy: process memory is critically high (anon ${anonAfterMB}MB). " +
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
            MemorySpikeRecorder.onEvent(
                "reject-tier",
                "session=$sessionId phase=$preExecPhase class=$cmdClass native=${preExecNativeMB}MB memAvail=${memAvailableMB}MB"
            )
            postRecycleMemoryRecovery()
            return CommandResult(preExecReject, -1, 0, true)
        }

        // [memory-dynamic-budget] Heavy 全局串行闸：先拿 heavyGate 再拿
        // globalConcurrency（锁序一致无死锁）。等待超时（10min）说明另一
        // heavy 还在跑——排队而非并发叠加，防止多会话大任务把 memcg 推爆。
        val heavyGateTimeoutMs = AgentRuntimeLimitsPrefs.heavyGateTimeoutSec() * 1000L
        // [deep-audit-1016 FIX-D 4b] The wait must be interruptible — see
        // [awaitHeavyGatePermitInterruptibly]. A cancel that lands while a HEAVY
        // command is queued behind another HEAVY one (a wait of up to 10
        // minutes) used to leave the thread parked inside `Semaphore.tryAcquire`
        // with no suspension point: the coroutine went Cancelling, the shell
        // stayed reserved, and the session's next command reported "System busy"
        // until the timeout expired.
        val heavyGateAcquired = cmdClass == CommandClass.HEAVY &&
            awaitHeavyGatePermitInterruptibly(heavyGate, heavyGateTimeoutMs)
        if (cmdClass == CommandClass.HEAVY && !heavyGateAcquired) {
            Log.w(TAG, "[$sessionId] Heavy gate timeout after ${heavyGateTimeoutMs}ms — another heavy command still running")
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
        //
        // [deep-audit-1016 FIX-D 4a+4b] Same interruptible wait, PLUS the
        // reason it is not a bare `tryAcquire` any more: this call sits between
        // the heavyGate acquisition and the `try` whose `finally` releases it,
        // so with the wait now interruptible an interrupt here would throw out
        // of `execute` while still holding the ONE heavy permit — and a leaked
        // heavy permit queues every HEAVY command in the process for the rest of
        // its life. [awaitGlobalShellSlotInterruptibly] →
        // [internalAcquireGlobalShellSlot] is the single place that can throw
        // from inside that window, and it hands the permit back before
        // rethrowing.
        val acquired =
            awaitGlobalShellSlotInterruptibly(
                globalConcurrency = globalConcurrency,
                heavyGate = heavyGate,
                heavyGateHeld = heavyGateAcquired,
            )
        if (!acquired) {
            if (heavyGateAcquired) heavyGate.release()
            Log.w(TAG, "[$sessionId] Global concurrency slot timeout after ${GLOBAL_SHELL_SLOT_TIMEOUT_SEC}s — rejecting command")
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
                    // [meta1028 D3-F02] False here means one of two things:
                    // cold start (no shells yet, no-op) or a rootfs rebuild
                    // under a live process — `RootfsManager.reset()` re-arms
                    // the gate. In the second case every entry in [shells] was
                    // spawned against the tree that was deleted: the process is
                    // still alive but its cwd inode is unlinked, so reusing it
                    // hands the command a ghost directory. Drop them; the
                    // post-boot path mints fresh ones.
                    dropShellsFromRebuiltRootfs()
                    Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
                    PRootKernel.boot(appContext)
                }

            // [P3-shell-auto-retry] Execute with one automatic retry: if the
            // PRoot shell process itself died mid-command (HyperOS silent_kill,
            // tracer OOM, idle-recycle race), rebuild the shell and re-run the
            // command. At most 2 attempts total — guards against infinite retry
            // loops. The agent/user sees a single successful result for
            // transient infra failures.
            // [fix/audit-s4h3] Stamp activity at command START too: the 1-min
            // idle sweeper previously saw only completion stamps, so a command
            // issued >10min after the previous completion looked idle and got
            // reaped 60s into its execution (now also blocked by isBusy).
            lastActiveMs[sessionId] = SystemClock.elapsedRealtime()
            val (result, shell) = executeWithShellRetry(
                sessionId = sessionId,
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(result.output)
            // [audit-0919 F-215] Host-side cap must honour the SAME knob the
            // shell-side buffer uses (PersistentShell.appendOutput →
            // AgentRuntimeLimitsPrefs.shellOutputKb() * 1024) and count BYTES.
            // It used to take truncateIfNeeded's default (50 000) and compare
            // it against String.length, so the user's ceiling was ignored on
            // this path entirely and was 3× its declared value for CJK.
            //
            // [deep-audit-1015 BUG-09] …plus a suffix margin. By the time we get
            // here the shell side has ALREADY clipped the capture to
            // `shellOutputKb() * 1024` bytes (silently, by design), and
            // executeWithShellRetry has appended its own trailing diagnostics
            // ([internalMemoryRecycleNotice], the "(exit code: N)" trailer).
            // Truncating at exactly the same ceiling therefore cut a SECOND
            // time whenever the output landed within a suffix-length of the
            // knob: a second "[... N bytes omitted ...]" marker with the tail of
            // the real output and the diagnostics squeezed out. The margin is
            // sized for the longest suffix this path appends (~350 chars) so the
            // diagnostics survive intact and the single silent shell-side clip
            // is the only truncation the agent ever sees.
            // NOT fixed by moving the notice after the truncation: that only
            // swaps which half gets cut (the notice would then be the clipped
            // one), and it would introduce a "notice itself truncated" path.
            val truncated = TerminalSanitizer.truncateIfNeeded(
                sanitized,
                com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.shellOutputKb() * 1024 +
                    SHELL_OUTPUT_TRUNCATION_SUFFIX_MARGIN_BYTES,
            )
            // Combine host-side and shell-side truncation flags
            val outputTruncated = result.truncated || truncated != sanitized
            // [audit-0916d] TIMEOUT_EXIT_CODE, not a bare 124: the value was
            // named once (producer → retry predicate → reclaim predicate) and
            // this was the one site left spelling it out — exactly the drift
            // the constant exists to prevent.
            // A timeout is deliberately the ONE failure with no
            // "(exit code: N)" trailer: the shell already wrote its own
            // "[Command timed out after Ns]" line, and 124 is now the only code
            // that reaches the agent as a result (no retry) — the trailer would
            // say it twice. Do NOT "fix" that into including 124.
            val output = if (result.exitCode != 0 && result.exitCode != TIMEOUT_EXIT_CODE) {
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

            // [mem-spike-diag] 命令结束归因：ΔRSS + PRoot 子进程聚合，落盘
            // memspike-<date>.log。诊断旁路，不影响返回值。
            MemorySpikeRecorder.onCommandEnd(durationMs, rssBeforeKb)
            // [deep-audit-1018 ISSUE-03] L1: carry the retry verdict's outcomeKnown
            // through the truncation re-wrap. Pre-fix this construction dropped it,
            // so the SAME command's two exits disagreed (executeWithShellRetry
            // stamped it, this wrap did not) and a withheld replay looked known.
            CommandResult(
                output = output,
                exitCode = result.exitCode,
                durationMs = durationMs,
                truncated = outputTruncated,
                outcomeKnown = result.outcomeKnown,
            )
        }
    } finally {
        // [mem-spike-diag] 兜底配对：异常 / 提前 return 的路径也把命令上下文收尾，
        // 避免 ΔRSS 归因丢行（幂等，成功路径已收尾时为 no-op）。
        MemorySpikeRecorder.onCommandEndIfPending(rssBeforeKb = rssBeforeKb)
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
        /** `(line, isPartial)` — see PersistentShell.CommandCallback.lineCallback. */
        lineCallback: ((String, Boolean) -> Unit)?,
    ): Pair<CommandResult, PersistentShell?> {
        var attempt = 0
        var lastShell: PersistentShell? = null
        while (true) {
            attempt++
            val shell = getOrCreateShell(sessionId)
            lastShell = shell

            // [fix/audit3-cancel-retry-1015 F-06] Did OUR OWN memory monitor
            // recycle this shell while the command was in flight? Both kill
            // paths of the in-flight monitor set this (child-RSS crossing and
            // app-native-heap crossing). A shell that we killed for memory is
            // NOT an infra death, so the command must not be replayed on the
            // rebuilt shell — see internalShouldRetryCommand.
            //
            // Atomics, not a captured `var`: the monitor runs in its own
            // coroutine on the IO pool (PersistentShell's `monitorJob`), while
            // this flag is read back on the command's coroutine after
            // `executeCommand` returns; a plain closure write has no
            // happens-before edge between the two.
            val recycledMidCommand = java.util.concurrent.atomic.AtomicBoolean(false)
            val recycleReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

            // Inject environment variables as a full snapshot (T124a).
            val envVars = envVarRepository?.allAsDict() ?: emptyMap()
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            // [deep-audit-1015 ARCH-01] PRE_FLIGHT probe — the ONE signal that
            // tells "the command never reached the PTY" (zero side effects, so
            // re-issuing it is just its first execution) apart from "the command
            // ran and its result was lost" (re-issuing an UNKNOWN command would
            // repeat its side effects).
            //
            // `PersistentShell.executeCommand` itself starts with
            // `ensureStarted()` and then bails out at `writer == null || !isAlive`
            // with `CommandResult("[Shell not running]", -1)`. Running that same
            // start step HERE first keeps the probe conservative: a shell that is
            // alive afterwards is treated as "dispatched" even in the rare case
            // where the write itself could still fail, so the probe can never
            // upgrade a genuine dispatch into a licence to replay. Only a shell
            // that refuses to come up — where the command provably never reached
            // the PTY — reports `dispatched = false`.
            shell.ensureStarted()
            val commandDispatched = shell.isAlive

            val result = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
                memoryMonitor = { rssMB ->
                    // [memory-dynamic-budget] Both marks are dynamic: the
                    // child RSS ceiling and the app-native recycling point
                    // scale with system MemAvailable.
                    val memNow = systemMemAvailableMB()
                    val childRssMarkMB = childRssHighWaterMarkMB(memNow)
                    if (midCommandRecycleIfOversized(shell, sessionId, rssMB, childRssMarkMB)) {
                        recycledMidCommand.set(true)
                        recycleReason.compareAndSet(
                            null,
                            "the PRoot command's RSS ${rssMB}MB crossed the ${childRssMarkMB}MB ceiling",
                        )
                    }
                    val appNativeMB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                    val appNativeMarkMB = appNativeHighWaterMarkMB(memNow)
                    if (appNativeMB > appNativeMarkMB) {
                        Log.w(TAG, "[$sessionId] App native heap ${appNativeMB}MB crossed mark in-flight — recycling shell")
                        recycledMidCommand.set(true)
                        recycleReason.compareAndSet(
                            null,
                            "the app's native heap ${appNativeMB}MB crossed the ${appNativeMarkMB}MB ceiling",
                        )
                        sessionDidTerminate(sessionId)
                    }
                },
            )

            // [audit-0916] Two distinct needs used to be conflated here:
            //   - reclaim the SHELL when a command died or timed out (a
            //     timeout's command keeps running in the PTY) — required;
            //   - re-run the COMMAND — only legitimate for an infra death.
            // shouldRetryCommand() now decides the second (timeout and
            // stall-kill return false); the no-retry branch below reclaims the
            // shell for those. `shellDied` here is only used to null out a
            // dead shell reference on the exhausted path.
            val shellDied = result.exitCode == -1 || !shell.isAlive
            val memoryRecycled = recycledMidCommand.get()
            // [deep-audit-1015 ARCH-01] The T3 side-effect-aware policy takes
            // over from the legacy `shouldRetryCommand` here. `shell_execute` is
            // registered as RetrySafety.UNKNOWN ("the command string cannot be
            // proven idempotent"), so a DISPATCHED command whose result was lost
            // (-1 / dead shell / truncated output) is never transparently
            // re-run: the agent gets `outcomeKnown = false` and decides. Only the
            // pre-flight case above (provably not dispatched) keeps the
            // rebuild-and-replay ability.
            val verdict = internalDecideShellRetryVerdict(
                exitCode = result.exitCode,
                shellAlive = shell.isAlive,
                truncated = result.truncated,
                attempt = attempt,
                safety = ToolRetrySafetyRegistry.lookup(SHELL_EXECUTE_TOOL_NAME),
                maxRetries = MAX_AUTO_RETRIES,
                memoryRecycled = memoryRecycled,
                commandDispatched = commandDispatched,
            )
            // [SYMPTOM-A F-A2b] The verdict's two `outcomeKnown = false` exits live
            // in the pure function above (`internalDecideShellRetryVerdict`), which
            // is JVM-tested and Android-free — a log call there would break that and
            // fire on every retry-policy evaluation. So the trace lands here, at the
            // CONSUMPTION point, where the verdict becomes a CommandResult.
            //
            // Why it matters: without this line an untrustworthy shell result leaves
            // no trace at all in the log. The only downstream evidence is the
            // T7-D shadow reducer's "declined" warning (10-28: 11, 10-29: 12 — i.e.
            // 17%/20% of runs), which reads like a failure and says nothing about
            // WHICH command became untrustworthy. Metadata only — never the command
            // text or any output (credentials leak through tool output; see the
            // redaction rules in this package).
            if (!verdict.outcomeKnown) {
                Log.w(
                    TAG,
                    "[$sessionId] outcome unknown (reason=" +
                        (if (memoryRecycled) "memory-recycled" else "outcome-unknown") +
                        "): exit=${result.exitCode} alive=${shell.isAlive} " +
                        "truncated=${result.truncated} attempt=$attempt/$MAX_AUTO_RETRIES " +
                        "safety=${ToolRetrySafetyRegistry.lookup(SHELL_EXECUTE_TOOL_NAME)} " +
                        "dispatched=$commandDispatched cmdLen=${command.length}",
                )
            }
            if (!verdict.retry) {
                if (memoryRecycled) {
                    // [fix/audit3-cancel-retry-1015 F-06] Our own memory monitor
                    // killed the shell mid-command. Re-running would rebuild a
                    // shell and replay the very command that just crossed the
                    // memory ceiling — it would cross it again (and repeat the
                    // side effects of a non-idempotent command), so the agent
                    // gets the death plus the reason instead. The kill site
                    // already called sessionDidTerminate, so only the local
                    // dead reference is dropped here (re-terminating would run
                    // the recycle GC pass a second time for nothing).
                    Log.w(
                        TAG,
                        "[$sessionId] Shell was memory-recycled mid-command (exit=${result.exitCode}) — " +
                            "reporting to the agent instead of re-running the command",
                    )
                    lastShell = null
                // [audit-RC7] Timeout must ALWAYS reclaim the shell. With
                // withTimeoutOrNull only cancelling the coroutine, the command
                // keeps running inside the PTY with pendingCallback already
                // nulled, so its late output (and the stale
                // __MINIS_DONE_<marker>__ line) would be scanned into the
                // NEXT command's output on this shell. Killing the shell here
                // guarantees the invariant: exitCode==124 ⇒ no orphan command,
                // no cross-command output pollution.
                //
                // [audit-0916] This is now the ONLY thing a timeout does —
                // shouldRetryCommand() returns false for 124 on every attempt,
                // so the branch fires on attempt 1 as well and the agent gets
                // the timeout error immediately instead of after a silent
                // re-run of the same command.
                } else if (internalShouldReclaimOnExhaustedTimeout(result.exitCode)) {
                    Log.w(
                        TAG,
                        "[$sessionId] Command timed out — reclaiming shell to kill the " +
                            "orphaned command and reporting the timeout to the agent " +
                            "(no re-run)"
                    )
                    sessionDidTerminate(sessionId)
                    lastShell = null
                } else if (result.exitCode == STALL_EXIT_CODE) {
                    // [fix/memory-hardening-stall] The stall guard hard-killed
                    // the shell at the process level. Drop the dead shell from
                    // the registry right away instead of leaving a zombie entry
                    // to be discovered by the next getOrCreateShell.
                    Log.w(TAG, "[$sessionId] Command stalled and was killed — recycling shell")
                    sessionDidTerminate(sessionId)
                    lastShell = null
                } else if (shellDied && attempt >= MAX_AUTO_RETRIES) {
                    lastShell = null // shell is dead and we're out of retries
                }
                // An exit code / output the agent can act on: a bare -1 with no
                // explanation reads like an unexplained crash, so the memory
                // kill carries the ceiling that was crossed.
                val notice = if (memoryRecycled) internalMemoryRecycleNotice(recycleReason.get()) else ""
                // [deep-audit-1015 ARCH-01] Carry the policy's verdict up:
                // `outcomeKnown = false` marks "the command may have run but the
                // result is not trustworthy" (see [CommandResult.outcomeKnown]).
                // It is set on exactly the shapes where the replay was withheld
                // — the two decisions are one decision.
                return CommandResult(
                    output = result.output + notice,
                    exitCode = result.exitCode,
                    durationMs = 0L,
                    truncated = result.truncated,
                    outcomeKnown = verdict.outcomeKnown,
                ) to lastShell
            }

            Log.w(
                TAG,
                if (commandDispatched) {
                    "[$sessionId] Retry policy allows a replay (exit=${result.exitCode}, " +
                        "alive=${shell.isAlive}) — rebuilding and retrying " +
                        "(attempt $attempt/$MAX_AUTO_RETRIES)"
                } else {
                    "[$sessionId] Command never reached the PTY (shell would not start) — " +
                        "rebuilding the shell and dispatching it " +
                        "(attempt $attempt/$MAX_AUTO_RETRIES)"
                }
            )
            sessionDidTerminate(sessionId)
        }
    }

    /** [P2-proot-native-leak] If the PRoot child is already dead mid-command
     * (it OOM'd), immediately recycle the session so the shell isn't held as
     * a zombie and the next command spawns fresh. Called from the executeCommand
     * in-flight monitor; rssMB of 0 means the child already died. The ceiling
     * is dynamic ([memory-dynamic-budget]) — passed in by the caller.
     *
     * [fix/audit3-cancel-retry-1015 F-06] Returns whether it actually recycled
     * (rather than Unit, which the call site used to discard): a shell killed
     * by the memory monitor is not an infra death, so the caller must refuse to
     * replay the command that crossed the ceiling. */
    private fun midCommandRecycleIfOversized(shell: PersistentShell, sessionId: String, rssMB: Long, childRssMarkMB: Long): Boolean {
        if (rssMB > childRssMarkMB) {
            Log.w(TAG, "[$sessionId] PRoot child RSS ${rssMB}MB crossed mark ${childRssMarkMB}MB mid-command — recycling")
            sessionDidTerminate(sessionId)
            return true
        }
        return false
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
            AppLogger.info(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
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
        // [fix/audit-s4h4] Do NOT remove the per-session mutex here. This is
        // called from inside `mutex.withLock` (the post-command RSS/memory
        // checks at :386/:445/:509/:533/:546/:558) — removing the map entry
        // while the lock is HELD lets a concurrent `mutexes.getOrPut` mint a
        // brand-new Mutex for the same session, acquire it immediately, and
        // run in parallel with the command still inside this withLock block
        // (breaking per-session command serialization). The mutex is small;
        // [meta1028 D3-F04] it is now removed by NO path at all —
        // stopCurrentCommand's `mutexes.remove`/`clear` were the same defect
        // through the other door (it is called precisely while a command holds
        // the lock) and are gone too, so "one live Mutex per session id" holds
        // process-wide and its lifetime is bounded by session id churn.
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
     * [deep-audit-1016 FIX-D 4c] App-scoped IO scope for reclaim work that a
     * main-thread caller must not run inline. Process-lifetime by design (this
     * object is a singleton): the recovery is best-effort cleanup, never tied
     * to a lifecycle that can be cancelled out from under it — which is also
     * why nothing here checks `isActive` before launching.
     */
    private val recoveryScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * [P2-app-native-oom] Post-recycle memory recovery, dispatched by caller
     * thread.
     *
     * Background callers (the whole command path: the pre-exec rejection
     * branches and [sessionDidTerminate] reached from the agent loop) keep the
     * SYNCHRONOUS contract — the pre-exec rejection paths deliberately GC
     * before returning so the caller's retry starts from a lower baseline.
     *
     * [deep-audit-1016 FIX-D 4c] Main-thread callers DEFER instead. They are
     * real: Compose calls `promoteDraftIfNeeded()` (ChatScreen), which launches
     * `ensureSession()` on viewModelScope (Dispatchers.Main.immediate), which
     * calls `migrateDraftResources` → `ExecutionCoordinator.sessionDidTerminate`
     * → here — so the first message in a NEW chat used to run up to three
     * `System.gc()` rounds with a 120ms settle each on the UI thread, and
     * `ViewModel.onCleared()` (also main) did the same on every chat screen
     * close. Nothing on those paths consumes the synchronous side effect: the
     * call sites discard the result (migrateDraftResources wraps it in
     * runCatching) or are teardown.
     */
    private fun postRecycleMemoryRecovery() {
        if (!::appContext.isInitialized) return
        when (recycleDispatchMode(isOnMainThread = Looper.myLooper() == Looper.getMainLooper())) {
            RecycleDispatch.DEFERRED -> recoveryScope.launch { postRecycleMemoryRecoveryNow() }
            RecycleDispatch.INLINE -> postRecycleMemoryRecoveryNow()
        }
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
    private fun postRecycleMemoryRecoveryNow() {
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
        if (ChatStreamOffloadHandler.activeStreams.get() > 0) {
            Log.w(TAG, "[direction-A] skipped reclaim: active stream in progress " +
                "(activeStreams=${ChatStreamOffloadHandler.activeStreams.get()}, native ${nativeNowMb}MB)")
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
     * [P2-proot-native-leak] Recycle shells idle past [shellIdleTimeoutMs].
     * Long-lived PRoot shells leak native memory monotonically; terminating
     * idle ones releases their footprint. Next command re-spawns fresh.
     */
    fun recycleIdleShells() {
        val now = SystemClock.elapsedRealtime()
        for (sessionId in shells.keys) {
            // [fix/audit-s4h3] Skip shells with a command mid-flight. The old
            // check keyed only on lastActiveMs, which updates on command
            // COMPLETION (not start) — so a freshly-started long command (big
            // git fetch / apk add) could be SIGKILLed 60s in, and any command
            // started >10min after the previous completion was reaped almost
            // immediately. Non-idempotent commands then re-ran with duplicated
            // side effects. cleanupProotTmp already had an isAlive-style guard;
            // this is the same protection for the reaper.
            val shell = shells[sessionId]
            if (shell != null && shell.isBusy) continue
            val last = lastActiveMs[sessionId] ?: 0L
            if (last != 0L && (now - last) > shellIdleTimeoutMs()) {
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
     *
     * [P2-guest-tmp-reclaim] The guest half was previously documented here but
     * NOT implemented: only the host `cache/proot-tmp` cache was swept, while
     * guest `/tmp` + `/var/tmp` (host side: `filesDir/alpine-rootfs/tmp` and
     * `.../alpine-rootfs/var/tmp`, reached by PRoot's `-r <rootfs>` with no
     * bind mount over them — verified from [PRootKernel.buildProotCommand]'s
     * argv, which binds only /dev, /proc, /sys and the /var/minis subdir maps)
     * accumulated without bound. See [AgentRuntimeLimitsPrefs.guestTmpMaxAgeMin]
     * for the age-gated plan and the measured evidence for the default threshold.
     */
    fun cleanupProotTmp() {
        // [P2-guest-tmp-reclaim] Gate on isBusy, not isAlive. A shell that is
        // merely ALIVE (the normal state — agent shells are persistent, idle
        // between turns) never writes anything, so the old isAlive gate meant
        // this sweeper effectively never ran during an active session; that is
        // how the guest temp dirs grew for weeks in the first place. A shell
        // with a command MID-FLIGHT is exactly the case the gate exists for, so
        // that is what we keep. Same predicate the idle reaper uses at :908.
        if (shells.values.any { it.isBusy }) return
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
        // [P2-guest-tmp-reclaim] Guest-rootfs half of the same sweep.
        try {
            val rootfsDir = RootfsManager.getInstance(ctx).rootfsDir
            var guestRemoved = 0L
            for (relative in GUEST_TMP_DIRS) {
                val guestTmp = File(rootfsDir, relative)
                guestRemoved += sweepGuestTmpDir(guestTmp, AgentRuntimeLimitsPrefs.guestTmpMaxAgeMin() * 60_000L)
            }
            if (guestRemoved > 0) {
                Log.i(TAG, "cleanupProotTmp: cleared $guestRemoved aged entries from guest tmp")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupProotTmp (guest tmp) failed: ${t.message}")
        }
        // TF-G P1-2: opportunistic orphan run-dir reaper (same low cadence as
        // the tmp sweeper). Removes only terminal + worker-dead + aged model-
        // exec run dirs that the stream/dispatch finally left behind (crashed
        // client / lost ack). Conservative — never touches an active run.
        runCatching {
            val reaped = com.rikkaminis.app.sandbox.offload.ModelExecutionOrphanReaper
                .reapOrphans(ctx)
            if (reaped > 0) {
                Log.i(TAG, "orphan model-exec reaper: reclaimed $reaped run dir(s)")
            }
        }
        // [fix/sweep1014-d2b] Aged shutdown-marker cleanup (same low cadence).
        cleanupStaleShutdownMarker(ctx)
    }

    /**
     * [fix/sweep1014-d2b] A `shutdown` marker in the model-exec staging root
     * is written by [maybeReclaimModelService] and consumed by the
     * :modelservice worker at each request finish — but nothing ever deleted
     * it, so once true the worker's `shutdownRequested` observation stayed
     * true forever: the worker's state dump and logs were permanently
     * poisoned, and a worker restarted AFTER a previous shutdown would read
     * the stale marker and self-reap on its first quiescent finish even
     * though the main process was no longer asking.
     *
     * Cleanup is AGE-GATED and conservative: the worker consumes the marker
     * at every request finish, so a marker that has sat for [SHUTDOWN_MARKER_STALE_MS]
     * is consumed-or-dead garbage. Deleting an aged marker can only cost a
     * skipped self-reap this round (the next pressure reclaim re-writes the
     * marker), never correctness — the worker owns its death timing either way.
     */
    private fun cleanupStaleShutdownMarker(ctx: Context) {
        try {
            val marker = File(
                File(ctx.cacheDir, "model-exec"),
                ModelExecutionMailbox.FILE_SHUTDOWN,
            )
            if (!marker.isFile) return
            val ageMs = System.currentTimeMillis() - marker.lastModified()
            if (ageMs < SHUTDOWN_MARKER_STALE_MS) return
            if (marker.delete()) {
                Log.i(TAG, "cleanupProotTmp: cleared stale model-exec shutdown marker (age ${ageMs / 60_000L}min)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupProotTmp (shutdown marker) failed: ${t.message}")
        }
    }

    /**
     * [fix/sweep1014-d2b] A shutdown marker must sit this long before the
     * stale-marker sweeper may delete it. WHY 30 min: far longer than any
     * single model-exec request's timeout + ack window, so a worker that is
     * still going to consume the marker has had many request finishes to do
     * so; and far shorter than the "forever" the stale marker used to live.
     */
    private const val SHUTDOWN_MARKER_STALE_MS = 30 * 60_000L

    /**
     * [P2-guest-tmp-reclaim] Recursive, age-gated sweep of one guest temp dir
     * (host-side path). Returns how many top-level entries were removed.
     *
     * Deletion rules (each one has a reason, none is decoration):
     *  - the directory itself is never deleted — it is part of the rootfs;
     *  - an entry younger than [maxAgeMs] is never touched (see
     *    [AgentRuntimeLimitsPrefs.guestTmpMaxAgeMin] for why the age gate sits on top of isBusy);
     *  - the age gate reads the entry's OWN timestamp
     *    ([internalGuestTmpEntryMtimeMs], i.e. `lstat`) — `File.lastModified()`
     *    follows links, which aged-out fresh symlinks instantly;
     *  - a symlink is removed as a LINK, and a directory is removed with
     *    [internalGuestTmpDeleteTreeNoFollow], which never walks into a link:
     *    an aged `tmp/x -> /` must not become "wipe the rootfs", and that has
     *    to hold for links nested INSIDE an aged directory too;
     *  - an aged directory is removed whole; only if that fails do we descend,
     *    re-applying the same gate at each level (so the count reflects real
     *    deletions rather than double-counting a child of a deleted parent).
     */
    /**
     * [P2-guest-tmp-reclaim] Delete aged children of one guest temp dir,
     * oldest-first policy: an entry is removed only when it is both aged and
     * not [guestTmpEntryStillInUse]. Returns the number of entries removed.
     */
    private fun sweepGuestTmpDir(dir: File, maxAgeMs: Long): Long {
        if (!dir.isDirectory) return 0L
        val now = System.currentTimeMillis()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return 0L
        var removed = 0L
        for (child in children) {
            val symlink = runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }
                .getOrDefault(false)
            // [meta1028 D3-F05] `lastModified()` is `stat` — it follows the
            // link, so a fresh dangling link read 0 ("definitely aged") and a
            // link to an old target inherited the target's timestamp. Either
            // way a link created seconds ago was reaped on the next 60 s tick.
            // The ENTRY's own timestamp is what the age gate is asking about.
            if (!internalGuestTmpIsAged(internalGuestTmpEntryMtimeMs(child.toPath()), now, maxAgeMs)) continue
            // [guest-tmp-activity-probe] Second chance before reaping: the age
            // gate read the entry's own mtime, which does not move when
            // something writes inside it. Probed only for aged candidates, so a
            // fresh entry never pays for it.
            if (guestTmpEntryStillInUse(child, symlink, now, maxAgeMs)) continue
            val ok = runCatching {
                if (symlink) {
                    java.nio.file.Files.deleteIfExists(child.toPath())
                } else {
                    // [meta1028 D3-F05] NOT `deleteRecursively()`: that walks
                    // with `File.listFiles()`/`isDirectory`, both of which
                    // follow links, so an aged `/tmp/build` containing
                    // `out -> /var/minis/workspace` deleted the TARGET's
                    // contents. Links are unlinked as links, at every depth.
                    internalGuestTmpDeleteTreeNoFollow(child.toPath())
                }
            }.getOrDefault(false)
            if (ok) {
                removed++
            } else if (internalGuestTmpShouldDescend(child.isDirectory, symlink)) {
                removed += sweepGuestTmpDir(child, maxAgeMs)
            }
        }
        return removed
    }

    /**
     * [guest-tmp-activity-probe] True when an aged entry must be left alone
     * because it is still in use: explicitly pinned, or showing a write inside
     * its top levels more recent than the age gate.
     *
     * Symlinks are excluded from both checks — the link itself is the entry, and
     * following it would judge (and then delete) files outside the temp dir.
     */
    private fun guestTmpEntryStillInUse(child: File, symlink: Boolean, now: Long, maxAgeMs: Long): Boolean {
        if (symlink) return false
        if (internalGuestTmpIsPinned(child)) return true
        if (!child.isDirectory) return false
        val newest = internalGuestTmpNewestActivityMs(
            child,
            GUEST_TMP_ACTIVITY_MAX_DEPTH,
            GUEST_TMP_ACTIVITY_MAX_ENTRIES,
        )
        return internalGuestTmpLooksLive(newest, now, maxAgeMs)
    }

    /**
     * [meta1028 D3-F02] Drop every shell that was spawned against a rootfs
     * tree which has since been deleted and re-extracted
     * ([RootfsManager.reset] → hook → `bootGate.invalidate`).
     *
     * Called only from [execute]'s `!isBooted` branch, i.e. under this
     * session's mutex and before the re-boot. Shells of OTHER sessions are
     * dropped too, deliberately: they were spawned into the same deleted tree,
     * so any command issued through one is running against unlinked inodes —
     * keeping it alive only converts "rebuild in progress" into a confusing
     * per-command failure later. A reset is a process-wide event; a command
     * caught mid-flight by it fails either way, and failing at the boundary is
     * the honest place.
     *
     * Mutexes are NOT touched (same contract as [sessionDidTerminate], see the
     * `[fix/audit-s4h4]` note there).
     */
    private fun dropShellsFromRebuiltRootfs() {
        if (shells.isEmpty()) return
        val dropped = shells.size
        for ((id, shell) in shells) {
            runCatching { shell.stop() }
            lastInjectedKeys.remove(id)
            lastActiveMs.remove(id)
        }
        shells.clear()
        Log.i(TAG, "rootfs rebuilt — dropped $dropped shell(s) spawned into the pre-rebuild tree")
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
            // [meta1028 D3-F04] NO `mutexes.remove(sessionId)` here. The
            // `[fix/audit-s4l2]` line that used to sit here justified itself
            // with "sessionDidTerminate also removes the mutex" — which had
            // already been retracted by `[fix/audit-s4h4]` (it removed the map
            // entry while the lock was HELD, letting a concurrent
            // `mutexes.getOrPut` mint a second Mutex for the same session and
            // run in parallel with the command still inside `withLock`).
            //
            // s4l2's premise was that this path "never holds the lock". That is
            // exactly backwards: stopCurrentCommand's whole job is to interrupt
            // a RUNNING command, and that command's coroutine is inside
            // `mutex.withLock` right now. Removing the entry there reproduced
            // the s4h4 defect through a different door — and the victim was the
            // NEXT command: it got a fresh mutex, entered immediately, created
            // a new PersistentShell, which the old coroutine's tail
            // (`sessionDidTerminate` → `shells.remove(id)?.stop()`) then killed
            // mid-command (`exitCode = -1`, `outcomeKnown = false`).
            //
            // The mutex map is therefore never pruned. Cost: one small Mutex
            // per session id ever used — the same lifetime trade s4h4 already
            // accepted, and the only one that keeps "one live Mutex object per
            // session id" true. A refcount (or `if (!mutex.isLocked) remove`)
            // does not help: the check and the remove are still a race against
            // a newcomer that already holds the reference.
            shell?.stop()
            Log.i(TAG, "[$sessionId] Shell stopped by user")
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            lastActiveMs.clear()
            // [meta1028 D3-F04] NO `mutexes.clear()`: same defect as above, and
            // worse in blast radius — it rewrote EVERY session's mutex identity
            // while their commands were mid-`withLock`. The map entry is the
            // identity the serialization contract is built on; clearing it does
            // not free anything the sweeper needs.
            // [audit-0919 F-217] No ShellExecutor.destroyCurrent() here. It was
            // a silent no-op: its only effect is `currentProcess?.let { … }`,
            // and `currentProcess` is assigned exclusively inside
            // ShellExecutor.execute(), which has zero production callers (the
            // agent path is ExecutionCoordinator → PersistentShell). The real
            // teardown is the `shells.values.forEach { it.stop() }` + clears
            // directly above. The call was removed rather than kept as a
            // "belt and braces" line that reads like it does something.
            // ponytail: ShellExecutor 整体仍是生产死码（只有 androidTest 引用）
            //   | 天花板: 该类的 execute() 永远走不到，其超时/取消语义无人验证
            //   | 升级触发: 有人想复用 ShellExecutor.execute()，或需要为它补测试
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
 * HyperOS silent_kill, PRoot tracer OOM, idle-recycle race — AND we haven't
 * exhausted [maxRetries]. A normal non-zero exit (script error), a live
 * shell, a timeout, and a stall-kill never trigger a retry.
 *
 * [audit-0916] Timeout (124) was previously lumped in with "shell died"
 * because it "can leave zombie processes in the PRoot tracer". Those are two
 * different needs and only the second one is real:
 *
 *   - reclaiming the SHELL (kill the orphaned command so its late output
 *     can't be scanned into the next command) — genuinely required, and
 *     already handled unconditionally by
 *     [internalShouldReclaimOnExhaustedTimeout], which runs on the no-retry
 *     path;
 *   - re-running the COMMAND — not required, and actively harmful. A timeout
 *     means the command burned its entire window, so the retry re-runs the
 *     same expensive work (measured: a 600s `java -jar` harness re-ran for
 *     another 600s until the user hit stop) and, for a non-idempotent command
 *     (git push / CI dispatch / append), repeats its side effects while the
 *     agent is told nothing — it sees one result and cannot know it ran twice.
 *
 * This is the same reasoning the stall guard already encodes: "retrying the
 * SAME command would hang again for the same reason" (see STALL_EXIT_CODE).
 * Timeout is that same shape. It also matches the repo's side-effect-aware
 * policy ([com.rikkaminis.app.agent.runtime.RetryPolicy]: UNKNOWN + TIMEOUT →
 * OutcomeUnknown, i.e. never transparently re-run).
 *
 * Extracted as a top-level function so it can be JVM-tested without loading
 * the [ExecutionCoordinator] object (which depends on `android.content.Context`
 * and `android.os.Debug`).
 *
 * [deep-audit-1015 ARCH-01] LEGACY: the production decision is now
 * [internalDecideShellRetryVerdict] (side-effect aware, via
 * [ToolRetrySafetyRegistry]). This predicate looks only at `exitCode == -1` and
 * therefore replays a DISPATCHED command of unknown safety — the exact blind
 * spot the audit found. Kept as the same-inputs comparison arm for the
 * before/after table and as the contract guard the existing tests pin.
 */
internal fun internalShouldRetryCommand(
    exitCode: Int,
    shellAlive: Boolean,
    attempt: Int,
    // Keep in sync with ExecutionCoordinator.MAX_AUTO_RETRIES (defaults to 2).
    maxRetries: Int = 2,
    /**
     * [fix/audit3-cancel-retry-1015 F-06] True when the in-flight memory
     * monitor recycled the shell to keep the app alive (PRoot child RSS or the
     * app's native heap crossed its dynamic ceiling). Same shape as the stall
     * and timeout guards above, but the reason is direct rather than inferred:
     * the shell died precisely BECAUSE this command was over the memory
     * ceiling, so a retry rebuilds a shell and replays the very command that
     * crossed it — it crosses it again (that is how it got there), doubling
     * the memory pressure the kill just relieved and repeating the side
     * effects of a non-idempotent command. The agent gets the death plus the
     * ceiling that was crossed ([internalMemoryRecycleNotice]) and picks a
     * smaller approach. Defaults to false so every pre-existing call site and
     * test keeps its exact semantics: an ordinary infra death (killed shell,
     * exit -1, nothing to do with memory) is still retried as before.
     */
    memoryRecycled: Boolean = false,
): Boolean {
    if (memoryRecycled) return false
    // [fix/memory-hardening-stall] A stall-killed command already burned its
    // whole no-progress window; retrying the SAME command would hang again for
    // the same reason. Hand the error to the agent instead and let it change
    // approach (see STALL_EXIT_CODE).
    if (exitCode == STALL_EXIT_CODE) return false
    // [audit-0916] Same shape as the stall guard above: a timeout burned the
    // command's whole window. The caller's no-retry path reclaims the shell
    // (killing the orphaned command) via
    // [internalShouldReclaimOnExhaustedTimeout], so the PTY-pollution hazard
    // this used to guard against is still covered — without re-running the
    // command. Guarded explicitly (not via `!shellAlive`) so a timeout is
    // never retried even if the shell happens to report dead.
    if (exitCode == TIMEOUT_EXIT_CODE) return false
    val shellDied = exitCode == -1 || !shellAlive
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
internal fun internalShouldReclaimOnExhaustedTimeout(exitCode: Int): Boolean = exitCode == TIMEOUT_EXIT_CODE

/**
 * [fix/audit3-cancel-retry-1015 F-06] Agent-facing explanation appended to the
 * output of a command whose shell was recycled mid-flight by the memory guard.
 *
 * Precedent: the stall guard appends its own reason line
 * ([com.rikkaminis.app.sandbox.stallMessage]) instead of handing back a bare
 * non-zero exit code. A memory kill is the same situation with a different
 * cause — the tool result would otherwise read as an unexplained `-1` and the
 * agent would have no way to tell "the sandbox killed this to stay alive"
 * apart from "the shell crashed". The reason (which ceiling, which measurement)
 * is carried through so the agent can size its next attempt.
 *
 * Kept as a top-level pure function so it is JVM-testable and so both the kill
 * reason and the wording have a single source.
 */
internal fun internalMemoryRecycleNotice(reason: String?): String = buildString {
    append("\n[memory-recycle] the command was killed by the sandbox memory guard")
    if (reason != null) append(": $reason")
    append(".")
    append(
        " The shell was recycled to keep the app alive and the command was NOT re-run — " +
            "replaying it would cross the same ceiling again. Reduce its memory footprint " +
            "(smaller batches, streaming instead of buffering, fewer parallel workers) " +
            "before retrying."
    )
}

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
 * - `exitCode == TIMEOUT_EXIT_CODE` → TIMEOUT (may have left zombies);
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
    exitCode == TIMEOUT_EXIT_CODE -> CommandFailureKind.TIMEOUT
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
 * command. Wraps [com.rikkaminis.app.agent.runtime.RetryPolicy.decideRetry]
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

/**
 * [deep-audit-1015 ARCH-01] Trusted tool name for the agent's shell tool, as
 * registered in [ToolRetrySafetyRegistry] (`shell_execute` → [RetrySafety.UNKNOWN]:
 * a command string can never be *proven* idempotent from the outside).
 */
internal const val SHELL_EXECUTE_TOOL_NAME = "shell_execute"

/**
 * [deep-audit-1015 ARCH-01] The production shape of the T3 decision: what to do
 * about this failure, and whether the result may be believed.
 *
 * [internalDecideShellRetry] answers only "may the command be re-run". This type
 * pairs that answer with [ExecutionCoordinator.CommandResult.outcomeKnown]
 * because the two must be decided together — replaying a non-idempotent command
 * and passing an untrustworthy result off as a known one are two halves of the
 * same mistake.
 */
internal data class ShellRetryVerdict(
    /**
     * true = re-issue the same command on a freshly built shell. Allowed only
     * when the replay is provably safe ([RetryOutcome.SafeToRetry]: read-only,
     * or an idempotent write with a verification hook) or when the command was
     * **never dispatched** (zero side effects — re-issuing it IS its first
     * execution).
     */
    val retry: Boolean,
    /**
     * false = the result is unknown ([RetryOutcome.OutcomeUnknown] /
     * `MustVerifyFirst`): the command may have run and its side effects may
     * have happened, but the returned output cannot be trusted. The caller must
     * neither replay a non-idempotent command on this basis nor treat a partial
     * result as completed.
     */
    val outcomeKnown: Boolean,
)

/**
 * [deep-audit-1015 ARCH-01] Wire the T3 contract into the production decision:
 * given the raw signals of one shell command, answer "replay?" and "believable?".
 *
 * The policy itself is [RetryPolicy.decideRetry]'s existing matrix — no new
 * semantics are invented here. Only two inputs are layer-specific:
 *  - [memoryRecycled]: the F-06 in-flight memory guard killed the shell. A
 *    replay rebuilds a shell and re-sends the very command that crossed the
 *    ceiling (it crosses it again), and the result of the killed command is not
 *    trustworthy either.
 *  - [commandDispatched]: whether the command actually reached the PTY. false =
 *    the shell was already unusable before the write
 *    (`PersistentShell.executeCommand` bails out at `writer == null || !isAlive`
 *    with `"[Shell not running]"`), so nothing ran and nothing can have had a
 *    side effect — here "retry" is a FIRST execution and must be kept. This is
 *    the one place the policy is more permissive than "never retry".
 *
 * The blind spot this closes: `shell_execute` is always [RetrySafety.UNKNOWN],
 * so once a command HAS been dispatched, a lost result (`exitCode == -1` /
 * dead shell / truncated output) must never be transparently re-run. The legacy
 * [internalShouldRetryCommand] looked only at `exitCode == -1` and therefore
 * replayed `git commit`, `rm -rf`, `curl -X POST`, `python3 deploy.py` — up to
 * `MAX_AUTO_RETRIES` total executions — after a HyperOS silent_kill, a tracer
 * OOM or a pipe break.
 */
internal fun internalDecideShellRetryVerdict(
    exitCode: Int,
    shellAlive: Boolean,
    truncated: Boolean,
    attempt: Int,
    safety: RetrySafety,
    maxRetries: Int = 2,
    hasVerification: Boolean = false,
    memoryRecycled: Boolean = false,
    commandDispatched: Boolean = true,
): ShellRetryVerdict {
    // [fix/audit3-cancel-retry-1015 F-06] Not an infra death: our own memory
    // monitor killed the shell because THIS command was over the ceiling.
    if (memoryRecycled) return ShellRetryVerdict(retry = false, outcomeKnown = false)

    // PRE_FLIGHT: the command never reached the PTY → zero side effects. Bounded
    // by the same retry budget, so the "rebuild the shell and re-run" ability
    // the legacy predicate provided for this case is preserved unchanged.
    if (!commandDispatched) {
        return ShellRetryVerdict(retry = attempt < maxRetries, outcomeKnown = true)
    }

    return when (
        internalDecideShellRetry(
            exitCode = exitCode,
            shellAlive = shellAlive,
            truncated = truncated,
            attempt = attempt,
            safety = safety,
            maxRetries = maxRetries,
            hasVerification = hasVerification,
        )
    ) {
        RetryOutcome.SafeToRetry -> ShellRetryVerdict(retry = true, outcomeKnown = true)
        RetryOutcome.DoNotRetry -> ShellRetryVerdict(retry = false, outcomeKnown = true)
        // Must-verify and outcome-unknown both mean "do not believe this result
        // and do not transparently replay it".
        RetryOutcome.MustVerifyFirst, RetryOutcome.OutcomeUnknown ->
            ShellRetryVerdict(retry = false, outcomeKnown = false)
    }
}

/**
 * [deep-audit-1015 BUG-09] Extra byte ceiling granted to the host-side
 * truncation pass so the diagnostics appended AFTER the shell-side clip are not
 * cut a second time.
 *
 * Must stay ≥ the longest suffix [ExecutionCoordinator.executeWithShellRetry]
 * can append to a clipped capture: [internalMemoryRecycleNotice] (~350 chars)
 * plus the `"(exit code: N)"` trailer. 1024 leaves ~2.5× headroom.
 */
internal const val SHELL_OUTPUT_TRUNCATION_SUFFIX_MARGIN_BYTES = 1024

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

// [deep-audit-1016 FIX-D 4a] How long a command waits for a global shell slot
// before the "System busy" rejection. Named (it used to be a bare `60` in the
// acquire call AND in the log line next to it — the two could drift apart, and
// the log is what a user report gets triaged against). Value unchanged.
private const val GLOBAL_SHELL_SLOT_TIMEOUT_SEC = 60L

/**
 * [deep-audit-1016 FIX-D 4a] Take the global shell slot, and on ANY failure
 * hand back the heavy permit the caller may already be holding.
 *
 * Why this exists as its own function: it is the only statement in `execute`
 * that can throw between acquiring the one global heavy permit (`heavyGate`,
 * `Semaphore(1, true)`) and entering the `try` whose `finally` releases it.
 * With 4b wrapping this wait in `runInterruptible`, an interrupt (user cancel)
 * on exactly this line would otherwise throw out of `execute` still holding the
 * permit — and a leaked heavy permit queues EVERY heavy command in the process
 * until it dies. Before 4b there was no interrupt source, so the window was
 * unreachable; the two halves ship together on purpose.
 *
 * Contract:
 *  - returns true  → the global slot is held; the caller still holds the heavy
 *                    permit (`heavyGateHeld`) and must release both.
 *  - returns false → plain timeout, nothing acquired. The heavy permit is left
 *                    to the caller (see the `if (!acquired)` branch), which
 *                    releases it before returning the "System busy" result.
 *  - throws        → the heavy permit has ALREADY been released; the caller
 *                    holds nothing and must not release again.
 *
 * Blocking by design: the caller runs it inside `runInterruptible(Dispatchers.IO)`
 * so a cancel interrupts the waiting thread. JVM-testable — plain
 * `java.util.concurrent.Semaphore`, no Android, no coroutines.
 */
internal fun internalAcquireGlobalShellSlot(
    globalConcurrency: Semaphore,
    heavyGate: Semaphore,
    heavyGateHeld: Boolean,
    timeoutSec: Long = GLOBAL_SHELL_SLOT_TIMEOUT_SEC,
): Boolean {
    return try {
        globalConcurrency.tryAcquire(timeoutSec, TimeUnit.SECONDS)
    } catch (t: Throwable) {
        // Interrupted while waiting (coroutine cancel) or any other throw:
        // give the heavy permit back before propagating, or this process never
        // runs another HEAVY command.
        if (heavyGateHeld) heavyGate.release()
        throw t
    }
}

/** [deep-audit-1016 FIX-D 4c] Where a recovery call should run. */
internal enum class RecycleDispatch { INLINE, DEFERRED }

/**
 * [deep-audit-1016 FIX-D 4c] The main-thread decision, split out so the
 * "who runs the GC loop" question is testable without an Android runtime.
 * `DEFERRED` = hand it to `recoveryScope`; `INLINE` = run it here (a blocking
 * GC loop is fine — and for the pre-exec rejection paths, required — on a
 * background thread).
 */
internal fun recycleDispatchMode(isOnMainThread: Boolean): RecycleDispatch =
    if (isOnMainThread) RecycleDispatch.DEFERRED else RecycleDispatch.INLINE

/**
 * [deep-audit-1016 FIX-D 4b] Wait for the heavy serialization gate in a way a
 * cancelled coroutine can actually break out of.
 *
 * `Semaphore.tryAcquire` is a blocking JUC call with no suspension point, so a
 * coroutine cancelled while parked in it stayed parked (the shell stayed
 * reserved behind it). `runInterruptible` interrupts the worker thread when the
 * coroutine is cancelled, `tryAcquire` throws `InterruptedException`, and that
 * surfaces as the cancellation the caller already handles.
 *
 * No permit is held when this throws (the interrupt lands while waiting, before
 * the semaphore hands anything over), so there is nothing to hand back here —
 * unlike [awaitGlobalShellSlotInterruptibly], which runs while a permit IS held.
 */
internal suspend fun awaitHeavyGatePermitInterruptibly(
    heavyGate: Semaphore,
    timeoutMs: Long,
): Boolean = runInterruptible(Dispatchers.IO) {
    heavyGate.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
}

/**
 * [deep-audit-1016 FIX-D 4b] Interruptible wait for a global shell slot.
 *
 * The body is [internalAcquireGlobalShellSlot] so the rollback of an already
 * held heavy permit is part of the same (directly JVM-testable) function that
 * runs inside the interruptible block — cancellation can land on any line of
 * it, and the permit must come back on all of them.
 */
internal suspend fun awaitGlobalShellSlotInterruptibly(
    globalConcurrency: Semaphore,
    heavyGate: Semaphore,
    heavyGateHeld: Boolean,
    timeoutSec: Long = GLOBAL_SHELL_SLOT_TIMEOUT_SEC,
): Boolean = runInterruptible(Dispatchers.IO) {
    internalAcquireGlobalShellSlot(globalConcurrency, heavyGate, heavyGateHeld, timeoutSec)
}
