package com.rikkaminis.app.service

import android.util.Log
import kotlinx.coroutines.CancellationException
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
     * [verify-1015 BUG-04] 取消若落在"已被提升、协程尚未真正恢复"的窗口内（CE 在
     * 挂起点抛出、调用方看不到返回值），已占用的槽位由 [releasePromotedAfterCancel]
     * 归还，保证取消路径不泄漏槽位。
     *
     * [memory-pressure-gate] 准入前检查进程 RSS 水线（[MemoryPressureGate]）：
     * ELEVATED 短暂等待 500ms（让回收动作生效）；CRITICAL 触发全局回收并等待 2s；
     * 扩散的目的不是拒绝准入（那会饿死重任务），而是给内存恢复争取时间——
     * 在大量 shell/WebView 线程堆积时避免新的 agent loop 立即把 RSS 推向硬门槛。
     */
    suspend fun acquireSlot(sessionId: String) {
        // 准入用瞬时纯分级（确定、可预期）：带滞回的状态机 [MemoryPressureGate.level]
        // 需要持续采样才能攒满置信拍，准入是一次性调用，用它只会恒返回 NORMAL。
        // [LOOP-1030 R1-g] 影子机与准入共用同一条读数：准入读数是亚秒突发的唯一
        // 来源（1s 周期采样四天峰值仅 564MB，准入侧 1032MB），影子必须活在被观测
        // 决策的同一条读数上。只观测，不驱动行为。
        val metrics = MemoryPressureGate.metricsReader()
        val sampled = metrics.anonMb
        MemoryPressureGate.observeShadowAtAdmission(sampled, metrics.rssMb, src = MemoryPressureGate.SRC_ADMISSION)
        val pressure = MemoryPressureGate.levelFor(sampled)
        MemoryPressureGate.notify(pressure, MemoryPressureGate.SRC_ADMISSION)
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
                } catch (e: CancellationException) {
                    // [verify-1015 BUG-04] 见 releasePromotedAfterCancel 的说明：
                    // 取消落在"已被提升、协程尚未真正恢复"的窗口时，槽位已计入
                    // active，而 cancelWaiter()/cleanupIfStillWaiting() 都因条目
                    // 已删或状态 PROMOTED 提前 return —— 调用方又拿不到返回值
                    // （CE 抛出），于是没人 release。这里补上。
                    releasePromotedAfterCancel(runId, sessionId)
                    throw e
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
     * [verify-1015 BUG-04] 取消落在"已提升、未恢复"窗口时，把那个已计入 active 的
     * 槽位释放掉。
     *
     * promoteLocked() 在两种形态下都会把 runId 记进 active：continuation 已注册则先删
     * pending 条目再 resume；尚未注册则保留 PROMOTED 条目等注册时立即恢复。若此刻
     * 协程已被取消，resume 被丢弃、调用方直接以 CancellationException 离开
     * acquireSlot —— 它不会进入持有 releaseSlot 的 try（业务侧 try 从 acquireSlot
     * 返回之后才开始），而 cancelWaiter() / cleanupIfStillWaiting() 又都提前 return
     * （前者 `pending[runId] ?: return`，后者 `p.state == PROMOTED -> return`）。
     * 结果：槽位永久泄漏；cap=2 时两次泄漏即全局排队死锁（后续所有会话永远排不上）。
     * 真机证据（2026-10-15）：该取消分支在生产日志中已出现
     * （CANCELLED waiting for slot，运行时 5 次），路径可达无需猜测。
     *
     * 按 runId 身份移除（不用 releaseSlot 的 removeLastOrNull）：同一 session 可能另有
     * 并发 run 已被提升，栈是 LIFO，而此处要精确释放"我这个"，否则会误放别人的槽位。
     */
    /**
     * [audit-1018 ISSUE-10] 临界区内只做状态迁移，resume 与打点交还
     * [finishPromotion] 在**锁外**执行（旧写法是 @Synchronized 方法体里直接调
     * promote，它内部的 `toResume?.resume(Unit)` 因此落在持锁线程上——注释与实现相反）。
     */
    private fun releasePromotedAfterCancel(runId: String, sessionId: String) {
        val promotion = synchronized(this) {
            val stack = activeRunIdsBySession[sessionId] ?: return@synchronized Promotion.NONE
            // 从未被提升（仍在排队）：排队清理路径已覆盖，不能重复释放。
            if (!stack.remove(runId)) return@synchronized Promotion.NONE
            pending.remove(runId)
            if (stack.isEmpty()) {
                activeRunIdsBySession.remove(sessionId)
                _runningSessions.value = _runningSessions.value - sessionId
            }
            val promoted = controller.release(runId)
            if (promoted != null) promoteLocked(promoted) else Promotion.NONE
        }
        finishPromotion(promotion)
    }

    /**
     * 释放该 session 最近获得的 active 槽位；无 active 槽位时 no-op（幂等）。
     * 释放后若等待队列非空，队首 waiter 被提升并恢复。
     *
     * [audit-1018 ISSUE-10] 临界区只覆盖状态迁移（本函数不再是 @Synchronized 方法），
     * 被提升 waiter 的 resume 由 [finishPromotion] 在锁外执行。
     */
    fun releaseSlot(sessionId: String) {
        val promotion = synchronized(this) {
            val stack = activeRunIdsBySession[sessionId] ?: return@synchronized Promotion.NONE
            val runId = stack.removeLastOrNull() ?: return@synchronized Promotion.NONE
            // [fix/audit-b19 / T8-L1] Only drop the session from the running set
            // when its last active run goes away. The old unconditional removal
            // made a session with two concurrent runs look idle after one of them
            // finished (occupancy() is read by RuntimeLimitsScreen).
            if (stack.isEmpty()) {
                activeRunIdsBySession.remove(sessionId)
                _runningSessions.value = _runningSessions.value - sessionId
            }
            val promoted = controller.release(runId)
            if (promoted != null) promoteLocked(promoted) else Promotion.NONE
        }
        finishPromotion(promotion)
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

    /** 该 session 是否还有其他仍在等待的 waiter（PROMOTED 的已计入 running）。 */
    private fun hasOtherWaiting(sessionId: String, except: PendingRun?): Boolean =
        pending.values.any {
            it !== except && it.sessionId == sessionId && it.state != PendingRun.State.PROMOTED
        }

    /**
     * [promoteLocked] 的结果：本次是否真的发生了提升（[D-2] 打点用）+ 需要由调用方在
     * **锁外** resume 的 continuation（null = 提升发生在挂起注册之前，PROMOTED 条目
     * 保留，等挂起块注册时立即恢复，避免 resume 丢失）。
     */
    private class Promotion(val happened: Boolean, val toResume: CancellableContinuation<Unit>?) {
        companion object {
            /** 无提升（runId 不在队列 / 已提升过）。 */
            val NONE = Promotion(happened = false, toResume = null)
        }
    }

    /**
     * 锁内核心：将 [runId] 对应的 waiter 提升为 active。
     *
     * **调用方必须已持有本对象锁**（本函数自身不加锁，好让 [releaseSlot] /
     * [releasePromotedAfterCancel] 在同一个临界区里完成"释放+提升"）；它只做状态迁移，
     * 把需要在锁外执行的 resume 交还调用方。这是本类的硬约定 —— resume 在持锁线程上
     * 执行会让被唤醒 waiter 的后续回调排在本对象锁之后，[audit-1018 ISSUE-10] 修的就是
     * 旧实现把这层分工架空（注释宣称"锁外 resume"，实际两个调用方都是 @Synchronized）。
     */
    private fun promoteLocked(runId: String): Promotion {
        val p = pending[runId] ?: return Promotion.NONE
        if (p.state == PendingRun.State.PROMOTED) return Promotion.NONE // 只提升一次
        p.state = PendingRun.State.PROMOTED
        // [fix/audit-0917-b9] 仅在“本 session 没有别的 waiter 还在等”时清挂起标记：
        // 同一会话并发两次发送时两个 run 共用一个 sessionId，提升第一个就清标记，
        // 会让仍在排队的第二个在 UI 上不再显示“等待中”。PROMOTED 的不算（已进 running）。
        if (!hasOtherWaiting(p.sessionId, p)) {
            _suspendedSessions.value = _suspendedSessions.value - p.sessionId
        }
        _runningSessions.value = _runningSessions.value + p.sessionId
        activeRunIdsBySession.getOrPut(p.sessionId) { ArrayDeque() }.addLast(runId)
        val cont = p.continuation ?: return Promotion(happened = true, toResume = null)
        // continuation 已注册：先删 pending 条目再把它交出去 resume。
        pending.remove(runId)
        return Promotion(happened = true, toResume = cont)
    }

    /**
     * 锁外收尾：resume 被 [promoteLocked] 提升的 waiter，然后打 [D-2] 点。
     *
     * **绝不可以在持有本对象锁时调用** —— 见 [promoteLocked]。
     */
    private fun finishPromotion(promotion: Promotion) {
        promotion.toResume?.resume(Unit)
        if (!promotion.happened) return
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
            // [fix/audit-0917-b9] 同 promote：还有别的 waiter 在等就保留标记，
            // 否则取消一个排队项会让另一个仍在排队的 run 失去“等待中”显示。
            if (!hasOtherWaiting(p.sessionId, null)) {
                _suspendedSessions.value = _suspendedSessions.value - p.sessionId
            }
            controller.cancel(runId)
        }
    }

    /** 防御性清理：挂起块异常等未预期路径下移除残留 waiter。 */
    private fun cleanupIfStillWaiting(runId: String) {
        synchronized(this) {
            val p = pending[runId] ?: return
            if (p.state == PendingRun.State.PROMOTED) return // resume 已发出，等待收敛
            pending.remove(runId)
            if (!hasOtherWaiting(p.sessionId, null)) {
                _suspendedSessions.value = _suspendedSessions.value - p.sessionId
            }
            controller.cancel(runId)
        }
    }
}
