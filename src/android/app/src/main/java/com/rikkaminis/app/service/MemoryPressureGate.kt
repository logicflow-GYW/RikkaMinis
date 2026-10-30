package com.rikkaminis.app.service

import kotlinx.coroutines.delay
import java.io.File

/**
 * 进程级内存水线门卫（纯 JVM 可测，无 Android 依赖）。主指标 RssAnon。
 *
 * 背景（2026-08-15 早上 OOM 崩溃分析）：`com.rikkaminis.app` 在 08:25-08:27
 * 连续 3 次 `pthread_create (1040KB stack) failed`——这是 **native 内存耗尽**
 * 而非 Java heap OOM。现有 [com.rikkaminis.app.sandbox.ExecutionCoordinator]
 * 的 P2-app-native-oom 防御只看 `Debug.getNativeHeapAllocatedSize()`（app
 * native heap），对 **进程 RSS 中的线程栈 / mmap 部分完全盲区**（崩溃时
 * native heap 可能是 100MB 而 RSS 已 280MB+）。本类补上 RSS 维度。
 *
 * 口径（2026-09-13 修正，数据见 shared/memory-defense-gap-audit-2026-09-13.md）：
 * 本门原本读 VmRSS。实测本机健康态 p50 265MB 中 **file 页恒为 ~147MB**（dex/
 * so/mmap，内核随时可回收），RssAnon 只有 ~101MB —— 也就是 **61% 的信号是
 * "能还的账"**，且压力态 file 仍恒定（159MB）。而本门立项要防的正是**线程栈 /
 * mmap**（见上段：native heap 账本看不到的那部分），那部分本来就在 anon 里。
 * 因此主指标改为 **RssAnon**，VmRSS 降为伴生记录（日志/对照用）。
 *
 * 阈值依据（2026-09-13 重定）：健康 anon p50 101 / p90 133 / max 145MB（83 样本）；
 * 已观测尖峰 anon 1603MB（自愈，未拒绝）；已观测压力点 anon 1370MB（当时拒绝是
 * 对的）；崩溃点 VmRSS 6043MB → anon≈5.9GB。旧阈值 600/800 VmRSS 换算成 anon
 * 只有 453/653MB，即"基线的 4.5 / 6.5 倍"，比注释本意松得多，却会在正常重活时
 * 误拒。现取：
 *   - SOFT 450MB（基线 4.4 倍、离死亡点 13 倍）→ 只做可重建的丢弃；
 *   - HARD 1200MB（最后已知压力点附近、离死亡点 5 倍）→ 回收 + 拒绝新准入。
 * 准入层敢放到 1200，是因为"工具自身膨胀"另有 in-flight 看门狗兜底（单命令
 * 子进程动态封顶 1GB），两者分工：门管"app 已经深压时不再加料"，看门狗管
 * "这一条命令别跑飞"。
 *
 * 滞回 + 置信拍：健康 anon 在 55~145MB 间波动（2.6 倍摆幅），单次采样定生死必然
 * 抖动。升档需连续 [CONFIRM_TICKS] 拍；降档只认"跌破本档线 - [HYSTERESIS_ANON_MB]"。
 *
 * 可测试性：metricsReader / reclaimHook / pressureListener 全部可注入，
 * 生产路径在 [com.rikkaminis.app.MinisApp] 装配。
 */
enum class MemoryPressureLevel { NORMAL, ELEVATED, CRITICAL }

object MemoryPressureGate {

    /** 预警水线（MB, RssAnon）：超过后触发回收 + 由治理器持续卸压。 */
    const val ELEVATED_ANON_MB = 450L

    /** 硬门槛（MB, RssAnon）：超过后回收 + 拒绝新工具/新 session 准入。 */
    const val CRITICAL_ANON_MB = 1200L

    /** 滞回带宽（MB）：只有跌破"本档线 - 该值"才降档，避免边界抖动。 */
    const val HYSTERESIS_ANON_MB = 150L

