package com.rikkaminis.app.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * MemorySpikeRecorder 的纯 JVM 单测：解析 / 决策 / 格式化 / 采样循环 /
 * 落盘与轮转 / socket 名多实例区分。
 *
 * 全部走可注入 provider，不触碰 Android（本类刻意零 Android 依赖）。
 */
class MemorySpikeRecorderTest {

    private val statusText = """
        Name:	com.rikkaminis.app
        VmPeak:	16864044 kB
        VmSize:	16864044 kB
        VmRSS:	  2538000 kB
        RssAnon:	  1800000 kB
        RssFile:	   700000 kB
        RssShmem:	    38000 kB
        Threads:	55
    """.trimIndent()

    @Before
    fun setUp() {
        MemorySpikeRecorder.resetForTest()
        MemorySpikeRecorder.enabled = true
        MemorySpikeRecorder.sink = {}
        MemorySpikeRecorder.snapshotProvider = { MemorySpikeRecorder.Snapshot() }
        MemorySpikeRecorder.childrenProvider = { emptyList() }
        MemorySpikeRecorder.clock = { 0L }
    }

    @After
    fun tearDown() {
        MemorySpikeRecorder.resetForTest()
        MemorySpikeRecorder.sink = {}
    }

    private fun snap(rssMb: Long, nativeMb: Long = 10L, threads: Int = 50) = MemorySpikeRecorder.Snapshot(
        rssKb = rssMb * 1024L,
        peakKb = rssMb * 2 * 1024L,
        anonKb = rssMb * 1024L / 2,
        fileKb = rssMb * 1024L / 4,
        shmemKb = 16L * 1024L,
        threads = threads,
        nativeHeapKb = nativeMb * 1024L,
        javaUsedKb = 30L * 1024L,
        javaMaxKb = 512L * 1024L,
    )

    // ---------- 解析 ----------

    @Test
    fun `parseStatus extracts every field`() {
        val s = MemorySpikeRecorder.parseStatus(statusText)
        assertEquals(2538000L, s.rssKb)
        assertEquals(16864044L, s.peakKb)
        assertEquals(1800000L, s.anonKb)
        assertEquals(700000L, s.fileKb)
        assertEquals(38000L, s.shmemKb)
        assertEquals(55, s.threads)
        assertEquals(2478L, s.rssMb)
    }

    @Test
    fun `parseStatus tolerates missing lines`() {
        val s = MemorySpikeRecorder.parseStatus("Name:\tx\n")
        assertEquals(0L, s.rssKb)
        assertEquals(0, s.threads)
    }

    @Test
    fun `parseProcName reads the Name line`() {
        assertEquals("libproot.so", MemorySpikeRecorder.parseProcName("Name:\tlibproot.so\nVmRSS:\t1200 kB\n"))
        assertEquals("", MemorySpikeRecorder.parseProcName("VmRSS:\t1 kB\n"))
    }

    @Test
    fun `parsePpid reads the parent pid and defaults to -1`() {
        assertEquals(4242L, MemorySpikeRecorder.parsePpid("Name:\tx\nPPid:\t4242\n"))
        assertEquals(-1L, MemorySpikeRecorder.parsePpid("Name:\tx\n"))
    }

    @Test
    fun `child enumeration never throws and self pid is readable on the host JVM`() {
        // 快路径（内核 children）在 Android 常缺失 → 必须能退回扫描且不抛异常
        assertNotNull(MemorySpikeRecorder.readChildProcs())
        assertNotNull(MemorySpikeRecorder.readChildPids())
        assertNotNull(MemorySpikeRecorder.scanChildPids())
        // Linux host（沙箱 JVM / CI）应能读到自身 pid
        val self = MemorySpikeRecorder.readSelfPid()
        assertTrue("self pid must be readable on Linux", self != null && self > 0L)
    }

    @Test
    fun `pickChildPids selects only direct children with a real rss`() {
        val self = 1000L
        val readers = mapOf(
            "1000" to "Name:\tself\nPPid:\t1\nVmRSS:\t50000 kB\n",
            "1001" to "Name:\tlibproot.so\nPPid:\t1000\nVmRSS:\t3680 kB\n",
            "1002" to "Name:\tsh\nPPid:\t1000\nVmRSS:\t1200 kB\n",
            "1003" to "Name:\tother\nPPid:\t42\nVmRSS:\t9000 kB\n",
            "1004" to "Name:\tgone\nPPid:\t1000\nVmRSS:\t0 kB\n",
        )
        val picked = MemorySpikeRecorder.pickChildPids(
            entries = listOf("1000", "1001", "1002", "1003", "1004", "not-a-pid"),
            selfPid = self,
            statusReader = { readers[it] },
        )
        assertEquals(listOf(1001L, 1002L), picked)
    }

