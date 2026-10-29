package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ModelEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [FIX-R4-Q F-01 / F-08] A continuation chain must look like ONE answer in all
 * three places at once: the bubble the user watches, the `assistant` rows in
 * the DB, and the in-memory transcript the next request is built from.
 *
 * Before the fix the no-tool tail persisted the turn BEFORE the empty-turn /
 * EOF continuation branches ran, so a resumed answer wrote one row for the head
 * of the reply and appended a second row for the tail (same
 * `loopState.assistantId`, one bubble on screen); the history meanwhile got one
 * ASSISTANT entry per segment plus the transient "<system-reminder>…continue"
 * turns, which then stayed in the transcript forever.
 *
 * Table (one row per scenario, shared assertions):
 *  - length wall ×1 then a clean finish   → 1 row, 1 assistant entry
 *  - EOF (no finish reason) ×1 then clean → 1 row, 1 assistant entry
 *  - length wall ×2 then a clean finish   → 1 row, 1 assistant entry
 *  - two separate runs                    → still 1 row / 1 entry each (no folding)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopContinuationChainTest {

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

    private fun host() = FakeAgentLoopHost()

    private fun usage(input: Int = 100, output: Int = 10) =
        LLMStreamChunk.Usage(LLMUsage(inputTokens = input, outputTokens = output))

    /** One streamed segment that ends in [finish] (null = no finish reason). */
    private fun segment(text: String, finish: String?): TurnScript = TurnScript(
        buildList {
            add(LLMStreamChunk.Started)
            add(usage())
            add(LLMStreamChunk.Text(text))
            if (finish != null) add(LLMStreamChunk.Finished(finish))
        }
    )

    /** One streamed segment whose stream died without a finish reason (EOF). */
    private fun eofSegment(text: String): TurnScript = TurnScript(
        listOf(
            LLMStreamChunk.Started,
            usage(),
            LLMStreamChunk.Text(text),
            LLMStreamChunk.Finished(stopReason = null, truncated = true),
        )
    )

    private fun assistantEntries(h: FakeAgentLoopHost): List<LLMMessage> =
        h.agentHistory.filter { it.role == LLMMessage.Role.ASSISTANT }

    private fun leftoverContinuationReminders(h: FakeAgentLoopHost): List<LLMMessage> =
        h.agentHistory.filter { ContinuationHistoryMerge.isContinuationReminder(it) }

    // ── F-01/F-08: length wall ──

    @Test
    fun lengthWall_oneContinuation_persistsOneRowAndOneHistoryEntry() = runTest(mainDispatcher) {
        val h = host()
        val p = ScriptedProvider(name = "p-main", model = model("m-main"))
        h.scripts.add(segment("The answer begins here", "length"))
        h.scripts.add(segment(" and finishes here.", "stop"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("two provider attempts (one continuation)", 2, h.streamRequests.size)
        assertEquals(
            "ONE assistant row for the whole answer — the continuation streams into the same bubble",
            1,
            h.persistedTurns.size,
        )
        assertEquals(
            "the single row carries the full answer, not just the head",
            "The answer begins here and finishes here.",
            h.finalAssistantText,
        )
        assertEquals(
            "the transcript carries ONE assistant entry for the answer (what the DB row holds)",
            1,
            assistantEntries(h).size,
        )
        assertTrue(
            "no continuation reminder is left behind in the transcript",
            leftoverContinuationReminders(h).isEmpty(),
        )
    }

    // ── F-01: EOF stub ──

    @Test
    fun eofContinuation_oneContinuation_persistsOneRowAndOneHistoryEntry() = runTest(mainDispatcher) {
        val h = host()
        val p = ScriptedProvider(name = "p-main", model = model("m-main"))
        h.scripts.add(eofSegment("Partial reply cut by a dropped connection"))
        h.scripts.add(segment(" — and the rest of it.", "stop"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("one continuation attempt", 2, h.streamRequests.size)
        assertEquals("ONE assistant row for the resumed answer", 1, h.persistedTurns.size)
        assertEquals("both segments ride the single row", 1, assistantEntries(h).size)
        assertEquals(
            "the resumed text is folded into the assistant entry the model sees next",
            "Partial reply cut by a dropped connection — and the rest of it.",
            assistantEntries(h).first().content,
        )
        assertTrue(
            "the EOF stub reminder does not survive the fold",
            leftoverContinuationReminders(h).isEmpty(),
        )
    }

    // ── F-01: three segments ──

    @Test
    fun twoLengthWallsAndACleanFinish_persistExactlyOneRow() = runTest(mainDispatcher) {
        val h = host()
        val p = ScriptedProvider(name = "p-main", model = model("m-main"))
        h.scripts.add(segment("Seg1", "length"))
        h.scripts.add(segment("+Seg2", "length"))
        h.scripts.add(segment("+Seg3", "stop"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("three provider attempts", 3, h.streamRequests.size)
        assertEquals("a 3-segment answer is ONE row, not three", 1, h.persistedTurns.size)
        assertEquals("a 3-segment answer is ONE assistant entry", 1, assistantEntries(h).size)
        assertEquals("every segment is in the folded text", "Seg1+Seg2+Seg3", h.finalAssistantText)
        assertTrue("no reminder stacking in the transcript", leftoverContinuationReminders(h).isEmpty())
    }

    // ── non-continuation turns keep one entry each (regression guard) ──

    @Test
    fun ordinaryTurns_areNotFoldedTogether() = runTest(mainDispatcher) {
        val h = host()
        val p = ScriptedProvider(name = "p-main", model = model("m-main"))
        h.scripts.add(segment("First answer", "stop"))
        h.scripts.add(segment("Second answer", "stop"))

        // Two separate runs: a new user message starts each one.
        engine(h).runAgentLoop(p, systemPrompt = null)
        h.agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "next question",
            )
        )
        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("two rows, one per answer", 2, h.persistedTurns.size)
        assertEquals("two assistant entries, one per answer", 2, assistantEntries(h).size)
        assertEquals(
            "the second answer is not glued onto the first",
            "Second answer",
            assistantEntries(h).last().content,
        )
    }

    // ── F-08: the fold's decision table ──

    @Test
    fun foldDecisionTable() {
        data class Case(
            val name: String,
            val history: List<LLMMessage>,
            val incoming: LLMMessage,
            val expectFolded: Boolean,
            // Exact assistant contents after the call. mergeIntoPreviousAssistant
            // NEVER appends the incoming entry on a false verdict (the caller
            // does — see the wiring in runAgentLoop), so a not-folded case
            // expects the history's original assistant texts, unchanged.
            val expectedAssistantContents: List<String>,
            val expectedSize: Int,
        )

        val head = assistant("head")
        val wallReminder = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "cut off mid-sentence",
            contentParts = listOf(AgentContentPart.Text("cut off mid-sentence by the output token limit")),
        )
        val eofReminder = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "cut off by a network error",
            contentParts = listOf(AgentContentPart.Text("cut off by a network error mid-stream")),
        )
        val toolResult = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = listOf(AgentContentPart.ToolResult(id = "t1", name = "shell", content = "ok")),
        )

        val cases = listOf(
            Case("plain continuation (prefill: no reminder)", listOf(head), assistant("+tail"), true, listOf("head+tail"), 1),
            Case("continuation behind a length-wall reminder", listOf(head, wallReminder), assistant("+tail"), true, listOf("head+tail"), 1),
            Case("continuation behind an EOF stub reminder", listOf(head, eofReminder), assistant("+tail"), true, listOf("head+tail"), 1),
            Case("empty restarted turn is NOT folded", listOf(head, wallReminder), assistant(""), false, listOf("head"), 2),
            Case("tool_result tail is not a continuation", listOf(head, toolResult), assistant("+tail"), false, listOf("head"), 2),
            Case(
                "already-persisted previous row is not folded into",
                listOf(head.copy(dbMessageId = "db-1"), eofReminder),
                assistant("+tail"),
                false,
                listOf("head"),
                2,
            ),
            Case("no previous assistant at all", emptyList(), assistant("+tail"), false, emptyList(), 0),
        )

        cases.forEach { case ->
            val history = case.history.toMutableList()
            val folded = ContinuationHistoryMerge.mergeIntoPreviousAssistant(history, case.incoming)
            assertEquals("[${case.name}] fold decision", case.expectFolded, folded)
            assertEquals("[${case.name}] history size", case.expectedSize, history.size)
            val assistantTexts = history.filter { it.role == LLMMessage.Role.ASSISTANT }.map { it.content }
            assertEquals(
                "[${case.name}] assistant contents after the call",
                case.expectedAssistantContents,
                assistantTexts,
            )
        }
    }

    private fun assistant(text: String, dbId: String? = null): LLMMessage = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        contentParts = if (text.isEmpty()) emptyList() else listOf(AgentContentPart.Text(text)),
        dbMessageId = dbId,
    )

    // ── F-06: the fallback target publishes the ENRICHED model ──

    @Test
    fun fallbackSwitch_publishesTheEnrichedModelNotTheRawEntryModel() = runTest(mainDispatcher) {
        val h = host()
        val primary = ScriptedProvider(name = "p-primary", model = model("m-primary"))
        // Enriched model: the registry knows the context window.
        val enriched = LLMModel(
            id = "m-secondary",
            displayName = "m-secondary",
            provider = "scripted",
            contextWindow = 128_000,
            maxOutputTokens = 8_192,
        )
        val secondary = ScriptedProvider(name = "p-secondary", model = enriched)
        // What the user STORED for that entry: no registry fields.
        h.configEntries = listOf(
            // uuid IS the entry id (ModelEntry.id derives from it) and the
            // fallback publish path resolves the candidate's entryId against
            // this list — without the match newEntry is null and nothing is
            // published.
            ModelEntry(
                providerInstanceId = "inst-secondary",
                baseModel = model("m-secondary"),
                uuid = "entry-secondary",
            ),
        )
        h.scripts.add(TurnScript(throwBefore = rateLimited()))
        h.scripts.add(segment("from secondary", "stop"))

        engine(h).runAgentLoop(
            primary,
            systemPrompt = null,
            fallbackProviders = listOf(FallbackCandidate(secondary, "entry-secondary")),
        )

        assertEquals("two attempts across providers", 2, h.streamRequests.size)
        assertTrue("the fallback target was published", h.currentModels.isNotEmpty())
        val published = h.currentModels.last()
        assertEquals("m-secondary", published.id)
        assertNotNull(
            "the published model keeps the registry context window — the raw entry model has none",
            published.contextWindow,
        )
        assertEquals(128_000, published.contextWindow)
        assertFalse(
            "the raw entry model really has no context window (guards the assertion above)",
            h.configEntries.first().model.contextWindow != null,
        )
    }

    private fun rateLimited(): CancellationException =
        CancellationException("rate limited").apply { initCause(LLMError.RateLimited()) }
}
