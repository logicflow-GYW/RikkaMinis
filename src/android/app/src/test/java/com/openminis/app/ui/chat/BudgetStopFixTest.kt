package com.openminis.app.ui.chat

import com.openminis.app.agent.runtime.AgentExecutionBudget
import com.openminis.app.agent.runtime.BudgetDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/budget-stop-silent-exit] JVM tests for the budget-stop fix.
 *
 * Field evidence (2026-09-07 log, 28-min agent session): the run burned
 * exactly 64 provider attempts (== the old T7_OBSERVE_MAX_PROVIDER_ATTEMPTS),
 * after which every continue-path loop iteration (verify nudge, empty-turn
 * retry) was refused by consumeProviderAttempt, produced three 1-ms empty
 * turns with ZERO provider dispatches, and the loop exited via budget stop
 * with NO user-visible signal.
 *
 * These tests pin the two halves of the fix:
 *  1. The raised ceiling (128 = 2x the observed real-workload peak) is
 *     actually exported by ChatAgentTraceObserver.
 *  2. The refusal gate itself behaves deterministically at the boundary
 *     (N allowed, N+1 denied) — the property the engine's budget-stop
 *     finalize path now relies on for its banner + resume semantics.
 */
class BudgetStopFixTest {

    @Test
    fun `provider attempt ceiling raised to 128`() {
        assertEquals(128, ChatAgentTraceObserver.T7_OBSERVE_MAX_PROVIDER_ATTEMPTS)
    }

    @Test
    fun `consume gate allows up to max then denies deterministically`() {
        val budget = AgentExecutionBudget(
            startedAtMonotonicMs = 0L,
            deadlineMonotonicMs = Long.MAX_VALUE,
            maxTurns = 200,
            maxProviderAttempts = 128,
            maxToolCalls = 128,
            maxShellCommands = 128,
            maxCompactionCalls = 8,
            maxConcurrentTools = 4,
            maxEstimatedTokens = null,
            monotonicClock = { 0L },
        )
        repeat(128) {
            val decision = budget.consumeProviderAttempt()
            assertTrue("attempt ${it + 1} must be allowed", decision is BudgetDecision.Allowed)
        }
        // The 129th is the budget-stop trigger: the engine sees this exact
        // refusal, sets t7BudgetStopReason, and (post-fix) finalizes with a
        // visible banner + resume instead of exiting silently.
        assertTrue(budget.consumeProviderAttempt() is BudgetDecision.Denied)
    }

    @Test
    fun `a 64-attempt workload that used to die now fits under the ceiling`() {
        // Regression shape of the field incident: 61 turns + 3 retry/nudge
        // re-entries = 64 provider attempts, all allowed under the new 128
        // ceiling. Under the old 64 ceiling the 64th itself was refused.
        val budget = AgentExecutionBudget(
            startedAtMonotonicMs = 0L,
            deadlineMonotonicMs = Long.MAX_VALUE,
            maxTurns = 200,
            maxProviderAttempts = ChatAgentTraceObserver.T7_OBSERVE_MAX_PROVIDER_ATTEMPTS,
            maxToolCalls = 128,
            maxShellCommands = 128,
            maxCompactionCalls = 8,
            maxConcurrentTools = 4,
            maxEstimatedTokens = null,
            monotonicClock = { 0L },
        )
        repeat(64) {
            assertTrue(budget.consumeProviderAttempt() is BudgetDecision.Allowed)
        }
        // …and there is still headroom for the run to complete.
        repeat(64) {
            assertTrue(budget.consumeProviderAttempt() is BudgetDecision.Allowed)
        }
    }
}
