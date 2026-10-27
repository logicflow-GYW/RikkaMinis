package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.AgentTerminalReason
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-0907 B5] terminalErrorSurfaced semantics — locked against the
 * PRODUCTION decision table.
 *
 * [deep-audit-1015 BUG-01a] This file used to carry its own copy of the
 * engine's `when` blocks and assert on the copy. That is why the defect it
 * was written to guard survived: the copy stayed correct while the engine
 * drifted, and a mutation of either one left the other green. Every
 * assertion below now calls [AgentLoopExitDecision] — the same functions
 * `AgentLoopEngine.runAgentLoop` calls.
 *
 * The defect: the "no tool calls" tail set `loopExitedNormally = true`
 * unconditionally, and the failure-terminal branches deliberately fall
 * through to it (that is how their partial reply is persisted). A run that
 * had just shown the user a failure banner was therefore recorded as
 * `SUCCEEDED` + `COMPLETED` in the T7 state machine and the trace.
 */
class TerminalErrorSurfacedTest {

    // ── BUG-01a: the clean-completion claim ────────────────────────────────

    @Test
    fun `a surfaced terminal error cannot claim a clean completion`() {
        assertFalse(
            "a run whose terminal banner is already on screen did not finish its reply",
            AgentLoopExitDecision.cleanCompletionFlag(terminalErrorSurfaced = true),
        )
    }

    @Test
    fun `a turn with no terminal error still claims a clean completion`() {
        // Control arm: the guard must not tighten the ordinary path.
        assertTrue(AgentLoopExitDecision.cleanCompletionFlag(terminalErrorSurfaced = false))
    }

    @Test
    fun `every failure-terminal fall-through branch is recorded as a failure`() {
        // The branches that set terminalErrorSurfaced and fall through to the
        // tail instead of breaking (the other setters break/continue first).
        // [deep-audit-1015] the five live fall-through sites.
        val fallThroughBranches = listOf(
            "error_shaped_stub_ceiling",
            "deterministic_empty",
            "length_xN_empty",
            "repetition_abort",
            "length_wall_ceiling",
        )
        for (branch in fallThroughBranches) {
            // Pre-fix the tail set the flag unconditionally, so this is the
            // value the trace was decided from — SUCCEEDED for a failed run.
            assertEquals(
                "$branch: the unconditional tail flag is what produced the false SUCCEEDED",
                AgentTerminal.SUCCEEDED,
                AgentLoopExitDecision.terminal(budgetStopReason = null, loopExitedNormally = true),
            )
            // Post-fix the tail asks first, so the flag stays false here.
            val flagAfterFix = AgentLoopExitDecision.cleanCompletionFlag(terminalErrorSurfaced = true)
            assertEquals(
                "$branch must be recorded as a failure",
                AgentTerminal.FAILED,
                AgentLoopExitDecision.terminal(budgetStopReason = null, loopExitedNormally = flagAfterFix),
            )
            assertEquals(
                "$branch must be recorded as EXECUTION_FAILED",
                AgentTerminalReason.EXECUTION_FAILED,
                AgentLoopExitDecision.reason(budgetStopReason = null, loopExitedNormally = flagAfterFix),
            )
            assertEquals(
                "$branch must keep its own trace label, not MAX_AGENT_TURNS",
                "terminal_error_surfaced",
                AgentLoopExitDecision.errorLabel(
                    budgetStopReason = null,
                    terminalErrorSurfaced = true,
                    loopExitedNormally = flagAfterFix,
                ),
            )
        }
    }

    // ── audit-0907 B5: the runaway guard ──────────────────────────────────

    @Test
    fun `failure-terminal exits never classified as runaway`() {
        assertFalse(
            "the already-surfaced banner must not be overwritten by finalizeAtTurnLimit",
            AgentLoopExitDecision.shouldFinalizeAsRunaway(
                loopExitedNormally = false,
                terminalErrorSurfaced = true,
                budgetStopReason = null,
            ),
        )
    }

    @Test
    fun `normal completion and true runaway keep their semantics`() {
        assertFalse(AgentLoopExitDecision.shouldFinalizeAsRunaway(true, false, null))
        assertTrue(AgentLoopExitDecision.shouldFinalizeAsRunaway(false, false, null))
        assertFalse(AgentLoopExitDecision.shouldFinalizeAsRunaway(false, false, "provider_attempt_limit"))
        assertFalse(AgentLoopExitDecision.shouldFinalizeAsRunaway(false, true, "deadline_reached"))
    }

