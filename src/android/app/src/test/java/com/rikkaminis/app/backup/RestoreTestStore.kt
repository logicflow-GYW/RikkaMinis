package com.rikkaminis.app.backup

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.rikkaminis.app.data.db.ProviderAgentLoopIdEntity
import com.rikkaminis.app.data.db.ProviderConfigMetaEntity
import com.rikkaminis.app.data.db.ProviderConfigMetaKeys
import com.rikkaminis.app.data.db.ProviderInstanceEntity
import com.rikkaminis.app.data.db.ProviderModelEntryEntity
import com.rikkaminis.app.data.db.ProviderModelGroupEntity
import com.rikkaminis.app.data.db.ProviderThinkingRuleEntity
import com.rikkaminis.app.data.db.RecordingProviderConfigDao
import com.rikkaminis.app.data.repository.ProviderRepository
import com.rikkaminis.app.data.repository.hashJsonMirror
import com.rikkaminis.app.util.InMemorySharedPreferences
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Faults at the storage adapters, not replicas of the importer, policy or queue. */
internal class RestoreTestStore {
    val events = ArrayList<String>()
    val dao = Dao()
    val mirror = Mirror()
    val queue = ConcurrentLinkedQueue<Runnable>()
    var holdSubmissions = false
    var failNextSecret = false
    private val secretMemory = InMemorySharedPreferences()
    private val secrets = object : SharedPreferences by secretMemory {
        override fun edit(): SharedPreferences.Editor {
            if (failNextSecret) {
                failNextSecret = false
                throw IOException("injected credentials write failure")
            }
            return secretMemory.edit()
        }
    }
    val repo = ProviderRepository(
        ContextWrapper(null), mirror, dao,
        Executor { if (holdSubmissions) queue.add(it) else it.run() },
        { secrets },
    )

    fun flush() {
        while (true) (queue.poll() ?: return).run()
    }

    inner class Mirror(private val memory: SharedPreferences = InMemorySharedPreferences()) :
        SharedPreferences by memory {
        var acceptsWrite = true
        var disk: String? = null
            private set

        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                private var config: String? = null
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    if (key == "config") config = value
                    return this
                }

                override fun commit(): Boolean {
                    editor.commit() // Even a failed Android commit can change memory.
                    val candidate = config ?: return true
                    events.add("mirror")
                    if (acceptsWrite) disk = candidate
                    return acceptsWrite
                }
            }
        }
    }

    inner class Dao : RecordingProviderConfigDao() {
        var acceptsWrite = true
        var savedInstances = emptyList<ProviderInstanceEntity>()
        var savedGroups = emptyList<ProviderModelGroupEntity>()
        var savedHash: String? = null
        val rules = LinkedHashMap<String, ProviderThinkingRuleEntity>()
        var failMutation = false
        var failRuleRead = false
        private val transaction = Mutex()
        var holdAfterCommitLabel: String? = null
        val committed = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()

        override suspend fun instanceCount() = savedInstances.size
        override suspend fun loadAllThinkingRules() = rules.values.toList()
        override suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity> {
            if (failRuleRead) throw IOException("injected rule read failure")
            return rules.values.filter { it.providerInstanceId == instanceId }.sortedBy { it.sortOrder }
        }
        override suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity) { rules[row.id] = row }
        override suspend fun deleteThinkingRulesForInstance(instanceId: String) {
            rules.values.removeAll { it.providerInstanceId == instanceId }
        }

        override suspend fun replaceThinkingRules(instanceId: String, rows: List<ProviderThinkingRuleEntity>): List<ProviderThinkingRuleEntity> {
            // The fake serializes the real DAO default body, then can delay the
            // COMMITTED result without still holding the DB transaction lock.
            val written = transaction.withLock { super.replaceThinkingRules(instanceId, rows) }
            if (written.any { it.label == holdAfterCommitLabel }) {
                committed.complete(Unit)
                releaseCommit.await()
            }
            return written
        }

        override suspend fun replaceThinkingRules(instanceId: String, mutation: com.rikkaminis.app.data.db.ThinkingRuleMutation): List<ProviderThinkingRuleEntity> {
            if (failMutation) throw IOException("injected rule write failure")
            return transaction.withLock { super.replaceThinkingRules(instanceId, mutation) }
        }

        override suspend fun syncAllIncremental(
            instances: List<ProviderInstanceEntity>, entries: List<ProviderModelEntryEntity>,
            groups: List<ProviderModelGroupEntity>, loopIds: List<ProviderAgentLoopIdEntity>,
            meta: List<ProviderConfigMetaEntity>, preserveEntryIds: Set<String>,
        ) {
            events.add("db")
            if (!acceptsWrite) throw IOException("injected database transaction failure")
            savedInstances = instances
            savedGroups = groups
            savedHash = meta.firstOrNull { it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH }?.value
        }

        override suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>) {
            events.add("repair")
            savedHash = rows.single().value
        }
        override suspend fun deleteMetaKey(key: String) {
            events.add("repair")
            savedHash = null
        }
    }

    fun diskHash() = mirror.disk?.let(::hashJsonMirror)
}

internal fun restoreDocument(
    providers: JSONArray = JSONArray(), rules: JSONArray = JSONArray(), groups: JSONArray = JSONArray(),
): String = JSONObject().put("format", "openminis.config.backup").put("version", 1)
    .put("providers", providers).put("thinkingRules", rules).put("groups", groups).toString()

internal fun sourceProvider(label: String = "Same", source: String? = null): JSONObject =
    JSONObject().put("providerType", "openAI").put("label", label).apply {
        if (source != null) put("_sourceInstanceId", source)
    }

internal fun ruleRow(id: String, label: String = id, sort: Int = 0): JSONObject =
    JSONObject().put("id", id).put("label", label).put("sortOrder", sort)

internal fun sourceRules(label: String = "Same", source: String? = null, vararg rows: JSONObject): JSONObject =
    JSONObject().put("providerType", "openAI").put("providerLabel", label).put("rules", JSONArray(rows.toList())).apply {
        if (source != null) put("_sourceInstanceId", source)
    }
