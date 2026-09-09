package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [provider-rss v2 / TF-A] Tests for the provider RSS attribution probe.
 *
 * Mirrors BrowserRssProbeTest: validates (1) all parseVm* parsers as pure
 * functions (RSS + HWM + Data + Peak + Pid + Name), (2) the ProbeRecord
 * brief()/peakDeltaKb() derived fields, and (3) record/summary/reset
 * aggregation + dual-condition LEAK-SUSPECT (peak-sustained A / post-idle
 * non-recovery B) + new aggregation fields (peakΔmax / postRss / lowest).
 * No Android dependency — /proc is never touched here, we feed synthetic
 * records through the aggregator.
 */
class ProviderRssProbeTest {

    @Before
    fun setUp() {
        ProviderRssProbe.reset()
    }

    // ---- pure parsers ----

    private val statusText = """
        Name:   com.rikkaminis.app
        State:  R (running)
        Pid:    18042
        VmPeak:   999999 kB
        VmSize:   456789 kB
        VmHWM:    234567 kB
        VmRSS:    123456 kB
        VmData:    98765 kB
        Anonymous:    1 kB
        VmSwap:   100 kB
    """.trimIndent()

    @Test
    fun `parseVmRss plain status text`() {
        assertEquals(123456L, ProviderRssProbe.parseVmRssKb(statusText))
    }

    @Test
    fun `parseVmHwm plain status text`() {
        assertEquals(234567L, ProviderRssProbe.parseVmHwmKb(statusText))
    }

    @Test
    fun `parseVmData plain status text`() {
        assertEquals(98765L, ProviderRssProbe.parseVmDataKb(statusText))
    }

    @Test
    fun `parseVmPeak plain status text`() {
        assertEquals(999999L, ProviderRssProbe.parseVmPeakKb(statusText))
    }

    @Test
    fun `parsePid plain status text`() {
        assertEquals(18042, ProviderRssProbe.parsePid(statusText))
    }

    @Test
    fun `parseProcessName plain status text`() {
        assertEquals("com.rikkaminis.app", ProviderRssProbe.parseProcessName(statusText))
    }

    @Test
    fun `parseVm generic key matches VmRSS`() {
        assertEquals(123456L, ProviderRssProbe.parseVmKb(statusText, "VmRSS"))
    }

    @Test
    fun `parseVmRss missing VmRSS line returns zero`() {
        val text = "Name:   com.rikkaminis.app\nVmSize: 999 kB\n".trimIndent()
        assertEquals(0L, ProviderRssProbe.parseVmRssKb(text))
    }

    @Test
    fun `parse all missing lines return safe defaults`() {
        val empty = ""
        assertEquals(0L, ProviderRssProbe.parseVmHwmKb(empty))
        assertEquals(0L, ProviderRssProbe.parseVmDataKb(empty))
        assertEquals(0L, ProviderRssProbe.parseVmPeakKb(empty))
        assertEquals(-1, ProviderRssProbe.parsePid(empty))
        assertEquals("", ProviderRssProbe.parseProcessName(empty))
    }

    @Test
    fun `parseVmRss malformed number returns zero`() {
        assertEquals(0L, ProviderRssProbe.parseVmRssKb("VmRSS:   not-a-number kB\n"))
    }

    // ---- ProbeRecord derived fields ----

    @Test
    fun `peakDeltaKb uses sampled peak rss`() {
        val rec = ProviderRssProbe.ProbeRecord(
            kind = "sendMessage:x", beforeRss = 100_000, afterRss = 120_000, peakRss = 150_000,
        )
        assertEquals(50_000L, rec.peakDeltaKb())
    }

    @Test
    fun `peakDeltaKb falls back to vmHwm when peak unknown`() {
        val rec = ProviderRssProbe.ProbeRecord(
            kind = "sendMessage:x", beforeRss = 100_000, afterRss = 120_000, peakRss = -1L,
            vmHwm = 140_000,
        )
        assertEquals(40_000L, rec.peakDeltaKb())
    }

    @Test
    fun `peakDeltaKb clamps negative to zero`() {
        val rec = ProviderRssProbe.ProbeRecord(
            kind = "sendMessage:x", beforeRss = 100_000, afterRss = 80_000, peakRss = 90_000,
        )
        assertEquals(0L, rec.peakDeltaKb())
    }

    @Test
    fun `brief includes remote fallback worker runId and bytes`() {
        val rec = ProviderRssProbe.ProbeRecord(
            kind = "streamMessage:mock", beforeRss = 100_000, afterRss = 120_000, peakRss = 130_000,
            runId = "run-42", workerPid = 999, remote = true, fallback = false,
            inputBytes = 1024, outputBytes = 2048,
        )
        val b = rec.brief()
        assertTrue(b.contains("run=run-42"))
        assertTrue(b.contains("worker=999"))
        assertTrue(b.contains("remote"))
        assertFalse(b.contains("fallback"))
        assertTrue(b.contains("in=1024"))
        assertTrue(b.contains("out=2048"))
    }

