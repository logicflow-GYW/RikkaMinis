package com.rikkaminis.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rikkaminis.app.data.db.AppDatabase
import com.rikkaminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [RC15] On-device regression test for the unique (session_id, sort_order)
 * index. Verifies that two concurrent [ChatRepository.appendMessage] calls on
 * the same session produce DISTINCT sort_order values with no rows lost (the
 * pre-fix behavior, with a non-unique index + REPLACE strategy, was to
 * silently overwrite the collided row and drop a message).
 *
 * Needs a real Room DB, so this is an instrumented test (JVM unit tests
 * cannot run Room without Robolectric). CI validates compilation via
 * `compileDebugAndroidTestKotlin`; run on device with connectedAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class SortOrderUniqueInstrumentedTest {

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
        // A session row is required — messages FK to sessions(id) ON DELETE CASCADE.
        runBlocking {
            repo.dao.insertSession(
                ChatSessionEntity(id = "s1", title = "t", modelId = "m", createdAt = 0L, updatedAt = 0L),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrentAppendProducesDistinctSortOrderAndLosesNoRows() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        // Append 20 messages concurrently; without the fix some sort_order
        // values collide and (with REPLACE) rows vanish.
        val jobs = (0 until 20).map { i ->
            scope.async {
                repo.appendMessage(
                    sessionId = "s1",
                    role = "user",
                    partsJson = """[{"type":"text","text":"msg$i"}]""",
                ).sortOrder
            }
        }
        val sortOrders = jobs.awaitAll()

        val rows = repo.dao.loadMessages("s1")
        assertEquals("no rows may be lost to a sort_order collision", 20, rows.size)

        val distinct = rows.map { it.sortOrder }.distinct()
        assertEquals("sort_order values must be unique per session", rows.size, distinct.size)
        assertTrue("sortOrders snapshot must not contain duplicates", sortOrders.toSet().size == sortOrders.size)
    }
}
