package com.rikkaminis.app.data.repository

import androidx.sqlite.db.SupportSQLiteQuery
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the lastMessage preview filter. The repository's
 * `stripSystemReminders` strips harness-injected `<system-reminder>` blocks
 * before the cleaned preview reaches the session-list row, so users never
 * see internal nudges like "task tools haven't been used recently".
 */
class ChatRepositoryTest {

    @Test
    fun `stripSystemReminders removes inline reminder block`() {
        val raw = "Hello <system-reminder>do this thing</system-reminder> world"
        assertEquals("Hello  world", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders removes multi-line reminder body`() {
        val raw = """
            User asked a question
            <system-reminder>
            The task tools haven't been used recently. Consider using TaskCreate.
            Make sure that you NEVER mention this reminder to the user
            </system-reminder>
            keep this content
        """.trimIndent()
        val cleaned = ChatRepository.stripSystemReminders(raw)
        // trimIndent strips the leading 12 spaces from every line, so the
        // pre/post-reminder lines have no indent. After the regex strips the
        // entire <system-reminder>...</system-reminder> block (DOTALL match),
        // what's left is "User asked a question\n\nkeep this content".
        assertEquals("User asked a question\n\nkeep this content", cleaned)
    }

    @Test
    fun `stripSystemReminders removes only-reminder content to empty`() {
        val raw = "<system-reminder>nothing else here</system-reminder>"
        assertEquals("", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders strips multiple back-to-back reminders independently`() {
        val raw = "a<system-reminder>x</system-reminder>b<system-reminder>y</system-reminder>c"
        // Reluctant quantifier prevents the two blocks merging into one match
        // that would also swallow the "b" between them.
        assertEquals("abc", ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves plain text unchanged`() {
        val raw = "Hello world, how are you today?"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    @Test
    fun `stripSystemReminders leaves markdown unchanged`() {
        val raw = "# Heading\n**bold** and `code` and a [link](https://example.com)"
        assertEquals(raw, ChatRepository.stripSystemReminders(raw))
    }

    // [T-empty-session-residue] guardActiveIds feeds the empty-session sweep's
    // SQL `IN (:activeIds)` clause. Room throws on an empty IN list, so an
    // empty active-set MUST become a non-empty sentinel list, and a populated
    // set MUST pass through untouched (otherwise the sweep could delete a
    // session that is currently open / mid first-send).

    @Test
    fun `guardActiveIds substitutes a sentinel for an empty active set`() {
        val guarded = ChatRepository.guardActiveIds(emptyList())
        // Never empty (would crash Room's IN clause)...
        assertEquals(1, guarded.size)
        // ...and the sentinel is the empty string, which cannot equal a real
        // session id (UUIDs / "__new__…" drafts are always non-empty).
        assertEquals("", guarded[0])
    }

    @Test
    fun `guardActiveIds passes a populated active set through unchanged`() {
        val active = listOf("sess-a", "__new__abc", "sess-b")
        assertEquals(active, ChatRepository.guardActiveIds(active))
    }

    @Test
    fun `guardActiveIds sentinel never collides with a real session id`() {
        // Defensive: the sweep excludes ids present in this list, so the
        // sentinel must match nothing. A real id is never the empty string.
        val sentinel = ChatRepository.guardActiveIds(emptyList()).single()
        assertNotEquals(sentinel, java.util.UUID.randomUUID().toString())
        assertNotEquals(sentinel, "__new__" + java.util.UUID.randomUUID())
    }
}

/**
 * Regression tests for [ChatRepository.createSession].
 *
 * P0-1 from the 2026-08-07 audit: the previous implementation ran
 * updateThinkingOverride() BEFORE insertSession() — an UPDATE that matched
 * zero rows because the session didn't exist yet, so a /thinking choice on a
 * draft chat was silently dropped. The fix folds the override into the row.
 *
 * These tests pin the contract: the entity handed to insertSession carries
 * the override, and NO separate update is issued before the insert.
 */
class ChatRepositoryCreateSessionTest {

    /** Records just the calls we care about; every other DAO method is inert. */
    private class RecordingDao : ChatDao {
        var insertedSession: ChatSessionEntity? = null
        val updateThinkingOverrideCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun insertSession(session: ChatSessionEntity) {
            insertedSession = session
        }

        override suspend fun updateThinkingOverride(
            id: String,
            value: String?,
            updatedAt: Long,
        ) {
            updateThinkingOverrideCalls.add(id to value)
        }

        // -- inert implementations for the remaining interface methods --
        override fun observeSessions(): Flow<List<ChatSessionEntity>> = emptyFlow()
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
        override suspend fun updateSessionModel(
            id: String, modelId: String, updatedAt: Long,
        ) {}
        override suspend fun updateSessionBinding(
            id: String, binding: String, modelId: String, updatedAt: Long,
        ) {}
        override suspend fun deleteSession(id: String) {}
        override suspend fun searchSessions(pattern: String): List<ChatSessionEntity> = emptyList()
        override suspend fun loadMessages(sessionId: String): List<MessageEntity> = emptyList()
        override suspend fun messagesLast(sessionId: String, limit: Int): List<MessageEntity> = emptyList()
        override fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun insertMessage(message: MessageEntity) {}
        override suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun nextSortOrder(sessionId: String): Int = 0
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
        override fun observeSessionsSorted(): Flow<List<ChatSessionEntity>> = emptyFlow()
        override suspend fun insertCompactMarker(marker: CompactMarkerEntity) {}
        override suspend fun updateCompactMarker(marker: CompactMarkerEntity) {}
        override suspend fun latestCompactMarker(sessionId: String): CompactMarkerEntity? = null
        override suspend fun listCompactMarkers(sessionId: String): List<CompactMarkerEntity> = emptyList()
        override suspend fun deleteCompactMarkers(sessionId: String) {}
        override suspend fun deleteCompactMarker(id: String): Int = 0
        override suspend fun runSessionsMetaQuery(query: SupportSQLiteQuery): List<SessionMetaRow> = emptyList()
        override suspend fun runMessageSearchQuery(query: SupportSQLiteQuery): List<MessageSearchRow> = emptyList()
        override suspend fun loadMessagesPage(sessionId: String, offset: Int, limit: Int): List<MessageEntity> = emptyList()
        override suspend fun messageCountForSession(sessionId: String): Int = 0
        override fun messageCountsPerSession(): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun deleteEmptySessions(activeIds: List<String>, staleBefore: Long): Int = 0
    }

    @Test
    fun `createSession folds thinking override into the inserted row`() = kotlinx.coroutines.runBlocking {
        val dao = RecordingDao()
        val repo = ChatRepository(dao)

        val session = repo.createSession(modelId = "m1", thinkingLevel = "HIGH")

        assertEquals("HIGH", session.thinkingOverride)
        assertEquals("HIGH", dao.insertedSession?.thinkingOverride)
        assertEquals(session.id, dao.insertedSession?.id)
        assertTrue(
            "updateThinkingOverride must not be called before the row is inserted",
            dao.updateThinkingOverrideCalls.isEmpty(),
        )
    }

    @Test
    fun `createSession without thinking level inserts null override`() = kotlinx.coroutines.runBlocking {
        val dao = RecordingDao()
        val repo = ChatRepository(dao)

        val session = repo.createSession(modelId = "m1")

        assertEquals(null, session.thinkingOverride)
        assertEquals(null, dao.insertedSession?.thinkingOverride)
    }

    @Test
    fun `createSession keeps memory enabled flag`() = kotlinx.coroutines.runBlocking {
        val dao = RecordingDao()
        val repo = ChatRepository(dao)

        val on = repo.createSession(modelId = "m1", memoryEnabled = true)
        val off = repo.createSession(modelId = "m1", memoryEnabled = false)

        assertEquals(1, on.memoryEnabled)
        assertEquals(0, off.memoryEnabled)
        // insertedSession is the LAST insert (the `off` session).
        assertEquals(0, dao.insertedSession?.memoryEnabled)
    }
}