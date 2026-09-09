package com.openminis.app.provider

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯观测打点（provider-rss v2）：记录每个 provider 调用（sendMessage / streamMessage）
 * 执行前后的主进程 VmRSS 增量，以及调用期间的峰值 / VmHWM / VmData / VmPeak 快照，
 * 覆盖「聊天收发消息直连 LLM provider」这条路径的泄漏归因。
 *
 * 背景（D-3，2026-08-20 / v1）：OffloadRssProbe 只覆盖走 offload socket 的工具 handler，
 * 但 ChatViewModel 里有若干处在主进程直接调 provider.sendMessage / streamMessage 的
 * 聊天主路径（agent loop、compaction、标题生成、streamChatTurnOffloaded 的 in-process
 * fallback）——它们绕过 offload socket，native 增长发生在主进程且此前完全没有 RSS 归因。
 *
 * v2 增强（TF-A，2026-08-22）：
 * - 每条记录带：pid / processName / runId / kind / beforeRss / peakRss / afterRss /
 *   vmHwm / vmData / vmPeak / workerPid / remote / fallback / inputBytes / outputBytes。
 * - peakRss 用调用期间的峰值采样（轻量 daemon 线程每 ~80ms 读 /proc/self/status 的
 *   VmRSS 最大值），补上 v1「只有前后快照」的盲区。
 * - 进程内聚合新增 peakDeltaMax / postCallRss / count / totalDeltaKb / avg。
 * - LEAK-SUSPECT 改为双条件：单次峰值增长超阈值（A） OR 多轮后 post-idle RSS 不回落（B），
 *   不能只靠累计 cum（v1 用 cum>=1GiB 会因 GC 回落被长期掩盖）。
 *
 * 设计约束（对齐 OffloadRssProbe / BrowserRssProbe 同构）：
 * - 零副作用：读 /proc/self/status 失败返回 0，绝不 throw、绝不改变调用路径或结果。
 * - 归因：按调用类型（sendMessage / streamMessage）聚合 delta、次数、峰值。
 * - 可 JVM 单测：parseVm* 独立成纯函数；sampler 与聚合逻辑均可喂合成数据驱动。
 * - 单位：kB（VmRSS 原生单位）。
 *
 * 挂点：LLMProvider.sendMessage / streamMessage 是两个默认实现（default method），
 * 所有 provider 的具体实现都经它俩进入 —— 在这里打点 = 一处改动覆盖所有 provider 调用。
 * streamMessage 是冷 Flow，打点放在 Flow 被 collect 的前后（真正发请求时），
 * 而非 streamMessage 调用时刻。
 */
object ProviderRssProbe {
    private const val TAG = "ProviderRssProbe"

    /** 单调用类型累计正 delta 超过该值即判定「可疑泄漏源」（兼容旧语义兜底）。 */
    private const val LEAK_SUSPECT_CUM_KB = 1_024L * 1024L  // 1 GiB 累计

    /** 每条记录峰值采样间隔（ms）。 */
    private const val PEAK_SAMPLE_MS = 80L

    /** 单次调用 peakDelta 超过该值（512MB）即触发 LEAK-SUSPECT 条件 A（峰值持续偏高）。 */
    private const val PEAK_SUSPECT_KB = 512L * 1024L

    /** post-idle 不回落判定：至少经过这么多调用后才可能判 B。 */
    private const val POST_IDLE_MIN_CALLS = 3

    /** 多轮后 afterRss 相对最低点累计漂移超过该值（256MB）且未回落，即触发条件 B。 */
    private const val POST_IDLE_DRIFT_KB = 256L * 1024L

