package com.rikkaminis.app.service

import android.content.ComponentCallbacks2

/**
 * 决策层：把 Android [ComponentCallbacks2.onTrimMemory] 的 `level` 分类成
 * 「前台运行时压力」或「后台可见性」两个正交维度，供 MinisApp / BrowserTabPool
 * 消费。
 *
 * 为什么要这个类（2026-08-20 实测发现的真实缺陷）：
 * 现有代码用 `level >= TRIM_MEMORY_RUNNING_CRITICAL(15)` 做数值比较。但 Android
 * 的 trim level 不是单调递增的严重度标尺——它混排了两类语义：
 *   - 前台运行时压力：RUNNING_MODERATE(5) < RUNNING_LOW(10) < RUNNING_CRITICAL(15)
 *   - 后台/可见性事件：UI_HIDDEN(20) < BACKGROUND(40) < MODERATE(60) < COMPLETE(80)
 * 用 `>= 15` 会把 20/40/60/80 全部误判为「前台且危急」。实测：把 app 切后台触发的
 * `UI_HIDDEN(20)` 被当成 CRITICAL，导致销毁 WebView tab、强制 System.gc()、回收 shell
 * ——这是「切后台回来网页没了/任务被打断/偶发卡顿」的元凶之一。
 *
 * 本类只做纯函数分类与决策（无 Android 副作用），JVM 可测。
 */
enum class TrimPhase { RUNNING_LIGHT, RUNNING_HEAVY, BACKGROUND_LIGHT, BACKGROUND_HEAVY }

object TrimPolicy {

    // 前台运行时压力（数字小）：app 在前台、系统内存吃紧
    private val FOREGROUND_PRESSURE = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, // 5
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,      // 10
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, // 15
    )

    // 后台可见性事件（数字大）：app UI 已隐藏或整体低内存
    private val BACKGROUND_HEAVY = setOf(
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,   // 60
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,   // 80
    )

    /**
     * 分类一个 trim level 落入哪一档。区分前台压力（5/10/15）与后台事件（>=20），
     * 并在各自维度内区分轻重。默认 BACKGROUND_LIGHT（20=UI_HIDDEN 起即后台）。
     */
    fun phase(level: Int): TrimPhase = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> TrimPhase.RUNNING_LIGHT
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> TrimPhase.RUNNING_HEAVY
        in BACKGROUND_HEAVY -> TrimPhase.BACKGROUND_HEAVY
        else -> TrimPhase.BACKGROUND_LIGHT
    }

    /** 是否前台运行时压力（app 在前台，系统内存吃紧）。 */
    fun isForegroundPressure(level: Int): Boolean = level in FOREGROUND_PRESSURE

    /** 是否后台可见性事件（app UI 已隐藏 / 后台低内存）。 */
    fun isBackground(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

    /**
     * MinisApp 决策：是否回收 idle shells + 强制 GC 来释放主进程 native 足迹。
     * 只在【前台运行时压力】下做——后台的 UI_HIDDEN/BACKGROUND 是可见性/后台事件，
     * 不等于主进程内存危机，强制 GC + 回收 shell 反而打断正在进行的任务。
     */
    fun shouldReclaimShellsAndGc(level: Int): Boolean = isForegroundPressure(level)

    /**
     * MinisApp 决策：是否让新 agent 会话等内存恢复后再准入。
     * 只在最严重的【前台临界】RUNNING_CRITICAL(15) 下触发——不能再把后台切换
     * (20+) 当成「new sessions must wait」的触发器。
     */
    fun shouldEngageMemoryGate(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

    /**
     * BrowserTabPool 决策：给定 trim level，返回「该销毁哪种 tab」的策略。
     *
     * 对应 BrowserTabPool.onTrimMemory 的四个分支，但用 TrimPhase 分类替代
     * 原来的 `level >= RUNNING_CRITICAL(15)` 数值比较（它会把后台 20+/40+/60+
     * 误判成前台临界）：
     *
     * - RUNNING_HEAVY (15)     前台真临界：只保留 selected tab（保住 agent 活跃上下文）
     * - RUNNING_LIGHT (5/10)   前台轻压：淘汰所有空闲 tab
     * - BACKGROUND_HEAVY(60/80) 整体内存极度吃紧：淘汰所有空闲 tab
     * - BACKGROUND_LIGHT(20/40) 后台可见性：只淘汰【长时间空闲】的 tab，
     *   不销毁近期用过的或 selected tab（后台 ≠ 内存危急，切后台刚看的页应保留）
     */
    fun browserTabKillPolicy(level: Int): BrowserTabKillPolicy = when (phase(level)) {
        TrimPhase.RUNNING_HEAVY -> BrowserTabKillPolicy.DROP_ALL_BUT_SELECTED
        TrimPhase.RUNNING_LIGHT -> BrowserTabKillPolicy.DROP_ALL_IDLE
        TrimPhase.BACKGROUND_HEAVY -> BrowserTabKillPolicy.DROP_ALL_IDLE
        TrimPhase.BACKGROUND_LIGHT -> BrowserTabKillPolicy.DROP_LONG_IDLE_ONLY
    }

    enum class BrowserTabKillPolicy {
        /** 前台真临界：只保留 selected tab。 */
        DROP_ALL_BUT_SELECTED,
        /** 前台轻压 / 整体吃紧：淘汰所有空闲 tab。 */
        DROP_ALL_IDLE,
        /** 后台可见性：只淘汰长时间空闲的 tab（保留近期用过的）。 */
        DROP_LONG_IDLE_ONLY,
    }
}
