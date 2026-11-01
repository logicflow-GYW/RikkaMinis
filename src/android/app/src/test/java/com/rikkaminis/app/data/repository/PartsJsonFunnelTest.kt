package com.rikkaminis.app.data.repository

import com.rikkaminis.app.backup.importChatSections
import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.InternalUsageEventEntity
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.db.MessageSearchRow
import com.rikkaminis.app.data.db.SessionMetaRow
import com.rikkaminis.app.data.db.SessionTailRow
import com.rikkaminis.app.data.db.UsageRecord
import com.rikkaminis.app.data.db.UsageStatsRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1019-partsjson-funnel] ISSUE-06: three paths bypassed the existing
 * ceilings, so an oversized row could re-enter the database and abort the next
 * read with `SQLiteBlobTooBigException` (the Issue #17 CursorWindow ceiling).
 *
 *  1. READ — `ChatRepository.loadMessagePage`'s unbounded branch called
 *     `dao.loadMessagesPage` directly instead of funnelling through the
 *     tolerant pager (`SessionsOffloadHandler` / `minis-sessions-cli messages`
 *     got the raw exception for a session with one >2 MB row).
 *  2. WRITE — `ChatRepository.updateMessageParts` called `dao.updateMessageParts`
 *     without `capPartsJsonForRow` (`ChatViewModel.rerunFromToolBlock` rewrites
 *     a row in place with parts cloned out of an existing row).
 *  3. IMPORT — `ConfigBackup.importChatSections` constructed `MessageEntity`
 *     straight from the payload: neither `parts_json` nor `reasoning_content`
 *     was capped, so a restore could install the very blob the write paths
 *     refuse to create.
 *
 * Every expectation here is a literal (the budget, the marker, the page shape)
 * rather than a value derived from the constant under test — a test that
 * derives its own expectation from the constant cannot see the constant move.
 */
class PartsJsonFunnelTest {

    // ------------------------------------------------------------------
    // Fake DAO: records which paged read shape was used and which payload
    // reached the write path; can fail the read of a chosen offset.
    // ------------------------------------------------------------------

    private class RecordingDao(
        private val rows: List<MessageEntity>,
        private val failOffsets: Set<Int> = emptySet(),
    ) : ChatDao {
        /** offset, limit, "unbounded" | "range" — in call order. */
        val pageReads = mutableListOf<Triple<Int, Int, String>>()
        val inserted = mutableListOf<MessageEntity>()
        val partsWrites = mutableListOf<String>()
        var rangeReads = 0

        private fun failure(): Nothing = throw IllegalStateException(
            "Couldn't read row 0, col 1 from CursorWindow. " +
                "Make sure the Cursor is initialized correctly before accessing data from it.",
        )

        override suspend fun loadMessagesPage(
            sessionId: String,
            offset: Int,
            limit: Int,
        ): List<MessageEntity> {
            pageReads.add(Triple(offset, limit, "unbounded"))
            if (failOffsets.any { it in offset until (offset + limit) }) failure()
            return rows.drop(offset).take(limit)
        }

        override suspend fun loadMessagesPageInRange(
            sessionId: String,
            offset: Int,
            limit: Int,
            startMs: Long?,
            endMs: Long?,
        ): List<MessageEntity> {
            pageReads.add(Triple(offset, limit, "range"))
            rangeReads += 1
            if (failOffsets.any { it in offset until (offset + limit) }) failure()
            return rows.drop(offset).take(limit)
        }

        override suspend fun insertMessage(message: MessageEntity) {
            inserted.add(message)
        }

        override suspend fun updateMessageParts(id: String, partsJson: String, updatedAt: Long) {
            partsWrites.add(partsJson)
        }

        // -- inert implementations for the remaining interface methods --
        override suspend fun loadMessages(sessionId: String): List<MessageEntity> = rows
        override suspend fun insertSession(session: ChatSessionEntity) {}
        override suspend fun insertInternalUsageEvent(event: InternalUsageEventEntity) {}
        override suspend fun internalUsageEventsBetween(sinceMs: Long, untilMs: Long): List<InternalUsageEventEntity> = emptyList()
        override suspend fun updateThinkingOverride(id: String, value: String?, updatedAt: Long) {}
        override suspend fun updateSessionTitle(id: String, title: String, updatedAt: Long) {}
        override suspend fun updateSessionTitleAndCategory(
            id: String, title: String, category: String?, updatedAt: Long,
        ) {}
        override suspend fun touchSession(id: String, updatedAt: Long) {}
        override suspend fun updateLastMessage(id: String, preview: String?, updatedAt: Long) {}
        override fun observeSessions(): Flow<List<ChatSessionEntity>> = emptyFlow()
        override fun observeSessionsSorted(): Flow<List<ChatSessionEntity>> = emptyFlow()
        override suspend fun listSessions(): List<ChatSessionEntity> = emptyList()
        override suspend fun getSession(id: String): ChatSessionEntity? = null
        override suspend fun getMessage(id: String): MessageEntity? = null
        override suspend fun sessionsUpdatedSince(cutoff: Long): List<ChatSessionEntity> = emptyList()
        override suspend fun updateSessionModel(id: String, modelId: String, updatedAt: Long) {}
        override suspend fun updateSessionBinding(
            id: String, binding: String, modelId: String, updatedAt: Long,
        ) {}
        override suspend fun deleteSession(id: String) {}
        override suspend fun searchSessions(pattern: String): List<ChatSessionEntity> = emptyList()
        override suspend fun messagesLast(sessionId: String, limit: Int): List<MessageEntity> = emptyList()
        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun nextSortOrder(sessionId: String): Int = 0
        override suspend fun deleteMessages(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int) {}
        override suspend fun totalMessageCount(): Int = 0
        override suspend fun tokenUsages(sessionId: String): List<String> = emptyList()
        override suspend fun allUsageRecords(): List<UsageRecord> = emptyList()
        override suspend fun usageRecordsBetween(sinceMs: Long, untilMs: Long): List<UsageRecord> = emptyList()
        override suspend fun json1Probe(): Int = 1
        override suspend fun usageStatsAggregated(sinceMs: Long, untilMs: Long): List<UsageStatsRow> = emptyList()
        override suspend fun lastMessageParts(sessionId: String): String? = null
        override suspend fun lastMessageTailPerSession(): List<SessionTailRow> = emptyList()
        override suspend fun updateMemoryEnabled(id: String, enabled: Int, updatedAt: Long) {}
        override suspend fun updatePinnedAt(id: String, pinnedAt: Long?, updatedAt: Long) {}
        override suspend fun updateSource(id: String, source: String?) {}
        override suspend fun incrementStreamInterruptCount(id: String, updatedAt: Long) {}
        override suspend fun updateMessageErrorInfo(messageId: String, errorInfo: String?) {}
        override suspend fun updateLastAssistantError(sessionId: String, errorInfo: String?) {}
        override suspend fun insertCompactMarker(marker: CompactMarkerEntity) {}
        override suspend fun updateCompactMarker(marker: CompactMarkerEntity) {}
        override suspend fun latestCompactMarker(sessionId: String): CompactMarkerEntity? = null
        override suspend fun listCompactMarkers(sessionId: String): List<CompactMarkerEntity> = emptyList()
        override suspend fun deleteCompactMarkers(sessionId: String) {}
        override suspend fun deleteCompactMarker(id: String): Int = 0
        override suspend fun runSessionsMetaQuery(query: androidx.sqlite.db.SupportSQLiteQuery): List<SessionMetaRow> = emptyList()
        override suspend fun runMessageSearchQuery(query: androidx.sqlite.db.SupportSQLiteQuery): List<MessageSearchRow> = emptyList()
        override suspend fun messageCountForSession(sessionId: String): Int = rows.size
        override suspend fun messageCountForSessionInRange(
            sessionId: String,
            startMs: Long?,
            endMs: Long?,
        ): Int = rows.size
        override fun messageCountsPerSession(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun deleteEmptySessions(activeIds: List<String>, staleBefore: Long): Int = 0
        // [dv21-a2] inert: this test never runs the empty-session sweep.
        override suspend fun selectEmptySessionIds(
            activeIds: List<String>,
            staleBefore: Long,
        ): List<String> = emptyList()
        override suspend fun existingSessionIds(ids: List<String>): List<String> = emptyList()
    }

    private fun rows(n: Int): List<MessageEntity> = (0 until n).map {
        MessageEntity(
            id = "m$it",
            sessionId = "s1",
            role = "user",
            partsJson = """[{"type":"text","value":"row $it"}]""",
            createdAt = 0L,
            sortOrder = it,
        )
    }

    private fun partsJsonOf(text: String): String =
        JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString()

    // ------------------------------------------------------------------
    // 1. READ — loadMessagePage funnels through the tolerant pager
    // ------------------------------------------------------------------

    @Test
    fun `loadMessagePage unbounded branch degrades instead of throwing`() = runBlocking {
        val dao = RecordingDao(rows(3), failOffsets = setOf(1))
        // limit=3 keeps the row-by-row walk bounded: one batched read (0,3)
        // that overflows, then three single-row reads.
        val page = ChatRepository(dao).loadMessagePage("s1", 0, 3)

        // 3 rows in, 3 items out: the un-materialisable row survives as an
        // elided proxy row rather than aborting the offload read.
        assertEquals(listOf("m0", "oversized-s1-1", "m2"), page.map { it.messageId })
        assertEquals(
            listOf("row 0", "[oversized content elided]", "row 2"),
            page.map { it.text },
        )
        // The first read is the batched page (0,200); the fallback then walks
        // single rows. Reaching the DAO batch at all is the point — the bug was
        // aborting on it.
        assertEquals(Triple(0, 3, "unbounded"), dao.pageReads.first())
        assertEquals(4, dao.pageReads.size)
        assertEquals(0, dao.rangeReads)
    }

    @Test
    fun `loadMessagePage ranged branch degrades through the same funnel`() = runBlocking {
        val dao = RecordingDao(rows(3), failOffsets = setOf(1))
        val page = ChatRepository(dao).loadMessagePage(
            "s1", 0, 3, startMs = 5_000L, endMs = 9_000L,
        )

        assertEquals(listOf("m0", "oversized-s1-1", "m2"), page.map { it.messageId })
        // The date-filtered shape is the one that used to escape the funnel;
        // both the batched read and the row-by-row fallback must use it.
        assertEquals(Triple(0, 3, "range"), dao.pageReads.first())
        assertEquals(4, dao.pageReads.size)
        assertEquals(4, dao.rangeReads)
    }

    @Test
    fun `loadMessagePage returns the plain page when nothing overflows`() = runBlocking {
        val dao = RecordingDao(rows(3))
        val page = ChatRepository(dao).loadMessagePage("s1", 0, 200)

        assertEquals(listOf("m0", "m1", "m2"), page.map { it.messageId })
        assertEquals(listOf("row 0", "row 1", "row 2"), page.map { it.text })
        // Precision arm: the fallback must NOT run when the page materialises.
        assertEquals(1, dao.pageReads.size)
    }

    @Test
    fun `loadMessagePage keeps blank rows out of the slice`() = runBlocking {
        val dao = RecordingDao(
            listOf(
                MessageEntity(
                    id = "m0", sessionId = "s1", role = "user",
                    partsJson = """[{"type":"text","value":"hello"}]""", createdAt = 0L, sortOrder = 0,
                ),
                MessageEntity(
                    id = "m1", sessionId = "s1", role = "assistant",
                    // tool_use whose payload is not an object -> no text, no tool
                    // names -> blank projection -> dropped from the slice.
                    partsJson = """[{"type":"toolUse","value":"plain-string"}]""",
                    createdAt = 0L, sortOrder = 1,
                ),
            ),
        )
        val page = ChatRepository(dao).loadMessagePage("s1", 0, 200)
        // Existing projection semantics survive the funnel change.
        assertEquals(listOf("m0"), page.map { it.messageId })
    }

    // ------------------------------------------------------------------
    // 2. WRITE — updateMessageParts applies the same ceiling
    // ------------------------------------------------------------------

    @Test
    fun `updateMessageParts caps an oversized rewrite and keeps the row loadable`() = runBlocking {
        val dao = RecordingDao(emptyList())
        val huge = partsJsonOf("x".repeat(900_000))
        assertEquals(900_028, huge.length)

        ChatRepository(dao).updateMessageParts("m1", huge)

        assertEquals(1, dao.partsWrites.size)
        val written = dao.partsWrites[0]
        assertTrue(
            "capped payload must fit the 500 KB byte budget, was " +
                "${written.toByteArray(Charsets.UTF_8).size}",
            written.toByteArray(Charsets.UTF_8).size <= 500_000,
        )
        // The marker is asserted in two dash-free fragments: buildTruncatedPartsJson
        // re-wraps the payload through org.json, and org.json's JSONWriter escapes
        // the em dash (U+2014 sits in the \u2000-\u20ff band) to `\u2014`. That
        // escaping differs between the platform JSON and the library JSON, so a
        // whole-marker literal would be a test that fails for the wrong reason.
        assertTrue(written.contains("Content truncated at 500 KB"))
        assertTrue(written.contains("original length 900028 chars]"))
        assertFalse("the original payload must not survive intact", written.contains("x".repeat(600_000)))
    }

    @Test
    fun `updateMessageParts passes a fitting payload through untouched`() = runBlocking {
        val dao = RecordingDao(emptyList())
        val small = partsJsonOf("kept verbatim")
        ChatRepository(dao).updateMessageParts("m1", small)
        assertEquals(listOf(small), dao.partsWrites)
    }

    // ------------------------------------------------------------------
    // 3. IMPORT — a restored row takes the same two ceilings
    // ------------------------------------------------------------------

    @Test
    fun `importChatSections caps an oversized restored row`() = runBlocking {
        val dao = RecordingDao(emptyList())
        val hugeParts = partsJsonOf("y".repeat(900_000))
        val hugeReasoning = "z".repeat(900_000)
        val messages = JSONArray().put(
            JSONObject()
                .put("id", "restored-1")
                .put("sessionId", "s1")
                .put("role", "assistant")
                .put("partsJson", hugeParts)
                .put("createdAt", 1_700_000_000_000L)
                .put("sortOrder", 0)
                .put("reasoningContent", hugeReasoning),
        )

        val (sessions, imported) = importChatSections(
            ChatRepository(dao), null, messages, mutableListOf(),
        )

        assertEquals(0, sessions)
        assertEquals(1, imported)
        assertEquals(1, dao.inserted.size)
        val row = dao.inserted[0]
        assertTrue(
            "restored parts_json must fit the 500 KB byte budget, was " +
                "${row.partsJson.toByteArray(Charsets.UTF_8).size}",
            row.partsJson.toByteArray(Charsets.UTF_8).size <= 500_000,
        )
        // parts_json is re-wrapped through org.json, which escapes the em dash
        // (\u2014) — assert the two dash-free fragments (see the write test above).
        assertTrue(row.partsJson.contains("Content truncated at 500 KB"))
        assertTrue(row.partsJson.contains("original length 900028 chars]"))
        val reasoning = row.reasoningContent
        assertTrue("reasoning must be capped too, was $reasoning", reasoning != null)
        assertTrue(
            "restored reasoning_content must fit the byte budget, was " +
                "${reasoning!!.toByteArray(Charsets.UTF_8).size}",
            reasoning.toByteArray(Charsets.UTF_8).size <= 500_000,
        )
        // reasoning_content is stored as plain text, never re-wrapped as JSON,
        // so here the whole marker literal — em dash included — must survive.
        assertTrue(reasoning.contains("[Content truncated at 500 KB — original length 900000 chars]"))
    }

    @Test
    fun `importChatSections leaves a fitting row byte-identical`() = runBlocking {
        val dao = RecordingDao(emptyList())
        val smallParts = partsJsonOf("hello")
        val messages = JSONArray().put(
            JSONObject()
                .put("id", "restored-2")
                .put("sessionId", "s1")
                .put("role", "user")
                .put("partsJson", smallParts)
                .put("createdAt", 1L)
                .put("sortOrder", 3)
                .put("reasoningContent", "short thoughts"),
        )

        val (_, imported) = importChatSections(
            ChatRepository(dao), null, messages, mutableListOf(),
        )

        assertEquals(1, imported)
        val row = dao.inserted[0]
        assertEquals(smallParts, row.partsJson)
        assertEquals("short thoughts", row.reasoningContent)
        assertEquals("restored-2", row.id)
        assertEquals(3, row.sortOrder)
    }
}
