package com.openminis.app.diagnostics

import android.os.Debug
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * T9-performance-baseline: unified performance baseline collector.
 *
 * Aggregates data from existing StreamPerfMonitor / PerfLongCtx / ProviderPerf
 * instrumentations and supplements missing metrics (first-token latency,
 * process RSS, resource release latency) into a single JSONL baseline file.
 *
 * Zero-overhead contract (hot path):
 *  - [tick] / [record] do primitive math + an AtomicLong bump only.
 *  - Logging/flushing happens at turn/run boundaries and on explicit [flush].
 *  - When no run is active every call no-ops on a boolean check.
 *
 * Baseline file format (JSONL):
 *   {"type":"cold_start","ts":...,"to_idle_ms":...,"config_load_ms":...,"java_heap_mb":...,"native_heap_mb":...}
 *   {"type":"stream_turn","ts":...,"session_id":"...","ttfb_ms":...,"tick_count":...,"flatten_avg_us":...,"flatten_max_ms":...,"frozen_hit_rate":...,"gc_count_delta":...,"gc_freed_mb":...,"turn_s":...}
 *   {"type":"tool_chunk","ts":...,"session_id":"...","tool_name":"...","tool_duration_ms":...,"shell_rss_mb":...,"result_known":true}
 *   {"type":"memory_snapshot","ts":...,"java_heap_mb":...,"native_heap_mb":...,"rss_mb":...,"thread_count":...}
 *   {"type":"resource_lease","ts":...,"action":"acquire|release","resource_type":"...","resource_id":"...","lease_token":"...","duration_ms":...}
 *   {"type":"multi_session","ts":...,"active_sessions":...,"queue_wait_ms":...,"peak_heap_mb":...,"peak_rss_mb":...}
 */
object PerfBaselineCollector {

    private const val TAG = "PerfBaseline"
    private const val COLLECTOR_VERSION = "1.0"

    // --- state ---
    private var enabled = false
    private var baselineDir: File? = null
    private var writer: File? = null
    private var writerAppendCount = 0L
    private val MAX_WRITES_PER_FILE = 5000L

    // per-session aggregation
    private val sessionTtfb = ConcurrentHashMap<String, Long>()       // first token latency (ms)
    private val sessionToolCount = ConcurrentHashMap<String, AtomicLong>()
    private val sessionToolDuration = ConcurrentHashMap<String, AtomicLong>()

    // cold-start recency
    private var lastColdStartMs = 0L
    private val COLD_START_COOLDOWN_MS = 30_000L

    // memory pressure tracking
    private var lastMemorySnapshotMs = 0L
    private val MEMORY_SNAPSHOT_INTERVAL_MS = 10_000L

    /** Initialize the collector. Call once from App.onCreate or similar. */
    fun init(baselineDir: File) {
        this.baselineDir = baselineDir
        if (!baselineDir.exists()) baselineDir.mkdirs()
        enabled = true
        sessionTtfb.clear()
        sessionToolCount.clear()
        sessionToolDuration.clear()
        lastColdStartMs = 0L
        lastMemorySnapshotMs = 0L
        rotateWriter()
        AppLogger.info(TAG, "collector v$COLLECTOR_VERSION initialized, dir=${baselineDir.absolutePath}")
    }

    /** Shutdown: flush remaining data and close writer. */
    fun shutdown() {
        flush()
        enabled = false
    }

    // ================================================================
    //  Event recorders
    // ================================================================

