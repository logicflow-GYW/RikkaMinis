package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 2 :modelservice lifecycle state machine
 * ([ModelExecutionLifecycle]) and the quiescence decision.
 *
 * These pin the semantics that replaced the reverted global 30s idle-kill:
 *   - a worker with in-flight work (active/queued/unacked) NEVER reaches a
 *     kill decision — concurrent streams can never be severed;
 *   - when fully quiescent the worker always proceeds to STOPPING and
 *     shouldKill=true (self-reap), which is the whole point of running the
 *     LLM call out-of-process;
 *   - DEAD is terminal: a DEAD worker can never come back or be killed again.
 */
class ModelExecutionLifecycleTest {

    private val idle = ModelExecutionQuiescenceInput(
        activeRequests = 0,
        queuedRequests = 0,
        unackedResponses = 0,
        streamFileFlushed = true,
    )

    private fun busy(active: Int = 1) = ModelExecutionQuiescenceInput(
        activeRequests = active,
        queuedRequests = 0,
        unackedResponses = 0,
        streamFileFlushed = true,
    )

    @Test
    fun `idle worker proceeds to stopping`() {
        // Natural completion: a fully quiescent worker self-reaps regardless of
        // anything the main process might have wanted. The old test asserted
        // this for shutdownRequested=false AND =true — both columns collapsed
        // into this one when the dead parameter was deleted
        // [FIX-FW-DEAD-SHUTDOWN-LEVER]; the transition table test pins the
        // full matrix now.
        assertEquals(
            ModelExecutionWorkerState.STOPPING,
            ModelExecutionLifecycle.transition(
                ModelExecutionWorkerState.ACTIVE, idle,
            ),
        )
    }

    @Test
    fun `busy worker stays active and never kills`() {
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.ACTIVE, busy(active = 2),
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        // An in-flight request blocks the kill.
        assertFalse(ModelExecutionLifecycle.shouldKill(next, busy(active = 2)))
    }

    @Test
    fun `queued or unacked work also blocks kill`() {
        val queued = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 1,
            unackedResponses = 0,
            streamFileFlushed = true,
        )
        val nextQ = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.ACTIVE, queued,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, nextQ)
        assertFalse(ModelExecutionLifecycle.shouldKill(nextQ, queued))

        val unacked = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 0,
            unackedResponses = 1,
            streamFileFlushed = true,
        )
        val nextU = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.ACTIVE, unacked,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, nextU)
        assertFalse(ModelExecutionLifecycle.shouldKill(nextU, unacked))
    }

    @Test
    fun `unflushed stream file blocks kill`() {
        val unflushed = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 0,
            unackedResponses = 0,
            streamFileFlushed = false,
        )
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.ACTIVE, unflushed,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, unflushed))
    }

    @Test
    fun `stopping with no work reaps`() {
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.QUIESCE_PENDING, idle,
        )
        assertEquals(ModelExecutionWorkerState.STOPPING, next)
        assertTrue(ModelExecutionLifecycle.shouldKill(next, idle))
    }

    @Test
    fun `stopping with revived work does not reap`() {
        // A new request arrived right as we were about to die: the worker
        // revived to ACTIVE (onStartCommand), so no kill.
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.STOPPING, busy(active = 1),
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, busy(active = 1)))
    }

    @Test
    fun `dead is terminal`() {
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.DEAD, busy(active = 1),
        )
        assertEquals(ModelExecutionWorkerState.DEAD, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, busy(active = 1)))
    }

    @Test
    fun `quiescent helper`() {
        assertTrue(ModelExecutionLifecycle.isQuiescent(idle))
        assertFalse(ModelExecutionLifecycle.isQuiescent(busy()))
        assertFalse(ModelExecutionLifecycle.isQuiescent(idle.copy(streamFileFlushed = false)))
    }

    // ── TF-G: streaming worker holding an unacked response ─────────

    @Test
    fun `streaming worker awaiting client ack (unacked=1) never reaches kill`() {
        // A streaming run finished its stream/result flush but is STILL waiting
        // for the client to consume (client.ack). Even with active==0 and the
        // stream flushed, unacked=1 must keep the worker ALIVE — this is the
        // precise P0 where the old hard-coded unacked=0 SIGKILLed the worker
        // one line after DONE, ahead of the client reading the tail.
        val awaitingAck = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 0,
            unackedResponses = 1,
            streamFileFlushed = true,
        )
        assertFalse(ModelExecutionLifecycle.isQuiescent(awaitingAck))
        val next = ModelExecutionLifecycle.transition(
            ModelExecutionWorkerState.ACTIVE, awaitingAck,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, awaitingAck))
    }
}