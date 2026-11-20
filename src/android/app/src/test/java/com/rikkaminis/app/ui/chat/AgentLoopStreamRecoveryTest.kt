package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AgentToolDefinition
import com.rikkaminis.app.data.model.AgentToolParam
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.sandbox.offload.ModelStreamErrorException
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Drive the real engine with the typed errors the worker mailbox actually carries. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopStreamRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun provider() = ScriptedProvider(model = LLMModel(id = "m", displayName = "m", provider = "test"))
    private fun engine(h: FakeAgentLoopHost) = AgentLoopEngine(h, ChatAgentTraceObserver(
        traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> },
    ))
    private fun error(kind: String? = "transient") = ModelStreamErrorException("Stream error", hadChunks = true, kind = kind)
    private fun partialFailure(text: String, kind: String? = "transient") = TurnScript(
        listOf(LLMStreamChunk.Started, LLMStreamChunk.Text(text)), throwAfter = error(kind),
    )
    private fun done(text: String) = TurnScript(listOf(LLMStreamChunk.Started, LLMStreamChunk.Text(text), LLMStreamChunk.Finished("stop")))
    private fun tool() = AgentToolDefinition("shell", "test tool", mapOf("cmd" to AgentToolParam("string", "command")))
    private fun toolChunks(id: String) = listOf(
        LLMStreamChunk.Started, LLMStreamChunk.ToolUseStart(id, "shell"),
        LLMStreamChunk.ToolCallComplete(id, "shell", JSONObject("{\"cmd\":\"echo once\"}")),
    )

    @Test fun `typed transient partial answer is continued not regenerated`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(partialFailure("First useful segment"))
        h.scripts.add(done(" and the rest."))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(2, h.streamRequests.size)
        assertEquals("First useful segment and the rest.", h.finalAssistantText)
        assertEquals(1, h.persistedTurns.size)
        val next = h.streamMessageSnapshots[1]
        assertEquals("First useful segment", next.single { it.role == LLMMessage.Role.ASSISTANT }.content)
        assertTrue(next.last().content.contains("First useful segment"))
        assertTrue(h.agentHistory.none { ContinuationHistoryMerge.isContinuationReminder(it) })
        assertTrue(h.inlineErrors.isEmpty())
        assertTrue(h.autoRetryCalls.isEmpty())
    }

    @Test fun `network kind shares the same bounded partial recovery`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(partialFailure("Network head", "network")); h.scripts.add(done(" tail."))
        engine(h).runAgentLoop(provider(), null)
        assertEquals("Network head tail.", h.finalAssistantText)
        assertEquals(1, h.persistedTurns.size)
    }

    @Test fun `repeated partial failures stop at the existing continuation ceiling`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(partialFailure("one-")); h.scripts.add(partialFailure("two-")); h.scripts.add(partialFailure("three"))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(3, h.streamRequests.size)
        assertEquals("one-two-three", h.finalAssistantText)
        assertEquals(1, h.persistedTurns.size)
        assertTrue("a terminal error must be visible", h.inlineErrors.isNotEmpty())
        assertTrue(h.calls.none { it.startsWith("finalizeAtTurnLimit") })
    }

    @Test fun `blank transient attempt retries before any answer is continued`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Started), throwAfter = error()))
        h.scripts.add(done("Recovered."))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(2, h.streamRequests.size)
        assertEquals(listOf(1 to 0), h.autoRetryCalls)
        assertEquals("Recovered.", h.finalAssistantText)
        assertTrue(h.streamMessageSnapshots[1].none { it.role == LLMMessage.Role.ASSISTANT })
    }

    @Test fun `blank transient failures exhaust after three retries not an infinite loop`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        repeat(4) { h.scripts.add(TurnScript(listOf(LLMStreamChunk.Started), throwAfter = error())) }
        try { engine(h).runAgentLoop(provider(), null); fail("must fail after the bounded retries") }
        catch (e: ModelStreamErrorException) { assertEquals("transient", e.kind) }
        assertEquals(4, h.streamRequests.size)
        assertEquals(listOf(1 to 0, 2 to 0, 3 to 0), h.autoRetryCalls)
        assertTrue(h.toolExecutions.isEmpty())
    }

    @Test fun `half a tool invocation is discarded and only the regenerated complete call executes`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(tool()))
        h.scripts.add(TurnScript(listOf(
            LLMStreamChunk.Started, LLMStreamChunk.Text("Not a resumable text-only reply"),
            LLMStreamChunk.ToolUseStart("bad", "shell"),
            LLMStreamChunk.ToolInputDelta("bad", "{\"cmd\":\"danger"),
        ), throwAfter = error()))
        h.scripts.add(TurnScript(toolChunks("good") + LLMStreamChunk.Finished("tool_use")))
        h.scripts.add(done("Finished."))
        h.toolResults.add(ToolExecutionResult(output = "ok", success = true, toolTitle = "shell"))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(3, h.streamRequests.size)
        assertEquals(listOf("good"), h.toolExecutions.map { it.toolId })
        assertTrue(h.lastBlocks.none { it.id == "bad" })
        assertEquals(listOf(1 to 0), h.autoRetryCalls)
    }

    @Test fun `even a complete pending tool is not executed when its stream subsequently fails`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(tool()))
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("head")) + toolChunks("pending"), throwAfter = error()))
        h.scripts.add(done("Recovered without a tool."))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(2, h.streamRequests.size)
        assertTrue(h.toolExecutions.isEmpty())
        assertTrue(h.lastBlocks.none { it.id == "pending" })
        assertEquals("Recovered without a tool.", h.finalAssistantText)
    }

    @Test fun `already executed tools from earlier turns are not replayed by continuation`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost(agentTools = listOf(tool()))
        h.scripts.add(TurnScript(toolChunks("once") + LLMStreamChunk.Finished("tool_use")))
        h.toolResults.add(ToolExecutionResult(output = "one result", success = true, toolTitle = "shell"))
        h.scripts.add(partialFailure("Answer after the tool")); h.scripts.add(done(" finishes."))
        engine(h).runAgentLoop(provider(), null)
        assertEquals(listOf("once"), h.toolExecutions.map { it.toolId })
        assertEquals(1, h.persistedToolResults.size)
        assertTrue(h.streamMessageSnapshots.last().flatMap { it.contentParts }.any { it is AgentContentPart.ToolResult && it.id == "once" })
        assertEquals("Answer after the tool finishes.", h.finalAssistantText)
    }

    @Test fun `user stop is never converted into continuation or retry`() = runTest(dispatcher) {
        val h = FakeAgentLoopHost()
        h.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("Keep this")), throwAfter = CancellationException("user stopped")))
        h.scripts.add(done("Must not run"))
        try { engine(h).runAgentLoop(provider(), null); fail("expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(1, h.streamRequests.size)
        assertTrue(h.autoRetryCalls.isEmpty())
        assertTrue(h.providerSwitches.isEmpty())
    }

    @Test fun `unknown and permanent mid stream failures do not gain continuation`() = runTest(dispatcher) {
        for (kind in listOf(null, "provider", "invalid_key", "rate_limited", "future_error")) {
            val h = FakeAgentLoopHost()
            h.scripts.add(partialFailure("Do not silently resume this", kind))
            try { engine(h).runAgentLoop(provider(), null); fail("must surface kind=$kind") }
            catch (_: ModelStreamErrorException) { }
            assertEquals("kind=$kind", 1, h.streamRequests.size)
            assertTrue(h.autoRetryCalls.isEmpty())
        }
    }

    @Test fun `partial continuation policy refuses reasoning blanks and any tool activity`() {
        assertTrue(ChatStreamErrorPolicy.shouldContinuePartialReply(true, "answer", false))
        assertFalse(ChatStreamErrorPolicy.shouldContinuePartialReply(false, "answer", false))
        assertFalse(ChatStreamErrorPolicy.shouldContinuePartialReply(true, " \n ", false))
        assertFalse(ChatStreamErrorPolicy.shouldContinuePartialReply(true, "answer", true))
    }
}
