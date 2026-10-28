package com.rikkaminis.app.data.db

import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.data.model.FallbackStrategy
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderCredentialMeta
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Bidirectional ProviderConfig ↔ Room-entity mapping. Bundles all
 * five tables into one Snapshot so saveConfig writes them atomically
 * via [ProviderConfigDao.replaceAll].
 *
 * Entry IDs are rewritten to the composite "{instanceId}/{modelId}"
 * shape in [toSnapshot] so DB rows and the legacy JSON mirror share
 * the same id semantics post-migration. Group memberEntryIds and
 * agentLoopModelEntryIds are translated in lockstep using the same
 * map, so a group/agent-loop pin written before the migration still
 * resolves after.
 */
data class ProviderConfigSnapshot(
    val instances: List<ProviderInstanceEntity>,
    val entries: List<ProviderModelEntryEntity>,
    val groups: List<ProviderModelGroupEntity>,
    val loopIds: List<ProviderAgentLoopIdEntity>,
    val meta: List<ProviderConfigMetaEntity>,
)

object ProviderConfigMetaKeys {
    const val DEFAULT_PRIMARY_GROUP_ID = "default_primary_group_id"
    const val DEFAULT_SUB_GROUP_ID = "default_sub_group_id"
    // [T-android-provider-voice] Voice Input / Voice Output group bindings
    // (mirrors iOS provider_local_kv voiceInputGroupId / voiceOutputGroupId).
    // Meta KV rows are additive — no Room schema migration needed.
    const val VOICE_INPUT_GROUP_ID = "voice_input_group_id"
    const val VOICE_OUTPUT_GROUP_ID = "voice_output_group_id"
    const val JSON_SYNC_HASH = "json_sync_hash"
}

/**
 * Build a [ProviderConfigSnapshot] from [config]. The provided
 * [jsonForBlobs] must be the same kotlinx.serialization Json instance
 * the repository uses elsewhere — its `ignoreUnknownKeys` /
 * `encodeDefaults` settings shape every baseModel / overrides /
 * memberEntryIds blob on disk.
 *
 * [jsonSyncHash] is the hash of the legacy mirror JSON we just wrote
 * (or about to write) for downgrade-detection on next load. Pass null
 * when called from the load path's first-time JSON→DB import (we'll
 * compute and store it immediately after).
 */
