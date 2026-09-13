package com.rikkaminis.app.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * 内存尖峰记录器（纯诊断，零介入执行路径）。
 *
 * 背景（2026-09-13 实测）：单条 shell 命令（文件密集遍历 / 大量子进程）
 * 可把 app 进程 VmRSS 在 20s 内从 338MB 推到 1,061MB；置顶会话与新建会话
 * 都能复现，设备重启亦复现 → 与会话内容/代龄无关，与「那一刻在跑什么命令」
 * 强相关。但 [MemoryPressureGate] 在 RSS ≥ 800MB 时会**拒绝一切工具调用**，
 * 于是「飙起来的那一刻」恰好是取证能力归零的时刻——现场永远抓不到。
 *
 * 本类补上这一环：**让进程自己在飙升时落盘**。
 * - 秒级采样：RSS / VmPeak / RssAnon / RssFile / RssShmem / threads /
 *   native heap / Java heap / **PRoot 子进程聚合 RSS**（区分「涨在 app 自己」
 *   还是「涨在 PRoot 子进程」——这是判定泄漏归属的关键一分）。
 * - 命令归因：命令开始/结束各打一行，带 `ΔRSS` 与命令分类（LIGHT/HEAVY/LEAKY），
 *   直接回答「哪类操作吃掉多少内存」。
 * - 门事件：内存门拒绝、shell 回收等事件同线记录，把「拒绝时刻」与「当时的
 *   内存构成」对齐。
 * - 只在需关注时写盘：RSS ≥ [WATCH_RSS_MB] 或单拍涨 ≥ [BURST_DELTA_MB]，
 *   回落后再记 [TAIL_SAMPLES] 拍即停。健康态零 IO。
 *
 * 设计约束（对齐项目「抽纯函数 + 可注入」路线）：
 * - **零 Android 依赖**：所有环境读取（/proc、Debug、Runtime、子进程枚举）
 *   经可注入 provider 提供 → 本类可在沙箱 JVM 直编直测，生产在
 *   [com.rikkaminis.app.MinisApp] 装配。
 * - 零副作用：任何读取失败都降级为 0/空，绝不 throw、绝不改变执行结果。
 * - 幂等启动：[start] 可被重复调用。
 */
object MemorySpikeRecorder {

    // ---------- 阈值（诊断参数，非治理阈值） ----------

    /** 进入观察窗口的进程 RSS（MB）。低于 [MemoryPressureGate.ELEVATED_RSS_MB] 留余量。 */
    const val WATCH_RSS_MB = 550L

    /** 单拍正增量达到该值 → 判定 BURST（无条件记录）。 */
    const val BURST_DELTA_MB = 80L

    /** 采样周期。 */
    const val SAMPLE_INTERVAL_MS = 1_000L

    /** 回落（低于观察阈值）后仍继续记录若干拍，用于观察「残留平台」。 */
    const val TAIL_SAMPLES = 12

    /** 子进程聚合每 N 拍刷新一次（枚举子进程比读自身 status 贵）。 */
    const val CHILD_REFRESH_EVERY = 5

    /** 单文件上限，超过即轮转到 `*.1`（只保留最近一段）。 */
    const val MAX_FILE_BYTES = 2L * 1024L * 1024L

    /** 文件名前缀（落在 files/logs/，沙箱内即 /var/minis/logs/）。 */
    const val FILE_PREFIX = "memspike-"

    // ---------- 数据 ----------

    /** 一次进程内存快照（kB，除 threads）。 */
    data class Snapshot(
        val rssKb: Long = 0L,
        val peakKb: Long = 0L,
        val anonKb: Long = 0L,
        val fileKb: Long = 0L,
        val shmemKb: Long = 0L,
        val threads: Int = 0,
        val nativeHeapKb: Long = 0L,
        val javaUsedKb: Long = 0L,
        val javaMaxKb: Long = 0L,
    ) {
        val rssMb: Long get() = rssKb / 1024L
    }

    /** 一个子进程的 RSS（用于 PRoot tracer 归属判定）。 */
    data class ChildProc(val pid: Long, val name: String, val rssKb: Long)

    /** 当前正在执行的命令上下文。 */
    data class CmdContext(val sessionId: String, val cmdClass: String, val preview: String)

    /** 采样决策（纯函数产物）。 */
    data class Decision(val emit: Boolean, val tailLeft: Int)

    // ---------- 可注入环境（生产在 MinisApp 装配） ----------

    /** 读取本进程快照。生产读 /proc/self/status + Debug + Runtime。 */
    @Volatile
    var snapshotProvider: () -> Snapshot = { Snapshot() }

