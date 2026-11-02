package com.rikkaminis.app.data.repository

import android.database.sqlite.SQLiteConstraintException
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv29-A2] `appendMessage` must replay ONE constraint and classify the rest.
 *
 * ## The defect this pins
 *
 * `appendMessage` caught `SQLiteConstraintException` and assumed it was always
 * the `unique(session_id, sort_order)` race: re-read `nextSortOrder`, replay the
 * row. Every other constraint took that path too. The one that mattered is the
 * foreign key on `messages.session_id → chat_sessions.id` (code 787): for a
 * session with no row, `nextSortOrder` answers 0, the replay repeats the
 * identical insert against the identical missing parent, the second throw left
 * the catch, and as an uncaught coroutine exception it killed the process.
 * Device logs, 2026-10-24 12:57 ×2 (`minis-sessions-cli send --session
 * <missing id>`):
 *
 * ```
 * applyModelOverride: session '00000000-…' has no persisted row …
 * appendMessage: enter session=00000000-… role=user
 * appendMessage: constraint-retry re-order=0 (original=0)   ← the replay
 * SQLiteConstraintException: FOREIGN KEY constraint failed (code 787 …)
 * ACRA caught a SQLiteConstraintException … (process died)
 * ```
 *
 * ## What is pinned here
 *
 * 1. `appendMessage` on a foreign-key failure surfaces a CLASSIFIED
 *    [MessageInsertRejectedException] instead of the raw constraint exception,
 *    carrying the session id — the caller can answer with an error, and nothing
 *    dies.
 * 2. The sort_order race still retries (the reason [RC15]'s catch exists). This
 *    is the control arm: it fails if the fix over-tightens the gate.
 * 3. A second failure during the replay is classified too — the replay exists
 *    exactly once, and its failure never escapes raw.
 *
 * The discriminator itself is pinned separately, with literals and no Android
 * involved, in `MessageInsertConstraintClassifyTest`.
 *
 * ## Why the FK arm does not assert `SESSION_ROW_MISSING` outright
 *
 * `ChatRepository` classifies from `SQLiteConstraintException.message`, and the
 * value of that message under the unit-test android.jar depends on whether the
 * generated mockable jar keeps the exception constructor's `super(message)`.
 * The classifier is pinned exactly by the sibling test; this file pins the
 * *contract that matters*: whatever the classification, the raw constraint
 * exception never escapes and the failure is never mistaken for the replayable
 * race.
 */
class MessageInsertRetryGateTest {

    // ── 1. appendMessage: the FK failure is classified, not raw ─────────────

    @Test
    fun `appendMessage reports a foreign key failure as a classified rejection`() =
        kotlinx.coroutines.runBlocking {
            val dao = ThrowingDao(
                "FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)",
            )
            val repo = ChatRepository(dao)

            val thrown = try {
                repo.appendMessage(
                    sessionId = "00000000-0000-0000-0000-000000000000",
                    role = "user",
                    partsJson = """[{"type":"text","text":"hi"}]""",
                )
                null
            } catch (e: Throwable) {
                e
            }

            assertTrue(
                "a raw SQLiteConstraintException escaping is exactly what killed the " +
                    "process; got ${thrown?.javaClass?.name}",
                thrown is MessageInsertRejectedException,
            )
            val rejected = thrown as MessageInsertRejectedException
            assertEquals(
                "00000000-0000-0000-0000-000000000000",
                rejected.sessionId,
            )
            assertNotEquals(
                "a missing FK parent must never be classified as the replayable race",
                MessageInsertConflict.SORT_ORDER,
                rejected.conflict,
            )
            assertTrue(
                "the insert may be attempted at most twice (one bounded replay)",
                dao.insertAttempts <= 2,
            )
        }

    // ── 2. control: the race still retries ─────────────────────────────────

    @Test
    fun `appendMessage still retries the sort order race`() =
        kotlinx.coroutines.runBlocking {
            val dao = ThrowingDao(
                "UNIQUE constraint failed: messages.session_id, messages.sort_order",
                failFirstOnly = true,
            )
            val repo = ChatRepository(dao)

            val persisted = repo.appendMessage(
                sessionId = "s1",
                role = "user",
                partsJson = """[{"type":"text","text":"hi"}]""",
            )

            assertEquals("one replay after the collision", 2, dao.nextSortOrderCalls)
            assertEquals(1, dao.inserted.size)
            assertEquals(
                "the retried row uses the SECOND nextSortOrder value",
                10,
                persisted.sortOrder,
            )
        }

    @Test
    fun `a second failure during the replay is classified too`() =
        kotlinx.coroutines.runBlocking {
            val dao = ThrowingDao(
                "UNIQUE constraint failed: messages.session_id, messages.sort_order",
                failFirstOnly = false,
            )
            val repo = ChatRepository(dao)

            val thrown = try {
                repo.appendMessage(
                    sessionId = "s1",
                    role = "user",
                    partsJson = """[{"type":"text","text":"hi"}]""",
                )
                null
            } catch (e: Throwable) {
                e
            }

            assertTrue(
                "the replay's own failure must reach the caller classified, got " +
                    "${thrown?.javaClass?.name}",
                thrown is MessageInsertRejectedException,
            )
            assertEquals("s1", (thrown as MessageInsertRejectedException).sessionId)
            assertEquals("initial read + one replay read", 2, dao.nextSortOrderCalls)
            assertEquals("the insert ran twice, no more", 2, dao.insertAttempts)
        }

    /**
     * DAO whose `insertMessage` throws a constraint exception whose message is
     * [message]; when [failFirstOnly] the second attempt lands.
     */
    private class ThrowingDao(
        private val constraintMessage: String,
        private val failFirstOnly: Boolean = false,
    ) : InertChatDao() {
        var insertAttempts = 0
        var nextSortOrderCalls = 0
        val inserted = mutableListOf<MessageEntity>()

        override suspend fun nextSortOrder(sessionId: String): Int {
            nextSortOrderCalls += 1
            return if (nextSortOrderCalls == 1) 5 else 10
        }

        override suspend fun insertMessage(message: MessageEntity) {
            insertAttempts += 1
            if (failFirstOnly && insertAttempts > 1) {
                inserted.add(message)
                return
            }
            // Named `constraintMessage`, not `message`: the parameter of
            // `insertMessage(message: MessageEntity)` shadows a property called
            // `message`, and the mismatch only surfaces in the test compile
            // (dv29-A2 run 40070578165 failed exactly there).
            throw SQLiteConstraintException(constraintMessage)
        }
    }
}

/**
 * Every [ChatDao] member inert. `open` on the handful the arms above override.
 * Deliberately duplicated from `AppendMessageSortOrderRetryTest` rather than
 * lifted into a shared helper: that file's fake is `private` and frozen, and a
 * shared fixture would have to be edited by both.
 */
internal open class InertChatDao : ChatDao {
    open override suspend fun nextSortOrder(sessionId: String): Int = 0
    open override suspend fun insertMessage(message: MessageEntity) {}
    override suspend fun insertSession(session: ChatSessionEntity) {}
    override suspend fun insertInternalUsageEvent(event: InternalUsageEventEntity) {}
    override suspend fun internalUsageEventsBetween(sinceMs: Long, untilMs: Long): List<InternalUsageEventEntity> = emptyList()
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
    override suspend fun loadUserMessagesSince(since: Long, limit: Int): List<MessageEntity> =
        emptyList()
    override suspend fun deleteMessages(sessionId: String) {}
    override suspend fun deleteMessagesAfter(sessionId: String, fromSortOrder: Int) {}
    override suspend fun totalMessageCount(): Int = 0
    override suspend fun tokenUsages(sessionId: String): List<String> = emptyList()
    override suspend fun allUsageRecords(): List<UsageRecord> = emptyList()
    override suspend fun usageRecordsBetween(sinceMs: Long, untilMs: Long): List<UsageRecord> =
        emptyList()
    override suspend fun json1Probe(): Int = 1
    override suspend fun usageStatsAggregated(
        sinceMs: Long, untilMs: Long,
    ): List<UsageStatsRow> = emptyList()
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
    override suspend fun listCompactMarkers(sessionId: String): List<CompactMarkerEntity> =
        emptyList()
    override suspend fun deleteCompactMarkers(sessionId: String) {}
    override suspend fun deleteCompactMarker(id: String): Int = 0
    override suspend fun runSessionsMetaQuery(
        query: androidx.sqlite.db.SupportSQLiteQuery,
    ): List<SessionMetaRow> = emptyList()
    override suspend fun runMessageSearchQuery(
        query: androidx.sqlite.db.SupportSQLiteQuery,
    ): List<MessageSearchRow> = emptyList()
    override suspend fun loadMessagesPage(
        sessionId: String, offset: Int, limit: Int,
    ): List<MessageEntity> = emptyList()
    override suspend fun loadMessagesPageInRange(
        sessionId: String,
        offset: Int,
        limit: Int,
        startMs: Long?,
        endMs: Long?,
    ): List<MessageEntity> = emptyList()
    override suspend fun messageCountForSession(sessionId: String): Int = 0
    override suspend fun messageCountForSessionInRange(
        sessionId: String,
        startMs: Long?,
        endMs: Long?,
    ): Int = 0
    override fun messageCountsPerSession(): Flow<Map<String, Int>> = flowOf(emptyMap())
    override suspend fun deleteEmptySessions(activeIds: List<String>, staleBefore: Long): Int = 0
    override suspend fun selectEmptySessionIds(
        activeIds: List<String>, staleBefore: Long,
    ): List<String> = emptyList()
    override suspend fun existingSessionIds(ids: List<String>): List<String> = emptyList()
}
