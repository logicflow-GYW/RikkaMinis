package com.rikkaminis.app.data.db

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderInstance
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/p1028-provider-corrupt-row] B-1 device: an undecodable
 * `provider_model_entries` row must be skipped from the in-memory config but
 * must NOT be deleted by the next save.
 *
 * The bug this pins: `toProviderConfig` dropped the row (audit-0917, correct
 * for display), and `syncAllIncremental` then computed its delete set as
 * `NOT IN (rows I was given)` — so the dropped-but-still-present row was
 * physically deleted by any unrelated write, with no second copy anywhere
 * (the JSON mirror never held that model). Two halves, two owners: the mapping
 * layer reports the ids, the DAO keeps them in the delete set.
 *
 * Pure-Kotlin: `FakeProviderConfigDao` implements the DAO interface and runs
 * the REAL `syncAllIncremental` default body, so the keep-set assembly is under
 * test, not re-implemented.
 */
class ProviderConfigCorruptRowQuarantineTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun model(id: String) = LLMModel(id = id, displayName = "DN-$id", provider = "openAI")

    private fun goodEntryRow(instanceId: String, modelId: String): ProviderModelEntryEntity =
        ProviderModelEntryEntity(
            id = compositeEntryKey(instanceId, modelId),
            providerInstanceId = instanceId,
            baseModelJson = json.encodeToString(LLMModel.serializer(), model(modelId)),
        )

    /** Same shape as a half-written / forward-compat blob: truncated JSON. */
    private fun corruptEntryRow(instanceId: String, modelId: String): ProviderModelEntryEntity =
        ProviderModelEntryEntity(
            id = compositeEntryKey(instanceId, modelId),
            providerInstanceId = instanceId,
            baseModelJson = """{"id":"$modelId","displayName":"trunc""",
        )

    private fun instanceRow(id: String) = ProviderInstanceEntity(
        id = id,
        label = "L-$id",
        providerType = "anthropic",
        credentialType = "apiKey",
        createdAt = 1_700_000_000_000L,
    )

    private fun groupRow(id: String, members: List<String>) = ProviderModelGroupEntity(
        id = id,
        name = id.uppercase(),
        strategy = "fallback",
        fallbackStrategy = "default",
        memberEntryIdsJson = json.encodeToString(ListSerializer(String.serializer()), members),
    )

    private fun metaRow(key: String, value: String) = ProviderConfigMetaEntity(key, value)

    // ---------------------------------------------------------------- case 1

    @Test
    fun `corrupt entry row is skipped from the config and reported by id`() {
        val good = goodEntryRow("i1", "m-good")
        val bad = corruptEntryRow("i1", "m-bad")
        val load = ProviderConfigSnapshot(
            instances = listOf(instanceRow("i1")),
            entries = listOf(good, bad),
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        ).toProviderConfigWithDiagnostics(json)

        // Display side: only the decodable entry (a corrupt row must never be
        // handed to the UI as a half-object).
        assertEquals(
            listOf("i1/m-good"),
            load.config.modelEntries.map { it.uuid },
        )
        // Diagnostic side: the unreadable row's primary key.
        assertEquals(setOf("i1/m-bad"), load.quarantinedEntryRowIds)
    }

    // ---------------------------------------------------------------- case 2

    @Test
    fun `sync keeps the quarantined row in the entry delete set`() {
        val good = goodEntryRow("i1", "m-good")
        val bad = corruptEntryRow("i1", "m-bad")
        val load = ProviderConfigSnapshot(
            instances = listOf(instanceRow("i1")),
            entries = listOf(good, bad),
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        ).toProviderConfigWithDiagnostics(json)

        // Persist exactly what an ordinary save would: the snapshot built from
        // the loaded config (which never contains the corrupt row)…
        val saveSnapshot = load.config.toSnapshot(json, jsonSyncHash = "h")
        val dao = FakeProviderConfigDao()

        kotlinx.coroutines.runBlocking {
            dao.syncAllIncremental(
                instances = saveSnapshot.instances,
                entries = saveSnapshot.entries,
                groups = saveSnapshot.groups,
                loopIds = saveSnapshot.loopIds,
                meta = saveSnapshot.meta,
                preserveEntryIds = load.quarantinedEntryRowIds,
            )
        }

        val keep = dao.lastDeleteEntriesKeep
        assertTrue(
            "delete set must keep the undecodable row: was $keep",
            keep != null && keep.contains("i1/m-bad"),
        )
        assertEquals(
            "delete set must still keep every decodable row",
            setOf("i1/m-good", "i1/m-bad"),
            keep!!.toSet(),
        )
        // The quarantined row is never re-upserted with a degraded shape.
        assertEquals(listOf("i1/m-good"), dao.upsertedEntries.map { it.id })
    }

    // ---------------------------------------------------------------- case 3

    @Test
    fun `group reference to a quarantined entry survives the save`() {
        val good = goodEntryRow("i1", "m-good")
        val bad = corruptEntryRow("i1", "m-bad")
        val load = ProviderConfigSnapshot(
            instances = listOf(instanceRow("i1")),
            entries = listOf(good, bad),
            groups = listOf(groupRow("g1", listOf("i1/m-good", "i1/m-bad"))),
            loopIds = listOf(ProviderAgentLoopIdEntity(kind = "entry", targetId = "i1/m-bad", sortOrder = 0)),
            meta = emptyList(),
        ).toProviderConfigWithDiagnostics(json)

        // (a) references are NOT pruned by the load itself — the group keeps the
        // member id, so when a later build decodes the row the model returns to
        // its group instead of being orphaned.
        assertEquals(
            listOf("i1/m-good", "i1/m-bad"),
            load.config.modelGroups.single().memberEntryIds.toList(),
        )
        // (b) agent-loop pin pointing at the corrupt row is preserved too
        // (loop ids are read straight off their own table, not filtered by the
        // entries projection).
        assertEquals(listOf("i1/m-bad"), load.config.agentLoopModelEntryIds.toList())

        // (c) after the round-trip save the group row still carries the id, and
        // the group row is not in any delete set.
        val saveSnapshot = load.config.toSnapshot(json)
        val dao = FakeProviderConfigDao()
        kotlinx.coroutines.runBlocking {
            dao.syncAllIncremental(
                instances = saveSnapshot.instances,
                entries = saveSnapshot.entries,
                groups = saveSnapshot.groups,
                loopIds = saveSnapshot.loopIds,
                meta = saveSnapshot.meta,
                preserveEntryIds = load.quarantinedEntryRowIds,
            )
        }
        val groupRowOut = dao.upsertedGroups.single()
        assertEquals(
            listOf("i1/m-good", "i1/m-bad"),
            json.decodeFromString(ListSerializer(String.serializer()), groupRowOut.memberEntryIdsJson),
        )
        assertEquals(listOf("g1"), dao.lastDeleteGroupsKeep)
        assertEquals("i1/m-bad", dao.upsertedLoopIds.single().targetId)
        assertEquals(
            "the entry delete set keeps the member row the group still points at",
            setOf("i1/m-good", "i1/m-bad"),
            dao.lastDeleteEntriesKeep!!.toSet(),
        )
    }

    // ---------------------------------------------------------------- case 4

    @Test
    fun `fully decodable snapshot reports no quarantine and an unchanged delete set`() {
        val cfg = ProviderConfig(
            instances = mutableListOf(
                ProviderInstance(
                    id = "i1",
                    label = "L",
                    providerType = com.rikkaminis.app.data.model.ProviderType.anthropic,
                    credentialType = com.rikkaminis.app.data.model.ProviderCredential.apiKey,
                ),
            ),
            modelEntries = mutableListOf(
                ModelEntry(providerInstanceId = "i1", baseModel = model("m1")),
                ModelEntry(providerInstanceId = "i1", baseModel = model("m2")),
            ),
            modelGroups = mutableListOf(ModelGroup(id = "g1", name = "G1")),
        )
        val snap = cfg.toSnapshot(json, jsonSyncHash = "h")
        val load = ProviderConfigSnapshot(
            instances = snap.instances,
            entries = snap.entries,
            groups = snap.groups,
            loopIds = snap.loopIds,
            meta = snap.meta,
        ).toProviderConfigWithDiagnostics(json)

        assertTrue(
            "a healthy DB must report nothing quarantined",
            load.quarantinedEntryRowIds.isEmpty(),
        )
        // Pre-fix shape, verbatim: keep == every decoded id, in order.
        assertEquals(
            load.config.modelEntries.map { it.uuid },
            entryIdsToKeep(snap.entries, load.quarantinedEntryRowIds),
        )

        val dao = FakeProviderConfigDao()
        kotlinx.coroutines.runBlocking {
            dao.syncAllIncremental(
                instances = snap.instances,
                entries = snap.entries,
                groups = snap.groups,
                loopIds = snap.loopIds,
                meta = snap.meta,
                preserveEntryIds = load.quarantinedEntryRowIds,
            )
        }
        assertEquals(
            setOf("i1/m1", "i1/m2"),
            dao.lastDeleteEntriesKeep!!.toSet(),
        )
    }

    // ---------------------------------------------------------------- case 5

    @Test
    fun `all rows corrupt still never takes the clearEntries shortcut`() {
        val load = ProviderConfigSnapshot(
            instances = listOf(instanceRow("i1")),
            entries = listOf(corruptEntryRow("i1", "m1"), corruptEntryRow("i1", "m2")),
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        ).toProviderConfigWithDiagnostics(json)
        assertTrue(load.config.modelEntries.isEmpty())

        val saveSnapshot = load.config.toSnapshot(json)
        val dao = FakeProviderConfigDao()
        kotlinx.coroutines.runBlocking {
            dao.syncAllIncremental(
                instances = saveSnapshot.instances,
                entries = saveSnapshot.entries,
                groups = saveSnapshot.groups,
                loopIds = saveSnapshot.loopIds,
                meta = saveSnapshot.meta,
                preserveEntryIds = load.quarantinedEntryRowIds,
            )
        }
        assertTrue(
            "entries=empty but quarantined rows exist ⇒ clearEntries() would be the same wipe",
            dao.clearedTables.none { it == "provider_model_entries" },
        )
        assertEquals(setOf("i1/m1", "i1/m2"), dao.lastDeleteEntriesKeep!!.toSet())
    }

    // ---------------------------------------------------------------- case 6

    @Test
    fun `entryIdsToKeep appends quarantined ids without duplicating decoded ones`() {
        val rows = listOf(goodEntryRow("i1", "m1"), goodEntryRow("i1", "m2"))
        assertEquals(
            // decoded ids in load order, then quarantine-only ids in their own
            // order ("i1/m1" is in both sets ⇒ appears once).
            listOf("i1/m1", "i1/m2", "i1/ghost"),
            entryIdsToKeep(rows, setOf("i1/m1", "i1/ghost")),
        )
        // A stale quarantine id (row already gone) is harmless: NOT IN simply
        // names an id that matches nothing.
        assertEquals(emptyList<String>(), entryIdsToKeep(emptyList(), emptySet()))
        assertEquals(listOf("i1/x"), entryIdsToKeep(emptyList(), setOf("i1/x")))
    }
}

