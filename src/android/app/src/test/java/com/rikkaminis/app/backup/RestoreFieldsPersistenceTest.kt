package com.rikkaminis.app.backup

import com.rikkaminis.app.config.ConfigBuiltins
import com.rikkaminis.app.config.ConfigRegistry
import com.rikkaminis.app.config.ConfigValue
import com.rikkaminis.app.data.db.ProviderConfigMetaKeys
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.ui.settings.restoreNotifyOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real importer, registry, six builtin writers, repository, and persistence queue.
 * Only the DAO and SharedPreferences storage adapters inject failures.
 */
class RestoreFieldsPersistenceTest {
    @Test fun primaryGroupWritesAndClearsAwaitAllFourStorageOutcomes() = checkBinding("defaults.primaryGroup")
    @Test fun subGroupWritesAndClearsAwaitAllFourStorageOutcomes() = checkBinding("defaults.subGroup")
    @Test fun voiceInputWritesAndClearsAwaitAllFourStorageOutcomes() = checkBinding("defaults.voiceInputGroup")
    @Test fun voiceOutputWritesAndClearsAwaitAllFourStorageOutcomes() = checkBinding("defaults.voiceOutputGroup")
    @Test fun agentEntriesWriteAndClearAwaitAllFourStorageOutcomes() = checkBinding("defaults.agentLoopEntries")
    @Test fun agentGroupsWriteAndClearAwaitAllFourStorageOutcomes() = checkBinding("defaults.agentLoopGroups")

    private fun checkBinding(path: String) = runBlocking {
        for ((db, mirror) in listOf(true to true, true to false, false to true, false to false)) {
            for (clear in listOf(false, true)) withDefaults { store, registry ->
                val selected = selectedValue(path, store)
                val empty = emptyValue(path)
                val before = if (clear) selected else empty
                val target = if (clear) empty else selected
                val field = registry.resolveField(path)!!
                field.write(before)
                assertEquals(before, databaseValue(path, store))
                assertEquals(before, mirrorValue(path, store))
                store.events.clear()
                store.holdSubmissions = true
                store.dao.acceptsWrite = db
                store.mirror.acceptsWrite = mirror
                val restore = async(start = CoroutineStart.UNDISPATCHED) {
                    ConfigBackup.import(store.repo, fieldsDocument(path, target))
                }
                assertEquals("one actual repository submission for $path", 1, store.queue.size)
                assertFalse("$path clear=$clear db=$db mirror=$mirror returned before its write", restore.isCompleted)
                assertEquals(target, field.read())
                assertEquals(before, databaseValue(path, store))
                assertEquals(before, mirrorValue(path, store))
                store.queue.remove().run()
                val report = withTimeout(5_000) { restore.await() }
                assertEquals(1, report.fieldsApplied)
                assertTrue(report.skipped.toString(), report.skipped.isEmpty())
                assertEquals(if (db) target else before, databaseValue(path, store))
                assertEquals(if (mirror) target else before, mirrorValue(path, store))
                assertEquals(
                    if (db && !mirror) listOf("db", "mirror", "repair") else listOf("db", "mirror"),
                    store.events,
                )
                val notification = restoreNotifyOutcome("restored", "partial restore", report.fatal, report.providerPersistenceWarning)
                assertEquals(db && mirror, notification.ok)
                assertEquals(db != mirror, notification.degraded)
                if (!db && !mirror) {
                    assertNotNull(report.fatal)
                    assertNull(report.providerPersistenceWarning)
                } else {
                    assertNull(report.fatal)
                    when {
                        db && mirror -> assertNull(report.providerPersistenceWarning)
                        db -> assertTrue(report.providerPersistenceWarning!!.contains("database only"))
                        else -> assertTrue(report.providerPersistenceWarning!!.contains("mirror only"))
                    }
                }
                if (db) assertEquals(store.diskHash(), store.dao.savedHash)
            }
        }
    }

    @Test fun earlierProviderAndGroupSuccessCannotHideFailedFieldSuccessor() = runBlocking {
        withDefaults { store, _ ->
            store.holdSubmissions = true
            val root = JSONObject(restoreDocument(
                providers = JSONArray().put(sourceProvider("Earlier provider")),
                groups = JSONArray().put(JSONObject().put("id", "earlier").put("name", "Earlier group")),
            )).put("fields", JSONObject().put("defaults.primaryGroup", ConfigValue.Str("field-group").jsonString()))
            val restore = async(start = CoroutineStart.UNDISPATCHED) { ConfigBackup.import(store.repo, root.toString()) }
            assertEquals("provider, group, then field snapshots", 3, store.queue.size)
            store.queue.remove().run()
            store.queue.remove().run()
            assertFalse("earlier BOTH snapshots do not confirm Stage 3", restore.isCompleted)
            store.dao.acceptsWrite = false
            store.mirror.acceptsWrite = false
            store.queue.remove().run()
            val report = withTimeout(5_000) { restore.await() }
            assertNotNull(report.fatal)
            assertNull(report.providerPersistenceWarning)
            assertEquals(1, report.fieldsApplied)
            assertEquals(ConfigValue.Str(""), databaseValue("defaults.primaryGroup", store))
            assertEquals(ConfigValue.Str(""), mirrorValue("defaults.primaryGroup", store))
        }
    }