fun ProviderConfig.toSnapshot(
    jsonForBlobs: Json,
    jsonSyncHash: String? = null,
): ProviderConfigSnapshot {
    // Map every legacy entry uuid → composite "{instanceId}/{modelId}".
    // Duplicate composite keys are theoretically impossible (one entry
    // per (instance, model)) — if we see one, last-write-wins on the
    // map, but both legacy uuids point at the same composite, so group /
    // agent-loop references converge harmlessly.
    val idMap = HashMap<String, String>(modelEntries.size)
    for (entry in modelEntries) {
        idMap[entry.uuid] = compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
    }

    val instanceRows = instances.mapIndexed { idx, inst ->
        ProviderInstanceEntity(
            id = inst.id,
            label = inst.label,
            providerType = inst.providerType.name,
            credentialType = inst.credentialType.name,
            customBaseURL = inst.customBaseURL,
            appendV1Suffix = if (inst.appendV1Suffix) 1 else 0,
            useResponsesAPI = if (inst.useResponsesAPI) 1 else 0,
            azureMode = if (inst.azureMode) 1 else 0,
            // [GH#68] Persist the picker choice + probe cache; auto is stored
            // explicitly (not null) so a legit "auto" survives round-trips too.
            imageEndpointMode = inst.imageEndpointMode.name,
            imageEndpointResolved = inst.imageEndpointResolved?.name,
            customUserAgent = inst.customUserAgent,
            isEnabled = if (inst.isEnabled) 1 else 0,
            sortOrder = idx,
            createdAt = inst.createdAt,
            // [P0-pinned-providers] Persist the favorite flag so Room
            // round-trips don't snap pinned instances back to unpinned.
            pinned = if (inst.pinned) 1 else 0,
            // [retained-schema:multi-api-key] Credential metadata blob. The
            // feature that used it is gone; the column (and this mapping) stay
            // because schema version 11 is already applied on installed
            // devices and the four-way sync gate wants the column mapped.
            // Encoded explicitly (not via a copy-through fast path) so the blob
            // keeps a typed, round-trip-tested shape. Secrets are NOT part of
            // it — see ProviderCredentialMeta's class doc.
            credentialsJson = if (inst.credentials.isEmpty()) null
                else jsonForBlobs.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ProviderCredentialMeta.serializer()),
                    inst.credentials,
                ),
        )
    }

    // Bucket entries by instance so sort_order is per-instance contiguous,
    // matching the loadEntries() ORDER BY clause.
    val entryRows = ArrayList<ProviderModelEntryEntity>(modelEntries.size)
    val grouped = modelEntries.groupBy { it.providerInstanceId }
    for ((instanceId, entries) in grouped) {
        entries.forEachIndexed { idx, e ->
            val composite = idMap[e.uuid] ?: compositeEntryKey(instanceId, e.baseModel.id)
            entryRows.add(
                ProviderModelEntryEntity(
                    id = composite,
                    providerInstanceId = instanceId,
                    baseModelJson = jsonForBlobs.encodeToString(LLMModel.serializer(), e.baseModel),
                    overridesJson = if (e.overrides.isEmpty) null
                        else jsonForBlobs.encodeToString(ModelOverrides.serializer(), e.overrides),
                    isCustom = if (e.isCustom) 1 else 0,
                    isHidden = if (e.isHidden) 1 else 0,
                    sortOrder = idx,
                    userModifiedAt = e.userModifiedAt,
                    costTier = e.costTier,
                )
            )
        }
    }

    val groupRows = modelGroups.mapIndexed { idx, g ->
        // Translate every member uuid to the composite shape. If an id
        // is already in composite form (e.g. user upgraded once, wrote
        // some config, downgraded, re-upgraded) it won't be in idMap;
        // pass it through unchanged — both forms remain valid string ids
        // and the lookup paths in ProviderRepository compare by string
        // equality regardless of shape.
        val translatedMembers = g.memberEntryIds.map { idMap[it] ?: it }
        ProviderModelGroupEntity(
            id = g.id,
            name = g.name,
            strategy = g.strategy.name,
            fallbackStrategy = g.fallbackStrategy.name,
            defaultThinkingLevel = g.defaultThinkingLevel?.name,
            contextLimitTokens = g.contextLimitTokens,
            lastContextLimitTokens = g.lastContextLimitTokens,
            memberEntryIdsJson = jsonForBlobs.encodeToString(
                ListSerializer(String.serializer()),
                translatedMembers,
            ),
            sortOrder = idx,
        )
    }

    val loopRows = ArrayList<ProviderAgentLoopIdEntity>(
        agentLoopModelEntryIds.size + agentLoopGroupIds.size,
    )
    agentLoopModelEntryIds.forEachIndexed { idx, id ->
        loopRows.add(
            ProviderAgentLoopIdEntity(
                kind = "entry",
                targetId = idMap[id] ?: id,
                sortOrder = idx,
            )
        )
    }
    agentLoopGroupIds.forEachIndexed { idx, id ->
        loopRows.add(
            ProviderAgentLoopIdEntity(
                kind = "group",
                targetId = id,
                sortOrder = idx,
            )
        )
    }

    val metaRows = mutableListOf<ProviderConfigMetaEntity>()
    defaultPrimaryGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID, it))
    }
    defaultSubGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID, it))
    }
    voiceInputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID, it))
    }
    voiceOutputGroupId?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID, it))
    }
    jsonSyncHash?.let {
        metaRows.add(ProviderConfigMetaEntity(ProviderConfigMetaKeys.JSON_SYNC_HASH, it))
    }

    return ProviderConfigSnapshot(instanceRows, entryRows, groupRows, loopRows, metaRows)
}

/**
 * Reverse-map a snapshot back to [ProviderConfig]. Entries' uuid
 * field is set to the composite-key id stored in DB — so once we round-trip,
 * group/agentLoop refs in the mirror JSON also point at the composite shape.
 */
private const val TAG = "ProviderConfigMapping"

/**
 * [fix/p1028-provider-corrupt-row] Result of [toProviderConfigWithDiagnostics]:
 * the in-memory config PLUS the primary keys of rows that could not be decoded
 * and were therefore left out of it.
 *
 * Why the ids have to travel with the result: `syncAllIncremental` computes its
 * delete set as "every id I was given → delete everything else" (`NOT IN`).
 * A decode failure means the row is missing from the list it feeds, so the very
 * next save would physically delete it — turning "one row is temporarily
 * unreadable" into "one row is permanently gone", with the legacy mirror
 * (which never carried the row's model) unable to bring it back.
 *
 * The mapping layer is the only place that can tell "row is absent from the
 * config" apart from "row could not be read" — hence the ids are produced HERE
 * and handed to the DAO explicitly, never re-derived there.
 */
