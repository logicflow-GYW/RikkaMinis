package com.rikkaminis.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [fix/memory-anon-metric] 门的主指标从 VmRSS 换成 RssAnon 后的口径 + 滞回/置信拍。
 *
 * 数据依据（2026-09-13，83 条健康样本）：健康 anon p50 101 / p90 133 / max 145MB，
 * 而同期 RSS p50 265MB 里 file 页恒为 ~147MB —— 旧口径 61% 的信号是内核可回收的
 * 缓存。阈值 450 / 1200 anon 的推导见 MemoryPressureGate 的 KDoc。
 */
class MemoryPressureGateTest {

    private var reclaimCalls = 0
    private var notifications = 0
    private var lastNotifiedLevel: MemoryPressureLevel? = null
    private val tierChanges = mutableListOf<Pair<MemoryPressureLevel, MemoryPressureLevel>>()
    private data class ShadowHop(
        val src: String,
        val from: MemoryPressureLevel,
        val to: MemoryPressureLevel,
        val anonMb: Long,
    )

    private val shadowChanges = mutableListOf<ShadowHop>()
    private var shadowRssMb = -1L

    @Before
    fun setUp() {
        reclaimCalls = 0
        notifications = 0
        lastNotifiedLevel = null
        tierChanges.clear()
        shadowChanges.clear()
        shadowRssMb = -1L
        MemoryPressureGate.resetTierStateForTest()
        MemoryPressureGate.reclaimHook = { reclaimCalls++ }
        MemoryPressureGate.pressureListener = { level, _, _, _ ->
            notifications++
            lastNotifiedLevel = level
        }
        tierListener = { from, to, _ -> tierChanges.add(from to to) }
        shadowTierListener = { src, from, to, anon, rss ->
            shadowChanges.add(ShadowHop(src, from, to, anon))
            shadowRssMb = rss
        }
    }

    @After
    fun tearDown() {
        MemoryPressureGate.reclaimHook = {}
        MemoryPressureGate.pressureListener = { _, _, _, _ -> }
        tierListener = { _, _, _ -> }
        shadowTierListener = { _, _, _, _, _ -> }
        MemoryPressureGate.metricsReader = { MemoryPressureGate.readMetricsFromProc() }
        MemoryPressureGate.resetTierStateForTest()
    }

    /** 用固定读数驱动门（anon 主指标 + rss 伴生值）。 */
    private fun inject(anonMb: Long, rssMb: Long = anonMb + 147L) {
        MemoryPressureGate.metricsReader = { MemoryPressureGate.Metrics(anonMb = anonMb, rssMb = rssMb) }
    }

    // ── 解析：anon 主指标 + rss 伴生 ──────────────────────────────────

    @Test
    fun `parseMetrics reads RssAnon and VmRSS from one status read`() {
        val text = """
            Name:   com.rikkaminis.app
            VmPeak:  16831788 kB
            VmSize:   9935852 kB
            VmRSS:     271412 kB
            RssAnon:   103588 kB
            RssFile:   151236 kB
            RssShmem:   16588 kB
        """.trimIndent()
        val m = MemoryPressureGate.parseMetrics(text)
        assertEquals(101L, m.anonMb)
        assertEquals(265L, m.rssMb)
    }

    @Test
    fun `parseMetrics falls back to VmRSS when RssAnon is absent`() {
        // 老内核没有 RssAnon 行：退回 RSS 更保守（宁可按高值判）而不是读成 0。
        val m = MemoryPressureGate.parseMetrics("Name: x\nVmRSS: 307200 kB\n")
        assertEquals(300L, m.anonMb)
        assertEquals(300L, m.rssMb)
    }

    @Test
    fun `parseMetrics is zero on malformed or missing input`() {
        assertEquals(MemoryPressureGate.Metrics(0L, 0L), MemoryPressureGate.parseMetrics(""))
        assertEquals(MemoryPressureGate.Metrics(0L, 0L), MemoryPressureGate.parseMetrics("VmRSS:  kB\n"))
        assertEquals(MemoryPressureGate.Metrics(0L, 0L), MemoryPressureGate.parseMetrics("VmRSS: oops\n"))
    }

    @Test
    fun `parseVmRss keeps its historical contract`() {
        assertEquals(265L, MemoryPressureGate.parseVmRss("VmRSS: 271412 kB\n"))
        assertEquals(0L, MemoryPressureGate.parseVmRss("VmRSS: 0 kB\n"))
        assertEquals(0L, MemoryPressureGate.parseVmRss("nothing"))
    }

