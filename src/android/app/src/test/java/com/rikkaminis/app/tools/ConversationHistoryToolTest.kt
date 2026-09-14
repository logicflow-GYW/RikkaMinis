package com.rikkaminis.app.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [U10] `conversation_history` — pure core + executor.
 *
 * The reason this tool exists is compaction: detail the model no longer has is
 * still on disk, and until now nothing the agent can call could reach it. So the
 * first test below is the load-bearing one — it asserts that a turn living
 * outside the recent window is reachable *at all* (the default page cannot see
 * it, a cursor can).
 *
 * The executor takes its row loader as a lambda, so every case here — including
 * the session-binding refusal — runs without Room or a device.
 */
class ConversationHistoryToolTest {

    private fun text(index: Int, role: String, body: String): TranscriptRow =
        TranscriptRow(index, role, """[{"type":"text","value":"$body"}]""")

    private fun conversation(n: Int): List<TranscriptRow> =
        (0 until n).map { i ->
            text(i, if (i % 2 == 0) "user" else "assistant", "turn $i says hello")
        }

    private fun call(
        args: String,
        sessionId: String = "session-abcdef123456",
        rows: List<TranscriptRow> = emptyList(),
    ): ToolExecutionResult = runBlocking {
        var loaded: String? = null
        val result = executeConversationHistoryTool(args, sessionId) { sid ->
            loaded = sid
            rows
        }
        lastLoaded = loaded
        result
    }

    private var lastLoaded: String? = null

    // ── ① compaction recovery: earlier turns are reachable ───────────────

    @Test
    fun `a turn outside the recent window is reachable by cursor`() {
        val rows = conversation(120)
        // Default read: newest page only, capped by max_items.
        val tail = buildTranscriptPage(
            rows,
            parseOk("""{"max_items":10,"max_chars":6000}"""),
            sessionLabel = "session-",
        )
        val tailIndices = tail.lines.map { it.index }
        assertEquals(List(10) { 110 + it }, tailIndices)
        assertFalse("the early turn must not be in the recent window", tailIndices.contains(3))
        assertNotNull("more history exists, so paging must be offered", tail.nextCursor)

        // Paging back reaches it — this is what compaction dropped.
        var cursor = tail.nextCursor
        var sawEarlyTurn = false
        var guard = 0
        while (cursor != null && guard++ < 50) {
            val page = buildTranscriptPage(
                rows,
                parseOk("""{"cursor":$cursor,"max_items":10,"max_chars":6000}"""),
                sessionLabel = "session-",
            )
            if (page.lines.any { it.index == 3 }) sawEarlyTurn = true
            cursor = page.nextCursor
        }
        assertTrue("cursor paging must reach the oldest turn", sawEarlyTurn)
    }

    // ── ② session binding ────────────────────────────────────────────────

    @Test
    fun `passing a session argument refuses instead of reading the active one`() = runBlocking {
        for (name in ConversationHistoryContract.SESSION_ARGUMENT_NAMES) {
            var loaderCalls = 0
            val result = executeConversationHistoryTool(
                """{"$name":"someone-elses-session"}""",
                "session-abcdef123456",
            ) { loaderCalls++; emptyList() }
            assertFalse("$name must be refused", result.success)
            assertTrue(result.output.contains(name))
            assertEquals("a refused call must not touch the DB", 0, loaderCalls)
        }
    }

    @Test
    fun `the loader receives the bound session, not something from the arguments`() {
        val result = call("""{"query":"hello"}""", sessionId = "bound-session-id")
        assertTrue(result.success)
        assertEquals("bound-session-id", lastLoaded)
    }

    @Test
    fun `the schema exposes no session parameter`() {
        val def = conversationHistoryDefinition()
        assertEquals(ConversationHistoryContract.NAME, def.name)
        for (name in ConversationHistoryContract.SESSION_ARGUMENT_NAMES) {
            assertFalse("schema must not offer '$name'", def.parameters.containsKey(name))
        }
        assertTrue(def.required.contains("tool_title"))
    }

    // ── ③ paging ─────────────────────────────────────────────────────────

