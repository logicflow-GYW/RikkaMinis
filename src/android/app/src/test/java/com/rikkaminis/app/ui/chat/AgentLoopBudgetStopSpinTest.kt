package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.tools.ToolExecutionResult
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
import org.junit.Before
import org.junit.Test

/**
 * [fix/r3-chat-1119 F-11] A budget gate that fires inside a TOOL EXECUTOR must
 * end the run, and a run that answered to completion must not be rewritten as
 * a budget failure.
 *
 * The two tool-executor gates (`tool_calls` / `shell_commands` in
 * `ChatPromptAndTools`) set `traceObserver.t7BudgetStopReason` and refuse the
 * call, but they live outside the turn loop. The loop never read the reason, so
 * it kept calling the model while the T7 reducer already stood in FINALIZING —
 * an empty spin that burns the remaining turns. And when the model then
 * answered normally (`loopExitedNormally = true`), the exit tail still matched
 * `t7BudgetStopReason != null` and pushed `finalizeBudgetStop` — banner +
 * FAILED terminal — over a finished answer.
 *
 * These tests drive the REAL loop through [FakeAgentLoopHost]; the budget
 * state is injected at the exact boundaries the production gates sit on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopBudgetStopSpinTest {

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

    private fun engine(host: FakeAgentLoopHost, observer: ChatAgentTraceObserver) =
        AgentLoopEngine(host, observer)

    private fun observer() = ChatAgentTraceObserver(
        traceDirResolver = { _ -> null },
        wallClockMs = { 0L },
        monotonicClockMs = { 0L },
        warn = { _, _ -> },
    )

    private fun model(id: String) = LLMModel(id = id, displayName = id, provider = "scripted")

    private fun provider() = ScriptedProvider(name = "p-main", model = model("m-main"))

    private fun usage() = LLMStreamChunk.Usage(LLMUsage(inputTokens = 100, outputTokens = 10))

    private fun toolDef(name: String) = AgentToolDefinition(
        name = name,
        description = "harness tool $name",
        parameters = mapOf("cmd" to AgentToolParam(type = "string", description = "command")),
    )

    private fun toolCallScript(id: String, name: String, args: String = "{\"cmd\":\"ls\"}") = TurnScript(
        listOf(
            LLMStreamChunk.Started,
            usage(),
            LLMStreamChunk.ToolUseStart(id, name),
            LLMStreamChunk.ToolInputDelta(id, args),
            LLMStreamChunk.ToolCallComplete(id, name, JSONObject(args)),
            LLMStreamChunk.Finished("tool_calls"),
        ),
    )

    private fun textScript(text: String, finish: String = "stop") = TurnScript(
        listOf(LLMStreamChunk.Started, usage(), LLMStreamChunk.Text(text), LLMStreamChunk.Finished(finish)),
    )

    // ── direction 1: a fired gate ends the run (no empty spin) ────────────

    @Test
    fun `a tool budget stop exits after the tool results without another model call`() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(toolDef("shell")))
        val observer = observer()
        // Turn 1 asks for a tool; the executor's budget gate fires (exactly
        // what ChatPromptAndTools does when the dimension is exhausted).
        h.scripts.add(toolCallScript("t1", "shell"))
        // A second script would be consumed by attempt #2 — its presence here
        // is what makes "no second model call" a real assertion instead of a
        // harness error.
        h.scripts.add(textScript("I would have kept going"))
        h.toolResults.add(ToolExecutionResult(output = "Error: Agent budget exhausted (tool_calls)", success = false, toolTitle = "Shell"))
        h.beforeExecuteTool = { observer.t7BudgetStopReason = "tool_call_limit" }

        engine(h, observer).runAgentLoop(provider(), systemPrompt = null)

        assertEquals(
            "the loop must NOT ask the model again after the budget gate fired",
            1,
            h.streamRequests.size,
        )
        assertEquals("the tool did run exactly once", 1, h.toolExecutions.size)
        assertTrue(
            "the run is finalized as a budget stop — the user is told WHY it stopped",
            h.calls.contains("finalizeBudgetStop(reason=tool_call_limit)"),
        )
        assertFalse(
            "a budget stop is not a MAX_AGENT_TURNS runaway",
            h.calls.any { it.startsWith("finalizeAtTurnLimit") },
        )
        assertTrue(
            "the tool result the model would have seen is still persisted (content delivery)",
            h.persistedToolResults.isNotEmpty(),
        )
    }

    // ── direction 2: a finished answer is not rewritten as a failure ──────

    @Test
    fun `a clean completion is not rewritten as a budget failure`() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(toolDef("shell")))
        val observer = observer()
        h.scripts.add(toolCallScript("t1", "shell"))
        h.scripts.add(textScript("all done"))
        h.toolResults.add(ToolExecutionResult(output = "ok", success = true, toolTitle = "Shell"))
        // A budget reason was observed during the run (a gate fired and was
        // refused) — but the model then answered to completion. The exit tail
        // must keep the normal exit.
        h.beforeCleanCompletion = { observer.t7BudgetStopReason = "tool_call_limit" }

        engine(h, observer).runAgentLoop(provider(), systemPrompt = null)

        assertEquals("both model calls happened", 2, h.streamRequests.size)
        assertEquals("the answer is delivered", "all done", h.finalAssistantText)
        assertFalse(
            "a finished answer must NOT be stamped as a budget failure",
            h.calls.any { it.startsWith("finalizeBudgetStop") },
        )
        assertFalse(
            "…nor as a runaway turn-limit stop",
            h.calls.any { it.startsWith("finalizeAtTurnLimit") },
        )
    }

}
