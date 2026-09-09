package com.rikkaminis.app.service

import kotlinx.coroutines.delay
import java.io.File

/**
 * 进程级 RSS 水线门卫（纯 JVM 可测，无 Android 依赖）。
 *
 * 背景（2026-08-15 早上 OOM 崩溃分析）：`com.rikkaminis.app` 在 08:25-08:27
 * 连续 3 次 `pthread_create (1040KB stack) failed`——这是 **native 内存耗尽**
 * 而非 Java heap OOM。现有 [com.rikkaminis.app.sandbox.ExecutionCoordinator]
 * 的 P2-app-native-oom 防御只看 `Debug.getNativeHeapAllocatedSize()`（app
 * native heap），对 **进程 RSS 中的线程栈 / mmap 部分完全盲区**（崩溃时
 * native heap 可能是 100MB 而 RSS 已 280MB+）。本类补上 RSS 维度。
 *
 * 阈值依据（实测，2026-08-18 更新）：旧阈值（ELEVATED=280 / CRITICAL=320）
 * 基于老设备实测（PID 23721 正常 RSS≈277MB、崩时 native 364MB mmap 失败）。
 * 但当前设备（11GB 内存）健康时 app RSS 就达 354~400MB → 旧阈值让
 * MemoryPressureGate 永远判 CRITICAL，每次开答都被拖 2 秒 + 频繁触发
 * GC/shell 回收。取 ELEVATED=600MB（预警）、CRITICAL=800MB（硬门槛），
 * 为健康 RSS（≈354-400MB）留足余量，同时保留对真泄漏（>800MB 明显不健康）
 * 的兜底。
 *
 * 可测试性：rssReader / reclaimHook / pressureListener 全部可注入，
 * 生产路径在 [com.rikkaminis.app.MinisApp] 装配。
 */
enum class MemoryPressureLevel { NORMAL, ELEVATED, CRITICAL }

object MemoryPressureGate {

    /** 预警水线（MB）：超过后新 session 准入短暂等待 + 触发回收。 */
    const val ELEVATED_RSS_MB = 600L

    /** 硬门槛（MB）：超过后新 session 准入触发全局回收并等待恢复。 */
    const val CRITICAL_RSS_MB = 800L

    /** 可注入的 RSS 读取器。生产读 /proc/self/status；测试注入 fake。 */
    @Volatile
    var rssReader: () -> Long = { readRssFromProc() }

    /**
     * 可注入的全局回收动作。生产（MinisApp 装配）：回收 idle shells +
     * 释放 WebView 标签 + System.gc()。测试注入 spy。
     */
    @Volatile
    var reclaimHook: () -> Unit = {}

    /**
     * 可注入的压力通知。生产（MinisApp 装配）接到 AppLogger；
     * 测试注入计数器。只在 ELEVATED / CRITICAL 时触发。
     */
    @Volatile
    var pressureListener: (MemoryPressureLevel, Long) -> Unit = { _, _ -> }

    /** 当前压力级别。 */
    fun level(): MemoryPressureLevel = levelFor(rssReader())

    /** 纯函数分级（可单测）。 */
    fun levelFor(rssMB: Long): MemoryPressureLevel = when {
        rssMB >= CRITICAL_RSS_MB -> MemoryPressureLevel.CRITICAL
        rssMB >= ELEVATED_RSS_MB -> MemoryPressureLevel.ELEVATED
        else -> MemoryPressureLevel.NORMAL
    }

    /**
     * [native-rss-tool-guard] 回收后仍处于硬门槛是否应拒绝新执行。
     * 纯函数可单测：主进程 RSS 已超过 800MB 时，即使刚触发过全局回收，
     * 也不应继续接受会进一步堆 native/mmap 的工具请求。
     */
    fun shouldRejectAfterReclaim(rssMB: Long): Boolean = rssMB >= CRITICAL_RSS_MB

    /** 读取当前进程 VmRSS（MB）。读失败返回 0（安全侧：不触发降级）。 */
    fun readRssFromProc(): Long {
        return try {
            parseVmRss(File("/proc/self/status").readText())
        } catch (t: Throwable) {
            0L
        }
    }

    /** 从 /proc/self/status 文本解析 VmRSS（MB）。无 VmRSS 行返回 0。 */
    internal fun parseVmRss(statusText: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0L
        val kb = line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
        return kb / 1024L
    }

    /** 触发一次全局回收 + 短暂等待让回收生效。 */
    suspend fun reclaimAndWait(waitMs: Long = 2_000L) {
        reclaimHook()
        delay(waitMs)
    }

    /** 通知压力事件（只对非 NORMAL 级别；NORMAL 静默）。 */
    fun notify(level: MemoryPressureLevel) {
        if (level != MemoryPressureLevel.NORMAL) {
            pressureListener(level, rssReader())
        }
    }
}