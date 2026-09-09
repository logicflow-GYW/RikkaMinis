package com.rikkaminis.app.backup

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.data.db.AppDatabase
import com.rikkaminis.app.data.db.ChatSessionEntity
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-backup-chat-idempotent] On-device regression test for the
 * existence-guarded chat restore (importChatSections). Verifies that
 * re-importing a backup is a true idempotent merge:
 *  - an existing session keeps its LOCAL metadata (title / pin / updatedAt
 *    are not clobbered by an older backup row),
 *  - an existing message is not overwritten by the backup's sanitized copy,
 *  - local-only messages survive a re-import,
 *  - a fresh restore still brings everything in.
 *
 * Needs a real Room DB, so this is an instrumented test (JVM unit tests
 * cannot run Room without Robolectric). CI validates compilation via
 * `compileDebugAndroidTestKotlin`; run on device with connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class ChatImportIdempotencyInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sessionJson(
        id: String,
        title: String,
        updatedAt: Long,
        pinnedAt: Long? = null,
        thinkingOverride: String? = null,
    ) = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("modelId", "test-model")
        put("createdAt", 1000L)
        put("updatedAt", updatedAt)
        if (pinnedAt != null) put("pinnedAt", pinnedAt)
        if (thinkingOverride != null) put("thinkingOverride", thinkingOverride)
    }

    private fun messageJson(id: String, sessionId: String, sortOrder: Int, parts: String) =
        JSONObject().apply {
            put("id", id)
            put("sessionId", sessionId)
            put("role", "user")
            put("partsJson", parts)
            put("createdAt", 1000L + sortOrder)
            put("sortOrder", sortOrder)
        }

    @Test
    fun freshRestoreBringsEverything() = runBlocking {
        val sessions = JSONArray().put(sessionJson("s1", "title", 5000L))
        val messages = JSONArray()
            .put(messageJson("m1", "s1", 0, """[{"type":"text","text":"hello"}]"""))
            .put(messageJson("m2", "s1", 1, """[{"type":"text","text":"world"}]"""))

        val (sess, msg) = importChatSections(repo, sessions, messages, mutableListOf())
        assertEquals(1, sess)
        assertEquals(2, msg)
        assertEquals(2, repo.dao.loadMessages("s1").size)
        assertNotNull(repo.dao.getSession("s1"))
    }

    @Test
    fun reimportDoesNotClobberSessionMetadata() = runBlocking {
        val sessions = JSONArray().put(sessionJson("s1", "old title", 5000L))
        val messages = JSONArray().put(messageJson("m1", "s1", 0, """[{"type":"text","text":"hi"}]"""))

        importChatSections(repo, sessions, messages, mutableListOf())

        // Local edits after the backup was taken: rename + pin the session.
        val local = repo.dao.getSession("s1")!!
        repo.dao.insertSession(
            local.copy(
                title = "locally renamed",
                updatedAt = 9000L,
                pinnedAt = 8000L,
                thinkingOverride = "HIGH",
            ),
        )
        assertEquals("locally renamed", repo.dao.getSession("s1")!!.title)
        assertEquals(8000L, repo.dao.getSession("s1")!!.pinnedAt)
        assertEquals("HIGH", repo.dao.getSession("s1")!!.thinkingOverride)

        // Re-import the OLD backup: session row must be left alone.
        val (sess2, msg2) = importChatSections(repo, sessions, messages, mutableListOf())
        assertEquals(0, sess2)
        assertEquals(0, msg2)
        val after = repo.dao.getSession("s1")!!
        assertEquals("locally renamed", after.title)
        assertEquals(8000L, after.pinnedAt)
        assertEquals("HIGH", after.thinkingOverride)
        assertEquals(9000L, after.updatedAt)
    }

    @Test
    fun reimportKeepsLocalFullMessageNotBackupTruncatedCopy() = runBlocking {
        // Local message with the FULL payload…
        val fullParts = """[{"type":"text","text":"full content"}]"""
        val sessions = JSONArray().put(sessionJson("s1", "t", 5000L))
        val messages = JSONArray()
            .put(messageJson("m1", "s1", 0, """[{"type":"text","text":"short backup copy"}]"""))

        // …the backup carries a sanitized/truncated version of the SAME id.
        importChatSections(repo, sessions, messages, mutableListOf())
        repo.dao.insertMessage(
            MessageEntity(
                id = "m1",
                sessionId = "s1",
                role = "user",
                partsJson = fullParts,
                createdAt = 1000L,
                sortOrder = 0,
            ),
        )
        assertEquals(fullParts, repo.dao.loadMessages("s1").first().partsJson)

        // Re-import must NOT overwrite the local full copy with the short one.
        val (_, msg2) = importChatSections(repo, sessions, messages, mutableListOf())
        assertEquals(0, msg2)
        assertEquals(fullParts, repo.dao.loadMessages("s1").first().partsJson)
    }

    @Test
    fun reimportKeepsLocalOnlyMessages() = runBlocking {
        val sessions = JSONArray().put(sessionJson("s1", "t", 5000L))
        val messages = JSONArray().put(messageJson("m1", "s1", 0, """[{"type":"text","text":"a"}]"""))

        importChatSections(repo, sessions, messages, mutableListOf())

        // A message created locally after the backup.
        repo.dao.insertMessage(
            MessageEntity(
                id = "m2", sessionId = "s1", role = "user",
                partsJson = """[{"type":"text","text":"local"}]""",
                createdAt = 9000L, sortOrder = 1,
            ),
        )
        assertEquals(2, repo.dao.loadMessages("s1").size)

        // Re-import: m2 is not in the backup and must survive.
        val (_, msg2) = importChatSections(repo, sessions, messages, mutableListOf())
        assertEquals(0, msg2)
        assertEquals(2, repo.dao.loadMessages("s1").size)
        assertNotNull(repo.dao.loadMessages("s1").firstOrNull { it.id == "m2" })
    }

    @Test
    fun reimportWithNewSessionsIsAdditive() = runBlocking {
        val sessions = JSONArray().put(sessionJson("s1", "a", 1000L))
        importChatSections(repo, sessions, JSONArray(), mutableListOf())

        // A second backup contains a brand-new session — must be added, not skipped.
        val sessions2 = JSONArray()
            .put(sessionJson("s1", "a", 1000L))
            .put(sessionJson("s2", "b", 2000L))
        val (sess2, _) = importChatSections(repo, sessions2, JSONArray(), mutableListOf())
        assertEquals(1, sess2)
        assertNotNull(repo.dao.getSession("s2"))
        assertNull(repo.dao.getSession("s3"))
    }
}
