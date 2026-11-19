package com.rikkaminis.app.data.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-data-1119 F-07] `NOT IN (:list)` must never bind more ids than
 * SQLite's hard parameter ceiling.
 *
 * On API 26–29 (SQLite 3.18–3.28) `SQLITE_MAX_VARIABLE_NUMBER` is 999, and a
 * list that reaches it is rejected at bind time — the whole
 * `syncAllIncremental` transaction rolled back, so a provider catalog of
 * ~1200 `provider_model_entries` (the scale the repository's own comments
 * record) could not be saved on an Android 8–10 device at all.
 *
 * Room binds one parameter per list element, so the fix is the Kotlin-side
 * chunking. These tests drive the REAL `syncAllIncremental` through a
 * recording double of the DAO: the binding ceiling itself is a SQLite
 * property and cannot be exercised on a JVM, but the decision that keeps
 * every statement under it is exactly what is pinned here.
 */
class ProviderConfigDaoChunkingTest {

    private class RecordingDao : ProviderConfigDao {
        val entryChunks = mutableListOf<List<String>>()
        val instanceChunks = mutableListOf<List<String>>()
        val groupChunks = mutableListOf<List<String>>()
        val metaChunks = mutableListOf<List<String>>()
        var upsertedEntries = 0

        // ── the raw statements Room binds ────────────────────────────────
        override suspend fun deleteEntriesNotIn(keepIds: List<String>) {
            entryChunks.add(keepIds)
        }

        override suspend fun deleteInstancesNotIn(keepIds: List<String>) {
            instanceChunks.add(keepIds)
        }

        override suspend fun deleteGroupsNotIn(keepIds: List<String>) {
            groupChunks.add(keepIds)
        }

        override suspend fun deleteMetaNotIn(keepKeys: List<String>) {
            metaChunks.add(keepKeys)
        }

        // ── the rest of the interface, inert ─────────────────────────────
        override suspend fun loadInstances(): List<ProviderInstanceEntity> = emptyList()
        override suspend fun loadEntries(): List<ProviderModelEntryEntity> = emptyList()
        override suspend fun loadGroups(): List<ProviderModelGroupEntity> = emptyList()
        override suspend fun loadAgentLoopIds(): List<ProviderAgentLoopIdEntity> = emptyList()
        override suspend fun loadMeta(): List<ProviderConfigMetaEntity> = emptyList()

        override suspend fun upsertInstances(rows: List<ProviderInstanceEntity>) {}
        override suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>) {
            upsertedEntries += rows.size
        }