    /**
     * 影子硬门槛（MB, RssAnon）—— **只观测，不驱动任何行为**。
     *
     * 依据（[SYMPTOM-A F-A1]，2026-10-29 夜对三天全量日志实测）：anon 峰值
     * **1032 / 585 / 552MB**，而 [CRITICAL_ANON_MB]=1200 的"回收 + 拒绝准入"
     * 分支 **三天 0 次**触发（`level=CRITICAL` 计数为 0）—— 这条硬分支在生产
     * 里从未执行过，而进程确实在 500–1032MB 区间被系统侧回收过（worker
     * `DIED_MID_STREAM`：10-28 11 次 / 64 run）。
     *
     * 这台影子机要回答的正是"把硬门槛下调到 800 会不会天天触发"：在**准入采样点**
     * （[observeShadowAtAdmission]，与真档 [levelFor] 的同一条读数）推进一台
     * **独立**状态机，只在穿越这条线（含跌回）时打一行 `[MemGateShadow]` 日志。
     * 它不参与 [level] / [levelFor] / [shouldRejectAfterReclaim]，也不碰回收与准入。
     *
     * ⚠ 这条线**只在准入点可观测**：它测的是亚秒突发（10-28 18:57:31 三连读
     * 866/1032/882MB），周期性采样（1s tick）四天峰值仅 564MB，看不到它。
     *
     * **何时读这条日志**：有人要动 [CRITICAL_ANON_MB] 的时候。`grep MemGateShadow`
     * 的行数 × 时间窗 = 假设水线下的触发频率；`src=shell-post-reclaim` 的行单独看
     * ——那是"回收完仍然越线"的，等于假设水线下的拒绝准入。拿到这个数之前不要
     * 凭感觉调阈值。
     */
    const val OBSERVE_CRITICAL_ANON_MB = 800L

    /** 升档需要的连续采样拍数（单次越线不算）。 */
    const val CONFIRM_TICKS = 2

    /** 一次 /proc/self/status 读取得到的两种口径。 */
    data class Metrics(val anonMb: Long, val rssMb: Long)

    /** 可注入的进程内存读取器。生产读 /proc/self/status；测试注入 fake。 */
    @Volatile
    var metricsReader: () -> Metrics = { readMetricsFromProc() }

    /** 主指标：RssAnon（MB）—— 内核回收不掉的私有匿名页。 */
    fun anonMb(): Long = metricsReader().anonMb

    /** 伴生指标：VmRSS（MB）—— 含 file 页，只用于日志与对照。 */
    fun rssMb(): Long = metricsReader().rssMb

    /** [level] 状态机：当前稳定档位。 */
    @Volatile
    private var currentLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL

    /** [level] 状态机：连续越线拍数。 */
    private var confirmingTicks: Int = 0

    /**
     * 影子机状态（[OBSERVE_CRITICAL_ANON_MB]）：**与真档完全独立的变量**。
     * 红线：影子机不得读写 [currentLevel] / [confirmingTicks] —— 一旦共用，
     * 一次观测就会改变真实分级结果。
     */
    @Volatile
    private var shadowLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL

    /** 测试用：把状态机复位（生产不需要——它本来就是全程累积的）。 */
    internal fun resetTierStateForTest() {
        currentLevel = MemoryPressureLevel.NORMAL
        confirmingTicks = 0
        lastNotifiedLevel = MemoryPressureLevel.NORMAL
        shadowLevel = MemoryPressureLevel.NORMAL
    }

    /** 上一次上报过的档位（用于档位跃迁事件，含降档）。 */
    @Volatile
    private var lastNotifiedLevel: MemoryPressureLevel = MemoryPressureLevel.NORMAL

    /**
     * 可注入的全局回收动作。生产（MinisApp 装配）：回收 idle shells +
     * 释放 WebView 标签 + System.gc()。测试注入 spy。
     */
    @Volatile
    var reclaimHook: () -> Unit = {}