    @Test
    fun `pickChildPids respects the entry cap`() {
        val picked = MemorySpikeRecorder.pickChildPids(
            entries = (1..50).map { it.toString() },
            selfPid = 7L,
            statusReader = { "Name:\tx\nPPid:\t7\nVmRSS:\t100 kB\n" },
            maxEntries = 3,
        )
        assertEquals(listOf(1L, 2L, 3L), picked)
    }

    @Test
    fun `pickChildPids tolerates unreadable entries`() {
        val picked = MemorySpikeRecorder.pickChildPids(
            entries = listOf("1", "2", "3"),
            selfPid = 1L,
            statusReader = { if (it == "2") null else "Name:\tx\nPPid:\t1\nVmRSS:\t10 kB\n" },
        )
        assertEquals(listOf(1L, 3L), picked)
    }

    // ---------- 决策 ----------

    @Test
    fun `isBurst fires exactly at the threshold`() {
        val base = 500L * 1024L
        assertFalse(MemorySpikeRecorder.isBurst(base, base + 79L * 1024L))
        assertTrue(MemorySpikeRecorder.isBurst(base, base + 80L * 1024L))
        assertFalse(MemorySpikeRecorder.isBurst(base, base - 500L * 1024L))
    }

    @Test
    fun `isBurst treats the first reading as a baseline, not a burst`() {
        // 采样循环第一拍的 prev=0，若不排除会把「进程本来就有 900MB」误报成突发
        assertFalse(MemorySpikeRecorder.isBurst(0L, 900L * 1024L))
    }

    @Test
    fun `decide emits while above watch and drains the tail afterwards`() {
        // 高于观察线：记录并重置尾随计数
        val hot = MemorySpikeRecorder.decide(700L, 0, burst = false)
        assertTrue(hot.emit)
        assertEquals(MemorySpikeRecorder.TAIL_SAMPLES, hot.tailLeft)

        // 回落后：继续记录 TAIL_SAMPLES 拍
        var tail = hot.tailLeft
        repeat(MemorySpikeRecorder.TAIL_SAMPLES) {
            val d = MemorySpikeRecorder.decide(300L, tail, burst = false)
            assertTrue("tail sample must be recorded", d.emit)
            tail = d.tailLeft
        }
        assertEquals(0, tail)

        // 尾随耗尽：安静
        val quiet = MemorySpikeRecorder.decide(300L, 0, burst = false)
        assertFalse(quiet.emit)
    }

    @Test
    fun `decide always emits on burst even below the watch line`() {
        val d = MemorySpikeRecorder.decide(200L, 0, burst = true)
        assertTrue(d.emit)
        assertEquals(MemorySpikeRecorder.TAIL_SAMPLES, d.tailLeft)
    }

    @Test
    fun `shouldRotate compares against the cap`() {
        assertFalse(MemorySpikeRecorder.shouldRotate(MemorySpikeRecorder.MAX_FILE_BYTES - 1))
        assertTrue(MemorySpikeRecorder.shouldRotate(MemorySpikeRecorder.MAX_FILE_BYTES))
    }

    // ---------- 预览与格式 ----------

    @Test
    fun `previewOf flattens newlines and truncates`() {
        assertEquals("ls -la /tmp", MemorySpikeRecorder.previewOf("ls -la\n/tmp\n"))
        val long = "x ".repeat(200) // spaced, so it is not a token-shaped run
        val p = MemorySpikeRecorder.previewOf(long, limit = 10)
        assertEquals(11, p.length) // 10 + 省略号
        assertTrue(p.endsWith("…"))
    }

    @Test
    fun `previewOf masks token shaped runs`() {
        val cmd = "curl -H \"Authorization: Bearer gsk_abcdefghijklmnop\" " +
            "--token 0123456789abcdef0123456789abcdef /sdcard/x"
        val out = MemorySpikeRecorder.previewOf(cmd)
        assertFalse("api key leaked: $out", out.contains("gsk_abcdefghijklmnop"))
        assertFalse("token leaked: $out", out.contains("0123456789abcdef0123456789abcdef"))
        assertTrue("nothing masked: $out", out.contains("***"))
        assertTrue("command context lost: $out", out.contains("curl -H"))
    }