    @Test fun fullyConfirmedFieldSnapshotHealsEarlierFailedGroupSnapshot() = runBlocking {
        withDefaults { store, _ ->
            store.holdSubmissions = true
            val root = JSONObject(restoreDocument(
                groups = JSONArray().put(JSONObject().put("id", "new").put("name", "New group")),
            )).put("fields", JSONObject().put("defaults.subGroup", ConfigValue.Str("field-group").jsonString()))
            val restore = async(start = CoroutineStart.UNDISPATCHED) { ConfigBackup.import(store.repo, root.toString()) }
            assertEquals(2, store.queue.size)
            store.dao.acceptsWrite = false
            store.mirror.acceptsWrite = false
            store.queue.remove().run()
            assertFalse(restore.isCompleted)
            store.dao.acceptsWrite = true
            store.mirror.acceptsWrite = true
            store.queue.remove().run()
            val report = withTimeout(5_000) { restore.await() }
            assertNull(report.fatal)
            assertNull(report.providerPersistenceWarning)
            assertEquals(ConfigValue.Str("field-group"), databaseValue("defaults.subGroup", store))
            assertEquals(ConfigValue.Str("field-group"), mirrorValue("defaults.subGroup", store))
            assertTrue(store.dao.savedGroups.any { it.name == "New group" })
        }
    }

