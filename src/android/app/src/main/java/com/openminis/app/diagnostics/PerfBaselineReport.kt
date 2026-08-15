package com.openminis.app.diagnostics

import com.openminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * T9-performance-baseline: aggregate baseline JSONL files into
 * P50/P95/P99 summary reports.
 *
 * Reads one or more [PerfBaselineCollector] JSONL files and produces:
 *  - Per-metric summary (count / p50 / p95 / p99 / max / mean)
 *  - Per-event-type breakdown
 *  - Comparison with a previous baseline (delta report)
 *
 * All methods are pure JVM — no Android dependency — so they can be
 * tested in unit tests.
 */
object PerfBaselineReport {

    private const val TAG = "PerfBaselineReport"

    // ================================================================
    //  Data model
    // ================================================================

    data class MetricSummary(
        val metricName: String,
        val count: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        val mean: Double,
    ) {
        /** Human-readable one-liner. */
        fun toLine(): String = String.format(
            Locale.US,
            "  %-30s count=%4d  p50=%8.1f  p95=%8.1f  p99=%8.1f  max=%8.1f  mean=%8.1f",
            metricName, count, p50, p95, p99, max, mean,
        )
    }

    data class EventTypeSummary(
        val eventType: String,
        val count: Int,
        val metrics: List<MetricSummary>,
    )

