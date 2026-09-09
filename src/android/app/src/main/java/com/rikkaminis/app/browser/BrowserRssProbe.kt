package com.rikkaminis.app.browser

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯观测打点：记录每个 browser_use action 执行前后的主进程 VmRSS 增量。
 *
 * 背景（P4，2026-08-20）：`browser_use` 走 [BrowserTabPool.execute]（主进程
 * WebView 池），**不经** `NativeOffloadServer.handleClient`，所以 sandbox 侧的
 * [com.rikkaminis.app.sandbox.OffloadRssProbe] 覆盖不到它。WebView 渲染进程是
 * 主进程 native/mmap 之外的另一大内存来源（每 tab 50–100MB，Chromium 独立
 * renderer），此前它一直是「泄漏涨在哪个 handler」的最后一块盲区。
 *
 * 本类与 OffloadRssProbe 同构：零副作用、按 action 名归因、可 JVM 单测。
 * 打点只是旁路观测，与 [com.rikkaminis.app.service.MemoryPressureGate] 的准入
 * 逻辑无关——绝不改变 action 的执行路径或结果。
 */
object BrowserRssProbe {
    private const val TAG = "BrowserRssProbe"

    /** 单 action 累计正 delta 超过该值即判定「可疑泄漏源」并 WARN 一次。 */
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

    // action 名 → 聚合统计（进程级累计，与 RSS 生命周期对齐）。
    private val byAction = ConcurrentHashMap<String, Stats>()

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
     * 记录一次 action 执行的 VmRSS 增量（kB）。delta = afterKb - beforeKb。
     * delta 可能为负（WebView 渲染进程被回收 / GC 释放了映射），如实记录。
     * 零副作用：所有聚合与日志都无法影响 action 的结果。
     */
    fun record(action: String, beforeKb: Long, afterKb: Long) {
        val deltaKb = afterKb - beforeKb
        val st = byAction.computeIfAbsent(action) { Stats() }
        st.count += 1
        st.totalDeltaKb += deltaKb
        st.lastDeltaKb = deltaKb
        if (deltaKb > st.peakDeltaKb) st.peakDeltaKb = deltaKb

        // 每次调用一行观测日志（压测时可按 action 名 grep 归类）
        Log.i(
            TAG,
            "[browser-rss] action=$action Δ=${deltaKb}kB " +
                "before=${beforeKb / 1024}MB after=${afterKb / 1024}MB " +
                "cum=${st.totalDeltaKb / 1024}MB(×${st.count}, avg=${st.averageDeltaKb() / 1024}MB)"
        )

        // 累计正增量越过嫌疑阈值 → 判定为可疑泄漏源，WARN 一次
        if (!st.leaked && st.totalDeltaKb >= LEAK_SUSPECT_KB) {
            st.leaked = true
            Log.w(
                TAG,
                "[browser-rss] LEAK-SUSPECT action=$action totalDelta=${st.totalDeltaKb / 1024}MB " +
                    "over ${st.count} calls (peak=${st.peakDeltaKb / 1024}MB) — 候选 WebView native OOM 源头"
            )
        }
    }

    /** 打印全量聚合汇总（压测结束 / 定时 dump 用）。返回汇总文本。 */
    fun summary(): String {
        if (byAction.isEmpty()) {
            Log.i(TAG, "[browser-rss] summary: (no browser actions executed)")
            return "(no data)"
        }
        val sb = StringBuilder("browser-rss summary:\n")
        byAction.entries
            .sortedByDescending { it.value.totalDeltaKb }
            .forEach { (name, st) ->
                sb.append(
                    "  $name: cum=${st.totalDeltaKb / 1024}MB " +
                        "count=${st.count} avg=${st.averageDeltaKb() / 1024}MB " +
                        "peak=${st.peakDeltaKb / 1024}MB last=${st.lastDeltaKb / 1024}MB" +
                        (if (st.leaked) " [LEAK-SUSPECT]" else "")
                ).append('\n')
            }
        Log.i(TAG, "[browser-rss] $sb")
        return sb.toString()
    }

    /** 压测自检用：重置聚合（生产不调用）。 */
    fun reset() {
        byAction.clear()
    }
}
