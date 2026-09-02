package com.openminis.app.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-backup-byte-budget] JVM coverage for the linear byte-budget packing of
 * chat history (see [packChatHistoryWithBudget]) and the new per-part caps in
 * [ConfigBackup.sanitizeChatParts].
 *
 * Contract under test:
 *  - packing granularity is ONE MESSAGE: whatever budget remains is filled
 *    exactly, no days-ladder jumps
 *  - newest-first: when the budget runs out mid-session, the messages that
 *    landed are the newest ones
 *  - the frontier is a hard stop: nothing after the first message that does
 *    not fit is carried (no silent holes in a restored transcript)
 *  - text parts and toolUse inputs are now capped like tool outputs
 */
class ChatBackupBudgetTest {

    private fun sessionJson(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "s-$id")
        .put("modelId", "m")
        .put("createdAt", 1L)
        .put("updatedAt", 2L)

    private fun msg(
        id: String,
        sessionId: String,
        text: String,
        sortOrder: Int,
    ) = BudgetChatMessage(
        id = id,
        sessionId = sessionId,
        role = "user",
        partsJson = """[{"type":"text","value":"$text"}]""",
        createdAt = 100L + sortOrder,
        sortOrder = sortOrder,
        reasoningContent = null,
    )

    private fun sanitize(s: String?): String? = ConfigBackup.sanitizeChatParts(s)
    private fun capReasoning(s: String?): String? = ConfigBackup.capReasoningContent(s)

    // ── Budget packing ──────────────────────────────────────────────────

