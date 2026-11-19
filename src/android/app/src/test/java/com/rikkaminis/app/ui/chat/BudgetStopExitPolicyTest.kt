package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-chat-1119 F-11] The exit policy, on its own.
 *
 * Two directions, and both are load-bearing:
 *  - a gate that STOPPED the run must be reported (banner + resumable +
 *    FAILED terminal) — dropping it would make a budget stop silent again
 *    (the 2026-09-07 field incident this whole mechanism was built for);
 *  - a run that ANSWERED must keep its normal exit — the old code keyed on
 *    `t7BudgetStopReason != null` alone, so any run that hit a gate and then
 *    finished had its answer rewritten into a budget failure.
 */
class BudgetStopExitPolicyTest {

    @Test
    fun `an interrupted run still finalizes as a budget stop`() {
        assertTrue(BudgetStopExitPolicy.shouldFinalizeBudgetStop("tool_call_limit", loopExitedNormally = false))
        assertTrue(BudgetStopExitPolicy.shouldFinalizeBudgetStop("shell_command_limit", loopExitedNormally = false))
        assertTrue(BudgetStopExitPolicy.shouldFinalizeBudgetStop("deadline_reached", loopExitedNormally = false))
        assertEquals(
            "tool_call_limit",
            BudgetStopExitPolicy.effectiveBudgetStopReason("tool_call_limit", loopExitedNormally = false),
        )
        assertEquals(
            "deadline_reached",
            BudgetStopExitPolicy.effectiveBudgetStopReason("deadline_reached", loopExitedNormally = false),
        )
    }

    @Test
    fun `a finished answer keeps its normal exit`() {
        assertFalse(
            "a finished answer must keep its normal exit — a tool_call_limit gate that was " +
                "answered to completion is not a budget failure",
            BudgetStopExitPolicy.shouldFinalizeBudgetStop("tool_call_limit", loopExitedNormally = true),
        )
        assertFalse(
            "a finished answer must keep its normal exit — same for shell_command_limit",
            BudgetStopExitPolicy.shouldFinalizeBudgetStop("shell_command_limit", loopExitedNormally = true),
        )
        assertEquals(
            "no budget reason reaches the terminal table for a clean finish",
            null,
            BudgetStopExitPolicy.effectiveBudgetStopReason("tool_call_limit", loopExitedNormally = true),
        )
        assertEquals(
            "no budget reason reaches the terminal table for a clean finish",
            null,
            BudgetStopExitPolicy.effectiveBudgetStopReason("shell_command_limit", loopExitedNormally = true),
        )
    }

    @Test
    fun `no reason at all is unchanged`() {
        assertFalse(BudgetStopExitPolicy.shouldFinalizeBudgetStop(null, loopExitedNormally = true))
        assertFalse(BudgetStopExitPolicy.shouldFinalizeBudgetStop(null, loopExitedNormally = false))
        assertEquals(null, BudgetStopExitPolicy.effectiveBudgetStopReason(null, loopExitedNormally = true))
        assertEquals(null, BudgetStopExitPolicy.effectiveBudgetStopReason(null, loopExitedNormally = false))
    }

    @Test
    fun `a fired gate stops the loop from asking for another turn`() {
        assertTrue(
            "a fired tool_calls gate must stop the loop from asking for another turn",
            BudgetStopExitPolicy.shouldStopLoop("tool_call_limit"),
        )
        assertTrue(
            "a fired shell_commands gate must stop the loop from asking for another turn",
            BudgetStopExitPolicy.shouldStopLoop("shell_command_limit"),
        )
        assertTrue(
            "a reached deadline must stop the loop from asking for another turn",
            BudgetStopExitPolicy.shouldStopLoop("deadline_reached"),
        )
        assertFalse("no reason → the loop keeps running (unchanged)", BudgetStopExitPolicy.shouldStopLoop(null))
    }
}