    @Test
    fun `t7 decision table`() {
        // Normal completion.
        assertEquals(AgentTerminal.SUCCEEDED, AgentLoopExitDecision.terminal(null, true))
        assertEquals(AgentTerminalReason.COMPLETED, AgentLoopExitDecision.reason(null, true))
        assertNull(AgentLoopExitDecision.errorLabel(null, false, true))

        // Exhausted turn range with nothing surfaced — the real runaway.
        assertEquals(AgentTerminal.FAILED, AgentLoopExitDecision.terminal(null, false))
        assertEquals("MAX_AGENT_TURNS", AgentLoopExitDecision.errorLabel(null, false, false))

        // Budget stops.
        assertEquals(AgentTerminal.INTERRUPTED, AgentLoopExitDecision.terminal("deadline_reached", false))
        assertEquals(
            AgentTerminalReason.DEADLINE_EXCEEDED,
            AgentLoopExitDecision.reason("deadline_reached", false),
        )
        assertEquals(
            "budget_exhausted(turn_limit)",
            AgentLoopExitDecision.errorLabel("turn_limit", true, false),
        )
        assertEquals(AgentTerminal.FAILED, AgentLoopExitDecision.terminal("turn_limit", true))
    }

    // ── BUG-01a through the REAL reducer ──────────────────────────────────
    //
    // The claim the fix rests on is a legality claim about the T7 event
    // sequence: a failure terminal that skips WorkCompleted must still reach
    // its terminal. That is checked against AgentRunReducer itself, not
    // against a description of it.

