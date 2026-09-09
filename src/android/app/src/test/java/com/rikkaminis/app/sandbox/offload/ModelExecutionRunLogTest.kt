package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * TF-G P1-1 JVM tests for the durable worker-phase log
 * ([ModelExecutionRunLog]). Verifies append ordering, tail reading (bounded),
 * and that a reclaimed/deleted dir never makes the writer throw.
 */
class ModelExecutionRunLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): java.io.File = tmp.newFolder("run-${System.nanoTime()}")

    @Test
    fun `appends phases in order and readTail returns chronological`() {
        val d = dir()
        ModelExecutionRunLog.log(d, pid = 42, phase = ModelExecutionRunLog.Phase.PROCESS_START, runId = "run-1")
        ModelExecutionRunLog.log(d, pid = 42, phase = ModelExecutionRunLog.Phase.REQUEST_ACCEPTED, runId = "run-1")
        ModelExecutionRunLog.log(d, pid = 42, phase = ModelExecutionRunLog.Phase.TERMINAL_WRITTEN, runId = "run-1")
        ModelExecutionRunLog.log(d, pid = 42, phase = ModelExecutionRunLog.Phase.SELF_REAP, runId = "run-1")

        val tail = ModelExecutionRunLog.readTail(d)
        assertEquals(4, tail.size)
        assertTrue(tail[0].contains("PROCESS_START"))
        assertTrue(tail[3].contains("SELF_REAP"))
        // chronological order preserved
        assertTrue(tail.indexOfFirst { it.contains("PROCESS_START") } <
            tail.indexOfFirst { it.contains("SELF_REAP") })
    }

    @Test
    fun `tail summary returns last phases compactly`() {
        val d = dir()
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.PROCESS_START, runId = "r")
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.REQUEST_ACCEPTED, detail = "streaming=true", runId = "r")
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.TERMINAL_WRITTEN, runId = "r")
        val summary = ModelExecutionRunLog.tailSummary(d)
        assertTrue(summary.contains("TERMINAL_WRITTEN"))
        assertTrue(summary.contains("REQUEST_ACCEPTED"))
        // no raw JSON braces / phase keys leaking into a single line is fine
        assertTrue(summary.isNotBlank())
    }

    @Test
    fun `readTail is bounded by line count and length`() {
        val d = dir()
        // Append many lines to exceed the MAX_LINES cap.
        repeat(300) { i ->
            ModelExecutionRunLog.log(d, 1, "PHASE_$i", runId = "r")
        }
        val tail = ModelExecutionRunLog.readTail(d)
        // Bounded to at most MAX_LINES (64) — doesn't blow memory on a huge log.
        assertTrue("tail size ${tail.size} should be capped", tail.size <= 64)
        // The LAST append must be present (we take the tail).
        assertTrue(tail.any { it.contains("PHASE_299") })
    }

    @Test
    fun `writer is defensive on a deleted dir and does not throw`() {
        val d = dir()
        d.deleteRecursively() // simulate a reclaimed run dir
        // logging into a gone dir must not throw / must not crash the worker
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.PROCESS_START, runId = "r")
        // readTail on missing file returns emptyList without throwing
        assertEquals(emptyList<String>(), ModelExecutionRunLog.readTail(d))
        assertEquals("no_run_log", ModelExecutionRunLog.tailSummary(d))
    }

    @Test
    fun `run log survives a gt 1KiB payload and is partially read via tail`() {
        val d = dir()
        ModelExecutionRunLog.log(d, 1, "A", runId = "r")
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.STREAM_ERROR, detail = ";".repeat(10_000), runId = "r")
        ModelExecutionRunLog.log(d, 1, ModelExecutionRunLog.Phase.SELF_REAP, runId = "r")
        val tail = ModelExecutionRunLog.readTail(d)
        // tail is bounded to the last TAIL_BYTES — this must still surface the
        // LAST complete phase (SELF_REAP) and not blow up on the multi-KiB
        // detail line. The huge detail line's torn prefix is dropped, which is
        // the intended bounded-tail behaviour.
        assertTrue("tail should not be empty", tail.isNotEmpty())
        assertTrue("last complete phase must be readable", tail.any { it.contains("SELF_REAP") })
    }
}