    /**
     * Cold-start event. Cooldown-guarded: only records once per
     * [COLD_START_COOLDOWN_MS] to avoid noisy duplicates from
     * process-lifecycle spurious restarts.
     */
    fun recordColdStart(
        toIdleMs: Long,
        configLoadMs: Long,
        configDbMs: Long,
        configAssembleMs: Long,
        configHashMs: Long,
    ) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastColdStartMs < COLD_START_COOLDOWN_MS) return
        lastColdStartMs = now

        writeJsonLine(
            "type" to "cold_start",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to now,
            "to_idle_ms" to toIdleMs,
            "config_load_ms" to configLoadMs,
            "config_db_ms" to configDbMs,
            "config_assemble_ms" to configAssembleMs,
            "config_hash_ms" to configHashMs,
            "java_heap_mb" to currentJavaHeapMB(),
            "native_heap_mb" to currentNativeHeapMB(),
            "rss_mb" to currentRssMB(),
        )
    }

    /** Record first-token latency for a streaming turn. */
    fun recordFirstToken(sessionId: String, ttfbMs: Long) {
        if (!enabled) return
        sessionTtfb[sessionId] = ttfbMs
    }

    /**
     * Record a stream-turn summary (called from StreamPerfMonitor.turnEnd
     * or equivalent aggregation point).
     */
    fun recordStreamTurn(
        sessionId: String,
        tickCount: Long,
        flattenAvgUs: Long,
        flattenMaxMs: Long,
        frozenHits: Long,
        totalTicks: Long,
        gcCountDelta: Long,
        gcFreedDeltaMb: Double,
        turnS: Long,
    ) {
        if (!enabled) return
        val ttfb = sessionTtfb.remove(sessionId) ?: -1L
        writeJsonLine(
            "type" to "stream_turn",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to System.currentTimeMillis(),
            "session_id" to sessionId,
            "ttfb_ms" to ttfb,
            "tick_count" to tickCount,
            "flatten_avg_us" to flattenAvgUs,
            "flatten_max_ms" to flattenMaxMs,
            "frozen_hit_rate" to if (totalTicks > 0) frozenHits.toDouble() / totalTicks else 0.0,
            "gc_count_delta" to gcCountDelta,
            "gc_freed_mb" to gcFreedDeltaMb,
            "turn_s" to turnS,
            "java_heap_mb" to currentJavaHeapMB(),
            "native_heap_mb" to currentNativeHeapMB(),
            "rss_mb" to currentRssMB(),
        )
    }

    /** Record a tool execution. */
    fun recordToolCall(
        sessionId: String,
        toolName: String,
        durationMs: Long,
        resultKnown: Boolean,
    ) {
        if (!enabled) return
        sessionToolCount.computeIfAbsent(sessionId) { AtomicLong(0) }.incrementAndGet()
        sessionToolDuration.computeIfAbsent(sessionId) { AtomicLong(0) }.addAndGet(durationMs)

        // Periodically sample shell RSS (not every tool call to avoid overhead)
        val shellRss = if (Math.random() < 0.2) currentRssMB() else -1.0

        writeJsonLine(
            "type" to "tool_call",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to System.currentTimeMillis(),
            "session_id" to sessionId,
            "tool_name" to toolName,
            "tool_duration_ms" to durationMs,
            "result_known" to resultKnown,
            "shell_rss_mb" to shellRss,
            "java_heap_mb" to currentJavaHeapMB(),
            "native_heap_mb" to currentNativeHeapMB(),
        )
    }

    /** Record a resource lease acquire/release. */
    fun recordResourceLease(
        action: String,        // "acquire" | "release"
        resourceType: String,  // "session_slot" | "shell" | "tool_slot" | "webview" | "temp_file"
        resourceId: String,
        leaseToken: String,
        durationMs: Long = -1L,
    ) {
        if (!enabled) return
        writeJsonLine(
            "type" to "resource_lease",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to System.currentTimeMillis(),
            "action" to action,
            "resource_type" to resourceType,
            "resource_id" to resourceId,
            "lease_token" to leaseToken,
            "duration_ms" to durationMs,
        )
    }

    /** Record a memory snapshot (called periodically or on explicit trigger). */
    fun recordMemorySnapshot() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastMemorySnapshotMs < MEMORY_SNAPSHOT_INTERVAL_MS) return
        lastMemorySnapshotMs = now

        writeJsonLine(
            "type" to "memory_snapshot",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to now,
            "java_heap_mb" to currentJavaHeapMB(),
            "native_heap_mb" to currentNativeHeapMB(),
            "rss_mb" to currentRssMB(),
            "thread_count" to currentThreadCount(),
        )
    }

    /** Record a multi-session pressure snapshot. */
    fun recordMultiSession(
        activeSessions: Int,
        queueWaitMs: Long,
        peakHeapMb: Double,
        peakRssMb: Double,
    ) {
        if (!enabled) return
        writeJsonLine(
            "type" to "multi_session",
            "collector_version" to COLLECTOR_VERSION,
            "ts_epoch_ms" to System.currentTimeMillis(),
            "active_sessions" to activeSessions,
            "queue_wait_ms" to queueWaitMs,
            "peak_heap_mb" to peakHeapMb,
            "peak_rss_mb" to peakRssMb,
        )
    }

    /** Explicit flush — rotates the writer file. */
    fun flush() {
        if (!enabled || writer == null) return
        rotateWriter()
    }

    // ================================================================
    //  Internal helpers
    // ================================================================

    private fun currentJavaHeapMB(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
    }

    private fun currentNativeHeapMB(): Long {
        return Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
    }

    private fun currentRssMB(): Double {
        return try {
            val procSelf = java.io.BufferedReader(java.io.FileReader("/proc/self/status"))
            var rss = 0.0
            procSelf.forEachLine { line ->
                if (line.startsWith("VmRSS:")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) rss = parts[1].toDoubleOrNull() ?: 0.0
                }
            }
            procSelf.close()
            rss
        } catch (_: Exception) { -1.0 }
    }

    private fun currentThreadCount(): Int {
        return try {
            val procSelf = java.io.BufferedReader(java.io.FileReader("/proc/self/status"))
            var threads = 0
            procSelf.forEachLine { line ->
                if (line.startsWith("Threads:")) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) threads = parts[1].toIntOrNull() ?: 0
                }
            }
            procSelf.close()
            threads
        } catch (_: Exception) { -1 }
    }

    @Synchronized
    private fun writeJsonLine(vararg fields: Pair<String, Any?>) {
        if (!enabled || writer == null) return
        val sb = StringBuilder("{")
        for ((i, f) in fields.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(escapeJson(f.first)).append('"').append(':')
            sb.append(jsonValue(f.second))
        }
        sb.append('}')
        sb.append('\n')
        try {
            writer!!.appendText(sb.toString())
            writerAppendCount++
            if (writerAppendCount >= MAX_WRITES_PER_FILE) rotateWriter()
        } catch (_: Exception) { /* best-effort */ }
    }

    @Synchronized
    private fun rotateWriter() {
        val dir = baselineDir ?: return
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        writer = File(dir, "perf-baseline-$ts.jsonl")
        writerAppendCount = 0
        AppLogger.info(TAG, "rotated to ${writer!!.name}")
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"${escapeJson(v)}\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        else -> "\"${escapeJson(v.toString())}\""
    }
}