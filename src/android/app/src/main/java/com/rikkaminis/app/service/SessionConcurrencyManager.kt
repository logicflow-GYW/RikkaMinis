package com.rikkaminis.app.service

import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Limits concurrent agent loop sessions to [MAX_CONCURRENT].
 * Excess sessions are suspended in a FIFO queue until a slot frees up.
 *
 * T1（stability/T1-session-concurrency）重构：并发语义全部收敛到
 * [SessionSlotController]（纯 JVM 状态机，容量检查与写入同一同步区间）；
 * 本对象只做 coroutine / StateFlow 适配层。行为变化（修复）：
 *
 * - 每个 acquire 请求生成唯一 runId，同 sessionId 的并发 run 各自独立占槽，
 *   不再被 `Set<String>` 按 sessionId 静默折叠；
 * - 取消中的 waiter 从队列原子移除，永远不会在取消后被 resume 成 active；
 * - release / cancel 幂等，重复调用不释放他人的槽位；
 * - FIFO waiter 严格按序提升。
 *
 * 对外 API（acquireSlot / releaseSlot / runningSessions / suspendedSessions /
 * isSuspended / MAX_CONCURRENT）签名与语义保持兼容；`runningSessions` /
 * `suspendedSessions` 仍按 sessionId 聚合展示（同 session 多 run 时 Set/List
 * 去重是展示层行为，不影响内部按 runId 的精确并发控制）。
 */
object SessionConcurrencyManager {
    // [native-rss-tool-guard / D-2] 与 ExecutionCoordinator / NativeOffloadServer
    // 三处对齐的跨会话并发上限。2026-08-19 现场：多个并发 agent 会话共享主进程
    // native/mmap 资源，可在几分钟内把主进程 RSS 推到 5.8–6.0GB 并触发 SIGABRT。
    // Phase 0 先钳到 2 槽；D-2 参数化为 [ConcurrencyPrefs] 可配置 + 可观测，让用户
    // 长期用 2 槽、读出占用后评估放宽到 3。默认值保持 2（与 Phase 0 一致），
    // 三处仍共用同一配置源，避免单点放松导致其他路径冲破内存预算。
    val maxConcurrent: Int get() = com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()

    /** 关键打点 tag —— 真机 `logcat -b all | grep 'concurrency'` 观测槽占用。 */
    private const val TAG = "ConcurrencyProbe"

    // 延迟到首次访问（Prime 已跑后）构造，容量从配置读；运行期不再重建。
    private val controller: SessionSlotController by lazy {
        SessionSlotController(maxConcurrent = maxConcurrent)
    }

    private val _runningSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningSessions: StateFlow<Set<String>> = _runningSessions.asStateFlow()

    private val _suspendedSessions = MutableStateFlow<List<String>>(emptyList())
    val suspendedSessions: StateFlow<List<String>> = _suspendedSessions.asStateFlow()

    private class PendingRun(
        val sessionId: String,
        val runId: String,
    ) {
        /** WAITING：仍在队列中；PROMOTED：已被提升为 active，等待/已经 resume。 */
        var state: State = State.WAITING

        /** 挂起中的 continuation；在 suspendCancellableCoroutine 块内注册。 */
        var continuation: CancellableContinuation<Unit>? = null

        enum class State { WAITING, PROMOTED }
    }

    /** runId -> 等待中的 run（跨挂起点存续）。 */
    private val pending = HashMap<String, PendingRun>()

    /** sessionId -> 该 session 当前 active 的 runId 栈（release 释放最近获得的 run）。 */
    private val activeRunIdsBySession = HashMap<String, ArrayDeque<String>>()

    /**
     * 等待获得一个会话并发槽位。
     * 容量未满立即返回；已满则进入 FIFO 队列挂起，直到 [releaseSlot] / 其它释放提升。
     * 协程被取消时从队列原子移除并以 [kotlinx.coroutines.CancellationException] 结束，
     * 不会在取消后又被提升为 active。
     *
     * [memory-pressure-gate] 准入前检查进程 RSS 水线（[MemoryPressureGate]）：
     * ELEVATED 短暂等待 500ms（让回收动作生效）；CRITICAL 触发全局回收并等待 2s；
     * 扩散的目的不是拒绝准入（那会饿死重任务），而是给内存恢复争取时间——
     * 在大量 shell/WebView 线程堆积时避免新的 agent loop 立即把 RSS 推向硬门槛。
     */
    suspend fun acquireSlot(sessionId: String) {
        val pressure = MemoryPressureGate.level()
        MemoryPressureGate.notify(pressure)
        if (pressure == MemoryPressureLevel.CRITICAL) {
            MemoryPressureGate.reclaimAndWait()
        } else if (pressure == MemoryPressureLevel.ELEVATED) {
            delay(500L)
        }

        val runId = controller.newRunId()
        val queuedForLog = BooleanArray(1)
        val outcome: SessionSlotController.AcquireOutcome = synchronized(this) {
            val r = controller.acquire(runId)
            when (r) {
                is SessionSlotController.AcquireOutcome.Acquired -> {
                    _runningSessions.value = _runningSessions.value + sessionId
                    activeRunIdsBySession.getOrPut(sessionId) { ArrayDeque() }.addLast(runId)
                }
                is SessionSlotController.AcquireOutcome.Queued -> {
                    queuedForLog[0] = true
                    _suspendedSessions.value = _suspendedSessions.value + sessionId
                    pending[runId] = PendingRun(sessionId, runId)
                }
                is SessionSlotController.AcquireOutcome.Duplicate -> {
                    // 生产路径不可达：runId 由 controller.newRunId() 保证唯一。
                }
            }
            r
        }
        if (queuedForLog[0]) {
            // [D-2] 排队 = 当前并发已打满上限。真机观测：queue 打点越频繁，
            // 说明 2 槽越不够 —— 它就是"判断容量够不够"的直接证据。
            Log.i(
                TAG,
                "[concurrency] queue session=$sessionId " +
                    "active=${controller.snapshot().activeCount} " +
                    "waiting=${controller.snapshot().waitingCount} " +
                    "cap=${maxConcurrent}",
            )
        }
        when (outcome) {
            is SessionSlotController.AcquireOutcome.Acquired -> return
            is SessionSlotController.AcquireOutcome.Queued -> {
                try {
                    suspendCancellableCoroutine<Unit> { cont ->
                        var resumeNow: CancellableContinuation<Unit>? = null
                        synchronized(this) {
                            val p = pending[runId]
                            if (p != null) {
                                p.continuation = cont
                                if (p.state == PendingRun.State.PROMOTED) {
                                    // 挂起注册前已被 release 提升：不真正挂起，立即恢复。
                                    pending.remove(runId)
                                    resumeNow = cont
                                }
                                cont.invokeOnCancellation { cancelWaiter(runId) }
                            }
                        }
                        resumeNow?.resume(Unit)
                    }
                } finally {
                    // 防御性收敛：正常/取消路径均已清理，此处只处理未预期的残留。
                    cleanupIfStillWaiting(runId)
                }
            }
            is SessionSlotController.AcquireOutcome.Duplicate -> {
                // 生产不可达；显式失败而不是静默折叠。
                error("duplicate runId $runId for session $sessionId")
            }
        }
    }