    @Test
    fun `levelFor boundaries are the anon lines`() {
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.levelFor(0))
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.levelFor(449))
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.levelFor(450))
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.levelFor(1199))
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.levelFor(1200))
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.levelFor(5890))
    }

    @Test
    fun `shouldRejectAfterReclaim only at the hard line`() {
        assertFalse(MemoryPressureGate.shouldRejectAfterReclaim(0))
        assertFalse(MemoryPressureGate.shouldRejectAfterReclaim(1199))
        assertTrue(MemoryPressureGate.shouldRejectAfterReclaim(1200))
        // 已知数据点：17:01 那次 anon=1370 → 拒绝是对的。
        assertTrue(MemoryPressureGate.shouldRejectAfterReclaim(1370))
    }

    // ── level()：滞回 + 置信拍状态机 ─────────────────────────────────

    @Test
    fun `rising needs two consecutive samples`() {
        inject(500)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())  // 1st: not confirmed
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level()) // 2nd: confirmed
    }

    @Test
    fun `a single spike followed by a normal reading never rises`() {
        inject(500)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
        inject(120)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
        inject(500)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
    }

    @Test
    fun `hysteresis holds a tier until it falls well below the line`() {
        inject(500)
        MemoryPressureGate.level()
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level())
        // 450 − 150 = 300：落在 300..449 之间保持 ELEVATED（健康 anon 摆幅 55~145，
        // 若在 450 边界反复穿越会抖）。
        inject(400)
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level())
        inject(350)
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level())
        inject(299)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
    }

    @Test
    fun `critical needs confirmation and holds until well below its line`() {
        inject(1300)
        assertEquals(MemoryPressureLevel.NORMAL, MemoryPressureGate.level())
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.level())
        // 1200 − 150 = 1050：1050..1199 仍算 CRITICAL。
        inject(1100)
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.level())
        inject(1000)
        assertEquals(MemoryPressureLevel.ELEVATED, MemoryPressureGate.level())
    }

    @Test
    fun `a jump straight to critical is allowed after confirmation`() {
        inject(2000)
        MemoryPressureGate.level()
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.level())
    }

    @Test
    fun `level tracks the injected metrics not the real proc file`() {
        inject(anonMb = 5890, rssMb = 6043)
        MemoryPressureGate.level()
        assertEquals(MemoryPressureLevel.CRITICAL, MemoryPressureGate.level())
        assertEquals(5890L, MemoryPressureGate.anonMb())
        assertEquals(6043L, MemoryPressureGate.rssMb())
    }

    // ── 上报与回收 ───────────────────────────────────────────────────

    @Test
    fun `notify fires the pressure listener only for non-normal levels`() {
        val seen = mutableListOf<Pair<MemoryPressureLevel, Long>>()
        MemoryPressureGate.pressureListener = { level, anon, _, _ -> seen += level to anon }
        inject(500)
        MemoryPressureGate.notify(MemoryPressureLevel.NORMAL, MemoryPressureGate.SRC_TICK)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED, MemoryPressureGate.SRC_TICK)
        assertEquals(listOf(MemoryPressureLevel.ELEVATED to 500L), seen)
    }

    @Test
    fun `notify carries the sampling source and the crossing flag`() {
        // [LOOP-1030 R1-b] 1s tick 与准入路径必须可分：旧签名让两者在日志里同形，
        // 3881 行里真实准入 ≤29 行却全被标成 "admission delayed 500ms"。
        val seen = mutableListOf<Triple<String, Boolean, MemoryPressureLevel>>()
        MemoryPressureGate.pressureListener = { level, _, src, crossed -> seen += Triple(src, crossed, level) }
        inject(500)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED, MemoryPressureGate.SRC_TICK)
        // 同一档位、滞回带内再来一次：这是续报，不是新的越线。
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED, MemoryPressureGate.SRC_ADMISSION)
        assertEquals(2, seen.size)
        assertEquals("tick", seen[0].first)
        assertEquals(true, seen[0].second)
        assertEquals("agent-admission", seen[1].first)
        assertEquals(false, seen[1].second)
    }

    @Test
    fun `the governor tick marks itself as a tick sample`() {
        val srcs = mutableListOf<String>()
        MemoryPressureGate.pressureListener = { _, _, src, _ -> srcs += src }
        inject(500)
        // 1s 状态机需要置信拍才升档，跑几拍确保真的报出一行。
        repeat(3) { MemoryPressureGate.sampleAndNotify() }
        assertTrue("governor tick never reported", srcs.isNotEmpty())
        assertEquals(listOf("tick"), srcs.distinct())
    }

    @Test
    fun `tier listener reports both rises and falls`() {
        val seen = mutableListOf<Triple<MemoryPressureLevel, MemoryPressureLevel, Long>>()
        tierListener = { from, to, anon -> seen += Triple(from, to, anon) }
        inject(500)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED, MemoryPressureGate.SRC_TICK)
        inject(1300)
        MemoryPressureGate.notify(MemoryPressureLevel.CRITICAL, MemoryPressureGate.SRC_TICK)
        // 降回 NORMAL 也要上报：这是「正常重活会不会常穿越梯子」的统计来源。
        MemoryPressureGate.notify(MemoryPressureLevel.NORMAL, MemoryPressureGate.SRC_TICK)
        assertEquals(3, seen.size)
        assertEquals(MemoryPressureLevel.NORMAL, seen[0].first)
        assertEquals(MemoryPressureLevel.ELEVATED, seen[0].second)
        assertEquals(MemoryPressureLevel.ELEVATED, seen[1].first)
        assertEquals(MemoryPressureLevel.CRITICAL, seen[1].second)
        assertEquals(MemoryPressureLevel.CRITICAL, seen[2].first)
        assertEquals(MemoryPressureLevel.NORMAL, seen[2].second)
    }

    @Test
    fun `reclaimAndWait invokes the hook`() {
        var hits = 0
        MemoryPressureGate.reclaimHook = { hits++ }
        kotlinx.coroutines.runBlocking { MemoryPressureGate.reclaimAndWait(waitMs = 0L) }
        assertTrue(hits >= 1)
    }

    @Test
    fun `real proc read yields a sane reading on this machine`() {
        MemoryPressureGate.metricsReader = { MemoryPressureGate.readMetricsFromProc() }
        val anon = MemoryPressureGate.anonMb()
        val rss = MemoryPressureGate.rssMb()
        // 不是在测具体数值，而是钉「读得到、且 anon ≤ rss」（anon 是 rss 的子集，
        // 除非内核不报 RssAnon —— 那时退回 rss，两者相等）。
        assertTrue("anon=$anon rss=$rss", anon > 0L && rss > 0L)
        assertTrue("anon=$anon rss=$rss", anon <= rss)
    }

    // ── [SYMPTOM-A F-A1] 影子水线（800MB，只观测） ────────────────────
    // [LOOP-1030 R1-g] 挂接点从 1s tick 挪到准入采样点：周期采样四天峰值仅
    // 564MB（memspike 12,111 样本），而 800+ 的读数只出现在准入的亚秒突发里
    // （10-28 18:57:31 三连读 866/1032/882MB）。探针必须活在被观测决策的
    // 同一条读数上——挂错位置比没有探针更坏（假阴性喂给调阈值的人）。

    @Test
    fun `shadow arm reports one hop when an admission read crosses 800`() {
        // tick 先跑两拍：tick 已退出影子业务，不得产生任何跃迁。
        inject(100)
        repeat(2) { MemoryPressureGate.sampleAndNotify() }
        assertEquals(0, shadowChanges.size)
        MemoryPressureGate.observeShadowAtAdmission(900L, 1047L, src = "agent-admission")
        assertEquals(1, shadowChanges.size)
        assertEquals(MemoryPressureLevel.NORMAL, shadowChanges[0].from)
        assertEquals(MemoryPressureLevel.CRITICAL, shadowChanges[0].to)
        assertEquals(900L, shadowChanges[0].anonMb)
        assertEquals("agent-admission", shadowChanges[0].src)
        assertEquals(1047L, shadowRssMb)
    }

    @Test
    fun `shadow arm stays quiet below the 800 line`() {
        // 500..799 是"真档的 ELEVATED 带"（450 线已由 tierListener 记录）。影子机
        // 测的是 800 这条硬线，所以这一段必须一条都不记。
        for (anon in listOf(500L, 600L, 700L, 799L, 799L, 640L)) {
            MemoryPressureGate.observeShadowAtAdmission(anon, anon + 147L, src = "shell-admission")
        }
        assertEquals(0, shadowChanges.size)
    }

    @Test
    fun `shadow arm holds the hop until anon falls below 650`() {
        MemoryPressureGate.observeShadowAtAdmission(850L, 997L, src = "agent-admission")
        assertEquals(1, shadowChanges.size)
        // 800 − 150 = 650：650..799 仍算"穿越中"（滞回），跌破 650 才降档。
        for (anon in listOf(700L, 650L)) {
            MemoryPressureGate.observeShadowAtAdmission(anon, anon + 147L, src = "shell-admission")
        }
        assertEquals(1, shadowChanges.size)
        MemoryPressureGate.observeShadowAtAdmission(649L, 796L, src = "shell-admission")
        assertEquals(2, shadowChanges.size)
        assertEquals(MemoryPressureLevel.CRITICAL, shadowChanges[1].from)
        assertEquals(MemoryPressureLevel.NORMAL, shadowChanges[1].to)
    }

    @Test
    fun `shadow arm never changes the real tier result`() {
        // 负控：影子机在跑的同一串读数下，真档结果必须逐位等于改动前的值。
        // 期望写死字面量，不从常量推导（常量一改，推导出来的期望跟着变，变异就
        // 伪装成 PASS —— JVM 装置自指陷阱）。
        val readings = listOf(100L, 500L, 500L, 1300L, 1300L, 1000L, 300L, 299L)
        val expected = listOf(
            MemoryPressureLevel.NORMAL,   // 100
            MemoryPressureLevel.NORMAL,   // 500 — 第 1 拍，置信不足
            MemoryPressureLevel.ELEVATED, // 500 — 第 2 拍确认（confirming=2）
            MemoryPressureLevel.CRITICAL, // 1300 — 已带 2 拍置信，升档当场确认
            MemoryPressureLevel.CRITICAL, // 1300
            MemoryPressureLevel.ELEVATED, // 1000 — 跌破 1050 回落
            MemoryPressureLevel.ELEVATED, // 300 — 仍在 450−150 之上
            MemoryPressureLevel.NORMAL,   // 299
        )
        val actual = readings.map { anon ->
            inject(anon)
            val tierResult = MemoryPressureGate.sampleAndNotify()
            // 真档之外，同一个读数同时喂影子（准入路径的真实形态）。
            MemoryPressureGate.observeShadowAtAdmission(anon, anon + 147L, src = "shell-admission")
            tierResult
        }
        assertEquals(expected, actual)
        // 影子机自己的轨迹（独立于真档）：1300 一次升、300 一次降（1000 在滞回带内不动）。
        assertEquals(
            listOf(
                ShadowHop("shell-admission", MemoryPressureLevel.NORMAL, MemoryPressureLevel.CRITICAL, 1300L),
                ShadowHop("shell-admission", MemoryPressureLevel.CRITICAL, MemoryPressureLevel.NORMAL, 300L),
            ),
            shadowChanges,
        )
        // 只观测：影子机判"穿越 800"不触发回收，也不改硬门槛拒绝线。
        assertEquals(0, reclaimCalls)
        assertFalse(MemoryPressureGate.shouldRejectAfterReclaim(900))
    }

    @Test
    fun `shadow arm advances only on admission reads, never from the tick`() {
        // R1-g 回归钉：1s tick（sampleAndNotify）结构性看不到亚秒突发，影子只
        // 挂准入。谁把影子挪回 tick，这条先红。
        inject(900)
        repeat(5) { MemoryPressureGate.level() }
        repeat(3) { MemoryPressureGate.sampleAndNotify() }
        assertEquals(0, shadowChanges.size)
        MemoryPressureGate.observeShadowAtAdmission(900L, 1047L, src = "agent-admission")
        assertEquals(1, shadowChanges.size)
    }

    @Test
    fun `sub-second burst cluster folds into exactly two hops`() {
        // 10-28 18:57:31 的真实形态：866 → 1032 → 882 三连读 + 回落后收敛。
        // 无滞回的话每个读数一行（日志噪声）；有滞回 = 一升一降两行。
        MemoryPressureGate.observeShadowAtAdmission(866L, 1013L, src = "shell-admission")
        MemoryPressureGate.observeShadowAtAdmission(1032L, 1179L, src = "shell-admission")
        MemoryPressureGate.observeShadowAtAdmission(882L, 1029L, src = "shell-post-reclaim")
        assertEquals(1, shadowChanges.size)
        assertEquals("shell-admission", shadowChanges[0].src)
        MemoryPressureGate.observeShadowAtAdmission(700L, 847L, src = "shell-admission") // 650..799 滞回带内
        assertEquals(1, shadowChanges.size)
        MemoryPressureGate.observeShadowAtAdmission(300L, 447L, src = "shell-admission")
        assertEquals(2, shadowChanges.size)
        assertEquals(MemoryPressureLevel.CRITICAL, shadowChanges[1].from)
        assertEquals(MemoryPressureLevel.NORMAL, shadowChanges[1].to)
    }

    private fun inject(anonMb: Long) = inject(anonMb = anonMb, rssMb = anonMb + 147L)
}