    /**
     * 单条 provider 调用的完整观测记录。所有字段仅在打点层使用，不影响调用结果。
     * - pid          ：provider 调用发生在哪个进程（主进程 / :modelservice 子进程）。
     * - processName  ：进程名（如 com.openminis.app 或 com.openminis.app:modelservice）。
     * - runId        ：所属 agent 运行的 ID（若调用点可观测到）。
     * - kind         ：调用类型（"sendMessage" / "streamMessage"），通常带 provider 名后缀。
     * - beforeRss    ：调用前 VmRSS（kB）。
     * - peakRss      ：调用期间采样峰值 VmRSS（kB）；采样不可用时为 -1，回退用 vmHwm。
     * - afterRss     ：调用后 VmRSS（kB）。
     * - vmHwm/vmData/vmPeak：调用结束后 /proc/self/status 快照。
     * - workerPid    ：若该调用已 offload 到 :modelservice，记录 worker 进程 pid；主进程直连 -1。
     * - remote       ：是否经 :modelservice 远程执行。
     * - fallback     ：是否因 remote 失败/不可用回退到主进程 in-process 执行。
     * - inputBytes/outputBytes：入参序列化估算 & 响应体大小（可观测时），-1 表示未知。
     */
    data class ProbeRecord(
        val kind: String = "",
        val beforeRss: Long = 0L,
        val afterRss: Long = 0L,
        val peakRss: Long = -1L,
        val vmHwm: Long = 0L,
        val vmData: Long = 0L,
        val vmPeak: Long = 0L,
        val runId: String? = null,
        val workerPid: Int = -1,
        val remote: Boolean = false,
        val fallback: Boolean = false,
        val inputBytes: Long = -1L,
        val outputBytes: Long = -1L,
    ) {
        /** 调用期间的峰值增量（kB）。peakRss 未知时回退到 vmHwm − beforeRss。 */
        fun peakDeltaKb(): Long =
            if (peakRss >= 0L) ((peakRss - beforeRss).coerceAtLeast(0L))
            else ((vmHwm - beforeRss).coerceAtLeast(0L))

        /** 单条记录的核心日志摘要（不含噪音字段）。 */
        fun brief(): String =
            "kind=$kind pid=${ProviderRssProbe.pid} process=${ProviderRssProbe.processName} " +
                "Δ=${(afterRss - beforeRss) / 1024}MB " +
                "before=${beforeRss / 1024}MB peak=${if (peakRss >= 0) peakRss / 1024 else vmHwm / 1024}MB " +
                "after=${afterRss / 1024}MB hwm=${vmHwm / 1024}MB data=${vmData / 1024}MB " +
                "vmpeak=${vmPeak / 1024}MB" +
                runId?.let { " run=$it" }.orEmpty() +
                (if (workerPid > 0) " worker=$workerPid" else "") +
                (if (remote) " remote" else "") +
                (if (fallback) " fallback" else "") +
                (if (inputBytes >= 0) " in=${inputBytes}" else "") +
                (if (outputBytes >= 0) " out=${outputBytes}" else "")
    }

    private data class Stats(
        var count: Int = 0,
        var totalDeltaKb: Long = 0L,
        var peakDeltaMaxKb: Long = 0L,   // 所有调用中单次 peakDeltaKb 的最大值
        var peakRssMaxKb: Long = 0L,     // 所有调用中采样绝对峰值的最大值
        var lastDeltaKb: Long = 0L,
        var postCallRssKb: Long = 0L,    // 最近一次 afterRss
        var lowestPostCallRssKb: Long = Long.MAX_VALUE, // 观察到的 afterRss 最低点（基线）
        var leaked: Boolean = false,
    ) {
        fun averageDeltaKb(): Long = if (count == 0) 0L else totalDeltaKb / count
    }

    // 调用类型名 → 聚合统计（进程级累计，与 RSS 生命周期对齐）。
    private val byKind = ConcurrentHashMap<String, Stats>()

    // ---- 进程身份（惰性读取一次并缓存）----

    private val pid: Int by lazy {
        try {
            parsePid(File("/proc/self/status").readText())
        } catch (t: Throwable) {
            -1
        }
    }