    /**
     * 释放该 session 最近获得的 active 槽位；无 active 槽位时 no-op（幂等）。
     * 释放后若等待队列非空，队首 waiter 被提升并恢复。
     */
    @Synchronized
    fun releaseSlot(sessionId: String) {
        val stack = activeRunIdsBySession[sessionId] ?: return
        val runId = stack.removeLastOrNull() ?: return
        // [fix/audit-b19 / T8-L1] Only drop the session from the running set
        // when its last active run goes away. The old unconditional removal
        // made a session with two concurrent runs look idle after one of them
        // finished (occupancy() is read by RuntimeLimitsScreen).
        if (stack.isEmpty()) {
            activeRunIdsBySession.remove(sessionId)
            _runningSessions.value = _runningSessions.value - sessionId
        }
        val promoted = controller.release(runId)
        if (promoted != null) promote(promoted)
    }

    fun isSuspended(sessionId: String): Boolean = sessionId in _suspendedSessions.value

    /**
     * [D-2] 当前并发槽占用快照（active 运行中 / waiting 排队中）。
     * UI / 诊断直接读它判断"cap 够不够"：waiting > 0 说明有会话在排队。
     */
    data class Occupancy(val active: Int, val waiting: Int) {
        val total: Int get() = active + waiting
    }

    fun occupancy(): Occupancy {
        val snap = controller.snapshot()
        return Occupancy(active = snap.activeCount, waiting = snap.waitingCount)
    }

    /** 仅测试用：清空全部状态（生产路径不调用）。 */
    internal fun resetForTesting() {
        synchronized(this) {
            pending.clear()
            activeRunIdsBySession.clear()
            _runningSessions.value = emptySet()
            _suspendedSessions.value = emptyList()
            controller.resetForTesting()
        }
    }

    /** 将 [runId] 对应的 waiter 提升为 active 并恢复其协程（在锁外 resume，避免死锁）。 */
    private fun promote(runId: String) {
        var toResume: CancellableContinuation<Unit>? = null
        synchronized(this) {
            val p = pending[runId] ?: return
            if (p.state == PendingRun.State.PROMOTED) return // 只提升一次
            p.state = PendingRun.State.PROMOTED
            _suspendedSessions.value = _suspendedSessions.value - p.sessionId
            _runningSessions.value = _runningSessions.value + p.sessionId
            activeRunIdsBySession.getOrPut(p.sessionId) { ArrayDeque() }.addLast(runId)
            if (p.continuation != null) {
                pending.remove(runId)
                toResume = p.continuation
            }
            // continuation == null：提升发生在 acquireSlot 挂起注册之前（线程抢占窗口），
            // 保留 PROMOTED 条目，等挂起块注册 continuation 时立即恢复，避免 resume 丢失。
        }
        toResume?.resume(Unit)
        // [D-2] 槽位释放后队列头部被提升：记录释放后的占用，配合 queue 打点完整还原
        // 每一轮占压-释放周期，判断 cap 是否够用。
        val snap = controller.snapshot()
        Log.i(TAG, "[concurrency] promote active=${snap.activeCount} waiting=${snap.waitingCount} cap=${maxConcurrent}")
    }

    /** 协程取消回调：从队列原子移除；已被提升的过期取消直接忽略。 */
    private fun cancelWaiter(runId: String) {
        synchronized(this) {
            val p = pending[runId] ?: return
            if (p.state == PendingRun.State.PROMOTED) return // 已提升，忽略过期取消
            pending.remove(runId)
            _suspendedSessions.value = _suspendedSessions.value - p.sessionId
            controller.cancel(runId)
        }
    }

    /** 防御性清理：挂起块异常等未预期路径下移除残留 waiter。 */
    private fun cleanupIfStillWaiting(runId: String) {
        synchronized(this) {
            val p = pending[runId] ?: return
            if (p.state == PendingRun.State.PROMOTED) return // resume 已发出，等待收敛
            pending.remove(runId)
            _suspendedSessions.value = _suspendedSessions.value - p.sessionId
            controller.cancel(runId)
        }
    }
}