data class ProviderConfigLoad(
    val config: ProviderConfig,
    /**
     * `provider_model_entries.id` values whose `base_model_json` failed to
     * decode. They stay in the DB untouched (no upsert, no delete) until a
     * build that CAN read them rotates them back into [config].
     */
    val quarantinedEntryRowIds: Set<String> = emptySet(),
    /**
     * [fix/meta1028-d1 F-02] Entry ids that ARE in [config] (the model stays
     * visible and usable with DEFAULT overrides) but whose `overrides_json`
     * column failed to decode. The persist path substitutes the stored blob —
     * see [restoreFrozenBlobs].
     */
    val unreadableOverridesEntryIds: Set<String> = emptySet(),
    /**
     * [fix/meta1028-d1 F-02] Group ids whose `member_entry_ids_json` failed to
     * decode; their in-memory member list is a placeholder (empty). Substituted
     * at persist time, and excluded from [ProviderRepository.removeInstance]'s
     * "became empty" group cascade so a decode failure can never delete a group.
     */
    val unreadableMemberGroupIds: Set<String> = emptySet(),
)

fun ProviderConfigSnapshot.toProviderConfig(jsonForBlobs: Json): ProviderConfig =
    toProviderConfigWithDiagnostics(jsonForBlobs).config

/**
 * [fix/p1028-provider-corrupt-row] Same projection as [toProviderConfig], but
 * also reports the rows it had to skip — see [ProviderConfigLoad]. Use this on
 * the load path; the plain [toProviderConfig] overload stays for the
 * canonicalization call sites that operate on an already-in-memory config.
 */
