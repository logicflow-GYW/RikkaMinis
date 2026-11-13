package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentTerminalReason
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [R8-B C-04] The trace must record the terminal the STATE MACHINE accepted, not
 * the loop's own verdict.
 *
 * `terminal` arrives from `AgentLoopExitDecision`. The reducer additionally knows
 * that an outcome-unknown tool result or a required persistence failure makes
 * SUCCEEDED illegal. Before this fix the rejection was only logged and the trace
 * file still said `"terminal_state":"Succeeded"` — the single artifact an auditor
 * reads was contradicting the invariant on exactly the runs that need auditing.
 *
 * SCOPE (the point of this test): trace output only. The observer's public
 * contract — no exception, no changed return value, no state left behind — is
 * asserted alongside the downgrade.
 */
class ChatAgentTraceObserverTerminalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun observer(dir: File, warnings: MutableList<String>? = null) = ChatAgentTraceObserver(
        traceDirResolver = { dir },
        wallClockMs = { 1_700_000_000_000L },
        monotonicClockMs = { 0L },
        warn = { _, m -> warnings?.add(m) },
    )

    private fun traceEndLine(dir: File): JSONObject {
        val file = File(dir, "agent-trace.jsonl")
        val lines = file.readLines().filter { it.isNotBlank() }
        return JSONObject(lines.last { it.contains("\"trace_end\"") })
    }

    /** A run that executed a tool whose result cannot be trusted. */
    private fun stateWithUnknownTool(): AgentRunState = AgentRunReducer.reduceAll(
        listOf(
            AgentRunEvent.RunStarted("r-c04"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = false),
            AgentRunEvent.WorkCompleted,
        ),
    ).finalState

    private fun stateWithPersistenceFailure(): AgentRunState = AgentRunReducer.reduceAll(
        listOf(
            AgentRunEvent.RunStarted("r-c04-p"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.PersistenceFailed("db insert"),
        ),
    ).finalState

    private fun runToTrace(
        state: AgentRunState,
        terminal: AgentTerminal,
        reason: AgentTerminalReason?,
    ): JSONObject {
        val dir = tmp.newFolder("trace-${System.nanoTime()}")
        val obs = observer(dir)
        obs.traceRunFile = File(dir, "agent-trace.jsonl")
        obs.t7ReducerState = state
        obs.t7EndRun(terminal, reason, durationMs = 42L)
        return traceEndLine(dir)
    }

    @Test
    fun `a SUCCEEDED verdict the reducer refuses is recorded as Failed`() {
        val end = runToTrace(stateWithUnknownTool(), AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED)
        assertEquals("Failed", end.optString("terminal_state"))
        assertTrue(
            "the error must say why the loop's verdict was overruled:\n$end",
            end.optString("error").contains("outcome-unknown"),
        )
    }

    @Test
    fun `a persistence failure also downgrades the recorded terminal`() {
        val end = runToTrace(
            stateWithPersistenceFailure(),
            AgentTerminal.SUCCEEDED,
            AgentTerminalReason.COMPLETED,
        )
        assertEquals("Failed", end.optString("terminal_state"))
        assertTrue(end.optString("error").contains("persistence"))
    }

    @Test
    fun `a clean run still records Succeeded`() {
        val clean = AgentRunReducer.reduceAll(
            listOf(
                AgentRunEvent.RunStarted("r-ok"),
                AgentRunEvent.ProviderAttemptStarted,
                AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
                AgentRunEvent.WorkCompleted,
            ),
        ).finalState
        val end = runToTrace(clean, AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED)
        assertEquals("Succeeded", end.optString("terminal_state"))
        assertEquals("completed_normally", end.optString("terminal_reason"))
        assertEquals("the un-downgraded path carries no synthetic error", "", end.optString("error"))
    }

    @Test
    fun `the downgraded trace keeps the caller's error text too`() {
        val dir = tmp.newFolder("trace-keep")
        val obs = observer(dir)
        obs.traceRunFile = File(dir, "agent-trace.jsonl")
        obs.t7ReducerState = stateWithUnknownTool()
        obs.t7EndRun(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED, 7L, error = "boom")
        val end = traceEndLine(dir)
        assertTrue("the original error must survive:\n$end", end.optString("error").startsWith("boom"))
        assertEquals("Failed", end.optString("terminal_state"))
    }

    @Test
    fun `the non-SUCCEEDED terminals are written unchanged`() {
        for (terminal in listOf(AgentTerminal.CANCELLED, AgentTerminal.FAILED, AgentTerminal.INTERRUPTED)) {
            val end = runToTrace(AgentRunState.initial(), terminal, null)
            assertEquals(
                "terminal=$terminal",
                ChatAgentTraceObserver.t7TerminalSchema(terminal),
                end.optString("terminal_state"),
            )
        }
    }

    @Test
    fun `the observer leaves no run state behind - control flow is untouched`() {
        val dir = tmp.newFolder("trace-cleanup")
        val obs = observer(dir)
        obs.traceRunFile = File(dir, "agent-trace.jsonl")
        obs.t7ReducerState = stateWithUnknownTool()
        obs.t7EndRun(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED, 5L)
        // The run is over: no shadow state, no budget, no observed phase. The
        // downgrade is a trace-only decision and must not survive the call.
        assertNull(obs.t7ReducerState)
        assertNull(obs.activeRunId)
        assertNull(obs.t7ObservedPhase)
    }

    @Test
    fun `no reducer state means the loop verdict is the only evidence and is written as-is`() {
        val dir = tmp.newFolder("trace-nostate")
        val obs = observer(dir)
        obs.traceRunFile = File(dir, "agent-trace.jsonl")
        obs.t7ReducerState = null
        obs.t7EndRun(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED, 1L)
        assertEquals("Succeeded", traceEndLine(dir).optString("terminal_state"))
    }

    @Test
    fun `downgradedTerminal only touches SUCCEEDED`() {
        assertEquals(AgentTerminal.FAILED, ChatAgentTraceObserver.downgradedTerminal(AgentTerminal.SUCCEEDED))
        assertEquals(AgentTerminal.CANCELLED, ChatAgentTraceObserver.downgradedTerminal(AgentTerminal.CANCELLED))
        assertEquals(AgentTerminal.INTERRUPTED, ChatAgentTraceObserver.downgradedTerminal(AgentTerminal.INTERRUPTED))
        assertEquals(AgentTerminal.FAILED, ChatAgentTraceObserver.downgradedTerminal(AgentTerminal.FAILED))
    }

    @Test
    fun `the downgrade never leaves the trace-only layer`() {
        // The boundary the task book draws: this fix may not change the agent
        // control flow. Two mechanical checks on the production source:
        //  (1) the write path is the ONLY consumer of `recorded`;
        //  (2) no `terminal =` assignment exists (the parameter is not shadowed
        //      or reassigned), so the caller's verdict is untouched.
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatAgentTraceObserver.kt",
        )
        val body = src.substring(
            src.indexOf("internal fun t7EndRun("),
            src.indexOf("internal fun newTraceFile("),
        )
        assertEquals(
            "recorded must feed exactly the two trace fields",
            2,
            Regex("t7TerminalSchema\\(recorded\\)|t7TerminalReasonSchema\\(recordedReason\\)")
                .findAll(body).count(),
        )
        assertFalse(
            "the inbound terminal must never be reassigned",
            // `(?!\\s*terminal\\b)` — the leading \\s* matters: without it the
            // engine backtracks `\\s*` to zero and the lookahead sees the space,
            // so `terminal = terminal,` (a NAMED ARGUMENT, not an assignment)
            // was reported as a reassignment. Found by running the arm.
            Regex("""\bterminal\s*=\s*(?!\s*terminal\b)""").containsMatchIn(body),
        )
        assertTrue(
            "the caller's terminal still drives the lease release",
            body.contains("releasedBy = when (terminal)"),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }
}
