package com.rikkaminis.app.data.db

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.repository.ConfigLoadSource
import com.rikkaminis.app.data.repository.decideConfigSource
import com.rikkaminis.app.data.repository.hashJsonMirror
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the REAL syncAllIncremental body against Python's native SQLite, not
 * a recorder. Raw @Query statements are read from the checked-out DAO. The
 * adapter supplies the @Transaction wrapper Room generates on Android; CI
 * additionally compiles that generated implementation. No JDBC/native dependency
 * or Android Context is needed. Python/sqlite3 are REQUIRED (no silent skip).
 */
class ProviderConfigDaoSqliteTest {
    private fun instance(id: String = "i1") =
        ProviderInstanceEntity(id, id, "openAI", "none", createdAt = 1L)
    private fun entry(id: String, parent: String = "i1", blob: String = "{}") =
        ProviderModelEntryEntity(id, parent, blob)
    private fun ids(prefix: String, count: Int) = (0 until count).map { "$prefix-${it.toString().padStart(4, '0')}" }

    private fun withDb(block: suspend (SqliteDao) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("provider-delete-set-").toFile()
        try { SqliteDao(dir).use { block(it) } } finally { dir.deleteRecursively() }
    }

    private fun boundary(count: Int) = withDb { dao ->
        dao.upsertInstances(listOf(instance()))
        dao.upsertEntries(listOf(entry("stale")))
        val keep = ids("keep", count)
        dao.syncAllIncremental(listOf(instance()), keep.map { entry(it) }, emptyList(), emptyList(), emptyList())
        assertEquals("final SQLite row set for $count kept ids", keep.toSet(), dao.loadEntryIds().toSet())
        assertEquals(listOf("i1"), dao.loadInstanceIds())
        dao.assertForeignKeys()
    }

    @Test fun `sqlite keeps exactly 0 entries`() = boundary(0)
    @Test fun `sqlite keeps exactly 900 entries`() = boundary(900)
    @Test fun `sqlite keeps exactly 901 entries`() = boundary(901)
    @Test fun `sqlite keeps exactly 1200 entries`() = boundary(1200)

    @Test
    fun `all four tables keep 1200 rows and delete 1200 obsolete keys`() = withDb { dao ->
        val stale = ids("old", 1200)
        val keep = ids("kept", 1200)
        dao.upsertInstances(stale.map { instance(it) })
        dao.upsertEntries(stale.map { entry(it, it) })
        dao.upsertGroups(stale.map { group(it) })
        dao.upsertMeta(stale.map { ProviderConfigMetaEntity(it, "old") })
        dao.syncAllIncremental(
            keep.map { instance(it) }, keep.map { entry(it, it) }, keep.map { group(it) },
            emptyList(), keep.map { ProviderConfigMetaEntity(it, "new") },
        )
        assertEquals(keep.toSet(), dao.loadInstanceIds().toSet())
        assertEquals(keep.toSet(), dao.loadEntryIds().toSet())
        assertEquals(keep.toSet(), dao.loadGroupIds().toSet())
        assertEquals(keep.toSet(), dao.loadMetaKeys().toSet())
        dao.assertForeignKeys()
    }

    @Test
    fun `quarantined rows across the old keep boundary survive byte for byte`() = withDb { dao ->
        dao.upsertInstances(listOf(instance()))
        val decoded = ids("good", 900).map { entry(it) }
        val bad = listOf(entry("bad-a", blob = "{truncated"), entry("bad-z", blob = "future-blob"))
        dao.upsertEntries(bad + ids("old", 1200).map { entry(it) })
        dao.syncAllIncremental(listOf(instance()), decoded, emptyList(), emptyList(), emptyList(), bad.map { it.id }.toSet())
        assertEquals((decoded + bad).map { it.id }.toSet(), dao.loadEntryIds().toSet())
        assertEquals(bad.associate { it.id to it.baseModelJson },
            dao.loadEntries().filter { it.id.startsWith("bad-") }.associate { it.id to it.baseModelJson })
    }

    @Test
    fun `empty decoded entries preserve quarantine but remove obsolete rows`() = withDb { dao ->
        dao.upsertInstances(listOf(instance()))
        dao.upsertEntries(listOf(entry("bad", blob = "{"), entry("old")))
        dao.syncAllIncremental(listOf(instance()), emptyList(), emptyList(), emptyList(), emptyList(), setOf("bad", "already-gone"))
        assertEquals(listOf("bad"), dao.loadEntryIds())
        assertEquals("{", dao.loadEntries().single().baseModelJson)
    }