    @Test
    fun `everything fits when budget is generous`() {
        val result = packChatHistoryWithBudget(
            skeletonChars = 100,
            budgetTotalChars = 1_000_000,
            sessionsInOrder = listOf(
                sessionJson("a") to listOf(msg("a1", "a", "hello", 1), msg("a2", "a", "world", 2)),
                sessionJson("b") to listOf(msg("b1", "b", "hi", 1)),
            ),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(2, result.sessions.size)
        assertEquals(3, result.messages.size)
        assertEquals(0, result.sessionsDropped)
        assertEquals(0, result.messagesDropped)
    }

    @Test
    fun `budget cuts at single-message granularity inside one session`() {
        // Budget sized so that skeleton + session + exactly the first
        // (newest) message fits, the second does not. The days-ladder
        // equivalent would have dropped the whole session.
        val session = sessionJson("a")
        val newest = msg("a2", "a", "newest message", 2)
        val older = msg("a1", "a", "older message", 1)
        val newestJson = JSONObject()
            .put("id", newest.id).put("sessionId", "a").put("role", "user")
            .put("partsJson", newest.partsJson).put("createdAt", newest.createdAt)
            .put("sortOrder", 2).put("reasoningContent", JSONObject.NULL)
        // measure what one message costs serialized
        val oneMsgChars = newestJson.toString().length
        val sessionChars = session.toString().length

        val budget = 100L + sessionChars + oneMsgChars // room for exactly ONE message

        val result = packChatHistoryWithBudget(
            skeletonChars = 100,
            budgetTotalChars = budget,
            sessionsInOrder = listOf(
                session to listOf(newest, older), // newest-first input order
            ),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(1, result.sessions.size)
        assertEquals("exactly one message must land", 1, result.messages.size)
        assertEquals("the NEWEST message must be the one that lands", "a2", result.messages[0].optString("id"))
        assertEquals(1, result.messagesDropped)
        assertEquals(0, result.sessionsDropped)
    }

    @Test
    fun `frontier is a hard stop - older sessions are counted as dropped`() {
        // Two sessions; budget only covers the first session's metadata.
        val s1 = sessionJson("a")
        val s2 = sessionJson("b")
        val budget = 50L + s1.toString().length

        val result = packChatHistoryWithBudget(
            skeletonChars = 50,
            budgetTotalChars = budget,
            sessionsInOrder = listOf(
                s1 to listOf(msg("a1", "a", "x", 1)),
                s2 to listOf(msg("b1", "b", "y", 1)),
            ),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        // Session a's metadata fit but its message did not → exhausted;
        // session b is dropped entirely.
        assertEquals(1, result.sessions.size)
        assertEquals("a", result.sessions[0].optString("id"))
        assertTrue("session b must be dropped", result.sessionsDropped >= 1)
    }

    @Test
    fun `zero remaining budget carries no chat but never throws`() {
        // Skeleton alone eats the whole budget (pathological skills/memory).
        val result = packChatHistoryWithBudget(
            skeletonChars = 10_000,
            budgetTotalChars = 5_000,
            sessionsInOrder = listOf(
                sessionJson("a") to listOf(msg("a1", "a", "x", 1)),
            ),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(0, result.sessions.size)
        assertEquals(0, result.messages.size)
        assertEquals(1, result.sessionsDropped)
    }

    @Test
    fun `messages that sanitize to null cost nothing and are skipped`() {
        // Media-only message: sanitize returns null → not counted, not carried.
        val mediaMsg = BudgetChatMessage(
            id = "m1", sessionId = "a", role = "user",
            partsJson = """[{"type":"image","image_base64":"x"}]""",
            createdAt = 1L, sortOrder = 1,
        )
        val result = packChatHistoryWithBudget(
            skeletonChars = 0,
            budgetTotalChars = 10_000,
            sessionsInOrder = listOf(sessionJson("a") to listOf(mediaMsg)),
            sanitize = ::sanitize,
            capReasoning = ::capReasoning,
        )
        assertEquals(1, result.sessions.size)
        assertEquals(0, result.messages.size)
        assertEquals(0, result.messagesDropped)
    }

    // ── Per-part caps (sanitizeChatParts) ───────────────────────────────

    @Test
    fun `oversized text part is capped with marker`() {
        val huge = "t".repeat(ConfigBackup.MAX_BACKUP_TEXT_CHARS + 5_000)
        val json = """[{"type":"text","value":"$huge"}]"""
        val out = ConfigBackup.sanitizeChatParts(json)
        assertNotNull(out)
        val value = JSONArray(out!!).getJSONObject(0).optString("value")
        assertTrue(
            "text must be capped near the limit",
            value.length <= ConfigBackup.MAX_BACKUP_TEXT_CHARS + 64,
        )
        assertTrue("marker must be present", value.contains("truncated"))
    }

    @Test
    fun `small text part is untouched`() {
        val json = """[{"type":"text","value":"plain words"}]"""
        val out = ConfigBackup.sanitizeChatParts(json)!!
        assertEquals("plain words", JSONArray(out).getJSONObject(0).optString("value"))
    }

    @Test
    fun `oversized toolUse input is capped with marker`() {
        // Mirror of the production write shape: input is an escaped JSON
        // STRING inside value (ChatViewModel.buildAssistantPartsJson).
        val hugeArg = """{"command":"${"x".repeat(50_000)}"}"""
        val json = """
            [{"type":"toolUse","value":{
                "toolUseId":"call_1",
                "name":"shell_execute",
                "input":"${hugeArg.replace("\"", "\\\"")}",
                "description":""
            }}]
        """.trimIndent()
        val out = ConfigBackup.sanitizeChatParts(json)
        assertNotNull(out)
        val tu = JSONArray(out!!).getJSONObject(0).getJSONObject("value")
        val input = tu.optString("input")
        assertTrue(
            "toolUse input must be capped near the limit",
            input.length <= ConfigBackup.MAX_BACKUP_TOOL_INPUT_CHARS + 64,
        )
        assertTrue("marker must be present", input.contains("truncated"))
        assertEquals("toolUseId must survive", "call_1", tu.optString("toolUseId"))
        assertEquals("name must survive", "shell_execute", tu.optString("name"))
    }

    @Test
    fun `small toolUse input is untouched`() {
        val json = """
            [{"type":"toolUse","value":{
                "toolUseId":"c2","name":"file_read","input":"{\"path\":\"/tmp/a\"}"
            }}]
        """.trimIndent()
        val out = ConfigBackup.sanitizeChatParts(json)!!
        val tu = JSONArray(out).getJSONObject(0).getJSONObject("value")
        assertEquals("""{"path":"/tmp/a"}""", tu.optString("input"))
    }

    // ── chatTruncated visibility (payload shape import reads) ───────────

    @Test
    fun `chatTruncated block carries the drop counters`() {
        val tr = JSONObject()
            .put("sessionsDropped", 3)
            .put("messagesDropped", 412)
            .put("budgetBytes", ConfigBackup.MAX_PAYLOAD_BYTES)
        val doc = JSONObject().put("chatTruncated", tr)
        val parsed = JSONObject(doc.toString())
        assertEquals(3, parsed.getJSONObject("chatTruncated").optInt("sessionsDropped"))
        assertEquals(412, parsed.getJSONObject("chatTruncated").optInt("messagesDropped"))
    }

    @Test
    fun `legacy payload without chatTruncated still parses as absent`() {
        val doc = JSONObject().put("chatSessions", JSONArray()).put("chatMessages", JSONArray())
        val parsed = JSONObject(doc.toString())
        assertNull(parsed.optJSONObject("chatTruncated"))
    }
}
