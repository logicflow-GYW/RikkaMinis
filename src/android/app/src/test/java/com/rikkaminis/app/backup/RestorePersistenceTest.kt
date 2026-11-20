package com.rikkaminis.app.backup

import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.ui.settings.restoreNotifyOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePersistenceTest {
    private val summary = "unqualified restore summary"
    private val headline = "partial restore with rollback available"

    @Test
    fun `real restore sends successful degraded and failed commits to the notification`() = runBlocking {
        for ((db, mirror) in listOf(true to true, true to false, false to true, false to false)) {
            val store = RestoreTestStore()
            store.repo.awaitConfigLoaded()
            // Begin with matching, valid copies, then fail twice in succession.
            // An Android false commit may still update the in-memory mirror.
            store.repo.addGroup(ModelGroup(id = "old", name = "Kept"))
            val oldMirror = store.mirror.disk
            store.events.clear()
            store.dao.acceptsWrite = db
            store.mirror.acceptsWrite = mirror
            val report = ConfigBackup.import(
                store.repo,
                restoreDocument(
                    providers = JSONArray().put(sourceProvider("Provider")),
                    groups = JSONArray().put(JSONObject().put("id", "new").put("name", "New")),
                ),
            )
            val notification = restoreNotifyOutcome(summary, headline, report.fatal, report.providerPersistenceWarning)
            val expectedOrder = if (db && !mirror) listOf("db", "mirror", "repair") else listOf("db", "mirror")
            assertEquals("two ordered snapshots: db=$db / mirror=$mirror", expectedOrder + expectedOrder, store.events)
            assertEquals(db, store.dao.savedInstances.any { it.label == "Provider" })
            assertEquals(db, store.dao.savedGroups.any { it.id == "new" })
            assertEquals(listOf("Kept", "New"), store.repo.config.value.modelGroups.map { it.name })
            if (mirror) {
                assertTrue(store.mirror.disk!!.contains("Provider"))
                assertTrue(store.mirror.disk!!.contains("New"))
            } else {
                assertEquals(oldMirror, store.mirror.disk)
                assertTrue(store.mirror.getString("config", "")!!.contains("New"))
            }
            if (db) assertEquals(store.diskHash(), store.dao.savedHash)
            assertEquals(db && mirror, notification.ok)
            assertEquals(db != mirror, notification.degraded)
            if (!db && !mirror) {
                assertNotNull(report.fatal)
                assertNull(report.providerPersistenceWarning)
                assertTrue(notification.body.contains(headline))
                assertFalse(notification.body.contains(summary))
            } else {
                assertNull(report.fatal)
                when {
                    db && mirror -> assertEquals(summary, notification.body)
                    db -> assertTrue(notification.body.contains("database only"))
                    else -> assertTrue(notification.body.contains("mirror only"))
                }
            }
        }
    }

    @Test
    fun `no change restore ignores already queued failing UI write`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        store.repo.addGroup(ModelGroup(id = "existing", name = "Unchanged"))
        store.holdSubmissions = true
        store.dao.acceptsWrite = false
        store.mirror.acceptsWrite = false
        store.repo.addGroup(ModelGroup(id = "ui", name = "Ordinary UI"))
        assertEquals(1, store.queue.size) // ordinary mutator returns before disk runs
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            ConfigBackup.import(store.repo, restoreDocument(groups = JSONArray().put(JSONObject().put("id", "existing").put("name", "Unchanged"))))
        }
        assertTrue("a no-op import must not await an ordinary UI write", waiting.isCompleted)
        val report = waiting.await()
        assertEquals(1, report.groupsImported)
        assertEquals(1, store.queue.size)
        store.flush()
        assertNull(report.fatal)
        assertNull(report.providerPersistenceWarning)
    }

    @Test
    fun `restore waits for its submission only not earlier failures or later successes`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        store.holdSubmissions = true
        store.repo.addGroup(ModelGroup(id = "older", name = "Earliest"))
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            ConfigBackup.import(store.repo, restoreDocument(groups = JSONArray().put(JSONObject().put("id", "restore").put("name", "Restored"))))
        }
        assertFalse(restore.isCompleted)
        store.repo.addGroup(ModelGroup(id = "later", name = "Later"))
        assertEquals(3, store.queue.size)
        store.dao.acceptsWrite = false
        store.mirror.acceptsWrite = false
        store.queue.remove().run() // failed predecessor belongs to UI
        store.dao.acceptsWrite = true
        store.mirror.acceptsWrite = true
        store.queue.remove().run()
        val report = restore.await()
        assertNull(report.fatal)
        assertNull(report.providerPersistenceWarning)
        assertEquals(1, store.queue.size)
        assertEquals(listOf("older", "restore"), store.dao.savedGroups.map { it.id })
        store.flush()
        assertEquals(listOf("older", "restore", "later"), store.dao.savedGroups.map { it.id })
    }

    @Test
    fun `cancelled restore stops waiting without cancelling the submitted store write`() = runBlocking {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        store.holdSubmissions = true
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            ConfigBackup.import(store.repo, restoreDocument(providers = JSONArray().put(sourceProvider("Queued"))))
        }
        assertFalse(restore.isCompleted)
        restore.cancelAndJoin()
        assertTrue(restore.isCancelled)
        assertEquals(1, store.queue.size)
        assertTrue(store.dao.savedInstances.isEmpty())
        store.flush()
        assertEquals(listOf("Queued"), store.dao.savedInstances.map { it.label })
        assertTrue(store.mirror.disk!!.contains("Queued"))
    }
}