    /**
     * 可注入的压力通知。生产（MinisApp 装配）接到 AppLogger；
     * 测试注入计数器。只在 ELEVATED / CRITICAL 时触发。
     *
     * [LOOP-1030 R1-b] 参数从 (level, anonMb) 扩到四个：
     * - `src`：采样点（[SRC_TICK] 1s 治理器 / [SRC_ADMISSION] 准入路径）。旧签名让
     *   两者在日志里完全同形——`MemoryPressureGate` 四天 3881 行里真实准入 ≤29 行，
     *   其余全是 tick，却被逐行标成 "admission delayed 500ms"，审计据此把
     *   「门升到 3875 次」读成事实。
     * - `crossed`：本次是否是**真越线**（档位相对上一次通知发生变化）。滞回带
     *   （anon 300–450 的 ELEVATED 续报）占 ELEVATED 行的 96.2%，不带这一位就
     *   无法把「刚越过 450」与「还挂在 380」分开。
     */
    @Volatile
    var pressureListener: (MemoryPressureLevel, Long, String, Boolean) -> Unit = { _, _, _, _ -> }

    /** 采样点：1s 治理器 tick（只观测，不产生准入/回收动作）。 */
    const val SRC_TICK = "tick"

    /** 采样点：准入路径（acquireSlot）——这里的延迟/回收才是真的发生在请求上。 */
    const val SRC_ADMISSION = "agent-admission"

    /**
     * 当前压力级别：带滞回 + 置信拍的状态机（每次调用推进一格采样状态）。
     * 单次越线不升档；跌破"本档线 - 滞回"才降档。用于持续采样方（治理器）
     * 与准入路径，得到的是"稳定分级"而非瞬时读数。
     */
    fun level(): MemoryPressureLevel {
        val anon = anonMb()
        val step = internalNextTier(currentLevel, confirmingTicks, anon)
        confirmingTicks = step.confirming
        currentLevel = step.level
        return step.level
    }

    /**
     * 采样拍：推进状态机并上报（含档位跃迁事件）。
     * 持续采样方（1s 治理器 tick）调用；**准入路径请用 [levelFor]** —— 它是一次性
     * 决策，需要确定、可预期的瞬时分级，而不是"攒够置信拍才升档"的状态机
     * （准入路径本身不持续采样，置信计数永远攒不满，用 [level] 会静默恒返回 NORMAL）。
     */
    fun sampleAndNotify(): MemoryPressureLevel {
        val sampled = level()
        // 注意：影子机**不在这里**推进（[LOOP-1030 R1-g]）——周期采样看不到亚秒
        // 突发，影子只挂在准入读数上，见 [observeShadowAtAdmission]。
        notify(sampled, SRC_TICK)
        return sampled
    }

    /**
     * [SYMPTOM-A F-A1] 影子水线（[OBSERVE_CRITICAL_ANON_MB]）：只在**穿越**该线时上报一行。
     *
     * ★ [LOOP-1030 R1-g 修正 2026-10-30] **挂接点 = 准入采样点，不是 1s tick**。
     * 上一版挂在 [sampleAndNotify]，实测结构上看不到要找的东西：周期采样四天
     * 峰值 anon 仅 527 / 566 / 514 / 454MB（`memspike-2026-10-28.log` 12,111 样本
     * max 564），而越过 800 的读数只出现在**准入的亚秒突发**里（10-28 18:57:31
     * 三连读 866 / 1032 / 882MB）。⇒ 挂在 tick 上 `grep MemGateShadow` 永远 0 行，
     * 把"800 从不触发"的**假阴性**喂给要调阈值的人——探针比没有更坏。
     *
     * 为什么准入点才是对的位置：这台影子机要回答的是"若把 [CRITICAL_ANON_MB] 降到
     * 800，回收 + 拒绝准入分支会不会触发"，而真档那条分支本身就是在准入点用
     * [levelFor] 做的**瞬时**判定。观测探针必须活在被观测决策的同一条读数上。
     *
     * 两条设计约束：
     *  - **无置信拍**：真档 CRITICAL 是瞬时判定，影子加拍数确认会把"第一个准入
     *    读数就够 800"这一事实抹掉。（真档 [level] 的置信拍属于 1s 状态机，
     *    那是另一件事，影子不模拟它。）
     *  - **读数由调用方传入**：准入路径本来就已经读了一次 `/proc/self/status`，
     *    影子复用这次读数（[resetTierStateForTest] 以外的零额外 IO）。
     */
    internal fun observeShadowAtAdmission(anonMbValue: Long, rssMbValue: Long, src: String) {
        val from = shadowLevel
        val to = when {
            from == MemoryPressureLevel.NORMAL && anonMbValue >= OBSERVE_CRITICAL_ANON_MB ->
                MemoryPressureLevel.CRITICAL
            // 跌破"线 − 滞回"才降档：一次突发簇（866/1032/882 三连读）折成
            // 一升一降两行，而不是每个读数一行。
            from == MemoryPressureLevel.CRITICAL &&
                anonMbValue < OBSERVE_CRITICAL_ANON_MB - HYSTERESIS_ANON_MB ->
                MemoryPressureLevel.NORMAL
            else -> from
        }
        if (to != from) {
            shadowLevel = to
            shadowTierListener(src, from, to, anonMbValue, rssMbValue)
        }
    }

