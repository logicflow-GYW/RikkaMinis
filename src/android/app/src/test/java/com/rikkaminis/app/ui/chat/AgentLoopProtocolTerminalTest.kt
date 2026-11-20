package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.provider.ProviderTerminalCompatibilityTest
import com.rikkaminis.app.sandbox.offload.ChatStreamErrorPolicyKind
import com.rikkaminis.app.sandbox.offload.ChatStreamJsonl
import com.rikkaminis.app.sandbox.offload.ModelStreamErrorException
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real engine + unchanged fake host. Every tool name/payload here is inert. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopProtocolTerminalTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun provider(id: String) = ScriptedProvider(name = id, model = LLMModel(id, id, "fixture"))
    private fun engine(h: FakeAgentLoopHost) = AgentLoopEngine(h, ChatAgentTraceObserver(
        traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> },
    ))
    private val tool = AgentToolDefinition("fixture_echo", "inert fixture", mapOf("value" to AgentToolParam("string", "value")))
    private fun done() = TurnScript(listOf(LLMStreamChunk.Text("Complete answer"), LLMStreamChunk.Finished("stop")))
    private fun wireError(failure: Throwable, hadChunks: Boolean): ModelStreamErrorException {
        val line = ChatStreamJsonl.errorLine(failure.message.orEmpty(), ChatStreamErrorPolicyKind.of(failure))
        assertTrue(ChatStreamJsonl.isError(line))
        return ModelStreamErrorException(ChatStreamJsonl.errorMessage(line), hadChunks, ChatStreamJsonl.errorKind(line))
    }

    @Test fun `actual Gemini terminal failure is visible not a clean engine success`() = runTest(dispatcher) {
        for (reason in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL")) {
            val (chunks, failure) = ProviderTerminalCompatibilityTest().streamGemini(reason, "partial")
            assertNotNull(failure)
            val h = FakeAgentLoopHost()
            h.scripts.add(TurnScript(chunks, throwAfter = wireError(failure!!, chunks.isNotEmpty())))
            try { engine(h).runAgentLoop(provider("first"), null); fail("must fail visibly") }
            catch (e: ModelStreamErrorException) { assertEquals("provider", e.kind) }
            assertEquals(1, h.streamRequests.size)
            assertTrue(h.autoRetryCalls.isEmpty())
            assertTrue(h.toolExecutions.isEmpty())
        }
    }

    @Test fun `actual Gemini protocol failure takes existing group fallback without replaying earlier inert tools`() = runTest(dispatcher) {
        val (chunks, failure) = ProviderTerminalCompatibilityTest().streamGemini("MALFORMED_FUNCTION_CALL")
        assertNotNull(failure)
        val h = FakeAgentLoopHost(agentTools = listOf(tool))
        h.configEntries = listOf(ModelEntry(providerInstanceId = "fixture-instance", baseModel = LLMModel("second", "second", "fixture")))
        h.scripts.add(TurnScript(listOf(
            LLMStreamChunk.ToolUseStart("once", tool.name),
            LLMStreamChunk.ToolCallComplete("once", tool.name, JSONObject().put("value", "fixture")),
            LLMStreamChunk.Finished("tool_use"),
        )))
        h.toolResults.add(ToolExecutionResult(output = "inert result", success = true, toolTitle = tool.name))
        h.scripts.add(TurnScript(chunks, throwAfter = wireError(failure!!, chunks.isNotEmpty())))
        h.scripts.add(done())
        engine(h).runAgentLoop(provider("first"), null, fallbackProviders = listOf(FallbackCandidate(provider("second"), "fixture-entry")))
        assertEquals(listOf("once"), h.toolExecutions.map { it.toolId })
        assertEquals(listOf("first", "first", "second"), h.streamRequests.map { it.providerName })
        assertTrue(h.autoRetryCalls.isEmpty())
        assertTrue(h.fallbackTriggers.isNotEmpty())
        assertEquals("Complete answer", h.finalAssistantText)
    }

    @Test fun `actual Anthropic context truncation never reaches fake executor and keeps tool result pairing`() = runTest(dispatcher) {
        for (closed in listOf(true, false)) {
            val chunks = ProviderTerminalCompatibilityTest().anthropicToolTurn("model_context_window_exceeded", closed)
                .map { ChatStreamJsonl.decode(ChatStreamJsonl.encode(it))!! }
            val h = FakeAgentLoopHost(agentTools = listOf(tool))
            h.scripts.add(TurnScript(chunks)); h.scripts.add(done())
            engine(h).runAgentLoop(provider("first"), null)
            assertTrue(h.toolExecutions.isEmpty())
            val parts = h.agentHistory.flatMap { it.contentParts }
            assertEquals(1, parts.filterIsInstance<AgentContentPart.ToolUse>().count { it.id == "fixture-id" })
            val result = parts.filterIsInstance<AgentContentPart.ToolResult>().single { it.id == "fixture-id" }
            assertEquals(tool.name, result.name)
            assertTrue(result.isError)
            assertTrue(result.content.contains("context window"))
            assertEquals(2, h.streamRequests.size)
        }
    }

    @Test fun `a complete normal inert call still executes once`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(tool))
        h.scripts.add(TurnScript(listOf(
            LLMStreamChunk.ToolUseStart("complete", tool.name),
            LLMStreamChunk.ToolCallComplete("complete", tool.name, JSONObject().put("value", "fixture")),
            LLMStreamChunk.Finished("tool_use"),
        )))
        h.scripts.add(done())
        engine(h).runAgentLoop(provider("first"), null)
        assertEquals(listOf("complete"), h.toolExecutions.map { it.toolId })
    }
}