    @Test
    fun `parent upsert retains quarantined child while removed parent cascades`() = withDb { dao ->
        dao.upsertInstances(listOf(instance(), instance("gone")))
        dao.upsertEntries(listOf(entry("bad", blob = "{"), entry("gone/child", "gone")))
        dao.syncAllIncremental(listOf(instance().copy(label = "updated")), emptyList(), emptyList(), emptyList(), emptyList(), setOf("bad", "gone/child"))
        assertEquals("updated", dao.loadInstances().single().label)
        assertEquals(listOf("bad"), dao.loadEntryIds())
        dao.assertForeignKeys()
    }

    @Test
    fun `second delete chunk failure rolls back earlier deletes and upserts`() = withDb { dao ->
        dao.upsertInstances(listOf(instance()))
        dao.upsertEntries(ids("old", 1200).map { entry(it) })
        val before = dao.dump()
        dao.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON provider_model_entries WHEN OLD.id='old-1199' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        expectFailure {
            dao.syncAllIncremental(listOf(instance().copy(label = "changed"), instance("new")), listOf(entry("new-entry")), emptyList(), emptyList(), emptyList())
        }
        assertEquals("first delete batch must actually have succeeded before the error", 1, dao.completedEntryBatches)
        assertEquals(before, dao.dump())
        dao.assertForeignKeys()
    }

    @Test
    fun `late metadata failure rolls back parent cascade groups loop ids and entries`() = withDb { dao ->
        dao.upsertInstances(listOf(instance(), instance("gone")))
        dao.upsertEntries(listOf(entry("gone/child", "gone"), entry("old")))
        dao.upsertGroups(listOf(group("old")))
        dao.upsertAgentLoopIds(listOf(ProviderAgentLoopIdEntity("entry", "old", 0)))
        dao.upsertMeta(listOf(ProviderConfigMetaEntity("old", "original")))
        val before = dao.dump()
        dao.execute("CREATE TRIGGER reject_meta BEFORE DELETE ON provider_config_meta BEGIN SELECT RAISE(ABORT, 'injected'); END")
        expectFailure {
            dao.syncAllIncremental(listOf(instance().copy(label = "changed")), listOf(entry("new")), listOf(group("new")),
                listOf(ProviderAgentLoopIdEntity("entry", "new", 0)), listOf(ProviderConfigMetaEntity("new", "new")))
        }
        assertEquals(before, dao.dump())
        dao.assertForeignKeys()
    }

    @Test
    fun `matching mirror hash reopen keeps all 1200 selected models`() = runBlocking {
        val dir = Files.createTempDirectory("provider-reopen-").toFile()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        val cfg = ProviderConfig(
            instances = mutableListOf(ProviderInstance(id = "i1", label = "I",
                providerType = com.rikkaminis.app.data.model.ProviderType.openAI,
                credentialType = com.rikkaminis.app.data.model.ProviderCredential.apiKey)),
            modelEntries = ids("model", 1200).map { ModelEntry(providerInstanceId = "i1", baseModel = LLMModel(it, it, "openAI")) }.toMutableList(),
        )
        val mirror = json.encodeToString(cfg)
        val snapshot = cfg.toSnapshot(json, hashJsonMirror(mirror))
        try {
            SqliteDao(dir).use { dao ->
                dao.syncAllIncremental(snapshot.instances, snapshot.entries, snapshot.groups, snapshot.loopIds, snapshot.meta)
            }
            SqliteDao(dir).use { dao ->
                val storedHash = dao.loadMeta().first { it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH }.value
                assertEquals(ConfigLoadSource.DB, decideConfigSource(dao.instanceCount() > 0, true, storedHash == hashJsonMirror(mirror)))
                val loaded = ProviderConfigSnapshot(dao.loadInstances(), dao.loadEntries(), dao.loadGroups(), dao.loadAgentLoopIds(), dao.loadMeta())
                    .toProviderConfigWithDiagnostics(json)
                assertTrue(loaded.quarantinedEntryRowIds.isEmpty())
                assertEquals(snapshot.entries.map { it.id }.toSet(), loaded.config.modelEntries.map { it.uuid }.toSet())
                assertEquals(1200, loaded.config.modelEntries.size)
            }
        } finally { dir.deleteRecursively() }
    }

    private fun group(id: String) = ProviderModelGroupEntity(id, id, "manual", "none", memberEntryIdsJson = "[]")
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); throw AssertionError("injected SQLite error must escape") }
        catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("injected")) }
    }
}

