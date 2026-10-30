package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SYMPTOM-A F-A2a] The T7-D rejection line must say what it is: a **shadow**
 * reducer declining a verdict, not the run failing to finish.
 *
 * Why the wording is load-bearing (evidence, 2026-10-29): 23 rejections across
 * three days, all `cannot SUCCEED while an outcome-unknown tool result exists`,
 * and 12 of them on 10-29 alone (20% of that day's runs). Every one of those
 * runs had already returned normally — `t7Reduce` writes no state on Rejected
 * and touches no control flow. Reading `T7-D reducer REJECTED …` in a log, a
 * triage pass (this one) concluded "run finalisation is failing" and spent a
 * round chasing a non-bug before the code path was read.
 *
 * These tests pin both halves: the wording (shadow / non-controlling / reason
 * kept) and the invariant the wording claims (a rejection never mutates the
 * shadow state, so it cannot influence the run).
 */
class ChatAgentTraceObserverRejectionMessageTest {

    private val warnings = mutableListOf<Pair<String, String>>()

    private fun observer() = ChatAgentTraceObserver(
        traceDirResolver = { null },
        wallClockMs = { 0L },
        monotonicClockMs = { 0L },
        warn = { tag, msg -> warnings += tag to msg },
    )

    /** Run that executed a tool whose result is untrustworthy, then completed. */
    private fun stateWithUnknownTool(): AgentRunState = AgentRunReducer.reduceAll(
        listOf(
            AgentRunEvent.RunStarted("r-shadow"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = false),
            AgentRunEvent.WorkCompleted,
        ),
    ).finalState

    @Test
    fun `declined SUCCEED names the shadow reducer and says it is non-controlling`() {
        val obs = observer()
        val state = stateWithUnknownTool()
        obs.t7ReducerState = state

        obs.t7Reduce(AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))

        assertEquals(1, warnings.size)
        assertEquals("ChatVMStream", warnings[0].first)
        val msg = warnings[0].second
        assertTrue("must name the event:\n$msg", msg.startsWith("T7-D shadow reducer declined RunFinalized"))
        assertTrue("must say the rejection is non-controlling:\n$msg", msg.contains("non-controlling"))
        assertTrue(
            "must keep the reducer's own reason:\n$msg",
            msg.contains("cannot SUCCEED while an outcome-unknown tool result exists"),
        )
        assertTrue("the old misleading wording must be gone:\n$msg", !msg.contains("reducer REJECTED"))

        // 文案声称"不改状态/不改控制流"——这条断言就是那句话的证据面。
        assertEquals("a rejection must not mutate the shadow state", state, obs.t7ReducerState)
    }

    @Test
    fun `accepted transitions stay silent and still move the shadow state`() {
        val obs = observer()
        obs.t7ReducerState = AgentRunState.initial()

        obs.t7Reduce(AgentRunEvent.RunStarted("r-ok"))

        assertEquals("an accepted transition is not a warning", 0, warnings.size)
        assertNotNull("accepted transitions must still advance the shadow machine", obs.t7ReducerState)
    }
}
