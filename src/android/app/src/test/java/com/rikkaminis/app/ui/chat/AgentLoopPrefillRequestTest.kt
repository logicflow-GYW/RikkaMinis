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
        val onTrim: (() -> Unit)? = null,
        val onCompact: (() -> Boolean)? = null,
        val onOffload: ((Boolean) -> Int?)? = null,
        val effectiveHistory: (() -> List<LLMMessage>)? = null,
    ) : AgentLoopHost by fake {
        val bodies = mutableListOf<JSONObject>()
        val requestSnapshots = mutableListOf<List<LLMMessage>>()
        override fun offloadContextIfNeeded(contextWindow: Int, lastContextTokens: Int, force: Boolean): Int? {
            val original = fake.offloadContextIfNeeded(contextWindow, lastContextTokens, force)
            return onOffload?.invoke(force) ?: original
        }
        override suspend fun maybeAutoCompactInLoop(contextWindow: Int, lastContextTokens: Int): Boolean {
            val original = fake.maybeAutoCompactInLoop(contextWindow, lastContextTokens)
            return onCompact?.invoke() ?: original
        }
        override fun trimContextHistoryWindow(contextWindow: Int, lastContextTokens: Int) {
            fake.trimContextHistoryWindow(contextWindow, lastContextTokens)
            onTrim?.invoke()
        }
        override fun effectiveAgentHistory(): List<LLMMessage> = effectiveHistory?.invoke() ?: fake.effectiveAgentHistory()
        override val thinkingLevel: ThinkingLevel get() = level?.invoke() ?: fake.thinkingLevel
        override fun effectiveContextWindowTokens(): Int = window?.invoke() ?: 200_000
        override fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int) = minOf(
            cap(provider), (effectiveContextWindowTokens() - lastContextTokens).coerceAtLeast(1024),
        )
        override fun streamChatTurnOffloaded(provider: LLMProvider, messages: List<LLMMessage>, systemPrompt: String?,
            maxTokens: Int, temperature: Double?, imageParts: List<LLMMessage.ImagePart>, tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel, turnId: String?, transcriptCommit: TranscriptCommitHandle?): Flow<LLMStreamChunk> = flow {
            requestSnapshots.add(messages.toList())
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

    @Test fun emptyReminderResponseStillUsesExistingOneShotRecovery() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("", "end_turn"))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(3, host.bodies.size)
        assertEquals("user", lastRole(host.bodies[2]))
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun blockedOuterFallbackKeepsIntentAndRequeriesNewMember() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("", "content_filter"))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null,
            fallbackProviders = listOf(FallbackCandidate(provider("claude-haiku-4-5"), "fallback")))
        assertEquals(listOf("user", "user", "assistant"), host.bodies.map { lastRole(it) })
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun legalPrefillDoesNotLoseBudgetForReminderItNeverSends() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake, cap = { 4096 }, window = {
            if (fake.streamRequests.isEmpty()) 200_000 else
                ContextEstimator.estimateRequestTokens(fake.agentHistory, null, emptyList()).toInt() +
                    ContextEstimator.PREFLIGHT_MARGIN_TOKENS.toInt() + 4096
        })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertEquals(4096, host.bodies[1].getInt("max_tokens"))
        assertEquals("assistant", lastRole(host.bodies[1])); assertOneAnswer(fake, "head+tail")
    }

    @Test fun emptyMaxTokensKeepsContinuationAcrossNewAndLegacyThinking() = runTest(dispatcher) {
        for ((id, level) in listOf("claude-sonnet-4-6" to ThinkingLevel.OFF,
            "claude-sonnet-4-6" to ThinkingLevel.HIGH, "claude-haiku-4-5" to ThinkingLevel.HIGH)) {
            val fake = FakeAgentLoopHost(thinkingLevel = level)
            fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("", "max_tokens"))
            fake.scripts.add(segment("+tail", "end_turn"))
            val host = WireHost(fake)
            engine(host).runAgentLoop(provider(id), null)
            assertEquals("$id/$level", 3, host.bodies.size)
            assertEquals("$id/$level", "user", lastRole(host.bodies[2]))
            assertEquals(1, Regex("cut off mid-sentence").findAll(host.bodies[2].getJSONArray("messages").toString()).count())
            assertOneAnswer(fake, "head+tail")
        }
    }

    @Test fun emptyLegacySmallCapUsesLegalAssistantPrefillForBothContinuationRequests() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("", "max_tokens"))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake, cap = { 512 })
        engine(host).runAgentLoop(provider("claude-haiku-4-5"), null)
        assertEquals(3, host.bodies.size)
        for (body in host.bodies.drop(1)) {
            assertEquals(512, body.getInt("max_tokens")); assertFalse(body.has("thinking"))
            assertEquals("assistant", lastRole(body))
        }
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun emptyNetworkErrorAndEofKeepRequestIntentUntilVisibleTail() = runTest(dispatcher) {
        for (eof in listOf(false, true)) {
            val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
            fake.scripts.add(segment("head", "max_tokens"))
            fake.scripts.add(if (eof) TurnScript(listOf(LLMStreamChunk.Started,
                LLMStreamChunk.Finished(null, truncated = true))) else segment("", "network_error"))
            fake.scripts.add(segment("+tail", "end_turn"))
            val host = WireHost(fake)
            engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
            assertEquals("eof=$eof", 3, host.bodies.size)
            assertEquals("eof=$eof", "user", lastRole(host.bodies[2]))
            assertOneAnswer(fake, "head+tail")
        }
    }

    @Test fun thinkingOnlyEmptyWallKeepsNextRequestUserFinal() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.HIGH)
        fake.scripts.add(segment("head", "max_tokens"))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Started, LLMStreamChunk.ThinkingDelta("thought"),
            LLMStreamChunk.Finished("max_tokens"))))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(3, host.bodies.size); assertEquals("user", lastRole(host.bodies[2]))
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun sameProviderTwoTransientRetriesNeverDuplicateReminderOrDeleteRealUser() = runTest(dispatcher) {
        val realUser = "real user's text includes cut off mid-sentence but is not our request object"
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF, seedUserPrompt = realUser)
        fake.scripts.add(segment("head", "max_tokens"))
        repeat(2) { fake.scripts.add(TurnScript(throwBefore = LLMError.TransientError("fixture retry"))) }
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(4, host.bodies.size)
        for (body in host.bodies.drop(1)) {
            val text = body.getJSONArray("messages").toString()
            assertEquals("user", lastRole(body))
            assertEquals(2, Regex("cut off mid-sentence").findAll(text).count())
            assertTrue(text.contains(realUser))
        }
        assertEquals(2, fake.autoRetryCalls.size)
        assertEquals(realUser, fake.agentHistory.first().content)
        assertEquals(1, fake.persistedTurns.size)
        assertEquals(listOf("head+tail"), fake.agentHistory.filter { it.role == LLMMessage.Role.ASSISTANT }.map { it.content })
        assertEquals(1, fake.agentHistory.count { it.role == LLMMessage.Role.USER })
    }

    @Test fun sameProviderRetriesThenLegacyFallbackRemovesOnlyItsOwnReminder() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens"))
        repeat(2) { fake.scripts.add(TurnScript(throwBefore = LLMError.TransientError("fixture retry"))) }
        fake.scripts.add(TurnScript(throwBefore = LLMError.RateLimited()))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null,
            fallbackProviders = listOf(FallbackCandidate(provider("claude-haiku-4-5"), "fallback")))
        assertEquals(listOf("user", "user", "user", "user", "assistant"), host.bodies.map { lastRole(it) })
        assertEquals(listOf(1, 1, 1, 0), host.bodies.drop(1).map {
            Regex("cut off mid-sentence").findAll(it.getJSONArray("messages").toString()).count()
        })
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun ordinaryEmptyContinuationRetriesOnlyOnceWithoutPersistingReminders() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens")); repeat(2) { fake.scripts.add(segment("", "end_turn")) }
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(3, host.bodies.size)
        assertEquals("user", lastRole(host.bodies[2]))
        assertTrue(host.bodies[2].toString().contains("previous response was empty"))
        assertTrue(fake.agentHistory.none { it.role == LLMMessage.Role.USER &&
            it.contentParts.any { part -> part is AgentContentPart.Text && part.text.contains("cut off mid-sentence") } })
    }

    private suspend fun reminderBoundary(compact: Boolean, noProgress: Boolean = false) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        val oldPayload = LLMMessage(LLMMessage.Role.USER, "old-payload-" + "x".repeat(700))
        fake.agentHistory.add(0, oldPayload)
        fake.scripts.add(segment("head", "max_tokens")); fake.scripts.add(segment("+tail", "end_turn"))
        var boundaryWindow = 0
        var forceRequested = false
        var changed = false
        val host = WireHost(fake, cap = { 1024 }, window = {
            if (fake.streamRequests.isEmpty()) 200_000 else {
                if (boundaryWindow == 0) boundaryWindow = ContextEstimator.estimateRequestTokens(
                    fake.agentHistory, null, emptyList()).toInt() +
                    ContextEstimator.PREFLIGHT_MARGIN_TOKENS.toInt() + 1024
                boundaryWindow
            }
        }, onOffload = { force -> if (force) forceRequested = true; null }, onCompact = {
            if (forceRequested && compact && !noProgress) {
                changed = true; true // real compaction publishes a projection, not a raw-history deletion
            } else false
        }, onTrim = {
            if (forceRequested && !compact && !noProgress) {
                fake.agentHistory.remove(oldPayload); changed = true
            }
        }, effectiveHistory = {
            if (compact && changed) listOf(LLMMessage(LLMMessage.Role.USER, "<context-summary>fixture</context-summary>")) +
                fake.agentHistory.filter { it !== oldPayload } else fake.agentHistory.toList()
        })
        if (noProgress) {
            try { engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null) }
            catch (_: LLMError.ProviderError) { /* expected local refusal, never sent */ }
            assertEquals("over-budget continuation must not escape to wire", 1, host.bodies.size)
        } else {
            engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
            assertEquals(2, host.bodies.size); assertTrue("pipeline actually rewrote the base history", changed)
            assertEquals("user", lastRole(host.bodies[1]))
            assertFalse(host.bodies[1].toString().contains("old-payload-"))
            if (compact) {
                assertTrue(fake.agentHistory.any { it === oldPayload })
                assertTrue(host.bodies[1].toString().contains("<context-summary>fixture"))
            }
            val actual = ContextEstimator.estimateRequestTokens(host.requestSnapshots[1], null, emptyList())
            assertTrue("actual projection fits preflight margin", actual + 1024 + ContextEstimator.PREFLIGHT_MARGIN_TOKENS <= boundaryWindow)
            assertOneAnswer(fake, "head+tail")
        }
        assertTrue("base PASS did not hide reminder NEEDS_COMPACTION", forceRequested)
        assertTrue(fake.offloadCalls.any { it.third })
        assertTrue(fake.compactCalls.isNotEmpty())
        if (!compact) assertTrue(fake.trimCalls.isNotEmpty())
    }
    @Test fun necessaryReminderNeedsCompactionRerunsTrimOnUpdatedBase() = runTest(dispatcher) {
        reminderBoundary(compact = false)
    }
    @Test fun necessaryReminderNeedsCompactionReprojectsCompactedHistory() = runTest(dispatcher) {
        reminderBoundary(compact = true)
    }
    @Test fun unshrinkableReminderBoundaryRefusesInsteadOfSendingOversizeProjection() = runTest(dispatcher) {
        reminderBoundary(compact = false, noProgress = true)
    }

    @Test fun EOFEmptyAfterContinuationAlsoKeepsNextRequestUserReminder() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        fake.scripts.add(segment("head", "max_tokens"))
        fake.scripts.add(TurnScript(listOf(LLMStreamChunk.Started, LLMStreamChunk.Finished(null, truncated = true))))
        fake.scripts.add(segment("+tail", "end_turn"))
        val host = WireHost(fake)
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(3, host.bodies.size); assertEquals("user", lastRole(host.bodies[2]))
        assertOneAnswer(fake, "head+tail")
    }

    @Test fun internalProviderRetryAfterSummaryRecoveryUsesRewrittenProjectionAgain() = runTest(dispatcher) {
        val fake = FakeAgentLoopHost(thinkingLevel = ThinkingLevel.OFF)
        val oldPayload = LLMMessage(LLMMessage.Role.USER, "internal-old-payload-" + "x".repeat(700))
        fake.agentHistory.add(0, oldPayload)
        fake.scripts.add(segment("head", "max_tokens"))
        fake.scripts.add(TurnScript(throwBefore = LLMError.TransientError("retry after compact")))
        fake.scripts.add(segment("+tail", "end_turn"))
        var forceRequested = false
        var compacted = false
        val baseWindow = ContextEstimator.estimateRequestTokens(fake.agentHistory, null, emptyList()).toInt() +
            ContextEstimator.PREFLIGHT_MARGIN_TOKENS.toInt() + 1024
        val host = WireHost(fake, cap = { 1024 }, window = {
            if (fake.streamRequests.isEmpty()) 200_000 else baseWindow
        }, onOffload = { force -> if (force) forceRequested = true; null }, onCompact = {
            if (forceRequested) { compacted = true; true } else false
        }, effectiveHistory = {
            if (compacted) listOf(LLMMessage(LLMMessage.Role.USER, "<context-summary>retry-summary</context-summary>")) +
                fake.agentHistory.filter { it !== oldPayload } else fake.agentHistory.toList()
        })
        engine(host).runAgentLoop(provider("claude-sonnet-4-6"), null)
        assertEquals(3, host.bodies.size)
        val retryBody = host.bodies[2].getJSONArray("messages").toString()
        assertTrue(retryBody.contains("<context-summary>retry-summary"))
        assertFalse(retryBody.contains("internal-old-payload-"))
        assertEquals(1, Regex("cut off mid-sentence").findAll(retryBody).count())
        assertTrue(fake.agentHistory.any { it === oldPayload })
        assertOneAnswer(fake, "head+tail")
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
