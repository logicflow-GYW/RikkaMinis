package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.EnvVarPrivacyStore
import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Runs the ENTIRE real Engine, and checks its next actual host request. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatToolPrivacyEngineTest {
    private val dispatcher = StandardTestDispatcher()
    private var privacy = true
    @Before fun setup() { privacy = EnvVarPrivacyStore.isEnabled; EnvVarPrivacyStore.setEnabled(true); Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain(); EnvVarPrivacyStore.setEnabled(privacy) }
    private fun events(id: String): List<LLMStreamChunk> {
        val source = AnthropicReplay.Source.fromActualRequest("https://api.anthropic.com/v1/messages", "claude-haiku-4-5", "privacy-fixture")!!
        val replay = AnthropicReplayCodec.complete(source, Json.parseToJsonElement("""[{"type":"thinking","thinking":"","signature":"PRIVATE-CANARY"},{"type":"tool_use","id":"$id","name":"read_probe","input":{}}]""") as JsonArray)
        return listOf(LLMStreamChunk.ToolUseStart(id, "read_probe"), LLMStreamChunk.ToolCallComplete(id, "read_probe", JSONObject()), LLMStreamChunk.Finished("tool_use", anthropicReplay = replay))
    }
    private fun observer() = ChatAgentTraceObserver(traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> })
    @Test fun actualRedactedExecutionNeverPublishesItsReplayButNextCleanGroupDoes() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "probe", emptyMap())))
        fake.toolResults.add(ToolExecutionResult("normal result", true, privacyRedacted = true))
        fake.toolResults.add(ToolExecutionResult("normal result", true))
        fake.scripts.add(TurnScript(events("a")))
        fake.scripts.add(TurnScript(events("b")))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
        AgentLoopEngine(fake, observer()).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertEquals(2, fake.toolExecutions.size)
        assertNull(fake.updatedReplays[0].second)
        assertNull(fake.persistedReplays[0].second)
        assertNotNull(fake.updatedReplays[1].second)
        assertTrue(fake.streamMessageSnapshots[1].none { it.anthropicReplay != null })
        assertEquals(1, fake.streamMessageSnapshots[2].count { it.anthropicReplay != null })
    }
    @Test fun fakePlaceholderWithoutRealHitDoesNotInvalidateReplay() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "probe", emptyMap())))
        fake.toolResults.add(ToolExecutionResult("documentation [MINIS_REDACTED]", true))
        fake.scripts.add(TurnScript(events("normal")))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
        AgentLoopEngine(fake, observer()).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertNotNull(fake.updatedReplays.single().second)
    }
    @Test fun privacyOffKeepsTheRealCopyAndTheReplayEligible() = runTest(dispatcher) {
        // [feat/privacy-mode-toggle-1124] With the switch OFF nothing model-facing
        // was rewritten, so the stored copy is the real one and the capsule still
        // matches what the provider saw. Storing a diagnostics-masked copy here
        // used to hand the next turn a placeholder instead of the value.
        EnvVarPrivacyStore.setEnabled(false)
        val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "probe", emptyMap())))
        fake.toolResults.add(ToolExecutionResult("real result bytes", true))
        fake.scripts.add(TurnScript(events("off")))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
        AgentLoopEngine(fake, observer()).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        val hot = fake.agentHistory.single { message -> message.contentParts.any { it is AgentContentPart.ToolResult } }
        assertEquals("real result bytes", (hot.contentParts.single() as AgentContentPart.ToolResult).content)
        assertNotNull(fake.updatedReplays.single().second)
        EnvVarPrivacyStore.setEnabled(true)
    }
    @Test fun privacyOnStillDropsTheReplayForARedactedExecution() = runTest(dispatcher) {
        // The companion arm: the switch being ON must not weaken the existing
        // rule that a rewritten payload invalidates its capsule.
        EnvVarPrivacyStore.setEnabled(true)
        val fake = FakeAgentLoopHost(agentTools = listOf(AgentToolDefinition("read_probe", "probe", emptyMap())))
        fake.toolResults.add(ToolExecutionResult("masked result bytes", true, privacyRedacted = true))
        fake.scripts.add(TurnScript(events("on")))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Text("done"), LLMStreamChunk.Finished("end_turn"))))
        AgentLoopEngine(fake, observer()).runAgentLoop(ScriptedProvider(model = LLMModel("claude-haiku-4-5", "fixture", "Anthropic")), null)
        assertNull(fake.updatedReplays.single().second)
    }
}