    // ---- record / aggregation ----

    @Test
    fun `record accumulates per kind count and total delta`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 110_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 110_000, afterRss = 115_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "streamMessage:mock", beforeRss = 120_000, afterRss = 160_000))

        val summary = ProviderRssProbe.summary()
        assertTrue("summary should mention sendMessage", summary.contains("sendMessage"))
        assertTrue("summary should mention streamMessage", summary.contains("streamMessage"))
    }

    @Test
    fun `record preserves negative delta`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "streamMessage:mock", beforeRss = 100_000, afterRss = 80_000))
        val summary = ProviderRssProbe.summary()
        assertTrue("negative cum expected, got:\n$summary", summary.contains("cum=-19MB") || summary.contains("cum=-20MB"))
    }

    @Test
    fun `record zero delta when before equals after`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 100_000))
        val summary = ProviderRssProbe.summary()
        assertTrue(summary.contains("cum=0MB"))
        assertTrue(summary.contains("count=1"))
    }

    @Test
    fun `summary shows peakDeltaMax and postRss aggregation`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 110_000, peakRss = 120_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 110_000, afterRss = 115_000, peakRss = 130_000))
        val summary = ProviderRssProbe.summary()
        // peakΔmax = 130000 − 110000 = 20000kB = 19MB；postRss = 最后 afterRss 115000kB = 112MB
        assertTrue("expect peakΔmax=19MB, got:\n$summary", summary.contains("peakΔmax=19MB"))
        assertTrue("expect postRss=112MB, got:\n$summary", summary.contains("postRss=112MB"))
    }

    // ---- LEAK-SUSPECT dual condition ----

    @Test
    fun `single call peak sustained explosion flags leak suspect`() {
        // 条件 A：单次调用 peakRss − beforeRss 达到 512MB。
        ProviderRssProbe.record(
            ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 300_000, peakRss = 700_000)
        )
        val summary = ProviderRssProbe.summary()
        assertTrue("expected LEAK-SUSPECT on peak explosion, got:\n$summary", summary.contains("[LEAK-SUSPECT]"))
    }

    @Test
    fun `many calls with monotonic post-idle rss not recovering flags leak suspect`() {
        // 条件 B：≥3 轮后 afterRss 只涨不落（相对最低点漂移 ≥256MB）。
        // 每轮 before 不变、after 递加 ~90MB，5 轮累计漂移 360MB。
        var after = 100_000L
        repeat(5) {
            ProviderRssProbe.record(
                ProviderRssProbe.ProbeRecord(
                    kind = "streamMessage:mock",
                    beforeRss = 100_000L,
                    afterRss = after,
                    peakRss = after,
                )
            )
            after += 90_000L // ~88MB/轮
        }
        val summary = ProviderRssProbe.summary()
        assertTrue("expected LEAK-SUSPECT on post-idle non-recovery, got:\n$summary", summary.contains("[LEAK-SUSPECT]"))
    }

    @Test
    fun `post-idle rss recovering back down does not flag`() {
        // before/after 回到基线，即使多次调用也不判 B（回落健康）。
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 105_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 103_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 101_000))
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 100_000))
        val summary = ProviderRssProbe.summary()
        assertFalse("healthy recovery should NOT be LEAK-SUSPECT, got:\n$summary", summary.contains("[LEAK-SUSPECT]"))
    }

    @Test
    fun `small cumulative growth does not flag leak suspect`() {
        // 健康波动：多次调用但 afterRss 围绕 ~100MB 基线小范围往返（±20MB），
        // 峰值增量、总量、post-idle 漂移全部远低于阈值 → 不应判 LEAK-SUSPECT。
        val baseline = 100_000L
        val deltas = longArrayOf(10_000, -8_000, 5_000, -3_000, 12_000, -9_000, 2_000, -4_000, 8_000, -6_000)
        for (d in deltas) {
            val before = baseline
            val after = baseline + d
            ProviderRssProbe.record(
                ProviderRssProbe.ProbeRecord(
                    kind = "streamMessage:mock", beforeRss = before, afterRss = after, peakRss = baseline + d.coerceAtLeast(0),
                )
            )
        }
        val summary = ProviderRssProbe.summary()
        assertFalse("small bounded fluctuation should NOT flag, got:\n$summary", summary.contains("[LEAK-SUSPECT]"))
    }

    @Test
    fun `record ignores empty kind`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "", beforeRss = 100_000, afterRss = 200_000))
        val summary = ProviderRssProbe.summary()
        assertEquals("(no data)", summary)
    }

    @Test
    fun `reset clears all aggregation`() {
        ProviderRssProbe.record(ProviderRssProbe.ProbeRecord(kind = "sendMessage:mock", beforeRss = 100_000, afterRss = 110_000))
        ProviderRssProbe.reset()
        val summary = ProviderRssProbe.summary()
        assertFalse(summary.contains("sendMessage"))
    }
}