    /** 瞬时分级（纯函数，无滞回/置信）——准入判定用，行为确定可预期。 */
    fun levelFor(anonMb: Long): MemoryPressureLevel = when {
        anonMb >= CRITICAL_ANON_MB -> MemoryPressureLevel.CRITICAL
        anonMb >= ELEVATED_ANON_MB -> MemoryPressureLevel.ELEVATED
        else -> MemoryPressureLevel.NORMAL
    }

    /**
     * [native-rss-tool-guard] 回收后仍处于硬门槛是否应拒绝新执行。
     * 纯函数可单测：主进程私有匿名内存已超过 1200MB 时，即使刚触发过全局
     * 回收，也不应继续接受会进一步堆 native/mmap 的工具请求。用原始读数
     * （非滞回档）判定：拒绝是保守动作，宁可多拒一次。
     */
    fun shouldRejectAfterReclaim(anonMB: Long): Boolean = anonMB >= CRITICAL_ANON_MB

    /** 读取当前进程的 anon / RSS 两种口径（MB）。读失败返回 0（安全侧：不降级）。 */
    fun readMetricsFromProc(): Metrics {
        return try {
            parseMetrics(File("/proc/self/status").readText())
        } catch (t: Throwable) {
            Metrics(anonMb = 0L, rssMb = 0L)
        }
    }

    /** 从 /proc/self/status 文本解析 anon/RSS。缺 RssAnon（老内核）则退回 VmRSS。 */
    internal fun parseMetrics(statusText: String): Metrics {
        val anon = parseKbField(statusText, "RssAnon:")
        val rss = parseKbField(statusText, "VmRSS:")
        return Metrics(anonMb = (if (anon > 0L) anon else rss) / 1024L, rssMb = rss / 1024L)
    }

    /** 从 /proc/self/status 文本解析 VmRSS（MB）。无 VmRSS 行返回 0。 */
    internal fun parseVmRss(statusText: String): Long =
        parseKbField(statusText, "VmRSS:") / 1024L

    /** `<Field>:  NNN kB` → NNN。缺失或畸形返回 0。 */
    private fun parseKbField(statusText: String, field: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith(field) } ?: return 0L
        return line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
    }

    /** 触发一次全局回收 + 短暂等待让回收生效。 */
    suspend fun reclaimAndWait(waitMs: Long = 2_000L) {
        reclaimHook()
        delay(waitMs)
    }

    /**
     * 通知压力事件（只对非 NORMAL 级别；NORMAL 静默）。带回 anon 主指标。
     * `src` 必填：调用方必须说明这是周期采样还是准入路径（[LOOP-1030 R1-b]）——
     * 两种来源在旧签名下不可区分，日志上把 tick 全读成了准入延迟。
     */
    fun notify(level: MemoryPressureLevel, src: String) {
        if (level != MemoryPressureLevel.NORMAL) {
            // 档位变化（含降回 NORMAL）也上报，供"正常重活会不会常穿越梯子"统计。
            val anon = anonMb()
            val crossed = level != lastNotifiedLevel
            if (crossed) {
                tierListener(lastNotifiedLevel, level, anon)
                lastNotifiedLevel = level
            }
            pressureListener(level, anon, src, crossed)
        } else if (lastNotifiedLevel != MemoryPressureLevel.NORMAL) {
            tierListener(lastNotifiedLevel, MemoryPressureLevel.NORMAL, anonMb())
            lastNotifiedLevel = MemoryPressureLevel.NORMAL
        }
    }
}

