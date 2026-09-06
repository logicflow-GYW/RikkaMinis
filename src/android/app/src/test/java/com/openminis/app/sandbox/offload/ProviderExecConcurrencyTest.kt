package com.openminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/provider-exec-concurrency] JVM tests for the concurrency semantics
 * the ModelExecutionService changes rely on:
 *
 *  1. Slot policy exports a bounded pool (2) — the serialized-mutex era is
 *     over but the native-heap containment rationale caps it.
 *  2. Queue admission bound (6) — the slot pool bounds execution, the
 *     admission bound bounds the queue itself.
 *  3. Lifecycle reap under concurrent finishers: with the TF-H generation
 *     gate removed from finishRequestLocked, EVERY finisher re-evaluates
 *     quiescence — the last one out reaps, earlier ones hold. This test
 *     pins that property against the pure lifecycle machine (the service
 *     delegates to exactly these transitions).
 */
class ProviderExecConcurrencyTest {

    @Test
    fun `slot pool allows two concurrent provider runs`() {
        assertEquals(2, ProviderExecSlotPolicy.MAX_CONCURRENT_PROVIDER_RUNS)
        assertEquals(6, ProviderExecSlotPolicy.MAX_QUEUED_REQUESTS)
    }

    @Test
    fun `lifecycle stays active while either of two concurrent runs is in flight`() {
        // Run A finishes (active 2->1) while B still streams: the finisher
        // re-evaluates quiescence and must NOT transition to STOPPING.
        val q = ModelExecutionQuiescenceInput(
            activeRequests = 1,
            queuedRequests = 0,
            unackedResponses = 0,
            streamFileFlushed = true,
        )
        val next = ModelExecutionLifecycle.transition(
            current = ModelExecutionWorkerState.ACTIVE,
            quiescence = q,
            shutdownRequested = false,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, q))
    }

    @Test
    fun `last finisher out reaps a drained worker`() {
        // Run B (the last of two) finishes: active 1->0, queue 0, acks 0 —
        // quiescent, so the transition goes STOPPING and shouldKill fires.
        val q = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 0,
            unackedResponses = 0,
            streamFileFlushed = true,
        )
        val next = ModelExecutionLifecycle.transition(
            current = ModelExecutionWorkerState.ACTIVE,
            quiescence = q,
            shutdownRequested = false,
        )
        assertEquals(ModelExecutionWorkerState.STOPPING, next)
        assertTrue(ModelExecutionLifecycle.shouldKill(next, q))
    }

    @Test
    fun `unacked response from the earlier finisher still blocks the reap`() {
        // A finished streaming and is waiting on its client ack (unacked=1)
        // when B finishes: B's finisher must see the pending ack and keep
        // the worker alive — exactly the TF-G barrier semantics preserved
        // under concurrent finishers.
        val q = ModelExecutionQuiescenceInput(
            activeRequests = 0,
            queuedRequests = 0,
            unackedResponses = 1,
            streamFileFlushed = true,
        )
        val next = ModelExecutionLifecycle.transition(
            current = ModelExecutionWorkerState.ACTIVE,
            quiescence = q,
            shutdownRequested = false,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, q))
    }

    @Test
    fun `queued slot-waiter blocks the reap while a holder streams`() {
        // Slot pool saturated (2 holders) + 1 waiter queued: any finisher
        // path that evaluates quiescence must see queued>0 and stay ACTIVE.
        val q = ModelExecutionQuiescenceInput(
            activeRequests = 2,
            queuedRequests = 1,
            unackedResponses = 0,
            streamFileFlushed = true,
        )
        val next = ModelExecutionLifecycle.transition(
            current = ModelExecutionWorkerState.ACTIVE,
            quiescence = q,
            shutdownRequested = false,
        )
        assertEquals(ModelExecutionWorkerState.ACTIVE, next)
        assertFalse(ModelExecutionLifecycle.shouldKill(next, q))
    }
}