    data class BaselineReport(
        val title: String,
        val generatedAt: String,
        val sourceFiles: List<String>,
        val eventTypes: List<EventTypeSummary>,
        val totalEvents: Int,
    ) {
        fun toMarkdown(): String = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Generated: $generatedAt")
            appendLine("Source files: ${sourceFiles.size}")
            sourceFiles.forEach { appendLine("  - $it") }
            appendLine("Total events: $totalEvents")
            appendLine()
            for (et in eventTypes) {
                appendLine("## ${et.eventType} (${et.count} events)")
                appendLine()
                for (m in et.metrics) {
                    appendLine(m.toLine())
                }
                appendLine()
            }
        }
    }

    // ================================================================
    //  Aggregation
    // ================================================================

    /** Parse a single JSONL line into a map of metric name -> value. */
    private fun parseMetrics(line: String): Map<String, Double> {
        return try {
            val obj = JSONObject(line)
            val type = obj.optString("type", "unknown")
            val metrics = mutableMapOf<String, Double>()

            // Common metrics across all event types
            val numericFields = when (type) {
                "cold_start" -> listOf(
                    "to_idle_ms", "config_load_ms", "config_db_ms",
                    "config_assemble_ms", "config_hash_ms",
                    "java_heap_mb", "native_heap_mb", "rss_mb",
                )
                "stream_turn" -> listOf(
                    "ttfb_ms", "tick_count", "flatten_avg_us",
                    "flatten_max_ms", "gc_count_delta", "gc_freed_mb",
                    "turn_s", "java_heap_mb", "native_heap_mb", "rss_mb",
                    "frozen_hit_rate",
                )
                "tool_call" -> listOf(
                    "tool_duration_ms", "shell_rss_mb",
                    "java_heap_mb", "native_heap_mb",
                )
                "memory_snapshot" -> listOf(
                    "java_heap_mb", "native_heap_mb", "rss_mb", "thread_count",
                )
                "resource_lease" -> listOf(
                    "duration_ms",
                )
                "multi_session" -> listOf(
                    "active_sessions", "queue_wait_ms",
                    "peak_heap_mb", "peak_rss_mb",
                )
                else -> emptyList()
            }

            for (field in numericFields) {
                val v = obj.optDouble(field, Double.NaN)
                if (v.isFinite() && v >= 0) {
                    // Use type-qualified keys for multi-event reports
                    metrics["${type}.$field"] = v
                }
            }

            metrics
        } catch (_: Exception) { emptyMap() }
    }

    /** Aggregate a list of metric values into P50/P95/P99. */
    private fun summarize(values: List<Double>, metricName: String): MetricSummary {
        if (values.isEmpty()) return MetricSummary(metricName, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val sorted = values.sorted()
        val n = sorted.size
        val mean = sorted.sum() / n
        return MetricSummary(
            metricName = metricName,
            count = n,
            p50 = percentile(sorted, 50.0),
            p95 = percentile(sorted, 95.0),
            p99 = percentile(sorted, 99.0),
            max = sorted.last(),
            mean = mean,
        )
    }

    /** Linear-interpolated percentile. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lower = sorted[rank.toInt()]
        val upper = sorted[if (rank.toInt() + 1 < sorted.size) rank.toInt() + 1 else sorted.size - 1]
        val frac = rank - rank.toInt()
        return lower + (upper - lower) * frac
    }

    // ================================================================
    //  Public API
    // ================================================================

    /**
     * Aggregate one or more JSONL baseline files into a full report.
     */
    fun aggregate(files: List<File>, title: String = "Performance Baseline Report"): BaselineReport {
        val allMetrics = mutableMapOf<String, MutableList<Double>>()
        val eventCounts = mutableMapOf<String, Int>()
        var totalEvents = 0
        val sourceNames = files.map { it.name }

        for (file in files) {
            if (!file.exists()) continue
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    totalEvents++
                    val metrics = parseMetrics(line)
                    for ((key, value) in metrics) {
                        allMetrics.computeIfAbsent(key) { mutableListOf() }.add(value)
                    }
                    // Track event type counts
                    try {
                        val type = JSONObject(line).optString("type", "unknown")
                        eventCounts[type] = (eventCounts[type] ?: 0) + 1
                    } catch (_: Exception) { /* skip */ }
                }
            } catch (_: Exception) {
                AppLogger.warn(TAG, "Failed to read ${file.name}: ${_}")
            }
        }

        // Group metrics by event type
        val eventTypeGroups = mutableMapOf<String, MutableList<MetricSummary>>()
        for ((key, values) in allMetrics) {
            val dotIdx = key.indexOf('.')
            val eventType = if (dotIdx > 0) key.substring(0, dotIdx) else "unknown"
            val metricName = if (dotIdx > 0) key.substring(dotIdx + 1) else key
            val summary = summarize(values, metricName)
            eventTypeGroups.computeIfAbsent(eventType) { mutableListOf() }.add(summary)
        }

        val eventTypes = eventTypeGroups.map { (type, metrics) ->
            EventTypeSummary(
                eventType = type,
                count = eventCounts[type] ?: metrics.size,
                metrics = metrics.sortedBy { it.metricName },
            )
        }.sortedBy { it.eventType }

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return BaselineReport(
            title = title,
            generatedAt = dateFmt.format(Date()),
            sourceFiles = sourceNames,
            eventTypes = eventTypes,
            totalEvents = totalEvents,
        )
    }

    /**
     * Compare two baselines and produce a delta report.
     * Returns a markdown string with delta columns.
     */
    fun compare(before: BaselineReport, after: BaselineReport, title: String = "Baseline Delta"): String {
        val beforeMap = before.eventTypes.flatMap { et ->
            et.metrics.map { "${et.eventType}.${it.metricName}" to it }
        }.toMap()
        val afterMap = after.eventTypes.flatMap { et ->
            et.metrics.map { "${et.eventType}.${it.metricName}" to it }
        }.toMap()

        val allKeys = (beforeMap.keys + afterMap.keys).sorted()

        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Before: ${before.title} (${before.generatedAt})")
            appendLine("After:  ${after.title} (${after.generatedAt})")
            appendLine()
            appendLine("| Metric | Before p95 | After p95 | Delta % | Before p50 | After p50 | Delta % |")
            appendLine("|--------|-----------|----------|---------|-----------|----------|---------|")
            for (key in allKeys) {
                val b = beforeMap[key]
                val a = afterMap[key]
                val bP95 = b?.p95 ?: Double.NaN
                val aP95 = a?.p95 ?: Double.NaN
                val bP50 = b?.p50 ?: Double.NaN
                val aP50 = a?.p50 ?: Double.NaN
                val deltaP95 = if (bP95.isFinite() && bP95 > 0) ((aP95 - bP95) / bP95 * 100) else Double.NaN
                val deltaP50 = if (bP50.isFinite() && bP50 > 0) ((aP50 - bP50) / bP50 * 100) else Double.NaN
                val p95Str = if (aP95.isFinite()) String.format(Locale.US, "%.1f", aP95) else "-"
                val bP95Str = if (bP95.isFinite()) String.format(Locale.US, "%.1f", bP95) else "-"
                val p50Str = if (aP50.isFinite()) String.format(Locale.US, "%.1f", aP50) else "-"
                val bP50Str = if (bP50.isFinite()) String.format(Locale.US, "%.1f", bP50) else "-"
                val deltaP95Str = if (deltaP95.isFinite()) String.format(Locale.US, "%+.1f%%", deltaP95) else "-"
                val deltaP50Str = if (deltaP50.isFinite()) String.format(Locale.US, "%+.1f%%", deltaP50) else "-"
                appendLine("| $key | $bP95Str | $p95Str | $deltaP95Str | $bP50Str | $p50Str | $deltaP50Str |")
            }
        }
    }

    /** Save a report to a markdown file. */
    fun saveReport(report: BaselineReport, file: File) {
        file.writeText(report.toMarkdown())
        AppLogger.info(TAG, "Report saved to ${file.absolutePath}")
    }

    /** Save a comparison report to a markdown file. */
    fun saveComparison(markdown: String, file: File) {
        file.writeText(markdown)
        AppLogger.info(TAG, "Comparison saved to ${file.absolutePath}")
    }
}