package com.rikkaminis.app.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TF-H: fallback-exhausted reducer transitions. Pins that the production
 * cohort "FALLBACK_FAILURE → FallbackExhausted → RunFinalized" is accepted,
 * and that FallbackExhausted is only legal from FALLING_BACK.
 */
class FallbackExhaustedReducerTest {

    private fun reduceBatch(vararg events: AgentRunEvent): AgentRunBatchResult =
        AgentRunReducer.reduceAll(events.toList())

    private fun AgentRunTransition.rejection(): AgentRunRejection =
        (this as AgentRunTransition.Rejected).rejection

    @Test
    fun `fallback exhausted then finalize is accepted`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FALLBACK_FAILURE),
            AgentRunEvent.FallbackExhausted,
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED),
        )
        assertNull("full fallback-exhaust chain must not be rejected", batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.FAILED, batch.finalState.phase)
        assertEquals(AgentTerminalReason.EXECUTION_FAILED, batch.finalState.terminalReason)
    }

    @Test
    fun `fallback exhausted outside falling back is rejected`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.FallbackExhausted,
        )
        // events: [0]=RunStarted accepted, [1]=ProviderAttemptStarted accepted
        // (PREPARING→CALLING_MODEL), [2]=FallbackExhausted rejected from
        // CALLING_MODEL.
        assertEquals(2, batch.firstRejectedIndex)
        assertEquals(
            AgentRunRejectionReason.INVALID_PHASE_FOR_EVENT,
            batch.transitions[2].rejection().reason,
        )
    }

    @Test
    fun `fallback exhausted from finalizing is no-op`() {
        val batch = reduceBatch(
            AgentRunEvent.RunStarted("r"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.FATAL_FAILURE),
            AgentRunEvent.FallbackExhausted,
            AgentRunEvent.RunFinalized(AgentTerminal.FAILED),
        )
        // FallbackExhausted in FINALIZING is tolerated as no-op (stale).
        assertNull(batch.firstRejectedIndex)
        assertEquals(AgentRunPhase.FAILED, batch.finalState.phase)
    }
}