    @Test fun fieldsWaitOnlyForOwnSubmissionNotUnrelatedUiQueue() = runBlocking {
        withDefaults { store, _ ->
            store.holdSubmissions = true
            store.repo.addGroup(ModelGroup(id = "older-ui", name = "Older UI"))
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                ConfigBackup.import(store.repo, fieldsDocument("defaults.voiceInputGroup", ConfigValue.Str("field-group")))
            }
            store.repo.addGroup(ModelGroup(id = "later-ui", name = "Later UI"))
            assertEquals(3, store.queue.size)
            assertFalse(restore.isCompleted)
            store.dao.acceptsWrite = false
            store.mirror.acceptsWrite = false
            store.queue.remove().run()
            store.dao.acceptsWrite = true
            store.mirror.acceptsWrite = true
            store.queue.remove().run()
            val report = withTimeout(5_000) { restore.await() }
            assertNull(report.fatal)
            assertNull(report.providerPersistenceWarning)
            assertEquals(1, store.queue.size)
            assertTrue(store.dao.savedGroups.any { it.id == "older-ui" })
            assertFalse(store.dao.savedGroups.any { it.id == "later-ui" })
            assertEquals(ConfigValue.Str("field-group"), databaseValue("defaults.voiceInputGroup", store))
            store.flush()
            assertTrue(store.dao.savedGroups.any { it.id == "later-ui" })
        }
    }

    @Test fun cancellingFieldsRestoreDoesNotCancelAlreadySubmittedWrite() = runBlocking {
        withDefaults { store, _ ->
            store.holdSubmissions = true
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                ConfigBackup.import(store.repo, fieldsDocument("defaults.agentLoopGroups", ConfigValue.Arr(listOf(ConfigValue.Str("field-group")))))
            }
            assertFalse(restore.isCompleted)
            restore.cancelAndJoin()
            assertTrue(restore.isCancelled)
            assertEquals(1, store.queue.size)
            assertEquals(ConfigValue.Arr(emptyList()), databaseValue("defaults.agentLoopGroups", store))
            store.flush()
            val expected = ConfigValue.Arr(listOf(ConfigValue.Str("field-group")))
            assertEquals(expected, databaseValue("defaults.agentLoopGroups", store))
            assertEquals(expected, mirrorValue("defaults.agentLoopGroups", store))
        }
    }

    @Test fun rejectedFieldsCreateNoReceiptAndDoNotWaitForUnrelatedUiWrite() = runBlocking {
        withDefaults { store, _ ->
            store.holdSubmissions = true
            store.repo.addGroup(ModelGroup(id = "ui", name = "Unrelated"))
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                ConfigBackup.import(store.repo, fieldsDocument("defaults.primaryGroup", ConfigValue.Int(42)))
            }
            assertTrue(restore.isCompleted)
            val report = restore.await()
            assertEquals(0, report.fieldsApplied)
            assertEquals(1, report.skipped.size)
            assertNull(report.fatal)
            assertNull(report.providerPersistenceWarning)
            assertEquals(1, store.queue.size)
        }
    }

    private suspend fun withDefaults(block: suspend (RestoreTestStore, ConfigRegistry) -> Unit) {
        val store = RestoreTestStore()
        store.repo.awaitConfigLoaded()
        // awaitConfigLoaded only covers the first load. The init block then runs
        // selection-only migration and voice-template reconciliation on loadScope.
        // Join those real jobs before asserting counts or holding submissions,
        // otherwise they can add a second submission or drop the entry we just added.
        val scopeField = ProviderRepository::class.java.getDeclaredField("loadScope").apply { isAccessible = true }
        val scope = scopeField.get(store.repo) as kotlinx.coroutines.CoroutineScope
        scope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.join() }
        store.repo.addInstance(ProviderInstance("field-owner", "Field owner", ProviderType.openAI, ProviderCredential.apiKey))
        store.repo.replaceEntries("field-owner", listOf(LLMModel("field-model", "Field model", "OpenAI")))
        store.repo.addGroup(ModelGroup(id = "field-group", name = "Field group"))
        assertEquals(1, store.repo.config.value.modelEntries.size)
        // Do not call platform-wide init() or register fake copies of the writers.
        // A private registry runs only the actual production defaults registrar.
        val constructor = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }
        val registry = constructor.newInstance()
        val register = ConfigBuiltins::class.java.getDeclaredMethod("registerDefaults", ConfigRegistry::class.java, ProviderRepository::class.java)
            .apply { isAccessible = true }
        register.invoke(ConfigBuiltins, registry, store.repo)
        assertEquals(6, registry.allVisibleFieldPaths().size)
        val singleton = ConfigRegistry::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previous = singleton.get(null)
        singleton.set(null, registry)
        try {
            block(store, registry)
        } finally {
            singleton.set(null, previous)
            store.flush()
        }
    }

    private fun fieldsDocument(path: String, value: ConfigValue): String =
        JSONObject(restoreDocument()).put("fields", JSONObject().put(path, value.jsonString())).toString()

    private fun emptyValue(path: String): ConfigValue =
        if (path == "defaults.agentLoopEntries" || path == "defaults.agentLoopGroups") ConfigValue.Arr(emptyList()) else ConfigValue.Str("")

    private fun selectedValue(path: String, store: RestoreTestStore): ConfigValue = when (path) {
        "defaults.agentLoopEntries" -> ConfigValue.Arr(listOf(ConfigValue.Str(store.repo.config.value.modelEntries.single().id)))
        "defaults.agentLoopGroups" -> ConfigValue.Arr(listOf(ConfigValue.Str("field-group")))
        else -> ConfigValue.Str("field-group")
    }

    private fun databaseValue(path: String, store: RestoreTestStore): ConfigValue = when (path) {
        "defaults.agentLoopEntries", "defaults.agentLoopGroups" -> {
            val kind = if (path == "defaults.agentLoopEntries") "entry" else "group"
            ConfigValue.Arr(store.dao.savedLoopIds.filter { it.kind == kind }.sortedBy { it.sortOrder }.map { ConfigValue.Str(it.targetId) })
        }
        else -> {
            val key = when (path) {
                "defaults.primaryGroup" -> ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID
                "defaults.subGroup" -> ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID
                "defaults.voiceInputGroup" -> ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID
                else -> ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID
            }
            ConfigValue.Str(store.dao.savedMeta.firstOrNull { it.key == key }?.value ?: "")
        }
    }

    private fun mirrorValue(path: String, store: RestoreTestStore): ConfigValue {
        val config = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(ProviderConfig.serializer(), store.mirror.disk!!)
        return when (path) {
            "defaults.primaryGroup" -> ConfigValue.Str(config.defaultPrimaryGroupId ?: "")
            "defaults.subGroup" -> ConfigValue.Str(config.defaultSubGroupId ?: "")
            "defaults.voiceInputGroup" -> ConfigValue.Str(config.voiceInputGroupId ?: "")
            "defaults.voiceOutputGroup" -> ConfigValue.Str(config.voiceOutputGroupId ?: "")
            "defaults.agentLoopEntries" -> ConfigValue.Arr(config.agentLoopModelEntryIds.map { ConfigValue.Str(it) })
            else -> ConfigValue.Arr(config.agentLoopGroupIds.map { ConfigValue.Str(it) })
        }
    }
}
