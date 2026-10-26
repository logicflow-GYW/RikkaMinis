package com.rikkaminis.app.ui.chat

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
}
