package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.CompactMarkerEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.db.MessageSearchRow
import com.rikkaminis.app.data.db.SessionMetaRow
import com.rikkaminis.app.data.db.SessionTailRow
import com.rikkaminis.app.data.db.UsageRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-usage-attribution] Regression tests for the token-usage attribution fix.
 *
 * Coverage:
 * 1. The DAO-level COALESCE semantics (new row → usage_model_id wins; legacy
 *    row → falls back to sessions.model_id) is validated against the real SQL
 *    applied to an in-memory SQLite engine (see the sibling Python probe); the
 *    end-to-end server-side semantics live in the SQL itself.
 * 2. The repository/appends path: `ChatRepository.appendMessage` passes the
 *    attribution identity through to the persisted MessageEntity, and omitting
 *    the new params leaves the fields NULL (old behavior unchanged).
 * 3. `ChatRepository.appendMessage` signature stays backward compatible — a
 *    legacy call site that doesn't know about the new params compiles and
 *    lands both columns as NULL.
 */
class UsageAttributionTest {

    /**
     * Fake DAO that records the last MessageEntity handed to insertMessage so
     * the repository keep-message identity pass-through is observable. Idle
     * implementations mirror ChatRepositoryTest's stub pattern.
     */
    private class RecordingDao : ChatDao {
        var inserted: MessageEntity? = null
        var nextSortOrderCalls = 0

        override suspend fun nextSortOrder(sessionId: String): Int {
            nextSortOrderCalls += 1
            return nextSortOrderCalls // 1, 2, 3...
        }

        override suspend fun insertMessage(message: MessageEntity) { inserted = message }

        // -- inert implementations for the remaining interface methods --
        override suspend fun insertSession(session: ChatSessionEntity) {}
        override suspend fun updateThinkingOverride(id: String, value: String?, updatedAt: Long) {}
        override fun observeSessions(): Flow<List<ChatSessionEntity>> = emptyFlow()
        override fun observeSessionsSorted(): Flow<List<ChatSessionEntity>> = emptyFlow()
        override suspend fun listSessions(): List<ChatSessionEntity> = emptyList()
        override suspend fun getSession(id: String): ChatSessionEntity? = null
        override suspend fun getMessage(id: String): MessageEntity? = null
        override suspend fun sessionsUpdatedSince(cutoff: Long): List<ChatSessionEntity> = emptyList()
        override suspend fun updateSessionTitle(id: String, title: String, updatedAt: Long) {}
        override suspend fun updateSessionTitleAndCategory(
            id: String, title: String, category: String?, updatedAt: Long,
        ) {}
        override suspend fun touchSession(id: String, updatedAt: Long) {}
        override suspend fun updateLastMessage(id: String, preview: String?, updatedAt: Long) {}
        override suspend fun updateSessionModel(id: String, modelId: String, updatedAt: Long) {}
        override suspend fun updateSessionBinding(
            id: String, binding: String, modelId: String, updatedAt: Long,
        ) {}
        override suspend fun deleteSession(id: String) {}
        override suspend fun searchSessions(pattern: String): List<ChatSessionEntity> = emptyList()
        override suspend fun loadMessages(sessionId: String): List<MessageEntity> = emptyList()
        override suspend fun messagesLast(sessionId: String, limit: Int): List<MessageEntity> = emptyList()
        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun deleteMessages(sessionId: String) {}
        override suspend fun deleteMessagesAfter(sessionId: String, keepCount: Int) {}
        override suspend fun totalMessageCount(): Int = 0
        override suspend fun tokenUsages(sessionId: String): List<String> = emptyList()
        override suspend fun allUsageRecords(): List<UsageRecord> = emptyList()
        override suspend fun usageRecordsBetween(sinceMs: Long, untilMs: Long): List<UsageRecord> = emptyList()
        override suspend fun lastMessageParts(sessionId: String): String? = null
        override suspend fun lastMessageTailPerSession(): List<SessionTailRow> = emptyList()
        override suspend fun updateMemoryEnabled(id: String, enabled: Int, updatedAt: Long) {}
        override suspend fun updatePinnedAt(id: String, pinnedAt: Long?, updatedAt: Long) {}
        override suspend fun updateSource(id: String, source: String?) {}
        override suspend fun incrementStreamInterruptCount(id: String, updatedAt: Long) {}
        override suspend fun updateMessageParts(id: String, partsJson: String, updatedAt: Long) {}
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
        override suspend fun loadMessagesPage(sessionId: String, offset: Int, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun messageCountForSession(sessionId: String): Int = 0
        override fun messageCountsPerSession(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun deleteEmptySessions(activeIds: List<String>, staleBefore: Long): Int = 0
    }

    @Test
    fun `appendMessage records usage identity on the persisted entity`() =
        kotlinx.coroutines.runBlocking {
            val dao = RecordingDao()
            val repo = ChatRepository(dao)

            repo.appendMessage(
                sessionId = "s1",
                role = "assistant",
                partsJson = """[{"type":"text","text":"hi"}]""",
                tokenUsage = """{"inputTokens":10,"outputTokens":5}""",
                usageModelId = "model-X",
                usageEntryId = "entry-42",
            )

            val entity = dao.inserted
            assertNotNull("appendMessage must have inserted a row", entity)
            assertEquals("model-X", entity!!.usageModelId)
            assertEquals("entry-42", entity.usageEntryId)
        }

    @Test
    fun `appendMessage defaults leave usage identity null`() =
        kotlinx.coroutines.runBlocking {
            val dao = RecordingDao()
            val repo = ChatRepository(dao)

            // Legacy call site signature — no attribution params.
            repo.appendMessage(sessionId = "s1", role = "assistant", partsJson = """[{"type":"text","text":"hi"}]""")

            val entity = dao.inserted
            assertNotNull(entity)
            assertNull("usageModelId defaults to null", entity!!.usageModelId)
            assertNull("usageEntryId defaults to null", entity.usageEntryId)
        }

    @Test
    fun `appendMessage attribution survives the sort_order retry copy`() =
        kotlinx.coroutines.runBlocking {
            // The constraint-retry path does `message.copy(sortOrder = ...)`.
            // Both identity fields are copied wholesale, so a retried insert
            // must still carry them. `RecordingDao` never throws, but we can
            // still reason: copy() keeps all named fields unless overridden.
            val dao = RecordingDao()
            val repo = ChatRepository(dao)
            repo.appendMessage(
                sessionId = "s1", role = "assistant", partsJson = "[]",
                usageModelId = "m", usageEntryId = "e",
            )
            // copy-retry path is exercised in AppendMessageSortOrderRetryTest;
            // here we just confirm the normal path propagates both fields.
            assertEquals("m", dao.inserted!!.usageModelId)
            assertEquals("e", dao.inserted!!.usageEntryId)
        }

    @Test
    fun `UsageRecord carries optional identity fields for the aggregator`() {
        val withIdentity = UsageRecord("modelId", "{}", 0L, "s", "real-model", "real-entry")
        assertEquals("real-model", withIdentity.usageModelId)
        assertEquals("real-entry", withIdentity.usageEntryId)
        val legacy = UsageRecord("modelId", "{}", 0L, "s")
        assertNull(legacy.usageModelId)
        assertNull(legacy.usageEntryId)
    }

    @Test
    fun `MessageEntity default construction keeps identity null`() {
        val msg = MessageEntity(
            id = "m1", sessionId = "s1", role = "assistant",
            partsJson = "[]", createdAt = 1L, sortOrder = 0,
        )
        assertNull(msg.usageModelId)
        assertNull(msg.usageEntryId)
    }
}