/**
 * Records what `syncAllIncremental` actually asks the DB to do. The real
 * default body runs (`@Transaction` is just an annotation off-device), so a
 * regression inside it — e.g. going back to `entries.map { it.id }` — shows up
 * here instead of only on a device.
 */
private class FakeProviderConfigDao : ProviderConfigDao {
    val clearedTables = mutableListOf<String>()
    val upsertedEntries = mutableListOf<ProviderModelEntryEntity>()
    val upsertedGroups = mutableListOf<ProviderModelGroupEntity>()
    val upsertedLoopIds = mutableListOf<ProviderAgentLoopIdEntity>()
    var lastDeleteEntriesKeep: List<String>? = null
    var lastDeleteGroupsKeep: List<String>? = null

    override suspend fun loadInstances(): List<ProviderInstanceEntity> = emptyList()
    override suspend fun loadEntries(): List<ProviderModelEntryEntity> = emptyList()
    override suspend fun loadGroups(): List<ProviderModelGroupEntity> = emptyList()
    override suspend fun loadAgentLoopIds(): List<ProviderAgentLoopIdEntity> = emptyList()
    override suspend fun loadMeta(): List<ProviderConfigMetaEntity> = emptyList()

    override suspend fun upsertInstances(rows: List<ProviderInstanceEntity>) = Unit
    override suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>) {
        upsertedEntries += rows
    }
    override suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>) {
        upsertedGroups += rows
    }
    override suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>) {
        upsertedLoopIds += rows
    }
    override suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>) = Unit

    override suspend fun clearInstances() {
        clearedTables += "provider_instances"
    }
    override suspend fun clearEntries() {
        clearedTables += "provider_model_entries"
    }
    override suspend fun clearGroups() {
        clearedTables += "provider_model_groups"
    }
    override suspend fun clearAgentLoopIds() = Unit
    override suspend fun clearMeta() = Unit

    override suspend fun deleteInstancesNotIn(keepIds: List<String>) = Unit
    override suspend fun deleteEntriesNotIn(keepIds: List<String>) {
        // SQLite rejects "NOT IN ()" — the DAO contract is that this is never
        // reached with an empty set (clearEntries covers that case).
        assertTrue("deleteEntriesNotIn must never be called with an empty keep set", keepIds.isNotEmpty())
        lastDeleteEntriesKeep = keepIds
    }
    override suspend fun deleteGroupsNotIn(keepIds: List<String>) {
        lastDeleteGroupsKeep = keepIds
    }
    override suspend fun deleteMetaNotIn(keepKeys: List<String>) = Unit

    override suspend fun instanceCount(): Int = 1

    override suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity> = emptyList()
    override suspend fun loadAllThinkingRules(): List<ProviderThinkingRuleEntity> = emptyList()
    override suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity) = Unit
    override suspend fun deleteThinkingRule(id: String) = Unit
    override suspend fun deleteThinkingRulesForInstance(instanceId: String) = Unit
}
