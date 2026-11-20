package com.rikkaminis.app.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the real transaction body; these recording checks complement the SQLite row-set tests. */
class ProviderConfigDaoChunkingTest {
    private fun ids(prefix: String, count: Int) = (0 until count).map { "$prefix-$it" }

    private fun assertChunked(chunks: List<List<String>>, expected: List<String>) {
        assertEquals(listOf(900, 300), chunks.map { it.size })
        assertTrue(chunks.all { it.size in 1..900 && it.size < SQLITE_MAX_VARIABLE_NUMBER })
        assertEquals(expected, chunks.flatten())
    }

    @Test
    fun `chunk helper splits at 900 without losing ids`() {
        val ids = ids("entry", 1200)
        assertChunked(chunkedIds(ids), ids)
    }

    @Test
    fun `chunk helper handles empty input`() {
        assertEquals(emptyList<List<String>>(), chunkedIds(emptyList<String>()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `chunk helper rejects non-positive size`() {
        chunkedIds(listOf("id"), 0)
    }

    @Test
    fun `all delete batches obey the legacy SQLite ceiling`() {
        for (size in listOf(998, 999, 1000, 1200)) {
            val chunks = chunkedIds(ids("old", size))
            assertTrue(chunks.all { it.size in 1..900 })
            assertEquals(size, chunks.sumOf { it.size })
        }
    }

    @Test
    fun `1200 retained entries are never sent to positive delete`() = runBlocking {
        val dao = RecordingProviderConfigDao()
        val stale = ids("old", 1200)
        dao.entryIds += stale
        val entries = ids("kept", 1200).map {
            ProviderModelEntryEntity(it, "i1", "{}")
        }
        dao.syncAllIncremental(listOf(instance("i1")), entries, emptyList(), emptyList(), emptyList())
        assertEquals(1200, dao.upsertedEntries.size)
        assertChunked(dao.entryChunks, stale)
        assertEquals(entries.map { it.id }.toSet(), dao.entryIds)
    }

    @Test
    fun `instances groups and meta delete only the full difference`() = runBlocking {
        val dao = RecordingProviderConfigDao()
        val stale = ids("old", 1200)
        dao.instanceIds += stale
        dao.groupIds += stale
        dao.metaKeys += stale
        val keep = ids("kept", 1200)
        dao.syncAllIncremental(
            keep.map(::instance), emptyList(),
            keep.map { ProviderModelGroupEntity(it, it, "manual", "none", memberEntryIdsJson = "[]") },
            emptyList(), keep.map { ProviderConfigMetaEntity(it, "v") },
        )
        assertChunked(dao.instanceChunks, stale)
        assertChunked(dao.groupChunks, stale)
        assertChunked(dao.metaChunks, stale)
        assertEquals(keep.toSet(), dao.instanceIds)
        assertEquals(keep.toSet(), dao.groupIds)
        assertEquals(keep.toSet(), dao.metaKeys)
    }

    @Test
    fun `unchanged rows issue no delete statement`() = runBlocking {
        val dao = RecordingProviderConfigDao()
        val entry = ProviderModelEntryEntity("i1/m", "i1", "{}")
        dao.entryIds += entry.id
        dao.syncAllIncremental(listOf(instance("i1")), listOf(entry), emptyList(), emptyList(), emptyList())
        assertTrue(dao.entryChunks.isEmpty())
        assertEquals(setOf(entry.id), dao.entryIds)
    }

    private fun instance(id: String) = ProviderInstanceEntity(id, id, "openAI", "none", createdAt = 0L)
}

/** Primary-key-aware double shared by the real-SQL adapter. Never decodes row payloads. */
internal open class RecordingProviderConfigDao : ProviderConfigDao {
    val entryIds = linkedSetOf<String>()
    val instanceIds = linkedSetOf<String>()
    val groupIds = linkedSetOf<String>()
    val metaKeys = linkedSetOf<String>()
    val entryChunks = mutableListOf<List<String>>()
    val instanceChunks = mutableListOf<List<String>>()
    val groupChunks = mutableListOf<List<String>>()
    val metaChunks = mutableListOf<List<String>>()
    val upsertedEntries = mutableListOf<ProviderModelEntryEntity>()

    override suspend fun loadInstanceIds() = instanceIds.toList()
    override suspend fun loadEntryIds() = entryIds.toList()
    override suspend fun loadGroupIds() = groupIds.toList()
    override suspend fun loadMetaKeys() = metaKeys.toList()
    override suspend fun deleteInstancesByIds(ids: List<String>) {
        instanceChunks += ids.toList()
        instanceIds.removeAll(ids.toSet())
    }
    override suspend fun deleteEntriesByIds(ids: List<String>) {
        entryChunks += ids.toList()
        entryIds.removeAll(ids.toSet())
    }
    override suspend fun deleteGroupsByIds(ids: List<String>) {
        groupChunks += ids.toList()
        groupIds.removeAll(ids.toSet())
    }
    override suspend fun deleteMetaByKeys(keys: List<String>) {
        metaChunks += keys.toList()
        metaKeys.removeAll(keys.toSet())
    }
    override suspend fun upsertInstances(rows: List<ProviderInstanceEntity>) { instanceIds += rows.map { it.id } }
    override suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>) {
        upsertedEntries += rows
        entryIds += rows.map { it.id }
    }
    override suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>) { groupIds += rows.map { it.id } }
    override suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>) { metaKeys += rows.map { it.key } }
    override suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>) = Unit
    override suspend fun clearInstances() { instanceIds.clear() }
    override suspend fun clearEntries() { entryIds.clear() }
    override suspend fun clearGroups() { groupIds.clear() }
    override suspend fun clearMeta() { metaKeys.clear() }
    override suspend fun clearAgentLoopIds() = Unit
    override suspend fun loadInstances(): List<ProviderInstanceEntity> = error("pruning must query keys, not payloads")
    override suspend fun loadEntries(): List<ProviderModelEntryEntity> = error("pruning must query keys, not payloads")
    override suspend fun loadGroups(): List<ProviderModelGroupEntity> = error("pruning must query keys, not payloads")
    override suspend fun loadMeta(): List<ProviderConfigMetaEntity> = error("pruning must query keys, not payloads")
    override suspend fun loadAgentLoopIds(): List<ProviderAgentLoopIdEntity> = emptyList()
    override suspend fun deleteMetaKey(key: String) { metaKeys.remove(key) }
    override suspend fun instanceCount() = instanceIds.size
    override suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity> = emptyList()
    override suspend fun loadAllThinkingRules(): List<ProviderThinkingRuleEntity> = emptyList()
    override suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity) = Unit
    override suspend fun deleteThinkingRule(id: String) = Unit
    override suspend fun deleteThinkingRulesForInstance(instanceId: String) = Unit
}
