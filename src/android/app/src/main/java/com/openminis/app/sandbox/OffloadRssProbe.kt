package com.openminis.app.sandbox

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯观测打点：记录每个 native-offload handler 执行前后的
 * 主进程 VmRSS 增量，用来定位 2026-08-17/19 主进程 native OOM 的泄漏归属。
 *
 * 背景：2026-08-17/19 多会话并发工具调用把主进程 RSS 推到 5.8–6.0GB（VmPeak ~17GB）
 * SIGABRT。ExecutionCoordinator 已对 shell 路径做 VmRSS 硬门，但 native-offload
 * handler（model-use / sessions / browser-use / weather …）是另一条在主进程
 * 直接执行的路径，之前没有任何 RSS 归因打点——这是「泄漏到底涨在哪个 handler」的盲区。
 *
 * 设计约束（对齐「打点定位」目标——用最便宜的信息买决策权）：
 * - 零副作用：读 /proc/self/status 失败返回 0，绝不 throw、绝不改变 handler 的
 *   执行路径或结果。打点只是旁路观测，与 [MemoryPressureGate] 的准入逻辑无关。
 * - 归因：按 handler 名聚合 delta、调用次数、峰值单次 delta。
 * - 轻量：每次调用一次 O(1) 聚合 + 一行 Log；无收集器、无后台线程、无持久化。
 * - 可单测：statusText 解析独立成纯函数，JVM 可测。
 * - 单位：全程用 kB（VmRSS 原生单位），MB 损失 <1MB 细节会掩盖慢泄漏增量。
 */
object OffloadRssProbe {
    private const val TAG = "OffloadRssProbe"

    /**
     * 治理阈值（kB）。当某个 handler 的累计正增量越过 [CUMULATIVE_GOVERN_KB]，
     * 或单次正增量越过 [PEAK_GOVERN_KB] 时，触发一次 [governanceHook] 并重置
     * 该 handler 的累计值（防止同一慢泄漏反复触发）。
     *
     * 阈值依据（2026-08-25 现场实测）：每次 offload handler 执行后主进程
     * VmRSS 单调 +~0.5MB 不回落（8 命令 +4MB）。单次 64MB 是「单条命令泄漏
     * 异常多」的信号；累计 256MB 是「慢泄漏累积到显著量」的信号。主进程回收
     * （destroy PRoot tracer + GC 善后）是高代价动作（~200ms+），不能每次
     * 命令都触发，故用累计阈值而非逐命令回收。
     */
    private const val PEAK_GOVERN_KB = 64L * 1024L        // 单次增量 64MB
    private const val CUMULATIVE_GOVERN_KB = 256L * 1024L // 累计增量 256MB

    /** 单 handler 累计正 delta 超过该值即判定「可疑泄漏源」并 WARN 一次。 */
    private const val LEAK_SUSPECT_KB = 1_024L * 1024L  // 1 GiB 累计

    private data class Stats(
        var count: Int = 0,
        var totalDeltaKb: Long = 0L,
        var peakDeltaKb: Long = 0L,
        var lastDeltaKb: Long = 0L,
        var leaked: Boolean = false,
    ) {
        fun averageDeltaKb(): Long = if (count == 0) 0L else totalDeltaKb / count
    }

    // handler 名 → 聚合统计（进程级累计，与 RSS 生命周期对齐）。
    // ConcurrentHashMap 保证并发 handler（Semaphore(2) 内的多个 worker 线程）安全。
    private val byHandler = ConcurrentHashMap<String, Stats>()

    /**
     * 可注入的治理动作。当累计/单次增量越过阈值时触发一次，用于把「观测到
     * 的泄漏」真正治理掉（回收 PRoot tracer / 触发 GC 善后）而不是等 1GiB
     * 或等系统清理来杀进程。默认空（纯观测，无副作用），生产在
     * [com.openminis.app.MinisApp] 装配到 ExecutionCoordinator / MemoryPressureGate
     * 的回收动作；测试注入 spy 断言「越界即触发」。
     *
     * 对齐 [com.openminis.app.service.MemoryPressureGate.reclaimHook] 的
     * 注入模式——零副作用、零 Android 依赖、纯 JVM 可测。
     */
    @Volatile
    var governanceHook: () -> Unit = {}

    /** 读当前进程 VmRSS（kB）。读失败返回 0（安全侧：不触发降级/告警）。 */
    fun rssKb(): Long = try {
        parseVmRssKb(File("/proc/self/status").readText())
    } catch (t: Throwable) {
        0L
    }

