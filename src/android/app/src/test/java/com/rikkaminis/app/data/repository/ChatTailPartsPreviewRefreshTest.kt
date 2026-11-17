package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [W1118-D5 F-03] `sessions.last_message` is what the chat drawer renders
 * (`ChatHistoryDrawer` reads `session.lastMessage`). [ChatRepository.appendMessage]
 * and [ChatRepository.updateSessionPreview] both maintain it, but
 * [ChatRepository.updateMessageParts] — the in-place rewrite used by
 * `ChatViewModel.rerunFromToolBlock` (trim the assistant row back to the parts
 * before the target tool_use, after dropping every later row) and by
 * `updatePersistedAssistantTurn` (early turn persist) — did not. The trimmed
 * row IS the tail after `deleteMessagesAfter`, so the drawer kept showing the
 * text of the tool call (or of the deleted turn) until the next append.
 *
 * Behavioural test over a delegating fake DAO: the repository's own logic runs,
 * only SQLite is replaced. Cases are chosen so a wrong predicate fails:
 * refreshing unconditionally breaks the non-tail case, dropping the refresh
 * breaks the tail case.
 */
class ChatTailPartsPreviewRefreshTest {

    private val trimmed = """[{"type":"text","value":"after trim"}]"""

    private class TailDao : ChatDao by ChatRepositoryCreateSessionTest.RecordingDao() {
        val rows = LinkedHashMap<String, MessageEntity>()
        var tailId: String? = null
        val lastMessageWrites = mutableListOf<Pair<String, String?>>()
        val partsWrites = mutableListOf<Pair<String, String>>()

        fun add(id: String, sessionId: String, sortOrder: Int, partsJson: String) {
            rows[id] = MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "assistant",
                partsJson = partsJson,
                createdAt = sortOrder.toLong(),
                sortOrder = sortOrder,
            )
        }

        override suspend fun sessionIdOf(id: String): String? = rows[id]?.sessionId

        override suspend fun updateMessageParts(id: String, partsJson: String, updatedAt: Long) {
            partsWrites.add(id to partsJson)
            rows[id]?.let { rows[id] = it.copy(partsJson = partsJson) }
        }

        override suspend fun lastMessageParts(sessionId: String): String? =
            tailId?.let { rows[it] }?.takeIf { it.sessionId == sessionId }?.partsJson

        override suspend fun updateLastMessage(id: String, preview: String?, updatedAt: Long) {
            lastMessageWrites.add(id to preview)
        }
    }

    @Test
    fun `rewriting the tail row refreshes the session preview`() = runTest {
        val dao = TailDao().apply {
            add("m1", "s1", 0, """[{"type":"text","value":"before tool"}]""")
            tailId = "m1"
        }
        val repo = ChatRepository(dao)

        repo.updateMessageParts("m1", trimmed)

        assertEquals(1, dao.lastMessageWrites.size)
        assertEquals("s1" to "after trim", dao.lastMessageWrites.single())
    }

    @Test
    fun `rewriting a row that is not the tail leaves the preview alone`() = runTest {
        val dao = TailDao().apply {
            add("m1", "s1", 0, """[{"type":"text","value":"before tool"}]""")
            add("m2", "s1", 1, """[{"type":"text","value":"later reply"}]""")
            tailId = "m2"
        }
        val repo = ChatRepository(dao)

        repo.updateMessageParts("m1", trimmed)

        assertTrue("the drawer shows the TAIL row, not this one: ${dao.lastMessageWrites}", dao.lastMessageWrites.isEmpty())
    }

    @Test
    fun `the write itself still happens for a non tail row`() = runTest {
        val dao = TailDao().apply {
            add("m1", "s1", 0, """[{"type":"text","value":"before tool"}]""")
            add("m2", "s1", 1, """[{"type":"text","value":"later reply"}]""")
            tailId = "m2"
        }
        val repo = ChatRepository(dao)

        repo.updateMessageParts("m1", trimmed)

        assertEquals(listOf("m1" to trimmed), dao.partsWrites)
    }

    @Test
    fun `a payload without a previewable part keeps the previous preview`() = runTest {
        val dao = TailDao().apply {
            add("m1", "s1", 0, """[{"type":"text","value":"before tool"}]""")
            tailId = "m1"
        }
        val repo = ChatRepository(dao)

        // An empty parts array yields no preview at all; blanking the drawer's
        // second line with null is the outcome updateSessionPreview explicitly
        // refuses, so the trim path must refuse it too.
        repo.updateMessageParts("m1", "[]")

        assertTrue("preview must not be blanked: ${dao.lastMessageWrites}", dao.lastMessageWrites.isEmpty())
    }

    @Test
    fun `an unknown row id is a no-op instead of a crash`() = runTest {
        val dao = TailDao().apply { tailId = "m1" }
        val repo = ChatRepository(dao)

        repo.updateMessageParts("missing", trimmed)

        assertEquals(listOf("missing" to trimmed), dao.partsWrites)
        assertTrue(dao.lastMessageWrites.isEmpty())
    }
}