fun ProviderConfigSnapshot.toProviderConfigWithDiagnostics(jsonForBlobs: Json): ProviderConfigLoad {
    val instances = this.instances.map { row ->
        ProviderInstance(
            id = row.id,
            label = row.label,
            providerType = runCatching { ProviderType.valueOf(row.providerType) }
                .getOrElse { ProviderType.anthropic },
            // [T-android-enum-safe-parse] Safe parse: an unknown enum name
            // persisted by a NEWER build must not throw — it would blow up the
            // whole DB load, fall back to the JSON mirror (which fails
            // identically on the same value), and wipe providers/groups from
            // the UI. Same rationale as ThinkingLevel.decoded() below and
            // ImageEndpointMode's runCatching above. Unknown → first value.
            credentialType = runCatching { ProviderCredential.valueOf(row.credentialType) }
                .getOrElse { ProviderCredential.apiKey },
            isEnabled = row.isEnabled != 0,
            createdAt = row.createdAt,
            customBaseURL = row.customBaseURL,
            appendV1Suffix = row.appendV1Suffix != 0,
            customUserAgent = row.customUserAgent,
            useResponsesAPI = row.useResponsesAPI != 0,
            azureMode = row.azureMode != 0,
            // [GH#68] Safe parse: null (pre-migration rows) or an unknown
            // name from a future build falls back to auto / no cache rather
            // than throwing and wiping the whole provider load.
            imageEndpointMode = row.imageEndpointMode?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            } ?: ImageEndpointMode.auto,
            imageEndpointResolved = row.imageEndpointResolved?.let { m ->
                runCatching { ImageEndpointMode.valueOf(m) }.getOrNull()
            },
            // [P0-pinned-providers] Restore the favorite flag on load.
            pinned = row.pinned != 0,
            // [retained-schema:multi-api-key] Restore credential metadata. A
            // decode failure must NOT throw — that would blow up the whole
            // provider load and, via the JSON-mirror fallback, wipe the
            // provider list from the UI. Degrading to an empty list is
            // harmless: the single-key view always resolves the historical
            // `apikey_<id>` slot, so the user keeps a working provider even if
            // this blob is corrupt.
            credentials = row.credentialsJson?.let { blob ->
                runCatching {
                    jsonForBlobs.decodeFromString(
                        ListSerializer(ProviderCredentialMeta.serializer()),
                        blob,
                    )
                }.getOrElse { emptyList() }
            }?.toMutableList() ?: mutableListOf(),
        )
    }.toMutableList()

    val quarantinedEntryRowIds = LinkedHashSet<String>()
    // [fix/meta1028-d1 F-02] Rows that STAY in the config but whose stored blob
    // cannot be re-derived from what we decoded — see ProviderConfigLoad.
    val unreadableOverridesEntryIds = LinkedHashSet<String>()
    val entries = this.entries.mapNotNull { row ->
        // [audit-0917] Guard the two JSON blob decodes the same way the enum
        // parses above are guarded. A malformed baseModelJson/overridesJson
        // (truncated write, hand-edited mirror, forward-compat field) threw out
        // of the whole toProviderConfig, which failed the entire DB load — one
        // bad row wiped every provider and group from the UI. Skip the bad row
        // and keep the rest.
        //
        // [fix/p1028-provider-corrupt-row] …but "skip" must not become "delete".
        // Remember the row id so the sync path can keep it in its delete-set
        // (see ProviderConfigLoad / entryIdsToKeep). Skipping is a display
        // decision; the bytes stay put for a build that can read them.
        val baseModel = runCatching {
            jsonForBlobs.decodeFromString(LLMModel.serializer(), row.baseModelJson)
        }.onFailure {
            AppLogger.warning(
                TAG,
                "skipping model entry ${row.id}: baseModelJson undecodable (${it.message})",
            )
        }.getOrNull()
        if (baseModel == null) {
            quarantinedEntryRowIds.add(row.id)
            return@mapNotNull null
        }
        val overrides = row.overridesJson?.let { blob ->
            runCatching { jsonForBlobs.decodeFromString(ModelOverrides.serializer(), blob) }
                .onFailure { e ->
                    // [fix/meta1028-d1 F-02] An UNDECODABLE blob is not the same
                    // thing as a MISSING one (overridesJson == null, the common
                    // and legitimate "model uses defaults" case). Falling back to
                    // ModelOverrides() for display is fine; re-encoding that
                    // default on the next save would write `null` over the column
                    // and erase the user's real tuning permanently. Remember the
                    // id so the persist path substitutes the STORED blob instead.
                    unreadableOverridesEntryIds.add(row.id)
                    AppLogger.warning(
                        TAG,
                        "model entry ${row.id}: overridesJson undecodable — showing defaults, " +
                            "stored blob preserved (edits to this entry's overrides will not " +
                            "persist until a build can read it) (${e.message})",
                    )
                }
                .getOrNull()
        } ?: ModelOverrides()
        ModelEntry(
            providerInstanceId = row.providerInstanceId,
            baseModel = baseModel,
            overrides = overrides,
            isCustom = row.isCustom != 0,
            isHidden = row.isHidden != 0,
            uuid = row.id,
            userModifiedAt = row.userModifiedAt,
            costTier = row.costTier,
        )
    }.toMutableList()

    val stringListSerializer = ListSerializer(String.serializer())
    val unreadableMemberGroupIds = LinkedHashSet<String>()
    val groups = this.groups.map { row ->
        // [audit-0917] Same guard as the entry blobs above — a malformed member
        // list must not abort the whole provider load.
        // [fix/meta1028-d1 F-02] ...and "render empty" must not become "write
        // empty": member_entry_ids_json is non-null by schema, so a decode
        // failure is ALWAYS a damaged blob, never "the group really is empty".
        // Re-encoding mutableListOf() on the next save would delete every
        // member for good; remember the id so the persist path substitutes the
        // stored blob (restoreFrozenBlobs) and removeInstance's "became empty"
        // cascade skips it.
        val decodedMembers = runCatching {
            jsonForBlobs.decodeFromString(stringListSerializer, row.memberEntryIdsJson)
        }.onFailure {
            unreadableMemberGroupIds.add(row.id)
            AppLogger.warning(
                TAG,
                "group ${row.id}: memberEntryIdsJson undecodable — rendering empty, stored blob " +
                    "preserved (member edits will not persist until a build can read it) " +
                    "(${it.message})",
            )
        }.getOrNull()
        ModelGroup(
            id = row.id,
            name = row.name,
            memberEntryIds = decodedMembers?.toMutableList() ?: mutableListOf(),
            // [T-android-enum-safe-parse] Safe parse — same rationale as the
            // ThinkingLevel.decoded() note below: an unknown strategy name
            // from a NEWER build must not throw and blow up the whole DB load.
            // Unknown → first value (fallback / default / continueLast),
            // matching the pre-existing semantics those defaults had.
            strategy = runCatching { RoutingStrategy.valueOf(row.strategy) }
                .getOrElse { RoutingStrategy.fallback },
            fallbackStrategy = runCatching { FallbackStrategy.valueOf(row.fallbackStrategy) }
                .getOrElse { FallbackStrategy.default },
            // [T-android-thinking-level-arch] decoded() (not valueOf()) so a
            // level string a NEWER build persisted (e.g. "MAX"/"ULTRA") can't
            // throw and blow up the whole DB load — which would fall back to the
            // JSON mirror, which fails identically on the same enum value,
            // wiping all providers/groups from the UI. Unknown → XHIGH.
            defaultThinkingLevel = row.defaultThinkingLevel?.let { ThinkingLevel.decoded(it) },
            contextLimitTokens = row.contextLimitTokens,
            lastContextLimitTokens = row.lastContextLimitTokens,
        )
    }.toMutableList()

    val entryLoopIds = this.loopIds.filter { it.kind == "entry" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()
    val groupLoopIds = this.loopIds.filter { it.kind == "group" }
        .sortedBy { it.sortOrder }
        .map { it.targetId }
        .toMutableList()

    val metaMap = this.meta.associate { it.key to it.value }
    return ProviderConfigLoad(
        config = ProviderConfig(
            instances = instances,
            modelEntries = entries,
            modelGroups = groups,
            defaultPrimaryGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_PRIMARY_GROUP_ID],
            defaultSubGroupId = metaMap[ProviderConfigMetaKeys.DEFAULT_SUB_GROUP_ID],
            voiceInputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_INPUT_GROUP_ID],
            voiceOutputGroupId = metaMap[ProviderConfigMetaKeys.VOICE_OUTPUT_GROUP_ID],
            agentLoopModelEntryIds = entryLoopIds,
            agentLoopGroupIds = groupLoopIds,
        ),
        quarantinedEntryRowIds = quarantinedEntryRowIds,
        unreadableOverridesEntryIds = unreadableOverridesEntryIds,
        unreadableMemberGroupIds = unreadableMemberGroupIds,
    )
}

