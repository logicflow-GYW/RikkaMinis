package com.openminis.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T9-performance-baseline: unit tests for PerfBaselineReport aggregation logic.
 *
 * Pure JVM — no Android dependency — so these run in standard unit test task.
 */
class PerfBaselineReportTest {

    private val testDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "perf-baseline-test-${System.nanoTime()}")

    private fun writeTestFile(lines: List<String>, name: String = "test-baseline.jsonl"): File {
        testDir.mkdirs()
        val file = File(testDir, name)
        file.writeText(lines.joinToString("\n") + "\n")
        return file
    }

    @Test
    fun `empty file produces empty report`() {
        val file = writeTestFile(emptyList())
        val report = PerfBaselineReport.aggregate(listOf(file))
        assertEquals(0, report.totalEvents)
        assertTrue(report.eventTypes.isEmpty())
    }

    @Test
    fun `cold start events are parsed correctly`() {
        val lines = listOf(
            """{"type":"cold_start","collector_version":"1.0","ts_epoch_ms":1000,"to_idle_ms":2350,"config_load_ms":108,"config_db_ms":52,"config_assemble_ms":55,"config_hash_ms":1,"java_heap_mb":148,"native_heap_mb":64,"rss_mb":277.0}""",
            """{"type":"cold_start","collector_version":"1.0","ts_epoch_ms":2000,"to_idle_ms":2500,"config_load_ms":120,"config_db_ms":60,"config_assemble_ms":58,"config_hash_ms":2,"java_heap_mb":150,"native_heap_mb":68,"rss_mb":280.0}""",
            """{"type":"cold_start","collector_version":"1.0","ts_epoch_ms":3000,"to_idle_ms":3100,"config_load_ms":95,"config_db_ms":40,"config_assemble_ms":50,"config_hash_ms":5,"java_heap_mb":145,"native_heap_mb":62,"rss_mb":265.0}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Cold Start Test")

        assertEquals(3, report.totalEvents)
        assertEquals(1, report.eventTypes.size)
        assertEquals("cold_start", report.eventTypes[0].eventType)
        assertEquals(3, report.eventTypes[0].count)

        // Check to_idle_ms P50/P95/P99
        val idleMetric = report.eventTypes[0].metrics.find { it.metricName == "to_idle_ms" }
        assertNotNull(idleMetric)
        assertEquals(3, idleMetric!!.count)
        assertEquals(2500.0, idleMetric.p50, 0.01)
        assertEquals(3100.0, idleMetric.p95, 0.01)
        assertEquals(3100.0, idleMetric.p99, 0.01)
        assertEquals(3100.0, idleMetric.max, 0.01)
        assertEquals((2350.0 + 2500.0 + 3100.0) / 3.0, idleMetric.mean, 0.01)
    }

    @Test
    fun `stream turn events are parsed correctly`() {
        val lines = listOf(
            """{"type":"stream_turn","collector_version":"1.0","ts_epoch_ms":1000,"session_id":"s1","ttfb_ms":800,"tick_count":42,"flatten_avg_us":150,"flatten_max_ms":12,"frozen_hit_rate":0.85,"gc_count_delta":5,"gc_freed_mb":12.3,"turn_s":8,"java_heap_mb":150,"native_heap_mb":70,"rss_mb":285.0}""",
            """{"type":"stream_turn","collector_version":"1.0","ts_epoch_ms":2000,"session_id":"s2","ttfb_ms":1200,"tick_count":18,"flatten_avg_us":320,"flatten_max_ms":28,"frozen_hit_rate":0.6,"gc_count_delta":3,"gc_freed_mb":8.1,"turn_s":4,"java_heap_mb":155,"native_heap_mb":72,"rss_mb":290.0}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Stream Test")

        assertEquals(2, report.totalEvents)
        val streamType = report.eventTypes.find { it.eventType == "stream_turn" }
        assertNotNull(streamType)
        assertEquals(2, streamType!!.count)

        val ttfb = streamType.metrics.find { it.metricName == "ttfb_ms" }
        assertNotNull(ttfb)
        assertEquals(2, ttfb!!.count)
        assertEquals(1000.0, ttfb.p50, 0.01)
        assertEquals(1200.0, ttfb.p95, 0.01)
    }

    @Test
    fun `tool call events are parsed correctly`() {
        val lines = listOf(
            """{"type":"tool_call","tool_name":"shell_execute","tool_duration_ms":3000,"result_known":true,"shell_rss_mb":277.0,"java_heap_mb":150,"native_heap_mb":68}""",
            """{"type":"tool_call","tool_name":"file_read","tool_duration_ms":5,"result_known":true,"shell_rss_mb":275.0,"java_heap_mb":148,"native_heap_mb":65}""",
            """{"type":"tool_call","tool_name":"shell_execute","tool_duration_ms":15000,"result_known":false,"shell_rss_mb":310.0,"java_heap_mb":160,"native_heap_mb":80}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Tool Test")

        assertEquals(3, report.totalEvents)
        val toolType = report.eventTypes.find { it.eventType == "tool_call" }
        assertNotNull(toolType)
        assertEquals(3, toolType!!.count)

        val duration = toolType.metrics.find { it.metricName == "tool_duration_ms" }
        assertNotNull(duration)
        assertEquals(3, duration!!.count)
        assertEquals(3000.0, duration.p50, 0.01)
        assertEquals(15000.0, duration.p95, 0.01)
        assertEquals(15000.0, duration.max, 0.01)
    }

    @Test
    fun `multi session events are parsed correctly`() {
        val lines = listOf(
            """{"type":"multi_session","active_sessions":3,"queue_wait_ms":0,"peak_heap_mb":180.0,"peak_rss_mb":290.0}""",
            """{"type":"multi_session","active_sessions":5,"queue_wait_ms":2000,"peak_heap_mb":210.0,"peak_rss_mb":340.0}""",
            """{"type":"multi_session","active_sessions":4,"queue_wait_ms":500,"peak_heap_mb":195.0,"peak_rss_mb":310.0}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Multi-session Test")

        assertEquals(3, report.totalEvents)
        val msType = report.eventTypes.find { it.eventType == "multi_session" }
        assertNotNull(msType)

        val activeSessions = msType!!.metrics.find { it.metricName == "active_sessions" }
        assertNotNull(activeSessions)
        assertEquals(3, activeSessions!!.count)
        assertEquals(4.0, activeSessions.p50, 0.01)
        assertEquals(5.0, activeSessions.max, 0.01)
    }

    @Test
    fun `memory snapshot events are parsed correctly`() {
        val lines = listOf(
            """{"type":"memory_snapshot","java_heap_mb":150,"native_heap_mb":70,"rss_mb":277.0,"thread_count":85}""",
            """{"type":"memory_snapshot","java_heap_mb":180,"native_heap_mb":85,"rss_mb":320.0,"thread_count":120}""",
            """{"type":"memory_snapshot","java_heap_mb":145,"native_heap_mb":60,"rss_mb":250.0,"thread_count":72}""",
            """{"type":"memory_snapshot","java_heap_mb":200,"native_heap_mb":95,"rss_mb":360.0,"thread_count":150}""",
            """{"type":"memory_snapshot","java_heap_mb":160,"native_heap_mb":75,"rss_mb":290.0,"thread_count":95}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Memory Test")

        assertEquals(5, report.totalEvents)
        val memType = report.eventTypes.find { it.eventType == "memory_snapshot" }
        assertNotNull(memType)

        val rss = memType!!.metrics.find { it.metricName == "rss_mb" }
        assertNotNull(rss)
        assertEquals(5, rss!!.count)
        assertEquals(290.0, rss.p50, 0.01)
        // Sorted: 250, 277, 290, 320, 360
        // P95 at rank 4*0.95=3.8 → interp between 320 and 360
        val expectedP95 = 320.0 + (360.0 - 320.0) * 0.8
        assertEquals(expectedP95, rss.p95, 0.01)
        assertEquals(360.0, rss.max, 0.01)

        val threads = memType.metrics.find { it.metricName == "thread_count" }
        assertNotNull(threads)
        assertEquals(5, threads!!.count)
        // Sorted: 72, 85, 95, 120, 150
        assertEquals(95.0, threads.p50, 0.01)
        assertEquals(150.0, threads.max, 0.01)
    }

    @Test
    fun `multiple files are aggregated together`() {
        val file1 = writeTestFile(
            listOf("""{"type":"cold_start","to_idle_ms":2000,"config_load_ms":100,"java_heap_mb":150,"native_heap_mb":65,"rss_mb":270.0}"""),
            "file1.jsonl",
        )
        val file2 = writeTestFile(
            listOf("""{"type":"cold_start","to_idle_ms":3000,"config_load_ms":120,"java_heap_mb":160,"native_heap_mb":70,"rss_mb":290.0}"""),
            "file2.jsonl",
        )
        val report = PerfBaselineReport.aggregate(listOf(file1, file2), "Multi-file Test")
        assertEquals(2, report.totalEvents)
        assertEquals(2, report.sourceFiles.size)
    }

    @Test
    fun `compare produces delta table`() {
        val beforeLines = listOf(
            """{"type":"cold_start","to_idle_ms":2000,"config_load_ms":100,"java_heap_mb":150,"native_heap_mb":65,"rss_mb":270.0}""",
            """{"type":"cold_start","to_idle_ms":2500,"config_load_ms":110,"java_heap_mb":155,"native_heap_mb":68,"rss_mb":280.0}""",
            """{"type":"cold_start","to_idle_ms":3000,"config_load_ms":120,"java_heap_mb":160,"native_heap_mb":70,"rss_mb":290.0}""",
        )
        val afterLines = listOf(
            """{"type":"cold_start","to_idle_ms":2200,"config_load_ms":105,"java_heap_mb":155,"native_heap_mb":66,"rss_mb":275.0}""",
            """{"type":"cold_start","to_idle_ms":2600,"config_load_ms":112,"java_heap_mb":158,"native_heap_mb":69,"rss_mb":285.0}""",
            """{"type":"cold_start","to_idle_ms":3100,"config_load_ms":118,"java_heap_mb":162,"native_heap_mb":72,"rss_mb":295.0}""",
            """{"type":"cold_start","to_idle_ms":3500,"config_load_ms":130,"java_heap_mb":165,"native_heap_mb":75,"rss_mb":310.0}""",
        )
        val beforeFile = writeTestFile(beforeLines, "before.jsonl")
        val afterFile = writeTestFile(afterLines, "after.jsonl")

        val before = PerfBaselineReport.aggregate(listOf(beforeFile), "Before")
        val after = PerfBaselineReport.aggregate(listOf(afterFile), "After")
        val delta = PerfBaselineReport.compare(before, after, "Delta Test")

        assertTrue(delta.contains("cold_start.to_idle_ms"))
        assertTrue(delta.contains("Before"))
        assertTrue(delta.contains("After"))
        assertTrue(delta.contains("Delta %"))
    }

    @Test
    fun `report to markdown produces valid markdown`() {
        val lines = listOf(
            """{"type":"cold_start","to_idle_ms":2500,"config_load_ms":108,"java_heap_mb":150,"native_heap_mb":65,"rss_mb":277.0}""",
            """{"type":"stream_turn","ttfb_ms":800,"tick_count":42,"flatten_avg_us":150,"flatten_max_ms":12,"frozen_hit_rate":0.85,"gc_count_delta":5,"gc_freed_mb":12.3,"turn_s":8,"java_heap_mb":150,"native_heap_mb":70,"rss_mb":285.0}""",
        )
        val file = writeTestFile(lines)
        val report = PerfBaselineReport.aggregate(listOf(file), "Markdown Test")
        val md = report.toMarkdown()

        assertTrue(md.startsWith("#"))
        assertTrue(md.contains("cold_start"))
        assertTrue(md.contains("stream_turn"))
        assertTrue(md.contains("p50"))
        assertTrue(md.contains("p95"))
    }
}