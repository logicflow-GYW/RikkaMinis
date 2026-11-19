package com.rikkaminis.app.data.repository

import com.rikkaminis.app.backup.BudgetChatMessage
import com.rikkaminis.app.backup.ChatBudgetState
import com.rikkaminis.app.backup.packMessagesIntoBudget
import com.rikkaminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/r3-data-1119 F-06] The tail-read direction is a data-correctness
 * contract, not an implementation detail.
 *
 * `ConfigBackup` feeds `chatRepo.loadMessagesTailTolerant(sessionId, 200)` into
 * the byte-budget packer, which walks the list from the HEAD and `break`s at
 * the first message that does not fit. Whatever sits at the head is what a
 * truncated backup keeps — so the list must be NEWEST first, which is also
 * `ChatDao.messagesLast`'s own contract (`ORDER BY sort_order DESC LIMIT n`)
 * and what the degraded one-row path returns.
 *
 * The fast path used to run an extra `.asReversed()`, flipping only the healthy
 * path to ASC: a backup that ran out of budget then kept the OLDEST 200
 * messages it had fetched and dropped the newest — silently, and irreversibly
 * once the file is written. The assertions below pin WHICH SLICE survives
 * (ids written out literally), not merely the count.
 */
class ChatTailBackupOrderTest {

    private val sessionId = "s1"

    /** Rows in `messagesLast` shape: sort_order DESC, i.e. newest first. */
    private fun newestFirstRows(count: Int): List<MessageEntity> =
        (count - 1 downTo 0).map { i ->
            MessageEntity(
                id = "m$i",
                sessionId = sessionId,
                role = if (i % 2 == 0) "user" else "assistant",
                partsJson = """[{"type":"text","value":"row $i"}]""",
                createdAt = i.toLong(),
                sortOrder = i,
            )
        }

    /**
     * DAO double: `messagesLast` is the real query's contract (DESC + LIMIT),
     * `messageCountForSession`/`loadMessagesPage` back the degraded path.
     */
    private class TailDao(
        private val rows: List<MessageEntity>,
        private val batchReadFails: Boolean,
    ) : InertChatDao() {
        var batchReads = 0
            private set

        override suspend fun messagesLast(sessionId: String, limit: Int): List<MessageEntity> {
            batchReads += 1
            if (batchReadFails) {
                // The exact shape `isCursorWindowFailure` classifies as the
                // CursorWindow ceiling (FIX-META1028-D1 F-04).
                throw IllegalStateException("CursorWindow: window is full")
            }
            return rows.take(limit)
        }

        override suspend fun messageCountForSession(sessionId: String): Int = rows.size

        /**
         * `ChatDao.loadMessagesPage` paginates `sort_order ASC` with
         * `LIMIT :limit OFFSET :offset`, so [offset] counts from the OLDEST
         * row — the degraded loop drives it from `total - 1` downwards to
         * collect newest-first.
         */
        override suspend fun loadMessagesPage(
            sessionId: String,
            offset: Int,
            limit: Int,
        ): List<MessageEntity> =
            rows.sortedBy { it.sortOrder }.drop(offset).take(limit)
    }

    private fun packInto(
        messages: List<MessageEntity>,
        budgetBytes: Long,
    ): List<JSONObject> {
        val keptSessions = mutableListOf<JSONObject>()
        val keptMessages = mutableListOf<JSONObject>()
        packMessagesIntoBudget(
            state = ChatBudgetState(budgetBytes = budgetBytes),
            sessionJson = JSONObject().put("id", sessionId),
            messages = messages.map {
                BudgetChatMessage(
                    id = it.id,
                    sessionId = it.sessionId,
                    role = it.role,
                    partsJson = it.partsJson,
                    createdAt = it.createdAt,
                    sortOrder = it.sortOrder,
                    reasoningContent = it.reasoningContent,
                )
            },
            keptSessions = keptSessions,
            keptMessages = keptMessages,
            sanitize = { it },
            capReasoning = { it },
        )
        return keptMessages
    }

    /**
     * The exact byte budget at which the packer keeps [wanted] messages.
     * Monotone by construction (every message is charged the same amount and
     * packing stops at the first one that does not fit), so a binary search
     * lands on the frontier without duplicating the packer's serialization.
     * [lo] holds "keeps fewer than [wanted]", [hi] holds "keeps at least
     * [wanted]"; the answer is the smallest budget on the [hi] side.
     */
    private fun budgetForExactly(messages: List<MessageEntity>, wanted: Int): Long {
        var lo = 0L
        var hi = 4_000_000L
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (packInto(messages, mid).size >= wanted) hi = mid else lo = mid
        }
        return hi
    }

    @Test
    fun `the fast path hands the packer rows in newest-first order`() = runBlocking {
        val dao = TailDao(newestFirstRows(200), batchReadFails = false)
        val load = ChatRepository(dao).loadMessagesTailTolerant(sessionId, limit = 200)

        assertEquals("fast path is taken", 1, dao.batchReads)
        assertEquals(0, load.unreadableRows)
        assertEquals(200, load.messages.size)
        // Literal, not "sorted": the direction IS the contract.
        assertEquals("m199", load.messages.first().id)
        assertEquals("m0", load.messages.last().id)
        assertEquals(
            (199 downTo 0).map { "m$it" },
            load.messages.map { it.id },
        )
    }

    @Test
    fun `the degraded path keeps the same direction as the fast path`() = runBlocking {
        val dao = TailDao(newestFirstRows(200), batchReadFails = true)
        val load = ChatRepository(dao).loadMessagesTailTolerant(sessionId, limit = 200)

        assertEquals("degraded path is taken", 1, dao.batchReads)
        assertEquals(0, load.unreadableRows)
        assertEquals(200, load.messages.size)
        assertEquals("m199", load.messages.first().id)
        assertEquals("m0", load.messages.last().id)
        assertEquals(
            (199 downTo 0).map { "m$it" },
            load.messages.map { it.id },
        )
    }

    @Test
    fun `a budget cut keeps the newest fifty messages, not the oldest fifty`() = runBlocking {
        val dao = TailDao(newestFirstRows(200), batchReadFails = false)
        val load = ChatRepository(dao).loadMessagesTailTolerant(sessionId, limit = 200)
        val messages = load.messages

        // Sanity: the whole set fits when the budget is generous.
        assertEquals(200, packInto(messages, 4_000_000L).size)

        val budget = budgetForExactly(messages, wanted = 50)
        val kept = packInto(messages, budget).map { it.getString("id") }

        assertEquals(50, kept.size)
        assertEquals("m199", kept.first())
        assertEquals("m150", kept.last())
        assertEquals(
            (199 downTo 150).map { "m$it" },
            kept,
        )
        // One byte less drops the frontier message: the cut is exactly here.
        assertEquals(49, packInto(messages, budget - 1).size)
    }
}