    @Test
    fun `formatSample carries rss delta, breakdown and command context`() {
        val line = MemorySpikeRecorder.formatSample(
            tsMs = 0L,
            snap = snap(rssMb = 1000L, nativeMb = 145L),
            prevRssKb = 300L * 1024L,
            children = listOf(MemorySpikeRecorder.ChildProc(42L, "libproot.so", 180L * 1024L)),
            cmd = MemorySpikeRecorder.CmdContext("sess-1", "HEAVY", "find src -name '*.kt'"),
        )
        assertTrue(line.contains("rss=1000MB(+700)"))
        assertTrue(line.contains("native=145MB"))
        assertTrue(line.contains("proot=1/180MB"))
        assertTrue(line.contains("HEAVY find src -name '*.kt'"))
        assertTrue(line.contains("session=sess-1"))
    }

    @Test
    fun `formatSample is a single line even with a multi-line command`() {
        val line = MemorySpikeRecorder.formatSample(
            tsMs = 0L,
            snap = snap(600L),
            prevRssKb = 0L,
            children = emptyList(),
            cmd = MemorySpikeRecorder.CmdContext("s", "LIGHT", MemorySpikeRecorder.previewOf("a\nb\nc")),
        )
        assertFalse(line.contains("\n"))
    }

    // ---------- 采样循环 ----------

