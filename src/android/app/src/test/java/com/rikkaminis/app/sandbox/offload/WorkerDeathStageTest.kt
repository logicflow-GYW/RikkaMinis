package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TF-H: stage-aware death classification tests
 * ([ModelExecutionRunDir.classifyWorkerDeathStaged]).
 */
class WorkerDeathStageTest {

    @Test
    fun `had chunks always mid stream`() {
        assertEquals(
            WorkerDeathReason.DIED_MID_STREAM,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = true, ready = true, hadChunks = true,
                reachedRequestThread = true, reachedHttp = true,
            ),
        )
    }

    @Test
    fun `no pid ref and never reached request thread is never started`() {
        assertEquals(
            WorkerDeathReason.NEVER_STARTED,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = false, ready = false, hadChunks = false,
                reachedRequestThread = false, reachedHttp = false,
            ),
        )
    }

    @Test
    fun `pid ref but never reached request thread is never started`() {
        assertEquals(
            WorkerDeathReason.NEVER_STARTED,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = true, ready = true, hadChunks = false,
                reachedRequestThread = false, reachedHttp = false,
            ),
        )
    }

    @Test
    fun `pid ref reached thread but never http is died before ready`() {
        assertEquals(
            WorkerDeathReason.DIED_BEFORE_READY,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = true, ready = true, hadChunks = false,
                reachedRequestThread = true, reachedHttp = false,
            ),
        )
    }

    @Test
    fun `pid ref reached http but no chunks falls back to ready matrix`() {
        assertEquals(
            WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = true, ready = true, hadChunks = false,
                reachedRequestThread = true, reachedHttp = true,
            ),
        )
    }

    @Test
    fun `reached http without ready still falls back to before ready`() {
        assertEquals(
            WorkerDeathReason.DIED_BEFORE_READY,
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = true, ready = false, hadChunks = false,
                reachedRequestThread = true, reachedHttp = true,
            ),
        )
    }

    @Test
    fun `no pid ref with chunks is still mid stream`() {
        assertTrue(
            ModelExecutionRunDir.classifyWorkerDeathStaged(
                hasPidRef = false, ready = false, hadChunks = true,
                reachedRequestThread = true, reachedHttp = true,
            ) == WorkerDeathReason.DIED_MID_STREAM,
        )
    }

    @Test
    fun `falsey defaults do not accidentally classify as after ready`() {
        val r = ModelExecutionRunDir.classifyWorkerDeathStaged(
            hasPidRef = false, ready = false, hadChunks = false,
            reachedRequestThread = false, reachedHttp = false,
        )
        assertFalse("must be NEVER_STARTED", r == WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT)
    }
}