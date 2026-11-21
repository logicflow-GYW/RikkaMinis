package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.R
import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.provider.ProviderTerminalCompatibilityTest
import com.rikkaminis.app.provider.anthropic.AnthropicProvider
import com.rikkaminis.app.sandbox.offload.ChatStreamJsonl
import com.rikkaminis.app.tools.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Full Engine, unchanged FakeAgentLoopHost, real Anthropic SSE and worker codec.
 * Only the transport/Android/database leaves are replaced; no paid upstream. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextWindowTerminalTest {
    private val dispatcher = StandardTestDispatcher()
    private val reason = "model_context_window_exceeded"
    private val partial = "Recoverable partial text\n第二段仍须完整保留。"
    private val tool = AgentToolDefinition("fixture_echo", "inert fixture",
        mapOf("value" to AgentToolParam("string", "value")))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun identity() = ScriptedProvider("fixture", LLMModel("fixture", "fixture", "fixture"))

    private suspend fun actualTextTurn(text: String, stop: String): List<LLMStreamChunk> {
        val server = MockWebServer(); server.start()
        try {
            val events = listOf(
                """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":0}}}""",
                JSONObject().put("type", "content_block_delta")
                    .put("delta", JSONObject().put("type", "text_delta").put("text", text)).toString(),
                JSONObject().put("type", "message_delta")
                    .put("delta", JSONObject().put("stop_reason", stop)).toString(),
                """{"type":"message_delta","delta":{},"usage":{"output_tokens":2}}""",
                """{"type":"message_stop"}""",
            )
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events.joinToString("") { "data: $it\n\n" }))
            val chunks = mutableListOf<LLMStreamChunk>()
            AnthropicProvider("fixture-key", LLMModel("claude-haiku-4-5-20251001", "fixture", "anthropic"),
                server.url("/").toString().trimEnd('/'))
                .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "fixture")), null, 2048)
                .collect { chunks.add(it) }
            val decoded = chunks.map { ChatStreamJsonl.decode(ChatStreamJsonl.encode(it))!! }
            val finish = decoded.filterIsInstance<LLMStreamChunk.Finished>().single()
            assertEquals(stop, finish.stopReason)
            assertFalse("a declared context/output reason is not EOF", finish.truncated)
            assertEquals(text, decoded.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
            return decoded
        } finally { server.shutdown() }
    }

    private suspend fun runWithTrace(h: FakeAgentLoopHost): List<JSONObject> {
        val dir = Files.createTempDirectory("context-terminal-").toFile()
        try {
            val observer = ChatAgentTraceObserver(traceDirResolver = { dir },
                wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> })
            AgentLoopEngine(h, observer).runAgentLoop(identity(), null,
                fallbackProviders = listOf(FallbackCandidate(identity(), "unused-fallback")))
            return dir.listFiles()!!.single().readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        } finally { dir.deleteRecursively() }
    }
    private fun assertFailed(h: FakeAgentLoopHost, trace: List<JSONObject>) {
        assertEquals(listOf(h.string(R.string.error_context_window_exceeded)), h.inlineErrors)
        assertTrue("no EOF/empty/provider retry", h.autoRetryCalls.isEmpty())
        assertTrue("context is not a fallback attempt", h.fallbackTriggers.isEmpty())
        assertTrue(h.calls.none { it.startsWith("finalizeAtTurnLimit") || it.startsWith("finalizeBudgetStop") })
        val end = trace.single { it.optString("type") == "trace_end" }
        // Production errorLabel returns this only when terminalErrorSurfaced=true;
        // Failed/ExecutionFailed (with no budget) proves loopExitedNormally=false.
        assertEquals("Failed", end.getString("terminal_state"))
        assertEquals("all_fallbacks_exhausted", end.getString("terminal_reason"))
        assertEquals("terminal_error_surfaced", end.getString("error"))
        val persisted = h.calls.indexOfLast { it.startsWith("persistAssistantTurn#") }
        val banner = h.calls.indexOfLast { it.startsWith("setInlineError(") }
        assertTrue("the banner follows this turn's own durable row", persisted >= 0 && banner > persisted)
    }
    private fun assertSucceeded(h: FakeAgentLoopHost, trace: List<JSONObject>) {
        assertTrue(h.inlineErrors.isEmpty())
        val end = trace.single { it.optString("type") == "trace_end" }
        assertEquals("Succeeded", end.getString("terminal_state"))
        assertEquals("completed_normally", end.getString("terminal_reason"))
        assertFalse(end.has("error"))
    }

    @Test fun partialContextIsVisibleFailureWithoutRetryAndKeepsEveryCharacter() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(TurnScript(actualTextTurn(partial, reason)))
        val trace = runWithTrace(h)
        println("CONTEXT_PARTIAL requests=${h.streamRequests.size} text=${h.finalAssistantText.length} rows=${h.persistedTurns.size} errors=${h.inlineErrors.size} end=${trace.last()}")
        assertEquals(1, h.streamRequests.size)
        assertTrue(h.toolExecutions.isEmpty())
        assertEquals(partial, h.finalAssistantText)
        assertEquals(listOf(partial), h.agentHistory.filter { it.role == LLMMessage.Role.ASSISTANT }.map { it.content })
        assertEquals(1, h.persistedTurns.size)
        assertEquals(listOf("text"), h.persistedTurns.single().partKinds)
        assertFailed(h, trace)
    }

    @Test fun emptyContextPersistsItsOwnRowAndFailsWithoutEmptyRecovery() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(TurnScript(actualTextTurn("", reason)))
        val trace = runWithTrace(h)
        println("CONTEXT_EMPTY requests=${h.streamRequests.size} rows=${h.persistedTurns.size} errors=${h.inlineErrors.size} end=${trace.last()}")
        assertEquals(1, h.streamRequests.size)
        assertTrue(h.toolExecutions.isEmpty())
        assertEquals("", h.finalAssistantText)
        assertEquals(1, h.persistedTurns.size)
        assertEquals(listOf("text"), h.persistedTurns.single().partKinds)
        assertFailed(h, trace)
    }

    @Test fun ordinaryEndTurnAndStopStillCompleteNormally() = runTest(dispatcher) {
        for (stop in listOf("end_turn", "stop")) {
            val h = FakeAgentLoopHost()
            h.scripts.add(TurnScript(actualTextTurn("complete", stop)))
            assertSucceeded(h, runWithTrace(h))
            assertEquals(1, h.streamRequests.size)
            assertEquals("complete", h.finalAssistantText)
        }
    }

    @Test fun maxTokensStillContinuesAndPersistsOneAnswer() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(TurnScript(actualTextTurn("head", "max_tokens")))
        h.scripts.add(TurnScript(actualTextTurn("+tail", "end_turn")))
        assertSucceeded(h, runWithTrace(h))
        assertEquals(2, h.streamRequests.size)
        assertEquals("head+tail", h.finalAssistantText)
        assertEquals(1, h.persistedTurns.size)
    }

    @Test fun contextToolRejectionAndPairingKeepExistingNextTurnBehavior() = runTest(dispatcher) {
        for (closed in listOf(true, false)) {
            val h = FakeAgentLoopHost(agentTools = listOf(tool))
            val chunks = ProviderTerminalCompatibilityTest().anthropicToolTurn(reason, closed)
                .map { ChatStreamJsonl.decode(ChatStreamJsonl.encode(it))!! }
            h.scripts.add(TurnScript(chunks))
            h.scripts.add(TurnScript(actualTextTurn("replanned answer", "end_turn")))
            assertSucceeded(h, runWithTrace(h))
            assertEquals(2, h.streamRequests.size)
            assertTrue(h.toolExecutions.isEmpty())
            val parts = h.agentHistory.flatMap { it.contentParts }
            assertEquals(1, parts.filterIsInstance<AgentContentPart.ToolUse>().count { it.id == "fixture-id" })
            val result = parts.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "fixture-id" }
            assertTrue(result.isError)
            assertTrue(result.content.contains("context window"))
        }
    }

    @Test fun emptyContextAfterSuccessfulToolCannotStampTheEarlierAssistantRow() = runTest(dispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(tool))
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.ToolUseStart("once", tool.name),
            LLMStreamChunk.ToolCallComplete("once", tool.name, JSONObject().put("value", "fixture")),
            LLMStreamChunk.Finished("tool_use"))))
        h.toolResults.add(ToolExecutionResult(output = "success", success = true, toolTitle = tool.name))
        h.scripts.add(TurnScript(actualTextTurn("", reason)))
        val trace = runWithTrace(h)
        println("CONTEXT_AFTER_TOOL requests=${h.streamRequests.size} rows=${h.persistedTurns.map { it.dbId }} errors=${h.inlineErrors.size} end=${trace.last()}")
        assertEquals(2, h.streamRequests.size)
        assertEquals(listOf("once"), h.toolExecutions.map { it.toolId })
        assertEquals(2, h.persistedTurns.size)
        assertTrue(h.persistedTurns.first().partKinds.any { it.startsWith("tool_use:") })
        assertEquals(listOf("text"), h.persistedTurns.last().partKinds)
        assertNotEquals(h.persistedTurns.first().dbId, h.persistedTurns.last().dbId)
        assertFailed(h, trace)
    }
}