    private val processName: String by lazy {
        try {
            File("/proc/self/cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .substringBefore('\u0000')
                .ifEmpty { parseProcessName(File("/proc/self/status").readText()) }
        } catch (t: Throwable) {
            ""
        }
    }

    /** 当前进程 VmRSS（kB）。读失败返回 0（安全侧）。 */
    fun rssKb(): Long = try {
        parseVmRssKb(File("/proc/self/status").readText())
    } catch (t: Throwable) {
        0L
    }

    /** 一次性读取 /proc/self/status 得到当前内存快照（仅内存字段，无 kind/runId 等）。 */
    private fun currentSnapshot(): ProbeRecord = try {
        val text = File("/proc/self/status").readText()
        ProbeRecord(
            beforeRss = parseVmRssKb(text),
            afterRss = parseVmRssKb(text),
            peakRss = parseVmRssKb(text),
            vmHwm = parseVmHwmKb(text),
            vmData = parseVmDataKb(text),
            vmPeak = parseVmPeakKb(text),
        )
    } catch (t: Throwable) {
        ProbeRecord()
    }

    /**
     * 一次手动峰值采样会话的句柄。围绕一次 provider 调用 start → stop，内部由 daemon
     * 线程每 [intervalMs] 读一次 /proc/self/status 的 VmRSS 并取最大。零副作用：
     * 不吃磅调用路径，daemon 读失败即停，不 inflate RSS。
     */
    class SampleHandle internal constructor(
        private val stop: AtomicBoolean,
        private val peakRef: java.util.concurrent.atomic.AtomicLong,
    ) {
        /** 停止采样并返回峰值（kB）；采样不可用返回 -1。 */
        fun stop(): Long {
            stop.set(true)
            return peakRef.get()
        }
    }

    /**
     * 手动峰值采样的一次会话（用于 suspend 调用点）：调用方 start 后执行 provider 调用，
     * finally 里 stop() 取峰值。
     */
    fun startPeakSampling(intervalMs: Long = PEAK_SAMPLE_MS): SampleHandle {
        val stop = AtomicBoolean(false)
        val peak = java.util.concurrent.atomic.AtomicLong(-1L)
        val sampler = Thread {
            var localPeak = -1L
            while (!stop.get()) {
                try {
                    val r = rssKb()
                    if (r > localPeak) localPeak = r
                } catch (t: Throwable) {
                    // 读失败放弃本轮采样，不打断主流程
                }
                if (localPeak > peak.get()) peak.set(localPeak)
                if (!stop.get()) {
                    try {
                        Thread.sleep(intervalMs)
                    } catch (t: InterruptedException) {
                        // 忽略
                    }
                }
            }
        }
        sampler.isDaemon = true
        sampler.name = "provider-rss-peak-sampler"
        sampler.start()
        return SampleHandle(stop, peak)
    }

    /**
     * 同步 block 形式的峰值采样（与手动 start/stop 等价）。返回 [block] 执行期间的采样峰值；
     * 若 [block] 抛异常则原样抛出（不吞，保证调用路径语义不变），采样线程仍会停止。
     */
    fun samplePeakRssDuring(intervalMs: Long = PEAK_SAMPLE_MS, block: () -> Unit): Long {
        val handle = startPeakSampling(intervalMs)
        var peak = -1L
        try {
            block()
            peak = handle.stop()
        } catch (t: Throwable) {
            handle.stop() // 确保 sampler 停止
            throw t
        }
        return peak
    }

    // ---- vm 解析（纯函数，可 JVM 单测）----

    /** 解析 /proc/self/status 文本中某 key 的 kB 值。无该行返回 0。 */
    internal fun parseVmKb(statusText: String, key: String): Long {
        val line = statusText.lineSequence().firstOrNull { it.startsWith("$key:") } ?: return 0L
        return line.substringAfter(":").trim().substringBefore(" kB").trim().toLongOrNull() ?: 0L
    }

    /** 从 /proc/self/status 文本解析 VmRSS（kB）。 */
    internal fun parseVmRssKb(statusText: String): Long = parseVmKb(statusText, "VmRSS")

    /** 从 /proc/self/status 文本解析 VmHWM（kB）。 */
    internal fun parseVmHwmKb(statusText: String): Long = parseVmKb(statusText, "VmHWM")

    /** 从 /proc/self/status 文本解析 VmData（kB）。 */
    internal fun parseVmDataKb(statusText: String): Long = parseVmKb(statusText, "VmData")

    /** 从 /proc/self/status 文本解析 VmPeak（kB）。 */
    internal fun parseVmPeakKb(statusText: String): Long = parseVmKb(statusText, "VmPeak")

    /** 从 /proc/self/status 文本解析 Pid（int）。无则 -1。 */
    internal fun parsePid(statusText: String): Int =
        statusText.lineSequence().firstOrNull { it.startsWith("Pid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.toIntOrNull()
            ?: -1

    /** 从 /proc/self/status 文本解析 Name（进程 comm）。无则空串。 */
    internal fun parseProcessName(statusText: String): String =
        statusText.lineSequence().firstOrNull { it.startsWith("Name:") }
            ?.substringAfter(":")
            ?.trim()
            .orEmpty()

    // ---- 记录与聚合 ----

    /** 兼容 v1 的便捷入口：只给 kind + before + after，其余字段走当前进程快照默认。 */
    fun record(kind: String, beforeKb: Long, afterKb: Long) {
        val snap = currentSnapshot()
        record(
            ProbeRecord(
                kind = kind,
                beforeRss = beforeKb,
                afterRss = afterKb,
                peakRss = -1L,
                vmHwm = snap.vmHwm,
                vmData = snap.vmData,
                vmPeak = snap.vmPeak,
            )
        )
    }

    /**
     * 记录一条 provider 调用观测。kind 用于区分调用类型（"sendMessage" / "streamMessage:<provider>"）。
     * 零副作用：所有聚合与日志都无法影响调用结果。
     */
    fun record(record: ProbeRecord) {
        if (record.kind.isEmpty()) return
        val st = byKind.computeIfAbsent(record.kind) { Stats() }
        // [fix/audit-b20 / T5-L4] record() is entered from LLMProvider default
        // methods, and parallel sub-agents (max_parallel=4) can hit the same
        // Stats at once. ConcurrentHashMap guards the map, not the value's
        // fields, so the `+=` / min / max sequences lost updates.
        val deltaKb = record.afterRss - record.beforeRss
        val peakDelta = record.peakDeltaKb()
        synchronized(st) {
            st.count += 1
            st.totalDeltaKb += deltaKb
            st.lastDeltaKb = deltaKb
            st.postCallRssKb = record.afterRss
            st.lowestPostCallRssKb = minOf(st.lowestPostCallRssKb, record.afterRss)
            if (peakDelta > st.peakDeltaMaxKb) st.peakDeltaMaxKb = peakDelta
            if (record.peakRss > st.peakRssMaxKb) st.peakRssMaxKb = record.peakRss
        }

        Log.i(
            TAG,
            "[provider-rss] ${record.brief()} " +
                "cum=${st.totalDeltaKb / 1024}MB(×${st.count}, avg=${st.averageDeltaKb() / 1024}MB " +
                "peakΔmax=${st.peakDeltaMaxKb / 1024}MB postRss=${st.postCallRssKb / 1024}MB)"
        )

        val condition = decideLeakSuspect(st, peakDelta, record)
        if (!st.leaked && condition != null) {
            st.leaked = true
            Log.w(
                TAG,
                "[provider-rss] LEAK-SUSPECT ${record.kind} (${condition}) " +
                    "peakΔmax=${st.peakDeltaMaxKb / 1024}MB totalDelta=${st.totalDeltaKb / 1024}MB " +
                    "over ${st.count} calls postRss=${st.postCallRssKb / 1024}MB — 候选聊天路径 native OOM 源头"
            )
        }
    }

    /**
     * 双条件 LEAK-SUSPECT 判定（纯逻辑，可 JVM 单测）：
     * - A「峰值持续偏高」：本调用 peakDelta ≥ [PEAK_SUSPECT_KB]（单次峰值暴涨 512MB）。
     * - B「多轮后 post-idle RSS 不回落」：已足量调用（≥ [POST_IDLE_MIN_CALLS]）后，
     *   afterRss 相对最低点累计漂移 ≥ [POST_IDLE_DRIFT_KB]，且当前 afterRss 未回落到基线附近
     *   （相对最低点仍漂移 ≥ 阈值的一半）——说明内存只涨不落。
     * 两者满足其一即判可疑；不再只靠累计 cum。
     */
    private fun decideLeakSuspect(
        st: Stats,
        peakDelta: Long,
        record: ProbeRecord,
    ): String? {
        // 条件 A：单次峰值暴涨
        if (peakDelta >= PEAK_SUSPECT_KB) {
            return "PEAK-DELTA-${peakDelta / 1024}MB>=${PEAK_SUSPECT_KB / 1024}MB"
        }
        // 条件 B：多轮后 post-idle 不回落
        if (st.count >= POST_IDLE_MIN_CALLS && st.lowestPostCallRssKb != Long.MAX_VALUE) {
            val drift = record.afterRss - st.lowestPostCallRssKb
            val stillOffBaseline = drift >= POST_IDLE_DRIFT_KB / 2L
            if (drift >= POST_IDLE_DRIFT_KB && stillOffBaseline) {
                return "POST-IDLE-DRIFT-${drift / 1024}MB>=${POST_IDLE_DRIFT_KB / 1024}MB"
            }
        }
        // 兜底：累计 cum 超阈值（兼容旧语义）
        if (st.totalDeltaKb >= LEAK_SUSPECT_CUM_KB) {
            return "CUM-${st.totalDeltaKb / 1024}MB>=${LEAK_SUSPECT_CUM_KB / 1024}MB"
        }
        return null
    }

    /** 打印全量聚合汇总。返回汇总文本。 */
    fun summary(): String {
        if (byKind.isEmpty()) {
            Log.i(TAG, "[provider-rss] summary: (no provider calls instrumented)")
            return "(no data)"
        }
        val sb = StringBuilder("provider-rss summary (pid=$pid process=$processName):\n")
        byKind.entries
            .sortedByDescending { it.value.totalDeltaKb }
            .forEach { (kind, st) ->
                val baseline = if (st.lowestPostCallRssKb == Long.MAX_VALUE) "?" else (st.lowestPostCallRssKb / 1024).toString() + "MB"
                sb.append(
                    "  $kind: cum=${st.totalDeltaKb / 1024}MB count=${st.count} " +
                        "avg=${st.averageDeltaKb() / 1024}MB peakΔmax=${st.peakDeltaMaxKb / 1024}MB " +
                        "peakAbs=${if (st.peakRssMaxKb >= 0) st.peakRssMaxKb / 1024 else 0}MB " +
                        "postRss=${st.postCallRssKb / 1024}MB lowest=$baseline" +
                        (if (st.leaked) " [LEAK-SUSPECT]" else "")
                ).append('\n')
            }
        Log.i(TAG, "[provider-rss] $sb")
        return sb.toString()
    }

    /** 压测自检用：重置聚合（生产不调用）。 */
    fun reset() {
        byKind.clear()
    }
}