    @Test
    fun `paging walks backwards without overlap and terminates`() {
        val rows = conversation(55)
        val seen = mutableListOf<Int>()
        var cursor: Int? = null
        var guard = 0
        do {
            val page = buildTranscriptPage(
                rows,
                parseOk(
                    if (cursor == null) """{"max_items":10,"max_chars":6000}"""
                    else """{"cursor":$cursor,"max_items":10,"max_chars":6000}""",
                ),
                sessionLabel = "s",
            )
            // newest → oldest, each page internally ascending
            assertEquals(page.lines.map { it.index }.sorted(), page.lines.map { it.index })
            seen += page.lines.map { it.index }
            cursor = page.nextCursor
        } while (cursor != null && guard++ < 40)

        assertEquals("every message must be reachable exactly once", 55, seen.size)
        assertEquals("no duplicates across pages", 55, seen.toSet().size)
        assertEquals(0, seen.min())
        assertEquals(54, seen.max())
    }

    @Test
    fun `max chars stops the page before it overflows`() {
        val rows = (0 until 20).map { text(it, "user", "x".repeat(100)) }
        val page = buildTranscriptPage(
            rows,
            parseOk("""{"max_items":20,"max_chars":500}"""),
            sessionLabel = "s",
        )
        val used = page.lines.sumOf { it.display.length + 1 }
        assertTrue("page must stay inside the budget ($used)", used <= 500)
        assertTrue("but must not be empty", page.lines.isNotEmpty())
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `max items is honoured exactly`() {
        val page = buildTranscriptPage(
            conversation(30),
            parseOk("""{"max_items":3,"max_chars":6000}"""),
            sessionLabel = "s",
        )
        assertEquals(listOf(27, 28, 29), page.lines.map { it.index })
    }

    // ── ④ empty / no match ───────────────────────────────────────────────

    @Test
    fun `empty conversation says so`() {
        val result = call("""{}""", rows = emptyList())
        assertTrue(result.success)
        assertTrue(result.output.contains("no messages in this conversation yet"))
    }

    @Test
    fun `unmatched query says so instead of returning the tail`() {
        val result = call("""{"query":"definitely-not-present"}""", rows = conversation(12))
        assertTrue(result.success)
        assertTrue(result.output.contains("no message matches"))
        assertTrue(result.output.contains("12 messages searched"))
    }

    @Test
    fun `query filters to matching turns and keeps them in order`() {
        val rows = listOf(
            text(0, "user", "we should pin the plugin version"),
            text(1, "assistant", "unrelated"),
            text(2, "user", "and the plugin docs too"),
        )
        val result = call("""{"query":"plugin"}""", rows = rows)
        assertTrue(result.output.contains("[#0 user]"))
        assertTrue(result.output.contains("[#2 user]"))
        assertFalse(result.output.contains("[#1 assistant]"))
    }

    @Test
    fun `a hit deep inside a long tool output shows the matched text`() {
        // The head of a long tool result would hide the very text searched for.
        val buried = "y".repeat(5000) + " NEEDLE " + "z".repeat(5000)
        val rows = listOf(
            TranscriptRow(0, "assistant", """[{"type":"toolResult","value":{"success":true,"output":"$buried"}}]"""),
            text(1, "user", "carry on"),
        )
        val result = call("""{"query":"NEEDLE"}""", rows = rows)
        assertTrue(result.output.contains("NEEDLE"))
    }

    // ── rendering ────────────────────────────────────────────────────────

    @Test
    fun `tool payloads are previewed with an explicit marker`() {
        val line = renderTranscriptLine(
            TranscriptRow(
                7,
                "assistant",
                """[{"type":"toolUse","value":{"name":"shell_execute","input":"${"q".repeat(1000)}"}}]""",
            ),
        )
        assertTrue(line.display.contains("[tool call shell_execute]"))
        assertTrue(line.display.contains("chars]"))
        assertTrue(line.payloadTruncated)
    }

    @Test
    fun `a failed tool result is marked failed`() {
        val line = renderTranscriptLine(
            TranscriptRow(
                3,
                "assistant",
                """[{"type":"toolResult","value":{"success":false,"output":"Error: nope"}}]""",
            ),
        )
        assertTrue(line.display.contains("[tool result failed]"))
        assertTrue(line.display.contains("Error: nope"))
    }

    @Test
    fun `malformed parts json degrades instead of failing the read`() {
        val broken = TranscriptRow(0, "user", "{not json")
        val line = renderTranscriptLine(broken)
        assertEquals("[empty message]", line.display)
        val page = buildTranscriptPage(
            listOf(broken, text(1, "assistant", "still readable")),
            parseOk("""{}"""),
            sessionLabel = "s",
        )
        assertTrue(page.header.contains("2 messages"))
        assertTrue(page.lines.size == 2)
    }

    @Test
    fun `image parts are described rather than dropped silently`() {
        val line = renderTranscriptLine(
            TranscriptRow(
                0,
                "user",
                """[{"type":"mediaRef","value":{"originalFileName":"shot.png","mimeType":"image/png"}}]""",
            ),
        )
        assertTrue(line.display.contains("[image shot.png]"))
    }

    // ── argument handling ────────────────────────────────────────────────

    @Test
    fun `malformed arguments are reported rather than guessed`() {
        val bad = listOf(
            """{"cursor":-1}""",
            """{"cursor":"abc"}""",
            """{"cursor":{}}""",
            """{"max_chars":"lots"}""",
            """not json""",
        )
        for (args in bad) {
            val result = call(args, rows = conversation(3))
            assertFalse("$args must be refused", result.success)
            assertTrue(result.output.startsWith("Error: "))
        }
    }

    @Test
    fun `limits are clamped to the contract range`() {
        val huge = parseConversationHistoryRequest("""{"max_chars":999999,"max_items":99999}""")
        assertTrue(huge is ConversationHistoryRequest.Ok)
        huge as ConversationHistoryRequest.Ok
        assertEquals(ConversationHistoryContract.MAX_MAX_CHARS, huge.args.maxChars)
        assertEquals(ConversationHistoryContract.MAX_MAX_ITEMS, huge.args.maxItems)

        val tiny = parseConversationHistoryRequest("""{"max_chars":1,"max_items":0}""")
        tiny as ConversationHistoryRequest.Ok
        assertEquals(ConversationHistoryContract.MIN_MAX_CHARS, tiny.args.maxChars)
        assertEquals(ConversationHistoryContract.MIN_MAX_ITEMS, tiny.args.maxItems)
    }

    @Test
    fun `cursor as a string is accepted but as a float-with-fraction is not silently truncated`() {
        val asString = parseConversationHistoryRequest("""{"cursor":"42"}""")
        assertTrue(asString is ConversationHistoryRequest.Ok)
        assertEquals(42, (asString as ConversationHistoryRequest.Ok).args.cursor)
        assertTrue(parseConversationHistoryRequest("""{"cursor":true}""") is ConversationHistoryRequest.Invalid)
        assertTrue(
            "a fractional cursor must not be silently truncated",
            parseConversationHistoryRequest("""{"cursor":1.7}""") is ConversationHistoryRequest.Invalid,
        )
    }

    // ── executor failure modes ───────────────────────────────────────────

    @Test
    fun `a loader failure is reported as a failed result, not an exception`() = runBlocking {
        val result = executeConversationHistoryTool("""{}""", "s") { throw IllegalStateException("db closed") }
        assertFalse(result.success)
        assertTrue(result.output.contains("db closed"))
    }

    @Test
    fun `a successful read reports the session label and the tool title`() {
        val result = call("""{"tool_title":"remembering"}""", sessionId = "abcdefgh-1234", rows = conversation(2))
        assertTrue(result.success)
        assertTrue(result.output.contains("session abcdefgh"))
        assertEquals("remembering", result.toolTitle)
    }

    @Test
    fun `header reports match counts and paging state`() {
        val page = buildTranscriptPage(
            conversation(50),
            parseOk("""{"max_items":5,"max_chars":6000}"""),
            sessionLabel = "s",
        )
        assertTrue(page.header.contains("50 messages"))
        assertTrue(page.header.contains("showing 5"))
        assertTrue(page.header.contains("next_cursor=45"))
        val oldest = buildTranscriptPage(
            conversation(3),
            parseOk("""{"max_items":5,"max_chars":6000}"""),
            sessionLabel = "s",
        )
        assertTrue(oldest.header.contains("no older messages"))
        assertNull(oldest.nextCursor)
    }

    private fun parseOk(json: String): ConversationHistoryArgs {
        val req = parseConversationHistoryRequest(json)
        assertTrue("expected Ok for $json, got $req", req is ConversationHistoryRequest.Ok)
        return (req as ConversationHistoryRequest.Ok).args
    }
}
