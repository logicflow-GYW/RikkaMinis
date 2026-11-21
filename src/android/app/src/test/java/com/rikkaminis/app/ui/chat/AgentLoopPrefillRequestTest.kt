package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.conversation.ContextEstimator
import com.rikkaminis.app.data.model.*
import com.rikkaminis.app.provider.LLMProvider
import com.rikkaminis.app.provider.anthropic.AnthropicProvider
import com.rikkaminis.app.sandbox.offload.TranscriptCommitHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Entire production engine + unchanged FakeHost, with real public provider
 * entry/builder recording the exact outbound history before each fake stream.
 * No Android worker or paid upstream is involved. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopPrefillRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    @Before fun setup() { Dispatchers.setMain(dispatcher); server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown(); Dispatchers.resetMain() }

    private fun provider(id: String, cap: Int = 8192) = AnthropicProvider("fixture-key",
        LLMModel(id, id, "Anthropic", maxOutputTokens = cap, supportsReasoning = true),
        server.url("/").toString().trimEnd('/'))
    private fun segment(text: String, reason: String) = TurnScript(listOf(
        LLMStreamChunk.Started, LLMStreamChunk.Text(text), LLMStreamChunk.Finished(reason)))
    private fun engine(host: AgentLoopHost) = AgentLoopEngine(host, ChatAgentTraceObserver(
        traceDirResolver = { null }, wallClockMs = { 0L }, monotonicClockMs = { 0L }, warn = { _, _ -> }))

    private inner class WireHost(
        val fake: FakeAgentLoopHost,
        val cap: (LLMProvider) -> Int = { 8192 },
        val window: (() -> Int)? = null,
        val level: (() -> ThinkingLevel)? = null,
    ) : AgentLoopHost by fake {
        val bodies = mutableListOf<JSONObject>()
        override val thinkingLevel: ThinkingLevel get() = level?.invoke() ?: fake.thinkingLevel
        override fun effectiveContextWindowTokens(): Int = window?.invoke() ?: 200_000
        override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int) = cap(provider)
        override fun streamChatTurnOffloaded(provider: LLMProvider, messages: List<LLMMessage>, systemPrompt: String?,
            maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel, turnId: String?, transcriptCommit: TranscriptCommitHandle?): Flow<LLMStreamChunk> = flow {
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"fixture"}],"stop_reason":"end_turn"}"""))
            provider.sendMessage(messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel)
            bodies.add(JSONObject(server.takeRequest().body.readUtf8()))
            emitAll(fake.streamChatTurnOffloaded(provider, messages, systemPrompt, maxTokens, temperature,
                imageParts, tools, thinkingLevel, turnId, transcriptCommit))
        }
    }
    private fun lastRole(body: JSONObject): String = body.getJSONArray("messages").let {
        it.getJSONObject(it.length()-1).getString("role") }
    private fun assertOneAnswer(fake: FakeAgentLoopHost, expected: String) {
        assertEquals(1, fake.persistedTurns.size)
        assertEquals(expected, fake.finalAssistantText)
        assertEquals(listOf(expected), fake.agentHistory.filter { it.role == LLMMessage.Role.ASSISTANT }.map { it.content })
        assertTrue(fake.agentHistory.none { ContinuationHistoryMerge.isContinuationReminder(it) })
    }

    @Test fun newGenerationOffStillSendsUserReminder() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals("user", lastRole(host.bodies[1]))
        assertEquals("disabled", host.bodies[1].getJSONObject("thinking").getString("type"))
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun legacyHighThinkingSendsReminderAndTwoWallsNeverStackIt() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+middle", "max_tokens"))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        for (body in host.bodies.drop(1)) {
            assertEquals("user", lastRole(body))
            assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
            assertEquals(1, Regex("cut off mid-sentence").findAll(body.getJSONArray("messages").toString()).count())
        }
        assertOneAnswer(fake, "head+middle+tail")
    }

    @Test fun legacyOffAndAutoKeepLegalAssistantFinalPrefill() = runTest(dispatcher) {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.AUTO)) {
            val fake = FakeAgentLoopHost(thinkingLevel = level)
            fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
            val host = WireHost(fake)
            engine(host).runAgentLoop(provider("claude-3-7-sonnet-20250219"), null)
            assertEquals("assistant", lastRole(host.bodies[1])); assertFalse(host.bodies[1].has("thinking"))
            assertOneAnswer(fake, "head+tail")
        }
    }

    @Test fun nextRequestCapNotPreviousWallCapDeterminesThinkingAndPrefill() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake, cap = { if (fake.streamRequests.isEmpty()) 512 else 4096 })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertFalse(host.bodies[0].has("thinking"))
        assertEquals(4096, host.bodies[1].getInt("max_tokens"))
        assertEquals("enabled", host.bodies[1].getJSONObject("thinking").getString("type"))
        assertEquals("user", lastRole(host.bodies[1])); assertOneAnswer(fake, "head+tail")
    }

    @Test fun nextSmallHighBudgetKeepsLegalPrefillWithoutThinking() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake, cap = { if (fake.streamRequests.isEmpty()) 4096 else 512 })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertEquals("enabled", host.bodies[0].getJSONObject("thinking").getString("type"))
        assertEquals(512, host.bodies[1].getInt("max_tokens"))
        assertFalse(host.bodies[1].has("thinking")); assertEquals("assistant", lastRole(host.bodies[1]))
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun preflightShrinkTo1024ReenablesLegacyPrefillWithActualThinkingAbsent() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        val reminder = lengthWallReminder("head")
        val host = WireHost(fake, window = {
            if (fake.streamRequests.isEmpty()) 200_000 else {
                val projected = fake.agentHistory + LLMMessage(LLMMessage.Role.USER, reminder,
                    contentParts = listOf(AgentContentPart.Text(reminder)))
                ContextEstimator.estimateRequestTokens(projected, null, emptyList()).toInt() +
                    ContextEstimator.PREFLIGHT_MARGIN_TOKENS.toInt() + 1024
            }
        })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertEquals(1024, host.bodies[1].getInt("max_tokens"))
        assertFalse(host.bodies[1].has("thinking")); assertEquals("assistant", lastRole(host.bodies[1]))
        assertOneAnswer(fake, "head+tail")
    }

    private suspend fun fallbackCase(first: String, second: String, roles: List<String>) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens"))
        fake.scripts.add(TurnScript(throwBefore = CancellationException("rate").apply { initCause(LLMError.RateLimited()) }))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider(first), null, fallbackProviders = listOf(FallbackCandidate(provider(second), "fallback")))
        assertEquals(listOf(first, first, second), host.bodies.map { it.getString("model") })
        assertEquals(roles, host.bodies.map { lastRole(it) }); assertOneAnswer(fake, "head+tail")
    }
    @Test fun fallbackFromLegacyToNewReplacesPrefillWithReminder() = runTest(dispatcher) {
        fallbackCase("claude-haiku-4-5", "claude-sonnet-4-6", listOf("user", "assistant", "user"))
    }
    @Test fun fallbackFromNewToLegacyRemovesRequestOnlyReminder() = runTest(dispatcher) {
        fallbackCase("claude-sonnet-4-6", "claude-haiku-4-5", listOf("user", "user", "assistant"))
    }

    @Test fun sameCapturedThinkingInputFeedsQueryAndActualRequest() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        var reads = 0
        val host = WireHost(fake, level = { reads++; if (reads == 3) ThinkingLevel.HIGH else ThinkingLevel.OFF })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertEquals("enabled", host.bodies[1].getJSONObject("thinking").getString("type"))
        assertEquals("user", lastRole(host.bodies[1])); assertEquals(3, reads)
        assertOneAnswer(fake, "head+tail")
    }
}