    @Test
    fun `the pre-fix tail sequence records a failed run as SUCCEEDED`() {
        val result = AgentRunReducer.reduceAll(
            listOf(
                AgentRunEvent.RunStarted("r1"),
                AgentRunEvent.ProviderAttemptStarted,
                AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
                AgentRunEvent.WorkCompleted,
                AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED, AgentTerminalReason.COMPLETED),
            ),
        )
        assertEquals(null, result.firstRejectedIndex)
        assertEquals(
            "the unconditional tail is what produced this terminal for a failed run",
            AgentRunPhase.SUCCEEDED,
            result.finalState.phase,
        )
    }

    @Test
    fun `the fixed tail still reaches a legal FAILED terminal`() {
        val loopExitedNormally = AgentLoopExitDecision.cleanCompletionFlag(terminalErrorSurfaced = true)
        val terminal = AgentLoopExitDecision.terminal(budgetStopReason = null, loopExitedNormally = loopExitedNormally)
        val reason = AgentLoopExitDecision.reason(budgetStopReason = null, loopExitedNormally = loopExitedNormally)
        assertEquals(AgentTerminal.FAILED, terminal)

        val result = AgentRunReducer.reduceAll(
            listOf(
                AgentRunEvent.RunStarted("r1"),
                AgentRunEvent.ProviderAttemptStarted,
                AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
                // The tail no longer emits WorkCompleted on this path; the
                // adapter's FAILED arm routes the state into FINALIZING.
                AgentRunEvent.ProcessInterrupted("terminal_error_surfaced"),
                AgentRunEvent.RunFinalized(terminal, reason),
            ),
        )
        assertEquals("the failure sequence must be legal", null, result.firstRejectedIndex)
        assertTrue(result.reachedTerminal)
        assertEquals(AgentRunPhase.FAILED, result.finalState.phase)
        assertEquals(AgentTerminalReason.EXECUTION_FAILED, result.finalState.terminalReason)
    }

    @Test
    fun `skipping the completion event alone would not finalize`() {
        // Why the fix keeps the fall-through + the adapter's FAILED arm: with
        // neither WorkCompleted nor a termination event the reducer refuses
        // RunFinalized (the run is still mid-EXECUTING_TOOLS).
        val result = AgentRunReducer.reduceAll(
            listOf(
                AgentRunEvent.RunStarted("r1"),
                AgentRunEvent.ProviderAttemptStarted,
                AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
                AgentRunEvent.RunFinalized(AgentTerminal.FAILED, AgentTerminalReason.EXECUTION_FAILED),
            ),
        )
        assertEquals(
            "RunFinalized from a running phase must be rejected",
            3,
            result.firstRejectedIndex,
        )
        assertEquals(AgentRunPhase.EXECUTING_TOOLS, result.finalState.phase)
    }

    // ── source-shape pin: the tail must ASK, not assume ────────────────────

    @Test
    fun `the clean-completion claim is guarded at its only write site`() {
        val lines = readRepoFile(ENGINE).split("\n")
        val assignments = lines.withIndex().filter { (_, l) -> l.trim() == "loopState.loopExitedNormally = true" }
        assertEquals(
            "AgentLoopEngine must claim a clean completion exactly once",
            1,
            assignments.size,
        )
        val assignIdx = assignments.single().index

        // The guard must be the statement immediately above the assignment
        // (comments and blank lines skipped), not an unconditional write.
        val guardIdx = (assignIdx - 1 downTo 0).first { lines[it].isNotBlank() && !lines[it].trim().startsWith("//") }
        val guard = lines[guardIdx].trim()
        assertTrue(
            "the clean-completion claim must be gated on the decision table, found: $guard",
            guard == "if (AgentLoopExitDecision.cleanCompletionFlag(loopState.terminalErrorSurfaced)) {",
        )

        // …and the T7 completion event must be inside that guard, not after it.
        val closeIdx = blockEnd(lines, guardIdx)
        val eventIdx = lines.indexOfFirst { it.contains("AgentRunEvent.WorkCompleted") }
        assertTrue("expected a WorkCompleted emission", eventIdx > 0)
        assertTrue(
            "WorkCompleted must only be emitted for a genuine clean completion",
            eventIdx in (guardIdx + 1) until closeIdx,
        )
    }

    @Test
    fun `negative control - an unguarded clean-completion claim is rejected`() {
        val src = readRepoFile(ENGINE)
        val mutated = src.replace(
            "if (AgentLoopExitDecision.cleanCompletionFlag(loopState.terminalErrorSurfaced)) {\n" +
                "                    loopState.loopExitedNormally = true",
            "loopState.loopExitedNormally = true",
        )
        assertTrue("mutation must change the source", mutated != src)
        expectProbeFailure(mutated)
    }

    @Test
    fun `negative control - a completion event outside the guard is rejected`() {
        val src = readRepoFile(ENGINE)
        val mutated = src.replace(
            "                    traceObserver.t7Reduce(AgentRunEvent.WorkCompleted)\n" +
                "                }\n                break",
            "                }\n" +
                "                traceObserver.t7Reduce(AgentRunEvent.WorkCompleted)\n" +
                "                break",
        )
        assertTrue("mutation must change the source", mutated != src)
        expectProbeFailure(mutated)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun expectProbeFailure(mutatedSource: String) {
        var caught = false
        try {
            assertClaimIsGuarded(mutatedSource)
        } catch (_: AssertionError) {
            caught = true
        }
        assertTrue("negative control: the probe passed on mutated source", caught)
    }

    /** The body of [the source-shape test], over an arbitrary source string. */
    private fun assertClaimIsGuarded(src: String) {
        val lines = src.split("\n")
        val assignments = lines.withIndex().filter { (_, l) -> l.trim() == "loopState.loopExitedNormally = true" }
        assertEquals("exactly one claim site", 1, assignments.size)
        val assignIdx = assignments.single().index
        val guardIdx = (assignIdx - 1 downTo 0).first { lines[it].isNotBlank() && !lines[it].trim().startsWith("//") }
        assertEquals(
            "the claim must be gated on the decision table",
            "if (AgentLoopExitDecision.cleanCompletionFlag(loopState.terminalErrorSurfaced)) {",
            lines[guardIdx].trim(),
        )
        val closeIdx = blockEnd(lines, guardIdx)
        val eventIdx = lines.indexOfFirst { it.contains("AgentRunEvent.WorkCompleted") }
        assertTrue("WorkCompleted out of guard", eventIdx in (guardIdx + 1) until closeIdx)
    }

    /** Index of the line closing the block opened on [openIdx]. */
    private fun blockEnd(lines: List<String>, openIdx: Int): Int {
        var depth = 0
        for (i in openIdx until lines.size) {
            depth += lines[i].count { it == '{' } - lines[i].count { it == '}' }
            if (depth <= 0) return i
        }
        throw AssertionError("unbalanced braces from line ${openIdx + 1}")
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val ENGINE = "app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"
    }
}