/**
 * [fix/meta1028-d1 F-02] Put the STORED blobs back into a snapshot whose
 * source config carried decoded-default placeholders.
 *
 * Two columns can be unreadable while the rest of the row is fine
 * (`overrides_json` on a model entry, `member_entry_ids_json` on a group). The
 * load path keeps the row visible with a placeholder (defaults / empty list) so
 * a single damaged blob does not hide a model or a whole group; writing that
 * placeholder back would destroy the bytes it stands in for. This substitution
 * happens at persist time, from rows re-read out of the DB, because `toSnapshot`
 * has no other source for "what the column actually holds".
 *
 * Pure on purpose: [ProviderRepository] is not JVM-constructible, and the failure
 * mode here (placeholder written back as truth) is exactly the kind of thing
 * that must be pinned by a unit test rather than by a device.
 *
 * @param storedEntries / [storedGroups] the raw rows as they exist on disk NOW
 *   (read after the config snapshot was built, so a concurrent write cannot
 *   make us invent a blob that never existed — a missing row simply leaves the
 *   placeholder in place).
 */
fun restoreFrozenBlobs(
    snapshot: ProviderConfigSnapshot,
    storedEntries: List<ProviderModelEntryEntity>,
    storedGroups: List<ProviderModelGroupEntity>,
    frozenEntryIds: Set<String>,
    frozenGroupIds: Set<String>,
): ProviderConfigSnapshot {
    if (frozenEntryIds.isEmpty() && frozenGroupIds.isEmpty()) return snapshot
    val entryBlobById = storedEntries
        .filter { it.id in frozenEntryIds }
        .associate { it.id to it.overridesJson }
    val groupBlobById = storedGroups
        .filter { it.id in frozenGroupIds }
        .associate { it.id to it.memberEntryIdsJson }
    return snapshot.copy(
        entries = snapshot.entries.map { row ->
            val blob = entryBlobById[row.id]
            if (row.id !in frozenEntryIds || blob == null) row else row.copy(overridesJson = blob)
        },
        groups = snapshot.groups.map { row ->
            val blob = groupBlobById[row.id]
            if (row.id !in frozenGroupIds || blob == null) row
            else row.copy(memberEntryIdsJson = blob)
        },
    )
}

/**
 * [fix/p1028-provider-corrupt-row] Delete-set for
 * [ProviderConfigDao.syncAllIncremental]'s entry table: every row we decoded
 * **plus** every row we quarantined because it could not be decoded.
 *
 * Pulled out as a pure function so the "keep the unreadable" rule is testable
 * without Room, and so the DAO has exactly one place where the set is built
 * (`NOT IN` turns "what I know" into "what should exist" — the invert is where
 * the bug lived).
 */
fun entryIdsToKeep(
    decoded: List<ProviderModelEntryEntity>,
    quarantinedEntryRowIds: Collection<String>,
): List<String> = (decoded.map { it.id } + quarantinedEntryRowIds).distinct()

/** Composite key shape used as ModelEntry id in DB and forward in JSON mirror. */
fun compositeEntryKey(instanceId: String, modelId: String): String = "$instanceId/$modelId"