/** 档位跃迁（用于日志/探针事件：升档、降档都记）。 */
@Volatile
var tierListener: (from: MemoryPressureLevel, to: MemoryPressureLevel, anonMb: Long) -> Unit =
    { _, _, _ -> }

/**
 * [SYMPTOM-A F-A1] **影子水线**跃迁（[MemoryPressureGate.OBSERVE_CRITICAL_ANON_MB]，
 * 800MB anon）——只观测，不驱动任何行为。
 *
 * 与 [tierListener] 分开而不是复用它：tierListener 的口径是"生产真档穿越频率"
 * （450 / 1200 两条线），混进影子机的读数会让那个统计失去意义。
 *
 * `src` = 采样点标识（`agent-admission` / `shell-admission` / `shell-post-reclaim`）：
 * 同一个影子状态机被多个准入点推进，日志必须能区分"哪个决策点看到它越线"
 * ——特别是 `shell-post-reclaim`，它意味着回收之后仍然在 800 之上。
 * 生产（MinisApp 装配）打到 tag `MemGateShadow`；测试注入计数器。
 */
@Volatile
var shadowTierListener: (
    src: String,
    from: MemoryPressureLevel,
    to: MemoryPressureLevel,
    anonMb: Long,
    rssMb: Long,
) -> Unit = { _, _, _, _, _ -> }

/**
 * 纯函数：带滞回与置信拍的档位推进。
 *
 * - 升档需连续 [MemoryPressureGate.CONFIRM_TICKS] 拍越线（`confirming` 累计）；
 * - 降档只认跌破"本档线 − [MemoryPressureGate.HYSTERESIS_ANON_MB]"；
 * - 读数落回当前档内即清零置信计数（必须连续）。
 */
internal fun internalNextTier(
    current: MemoryPressureLevel,
    confirming: Int,
    anonMb: Long,
    elevatedMb: Long = MemoryPressureGate.ELEVATED_ANON_MB,
    criticalMb: Long = MemoryPressureGate.CRITICAL_ANON_MB,
    hysteresisMb: Long = MemoryPressureGate.HYSTERESIS_ANON_MB,
    confirmTicks: Int = MemoryPressureGate.CONFIRM_TICKS,
): TierStep {
    val target = when (current) {
        MemoryPressureLevel.CRITICAL -> when {
            anonMb >= criticalMb - hysteresisMb -> MemoryPressureLevel.CRITICAL
            anonMb >= elevatedMb -> MemoryPressureLevel.ELEVATED
            anonMb >= elevatedMb - hysteresisMb -> MemoryPressureLevel.ELEVATED
            else -> MemoryPressureLevel.NORMAL
        }

        MemoryPressureLevel.ELEVATED -> when {
            anonMb >= criticalMb -> MemoryPressureLevel.CRITICAL
            anonMb >= elevatedMb - hysteresisMb -> MemoryPressureLevel.ELEVATED
            else -> MemoryPressureLevel.NORMAL
        }

        MemoryPressureLevel.NORMAL -> when {
            anonMb >= criticalMb -> MemoryPressureLevel.CRITICAL
            anonMb >= elevatedMb -> MemoryPressureLevel.ELEVATED
            else -> MemoryPressureLevel.NORMAL
        }
    }
    val rising = target.ordinal > current.ordinal
    val streak = if (rising) confirming + 1 else 0
    return if (rising && streak < confirmTicks.coerceAtLeast(1)) {
        TierStep(current, streak)
    } else {
        TierStep(target, streak)
    }
}

/** [internalNextTier] 的结果：下一档位 + 升档置信计数。 */
internal data class TierStep(val level: MemoryPressureLevel, val confirming: Int)