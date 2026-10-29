package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunBudgetCeilings
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [R5-C2] 2026-10-29 — "a continuation that never reaches the turn tail used to
 * vanish from the DB". Two shapes, one root: the no-tool tail defers the turn's
 * row (correctly — a resumed answer must be ONE row), and every exit that does
 * NOT go through that tail owed the un-persisted slice a write and did not.
 *
 *  - **C-F-03** a turn/budget ceiling hit right after a continuation `continue`
 *    ran `finalizeAtTurnLimit` / `finalizeBudgetStop` with no persist at all:
 *    the text the user had already read was gone on reload, and the banner
 *    (`updateLastAssistantError` = "the session's LAST assistant row") landed on
 *    the PREVIOUS turn's row.
 *  - **C-C-02②** the two empty-reply terminals wrote a `Text("")` row and broke
 *    out of the loop, dropping every segment the earlier `continue` branches had
 *    streamed into `allToolBlocks`.
 *  - **C-C-02①** the error-shaped continuation branch appended a SECOND
 *    assistant entry instead of folding into the un-persisted one, and its
 *    stacking guard only recognised the EOF-stub reminder family.
 *
 * Evidence split (same as the rest of this batch): behaviour where the JVM can
 * drive the real loop ([FakeAgentLoopHost] + [AgentRunBudgetCeilings]), shape
 * where the Android shell cannot be instantiated (the finalize hand-holdings
 * live on the host), and table-driven checks for the two pure decisions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R5ContinuationPersistTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── builders ────────────────────────────────────────────────────────────

    private fun engine(host: FakeAgentLoopHost): AgentLoopEngine = AgentLoopEngine(
        host,
        ChatAgentTraceObserver(
            traceDirResolver = { _ -> null },
            wallClockMs = { 0L },
            monotonicClockMs = { 0L },
            warn = { _, _ -> },
        ),
    )

    private fun model(id: String = "m-main") = LLMModel(id = id, displayName = id, provider = "scripted")

    private fun usage(input: Int = 100, output: Int = 10) =
        LLMStreamChunk.Usage(LLMUsage(inputTokens = input, outputTokens = output))

    /** One streamed segment that ends in [finish] (null = no finish reason). */
    private fun segment(text: String, finish: String?): TurnScript = TurnScript(
        buildList {
            add(LLMStreamChunk.Started)
            add(usage())
            if (text.isNotEmpty()) add(LLMStreamChunk.Text(text))
            if (finish != null) add(LLMStreamChunk.Finished(finish))
        }
    )

    private fun assistantEntries(h: FakeAgentLoopHost): List<LLMMessage> =
        h.agentHistory.filter { it.role == LLMMessage.Role.ASSISTANT }

    private fun ceiling(h: FakeAgentLoopHost, maxTurns: Int) {
        AgentRunBudgetCeilings.register(
            h.activeSessionId,
            AgentRunBudgetCeilings.Ceiling(
                deadlineMs = 10L * 60 * 1000,
                maxTurns = maxTurns,
                maxEstimatedTokens = Long.MAX_VALUE,
            ),
        )
    }

    // ── C-F-03: the turn ceiling must flush the un-persisted slice ──────────

    /**
     * Two length-wall segments, then the range exhausts (ceiling = 2). Before
     * the fix this wrote NOTHING: `finalizeAtTurnLimit` only touched the UI, so
     * a reload showed an empty answer next to text the user had just read.
     */
    @Test
    fun turnCeilingAfterContinuations_persistsTheUnpersistedSegments() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost()
        val p = ScriptedProvider(name = "p-main", model = model())
        ceiling(h, maxTurns = 2)
        h.scripts.add(segment("Seg1", "length"))
        h.scripts.add(segment("Seg2", "length"))
        try {
            engine(h).runAgentLoop(p, systemPrompt = null)

            assertEquals("both continuation attempts happened", 2, h.streamRequests.size)
            assertEquals(
                "the un-persisted continuation must reach the DB before the finalize hand-holding",
                1,
                h.persistedTurns.size,
            )
            assertEquals(
                "ONE row carries BOTH segments (the slice starts at unpersistedBlockStart)",
                listOf("text", "text"),
                h.persistedTurns.first().partKinds,
            )
            assertEquals(
                "the turn-ceiling finalize still fires exactly once",
                1,
                h.calls.count { it == "finalizeAtTurnLimit(maxTurns=2)" },
            )
            assertTrue(
                "and the text is still on screen (the flush never replaces the UI snapshot)",
                h.finalAssistantText.contains("Seg1"),
            )
        } finally {
            AgentRunBudgetCeilings.clear(h.activeSessionId)
        }
    }

    /**
     * Negative control for the flush's own precondition: a clean completion goes
     * through the in-loop tail, which already advanced the slice, so the
     * pre-finalize flush must add NOTHING (no second row, no empty row).
     */
    @Test
    fun cleanCompletion_doesNotProduceAnExtraFlushRow() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost()
        val p = ScriptedProvider(name = "p-main", model = model())
        h.scripts.add(segment("One clean answer", "stop"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("exactly the one row the turn tail writes", 1, h.persistedTurns.size)
        assertFalse(
            "no turn-limit finalize on a clean completion",
            h.calls.any { it.startsWith("finalizeAtTurnLimit") },
        )
    }

    // ── C-C-02②: the empty-reply terminals must keep the earlier segments ───

    /**
     * Two continuation segments, then an error-shaped turn that is empty even
     * after its one-shot retry. Before the fix the terminal wrote a blank row
     * and broke — the two segments were lost from the DB (the UI had shown them).
     */
    @Test
    fun errorShapedEmptyAfterRetry_keepsTheEarlierContinuationSegments() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost()
        val p = ScriptedProvider(name = "p-main", model = model())
        h.scripts.add(segment("Seg1", "length"))
        h.scripts.add(segment("Seg2", "length"))
        h.scripts.add(segment("", "network_error"))   // empty + error-shaped → one-shot retry
        h.scripts.add(segment("", "network_error"))   // still empty → surfacing error

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("the run stops here instead of continuing", 4, h.streamRequests.size)
        assertEquals("the earlier segments are persisted exactly once", 1, h.persistedTurns.size)
        assertEquals(
            "the row carries BOTH un-persisted segments — not a blank filler",
            listOf("text", "text"),
            h.persistedTurns.first().partKinds,
        )
        assertTrue(
            "the banner is still surfaced",
            h.inlineErrors.any { it == "R${com.rikkaminis.app.R.string.error_stream_interrupted}" },
        )
    }

    /**
     * The blank-row contract itself must survive: with NOTHING un-persisted the
     * terminal still writes the empty row the banner needs (otherwise
     * `updateLastAssistantError` stamps the previous turn's row).
     */
    @Test
    fun errorShapedEmptyAfterRetry_stillWritesTheBlankRowForTheBanner() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost()
        val p = ScriptedProvider(name = "p-main", model = model())
        h.scripts.add(segment("", "network_error"))
        h.scripts.add(segment("", "network_error"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("one blank row for this turn", 1, h.persistedTurns.size)
        assertEquals("the row is the empty assistant bubble", listOf("text"), h.persistedTurns.first().partKinds)
        assertTrue(
            "the banner rides that row",
            h.inlineErrors.any { it == "R${com.rikkaminis.app.R.string.error_stream_interrupted}" },
        )
    }

    // ── C-C-02①: the error-shaped continuation folds into ONE entry ─────────

    /**
     * Length wall, then an error-shaped continuation with visible text, then a
     * clean finish. The middle segment continues the same answer, so the
     * transcript must hold ONE assistant entry — the blind `add` used to leave a
     * second entry whose dbMessageId never got back-filled (only the LAST
     * un-persisted entry is anchored).
     */
    @Test
    fun errorShapedContinuation_foldsIntoTheSameAssistantEntry() = runTest(mainDispatcher) {
        val h = FakeAgentLoopHost()
        val p = ScriptedProvider(name = "p-main", model = model())
        h.scripts.add(segment("Head", "length"))
        h.scripts.add(segment(" tail", "network_error"))
        h.scripts.add(segment("end.", "stop"))

        engine(h).runAgentLoop(p, systemPrompt = null)

        assertEquals("three provider attempts", 3, h.streamRequests.size)
        assertEquals("ONE assistant entry for the whole answer", 1, assistantEntries(h).size)
        assertEquals(
            "every segment is folded in stream order",
            "Head tailend.",
            assistantEntries(h).first().content,
        )
        assertEquals("and ONE row carries it", 1, h.persistedTurns.size)
        assertEquals(
            "the entry is anchored to that row",
            h.persistedTurns.first().dbId,
            assistantEntries(h).first().dbMessageId,
        )
        assertTrue(
            "neither reminder family survives the fold",
            h.agentHistory.none { ContinuationHistoryMerge.isContinuationReminder(it) },
        )
    }

    // ── the two pure decisions, table-driven ────────────────────────────────

    @Test
    fun flushPolicy_table() {
        data class Case(val start: Int, val count: Int, val expected: Boolean)

        listOf(
            Case(start = 0, count = 2, expected = true),   // two un-persisted blocks
            Case(start = 1, count = 2, expected = true),   // one tail block
            Case(start = 2, count = 2, expected = false),  // everything durable
            Case(start = 0, count = 0, expected = false),  // nothing streamed
            Case(start = 3, count = 2, expected = false),  // defensive: start beyond the list
            Case(start = -1, count = 2, expected = false), // defensive: negative start
        ).forEach { case ->
            assertEquals(
                "[start=${case.start}, count=${case.count}] flush before finalize",
                case.expected,
                ContinuationFlushPolicy.mustFlushBeforeFinalize(case.start, case.count),
            )
        }
    }

    @Test
    fun staleContinuationReminder_coversBothFamilies() {
        fun user(vararg texts: String) = LLMMessage(
            role = LLMMessage.Role.USER,
            content = texts.joinToString(""),
            contentParts = texts.map { AgentContentPart.Text(it) },
        )

        // Literal wording, NOT built from the production constants: pinning the
        // marker against production wording would let the two drift apart.
        val eofStub = user(
            "<system-reminder>Your previous response was cut off by a network error mid-stream. " +
                "Continue exactly where you left off, starting from the character after: \"abc\". " +
                "Do not restart or repeat prior text. Finish the answer directly.</system-reminder>",
        )
        val lengthWall = user(
            "<system-reminder>Your previous reply was cut off mid-sentence by the output token " +
                "limit. Continue the reply starting from the exact character after: \"abc\". " +
                "Do NOT repeat any text you have already output.</system-reminder>",
        )

        data class Case(val name: String, val message: LLMMessage?, val expected: Boolean)

        // The injection site's guard is the OR of the two family predicates —
        // each family keeps exactly one source (see the shape test below).
        fun injectionSiteGuard(message: LLMMessage?): Boolean =
            EofStubReminderStackGuard.isStaleEofStubReminder(message) ||
                ContinuationHistoryMerge.isLengthWallReminder(message)

        listOf(
            Case("EOF-stub reminder (its own family)", eofStub, true),
            Case("length-wall reminder (this branch used to miss it)", lengthWall, true),
            Case("the user's real question", user("please write the report"), false),
            Case("a tool_result turn (two parts)", LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ToolResult(id = "t1", name = "shell", content = "ok"),
                    AgentContentPart.Text("<system-reminder>cut off by a network error mid-stream"),
                ),
            ), false),
            Case("no previous turn", null, false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, injectionSiteGuard(case.message))
        }
    }

    // ── shape: the wiring the Android shell cannot run here ─────────────────

    private val engineRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** Comment-free source: the prose in this file quotes every shape asserted
     *  below (the KDoc explains the defect with the very call names), so a raw
     *  count would be satisfied by a sentence. */
    private fun withoutComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val nl = src.indexOf('\n', i)
                    i = if (nl < 0) src.length else nl
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2)
                    i = if (end < 0) src.length else end + 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            n++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return n
    }

    @Test
    fun preFinalizeFlushRunsBeforeBothFinalizers() {
        val code = withoutComments(readRepoFile(engineRel))
        val flushAt = code.indexOf("flushUnpersistedTurnRow(")
        assertTrue("the shared flush helper is gone", flushAt >= 0)

        val runawayAt = code.indexOf("AgentLoopExitDecision.shouldFinalizeAsRunaway(")
        assertTrue("the runaway decision is gone", runawayAt >= 0)
        val atLimitAt = code.indexOf("host.finalizeAtTurnLimit(")
        val budgetAt = code.indexOf("host.finalizeBudgetStop(")
        assertTrue("finalizeAtTurnLimit is gone", atLimitAt >= 0)
        assertTrue("finalizeBudgetStop is gone", budgetAt >= 0)

        val exitFlushAt = code.lastIndexOf(
            "ContinuationFlushPolicy.mustFlushBeforeFinalize(",
        )
        assertTrue(
            "the exit flush must be decided through the pure policy",
            exitFlushAt >= 0,
        )
        assertTrue(
            "the exit flush must precede the turn-limit finalize (the banner addresses " +
                "'the LAST assistant row', so the row has to exist first)",
            exitFlushAt < atLimitAt,
        )
        assertTrue(
            "…and the budget-stop finalize",
            exitFlushAt < budgetAt,
        )
        assertTrue(
            "…and the runaway decision itself, so the flush is unconditional across both exits",
            exitFlushAt < runawayAt,
        )
    }

    @Test
    fun emptyReplyTerminalsCarryTheUnpersistedSliceIntoTheirBannerRow() {
        val code = withoutComments(readRepoFile(engineRel))
        assertEquals(
            "no site may hand-write a blank parts list any more: it dropped every segment the " +
                "continuation `continue` branches had already streamed",
            0,
            count(code, "listOf(AgentContentPart.Text(\"\")"),
        )
        assertEquals(
            "both empty-reply terminals route their parts through the placement helper",
            2,
            count(code, "carriedTurnParts = TerminalErrorPlacement.partsForTerminalError("),
        )
        assertEquals(
            "…and both anchor the row they just wrote",
            2,
            count(code, "markTurnPersisted(loopState, carriedRowId)"),
        )
    }

    @Test
    fun errorShapedContinuationFoldsAndGuardsBothFamilies() {
        val code = withoutComments(readRepoFile(engineRel))
        assertTrue(
            "the error-shaped continuation must fold into the previous entry",
            code.contains("errContinuationMessage"),
        )
        assertTrue(
            "…through the shared merge",
            code.contains("mergeIntoPreviousAssistant(\n                                host.agentHistory,\n                                errContinuationMessage,"),
        )
        assertTrue(
            "…and its stacking guard must cover both reminder families, one source per family",
            code.contains("EofStubReminderStackGuard.isStaleEofStubReminder(errPrevTail) ||") &&
                code.contains("ContinuationHistoryMerge.isLengthWallReminder(errPrevTail)"),
        )
        assertEquals(
            "the append is the fallback branch, never the only path",
            1,
            count(code, "host.agentHistory.add(errContinuationMessage)"),
        )
    }
}
