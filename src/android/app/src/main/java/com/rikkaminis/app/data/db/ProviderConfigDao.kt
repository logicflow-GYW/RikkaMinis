package com.rikkaminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * [fix/r3-data-1119 F-07] SQLite's hard bind-parameter ceiling.
 *
 * `SQLITE_MAX_VARIABLE_NUMBER` is **999** on the SQLite versions bundled by
 * API 26–29 (SQLite 3.18–3.28) and was raised to 32766 only later — this
 * app's `minSdk` is 26, so a `NOT IN (:list)` whose list is longer than 999
 * fails with "too many SQL variables" **at bind time**, before any row is
 * touched. A 1200-entry provider catalog therefore could not be saved at
 * all on an API 8–10 device: every `syncAllIncremental` rolled back and
 * `provider.db` never landed.
 */
internal const val SQLITE_MAX_VARIABLE_NUMBER = 999

/** Maximum primary keys bound by one positive `DELETE ... IN` statement. */
internal const val SQL_DELETE_CHUNK_SIZE = 900

/**
 * Split an already-computed delete set into bounded statements. Only positive
 * `IN` deletes compose across chunks: chunking a keep set for `NOT IN` would
 * delete rows that belong to another chunk.
 */
internal fun <T> chunkedIds(ids: List<T>, chunkSize: Int = SQL_DELETE_CHUNK_SIZE): List<List<T>> {
    require(chunkSize > 0) { "chunkSize must be positive" }
    if (ids.isEmpty()) return emptyList()
    val out = ArrayList<List<T>>((ids.size + chunkSize - 1) / chunkSize)
    var start = 0
    while (start < ids.size) {
        val end = minOf(start + chunkSize, ids.size)
        out.add(ids.subList(start, end))
        start = end
    }
    return out
}

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_instances ORDER BY sort_order ASC, created_at ASC")
    suspend fun loadInstances(): List<ProviderInstanceEntity>

    @Query("SELECT * FROM provider_model_entries ORDER BY provider_instance_id, sort_order ASC")
    suspend fun loadEntries(): List<ProviderModelEntryEntity>

    @Query("SELECT * FROM provider_model_groups ORDER BY sort_order ASC")
    suspend fun loadGroups(): List<ProviderModelGroupEntity>

    @Query("SELECT * FROM provider_agent_loop_ids ORDER BY kind, sort_order ASC")
    suspend fun loadAgentLoopIds(): List<ProviderAgentLoopIdEntity>

    @Query("SELECT * FROM provider_config_meta")
    suspend fun loadMeta(): List<ProviderConfigMetaEntity>

    // [fix/audit-b22 / T4-L6] Was @Insert(REPLACE): REPLACE deletes the old
    // row and inserts a new one, so the FK's ON DELETE CASCADE wiped every
    // provider_entries row of that instance on each sync — the exact opposite
    // of what this DAO's incremental-sync contract promises. @Upsert emits an
    // UPDATE for existing ids (Room falls back to INSERT-then-UPDATE on SQLite
    // < 3.24), so the parent row — and its children — survive.
    @Upsert
    suspend fun upsertInstances(rows: List<ProviderInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>)

    @Query("DELETE FROM provider_instances") suspend fun clearInstances()
    @Query("DELETE FROM provider_model_entries") suspend fun clearEntries()
    @Query("DELETE FROM provider_model_groups") suspend fun clearGroups()
    @Query("DELETE FROM provider_agent_loop_ids") suspend fun clearAgentLoopIds()
    @Query("DELETE FROM provider_config_meta") suspend fun clearMeta()

    // Read only primary keys: undecodable row payloads must not affect pruning.
    @Query("SELECT id FROM provider_instances")
    suspend fun loadInstanceIds(): List<String>

    @Query("SELECT id FROM provider_model_entries")
    suspend fun loadEntryIds(): List<String>

    @Query("SELECT id FROM provider_model_groups")
    suspend fun loadGroupIds(): List<String>

    @Query("SELECT key FROM provider_config_meta")
    suspend fun loadMetaKeys(): List<String>

    // Callers compute the FULL difference first, then bind at most 900 keys.
    @Query("DELETE FROM provider_instances WHERE id IN (:ids)")
    suspend fun deleteInstancesByIds(ids: List<String>)

    @Query("DELETE FROM provider_model_entries WHERE id IN (:ids)")
    suspend fun deleteEntriesByIds(ids: List<String>)

    @Query("DELETE FROM provider_model_groups WHERE id IN (:ids)")
    suspend fun deleteGroupsByIds(ids: List<String>)

    @Query("DELETE FROM provider_config_meta WHERE key IN (:keys)")
    suspend fun deleteMetaByKeys(keys: List<String>)

    /**
     * [fix/meta1028-d1 F-01] Drop a single meta row — the sync-token repair needs
     * "this key names no mirror" as an expressible state.
     *
     * Deleting the row (rather than storing an empty string) is what makes the
     * next cold start take the re-import branch: `loadConfigSuspending` treats a
     * missing `json_sync_hash` as "not in step", which is exactly the honest
     * answer after a rejected mirror write.
     */
    @Query("DELETE FROM provider_config_meta WHERE key = :key")
    suspend fun deleteMetaKey(key: String)

    /**
     * Atomic incremental replace — upsert the incoming rows, then delete only
     * the rows that are no longer present. Unlike [replaceAll], rows that are
     * unchanged keep their position/rowid, so SQLite doesn't pay an
     * delete+reinsert (page churn, index maintenance) for untouched data.
     *
     * Order matters because provider_model_entries has an FK CASCADE to
     * provider_instances:
     *   1. upsert instances first (so every referenced instance exists),
     *   2. delete vanished instances (their entries go away via CASCADE),
     *   3. upsert entries, then delete entries whose composite key vanished,
     *   4. groups / loop-ids / meta have no FK between them, order is free.
     *
     * Empty incoming lists retain the clear* fast paths.
     *
     * [fix/p1028-provider-corrupt-row] [preserveEntryIds] are `provider_model_entries`
     * rows the caller could NOT decode on load (see
     * [ProviderConfigLoad.quarantinedEntryRowIds]). They are absent from
     * [entries] by construction, so a plain `NOT IN (entries.map { id })` would
     * physically delete them on the first unrelated save — the load path's
     * "skip the unreadable row" guard would then be undone here, irreversibly
     * (the legacy mirror never carried those models). Passed in explicitly
     * rather than re-derived here: only the mapping layer can tell "row is
     * absent from the config" apart from "row could not be read".
     *
     * Compute existing primary keys minus the COMPLETE keep set before
     * chunking. Positive deletes then compose without removing another chunk's
     * retained rows, and every statement stays below the API 26–29 bind limit.
     * All reads, upserts and deletes remain in this transaction; any failure
     * rolls back the whole sync.
     */
    @Transaction
    suspend fun syncAllIncremental(
        instances: List<ProviderInstanceEntity>,
        entries: List<ProviderModelEntryEntity>,
        groups: List<ProviderModelGroupEntity>,
        loopIds: List<ProviderAgentLoopIdEntity>,
        meta: List<ProviderConfigMetaEntity>,
        preserveEntryIds: Set<String> = emptySet(),
    ) {
        if (instances.isEmpty()) {
            clearInstances() // CASCADE wipes their entries too
        } else {
            upsertInstances(instances)
            val keepInstanceIds = instances.map { it.id }.toHashSet()
            for (chunk in chunkedIds(loadInstanceIds().filterNot { it in keepInstanceIds })) {
                deleteInstancesByIds(chunk)
            }
        }

        // [fix/p1028-provider-corrupt-row] Quarantined rows count as "known":
        // they stay out of [entries] (so they are never upserted with a
        // degraded shape) but must survive the delete. When a corrupt row is the
        // ONLY row (entries empty + preserve non-empty) the clearEntries() fast
        // path must not run either — that is the same wipe via a different door.
        val keepEntryIds = entryIdsToKeep(entries, preserveEntryIds).toHashSet()
        if (keepEntryIds.isEmpty()) {
            clearEntries()
        } else {
            if (entries.isNotEmpty()) upsertEntries(entries)
            for (chunk in chunkedIds(loadEntryIds().filterNot { it in keepEntryIds })) {
                deleteEntriesByIds(chunk)
            }
        }

        if (groups.isEmpty()) {
            clearGroups()
        } else {
            upsertGroups(groups)
            val keepGroupIds = groups.map { it.id }.toHashSet()
            for (chunk in chunkedIds(loadGroupIds().filterNot { it in keepGroupIds })) {
                deleteGroupsByIds(chunk)
            }
        }

        if (loopIds.isEmpty()) {
            clearAgentLoopIds()
        } else {
            // composite PK (kind, target_id); the table is tiny (a handful
            // of agent-loop rows) so clear + upsert is simpler and safer
            // than a NOT-IN delete on a composite key.
            clearAgentLoopIds()
            upsertAgentLoopIds(loopIds)
        }

        if (meta.isEmpty()) {
            clearMeta()
        } else {
            upsertMeta(meta)
            val keepMetaKeys = meta.map { it.key }.toHashSet()
            for (chunk in chunkedIds(loadMetaKeys().filterNot { it in keepMetaKeys })) {
                deleteMetaByKeys(chunk)
            }
        }
    }

    /**
     * Atomic full replace. Entry FK CASCADE means clearing instances first
     * would wipe entries via cascade; we still clearEntries explicitly to
     * keep the order predictable and to defensively cover schema-without-FK
     * test paths.
     */
    @Transaction
    suspend fun replaceAll(
        instances: List<ProviderInstanceEntity>,
        entries: List<ProviderModelEntryEntity>,
        groups: List<ProviderModelGroupEntity>,
        loopIds: List<ProviderAgentLoopIdEntity>,
        meta: List<ProviderConfigMetaEntity>,
    ) {
        clearAgentLoopIds()
        clearGroups()
        clearEntries()
        clearInstances()
        clearMeta()
        upsertInstances(instances)
        upsertEntries(entries)
        upsertGroups(groups)
        upsertAgentLoopIds(loopIds)
        upsertMeta(meta)
    }

    @Query("SELECT COUNT(*) FROM provider_instances")
    suspend fun instanceCount(): Int

    // ---- [T-android-thinking-rules-phase2] Custom thinking rules ----
    //
    // Deliberately NOT touched by replaceAll(): thinking rules are per-instance user
    // data that is orthogonal to the config snapshot round-trip. They are cleaned up
    // only when their owning instance is deleted (see deleteThinkingRulesForInstance,
    // called from ProviderRepository.deleteInstance).

    @Query("SELECT * FROM provider_thinking_rules WHERE provider_instance_id = :instanceId ORDER BY sort_order ASC")
    suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity>

    @Query("SELECT * FROM provider_thinking_rules ORDER BY provider_instance_id, sort_order ASC")
    suspend fun loadAllThinkingRules(): List<ProviderThinkingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity)

    @Query("DELETE FROM provider_thinking_rules WHERE id = :id")
    suspend fun deleteThinkingRule(id: String)

    @Query("DELETE FROM provider_thinking_rules WHERE provider_instance_id = :instanceId")
    suspend fun deleteThinkingRulesForInstance(instanceId: String)

    /** Atomic reorder/replace of an instance's whole rule set (drag-reorder + add/edit). */
    @Transaction
    suspend fun replaceThinkingRules(instanceId: String, rows: List<ProviderThinkingRuleEntity>) {
        deleteThinkingRulesForInstance(instanceId)
        rows.forEach { upsertThinkingRule(it) }
    }
}
