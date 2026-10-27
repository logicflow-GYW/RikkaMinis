package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.db.ChatDao
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [fix/da1018-dao-loadmessages] ISSUE-08: a `.dao.loadMessages(` batch read
 * bypasses the CursorWindow-overflow degradation pipeline and aborts the whole
 * call as soon as a session contains one un-materialisable (>2 MB) row — the
 * same failure Issue #17 fixed inside [ChatRepository.loadMessages]. Twelve
 * production call sites (compaction, `minis-sessions-cli` debug/mutation paths,
 * headless runner) read the DAO directly instead.
 *
 * Two halves:
 *  - BEHAVIORAL: real [ChatRepository] against a faulting fake DAO. The
 *    degradation branch is driven through the `IllegalStateException("...
 *    CursorWindow...")` arm, which funnels into the identical
 *    `loadPageRowByRow` fallback as the `SQLiteBlobTooBigException` arm (the
 *    two arms were byte-identical code before this change). `loadMessagePageRaw`
 *    — the second unprotected entry point, used by the zip exporter — is
 *    covered the same way.
 *  - STRUCTURAL: no production source outside [ChatRepository]/[ChatDao] may
 *    call `.dao.loadMessages(`; count-only sites must use the existing
 *    `messageCount` instead of materialising rows.
 */
class DaoLoadMessagesConvergenceTest {

    // ------------------------------------------------------------------
    // Behavioral half — real ChatRepository, faulting fake DAO.
    // ------------------------------------------------------------------