    @Test
    fun `sampleOnce records only when watch line is crossed and keeps the tail`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }

        // 320 → 330(+10) → 900(+570 突发) → 880 → 500 → 320 ×3
        val readings = ArrayDeque(listOf(320L, 330L, 900L, 880L, 500L, 320L, 320L, 320L))
        MemorySpikeRecorder.snapshotProvider = { snap(readings.first()) }
        val state = MemorySpikeRecorder.LoopState()

        // 1) 320MB：低于观察线，不记录
        assertFalse(MemorySpikeRecorder.sampleOnce(state))
        readings.removeFirst()

        // 2) 330MB：仍低于观察线且只是小幅增长，不记录
        assertFalse(MemorySpikeRecorder.sampleOnce(state))
        readings.removeFirst()

        // 3) 900MB：越过观察线（同时是 BURST）→ 记录
        assertTrue(MemorySpikeRecorder.sampleOnce(state))
        readings.removeFirst()

        // 4) 880MB：仍在线以上 → 记录
        assertTrue(MemorySpikeRecorder.sampleOnce(state))
        readings.removeFirst()

        // 5) 500MB：回落到线下但尾随未耗尽 → 记录
        assertTrue(MemorySpikeRecorder.sampleOnce(state))
        readings.removeFirst()

        // 6..：尾随 TAIL_SAMPLES 拍逐步耗尽，期间仍记录
        var recorded = 0
        while (readings.isNotEmpty()) {
            if (MemorySpikeRecorder.sampleOnce(state)) recorded++
            readings.removeFirst()
        }
        assertEquals("第 3、4、5 拍 + 剩余 3 拍", 6, written.size)
        assertEquals(3, recorded)
        assertTrue(written.first().contains("rss=900MB"))
        assertTrue(written.last().contains("rss=320MB"))
    }

    @Test
    fun `onCommandEnd writes delta against the start rss`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        MemorySpikeRecorder.snapshotProvider = { snap(1000L, nativeMb = 145L) }

        MemorySpikeRecorder.onCommandStart("sess-9", "HEAVY", "find . -name '*.kt'")
        MemorySpikeRecorder.onCommandEnd(durationMs = 12_340L, rssBeforeKb = 316L * 1024L)

        assertEquals(2, written.size)
        assertTrue(written[0].contains("[cmd-start]"))
        val end = written[1]
        assertTrue(end.contains("[cmd-end]"))
        assertTrue(end.contains("rss=1000MB(+684MB)"))
        assertTrue(end.contains("dur=12340ms"))
        assertTrue(end.contains("HEAVY session=sess-9"))
    }

    @Test
    fun `onCommandEndIfPending is a no-op without a pending command`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        MemorySpikeRecorder.onCommandEndIfPending()
        assertTrue(written.isEmpty())

        MemorySpikeRecorder.onCommandStart("s", "LIGHT", "echo hi")
        written.clear()
        MemorySpikeRecorder.onCommandEndIfPending(durationMs = 5L)
        assertEquals(1, written.size)
        // 已收尾 → 再调为 no-op
        MemorySpikeRecorder.onCommandEndIfPending()
        assertEquals(1, written.size)
    }

    @Test
    fun `onEvent writes a single line with the breakdown`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        MemorySpikeRecorder.snapshotProvider = { snap(871L, nativeMb = 100L) }
        MemorySpikeRecorder.childrenProvider = {
            listOf(MemorySpikeRecorder.ChildProc(1L, "libproot.so", 200L * 1024L))
        }
        MemorySpikeRecorder.onEvent("reject-rss", "session=abc rssAfter=871MB")
        assertEquals(1, written.size)
        assertTrue(written[0].contains("[reject-rss]"))
        assertTrue(written[0].contains("rss=871MB"))
        assertTrue(written[0].contains("proot=1/200MB"))
    }

    @Test
    fun `disabled recorder writes nothing`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        MemorySpikeRecorder.enabled = false
        MemorySpikeRecorder.onCommandStart("s", "LIGHT", "echo hi")
        MemorySpikeRecorder.onEvent("x", "y")
        MemorySpikeRecorder.sampleOnce(MemorySpikeRecorder.LoopState())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `measurePhase records the delta and returns the block value`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        val readings = ArrayDeque(listOf(300L, 900L))
        MemorySpikeRecorder.snapshotProvider = { snap(readings.removeFirst(), nativeMb = 10L) }

        val out = MemorySpikeRecorder.measurePhase("phase:test", "detail=1") { "result" }

        assertEquals("result", out)
        assertEquals(1, written.size)
        val line = written[0]
        assertTrue(line.contains("[phase:test]"))
        assertTrue(line.contains("(+600MB)"))
        assertTrue(line.contains("detail=1"))
        assertTrue(line.contains("dur="))
        assertTrue(line.contains("native=10→10MB"))
    }

    @Test
    fun `measurePhase still records when the block throws`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        val readings = ArrayDeque(listOf(300L, 400L))
        MemorySpikeRecorder.snapshotProvider = { snap(readings.removeFirst()) }

        var threw = false
        try {
            MemorySpikeRecorder.measurePhase("phase:boom") { throw IllegalStateException("x") }
        } catch (t: IllegalStateException) {
            threw = true
        }

        assertTrue("异常必须原样抛出", threw)
        assertEquals("但阶段仍要落盘", 1, written.size)
        assertTrue(written[0].contains("[phase:boom]"))
    }

    @Test
    fun `measurePhase writes nothing when disabled`() {
        val written = mutableListOf<String>()
        MemorySpikeRecorder.sink = { written.add(it) }
        MemorySpikeRecorder.enabled = false
        assertEquals(7, MemorySpikeRecorder.measurePhase("phase:off") { 7 })
        assertTrue(written.isEmpty())
    }

    @Test
    fun `provider failures never propagate`() {
        MemorySpikeRecorder.snapshotProvider = { throw IllegalStateException("boom") }
        MemorySpikeRecorder.childrenProvider = { throw IllegalStateException("boom") }
        MemorySpikeRecorder.sink = { throw IllegalStateException("disk full") }
        // 不抛异常即通过
        MemorySpikeRecorder.onEvent("test", "detail")
    }

    // ---------- 文件落盘 ----------

    @Test
    fun `installFileSink appends one line per call and rotates over the cap`() {
        val dir = Files.createTempDirectory("memspike-test").toFile()
        try {
            MemorySpikeRecorder.clock = { 1_700_000_000_000L }
            MemorySpikeRecorder.installFileSink(dir)
            MemorySpikeRecorder.sink("first")
            MemorySpikeRecorder.sink("second")
            val f = dir.listFiles()!!.first { it.name.startsWith(MemorySpikeRecorder.FILE_PREFIX) }
            val lines = f.readLines()
            assertEquals(2, lines.size)
            assertEquals("first", lines[0])
            assertEquals("second", lines[1])

            // 轮转：伪造超限文件后下一次写入应轮转
            f.writeText("x".repeat((MemorySpikeRecorder.MAX_FILE_BYTES).toInt()))
            MemorySpikeRecorder.sink("third")
            assertTrue(File(dir, f.name + ".1").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
