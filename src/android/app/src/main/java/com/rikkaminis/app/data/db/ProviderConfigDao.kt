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

/**
 * [fix/r3-data-1119 F-07] Rows handed to one `NOT IN (:list)` statement.
 * Strictly below [SQLITE_MAX_VARIABLE_NUMBER] so the ceiling is never
 * approached: Room binds one parameter per list element, and a list that
 * reaches the ceiling is a hard SQL error rather than a slow query.
 */
internal const val SQL_DELETE_CHUNK_SIZE = 900

/**
 * [fix/r3-data-1119 F-07] Split [ids] into statement-sized chunks.
 *
 * A single `NOT IN (:list)` binds one parameter per element, so the list has
 * to stay under SQLite's ceiling on every device this app runs on. Chunking
 * is semantically neutral: `NOT IN` over the union of the chunks selects
 * exactly the rows `NOT IN` over the whole list would have.
 *
 * The helper is a plain top-level function (not a DAO default method) so the
 * policy is drivable from a JVM test and so Room never has to generate a
 * super-call bridge for it — the DAO keeps exactly the abstract statements it
 * had before.
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

    /**
     * [fix/r3-data-1119 F-07] Raw statement for the instances delete. Room
     * binds one parameter per list element, so the caller must never hand
     * this more than [SQL_DELETE_CHUNK_SIZE] ids — see [chunkedIds].
     */
    @Query("DELETE FROM provider_instances WHERE id NOT IN (:keepIds)")
    suspend fun deleteInstancesNotIn(keepIds: List<String>)

    /** Raw statement for the entries delete; see [deleteInstancesNotIn]. */
    @Query("DELETE FROM provider_model_entries WHERE id NOT IN (:keepIds)")
    suspend fun deleteEntriesNotIn(keepIds: List<String>)

    /** Raw statement for the groups delete; see [deleteInstancesNotIn]. */
    @Query("DELETE FROM provider_model_groups WHERE id NOT IN (:keepIds)")
    suspend fun deleteGroupsNotIn(keepIds: List<String>)

    /** Raw statement for the meta delete; see [deleteInstancesNotIn]. */
    @Query("DELETE FROM provider_config_meta WHERE key NOT IN (:keepKeys)")
    suspend fun deleteMetaNotIn(keepKeys: List<String>)

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
     * Empty incoming lists use the clear* calls because SQLite rejects
     * "NOT IN ()" as a syntax error.
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
     * [fix/r3-data-1119 F-07] Every `NOT IN` delete below runs in
     * [SQL_DELETE_CHUNK_SIZE]-sized statements. One statement binding the
     * whole id list fails at bind time on API 26–29
     * ([SQLITE_MAX_VARIABLE_NUMBER]), which rolled this whole transaction
     * back — a provider catalog of ~1200 entries could not be saved at all on
     * an Android 8–10 device. The chunks are semantically neutral (the union
     * of `NOT IN` over the chunks is the same set), and because they all run
     * inside this `@Transaction`, a chunk that fails still rolls the whole
     * sync back rather than leaving a half-deleted table.
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
            for (chunk in chunkedIds(instances.map { it.id })) {
                deleteInstancesNotIn(chunk)
            }
        }

        // [fix/p1028-provider-corrupt-row] Quarantined rows count as "known":
        // they stay out of [entries] (so they are never upserted with a
        // degraded shape) but must survive the delete. When a corrupt row is the
        // ONLY row (entries empty + preserve non-empty) the clearEntries() fast
        // path must not run either — that is the same wipe via a different door.
        val keepEntryIds = entryIdsToKeep(entries, preserveEntryIds)
        if (keepEntryIds.isEmpty()) {
            clearEntries()
        } else {
            if (entries.isNotEmpty()) upsertEntries(entries)
            for (chunk in chunkedIds(keepEntryIds)) {
                deleteEntriesNotIn(chunk)
            }
        }

        if (groups.isEmpty()) {
            clearGroups()
        } else {
            upsertGroups(groups)
            for (chunk in chunkedIds(groups.map { it.id })) {
                deleteGroupsNotIn(chunk)
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
            for (chunk in chunkedIds(meta.map { it.key })) {
                deleteMetaNotIn(chunk)
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