    /**
     * Fake DAO over [rows]. When [failForOffset] falls inside the requested
     * page, `loadMessagesPage` throws the CursorWindow-wrapped overflow —
     * including for the single-row probe that `loadPageRowByRow` issues, so
     * the deepest arm (proxy-row substitution) is the one exercised.
     *
     * The batch method `loadMessages` deliberately blows up: reaching it from
     * a repository read path is the bug under test.
     */
    private class PagingDao(
        private val rows: List<MessageEntity>,
        private val failForOffset: Int = -1,
        private val failure: (() -> Throwable)? = null,
    ) : ChatDao {
        val pageReads = mutableListOf<Pair<Int, Int>>()
        var batchReads = 0

        override suspend fun loadMessages(sessionId: String): List<MessageEntity> {
            batchReads += 1
            return error("dao.loadMessages (batch) must not be reached — ISSUE-08")
        }

        override suspend fun messageCountForSession(sessionId: String): Int = rows.size

        override suspend fun loadMessagesPage(
            sessionId: String,
            offset: Int,
            limit: Int,
        ): List<MessageEntity> {
            pageReads.add(offset to limit)
            if (failure != null) throw failure.invoke()
            if (failForOffset >= 0 && failForOffset in offset until (offset + limit)) {
                throw IllegalStateException(
                    "Couldn't read row $failForOffset, col 1 from CursorWindow. " +
                        "Make sure the Cursor is initialized correctly before accessing data from it.",
                )
            }
            return rows.drop(offset).take(limit)
        }

        // -- inert implementations for the remaining interface methods --
        override suspend fun insertSession(session: ChatSessionEntity) {}
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
        override suspend fun insertMessage(message: MessageEntity) {}
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
        override suspend fun loadMessagesPageInRange(
            sessionId: String,
            offset: Int,
            limit: Int,
            startMs: Long?,
            endMs: Long?,
        ): List<MessageEntity> = emptyList()
        override suspend fun messageCountForSessionInRange(
            sessionId: String,
            startMs: Long?,
            endMs: Long?,
        ): Int = 0
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

    @Test
    fun `loadMessages substitutes a proxy row instead of throwing when one page overflows`() = runBlocking {
        val dao = PagingDao(rows(3), failForOffset = 1)
        val out = ChatRepository(dao).loadMessages("s1")

        // No throw, no hole: the un-materialisable row keeps its identity and
        // sort position as an elided proxy row (3 in → 3 out).
        assertEquals(listOf("m0", "oversized-s1-1", "m2"), out.map { it.id })
        assertTrue(out[1].partsJson.contains("oversized content elided"))
        assertEquals(0, dao.batchReads)
    }

    @Test
    fun `loadMessages returns every row in order when nothing overflows`() = runBlocking {
        val dao = PagingDao(rows(3))
        val out = ChatRepository(dao).loadMessages("s1")
        assertEquals(listOf("m0", "m1", "m2"), out.map { it.id })
        assertEquals(0, dao.batchReads)
    }

    @Test
    fun `loadMessagePageRaw degrades instead of aborting the export`() = runBlocking {
        val dao = PagingDao(rows(3), failForOffset = 1)
        val page = ChatRepository(dao).loadMessagePageRaw("s1", 0, 200)
        assertEquals(listOf("m0", "oversized-s1-1", "m2"), page.map { it.id })
        assertEquals(0, dao.batchReads)
    }

    @Test
    fun `a non-CursorWindow IllegalStateException still propagates`() = runBlocking {
        val boom = IllegalStateException("no such table: messages")
        val dao = PagingDao(rows(3), failure = { boom })
        val repo = ChatRepository(dao)
        try {
            repo.loadMessages("s1")
            fail("loadMessages must not swallow a genuine SQL failure")
        } catch (e: IllegalStateException) {
            assertEquals("no such table: messages", e.message)
        }
        try {
            repo.loadMessagePageRaw("s1", 0, 200)
            fail("loadMessagePageRaw must not swallow a genuine SQL failure")
        } catch (e: IllegalStateException) {
            assertEquals("no such table: messages", e.message)
        }
    }

    @Test
    fun `messageCount answers count-only reads without materialising rows`() = runBlocking {
        // Both read entry points blow up if touched; the count must not need them.
        val dao = PagingDao(
            rows(7),
            failure = { IllegalStateException("count path must not read pages") },
        )
        val repo = ChatRepository(dao)
        assertEquals(7, repo.messageCount("s1"))
        assertEquals(0, dao.batchReads)
        assertTrue(dao.pageReads.isEmpty())
    }

    // ------------------------------------------------------------------
    // Structural half — the convergence itself.
    // ------------------------------------------------------------------

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** `.../src/android` — the directory holding `app/src/main/java/...`. */
    private fun androidSrcRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "app/src/main/java/com/rikkaminis/app").isDirectory) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("app/src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private fun productionKotlinFiles(): List<File> =
        File(androidSrcRoot(), "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no production source calls dao loadMessages directly`() {
        val offenders = productionKotlinFiles()
            .filter { it.readText().contains(".dao.loadMessages(") }
            .map { it.name }
        assertEquals(
            "every production read must go through ChatRepository.loadMessages / loadMessagePageRaw",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the five converged files route through the repository`() {
        val expectations = mapOf(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt"
                to "chatRepository.loadMessages(sid).map { it.id }.toSet()",
            "app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt"
                to "val all = repo.loadMessages(sessionId)",
            "app/src/main/java/com/rikkaminis/app/debug/ChatMutationMethods.kt"
                to "val msgs = app.chatRepository.loadMessages(sessionId)",
            "app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt"
                to "val msgs = app.chatRepository.loadMessages(sessionId)",
        )
        expectations.forEach { (path, needle) ->
            assertTrue("$path must contain: $needle", repoFile(path).contains(needle))
        }
        assertFalse(repoFile("app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt")
            .contains("val msgs = repo.dao.loadMessages("))
    }

    @Test
    fun `count-only sites use messageCount instead of materialising rows`() {
        val debug = repoFile("app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt")
        assertTrue(debug.contains("val messageCount = repo.messageCount(sessionId)"))
        assertTrue(debug.contains("""put("messageCount", messageCount)"""))

        val headless = repoFile("app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt")
        assertTrue(headless.contains("val before = app.chatRepository.messageCount(sessionId)"))
        assertFalse(headless.contains("loadMessages(sessionId).size"))
    }

    @Test
    fun `ChatRepository keeps a single tolerant paged-read path`() {
        val src = repoFile("app/src/main/java/com/rikkaminis/app/data/repository/ChatRepository.kt")
        // A) the batch bypass is gone from the repository entirely
        assertFalse(src.contains("dao.loadMessages("))
        // B) every production paged entry funnels through one policy function:
        //    1 definition + 3 call sites (loadMessagesInner, loadMessagePageRaw,
        //    loadMessagePage). [fix/da1019-partsjson-funnel] re-anchored 3 -> 4:
        //    loadMessagePage's unbounded branch used to call the DAO directly,
        //    so the count legitimately grows with the convergence (see
        //    PartsJsonFunnelTest for the funnel's own pin).
        assertEquals(4, Regex("loadPageTolerant\\(").findAll(src).count())
        assertTrue(src.contains("val page = loadPageTolerant(sessionId, offset, limit)"))
        assertTrue(src.contains("        loadPageTolerant(sessionId, offset, limit)"))
        assertTrue(src.contains("val rows = loadPageTolerant(sessionId, offset, limit, startMs, endMs)"))
        // C) both CursorWindow arms (type + message-shaped) survive the extraction
        assertTrue(src.contains("catch (e: SQLiteBlobTooBigException)"))
        assertTrue(src.contains("""e.message?.contains("CursorWindow", ignoreCase = true) == true"""))
        // D) the exported raw page inherits the tolerance
        assertTrue(
            src.contains(
                "    suspend fun loadMessagePageRaw(\n" +
                    "        sessionId: String,\n" +
                    "        offset: Int,\n" +
                    "        limit: Int,\n" +
                    "    ): List<MessageEntity> =\n" +
                    "        loadPageTolerant(sessionId, offset, limit)",
            ),
        )
    }

    @Test
    fun `the id-set call site keeps rows and does not invent a helper`() {
        val orchestration = repoFile("app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt")
        assertTrue(orchestration.contains("chatRepository.loadMessages(sid).map { it.id }.toSet()"))
        assertFalse(orchestration.contains("messageIds("))
    }
}
