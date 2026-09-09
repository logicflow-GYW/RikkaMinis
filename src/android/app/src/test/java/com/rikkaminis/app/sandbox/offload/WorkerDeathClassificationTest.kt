package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TF-G P0-3 JVM tests: the pure worker-death classification matrix
 * ([ModelExecutionRunDir.classifyWorkerDeath]). Every combination of:
 *   - valid worker.pid ref present (hasPidRef)   true/false
 *   - worker.ready marker present (ready)        true/false
 *   - ≥1 stream chunk already emitted (hadChunks) true/false
 *
 * These pin the caller's retry-vs-fatal weight:
 *   - hadChunks=true → DIED_MID_STREAM (MUST NOT re-send — user saw text);
 *   - pid ref + pre-ready death → DIED_BEFORE_READY;
 *   - pid ref + ready death with no output → DIED_AFTER_READY_NO_OUTPUT;
 *   - no pid ref ever → NEVER_STARTED.
 * Classification is only reached AFTER the 3-state probe already returned DEAD
 * and no terminal/result was present, so this never needs to express
 * "UNKNOWN or alive".
 */
class WorkerDeathClassificationTest {

    // ── hadChunks dominates: any death after output is mid-stream ─────

    @Test
    fun `had chunks always classifies as mid stream`() {
        assertEquals(
            WorkerDeathReason.DIED_MID_STREAM,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = true, ready = true, hadChunks = true),
        )
        assertEquals(
            WorkerDeathReason.DIED_MID_STREAM,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = true, ready = false, hadChunks = true),
        )
        assertEquals(
            WorkerDeathReason.DIED_MID_STREAM,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = false, ready = false, hadChunks = true),
        )
    }

    // ── no pid ref anywhere → never started ──────────────────────────

    @Test
    fun `no pid ref and no chunks is never started`() {
        assertEquals(
            WorkerDeathReason.NEVER_STARTED,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = false, ready = false, hadChunks = false),
        )
        // (ready can't meaningfully be true without a pid ref, but remains NEVER_STARTED)
        assertEquals(
            WorkerDeathReason.NEVER_STARTED,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = false, ready = true, hadChunks = false),
        )
    }

    // ── pid ref present, pre-ready → died before ready (may retry) ────

    @Test
    fun `pid ref present but killed before ready is died before ready`() {
        assertEquals(
            WorkerDeathReason.DIED_BEFORE_READY,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = true, ready = false, hadChunks = false),
        )
    }

    // ── pid ref present + ready, no output → after ready no output ───

    @Test
    fun `pid ref and ready but no output is died after ready no output`() {
        assertEquals(
            WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT,
            ModelExecutionRunDir.classifyWorkerDeath(hasPidRef = true, ready = true, hadChunks = false),
        )
    }

    // ── the full 2^3 matrix, asserted exhaustively ────────────────────

    @Test
    fun `full classification matrix`() {
        // (hasPidRef, ready, hadChunks) -> expected
        val expected = mapOf(
            (false to false) to WorkerDeathReason.NEVER_STARTED,
            (false to true) to WorkerDeathReason.NEVER_STARTED,
            (true to false) to WorkerDeathReason.DIED_BEFORE_READY,
            (true to true) to WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT,
        )
        for (hasPidRef in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                for (hadChunks in listOf(false, true)) {
                    val reason = ModelExecutionRunDir.classifyWorkerDeath(hasPidRef, ready, hadChunks)
                    val expect = if (hadChunks) {
                        WorkerDeathReason.DIED_MID_STREAM
                    } else {
                        expected[(hasPidRef to ready)]!!
                    }
                    assertEquals(
                        "classify($hasPidRef,$ready,$hadChunks)",
                        expect, reason,
                    )
                }
            }
        }
    }

    // ── exception message carries the reason + runId + phase ─────────

    @Test
    fun `worker died exception message includes reason and runId and phase`() {
        val ex = ModelWorkerDiedException(
            hadChunks = true,
            reason = WorkerDeathReason.DIED_MID_STREAM,
            runId = "runId-xyz",
            phaseSummary = "STREAM_DONE|TERMINAL_WRITTEN",
        )
        val msg = ex.message ?: ""
        assert(msg.contains("mid-stream"))
        assert(msg.contains("DIED_MID_STREAM"))
        assert(msg.contains("runId-xyz"))
        assert(msg.contains("STREAM_DONE"))
        assert(ex.hadChunks)

        val noChunk = ModelWorkerDiedException(
            hadChunks = false,
            reason = WorkerDeathReason.NEVER_STARTED,
            runId = "r2",
        )
        assert((noChunk.message ?: "").contains("before any output"))
        assert((noChunk.message ?: "").contains("NEVER_STARTED"))
        assert(!noChunk.hadChunks)
    }
}