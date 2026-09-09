package com.rikkaminis.app.data.repository

import android.database.sqlite.SQLiteConstraintException
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RC15] Regression tests for the unique (session_id, sort_order) retry in
 * [ChatRepository.appendMessage].
 *
 * `nextSortOrder` reads MAX(sort_order)+1 outside a transaction, so two
 * concurrent appends in the same session can resolve the same value. The
 * unique index (added in MIGRATION_10_11) turns the second insert into a
 * SQLiteConstraintException; appendMessage must catch it, re-read the next
 * sort_order, and retry once rather than silently losing the row.
 */
class AppendMessageSortOrderRetryTest {

    /**
     * Fake DAO that throws [SQLiteConstraintException] on the FIRST insert and
     * succeeds on the second, returning a monotonically increasing sort order
     * each time `nextSortOrder` is asked. This mirrors the real race: first
     * append resolves sort_order=N and inserts OK; a concurrent append resolves
     * N too (stale read) and hits the unique violation; the retry re-reads and
     * lands on N+1.
     */
    private class RacingDao : ChatDao {
        var nextSortOrderCalls = 0
        val insertedSortOrders = mutableListOf<Int>()
        var failFirstInsert = true

        override suspend fun nextSortOrder(sessionId: String): Int {
            nextSortOrderCalls += 1
            // Deterministic: first call returns 5 (which then collides).
            return if (nextSortOrderCalls == 1) 5 else 10
        }

        override suspend fun insertMessage(message: MessageEntity) {
            if (failFirstInsert) {
                failFirstInsert = false
                throw SQLiteConstraintException("UNIQUE constraint failed: messages.session_id, messages.sort_order")
            }
            insertedSortOrders.add(message.sortOrder)
        }

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
    fun `appendMessage retries with a fresh sort_order on unique violation`() =
        kotlinx.coroutines.runBlocking {
            val dao = RacingDao()
            val repo = ChatRepository(dao)

            val result = repo.appendMessage(sessionId = "s1", role = "user", partsJson = """[{"type":"text","text":"hi"}]""")

            // The first insert threw, so the retry insert must have run and
            // landed on the SECOND nextSortOrder value (10), not the collided 5.
            assertEquals("retry must re-read sort_order", 2, dao.nextSortOrderCalls)
            assertEquals("exactly one insert must succeed", 1, dao.insertedSortOrders.size)
            assertEquals("retried row uses the fresh sort_order", 10, dao.insertedSortOrders[0])
            // The returned entity must reflect the actually-persisted row, so a
            // caller building an id→sort_order remap (SessionForkManager) gets
            // the real value, not the collided one.
            assertEquals("returned entity carries the retried sort_order", 10, result.sortOrder)
        }

    @Test
    fun `appendMessage does not retry when first insert succeeds`() =
        kotlinx.coroutines.runBlocking {
            val dao = RacingDao()
            dao.failFirstInsert = false
            val repo = ChatRepository(dao)

            repo.appendMessage(sessionId = "s1", role = "user", partsJson = """[{"type":"text","text":"hi"}]""")

            assertEquals("single insert, no retry", 1, dao.nextSortOrderCalls)
            assertEquals(1, dao.insertedSortOrders.size)
            assertEquals(5, dao.insertedSortOrders[0])
        }
}