    /** 枚举子进程及其 RSS。生产走 ProcessHandle + /proc/<pid>/status。 */
    @Volatile
    var childrenProvider: () -> List<ChildProc> = { emptyList() }

    /** 落盘一行。生产写 files/logs/memspike-<date>.log（append + flush）。 */
    @Volatile
    var sink: (String) -> Unit = {}

    /** 时间源（可注入，测试固定）。 */
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    /** 总开关（生产恒开；诊断期可关）。 */
    @Volatile
    var enabled: Boolean = true

    private val currentCmd = AtomicReference<CmdContext?>(null)
    private var started = false
    private var sampleIndex = 0L
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ---------- 纯函数 ----------

    /** 从 /proc/self/status 文本解析快照。缺字段按 0 处理。 */
    fun parseStatus(text: String): Snapshot {
        var rss = 0L; var peak = 0L; var anon = 0L; var file = 0L; var shmem = 0L; var threads = 0
        for (line in text.lineSequence()) {
            when {
                line.startsWith("VmRSS:") -> rss = kbOf(line)
                line.startsWith("VmPeak:") -> peak = kbOf(line)
                line.startsWith("RssAnon:") -> anon = kbOf(line)
                line.startsWith("RssFile:") -> file = kbOf(line)
                line.startsWith("RssShmem:") -> shmem = kbOf(line)
                line.startsWith("Threads:") -> threads = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return Snapshot(rss, peak, anon, file, shmem, threads)
    }

    /** 从 /proc/<pid>/status 文本解析进程名（Name 行）。 */
    fun parseProcName(text: String): String =
        text.lineSequence().firstOrNull { it.startsWith("Name:") }
            ?.substringAfter(":")?.trim().orEmpty()

    private fun kbOf(line: String): Long =
        line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L

    /** 单拍正增量是否达到 BURST 阈值。首拍（无前一读数为基线）不算突发。 */
    fun isBurst(prevRssKb: Long, curRssKb: Long): Boolean =
        prevRssKb > 0L && curRssKb - prevRssKb >= BURST_DELTA_MB * 1024L

    /** 采样决策：是否记录 + 剩余尾随拍数。 */
    fun decide(rssMb: Long, tailLeft: Int, burst: Boolean): Decision = when {
        burst -> Decision(true, TAIL_SAMPLES)
        rssMb >= WATCH_RSS_MB -> Decision(true, TAIL_SAMPLES)
        tailLeft > 0 -> Decision(true, tailLeft - 1)
        else -> Decision(false, 0)
    }

    /** 文件是否该轮转（纯函数）。 */
    fun shouldRotate(currentBytes: Long, maxBytes: Long = MAX_FILE_BYTES): Boolean =
        currentBytes >= maxBytes

    /** 命令预览：压平换行、截断，保证单行可 grep。 */
    fun previewOf(command: String, limit: Int = 120): String {
        val flat = command.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= limit) flat else flat.take(limit) + "…"
    }

    /** 格式化采样行（单行、易 grep）。 */
    fun formatSample(
        tsMs: Long,
        snap: Snapshot,
        prevRssKb: Long,
        children: List<ChildProc>,
        cmd: CmdContext?,
    ): String {
        val deltaMb = (snap.rssKb - prevRssKb) / 1024L
        val kidRssMb = children.sumOf { it.rssKb } / 1024L
        val sb = StringBuilder(192)
        sb.append(stampFormat(tsMs))
        sb.append(" rss=").append(snap.rssMb).append("MB(").append(sign(deltaMb)).append(')')
        sb.append(" peak=").append(snap.peakKb / 1024L).append("MB")
        sb.append(" anon=").append(snap.anonKb / 1024L)
        sb.append(" file=").append(snap.fileKb / 1024L)
        sb.append(" shmem=").append(snap.shmemKb / 1024L)
        sb.append(" th=").append(snap.threads)
        sb.append(" native=").append(snap.nativeHeapKb / 1024L).append("MB")
        sb.append(" java=").append(snap.javaUsedKb / 1024L).append('/').append(snap.javaMaxKb / 1024L).append("MB")
        sb.append(" proot=").append(children.size).append('/').append(kidRssMb).append("MB")
        if (cmd != null) {
            sb.append(" | ").append(cmd.cmdClass).append(' ').append(cmd.preview)
            sb.append(" session=").append(cmd.sessionId)
        }
        return sb.toString()
    }

    /** 格式化事件行（命令起止 / 门拒绝 / 回收）。 */
    fun formatEvent(tsMs: Long, kind: String, detail: String): String =
        stampFormat(tsMs) + " [" + kind + "] " + detail

    private fun stampFormat(tsMs: Long): String = stamp.format(Date(tsMs))

    private fun sign(v: Long): String = if (v > 0) "+$v" else v.toString()

    // ---------- 事件入口（被 ExecutionCoordinator / 门调用） ----------

    /** 命令开始：记录上下文 + 起始 RSS 行。 */
    fun onCommandStart(sessionId: String, cmdClass: String, command: String) {
        if (!enabled) return
        val ctx = CmdContext(sessionId, cmdClass, previewOf(command))
        currentCmd.set(ctx)
        write(formatEvent(clock(), "cmd-start", "rss=${safeSnapshot().rssMb}MB ${ctx.cmdClass} session=$sessionId :: ${ctx.preview}"))
    }

    /** 命令结束：记录 ΔRSS（本类最有价值的一行——直接回答「哪条命令吃掉多少」）。 */
    fun onCommandEnd(durationMs: Long, rssBeforeKb: Long? = null) {
        if (!enabled) return
        val ctx = currentCmd.getAndSet(null)
        val now = safeSnapshot()
        val before = rssBeforeKb ?: 0L
        val delta = if (before > 0) (now.rssKb - before) / 1024L else 0L
        val kids = safeChildren()
        val kidMb = kids.sumOf { it.rssKb } / 1024L
        val sb = StringBuilder(160)
        sb.append("rss=").append(now.rssMb).append("MB")
        if (before > 0) sb.append("(").append(sign(delta)).append("MB)")
        sb.append(" dur=").append(durationMs).append("ms")
        sb.append(" native=").append(now.nativeHeapKb / 1024L).append("MB")
        sb.append(" proot=").append(kids.size).append('/').append(kidMb).append("MB")
        if (ctx != null) {
            sb.append(" ").append(ctx.cmdClass).append(" session=").append(ctx.sessionId)
            sb.append(" :: ").append(ctx.preview)
        }
        write(formatEvent(clock(), "cmd-end", sb.toString()))
    }

    /** 内存门拒绝 / 其他治理事件：把「拒绝时刻」与内存构成对齐。 */
    fun onEvent(kind: String, detail: String) {
        if (!enabled) return
        val now = safeSnapshot()
        val kids = safeChildren()
        write(
            formatEvent(
                clock(),
                kind,
                "$detail rss=${now.rssMb}MB anon=${now.anonKb / 1024L} file=${now.fileKb / 1024L} " +
                    "native=${now.nativeHeapKb / 1024L}MB proot=${kids.size}/${kids.sumOf { it.rssKb } / 1024L}MB"
            )
        )
    }

    /** 当前命令上下文（供调用方判断是否需要配对 end）。 */
    fun currentCommand(): CmdContext? = currentCmd.get()

    /**
     * 兜底配对：仍有未收尾的命令上下文时才记录（异常 / 早退路径）。
     * 幂等——记录后上下文被清空。
     */
    fun onCommandEndIfPending(durationMs: Long = -1L, rssBeforeKb: Long? = null) {
        if (currentCmd.get() != null) onCommandEnd(durationMs, rssBeforeKb)
    }

    // ---------- 采样循环（由 MinisApp 的 applicationScope 驱动） ----------

    /** 幂等启动标记；采样循环本体由 [sampleOnce] 驱动以便测试。 */
    fun markStarted(): Boolean {
        if (started) return false
        started = true
        return true
    }

    /**
     * 一拍采样：返回是否写盘。循环由调用方（MinisApp / 测试）驱动，
     * 保持本类无协程依赖。
     */
    fun sampleOnce(state: LoopState): Boolean {
        if (!enabled) return false
        val snap = safeSnapshot()
        val burst = isBurst(state.prevRssKb, snap.rssKb)
        val d = decide(snap.rssMb, state.tailLeft, burst)
        state.prevRssKb = snap.rssKb
        state.tailLeft = d.tailLeft
        var wrote = false
        if (d.emit) {
            sampleIndex++
            val kids = if (sampleIndex % CHILD_REFRESH_EVERY == 1L || burst) safeChildren() else state.lastChildren
            state.lastChildren = kids
            val line = formatSample(clock(), snap, state.lastEmittedRssKb, kids, currentCmd.get())
            state.lastEmittedRssKb = snap.rssKb
            write(line)
            wrote = true
        }
        return wrote
    }

    /** 采样循环的可变状态（调用方持有，便于测试注入）。 */
    class LoopState {
        var prevRssKb: Long = 0L
        var lastEmittedRssKb: Long = 0L
        var tailLeft: Int = 0
        var lastChildren: List<ChildProc> = emptyList()
    }

    /** 供 [sampleOnce] 循环使用的间隔（毫秒）。 */
    val intervalMs: Long get() = SAMPLE_INTERVAL_MS

    // ---------- 生产环境装配（MinisApp 调用；纯 JDK，无 Android 依赖） ----------

    /** app native heap 读取（生产注入 Debug.getNativeHeapAllocatedSize，单位 byte）。 */
    @Volatile
    var nativeHeapProvider: () -> Long = { 0L }

    /**
     * 读本进程快照：/proc/self/status + java heap + 注入的 native heap。
     * 任何失败降级为 0 值快照（诊断路径绝不反噬执行路径）。
     */
    fun readSelfSnapshot(): Snapshot = try {
        val base = parseStatus(File("/proc/self/status").readText())
        val rt = Runtime.getRuntime()
        base.copy(
            nativeHeapKb = nativeHeapProvider() / 1024L,
            javaUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024L,
            javaMaxKb = rt.maxMemory() / 1024L,
        )
    } catch (t: Throwable) {
        Snapshot()
    }

    /**
     * 枚举子进程及其 RSS。
     *
     * 走 `/proc/self/task/<tid>/children`（内核 CONFIG_PROC_CHILDREN；Android
     * 默认开启）而不是 `ProcessHandle`（API 33 才有，低版本会 NoClassDefFound）。
     * 这一步是「泄漏涨在 app 自己还是涨在 PRoot tracer」的判定依据。
     */
    fun readChildProcs(): List<ChildProc> = try {
        val out = ArrayList<ChildProc>(4)
        for (pid in readChildPids()) {
            val text = File("/proc/$pid/status").readText()
            val rss = parseStatus(text).rssKb
            if (rss > 0L) out.add(ChildProc(pid, parseProcName(text), rss))
        }
        out
    } catch (t: Throwable) {
        emptyList()
    }

    /** 读取本进程的直接子进程 pid（/proc/self/task/<tid>/children）。 */
    fun readChildPids(): List<Long> {
        val pids = LinkedHashSet<Long>()
        val taskDir = File("/proc/self/task")
        val tids = taskDir.list() ?: return emptyList()
        for (tid in tids) {
            val f = File(taskDir, "$tid/children")
            val txt = runCatching { if (f.exists()) f.readText() else "" }.getOrElse { "" }
            for (tok in txt.split(' ', '\n')) {
                tok.trim().toLongOrNull()?.let { pids.add(it) }
            }
            // 主线程的 children 已覆盖全部子进程；读到就够。
            if (pids.isNotEmpty()) break
        }
        return pids.toList()
    }

    /**
     * 生产 sink：追加到 `<logsDir>/memspike-<yyyy-MM-dd>.log`，单文件超过
     * [MAX_FILE_BYTES] 时轮转为 `*.1`（只保留最近一段，避免诊断文件自成长）。
     * 每行 append + close → 崩溃瞬间（SIGABRT）也不丢已写内容。
     */
    fun installFileSink(logsDir: File) {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var day = ""
        var file: File? = null
        sink = { line ->
            try {
                val today = dayFmt.format(Date(clock()))
                var f = file
                if (f == null || today != day) {
                    f = File(logsDir, FILE_PREFIX + today + ".log")
                    f.parentFile?.mkdirs()
                    day = today
                    file = f
                }
                // 轮转判断读真实文件长度（而非进程内计数），外部改动/多次
                // install 后仍准确。
                if (shouldRotate(f.length())) {
                    val rotated = File(f.parentFile, f.name + ".1")
                    runCatching { rotated.delete() }
                    runCatching { f.renameTo(rotated) }
                }
                f.appendText(line + "\n")
            } catch (t: Throwable) {
                // 磁盘满 / 权限异常：静默降级，绝不影响执行路径
            }
        }
    }

    /** 装配生产 providers（MinisApp.onCreate 调用一次）。 */
    fun installProductionProviders(
        logsDir: File,
        /** 单位 byte（生产传 Debug.getNativeHeapAllocatedSize）。 */
        nativeHeapBytes: () -> Long,
    ) {
        nativeHeapProvider = nativeHeapBytes
        snapshotProvider = { readSelfSnapshot() }
        childrenProvider = { readChildProcs() }
        installFileSink(logsDir)
    }

    // ---------- 内部 ----------

    private fun safeChildren(): List<ChildProc> = try {
        childrenProvider()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 快照读取同样不得反噬执行路径：provider 抛异常时降级为 0 值快照。 */
    private fun safeSnapshot(): Snapshot = try {
        snapshotProvider()
    } catch (t: Throwable) {
        Snapshot()
    }

    private fun write(line: String) {
        try {
            sink(line)
        } catch (t: Throwable) {
            // 诊断路径绝不反噬执行路径
        }
    }

    /** 测试用：重置内部状态。生产不调用。 */
    fun resetForTest() {
        currentCmd.set(null)
        started = false
        sampleIndex = 0L
    }
}
