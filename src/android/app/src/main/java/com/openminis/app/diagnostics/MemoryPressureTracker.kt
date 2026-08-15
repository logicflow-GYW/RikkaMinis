package com.openminis.app.diagnostics

import android.os.Debug
import com.openminis.app.logging.AppLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * T9-performance-baseline + T-android-native-oom-fix: process-level memory
 * pressure tracking.
 *
 * Monitors RSS from /proc/self/status, categorizes pressure into NORMAL /
 * ELEVATED / CRITICAL bands, and exposes a listener pattern for the
 * MemoryPressureGate (service/MemoryPressureGate.kt) to react.
 *
 * This is the **diagnostics-layer observer** — it reads the metrics and
 * emits events. The gate/reaction layer lives in the service package.
 *
 * Bands (derived from 08-15 OOM postmortem, Redmi Note 12 Turbo):
 *   NORMAL    < 280 MB RSS  — safe zone
 *   ELEVATED  280-319 MB    — approaching limit, consider throttling
 *   CRITICAL  >= 320 MB     — near OOM inflection point, must act
 *
 * Thread-safety: reads are lock-free (AtomicReference for listener list);
 * [check] is designed to be called from a single polling coroutine.
 */
object MemoryPressureTracker {

    private const val TAG = "MemPressure"
    private const val NORMAL_THRESHOLD_MB = 280
    private const val CRITICAL_THRESHOLD_MB = 320

    /** Current pressure level. */
    enum class Level { NORMAL, ELEVATED, CRITICAL }

    /** A snapshot of memory metrics at a point in time. */
    data class Snapshot(
        val rssMb: Double,
        val javaHeapMb: Long,
        val nativeHeapMb: Long,
        val threadCount: Int,
        val level: Level,
        val timestampMs: Long,
    )

    private var lastLevel = Level.NORMAL
    private val listeners = AtomicReference(listOf<(Level, Snapshot) -> Unit>())
    private val peakRss = AtomicLong(0L)
    private var lastSnapshot = Snapshot(0.0, 0, 0, 0, Level.NORMAL, 0L)

    // --- listener API ---

    /** Register a pressure listener. Returns a Runnable that removes it. */
    fun addListener(l: (Level, Snapshot) -> Unit): Runnable {
        listeners.updateAndGet { it + l }
        return Runnable { listeners.updateAndGet { it - l } }
    }

    // --- polling API ---

    /**
     * Read the current memory snapshot and classify pressure level.
     * Returns the [Snapshot] and fires listeners if level changed.
     * Call from a periodic coroutine (e.g. every 5s when a run is active).
     */
    fun check(): Snapshot {
        val rss = readRssMB()
        val javaHeap = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        val threads = readThreadCount()
        val level = classify(rss)
        val snap = Snapshot(rss, javaHeap, nativeHeap, threads, level, System.currentTimeMillis())

        if (rss.toLong() > peakRss.get()) peakRss.set(rss.toLong())
        lastSnapshot = snap

        if (level != lastLevel) {
            lastLevel = level
            listeners.get().forEach { it(level, snap) }
            AppLogger.info(
                TAG,
                "pressure change: $level (rss=${rss}MB java=${javaHeap}MB native=${nativeHeap}MB threads=$threads)",
            )
        }

        return snap
    }

    /** Get the peak RSS seen since process start. */
    fun peakRssMB(): Long = peakRss.get()

    /** Get the last snapshot (fast, no I/O). */
    fun lastSnapshot(): Snapshot = lastSnapshot

    // --- internal ---

    private fun classify(rssMb: Double): Level = when {
        rssMb >= CRITICAL_THRESHOLD_MB -> Level.CRITICAL
        rssMb >= NORMAL_THRESHOLD_MB -> Level.ELEVATED
        else -> Level.NORMAL
    }

    private fun readRssMB(): Double = try_catch({
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
    }, 0.0)

    private fun readThreadCount(): Int = try_catch({
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
    }, -1)

    private inline fun <T> try_catch(block: () -> T, fallback: T): T {
        return try { block() } catch (_: Exception) { fallback }
    }
}