/** JSON-lines RPC keeps one native SQLite connection open throughout each real DAO transaction. */
private class SqliteDao(dir: File) : RecordingProviderConfigDao(), AutoCloseable {
    private val driver = File(dir, "sqlite-driver.py").apply { writeText(DRIVER) }
    private val process = ProcessBuilder("python3", "-u", driver.path, File(dir, "provider.db").path, daoFile().path).start()
    private val input = process.inputStream.bufferedReader()
    private val output = process.outputStream.bufferedWriter()
    var completedEntryBatches = 0
        private set

    private fun rpc(method: String, rows: List<List<Any?>> = emptyList(), sql: String? = null): JSONArray {
        val request = JSONObject().put("method", method).put("rows", JSONArray(rows))
        if (sql != null) request.put("sql", sql)
        output.write(request.toString()); output.newLine(); output.flush()
        val line = input.readLine() ?: error("SQLite driver exited: ${process.errorStream.bufferedReader().readText()}")
        val result = JSONObject(line)
        check(!result.has("error")) { result.optString("error") }
        return result.getJSONArray("rows")
    }
    fun execute(sql: String) { rpc("execute", sql = sql) }
    private fun keys(method: String) = rpc(method).let { a -> (0 until a.length()).map { a.getJSONArray(it).getString(0) } }
    private fun delete(method: String, keys: List<String>) { rpc(method, listOf(keys)) }
    fun assertForeignKeys() { assertEquals(0, rpc("execute", sql = "PRAGMA foreign_key_check").length()) }
    fun dump() = listOf("provider_instances", "provider_model_entries", "provider_model_groups", "provider_agent_loop_ids", "provider_config_meta")
        .map { rpc("execute", sql = "SELECT * FROM $it ORDER BY 1,2").toString() }