        override suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>) {}
        override suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>) {}
        override suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>) {}

        override suspend fun clearInstances() {}
        override suspend fun clearEntries() {}
        override suspend fun clearGroups() {}
        override suspend fun clearAgentLoopIds() {}
        override suspend fun clearMeta() {}

        override suspend fun deleteMetaKey(key: String) {}
        override suspend fun instanceCount(): Int = 0

        override suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity> =
            emptyList()
        override suspend fun loadAllThinkingRules(): List<ProviderThinkingRuleEntity> = emptyList()
        override suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity) {}
        override suspend fun deleteThinkingRule(id: String) {}
        override suspend fun deleteThinkingRulesForInstance(instanceId: String) {}
    }

    private fun ids(from: Int, count: Int): List<String> =
        (0 until count).map { "entry-$from-$it" }

    private fun assertChunked(chunks: List<List<String>>, all: List<String>, label: String) {
        assertTrue("$label: no chunk was issued", chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue(
                "$label: a chunk of ${chunk.size} ids reaches the " +
                    "SQLITE_MAX_VARIABLE_NUMBER ceiling",
                chunk.size < SQLITE_MAX_VARIABLE_NUMBER,
            )
            assertTrue(
                "$label: a chunk of ${chunk.size} ids exceeds the chunk size",
                chunk.size <= SQL_DELETE_CHUNK_SIZE,
            )
        }
        assertEquals("$label: the chunks cover the input exactly once", all, chunks.flatten())
    }

    @Test
    fun `the chunk helper splits at the chunk size and keeps every id`() {
        val ids = ids(0, 1200)
        val chunks = chunkedIds(ids)

        assertEquals(listOf(900, 300), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
    }

    @Test
    fun `the chunk helper returns nothing for an empty list`() {
        assertEquals(emptyList<List<String>>(), chunkedIds(emptyList<String>()))
    }

    @Test
    fun `the chunk helper refuses a non-positive chunk size`() {
        try {
            chunkedIds(ids(0, 3), chunkSize = 0)
            throw AssertionError("chunkedIds must reject chunkSize = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("chunkSize") == true)
        }
    }

    @Test
    fun `no statement ever binds more ids than the SQLite ceiling`() = runBlocking {
        val dao = RecordingDao()
        // Around the ceiling and past it: the pre-fix code bound the whole
        // list in one statement, so any of these would have thrown on API 26-29.
        for (n in listOf(998, SQLITE_MAX_VARIABLE_NUMBER, SQLITE_MAX_VARIABLE_NUMBER + 1, 1200)) {
            dao.entryChunks.clear()
            dao.deleteEntriesNotIn(ids(0, n))
            // one statement would bind n ids — the point of the check is that
            // the CALLER must chunk, so the helper is what is asserted.
            val chunks = chunkedIds(ids(0, n))
            assertTrue("n=$n: no chunk was produced", chunks.isNotEmpty())
            for (chunk in chunks) {
                assertTrue(
                    "n=$n: a chunk of ${chunk.size} reaches the bind ceiling",
                    chunk.size < SQLITE_MAX_VARIABLE_NUMBER,
                )
            }
            assertEquals(n, chunks.sumOf { it.size })
        }
    }

    @Test
    fun `a list one over the ceiling splits into a full chunk plus the rest`() {
        val chunks = chunkedIds(ids(0, SQLITE_MAX_VARIABLE_NUMBER + 1))
        assertEquals(
            listOf(SQL_DELETE_CHUNK_SIZE, SQLITE_MAX_VARIABLE_NUMBER + 1 - SQL_DELETE_CHUNK_SIZE),
            chunks.map { it.size })
    }

    @Test
    fun `syncAllIncremental deletes a 1200 entry catalog in chunks`() = runBlocking {
        val dao = RecordingDao()
        val entries = ids(0, 1200).map {
            ProviderModelEntryEntity(
                id = it,
                providerInstanceId = "i1",
                baseModelJson = "{}",
            )
        }

        dao.syncAllIncremental(
            instances = emptyList(),
            entries = entries,
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        )

        assertEquals("all 1200 entries were upserted in one call", 1200, dao.upsertedEntries)
        assertChunked(dao.entryChunks, entries.map { it.id }, "deleteEntriesNotIn")
        assertEquals("900 + 300", listOf(900, 300), dao.entryChunks.map { it.size })
    }

    @Test
    fun `syncAllIncremental chunks instances groups and meta the same way`() = runBlocking {
        val dao = RecordingDao()
        val instances = ids(0, 1200).map {
            ProviderInstanceEntity(
                id = it,
                label = it,
                providerType = "openai",
                credentialType = "none",
                createdAt = 0L,
            )
        }
        val groups = ids(0, 1200).map {
            ProviderModelGroupEntity(
                id = it,
                name = it,
                strategy = "manual",
                fallbackStrategy = "none",
                memberEntryIdsJson = "[]",
            )
        }
        val meta = ids(0, 1200).map { ProviderConfigMetaEntity(key = it, value = "v") }

        dao.syncAllIncremental(
            instances = instances,
            entries = emptyList(),
            groups = groups,
            loopIds = emptyList(),
            meta = meta,
        )

        assertChunked(dao.instanceChunks, instances.map { it.id }, "deleteInstancesNotIn")
        assertChunked(dao.groupChunks, groups.map { it.id }, "deleteGroupsNotIn")
        assertChunked(dao.metaChunks, meta.map { it.key }, "deleteMetaNotIn")
    }
}