    /**
     * 从 /proc/self/status 文本解析 VmRSS（kB）。无 VmRSS 行返回 0。
     * 独立成纯函数以便 JVM 单测（对齐项目「抽纯函数零回归」路线）。
     */
    internal fun parseVmRssKb(statusText: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        return line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
    }

    /**
     * 记录一次 handler 执行的 VmRSS 增量（kB）。delta = afterKb - beforeKb。
     * delta 可能为负（GC / shell 回收释放了映射），如实记录。
     * 零副作用：所有聚合与日志都无法影响 handler 的结果。
     */
    fun record(name: String, beforeKb: Long, afterKb: Long) {
        val deltaKb = afterKb - beforeKb
        val st = byHandler.computeIfAbsent(name) { Stats() }
        // [fix/audit-b20 / T3-L2] Up to two offload workers (Semaphore(2)) can
        // record into the same Stats concurrently; the ConcurrentHashMap only
        // protects the map, not the value's fields, so `+=` lost updates.
        synchronized(st) {
            st.count += 1
            st.totalDeltaKb += deltaKb
            st.lastDeltaKb = deltaKb
            if (deltaKb > st.peakDeltaKb) st.peakDeltaKb = deltaKb
        }

        // 每次调用一行观测日志（压测时可按 handler 名 grep 归类）
        Log.i(
            TAG,
            "[offload-rss] handler=$name Δ=${deltaKb}kB " +
                "before=${beforeKb / 1024}MB after=${afterKb / 1024}MB " +
                "cum=${st.totalDeltaKb / 1024}MB(×${st.count}, avg=${st.averageDeltaKb() / 1024}MB)"
        )

        // 累计正增量越过嫌疑阈值 → 判定为可疑泄漏源，WARN 一次身份
        if (!st.leaked && st.totalDeltaKb >= LEAK_SUSPECT_KB) {
            st.leaked = true
            Log.w(
                TAG,
                "[offload-rss] LEAK-SUSPECT handler=$name totalDelta=${st.totalDeltaKb / 1024}MB " +
                    "over ${st.count} calls (peak=${st.peakDeltaKb / 1024}MB) — 候选 native OOM 源头"
            )
        }

        // [offload-rss-governance] 观测 → 治理。当累计正增量越过阈值，或单次
        // 增量异常大时，触发一次 governanceHook（回收 PRoot tracer / GC 善后），
        // 并把该 handler 的累计归零，防止同一慢泄漏在后续每次调用里反复触发
        // 高代价回收。hook 空实现时保持纯观测（向后兼容、零副作用）。
        val toGovern = st.totalDeltaKb >= CUMULATIVE_GOVERN_KB || deltaKb >= PEAK_GOVERN_KB
        if (toGovern) {
            if (st.totalDeltaKb >= CUMULATIVE_GOVERN_KB) {
                Log.w(
                    TAG,
                    "[offload-rss] GOVERN handler=$name cumulative ${st.totalDeltaKb / 1024}MB " +
                        ">= ${CUMULATIVE_GOVERN_KB / 1024}MB — triggering governance + reset"
                )
            } else {
                Log.w(
                    TAG,
                    "[offload-rss] GOVERN handler=$name single Δ=${deltaKb / 1024}MB " +
                        ">= ${PEAK_GOVERN_KB / 1024}MB — triggering governance"
                )
            }
            st.totalDeltaKb = 0L
            runCatching { governanceHook() }
        }
    }

    /** 打印全量聚合汇总（压测结束 / 定时 dump 用）。返回汇总文本。 */
    fun summary(): String {
        if (byHandler.isEmpty()) {
            Log.i(TAG, "[offload-rss] summary: (no offload handlers executed)")
            return "(no data)"
        }
        val sb = StringBuilder("offload-rss summary:\n")
        byHandler.entries
            .sortedByDescending { it.value.totalDeltaKb }
            .forEach { (name, st) ->
                sb.append(
                    "  $name: cum=${st.totalDeltaKb / 1024}MB " +
                        "count=${st.count} avg=${st.averageDeltaKb() / 1024}MB " +
                        "peak=${st.peakDeltaKb / 1024}MB last=${st.lastDeltaKb / 1024}MB" +
                        (if (st.leaked) " [LEAK-SUSPECT]" else "")
                ).append('\n')
            }
        Log.i(TAG, "[offload-rss] $sb")
        return sb.toString()
    }

    /** 压测自检用：重置聚合（生产不调用）。 */
    fun reset() {
        byHandler.clear()
    }
}
