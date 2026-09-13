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

    @Before
    fun setUp() {
        reclaimCalls = 0
        notifications = 0
        lastNotifiedLevel = null
        tierChanges.clear()
        MemoryPressureGate.resetTierStateForTest()
        MemoryPressureGate.reclaimHook = { reclaimCalls++ }
        MemoryPressureGate.pressureListener = { level, _ ->
            notifications++
            lastNotifiedLevel = level
        }
        tierListener = { from, to, _ -> tierChanges.add(from to to) }
    }

    @After
    fun tearDown() {
        MemoryPressureGate.reclaimHook = {}
        MemoryPressureGate.pressureListener = { _, _ -> }
        tierListener = { _, _, _ -> }
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
        MemoryPressureGate.pressureListener = { level, anon -> seen += level to anon }
        inject(500)
        MemoryPressureGate.notify(MemoryPressureLevel.NORMAL)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED)
        assertEquals(listOf(MemoryPressureLevel.ELEVATED to 500L), seen)
    }

    @Test
    fun `tier listener reports both rises and falls`() {
        val seen = mutableListOf<Triple<MemoryPressureLevel, MemoryPressureLevel, Long>>()
        tierListener = { from, to, anon -> seen += Triple(from, to, anon) }
        inject(500)
        MemoryPressureGate.notify(MemoryPressureLevel.ELEVATED)
        inject(1300)
        MemoryPressureGate.notify(MemoryPressureLevel.CRITICAL)
        // 降回 NORMAL 也要上报：这是「正常重活会不会常穿越梯子」的统计来源。
        MemoryPressureGate.notify(MemoryPressureLevel.NORMAL)
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

    private fun inject(anonMb: Long) = inject(anonMb = anonMb, rssMb = anonMb + 147L)
}