    override suspend fun syncAllIncremental(instances: List<ProviderInstanceEntity>, entries: List<ProviderModelEntryEntity>, groups: List<ProviderModelGroupEntity>, loopIds: List<ProviderAgentLoopIdEntity>, meta: List<ProviderConfigMetaEntity>, preserveEntryIds: Set<String>) {
        execute("BEGIN")
        try {
            super.syncAllIncremental(instances, entries, groups, loopIds, meta, preserveEntryIds)
            execute("COMMIT")
        } catch (e: Throwable) { execute("ROLLBACK"); throw e }
    }
    override suspend fun loadInstanceIds() = keys("loadInstanceIds")
    override suspend fun loadEntryIds() = keys("loadEntryIds")
    override suspend fun loadGroupIds() = keys("loadGroupIds")
    override suspend fun loadMetaKeys() = keys("loadMetaKeys")
    override suspend fun deleteInstancesByIds(ids: List<String>) = delete("deleteInstancesByIds", ids)
    override suspend fun deleteEntriesByIds(ids: List<String>) { delete("deleteEntriesByIds", ids); completedEntryBatches++ }
    override suspend fun deleteGroupsByIds(ids: List<String>) = delete("deleteGroupsByIds", ids)
    override suspend fun deleteMetaByKeys(keys: List<String>) = delete("deleteMetaByKeys", keys)
    override suspend fun upsertInstances(rows: List<ProviderInstanceEntity>) {
        rpc("upsertInstances", rows.map { listOf(it.id, it.label, it.providerType, it.credentialType, it.sortOrder, it.createdAt) })
    }
    override suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>) {
        rpc("upsertEntries", rows.map { listOf(it.id, it.providerInstanceId, it.baseModelJson, it.sortOrder) })
    }
    override suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>) {
        rpc("upsertGroups", rows.map { listOf(it.id, it.name, it.strategy, it.fallbackStrategy, it.memberEntryIdsJson, it.sortOrder) })
    }
    override suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>) { rpc("upsertMeta", rows.map { listOf(it.key, it.value) }) }
    override suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>) { rpc("upsertAgentLoopIds", rows.map { listOf(it.kind, it.targetId, it.sortOrder) }) }
    override suspend fun clearInstances() { rpc("clearInstances") }
    override suspend fun clearEntries() { rpc("clearEntries") }
    override suspend fun clearGroups() { rpc("clearGroups") }
    override suspend fun clearAgentLoopIds() { rpc("clearAgentLoopIds") }
    override suspend fun clearMeta() { rpc("clearMeta") }
    override suspend fun loadInstances() = rpc("loadInstances").let { a -> (0 until a.length()).map { i ->
        val r = a.getJSONArray(i); ProviderInstanceEntity(r.getString(0), r.getString(1), r.getString(2), r.getString(3), sortOrder = r.getInt(4), createdAt = r.getLong(5))
    } }
    override suspend fun loadEntries() = rpc("loadEntries").let { a -> (0 until a.length()).map { i ->
        val r = a.getJSONArray(i); ProviderModelEntryEntity(r.getString(0), r.getString(1), r.getString(2), sortOrder = r.getInt(3))
    } }
    override suspend fun loadGroups() = rpc("loadGroups").let { a -> (0 until a.length()).map { i ->
        val r = a.getJSONArray(i); ProviderModelGroupEntity(r.getString(0), r.getString(1), r.getString(2), r.getString(3), memberEntryIdsJson = r.getString(4), sortOrder = r.getInt(5))
    } }
    override suspend fun loadAgentLoopIds() = rpc("loadAgentLoopIds").let { a -> (0 until a.length()).map { i ->
        val r = a.getJSONArray(i); ProviderAgentLoopIdEntity(r.getString(0), r.getString(1), r.getInt(2))
    } }
    override suspend fun loadMeta() = rpc("loadMeta").let { a -> (0 until a.length()).map { i ->
        val r = a.getJSONArray(i); ProviderConfigMetaEntity(r.getString(0), if (r.isNull(1)) null else r.getString(1))
    } }
    override suspend fun instanceCount() = rpc("instanceCount").getJSONArray(0).getInt(0)
    override fun close() {
        output.close()
        assertEquals("SQLite driver failed: ${process.errorStream.bufferedReader().readText()}", 0, process.waitFor())
        input.close()
    }
    private fun daoFile(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        val path = "src/android/app/src/main/java/com/rikkaminis/app/data/db/ProviderConfigDao.kt"
        while (dir != null) {
            File(dir, path).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("DAO source not found")
    }
    private companion object {
        private val DRIVER = """
            import json, re, sqlite3, sys
            from pathlib import Path
            source = Path(sys.argv[2]).read_text()
            queries = dict((name, sql) for sql, name in re.findall(r'@Query\("([^"]+)"\)\s*suspend fun (\w+)', source))
            db = sqlite3.connect(sys.argv[1], isolation_level=None)
            db.setlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
            db.execute('PRAGMA foreign_keys=ON')
            db.executescript('''
              CREATE TABLE IF NOT EXISTS provider_instances(id TEXT PRIMARY KEY, label TEXT, provider_type TEXT, credential_type TEXT, sort_order INTEGER, created_at INTEGER);
              CREATE TABLE IF NOT EXISTS provider_model_entries(id TEXT PRIMARY KEY, provider_instance_id TEXT NOT NULL REFERENCES provider_instances(id) ON DELETE CASCADE, base_model_json TEXT, sort_order INTEGER);
              CREATE TABLE IF NOT EXISTS provider_model_groups(id TEXT PRIMARY KEY, name TEXT, strategy TEXT, fallback_strategy TEXT, member_entry_ids_json TEXT, sort_order INTEGER);
              CREATE TABLE IF NOT EXISTS provider_agent_loop_ids(kind TEXT, target_id TEXT, sort_order INTEGER, PRIMARY KEY(kind,target_id));
              CREATE TABLE IF NOT EXISTS provider_config_meta(key TEXT PRIMARY KEY, value TEXT);
            ''')
            inserts = {
              'upsertInstances': 'INSERT INTO provider_instances VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET label=excluded.label,provider_type=excluded.provider_type,credential_type=excluded.credential_type,sort_order=excluded.sort_order,created_at=excluded.created_at',
              'upsertEntries': 'INSERT OR REPLACE INTO provider_model_entries VALUES(?,?,?,?)',
              'upsertGroups': 'INSERT OR REPLACE INTO provider_model_groups VALUES(?,?,?,?,?,?)',
              'upsertAgentLoopIds': 'INSERT OR REPLACE INTO provider_agent_loop_ids VALUES(?,?,?)',
              'upsertMeta': 'INSERT OR REPLACE INTO provider_config_meta VALUES(?,?)',
            }
            for line in sys.stdin:
                try:
                    r = json.loads(line)
                    method, rows = r['method'], r['rows']
                    if method in inserts:
                        db.executemany(inserts[method], rows)
                        out = []
                    else:
                        sql = r['sql'] if method == 'execute' else queries[method]
                        args = rows[0] if rows else []
                        if args:
                            sql, n = re.subn(r':\w+', ','.join('?' for _ in args), sql)
                            assert n == 1
                        out = db.execute(sql, args).fetchall()
                    print(json.dumps({'rows': out}), flush=True)
                except Exception as e:
                    print(json.dumps({'error': str(e)}), flush=True)
            db.close()
        """.trimIndent()
    }
}
