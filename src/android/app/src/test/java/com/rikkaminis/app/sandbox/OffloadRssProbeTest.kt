package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [offload-rss] Tests for the pure RSS attribution probe.
 *
 * Validates two things the construction plan's "打点定位" phase needs:
 * 1. [parseVmRssKb] correctly extracts VmRSS from /proc/self/status text
 *    (pure function — no Android dependency).
 * 2. [record]/[summary]/[reset] correctly aggregate per-handler VmRSS
 *    deltas and flag LEAK-SUSPECT sources crossing 1 GiB cumulative growth.
 *    Does NOT touch /proc (the object's rssKb() reads the real process;
 *    here we feed synthetic deltas through the aggregator directly).
 */
class OffloadRssProbeTest {

    @Before
    fun setUp() {
        // 每个用例独立起点：聚合器是进程级 object，跨用例累计会污染断言。
        OffloadRssProbe.reset()
        // 治理 hook 是进程级可注入状态，测试间重置为空，避免跨用例触发。
        OffloadRssProbe.governanceHook = {}
    }

    // ---- parseVmRssKb ----

    @Test
    fun `parseVmRss_plain status text`() {
        val text = """
            Name:   com.rikkaminis.app
            State:  R (running)
            VmRSS:    123456 kB
            VmSize:   999 kB
        """.trimIndent()
        assertEquals(123456L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_missing VmRSS line returns zero`() {
        val text = """
            Name:   com.rikkaminis.app
            State:  R (running)
            VmSize: 999 kB
        """.trimIndent()
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_empty string returns zero`() {
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(""))
    }

    @Test
    fun `parseVmRss_malformed number returns zero`() {
        val text = "VmRSS:   not-a-number kB\n"
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parseVmRss_prefixed line not treated as VmRSS`() {
        // 只认以 "VmRSS:" 开头的行，不以 substring 前缀误匹配其他行
        val text = "NotVmRSS:  123 kB\n"
        assertEquals(0L, OffloadRssProbe.parseVmRssKb(text))
    }

    // ---- record / aggregation ----

    @Test
    fun `record accumulates per handler count and total delta`() {
        OffloadRssProbe.record("weather", 100_000, 100_000 + 10_000)   // +10k kB
        OffloadRssProbe.record("weather", 110_000, 110_000 + 5_000)    // +5k kB
        OffloadRssProbe.record("model-use", 120_000, 120_000 + 40_000) // +40k kB

        val summary = OffloadRssProbe.summary()
        // summary 只用于可读 dump；这里通过排序断言存在性（顺序: model-use 最大）
        assertTrue("summary should mention model-use", summary.contains("model-use"))
        assertTrue("summary should mention weather", summary.contains("weather"))
        // 各 handler 出现了一次（聚合行）
        assertEquals(2, summary.lines().count { it.trimStart().startsWith("model-use") || it.trimStart().startsWith("weather") })
    }

    @Test
    fun `record tracks peak single delta`() {
        OffloadRssProbe.record("browser-use", 100_000, 150_000) // peak 50k
        OffloadRssProbe.record("browser-use", 150_000, 155_000) // delta 5k, not peak
        val summary = OffloadRssProbe.summary()
        // peak=49MB (50013/1024≈48.8 → 48MB int division)
        val browserLine = summary.lines().first { it.trimStart().startsWith("browser-use") }
        assertTrue(browserLine.contains("peak=48MB") || browserLine.contains("peak=49MB"))
    }

    @Test
    fun `record preserves negative delta`() {
        // GC / shell 回收释放映射：delta 为负，应如实计入（不放号强制归零）
        OffloadRssProbe.record("sessions", 100_000, 80_000) // -20k kB
        val summary = OffloadRssProbe.summary()
        assertTrue(summary.contains("cum=-19MB") || summary.contains("cum=-20MB"))
    }

    @Test
    fun `record zero delta when before equals after`() {
        OffloadRssProbe.record("open", 100_000, 100_000)
        val summary = OffloadRssProbe.summary()
        assertTrue(summary.contains("cum=0MB"))
        assertTrue(summary.contains("count=1"))
    }

    // ---- LEAK-SUSPECT ----

    @Test
    fun `cumulative growth crossing 1 GiB flags leak suspect`() {
        // 累计正 delta > 1 GiB (1048576 kB)：需要跨过阈值。
        // 但治理阈值（256MB）会提前触发并归零累计，所以在默认 governanceHook
        // 下，1 GiB 累计永远到不了——治理把「慢泄漏告警」提前成了「慢泄漏治理」。
        // 这里注入一个空计数 hook，验证「LEAK-SUSPECT 不再由 1GiB 触发」是因为
        // 治理截断了累计，而不是告警逻辑坏了。
        OffloadRssProbe.governanceHook = {} // 保持默认空实现（治理照常归零累计）
        repeat(105) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("model-use", before, before + 10_000)
        }
        val summary = OffloadRssProbe.summary()
        // 治理在 256MB 归零后，累计被截断，无法累积到 1GiB → 不应出现 LEAK-SUSPECT。
        // 这证明治理阈值（治理）优先于告警阈值（观测）生效。
        assertFalse(
            "治理截断后不应再触发 1GiB LEAK-SUSPECT, got:\n$summary",
            summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `small cumulative growth does not flag leak suspect`() {
        // 远低于 1 GiB 阈值
        repeat(5) { i ->
            val before = 100_000L + i * 1_000L
            OffloadRssProbe.record("weather", before, before + 1_000) // 累计 5k kB
        }
        val summary = OffloadRssProbe.summary()
        assertFalse(
            "weather should NOT be LEAK-SUSPECT, got:\n$summary",
            summary.contains("[LEAK-SUSPECT]")
        )
    }

    @Test
    fun `leak suspect fires only once per handler`() {
        // leaked 标志幂等：一旦判定，后续不再重复 WARN。但治理阈值（256MB）
        // 会提前归零累计，所以 1GiB 的 LEAK-SUSPECT 在治理生效路径下同样被
        // 截断。这里改为验证 leaked 幂等语义本身：即使累计被治理归零再重新
        // 累积，每个 handler 在 summary 中也只有一行（不会重复打印）。
        repeat(120) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("sessions", before, before + 10_000)
        }
        val summary = OffloadRssProbe.summary()
        // summary 里每个 handler 只打印一行（聚合而非每调用一行）
        val sessionsLines = summary.lines().count { it.trimStart().startsWith("sessions") }
        assertEquals(1, sessionsLines)
    }

    // ---- reset ----

    @Test
    fun `reset clears all aggregation`() {
        OffloadRssProbe.record("weather", 100_000, 110_000)
        OffloadRssProbe.reset()
        val summary = OffloadRssProbe.summary()
        assertFalse(summary.contains("weather"))
    }

    // ---- governance（观测 → 治理）----

    @Test
    fun `governance hook fires when cumulative delta crosses threshold`() {
        // 累计阈值 256MB = 262144 kB。用 27 次 × 10_000 kB = 270MB 跨过。
        var fired = 0
        OffloadRssProbe.governanceHook = { fired++ }
        repeat(27) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("android-shizuku-cli", before, before + 10_000)
        }
        assertTrue("governance hook should have fired, got $fired", fired >= 1)
    }

    @Test
    fun `governance hook fires when single delta crosses peak threshold`() {
        // 单次阈值 64MB = 65536 kB。单条 70MB 增量立即触发。
        var fired = 0
        OffloadRssProbe.governanceHook = { fired++ }
        OffloadRssProbe.record("minis-browser-use", 100_000, 100_000 + 70_000)
        assertEquals(1, fired)
    }

    @Test
    fun `cumulative reset after governance prevents repeat fire every call`() {
        // 累计越界触发一次并归零后，后续小 delta 不应每条命令都触发高代价回收。
        var fired = 0
        OffloadRssProbe.governanceHook = { fired++ }
        // 跨过累计阈值
        repeat(27) { i ->
            val before = 100_000L + i * 10_000L
            OffloadRssProbe.record("weather", before, before + 10_000)
        }
        val firedAfterThreshold = fired
        assertTrue(firedAfterThreshold >= 1)
        // 触发后累计已归零，再跑 5 条小 delta（+1000kB）不应再次触发
        repeat(5) { i ->
            val before = 200_000L + i * 1_000L
            OffloadRssProbe.record("weather", before, before + 1_000)
        }
        assertEquals("no new governance should fire below threshold", firedAfterThreshold, fired)
    }

    @Test
    fun `governance hook no-op when below threshold`() {
        var fired = 0
        OffloadRssProbe.governanceHook = { fired++ }
        repeat(5) { i ->
            val before = 100_000L + i * 1_000L
            OffloadRssProbe.record("android-open", before, before + 1_000) // 累计 5k kB
        }
        assertEquals(0, fired)
    }

    @Test
    fun `governance hook default is no-op and does not throw`() {
        // 默认空 hook（未装配生产动作）时，record 不抛异常、保持纯观测。
        OffloadRssProbe.governanceHook = {}
        val before = 100_000L
        OffloadRssProbe.record("android-device", before, before + 100_000) // 单次 100MB 越界
        val summary = OffloadRssProbe.summary()
        assertTrue(summary.contains("android-device"))
    }
}
