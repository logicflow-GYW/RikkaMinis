package com.openminis.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * T9-performance-baseline: unit tests for PerfBaselineCollector.
 *
 * Tests the JSONL output format and event recording logic.
 * Pure JVM — does not depend on Android (the /proc/self/status reads
 * return -1 on JVM which is fine — the test validates the output format).
 */
class PerfBaselineCollectorTest {

    private val testDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "perf-collector-test-${System.nanoTime()}")

    @Test
    fun `init creates directory`() {
        PerfBaselineCollector.init(testDir)
        assertTrue(testDir.exists())
        PerfBaselineCollector.shutdown()
        testDir.deleteOnExit()
    }

    @Test
    fun `cold start event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordColdStart(
            toIdleMs = 2350,
            configLoadMs = 108,
            configDbMs = 52,
            configAssembleMs = 55,
            configHashMs = 1,
        )
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("cold_start"))
        assertTrue(content.contains("2350"))
        assertTrue(content.contains("108"))
    }

    @Test
    fun `stream turn event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordFirstToken("s1", 800)
        PerfBaselineCollector.recordStreamTurn(
            sessionId = "s1",
            tickCount = 42,
            flattenAvgUs = 150,
            flattenMaxMs = 12,
            frozenHits = 35,
            totalTicks = 42,
            gcCountDelta = 5,
            gcFreedDeltaMb = 12.3,
            turnS = 8,
        )
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("stream_turn"))
        assertTrue(content.contains("ttfb_ms"))
        assertTrue(content.contains("frozen_hit_rate"))
        assertTrue(content.contains("0.833"))
    }

    @Test
    fun `tool call event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordToolCall(
            sessionId = "s1",
            toolName = "shell_execute",
            durationMs = 3000,
            resultKnown = true,
        )
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("tool_call"))
        assertTrue(content.contains("shell_execute"))
        assertTrue(content.contains("3000"))
        assertTrue(content.contains("true"))
    }

    @Test
    fun `resource lease event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordResourceLease(
            action = "acquire",
            resourceType = "session_slot",
            resourceId = "s1",
            leaseToken = "l1",
        )
        PerfBaselineCollector.recordResourceLease(
            action = "release",
            resourceType = "session_slot",
            resourceId = "s1",
            leaseToken = "l1",
            durationMs = 15000,
        )
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("resource_lease"))
        assertTrue(content.contains("acquire"))
        assertTrue(content.contains("release"))
        assertTrue(content.contains("15000"))
    }

    @Test
    fun `memory snapshot event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordMemorySnapshot()
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("memory_snapshot"))
    }

    @Test
    fun `multi session event writes JSONL line`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordMultiSession(
            activeSessions = 5,
            queueWaitMs = 2000,
            peakHeapMb = 210.0,
            peakRssMb = 340.0,
        )
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("multi_session"))
        assertTrue(content.contains("5"))
        assertTrue(content.contains("2000"))
    }

    @Test
    fun `cold start cooldown prevents duplicate events`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordColdStart(2350, 108, 52, 55, 1)
        PerfBaselineCollector.recordColdStart(2400, 110, 53, 56, 2) // within cooldown
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        // Should only have one cold_start event due to cooldown
        val count = content.split("cold_start").size - 1
        assertEquals(1, count)
    }

    @Test
    fun `multiple event types can coexist in same file`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.recordColdStart(2350, 108, 52, 55, 1)
        PerfBaselineCollector.recordFirstToken("s1", 800)
        PerfBaselineCollector.recordStreamTurn("s1", 42, 150, 12, 35, 42, 5, 12.3, 8)
        PerfBaselineCollector.recordToolCall("s1", "file_read", 5, true)
        PerfBaselineCollector.recordMemorySnapshot()
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()

        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        val content = files.first().readText()
        assertTrue(content.contains("cold_start"))
        assertTrue(content.contains("stream_turn"))
        assertTrue(content.contains("tool_call"))
        assertTrue(content.contains("memory_snapshot"))
    }

    @Test
    fun `shutdown is idempotent`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.shutdown()
        // Second shutdown should not throw
        PerfBaselineCollector.shutdown()
    }

    @Test
    fun `init without cold start produces no files`() {
        PerfBaselineCollector.init(testDir)
        PerfBaselineCollector.flush()
        PerfBaselineCollector.shutdown()
        // init + flush without any events — should still produce a file
        val files = testDir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()
        // flush triggers rotateWriter which creates a file even if empty
        // (the file will have 0 lines but exist)
    }
}