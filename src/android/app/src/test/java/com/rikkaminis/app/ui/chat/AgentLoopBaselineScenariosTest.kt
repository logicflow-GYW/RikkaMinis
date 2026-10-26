package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunBudgetCeilings
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Behavior baseline for [AgentLoopEngine.runAgentLoop] driven through a full
 * [AgentLoopHost] fake — the first tests in this repo that execute the real loop
 * body on the JVM.
 *
 * Contract: assertions describe required behavior (what the loop must do), never
 * "whatever the code currently does". Two families are load-bearing history:
 *  - turn-tail persistence (a turn must be persisted exactly once, with the parts
 *    of that turn only — F-T01-01 / turnStartBlockIndex slicing);
 *  - abort paths (cancellation, empty turn) must not persist a half turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopBaselineScenariosTest {

    /** Shared with runTest so Main and the test body agree on one scheduler
     *  (coroutines-test raises on mismatched schedulers). */
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── builders ──

    private fun engine(host: FakeAgentLoopHost): AgentLoopEngine = AgentLoopEngine(
        host,
        ChatAgentTraceObserver(
            traceDirResolver = { _ -> null },
            wallClockMs = { 0L },
            monotonicClockMs = { 0L },
            warn = { _, _ -> },
        ),
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "scripted")

    private fun provider(name: String = "p-main", modelId: String = "m-main"): ScriptedProvider =
        ScriptedProvider(name = name, model = model(modelId))

    private fun usage(input: Int = 100, output: Int = 10) =
        LLMStreamChunk.Usage(LLMUsage(inputTokens = input, outputTokens = output))

    private fun toolDef(name: String) = AgentToolDefinition(
        name = name,
        description = "harness tool $name",
        parameters = mapOf("cmd" to AgentToolParam(type = "string", description = "command")),
        required = listOf("cmd"),
    )

    private fun host(vararg tools: AgentToolDefinition) = FakeAgentLoopHost(agentTools = tools.toList())

    /**
     * Mirrors the production stream wrapper (callbackFlow): a model error reaches
     * the collector as a CancellationException whose cause is the LLMError, while
     * a genuine user stop is a CancellationException with no cause. The loop
     * distinguishes the two by `cause == null`.
     */
    private fun llmFailure(error: LLMError): CancellationException =
        CancellationException(error.message ?: "llm error").apply { initCause(error) }

    // ── t01: single turn text ──

    @Test
    fun t01_singleTurnText_streamsAndPersistsOnce() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.Text("Hello"),
                    LLMStreamChunk.Text(" world"),
                    LLMStreamChunk.Finished("stop"),
                )
            )
        )

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("exactly one provider attempt", 1, h.streamRequests.size)
        assertEquals("streamed text is complete", "Hello world", h.finalAssistantText)
        assertEquals("turn persisted exactly once", 1, h.persistedTurns.size)
        assertEquals("persisted parts = the turn text", listOf("text"), h.persistedTurns[0].partKinds)
        assertTrue("no inline error on a clean run", h.inlineErrors.isEmpty())
        assertFalse(
            "clean run must not hit a budget finalizer",
            h.calls.any { it.startsWith("finalizeBudgetStop") || it.startsWith("finalizeAtTurnLimit") },
        )
        assertTrue(
            "assistant text lands in history",
            h.agentHistory.any { it.role == LLMMessage.Role.ASSISTANT && it.content == "Hello world" },
        )
    }

    // ── t02: one tool round, then the follow-up answer ──

    @Test
    fun t02_toolRound_executesOnceThenAnswers() = runTest(mainDispatcher) {
        val h = host(toolDef("shell"))
        val p = provider()
        val args = JSONObject("{\"cmd\":\"ls\"}")
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.ToolUseStart("t1", "shell"),
                    LLMStreamChunk.ToolInputDelta("t1", "{\"cmd\":\"ls\"}"),
                    LLMStreamChunk.ToolCallComplete("t1", "shell", args),
                    LLMStreamChunk.Finished("tool_calls"),
                )
            )
        )
        h.scripts.add(
            TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("stop")))
        )
        h.toolResults.add(ToolExecutionResult(output = "files listed", success = true, toolTitle = "Shell"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("two provider attempts (tool round + answer)", 2, h.streamRequests.size)
        assertEquals("tool executed exactly once", 1, h.toolExecutions.size)
        assertEquals("shell", h.toolExecutions[0].name)
        assertTrue("tool result handed back to the model", h.persistedToolResults.isNotEmpty())
        assertEquals("both turns persisted", 2, h.persistedTurns.size)
        assertEquals("final answer text", "done", h.finalAssistantText)
        assertTrue("no inline error", h.inlineErrors.isEmpty())
    }

    // ── t03: empty turn must recover without persisting the empty shell ──

    @Test
    fun t03_emptyTurn_recoversWithoutPersistingEmptyShell() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Finished(null))))
        h.scripts.add(
            TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("recovered"), LLMStreamChunk.Finished("stop")))
        )

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("empty turn retried on the same provider", 2, h.streamRequests.size)
        assertEquals("only the recovered turn is persisted", 1, h.persistedTurns.size)
        assertEquals(listOf("text"), h.persistedTurns[0].partKinds)
        assertEquals("recovered", h.finalAssistantText)
    }

    // ── t04: transient network error → auto retry on the same provider ──

    @Test
    fun t04_networkError_retriesSameProvider() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.scripts.add(TurnScript(throwBefore = llmFailure(LLMError.NetworkError(RuntimeException("reset")))))
        h.scripts.add(
            TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("after retry"), LLMStreamChunk.Finished("stop")))
        )

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("retried exactly once", 2, h.streamRequests.size)
        assertTrue("retry surfaced to the UI", h.autoRetryCalls.isNotEmpty())
        assertEquals("after retry", h.finalAssistantText)
        assertEquals("only the successful turn persisted", 1, h.persistedTurns.size)
        assertTrue("no fallback trigger for a retryable error", h.fallbackTriggers.isEmpty())
    }

    // ── t05: rate limit → fallback to the next group member ──

    @Test
    fun t05_rateLimited_fallsBackToNextProvider() = runTest(mainDispatcher) {
        val h = host()
        val primary = provider("p-primary", "m-primary")
        val secondary = provider("p-secondary", "m-secondary")
        h.configEntries = listOf(
            ModelEntry(providerInstanceId = "inst-secondary", baseModel = model("m-secondary")),
        )
        h.scripts.add(TurnScript(throwBefore = llmFailure(LLMError.RateLimited())))
        h.scripts.add(
            TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("from secondary"), LLMStreamChunk.Finished("stop")))
        )

        engine(h).runAgentLoop(
            primary,
            systemPrompt = null,
            fallbackProviders = listOf(FallbackCandidate(secondary, "entry-secondary")),
        )

        assertEquals("two attempts across providers", 2, h.streamRequests.size)
        assertEquals("p-primary", h.streamRequests[0].providerName)
        assertEquals("p-secondary", h.streamRequests[1].providerName)
        assertTrue("fallback counter bumped", h.fallbackTriggers.isNotEmpty())
        assertTrue("provider switched in UI state", h.providerSwitches.contains("p-secondary"))
        assertEquals("from secondary", h.finalAssistantText)
        assertEquals("one persisted turn", 1, h.persistedTurns.size)
    }

    // ── t06: unknown tool is rejected before execution ──

    @Test
    fun t06_unknownTool_neverExecuted() = runTest(mainDispatcher) {
        val h = host(toolDef("shell"))
        val p = provider()
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.ToolCallComplete("t1", "no_such_tool", JSONObject("{}")),
                    LLMStreamChunk.Finished("tool_calls"),
                )
            )
        )
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("ok"), LLMStreamChunk.Finished("stop"))))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertTrue("unknown tool must not reach the executor", h.toolExecutions.isEmpty())
        assertEquals("loop still completes", 2, h.streamRequests.size)
        assertEquals("ok", h.finalAssistantText)
    }

    // ── t07: real cancellation propagates and persists nothing ──

    @Test
    fun t07_cancellation_propagatesAndPersistsNothing() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.scripts.add(
            TurnScript(
                chunks = listOf(LLMStreamChunk.Started, LLMStreamChunk.Text("half a sentence")),
                throwAfter = CancellationException("user stopped"),
            )
        )

        try {
            engine(h).runAgentLoop(p, systemPrompt = null)
            fail("cancellation must propagate out of runAgentLoop")
        } catch (e: CancellationException) {
            // expected: user stop is not a model error
        }

        assertTrue("half a turn must not be persisted", h.persistedTurns.isEmpty())
        assertFalse(
            "cancellation is not a budget stop",
            h.calls.any { it.startsWith("finalizeBudgetStop") },
        )
    }

    // ── t08: two tool rounds — persistence slices stay per-turn ──

    @Test
    fun t08_twoToolRounds_persistSlicesArePerTurn() = runTest(mainDispatcher) {
        val h = host(toolDef("shell"))
        val p = provider()
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.ToolUseStart("t1", "shell"),
                    LLMStreamChunk.ToolInputDelta("t1", "{\"cmd\":\"ls\"}"),
                    LLMStreamChunk.ToolCallComplete("t1", "shell", JSONObject("{\"cmd\":\"ls\"}")),
                    LLMStreamChunk.Finished("tool_calls"),
                )
            )
        )
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.ToolUseStart("t2", "shell"),
                    LLMStreamChunk.ToolInputDelta("t2", "{\"cmd\":\"pwd\"}"),
                    LLMStreamChunk.ToolCallComplete("t2", "shell", JSONObject("{\"cmd\":\"pwd\"}")),
                    LLMStreamChunk.Finished("tool_calls"),
                )
            )
        )
        h.scripts.add(
            TurnScript(listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text("all done"), LLMStreamChunk.Finished("stop")))
        )
        h.toolResults.add(ToolExecutionResult(output = "ls out", success = true, toolTitle = "Shell"))
        h.toolResults.add(ToolExecutionResult(output = "pwd out", success = true, toolTitle = "Shell"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("three attempts", 3, h.streamRequests.size)
        assertEquals("both tool calls executed", 2, h.toolExecutions.size)
        assertEquals("every turn persisted", 3, h.persistedTurns.size)
        assertTrue(
            "each persisted turn carries at least one part",
            h.persistedTurns.all { it.partKinds.isNotEmpty() },
        )
        assertTrue(
            "tool calls are persisted as tool_use parts",
            h.persistedTurns.any { turn -> turn.partKinds.any { it.startsWith("tool_use:shell") } },
        )
        assertTrue(
            "slice start never exceeds the block list it slices",
            h.buildTurnPartsCalls.all { (start, size) -> start <= size },
        )
        assertEquals("all done", h.finalAssistantText)
    }

    // ── t09 / t10: 压缩触发 ────────────────────────────────────────────────────

    /**
     * Turn-entry window block. Two fixed contracts live in this branch:
     *  1. compact receives the POST-offload decision figure, never the stale
     *     pre-offload one ([fix/offload-stale-token-compact] — 2026-09-25 field
     *     log: two runs already under their compact lines still paid a 92 s
     *     summary wait, because the gate read a figure from before the offload);
     *  2. a compact that actually folded MUST skip the hard trim
     *     ([fix/diff-audit-0904-F3] — trimming on the stale figure drops the very
     *     turns the new summary marker anchors on, so the summary is silently
     *     discarded and every later turn re-compacts), and the cleared figure
     *     must go to BOTH the loop state and the ViewModels.
     */
    @Test
    fun t09_autoCompactFolded_skipsHardTrimAndClearsStaleFigure() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.autoCompactVerdict = true
        h.offloadResultTokens = 117_470
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.Text("folded"),
                    LLMStreamChunk.Finished("stop"),
                )
            )
        )

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("one attempt", 1, h.streamRequests.size)
        assertEquals("the offload pass ran once on the turn-entry block", 1, h.offloadCalls.size)
        assertEquals("compact was asked once", 1, h.compactCalls.size)
        assertEquals(
            "compact receives the post-offload figure, not the stale one",
            117_470,
            h.compactCalls[0].second,
        )
        assertTrue("a folded compact must skip the last-resort trim (marker anchor)", h.trimCalls.isEmpty())
        assertTrue(
            "stale figure cleared to 0 after the fold",
            h.lastTurnContextTokensValues.contains(0),
        )
        assertEquals("folded", h.finalAssistantText)
    }

    /**
     * Control arm for t09. A compact that DECLINES to fold (still-compacting /
     * debounced / tail too small) preserves no anchor, so the hard trim must
     * still run — on the same decision figure. Without this arm, t09's
     * `trimCalls.isEmpty()` could not tell "correctly skipped" from "trim never
     * wired into the loop at all".
     */
    @Test
    fun t10_compactDeclined_hardTrimStillRunsOnTheSameFigure() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.autoCompactVerdict = false
        h.offloadResultTokens = 91_107
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.Text("trimmed"),
                    LLMStreamChunk.Finished("stop"),
                )
            )
        )

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("compact was asked once and declined", 1, h.compactCalls.size)
        assertEquals("no fold → the last-resort trim runs", 1, h.trimCalls.size)
        assertEquals(
            "trim receives the same decision figure compact received",
            h.compactCalls[0].second,
            h.trimCalls[0].second,
        )
        assertEquals(91_107, h.trimCalls[0].second)
        assertTrue("offload freed parts → stale figure cleared", h.lastTurnContextTokensValues.contains(0))
        assertEquals("trimmed", h.finalAssistantText)
    }

    // ── t11: turn 上限 ─────────────────────────────────────────────────────────

    /**
     * The run's turn ceiling (user runtime limits clamped by the session's
     * [AgentRunBudgetCeilings] entry) bounds the turn loop. Note the shape that
     * makes this the ONLY live turn-limit path: `consumeTurn` checks
     * `turnsUsed >= maxTurns` before incrementing, and the loop range is the
     * same number, so the in-loop turn_limit break is unreachable by
     * construction — the range exhausts first and the run finalizes as a
     * resumable runaway. [audit-1015 BUG-03D] pins the banner to THIS run's
     * figure, so the assert quotes the ceiling, not a live re-read.
     */
    @Test
    fun t11_turnCeiling_finalizesAsResumableRunawayNotError() = runTest(mainDispatcher) {
        val h = host(toolDef("shell"))
        val p = provider()
        AgentRunBudgetCeilings.register(
            h.activeSessionId,
            AgentRunBudgetCeilings.Ceiling(
                deadlineMs = 10L * 60 * 1000,
                maxTurns = 2,
                maxEstimatedTokens = Long.MAX_VALUE,
            ),
        )
        try {
            // Three tool rounds queued; the ceiling is 2, so the third must never fire.
            for (i in 0 until 3) {
                h.scripts.add(
                    TurnScript(
                        listOf(
                            LLMStreamChunk.Started,
                            usage(),
                            LLMStreamChunk.ToolUseStart("t$i", "shell"),
                            LLMStreamChunk.ToolInputDelta("t$i", "{\"cmd\":\"round-$i\"}"),
                            LLMStreamChunk.ToolCallComplete("t$i", "shell", JSONObject("{\"cmd\":\"round-$i\"}")),
                            LLMStreamChunk.Finished("tool_calls"),
                        )
                    )
                )
            }
            h.toolResults.add(ToolExecutionResult(output = "out 0", success = true, toolTitle = "Shell"))
            h.toolResults.add(ToolExecutionResult(output = "out 1", success = true, toolTitle = "Shell"))

            engine(h).runAgentLoop(p, systemPrompt = null)

            assertEquals("loop stopped AT the ceiling — no request past it", 2, h.streamRequests.size)
            assertEquals("both in-ceiling tool rounds executed", 2, h.toolExecutions.size)
            assertEquals("work already done before the stop is kept", 2, h.persistedTurns.size)
            assertEquals(
                "turn-limit finalize fires exactly once with THIS run's ceiling",
                1,
                h.calls.count { it == "finalizeAtTurnLimit(maxTurns=2)" },
            )
            assertFalse("a turn ceiling is not a budget-stop exit", h.calls.any { it.startsWith("finalizeBudgetStop") })
            assertTrue("a turn ceiling must not surface an inline error (it is resumable)", h.inlineErrors.isEmpty())
        } finally {
            AgentRunBudgetCeilings.clear(h.activeSessionId)
        }
    }

    // ── t12: 预算停止（deadline 门）─────────────────────────────────────────────

    /**
     * Deadline already reached at turn entry: the run must stop BEFORE the first
     * provider request and finalize through [AgentLoopHost.finalizeBudgetStop]
     * with reason "deadline_reached" — an explicit terminal, not the silent
     * "reply just stopped" exit that [fix/budget-stop-silent-exit] removed. The
     * ceiling is the only JVM-reachable way to expire a run: the monotonic clock
     * is frozen in this harness, so a run can never become expired mid-flight.
     */
    @Test
    fun t12_deadlineReached_stopsBeforeFirstRequestAndFinalizesExplicitly() = runTest(mainDispatcher) {
        val h = host()
        val p = provider()
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.Text("never sent"),
                    LLMStreamChunk.Finished("stop"),
                )
            )
        )
        AgentRunBudgetCeilings.register(
            h.activeSessionId,
            AgentRunBudgetCeilings.Ceiling(
                deadlineMs = 0L,
                maxTurns = 8,
                maxEstimatedTokens = Long.MAX_VALUE,
            ),
        )
        try {
            engine(h).runAgentLoop(p, systemPrompt = null)
        } finally {
            AgentRunBudgetCeilings.clear(h.activeSessionId)
        }

        assertEquals("the deadline gate fires before the first request", 0, h.streamRequests.size)
        assertEquals(
            "budget stop fires exactly once with the deadline reason",
            1,
            h.calls.count { it == "finalizeBudgetStop(reason=deadline_reached)" },
        )
        assertTrue("no turn ever started → nothing to persist", h.persistedTurns.isEmpty())
        assertFalse("a deadline stop is not the turn-limit runaway path", h.calls.any { it.startsWith("finalizeAtTurnLimit") })
        assertTrue("budget stop is explicit by contract, not an inline error", h.inlineErrors.isEmpty())
    }

    // ── t13: 真实取消落在工具执行中 ─────────────────────────────────────────────

    /**
     * A user stop landing WHILE A TOOL IS IN FLIGHT — a different exit than t07
     * (stop during streaming). The engine has no catch around the sequential
     * `host.executeTool` call, and this pins that: kotlinx's CancellationException
     * IS an Exception, so a `catch (e: Exception)` added there later would swallow
     * the stop and keep the loop running after the user pressed stop. Required
     * behavior: propagate out of runAgentLoop, leave exactly the turn already
     * made durable by [fix/early-turn-persist] (no tool result, no rewrite),
     * reach no finalizer.
     */
    @Test
    fun t13_cancellationDuringToolExecution_propagatesAndPersistsNothing() = runTest(mainDispatcher) {
        val h = host(toolDef("shell"))
        val p = provider()
        h.scripts.add(
            TurnScript(
                listOf(
                    LLMStreamChunk.Started,
                    usage(),
                    LLMStreamChunk.ToolUseStart("t1", "shell"),
                    LLMStreamChunk.ToolInputDelta("t1", "{\"cmd\":\"sleep\"}"),
                    LLMStreamChunk.ToolCallComplete("t1", "shell", JSONObject("{\"cmd\":\"sleep\"}")),
                    LLMStreamChunk.Finished("tool_calls"),
                )
            )
        )
        // cause == null — a real job cancellation, not a wrapped model error.
        h.toolThrowables.add(CancellationException("user stopped"))

        try {
            engine(h).runAgentLoop(p, systemPrompt = null)
            fail("cancellation during tool execution must propagate out of runAgentLoop")
        } catch (e: CancellationException) {
            // expected exit
        }

        assertEquals("the tool really was dispatched", 1, h.toolExecutions.size)
        // [fix/early-turn-persist] (engine:3367) — the assistant row is made
        // durable BEFORE the tools run, precisely so a stop/kill inside the tool
        // window cannot erase a turn the user already read. The fix promises the
        // worst case is "a missing tool outcome on a row whose text is intact";
        // these four asserts pin both halves of that promise.
        assertEquals("the tool-call turn was already durable when the stop landed", 1, h.persistedTurns.size)
        assertEquals("the durable row carries the tool_use block", listOf("tool_use:shell"), h.persistedTurns[0].partKinds)
        assertTrue("a cancelled tool produces no persisted tool result", h.persistedToolResults.isEmpty())
        assertTrue("no terminal rewrite of the early row happened after the stop", h.updatedPersistedTurns.isEmpty())
        assertFalse(
            "a cancelled run must not reach any budget finalizer",
            h.calls.any { it.startsWith("finalizeBudgetStop") || it.startsWith("finalizeAtTurnLimit") },
        )
        assertTrue("a user stop is not an error banner", h.inlineErrors.isEmpty())
    }
}
