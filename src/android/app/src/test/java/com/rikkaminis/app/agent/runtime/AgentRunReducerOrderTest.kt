package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TF-G P1-3 JVM tests: the reducer ordering contract that Pin «RunStarted
 * first». They pin the exact bug seen in production ("T7-D reducer REJECTED
 * ... run not started; requires RunStarted first"): if a caller issues
 * ProviderAttemptStarted / ToolStarted BEFORE RunStarted, the reducer standing
 * at IDLE rejects them with RUN_NOT_STARTED. ChatViewModel's fix initialises
 * the reducer state machine and emits RunStarted before any provider/phase
 * event; these tests lock that contract so a regression re-triggers the spam.
 */
class AgentRunReducerOrderTest {

    @Test
    fun `provider attempt before RunStarted is rejected as run not started`() {
        val batch = AgentRunReducer.reduceAll(listOf(AgentRunEvent.ProviderAttemptStarted))
        assertNotNull(batch.firstRejectedIndex)
        assertEquals(0, batch.firstRejectedIndex)
        val first = batch.transitions.first()
        assertTrue(first is AgentRunTransition.Rejected)
        assertEquals(
            AgentRunRejectionReason.RUN_NOT_STARTED,
            (first as AgentRunTransition.Rejected).rejection.reason,
        )
    }

    @Test
    fun `tool start before RunStarted is rejected as run not started`() {
        val batch = AgentRunReducer.reduceAll(listOf(AgentRunEvent.ToolStarted("file_read")))
        assertNotNull(batch.firstRejectedIndex)
        val first = batch.transitions.first()
        assertEquals(
            AgentRunRejectionReason.RUN_NOT_STARTED,
            (first as AgentRunTransition.Rejected).rejection.reason,
        )
    }

    @Test
    fun `full normal turn after RunStarted is fully accepted`() {
        // The exact sequence a healthy chat turn now emits, STARTING with
        // RunStarted (the TF-G P1-3 fix ensures this is issued before any
        // provider/phase event). Assert NO rejection in the whole normal path.
        val batch = AgentRunReducer.reduceAll(listOf(
            AgentRunEvent.RunStarted("r1"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("file_read"),
            AgentRunEvent.ToolFinished("file_read", resultKnown = true),
            AgentRunEvent.WorkCompleted,
            AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED),
        ))
        assertNull("no event should be rejected", batch.firstRejectedIndex)
        assertTrue(batch.reachedTerminal)
        assertEquals(AgentRunPhase.SUCCEEDED, batch.finalState.phase)
    }

    @Test
    fun `reducer with RunStarted first never reports run-not-started for provider events`() {
        // Guard against the RunStarted-after-init regression: with the state
        // machine properly initialised + RunStarted issued first, a
        // ProviderAttemptStarted must be Accepted (not silently dropped at IDLE,
        // which is what produced the old misleading REJECTED spam).
        val start = AgentRunReducer.reduce(
            AgentRunState.initial(), AgentRunEvent.RunStarted("abc"),
        )
        assertTrue(start is AgentRunTransition.Accepted)
        val afterStart = (start as AgentRunTransition.Accepted).state
        val attempt = AgentRunReducer.reduce(afterStart, AgentRunEvent.ProviderAttemptStarted)
        assertTrue(attempt is AgentRunTransition.Accepted)
        assertEquals(AgentRunPhase.CALLING_MODEL, (attempt as AgentRunTransition.Accepted).state.phase)
    }
}