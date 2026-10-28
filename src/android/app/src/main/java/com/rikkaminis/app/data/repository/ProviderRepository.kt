package com.rikkaminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import com.rikkaminis.app.data.db.ProviderConfigDao
import com.rikkaminis.app.data.db.ProviderConfigMetaKeys
import com.rikkaminis.app.data.db.ProviderConfigMetaEntity
import com.rikkaminis.app.data.db.ProviderConfigSnapshot
import com.rikkaminis.app.data.db.ProviderDatabase
import com.rikkaminis.app.data.db.ProviderThinkingRuleEntity
import com.rikkaminis.app.data.db.compositeEntryKey
import com.rikkaminis.app.data.db.toProviderConfig
import com.rikkaminis.app.data.db.toProviderConfigWithDiagnostics
import com.rikkaminis.app.data.db.toSnapshot
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.ProviderConfig
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.SystemVoiceEntries
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.RoutingStrategy
import com.rikkaminis.app.data.model.SystemVoiceIds
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.data.model.VoiceProviderTemplate
import com.rikkaminis.app.data.model.hasAudioInput
import com.rikkaminis.app.data.model.hasAudioOutput
import com.rikkaminis.app.data.model.hasVoiceModality
import com.rikkaminis.app.data.model.isVoiceTemplateSeedShape
import com.rikkaminis.app.data.model.withInferredVoiceModality
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.registerModelListProviders
import com.rikkaminis.app.provider.thinking.ThinkingRule
import com.rikkaminis.app.provider.thinking.ThinkingRuleCoding
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class ProviderRepository(private val context: Context) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all providers/groups vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    private val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    companion object {
        /**
         * Per-instance model-cache TTL. Matches iOS's daily calendar-day
         * refresh window (24h rolling here — simpler than calendar-day math
         * and the behavioral difference at midnight is negligible). Used by
         * [triggerBackgroundRefreshIfStale] to decide whether a UI-triggered
         * stale-while-revalidate refresh should fire.
         */
        private const val MODEL_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

        /** Per-instance `lastFetchAt` pref key. */
        private fun lastFetchKey(instanceId: String) = "modelsLastFetchAt_$instanceId"

        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        private const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"

        /**
         * [T-android-provider-voice] Normalize a base URL for shadow-voice
         * cross-instance de-dup: lowercased, trailing "/" and "/v1" stripped.
         * Mirrors iOS ProviderConfigStore.normalizedShadowKey.
         */
        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }

        /**
         * [reorder-unify] Reorder [current] to match the id sequence in
         * [newOrder], or return null if [newOrder] isn't a faithful
         * permutation of it.
         *
         * Every drag-reorder mutator routes through here. A UI drag reports
         * "id A moved to where id B was", and the resulting order is computed
         * against a snapshot the UI read earlier — which may already be stale
         * (a cascade cleanup, a background model refresh, or a second screen
         * could have removed or added an element mid-gesture). Applying a
         * stale permutation blindly would silently drop or duplicate the
         * user's data, so a rejected reorder (null) is the safe outcome: the
         * caller keeps the persisted order and the row snaps back.
         *
         * Rejects, in order:
         *  - a length mismatch (something was added/removed mid-drag),
         *  - duplicate ids on either side. An already-corrupted [current]
         *    holding two elements with the same id cannot be reordered by id
         *    unambiguously, and a [newOrder] naming an id twice would
         *    duplicate one element while dropping another. Note this case is
         *    NOT caught by the set comparison below: current ids [A, B, B]
         *    against newOrder [B, A, B] matches on both length and id-set,
         *    yet applying it would lose one of the two B elements. Refuse.
         *  - an id-set mismatch (same count, different membership).
         *
         * Reordering is by id lookup rather than by index, so the elements
         * themselves are carried across untouched.
         */
        fun <T> permuteById(
            current: List<T>,
            newOrder: List<String>,
            idOf: (T) -> String,
        ): List<T>? {
            if (newOrder.size != current.size) return null
            val currentIds = current.map(idOf)
            val currentIdSet = currentIds.toSet()
            if (currentIdSet.size != currentIds.size) return null
            val newOrderSet = newOrder.toSet()
            if (newOrderSet.size != newOrder.size) return null
            if (newOrderSet != currentIdSet) return null
            val byId = current.associateBy(idOf)
            return newOrder.mapNotNull { byId[it] }
        }
    }

    /** Record the time of a successful model fetch for [instanceId]. */
    private fun markInstanceFetched(instanceId: String) {
        prefs.edit().putLong(lastFetchKey(instanceId), System.currentTimeMillis()).apply()
    }

    /** Whether the cached model list for [instanceId] is older than the TTL. */
    private fun isInstanceStale(instanceId: String): Boolean {
        val last = prefs.getLong(lastFetchKey(instanceId), 0L)
        return last == 0L || (System.currentTimeMillis() - last) > MODEL_CACHE_TTL_MS
    }

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * [triggerBackgroundRefreshIfStale] / [refreshAllModelsIfNeeded] call
     * refreshes it immediately. Called when instance config changes in ways
     * that could alter the set of available models (e.g. base URL rewrite,
     * credential swap). Mirrors iOS's implicit invalidation on
     * `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) {
        prefs.edit().remove(lastFetchKey(instanceId)).apply()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        com.rikkaminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
    }

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    private val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    private val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    /** Internal scope for the one-shot async config load. */
    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    private val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        // [T8-1] One-time registration of provider API adapters so the
        // data layer never imports provider implementations directly.
        // Idempotent (last-registration-wins per type).
        registerModelListProviders()
        loadScope.launch {
            val loaded = loadConfig()
            synchronized(configLock) {
                // Only adopt the disk config if nothing has written in the
                // meantime. A write during the load window (rare — writes come
                // from user/refresh actions that themselves need config) flips
                // _configLoaded true and takes precedence; we must not clobber
                // it with the stale on-disk snapshot.
                if (!_configLoaded.value) {
                    _config.value = loaded
                    _configLoaded.value = true
                    // [T-thinking-rules-cold-start-warm] Warm the resolver's
                    // custom-rule cache on the adopt path too. The sync-load
                    // path warms inside [ensureConfigLoaded], but once this
                    // async load wins the race every later ensureConfigLoaded
                    // early-returns — without this warm the cache stayed
                    // empty after a cold start and user thinking rules were
                    // silently ignored until the next rule edit.
                    loadAllThinkingRulesIntoCache()
                }
            }
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[Voice] ensureVoiceTemplateModels failed: ${e.message}")
            }
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    private val configLock = Any()

    /**
     * [T-android-provider-offmain-persist] Single-thread executor for the
     * serialize + DB + commit span (see [saveConfig]). One thread = persist
     * jobs land in submission (mutation) order and never overlap; daemon so
     * it can never block JVM exit.
     */
    private val persistExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ProviderConfig-Persist").apply { isDaemon = true }
        }

    /**
     * [fix/p1028-provider-corrupt-row] `provider_model_entries.id` values the
     * last DB load could not decode, and which the in-memory [ProviderConfig]
     * therefore does not contain. Kept here so every save can hand them to
     * [ProviderConfigDao.syncAllIncremental] as "known rows" — its delete set is
     * `NOT IN (ids I was given)`, so without this an unrelated write (rename a
     * provider, reorder models) physically deletes the unreadable row. The
     * legacy JSON mirror never carried those models, so nothing else can bring
     * the bytes back.
     *
     * Recomputed on every load (and only then): the row keeps failing to decode
     * until a build that can read it arrives, so the protection has to stay in
     * place for the whole process lifetime.
     */
    @Volatile
    private var quarantinedEntryRowIds: Set<String> = emptySet()

    /**
     * [fix/meta1028-d1 F-02] Entry ids whose row is IN the in-memory config (so
     * it shows and behaves normally with DEFAULT overrides) but whose stored
     * `overrides_json` could not be decoded. Same lifetime rule as
     * [quarantinedEntryRowIds]: recomputed on load, kept for the process
     * lifetime, because the persist path has to hand the STORED blob back
     * instead of re-encoding the placeholder (see [restoreFrozenBlobs]).
     */
    @Volatile
    private var unreadableOverridesEntryIds: Set<String> = emptySet()

    /**
     * [fix/meta1028-d1 F-02] Group ids whose stored `member_entry_ids_json`
     * could not be decoded; their in-memory member list is a placeholder (empty).
     * Two consumers: the persist path substitutes the stored blob, and
     * [removeInstance] refuses to treat them as "became empty" (deleting the
     * group would delete the only copy of its real member list).
     */
    @Volatile
    private var unreadableMemberGroupIds: Set<String> = emptySet()

    private fun loadConfig(): ProviderConfig = runBlocking { loadConfigSuspending() }

    /**
     * [T-android-provider-room-store] DB-first load with three-way
     * reconciliation between provider.db and the legacy JSON mirror:
     *
     *   - DB has rows AND meta.json_sync_hash matches the live mirror's
     *     hash → DB is in sync with what we last wrote. Use DB.
     *   - DB has rows but the hash mismatches → an older app build was
     *     installed at some point, wrote through the JSON path, and
     *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
     *     DB → resync hash.
     *   - DB is empty but JSON exists → first launch on a build that
     *     knows about the DB. One-shot import from JSON → DB.
     *   - Both empty → empty config (fresh install).
     *
     * The JSON mirror is the durable downgrade safety net: we keep
     * writing it on every save so the old build always sees current
     * config; if the user round-trips through an old build, the
     * hash check above re-syncs DB to whatever JSON looks like now.
     */
    private suspend fun loadConfigSuspending(): ProviderConfig {
        val tLoad = System.nanoTime()
        val rawJson = prefs.getString("config", null)
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            0
        }

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val tDb = System.nanoTime()
                val snapshot = ProviderConfigSnapshot(
                    instances = providerDao.loadInstances(),
                    entries = providerDao.loadEntries(),
                    groups = providerDao.loadGroups(),
                    loopIds = providerDao.loadAgentLoopIds(),
                    meta = providerDao.loadMeta(),
                )
                val dbMs = (System.nanoTime() - tDb) / 1_000_000
                val tAssemble = System.nanoTime()
                // [fix/p1028-provider-corrupt-row] Load through the diagnostics
                // overload: rows whose base_model_json won't decode are skipped
                // from the in-memory config (unchanged display behaviour) but
                // their ids are remembered so the sync path keeps them in the
                // delete-set. Skipping without this bookkeeping = deletion on
                // the next save, which is exactly what THIS function used to do.
                val loaded = snapshot.toProviderConfigWithDiagnostics(json)
                val cfg = loaded.config
                quarantinedEntryRowIds = loaded.quarantinedEntryRowIds
                unreadableOverridesEntryIds = loaded.unreadableOverridesEntryIds
                unreadableMemberGroupIds = loaded.unreadableMemberGroupIds
                if (loaded.quarantinedEntryRowIds.isNotEmpty()) {
                    android.util.Log.w(
                        "ProviderRepo",
                        "[ProviderStore] ${loaded.quarantinedEntryRowIds.size} provider model " +
                            "entries undecodable — rows preserved in provider.db (kept out of the " +
                            "model list; NOT deleted by the next sync): " +
                            loaded.quarantinedEntryRowIds.take(5).joinToString(","),
                    )
                }
                // [fix/meta1028-d1 F-02] Undecodable BLOBS on otherwise-readable
                // rows. They stay visible with placeholder values; the stored
                // bytes are handed back on every save until a build can read them.
                if (loaded.unreadableOverridesEntryIds.isNotEmpty()) {
                    android.util.Log.w(
                        "ProviderRepo",
                        "[ProviderStore] ${loaded.unreadableOverridesEntryIds.size} model " +
                            "entries have undecodable overrides — showing defaults, stored blobs " +
                            "preserved: ${loaded.unreadableOverridesEntryIds.take(5).joinToString(",")}",
                    )
                }
                if (loaded.unreadableMemberGroupIds.isNotEmpty()) {
                    android.util.Log.w(
                        "ProviderRepo",
                        "[ProviderStore] ${loaded.unreadableMemberGroupIds.size} model groups " +
                            "have undecodable member lists — rendering empty, stored blobs preserved " +
                            "(and protected from the removeInstance empty-group cascade): " +
                            loaded.unreadableMemberGroupIds.take(5).joinToString(","),
                    )
                }
                val assembleMs = (System.nanoTime() - tAssemble) / 1_000_000
                android.util.Log.i(
                    "ProviderPerf",
                    "loadConfigSuspending: instances=${snapshot.instances.size} " +
                        "entries=${snapshot.entries.size} groups=${snapshot.groups.size} " +
                        "db=${dbMs}ms assemble=${assembleMs}ms",
                )
                val storedHash = snapshot.meta.firstOrNull {
                    it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val tHash = System.nanoTime()
            val liveHash = rawJson?.let(::hashJsonMirror)
            val hashMs = (System.nanoTime() - tHash) / 1_000_000
            android.util.Log.i("ProviderPerf", "hashJsonMirror: ${hashMs}ms (jsonLen=${rawJson?.length})")
            // [fix/meta1028-d1 F-01] The hash gate is now a pure decision
            // ([decideConfigSource]) so that it is unit-tested TOGETHER with the
            // write order that makes it sound ([persistMirrorThenDb]). The
            // decision itself is unchanged; what changed is that the mirror can
            // no longer be older than the DB (see persistToDbAndMirror), which
            // is the premise "mismatch ⇒ the mirror is fresher" rests on.
            val source = decideConfigSource(
                hasDbConfig = true,
                hasMirror = rawJson != null,
                liveHashMatchesStored = liveHash != null && liveHash == dbHashStored,
            )
            if (source == ConfigLoadSource.DB) {
                return dbConfig
            }
            // Hash mismatch: the JSON mirror was written behind the DB's back
            // (an older build during a downgrade window, or a hand-edited /
            // restored prefs file). Re-import JSON → reseed DB so DB catches
            // up to the user's actual current config.
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val jsonLen = rawJson.length
            val tDecode = System.nanoTime()
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            val decodeMs = (System.nanoTime() - tDecode) / 1_000_000
            android.util.Log.i("ProviderPerf", "JSON fallback decode: ${decodeMs}ms (jsonLen=$jsonLen)")
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                // Migrate the per-user lastUsedEntryId SharedPreferences key
                // from the legacy random-uuid entry id form to the new
                // composite "{instanceId}/{modelId}" shape, using the
                // pre-canonicalization `parsed` entries as the uuid→composite
                // dictionary. Without this, the user's last-picked model on
                // the upgrade-first-launch isn't recognized by
                // lastUsedVisibleEntry() and the next new chat falls through
                // to the newest-provider fallback — i.e. it looks like the
                // upgrade "forgot" the user's recent selection.
                val legacyLastUsed = prefs.getString(KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        // [T-android-provider-room-store] Last-resort fallback. If DB had
        // rows but the live JSON mirror is unparseable (disk corruption,
        // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
        // — losing user config is worse than running with a stale mirror.
        // The next successful save will rewrite the mirror and resync the
        // hash. Returning ProviderConfig() here would let the very next
        // mutator's persistToDbAndMirror overwrite the populated DB with
        // an empty config, silently wiping the user's providers.
        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }
        return ProviderConfig()
    }

    /**
     * Atomically persist [config] to (DB) + (legacy JSON mirror), with
     * the meta.json_sync_hash kept in lockstep with the mirror we just
     * wrote. Returns the canonicalized config (entry uuids rewritten to
     * the composite "{instanceId}/{modelId}" shape via the snapshot
     * round-trip). Callers should treat the return value as the new
     * authoritative state — using the in-memory pre-call object would
     * leak the legacy uuid form into [_config.value].
     *
     * [fix/dv22-a1 §1042-A-01] Degraded mode: if the DB write fails
     * (provider.db unavailable), the failure is logged and the JSON mirror
     * is STILL written — with the DB down, prefs is the only surviving
     * store. The next launch's hash-mismatch re-import reseeds the DB from
     * the mirror once the DB opens again.
     */
    private suspend fun persistToDbAndMirror(config: ProviderConfig): ProviderConfig {
        // First serialize without the hash so the meta row reflects the
        // exact string we put into prefs (the hash sees the mirror that
        // older builds will read, not a hash-of-itself).
        val t0 = System.nanoTime()
        val mirrorStr = json.encodeToString(ProviderConfig.serializer(), config)
        val serializeMs = (System.nanoTime() - t0) / 1_000_000
        // [fix/dv22-a1 §1042-A-01] A provider.db that fails to open (migration
        // abort — ProviderDatabase has no fallbackToDestructiveMigration, see
        // MIGRATION_9_10's KDoc) would throw out of the sync below and stop the
        // mirror write from ever running: with the DB down, prefs is the only
        // surviving store, so the user's change was lost ENTIRELY. The DAO call
        // is therefore wrapped and its failure logged, leaving the mirror write
        // reachable — provider_import_id_roundtrip_check arm 10 pins that shape
        // (envelope around the DAO call, mirror OUTSIDE it).
        //
        // The sync token is hashed from the string we are ABOUT to commit, because
        // the DB write needs a value now. That makes it a CLAIM, not a fact —
        // [fix/meta1028-d1 F-01] repairs it to the truth right after commit()
        // answers, see the block below the sync. (The mirror hash sees the string
        // we put into prefs, not a hash of itself.)
        val mirrorHash = hashJsonMirror(mirrorStr)
        val snapshot = restoreFrozenBlobs(
            snapshot = config.toSnapshot(json, jsonSyncHash = mirrorHash),
            storedEntries = storedEntriesForFrozen,
            storedGroups = storedGroupsForFrozen,
            frozenEntryIds = unreadableOverridesEntryIds,
            frozenGroupIds = unreadableMemberGroupIds,
        )
        val t1 = System.nanoTime()
        val dbWrite = runCatching {
            providerDao.syncAllIncremental(
                instances = snapshot.instances,
                entries = snapshot.entries,
                groups = snapshot.groups,
                loopIds = snapshot.loopIds,
                meta = snapshot.meta,
                // [fix/p1028-provider-corrupt-row] The delete-set is derived from
                // `entries`, and quarantined rows are absent from it by
                // construction — hand them over explicitly so an unrelated save
                // (rename / reorder / refresh) doesn't physically drop the row
                // that the load path only meant to SKIP.
                preserveEntryIds = quarantinedEntryRowIds,
            )
        }
        dbWrite.onFailure { e ->
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] DB incremental sync failed (provider.db unavailable?); " +
                    "the JSON mirror already carries this write — DB stale until the next " +
                    "launch re-imports it: ${e.message}",
                e,
            )
        }

        // commit() not apply(): apply() queues the disk write and returns. If the
        // process dies before it flushes, the next launch sees
        // DB(hash=new) + JSON-on-disk(content=old) → the hash-mismatch path reads
        // that as "an old build wrote during a downgrade" and re-imports the stale
        // JSON. commit() blocks the persist thread (~5–30ms) and makes "did it
        // land" observable, which the repair below depends on.
        val mirrorWritten = prefs.edit().putString("config", mirrorStr).commit()
        if (!mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] prefs mirror commit() returned false — the DB rows keep the " +
                    "previous json_sync_hash after the repair below, so the two stores stay " +
                    "in step; this save is lost (disk full? prefs corruption?)",
            )
        }
        // [fix/meta1028-d1 F-01] `meta.json_sync_hash` must be a FACT about the
        // mirror, not a claim about the string we tried to write. The cold-start
        // rule is "mismatch ⇒ the mirror is fresher, re-import it", so a hash
        // naming a mirror that never landed makes the load path mistake a rejected
        // commit for a downgrade and back-fill the OLD JSON over the rows the user
        // just saved.
        //
        // The mirror cannot be written before the DB (provider_import_id_roundtrip
        // arm 10 pins that order), so the token is repaired after the fact: read
        // back what prefs actually holds and, when it disagrees with what the DB
        // was told, rewrite the single meta row to name the mirror that exists.
        val liveMirror: String? = prefs.getString("config", null)
        val truthfulHash = decideSyncHash(intendedMirror = mirrorStr, liveMirror = liveMirror)
        if (dbWrite.isSuccess && truthfulHash != mirrorHash) {
            runCatching {
                if (truthfulHash == null) {
                    providerDao.deleteMetaKey(ProviderConfigMetaKeys.JSON_SYNC_HASH)
                } else {
                    providerDao.upsertMeta(
                        listOf(
                            ProviderConfigMetaEntity(
                                ProviderConfigMetaKeys.JSON_SYNC_HASH,
                                truthfulHash,
                            ),
                        ),
                    )
                }
            }.onFailure { e ->
                // Best-effort: it cannot make the save durable, only stop a rejected
                // commit from being read as a downgrade. Name the remaining risk.
                android.util.Log.w(
                    "ProviderRepo",
                    "[ProviderStore] could not repair json_sync_hash after a rejected mirror " +
                        "write — the next cold start may re-import the previous mirror over " +
                        "these rows: ${e.message}",
                )
            }
        }
        // [fix/meta1028-d1 F-01] Repair the sync token when the DB was told to
        // expect a mirror that prefs never accepted. Without this the next cold
        // start computes liveHash(old) != storedHash(new) and re-imports the OLD
        // mirror over these rows — the user's last save, undone by the very
        // mechanism that exists to detect downgrades.
        if (!mirrorWritten && dbWrite.isSuccess) {
            runCatching {
                if (mirrorHash == null) {
                    providerDao.deleteMetaKey(ProviderConfigMetaKeys.JSON_SYNC_HASH)
                } else {
                    providerDao.upsertMeta(
                        listOf(
                            ProviderConfigMetaEntity(
                                ProviderConfigMetaKeys.JSON_SYNC_HASH,
                                mirrorHash,
                            ),
                        ),
                    )
                }
            }.onFailure { e ->
                // The repair is best-effort: it cannot make the save durable, only
                // stop a rejected commit from being mistaken for a downgrade. Say
                // so, and name the remaining risk explicitly.
                android.util.Log.w(
                    "ProviderRepo",
                    "[ProviderStore] could not repair json_sync_hash after a rejected mirror " +
                        "write — the next cold start may re-import the previous mirror over " +
                        "these rows: ${e.message}",
                )
            }
        }
        val dbMs = (System.nanoTime() - t1) / 1_000_000
        // [fix/dv22-a1 §1042-A-01] dbOk makes "did the DB row-set actually
        // land" judgeable from the log line itself, instead of inferring it
        // from the absence of a follow-up error. mirrorOk is new with the
        // order swap (§meta1028-d1): a save is fully durable only when BOTH
        // bits are true.
        android.util.Log.i(
            "ProviderPerf",
            "persistToDbAndMirror: entries=${snapshot.entries.size} " +
                "instances=${snapshot.instances.size} groups=${snapshot.groups.size} " +
                "mirror=${mirrorStr.length / 1024}KB serialize=${serializeMs}ms db=${dbMs}ms " +
                "dbOk=${dbWrite.isSuccess} mirrorOk=$mirrorWritten",
        )
        if (!dbWrite.isSuccess && !mirrorWritten) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] DB sync FAILED as well (see the incremental-sync error " +
                    "above) — neither store took this write, in-memory state only",
            )
        }
        // Return canonicalized form so the caller's _config.value reflects
        // entry uuids in composite-key shape from this write forward.
        return snapshot.toProviderConfig(json)
    }

    /**
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads `_config.value`. Without this a
     * write that lands during the startup load window would read the empty
     * placeholder, mutate it, and persist — WIPING the user's real config.
     * Idempotent and cheap once loaded (just a volatile read). Synchronized on
     * [configLock] so it can't race the async loader's emit.
     */
    private fun ensureConfigLoaded() {
        if (_configLoaded.value) return
        synchronized(configLock) {
            if (_configLoaded.value) return
            _config.value = loadConfig()
            _configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache
        // once config is available, so the (sync) request builder can read user rules.
        loadAllThinkingRulesIntoCache()
        if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
    }

    private fun saveConfig(config: ProviderConfig) {
        // [T-android-provider-room-store] Double-write: DB + legacy JSON
        // mirror, both produced inside the same configLock window. The
        // mirror keeps older app builds able to read current config on
        // downgrade; the DB is the new authoritative store on this build.
        //
        // [T-android-provider-offmain-persist] The serialize + DB + commit
        // span measured 60-160ms on device (ProviderPerf: 813KB mirror,
        // serialize 15-45ms + db 35-113ms + prefs commit fsync) and this
        // function runs on the CALLER's thread — UI click handlers included
        // (model visibility toggle, entry Save, group costTier), so every
        // settings action froze the UI for that span while holding
        // configLock. Now only the pure in-memory canonicalization +
        // emission stay on the caller; the disk work moves to a
        // single-thread persist executor:
        //  - ordering: submissions run in mutation order, so a rapid burst
        //    of writes lands on disk in the same order;
        //  - snapshot safety: `config` is the mutator's private
        //    mutationSnapshot and is not mutated after saveConfig returns
        //    (verified across all mutators — only cache invalidations
        //    follow), so serializing it off-lock cannot race a writer;
        //  - durability tradeoff: the disk write lands ~50-160ms after the
        //    mutation instead of before saveConfig returns — a crash in
        //    that window loses the last change. DB + mirror are still
        //    written by one job, so the json_sync_hash downgrade-detection
        //    invariant is unchanged.
        synchronized(configLock) {
            // Canonicalize synchronously — a pure in-memory round-trip over
            // ~1200 entries (idMap build + entity map + reverse map), no
            // disk and no 813KB mirror serialization — so the emission keeps
            // the consistent composite-id shape without waiting for the
            // persist job.
            val canonical = try {
                config.toSnapshot(json).toProviderConfig(json)
            } catch (e: Exception) {
                android.util.Log.w(
                    "ProviderRepo",
                    "[ProviderStore] canonicalize failed; emitting raw config: ${e.message}",
                )
                config
            }
            // [fix/dv20-a2 A-04] Publish AND persist the same object. The
            // persist face serializes whatever it is handed
            // ([persistToDbAndMirror] step 1: `json.encodeToString(config)` →
            // the prefs mirror string, plus the mirror hash stored in DB meta),
            // so passing the pre-canonicalization `config` wrote the legacy
            // shape to the mirror while `_config.value` carried the composite
            // shape — two readers of the same save disagreed (downgrade
            // reader / mirror-fallback load vs. the live UI), and the mirror's
            // entry ids + group/agent-loop references stayed in the legacy
            // form. [persistToDbAndMirror]'s own KDoc already required this
            // pairing ("using the in-memory pre-call object would leak the
            // legacy uuid form into [_config.value]").
            val published = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                // T273: structural equals walks the inner Lists and `prev`
                // (already mutated in place by the mutator) compares equal to
                // `next` → StateFlow would suppress the emission. Bumping
                // revision makes equals always false so 18+ collectAsState
                // callers see the new value.
                revision = config.revision + 1,
            )
            _config.value = published
            persistExecutor.execute {
                try {
                    runBlocking { persistToDbAndMirror(published) }
                } catch (e: Exception) {
                    // In-memory state is already emitted; the next successful
                    // save resyncs mirror + DB (same contract as the old
                    // catch path — fire-and-forget callers never see this).
                    android.util.Log.e(
                        "ProviderRepo",
                        "[ProviderStore] async persist failed; DB+mirror stale until next save: ${e.message}",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Make a private, writable snapshot for a mutation. Published
     * [ProviderConfig] values must never be modified in place: StateFlow and
     * Compose can be structurally comparing the previous value on the main
     * thread while a model-refresh coroutine returns on IO. Mutating one of
     * its ArrayLists in that window throws ConcurrentModificationException
     * from ArrayList.equalsArrayList (see crash-2026-08-02_20-56-28).
     *
     * ModelEntry and its nested model/overrides are immutable; ProviderInstance
     * and ModelGroup carry vars / mutable member ids and therefore need copies.
     */
    private fun mutationSnapshot(source: ProviderConfig): ProviderConfig = source.copy(
        instances = source.instances.map { it.copy() }.toMutableList(),
        modelEntries = source.modelEntries.toMutableList(),
        modelGroups = source.modelGroups.map { group ->
            group.copy(memberEntryIds = group.memberEntryIds.toMutableList())
        }.toMutableList(),
        agentLoopModelEntryIds = source.agentLoopModelEntryIds.toMutableList(),
        agentLoopGroupIds = source.agentLoopGroupIds.toMutableList(),
    )

    val instances: List<ProviderInstance> get() = _config.value.instances

    fun addInstance(instance: ProviderInstance) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.instances.add(instance)
        // [T-provider-no-static-seed] NO built-in model seeding, ever. A fresh
        // provider starts with an EMPTY model list (count 0) — the only source
        // of models is refreshModels() pulling the upstream /v1/models (or the
        // models.dev fallback). Rationale:
        //   - The static LLMModel.all* lists go stale: models get renamed,
        //     retired, added. Seeding them shows entries that don't exist
        //     upstream and hides the ones that do.
        //   - Every creation path (AddProviderScreen api-key + OAuth,
        //     Onboarding, RPC) follows addInstance with an immediate
        //     refreshModels() call, so the "UI is blank during first refresh"
        //     window is a fraction of a second — seeding for that is
        //     unnecessary, and it produced the inconsistent "Gemini shows 5
        //     built-ins, third-party OpenAI-compat shows 0" the user saw.
        // Voice-template seeds below are the ONLY exception: TTS/ASR vendors
        // (ElevenLabs, MiMo…) expose no /v1/models for their voices, so the
        // template is the sole source for those functional entries.
        // [T-android-provider-voice] Seed voice-template mock models when the
        // base URL matches a voice vendor (MiMo / MiniMax / Doubao …). These
        // vendors have no /v1/models for their voices, so the template list is
        // the only source. Mirrors iOS addInstance + VoiceProviderTemplate.
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
            .filter { seed ->
                config.modelEntries.none {
                    it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
                }
            }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            android.util.Log.i(
                "ProviderRepo",
                "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
            )
        }
        saveConfig(config)
        // Stale from the start so the next background sweep (or an explicit
        // triggerBackgroundRefreshIfStale call) will fetch it.
        invalidateModelCache(instance.id)
    }

    /**
     * [da1024-C] Clear the prefs-held secondary references an entry removal
     * orphans: `lastUsedEntryId` and the voice input/output overrides. They are
     * per-device SharedPreferences values, so [saveConfig]'s idMap translation
     * cannot heal them the way it heals the in-config group / agent-loop refs.
     *
     * The compact pin is deliberately NOT handled here — every call site goes
     * through the shared [decideCompactPinCleanup] predicate (one rule, four
     * sites) rather than a second copy of "is the pin dangling".
     */
    private fun clearStaleSecondaryRefs(removedEntryIds: Collection<String>) {
        if (decideStaleRefCleanup(lastUsedEntryId, removedEntryIds)) lastUsedEntryId = null
        if (decideStaleRefCleanup(voiceInputOverrideEntryId, removedEntryIds)) {
            voiceInputOverrideEntryId = null
        }
        if (decideStaleRefCleanup(voiceOutputOverrideEntryId, removedEntryIds)) {
            voiceOutputOverrideEntryId = null
        }
    }

    /**
     * [T-android-provider-voice] Reconcile voice-template seed entries on every
     * launch: add template models a previous build didn't know, remove RETIRED
     * seeds, and heal corrupted modality. Straight port of iOS
     * ProviderConfigStore.ensureVoiceTemplateModels with its two hard-won
     * guards:
     *
     *  - [T-voice-seed-shape-exact] Stale-seed removal matches ONLY the exact
     *    single-flag seed shape (isVoiceTemplateSeedShape). API-fetched models
     *    always carry the paired text side (≥2 flags), so a future API ASR/TTS
     *    model can never be mistaken for a seed and wiped every launch
     *    (regression class of cffbec0e: MiMo launch model count 15→11).
     *  - Heal: a models refresh can overwrite a template entry with text-only
     *    modality from /v1/models; restore the template's authoritative
     *    modality when it diverged.
     */
    fun ensureVoiceTemplateModels() = synchronized(configLock) {
        ensureConfigLoaded()
        // Runs on Dispatchers.IO from the init block's loadScope while Compose
        // may be structurally comparing the published config on Main — mutate a
        // detached snapshot, never `_config.value`. See [mutationSnapshot].
        val config = mutationSnapshot(_config.value)
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()

            // Remove stale template seeds (exact seed shape only) not in the
            // current template version.
            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                // [da1031-A] The stale-seed ids are bare baseModel.id values, but
                // every secondary reference hangs off the entry id (uuid,
                // canonicalized to the composite "{instanceId}/{modelId}" shape
                // by toSnapshot). Comparing bare ids against entry ids made the
                // whole cascade below a no-op.
                val staleEntryIds = instanceEntries
                    .filter { it.baseModel.isVoiceTemplateSeedShape }
                    .filter { it.baseModel.id !in templateById }
                    .map { it.id }
                config.modelEntries.removeAll {
                    it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
                }
                // [da1024-C] Full secondary-reference cascade for the ids just
                // dissolved (mirrors replaceEntries' prune branch): group
                // members, agent-loop pins, the compact pin and the per-device
                // lastUsed / voice references.
                for (group in config.modelGroups) {
                    group.memberEntryIds.removeAll { it in staleEntryIds }
                }
                config.agentLoopModelEntryIds.removeAll { it in staleEntryIds }
                if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), staleEntryIds)) {
                    AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
                }
                clearStaleSecondaryRefs(staleEntryIds)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }

            // Add template models missing from this instance.
            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }

            // Heal corrupted modality on surviving template entries.
            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) saveConfig(config)
    }

    fun updateInstance(instance: ProviderInstance) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            // Store a private copy: `instance` is caller-owned and carries
            // `var` fields, so keeping the caller's reference in the published
            // config would let a later caller-side edit mutate live state (the
            // same class of race as the in-place list mutation above).
            var next = instance.copy()
            // [T-android-image-endpoint-mode] If the base URL or v1-suffix
            // changed, the previously probed image endpoint may not exist on the
            // new upstream — drop the cached resolution so auto mode re-probes.
            // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
            // the caller didn't already set it (e.g. the UI clears it itself when
            // the user forces a non-auto mode).
            if ((prior.customBaseURL != next.customBaseURL ||
                    prior.appendV1Suffix != next.appendV1Suffix) &&
                next.imageEndpointResolved != null
            ) {
                next = next.copy(imageEndpointResolved = null)
            }
            config.instances[idx] = next
            saveConfig(config)
            // Any change that could move the model list (base URL, credential
            // swap, enabled flag, API format) invalidates the cache. Cheap to
            // over-invalidate.
            if (prior.effectiveBaseURL != next.effectiveBaseURL ||
                prior.credentialType != next.credentialType ||
                prior.isEnabled != next.isEnabled ||
                prior.useResponsesAPI != next.useResponsesAPI
            ) {
                invalidateModelCache(next.id)
            }
        }
    }

    /**
     * Set whether a provider instance is pinned ("favorite"). Multiple
     * instances may be pinned; pinned instances render in a dedicated
     * favorites section at the top of the provider list.
     * [P0-pinned-providers]
     */
    fun setInstancePinned(instanceId: String, pinned: Boolean) = synchronized(configLock) {
        ensureConfigLoaded()
        val idx = _config.value.instances.indexOfFirst { it.id == instanceId }
        if (idx >= 0) {
            // updateInstance handles mutationSnapshot + saveConfig + cache
            // invalidation; delegate to keep one source of truth.
            updateInstance(_config.value.instances[idx].copy(pinned = pinned))
        }
    }

    fun removeInstance(instanceId: String) = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = mutationSnapshot(_config.value)
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }

        // [fix/meta1028-d1 F-02] Which groups must be deleted: only the ones
        // THIS removal emptied. The pre-fix code scanned every group with an
        // empty member list, which also matched (a) a group that was already
        // empty before this call, and (b) a group whose member list merely
        // failed to DECODE on load (the placeholder is empty) — deleting those
        // removes the only copy of their member list from provider.db.
        val emptiedGroupIds = LinkedHashSet<String>()
        if (removedEntryIds.isNotEmpty()) {
            val touchedGroupIds = config.modelGroups
                .filter { group -> group.memberEntryIds.any { it in removedEntryIds } }
                .map { it.id }
                .toSet()
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            emptiedGroupIds.addAll(
                decideEmptiedGroupIds(
                    groups = config.modelGroups.map { it.id to it.memberEntryIds.toList() },
                    touchedGroupIds = touchedGroupIds,
                    unreadableGroupIds = unreadableMemberGroupIds,
                ),
            )
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
            // [da1023-C] Same compact-pin cleanup as removeEntry, applied to
            // the whole removed set — removeInstance cascades on its own and
            // would otherwise leave the pin dangling on a vanished instance.
            if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), removedEntryIds)) {
                AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
            }
            // [da1024-C] ...and the per-device refs the same removal orphans.
            clearStaleSecondaryRefs(removedEntryIds)
        }
        val emptyGroupIds = emptiedGroupIds
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
            // [T-provider-remove-group-refs] Removing an instance can empty a
            // group and delete it here (direct removeAll, NOT via removeGroup),
            // so mirror removeGroup's full reference cascade: drop the group
            // from agent-loop pins and voice input/output bindings — otherwise
            // those lists keep dangling ids for a group that no longer exists
            // (the group-side twin of T171's entry-side stale-pin bug).
            if (config.voiceInputGroupId in emptyGroupIds) config.voiceInputGroupId = null
            if (config.voiceOutputGroupId in emptyGroupIds) config.voiceOutputGroupId = null
            config.agentLoopGroupIds.removeAll { it in emptyGroupIds }
        }

        saveConfig(config)
        deleteApiKey(instanceId)
        // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
        // rules from Room and the resolver cache (they can never fire again).
        runCatching {
            runBlocking { providerDao.deleteThinkingRulesForInstance(instanceId) }
            ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        }
    }

    /**
     * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
     * `auto`-mode image generation on [instanceId]. Called by
     * ModelUseOffloadHandler after a successful /images/generations call (cache
     * `imagesGenerations`) or after a route-missing fallback (cache
     * `chatCompletions`). No-op when unchanged so we don't churn the config /
     * iCloud sync on every image call. Does not invalidate the model cache —
     * the endpoint choice never moves the model list.
     */
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode) = synchronized(configLock) {
        ensureConfigLoaded()
        // Called from ModelUseOffloadHandler on the sandbox offload path (not
        // Main). Snapshot before writing: this mutates a ProviderInstance `var`,
        // which on the published config would be visible mid-comparison to a
        // Compose collector. See [mutationSnapshot].
        val config = mutationSnapshot(_config.value)
        val idx = config.instances.indexOfFirst { it.id == instanceId }
        if (idx < 0) return@synchronized
        if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
        config.instances[idx].imageEndpointResolved = endpoint
        saveConfig(config)
    }

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    // ── [T-android-thinking-rules-phase2] Custom thinking rules ──

    /**
     * [T-thinking-rules-single-query] One Room roundtrip returns both the
     * rules and their persisted ids — the UI previously called
     * [thinkingRules] and [thinkingRuleIds] separately, i.e. the SAME
     * loadThinkingRules query twice inside two `remember { }` blocks.
     */
    fun thinkingRulesWithIds(instanceId: String): Pair<List<ThinkingRule>, List<String>> = runBlocking {
        runCatching {
            val rows = providerDao.loadThinkingRules(instanceId)
            rows.map { ThinkingRuleCoding.toRule(it) } to rows.map { it.id }
        }.getOrDefault(emptyList<ThinkingRule>() to emptyList())
    }

    /** Load one instance's custom rules from Room, in stored order. */
    fun thinkingRules(instanceId: String): List<ThinkingRule> = runBlocking {
        runCatching { providerDao.loadThinkingRules(instanceId).map { ThinkingRuleCoding.toRule(it) } }
            .getOrDefault(emptyList())
    }

    /** [T-thinking-rules-single-query] The standalone [thinkingRuleIds] was
     *  folded into [thinkingRulesWithIds] — a second `loadThinkingRules`
     *  query for the same table existed only because the UI wanted ids
     *  separately. Call [thinkingRulesWithIds] instead. */

    /** First model id served by [instanceId], for the resolution-trace sample. Null if none. */
    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.baseModel?.id
    }

    /** Warm the resolver cache with every instance's custom rules (called on config load). */
    fun loadAllThinkingRulesIntoCache() {
        runCatching {
            val rows = runBlocking { providerDao.loadAllThinkingRules() }
            val byInstance = rows.groupBy { it.providerInstanceId }
                .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { ThinkingRuleCoding.toRule(it) } }
            ThinkingRuleResolver.setAllCustomRules(byInstance)
        }
    }

    private fun republishThinkingCache(instanceId: String) {
        ThinkingRuleResolver.setCustomRules(instanceId, thinkingRules(instanceId))
    }

    /**
     * [T-thinking-rules-single-query] Mutation paths pass the rows they just
     * persisted so the resolver cache is updated WITHOUT a second Room
     * roundtrip. Null = persistence failed → fall back to a fresh DB query
     * so the cache never holds stale data.
     */
    private fun republishThinkingCache(instanceId: String, rules: List<ThinkingRule>?) {
        if (rules != null) {
            ThinkingRuleResolver.setCustomRules(instanceId, rules)
            return
        }
        republishThinkingCache(instanceId)
    }

    /**
     * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
     * list (position 0) — a rule overriding a built-in is useless below it; existing
     * rules shift down. A non-null [id] updates in place, preserving position.
     * Returns the rule id.
     */
    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String = runBlocking {
        val ruleId = id ?: java.util.UUID.randomUUID().toString()
        // [T-thinking-rules-single-query] Persisted rows are reused for the
        // resolver cache (republish takes nullable rows — on failure it
        // falls back to a fresh DB query so the cache never holds stale data).
        var persistedRules: List<ThinkingRule>? = null
        // [audit-0909 T4-H1] These DAO writes used to be bare. A provider.db
        // that failed to open (e.g. a migration abort on API ≤ 33 — see
        // ProviderDatabase.MIGRATION_9_10) threw an uncaught SQLiteException
        // straight into the settings editor. Mirror the read paths above
        // (runCatching) but log loudly instead of silently dropping.
        runCatching {
            val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
            val idx = existing.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                // Update in place at its current sort_order.
                existing[idx] = ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, existing[idx].sortOrder)
            } else {
                // New rule at the top; everything else shifts down.
                existing.add(0, ThinkingRuleCoding.toEntity(rule, ruleId, instanceId, 0))
            }
            val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, renumbered)
            // [fix/dv23-a1 A-01] Assigned ONLY AFTER the DAO write returned.
            // Before, this assignment sat in front of replaceThinkingRules: a
            // write that threw (disk full / SQLite lock / IO error) left
            // persistedRules non-null, so republishThinkingCache took the
            // "persisted" fast path and published rules that never reached
            // provider.db into ThinkingRuleResolver + bumped
            // customRulesRevision. Null ⇒ the no-arg overload re-reads the
            // DB, so the cache can only ever hold disk truth.
            persistedRules = renumbered.map { ThinkingRuleCoding.toRule(it) }
        }.onFailure { e ->
            android.util.Log.e("ProviderRepo", "[audit-0909] saveThinkingRule persistence failed ($instanceId/$ruleId)", e)
        }
        republishThinkingCache(instanceId, persistedRules)
        ruleId
    }

    fun deleteThinkingRule(instanceId: String, id: String) = runBlocking {
        // [audit-0909 T4-H1] same bare-DAO hardening as saveThinkingRule.
        var persistedRules: List<ThinkingRule>? = null
        runCatching {
            providerDao.deleteThinkingRule(id)
            // Renumber survivors so sort_order stays dense.
            val survivors = providerDao.loadThinkingRules(instanceId)
                .sortedBy { it.sortOrder }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, survivors)
            // [fix/dv23-a1 A-01] assigned only after the DAO write returned —
            // see the note in saveThinkingRule. A failed write leaves
            // persistedRules null ⇒ the cache is re-read from provider.db.
            persistedRules = survivors.map { ThinkingRuleCoding.toRule(it) }
        }.onFailure { e ->
            android.util.Log.e("ProviderRepo", "[audit-0909] deleteThinkingRule persistence failed ($instanceId/$id)", e)
        }
        republishThinkingCache(instanceId, persistedRules)
    }

    /** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) = runBlocking {
        // [audit-0909 T4-H1] same bare-DAO hardening as saveThinkingRule.
        var persistedRules: List<ThinkingRule>? = null
        runCatching {
            val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            // Keep any id the caller omitted (defensive against a partial list) appended.
            val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
            providerDao.replaceThinkingRules(instanceId, reordered + omitted)
            // [fix/dv23-a1 A-01] assigned only after the DAO write returned —
            // see the note in saveThinkingRule.
            persistedRules = (reordered + omitted).map { ThinkingRuleCoding.toRule(it) }
        }.onFailure { e ->
            android.util.Log.e("ProviderRepo", "[audit-0909] reorderThinkingRules persistence failed ($instanceId)", e)
        }
        republishThinkingCache(instanceId, persistedRules)
    }

    // ── [T-auto-backup-assets] Backup/restore of custom thinking rules ──
    //
    // The custom rules are per-provider user data with NO cloud sync (Android
    // provider config is local-only). Without a backup path they silently
    // vanish on restore — the same field-evap family as the env-var group bug.
    // Export keeps the full entity row (including the stable rule id and
    // sort_order) so restore is faithful; ConfigBackup attaches the owning
    // provider's (providerType, label) so rules can be re-attached after the
    // provider's own id was re-minted by a restore.

    /** Backup JSON rows for [instanceId]'s custom rules, in stored order. */
    fun exportThinkingRulesJSON(instanceId: String): org.json.JSONArray? = runBlocking {
        val rows = runCatching { providerDao.loadThinkingRules(instanceId) }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return@runBlocking null
        val arr = org.json.JSONArray()
        for (row in rows.sortedBy { it.sortOrder }) {
            arr.put(org.json.JSONObject().apply {
                put("id", row.id)
                put("label", row.label)
                put("scopeKind", row.scopeKind)
                row.scopePattern?.let { put("scopePattern", it) }
                row.wireFormatJson?.let { put("wireFormatJson", it) }
                row.reasoningEchoJson?.let { put("reasoningEchoJson", it) }
                put("sortOrder", row.sortOrder)
            })
        }
        arr
    }

    /**
     * Replace [instanceId]'s custom rules with the rows from a backup,
     * preserving each rule's id and sort_order. Returns the number of rules
     * restored (0 when [rows] is empty or malformed, or when persistence
     * failed — failures are logged, not thrown; [fix/dv22-a1 §1042-A-01]).
     * A backup section that carries no rules for this instance is a no-op —
     * restore must never wipe rules just because an older backup predates
     * the section.
     */
    fun restoreThinkingRules(instanceId: String, rows: org.json.JSONArray): Int = runBlocking {
        val entities = ArrayList<ProviderThinkingRuleEntity>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty()) continue
            val label = o.optString("label", "")
            if (label.isEmpty()) continue
            val scopeKind = o.optString("scopeKind", "allModels")
            entities.add(
                ProviderThinkingRuleEntity(
                    id = id,
                    providerInstanceId = instanceId,
                    label = label,
                    scopeKind = scopeKind,
                    scopePattern = o.optString("scopePattern", "").ifEmpty { null },
                    wireFormatJson = o.optString("wireFormatJson", "").ifEmpty { null },
                    reasoningEchoJson = o.optString("reasoningEchoJson", "").ifEmpty { null },
                    sortOrder = o.optInt("sortOrder", i),
                )
            )
        }
        if (entities.isEmpty()) return@runBlocking 0
        // [fix/dv22-a1 §1042-A-01] Same bare-DAO hardening as the nine
        // sibling functions above ([audit-0909 T4-H1]): a provider.db that
        // failed to open threw here straight into the backup importer.
        // Failure is logged and reported as 0 rules restored — the truth:
        // no rows landed. The republish below only runs on success, so the
        // resolver cache keeps matching what is actually on disk (on
        // failure the DB still holds the pre-restore rows).
        val persisted = runCatching {
            providerDao.replaceThinkingRules(instanceId, entities)
        }.onFailure { e ->
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] restoreThinkingRules persistence failed " +
                    "($instanceId, ${entities.size} rules) — reporting 0 restored: ${e.message}",
                e,
            )
        }.isSuccess
        if (!persisted) return@runBlocking 0
        republishThinkingCache(instanceId)
        entities.size
    }

    /**
     * Built-in rules relevant to THIS instance, for the Provider-detail UI. Mirrors iOS
     * builtInRulesForDisplay: resolve the vendor context from the instance's base URL,
     * then keep every AllModels-scoped rule (endpoint/provider-type defaults) plus any
     * ModelPattern rule the provider actually serves a matching model for. An empty
     * catalog keeps everything (list must not be mysteriously empty before first fetch).
     */
    fun builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
        ensureConfigLoaded()
        val config = _config.value
        val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
        val base = (inst.effectiveBaseURL ?: "").lowercase()
        val ctx = com.rikkaminis.app.provider.thinking.ThinkingResolveContext(
            modelId = "",
            supportsReasoning = null,
            declaredEffortValues = null,
            level = ThinkingLevel.OFF,
            maxTokens = 0,
            isOpenRouter = base.contains("openrouter.ai"),
            usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
            isMistral = base.contains("mistral.ai"),
            isDashScope = base.contains("dashscope"),
            isXAI = base.contains("api.x.ai"),
            isOfficialDeepSeek = base.contains("api.deepseek.com"),
            offEffort = null,
        )
        val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
        return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
            when (rule.scope) {
                is ThinkingRule.Scope.AllModels -> true
                is ThinkingRule.Scope.ModelPattern ->
                    modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
            }
        }
    }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries.filter { it.providerInstanceId == instanceId }

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries.filter { it.providerInstanceId == instanceId && !it.isHidden }

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries.filter { it.providerInstanceId in enabledIds && !it.isHidden }
        }

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_USED_ENTRY) else putString(KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /**
     * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
     * entry — or null when the recorded id was deleted / hidden / its provider
     * disabled. Callers treat null as "fall through to the next tier".
     */
    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id }
    }

    /**
     * [deep-audit-1044 A-01] A [ModelEntry]'s model **as the registry knows it**
     * — the only legitimate source for a capability / modality decision in this
     * file.
     *
     * [ModelEntry.model] is nothing but `baseModel.withOverrides(overrides)`: it
     * never consults models.dev. All 39 built-in models declare null modality
     * lists by construction (see [LLMModel.isTextOutput]'s KDoc, and the
     * "absent modality list means text in / text out" convention), so the raw
     * property answered "no audio / yes text" for models the registry declares
     * multimodal. Consequences on the voice side were user-visible: a
     * multimodal model with audio input was dropped from the Voice Input
     * candidates → recording failed with "No provider voice-input model
     * configured"; the same read in [hasVoiceModels] painted every Voice
     * Services row as "0 models"; and [newestProviderNewestTextEntry] offered a
     * pure image generator as the new-chat default.
     *
     * [ModelsDevApi.effectiveModel] is the repo-wide single entry point (the
     * `minis-model-use` surface, the config collection, the Debug RPCs,
     * `ThinkingLevelCatalog` and `ModelEntryPicker` all ride on it): it enriches
     * [ModelEntry.baseModel] FIRST and applies the user's overrides SECOND, so
     * an explicit `overrides.inputModalities` / `outputModalities` still wins
     * over the registry row. Do **not** re-derive it as `enrichModel(entry.model)`
     * — that order hands the registry value back over the user's choice.
     *
     * Read-only: the enriched model must never be written back into
     * `baseModelJson` / `overridesJson` (see [toSnapshot]).
     */
    private fun effectiveEntryModel(entry: ModelEntry): LLMModel = ModelsDevApi.effectiveModel(entry)

    /**
     * [T-newchat-default-model-fallback-android] Final-tier default for a new
     * chat: the newest text-output model from the newest-added enabled
     * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
     * model" = last text-capable entry in add order (modelEntries is appended
     * in add order, so the instance's last matching entry is the most recently
     * added). Image/audio-only models are excluded — a fresh chat must default
     * to something that can produce a text reply.
     *
     * Walks providers newest→oldest so that if the newest provider somehow has
     * no text model (all image/audio), we still land on the newest text model
     * from the next provider rather than returning null.
     */
    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = _config.value
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && !it.isHidden && effectiveEntryModel(it).isTextOutput }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    /**
     * [T-disabled-provider-via-group-android] Members of [group] whose
     * provider instance is currently enabled. Used everywhere we previously
     * read `group.memberEntryIds` directly to resolve / display a model —
     * runtime selection must skip disabled providers, and the settings UI
     * uses this to surface "no available models" when every member sits
     * behind a disabled provider.
     *
     * Returns entries in the same order as [ModelGroup.memberEntryIds] so
     * the routing-strategy semantics (primary = first member) carry over.
     * Entries whose [ModelEntry.providerInstanceId] no longer maps to any
     * instance are also filtered — orphaned ids would otherwise pin the
     * group to a model that can't be instantiated.
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            // [deep-audit-1043 B-06] DELIBERATE: System voice entries are
            // resolved elsewhere and must NOT appear in this list.
            //
            // This is the CHAT selection list — both callers
            // (`ChatSessionLifecycle.resolveGroupModel`, `ChatModelRouting`
            // load-balance rotation) immediately require
            // `instance(entry.providerInstanceId)` + `loadApiKey(...)`, and a
            // System entry's instance is synthesized, never persisted, so
            // `instance()` returns null. Admitting one would make
            // `GroupRouter.select` hand back an id that the very next line
            // rejects — a mixed Voice group (System ASR + a cloud STT entry)
            // would then fail to bind at all, where today it binds the cloud
            // member. Display surfaces want the opposite answer and use
            // `SystemVoiceEntries.isEnabledGroupMember` instead.
            //
            // The exclusion used to be INCIDENTAL (System ids simply aren't in
            // `modelEntries`). Stated here because the obvious "fix" — route
            // this through `resolveIn` like the display readers — is a
            // regression, and the next reader should not have to rediscover
            // that from the call sites.
            if (SystemVoiceEntries.isSystemEntryId(entryId)) return@mapNotNull null
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null && entry.providerInstanceId in enabledIds) entry else null
        }
    }

    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    /**
     * [T-android-regenerate-title-submodel] The dedicated title-generation
     * sub-model entry: the first enabled member of the configured
     * `defaultSubGroupId` group. Returns null when no sub-group is configured or
     * every member sits behind a disabled provider (caller then falls back to
     * the primary model). Single source of truth for both the auto-title path
     * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
     * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
     */
    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        return firstEnabledMemberEntry(group)
    }

    /**
     * [T-disabled-provider-via-group-android] True when [entryId] resolves
     * to an entry whose provider instance is currently enabled. Used by the
     * settings UI to dim members that won't be reachable at runtime.
     */
    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    fun replaceEntries(instanceId: String, models: List<LLMModel>) = synchronized(configLock) {
        ensureConfigLoaded()
        // Hot path for concurrent autoRefreshModels coroutines (one per
        // enabled instance) — without this lock, two replaceEntries() calls
        // race on the shared config.modelEntries ArrayList.
        // Never mutate `_config.value` itself. Parallel model refreshes complete
        // on IO while Compose compares the last published ProviderConfig on
        // Main; changing its ArrayLists in place is the exact CME in the crash
        // report above. All edits below happen on this detached snapshot.
        val config = mutationSnapshot(_config.value)
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
        val refreshedModelIds = models.map { it.id }.toSet()
        // [T-android-provider-voice] Template-sourced voice models carry an
        // authoritative modality that API-inferred modality (typically no audio
        // bits) must never overwrite. Mirrors iOS replaceEntries
        // templateModalityById guard (d55cd821).
        val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        val newEntries = models.map { model ->
            val prior = existingByModelId[model.id]
            // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
            // the API returned no modality info; the template's shape wins last.
            var resolved = model.withInferredVoiceModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = resolved,
                overrides = prior?.overrides ?: ModelOverrides(),
                // [T-provider-custom-identity] A refresh must never strip a
                // manually-added model's custom identity: the long-press delete
                // gesture on the provider detail screen is gated on
                // entry.isCustom, so wiping it here silently removes the user's
                // only delete path (model-delete-bug: custom entry with same id
                // returned by /v1/models became undeletable after manual
                // Refresh). Carrying prior.isCustom forward keeps the delete
                // gesture reachable forever; autoRefreshModels already skips
                // instances holding custom entries, so this only matters for
                // explicit refreshModels() calls.
                isCustom = prior?.isCustom ?: false,
                // [T-provider-default-hidden] Newly refreshed models come in hidden by
                // default — mirrors rikkahub "pull everything, show the chosen
                // few". The user unpicks them on the Manage All Models sheet;
                // the provider detail page starts empty for new providers, which
                // is the intended low-noise first-run. Existing entries carry
                // their prior isHidden forward so a refresh never resets what
                // the user already toggled.
                isHidden = prior?.isHidden ?: true,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
                // [F-222] costTier is user-authored metadata (set from the
                // group member sheet) and MUST survive a model refresh — it is
                // the sole sort key for RoutingStrategy.cheapestFirst
                // (GroupRouter: `costTier ?: Int.MAX_VALUE`). Dropping it here
                // demoted every entry to "most expensive" after one Refresh,
                // silently inverting cheapest-first routing. Same family as
                // the four-way sync (model/entity/toSnapshot/toProviderConfig)
                // — this hand-written constructor is the 5th landing point.
                costTier = prior?.costTier,
            )
        }

        // Keep custom entries that weren't in the refreshed list
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

        // [T-android-provider-voice] Preserve voice-template SEED entries the
        // /models list didn't return. Vendors like MiMo never expose their
        // ASR/TTS voices via /v1/models, so without this every refresh wipes
        // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
        // Only genuine template members are protected — never stray audio
        // entries. Mirrors iOS replaceEntries preservedVoice.
        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }

        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)
        if (preservedVoice.isNotEmpty()) {
            config.modelEntries.addAll(preservedVoice)
            android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
        }

        // Prune stale group member references
        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                // T171: same cascade for agent-loop direct-entry pins —
                // mirrors iOS ProviderConfigStore.replaceEntries (L716).
                // Skipped under the suspicious-shrink branch alongside the
                // group-member preservation, so a transient API hiccup
                // never silently nukes the user's curated agent-loop set.
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
                // [da1024-C] Same cascade for the references that live OUTSIDE
                // ProviderConfig, which saveConfig's idMap cannot translate:
                // the compact pin and the per-device lastUsed / voice refs.
                // Inside the non-suspicious-shrink branch, like the two above.
                if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), prunedEntryIds)) {
                    AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
                }
                clearStaleSecondaryRefs(prunedEntryIds)
            }
        }

        val tReplaceStart = System.nanoTime()
        saveConfig(config)
        val tReplaceMs = (System.nanoTime() - tReplaceStart) / 1_000_000
        if (models.size >= 20) {
            android.util.Log.i(
                "ProviderPerf",
                "replaceEntries: instance=$instanceId " +
                    "refreshed=${models.size} before=${existing.size} " +
                    "custom-preserved=${remainingCustom.size} " +
                    "saveConfig=${tReplaceMs}ms",
            )
        }
        // Stamp so staleness checks know this instance just refreshed.
        markInstanceFetched(instanceId)
    }

    // --- Model Entry management ---

    fun addEntry(entry: ModelEntry): String = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        // [da1023-C] Composite-key guard, unified for custom AND API-reported
        // entries: two rows with the same (providerInstanceId, baseModel.id)
        // canonicalize to the same id (ProviderConfigMapping writes
        // idMap[entry.uuid] = compositeEntryKey(...) and loads uuid = row.id),
        // so LazyColumn `key = entry.id` crashes with "Key was already used"
        // and one of the pair silently vanishes on reload. A duplicate is
        // never written (fail-closed at the data layer); the agent-facing
        // surfaces (ModelsCollection.add, provider.models.add) pre-check and
        // translate the skip into their typed errors.
        val decision = decideEntryAdd(
            providerInstanceId = entry.providerInstanceId,
            modelId = entry.baseModel.id,
            existing = config.modelEntries.map {
                it.id to compositeEntryKey(it.providerInstanceId, it.baseModel.id)
            },
        )
        if (!decision.allowed) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] addEntry skipped duplicate: " +
                    "${entry.providerInstanceId}/${entry.baseModel.id} already exists as ${decision.persistedId}",
            )
            return@synchronized decision.persistedId
        }
        config.modelEntries.add(entry)
        saveConfig(config)
        // Re-query the persisted list for the canonical id (mirrors
        // mergeImportInstanceJSON): the uuid minted by ModelEntry's default is
        // rewritten by saveConfig's canonicalize pass, and the re-query
        // survives a future drift in that pass where a computed composite
        // would not.
        _config.value.modelEntries.firstOrNull {
            it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
        }?.id ?: compositeEntryKey(entry.providerInstanceId, entry.baseModel.id)
    }

    // [fix/dv21-a3 A-03] `stampUserModified` selects which meaning of "update"
    // this write carries, and the default (true) keeps every editor / agent
    // caller on the historical behavior:
    //   - true  = a user edit landed → this entry IS newly modified, stamp now.
    //   - false = a restore wrote the backup's values onto the entry → the
    //             backup's own userModifiedAt must survive (see
    //             [mergeImportedEntryOntoLocal]'s restore-is-restore contract).
    // Stamping a restore with "now" quietly broke two things: the merge's no-op
    // short-circuit (`restored == local` can never hold once the local carries
    // t_import while the backup carries null/t_backup, so every re-import
    // rewrote every hit), and [shadowVoiceProviders]' arbitration (restoring
    // instance A pushed its mostRecentModified past instance B's genuine edit,
    // stealing the voice-provider slot).
    fun updateEntry(entry: ModelEntry, stampUserModified: Boolean = true): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            val existing = config.modelEntries[idx]
            // [da1022-E] Id-rename integrity (decideEntryRename, extracted to
            // ProviderRepositoryUtils for JVM testing): a custom model id edit
            // changes the composite entry key. Blank ids and renames that
            // collide with another entry of the same instance are rejected
            // fail-closed (a collision would make upsertEntries REPLACE the
            // existing row and silently drop the duplicate on next load).
            val decision = decideEntryRename(
                existingUuid = existing.id,
                updatedInstanceId = entry.providerInstanceId,
                updatedModelId = entry.baseModel.id,
                others = config.modelEntries
                    .filter { it.id != existing.id }
                    .map { it.providerInstanceId to it.baseModel.id },
            )
            if (!decision.allowed) {
                android.util.Log.w(
                    "ProviderRepo",
                    "[ProviderStore] updateEntry rejected: ${decision.rejectionReason}",
                )
                return@synchronized null
            }
            val renamed = decision.newKey != null
            val newUuid = decision.newKey ?: existing.id
            config.modelEntries[idx] = entry.copy(
                uuid = newUuid,
                userModifiedAt = if (stampUserModified) System.currentTimeMillis() else entry.userModifiedAt,
            )
            if (renamed) {
                val oldKey = decision.oldKey
                // Canonicalize this entry's uuid to the new composite key and
                // sync the in-config references that hang off the old key.
                // (saveConfig's idMap translation would heal the persisted
                // rows either way, but only canonicalizing here keeps
                // memory, DB and prefs consistent within the same session —
                // lastUsedVisibleEntry() compares by string equality, so a
                // pref rewritten ahead of the in-memory uuid would dangle
                // until the next cold start.)
                config.modelGroups.forEach { group ->
                    group.memberEntryIds.replaceAll { id -> if (id == oldKey) newUuid else id }
                }
                config.agentLoopModelEntryIds.replaceAll { id -> if (id == oldKey) newUuid else id }
                // KEY_LAST_USED_ENTRY (prefs) holds the entry id; the
                // legacy-uuid rewrite at load only covers the upgrade
                // first-launch, not in-place renames. Rewrite here so the
                // next new chat still falls back to "the model I was just
                // using" instead of the newest-provider fallback.
                if (lastUsedEntryId == oldKey) lastUsedEntryId = newUuid
                // [da1031-A] Voice overrides (prefs) are the same class of
                // per-device secondary reference as lastUsedEntryId above —
                // rename migrates them too.
                if (voiceInputOverrideEntryId == oldKey) voiceInputOverrideEntryId = newUuid
                if (voiceOutputOverrideEntryId == oldKey) voiceOutputOverrideEntryId = newUuid
                // [da1023-C] KEY_COMPACT_MODEL_ENTRY_ID (AgentRuntimeLimitsPrefs)
                // is a prefs-level secondary reference to this entry — same
                // class as the lastUsedEntryId rewrite above, but it lives
                // outside ProviderConfig, so saveConfig's idMap translation
                // cannot heal it. Without the rewrite the pin dangles and
                // CompactOrchestration silently follows the session chain.
                decideCompactPinMigration(
                    pinEntryId = AgentRuntimeLimitsPrefs.compactModelEntryId(),
                    oldKey = oldKey ?: existing.id,
                    newKey = newUuid,
                )?.let { migrated ->
                    AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = migrated)
                    android.util.Log.i(
                        "ProviderRepo",
                        "[ProviderStore] compactModelEntryId migrated: $oldKey -> $migrated",
                    )
                }
            }
            saveConfig(config)
            return@synchronized newUuid
        }
        null
    }

    fun removeEntry(entryId: String) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        // T171: cascade-clean the agent-loop direct-entry pin so the
        // AgentLoopModelsScreen never surfaces a checkmark on a model that
        // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
        // (Providers/ProviderConfigStore.swift L268).
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        // [da1023-C] Same compact-pin cascade as the agent-loop cleanup above:
        // the pin is prefs-held (AgentRuntimeLimitsPrefs) and saveConfig's
        // idMap translation cannot see it — a dangling pin makes
        // CompactOrchestration silently follow the session chain. Reset to ""
        // (explicit follow-session) when the pinned entry goes away.
        if (decideCompactPinCleanup(AgentRuntimeLimitsPrefs.compactModelEntryId(), setOf(entryId))) {
            AgentRuntimeLimitsPrefs.save(context, compactModelEntryId = "")
        }
        // [da1024-C] Twin cascade for the per-device references: a deleted
        // entry id can still be the last-used / voice override value, and only
        // this path can clear it (the readers fall back silently).
        clearStaleSecondaryRefs(setOf(entryId))
        saveConfig(config)
    }

    // --- Model Group management ---

    fun addGroup(group: ModelGroup) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        // Detach the caller's group: `memberEntryIds` is a MutableList the
        // caller still holds a reference to.
        config.modelGroups.add(group.copy(memberEntryIds = group.memberEntryIds.toMutableList()))
        saveConfig(config)
    }

    fun updateGroup(group: ModelGroup) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            config.modelGroups[idx] = group.copy(memberEntryIds = group.memberEntryIds.toMutableList())
            saveConfig(config)
        }
    }

    fun removeGroup(groupId: String) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.modelGroups.removeAll { it.id == groupId }
        if (config.defaultPrimaryGroupId == groupId) {
            config.defaultPrimaryGroupId = null
        }
        if (config.defaultSubGroupId == groupId) {
            config.defaultSubGroupId = null
        }
        if (config.voiceInputGroupId == groupId) {
            config.voiceInputGroupId = null
        }
        if (config.voiceOutputGroupId == groupId) {
            config.voiceOutputGroupId = null
        }
        config.agentLoopGroupIds.removeAll { it == groupId }
        saveConfig(config)
    }

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.agentLoopModelEntryIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
        // first-seen order). addAgentLoopEntry guards against dups, but any
        // path writing straight through this sink (cross-device sync merge,
        // reorder write-back, data migration) could otherwise persist a
        // duplicate id — which surfaces downstream as two pinnedEntries with
        // the same id, a duplicate LazyColumn key, and a crash on scroll.
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        saveConfig(config)
    }

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.agentLoopGroupIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (see above) so
        // no write path can persist duplicate group ids → duplicate LazyColumn
        // key on the agent-loop groups list.
        config.agentLoopGroupIds.addAll(ids.distinct())
        saveConfig(config)
    }

    // T182: thin add/remove helpers used by AgentLoopModelsSection +
    // AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
    // set* functions so caller doesn't have to round-trip through the
    // existing list (mutating in place would race with a parallel
    // saveConfig). Keep insertion order — preserves the order the user
    // added items in the picker, mirrors iOS appendIfNeeded behaviour.

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) = synchronized(configLock) {
        // [fix/dv23-a1 A-02] Every read-modify-write mutator must call
        // ensureConfigLoaded() BEFORE reading _config.value. During the cold
        // start async load window _config holds an empty placeholder
        // ProviderConfig(), so reading it here would persist the empty list
        // over the user's real pins — silent config loss. The four sibling
        // add/remove helpers and setAgentLoop*Ids already carry this guard;
        // these four did not (31 config mutators, 6 missing).
        ensureConfigLoaded()
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return@synchronized
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) = synchronized(configLock) {
        // [fix/dv23-a1 A-02] guard before reading _config.value — see above.
        ensureConfigLoaded()
        val cur = _config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return@synchronized
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) = synchronized(configLock) {
        // [fix/dv23-a1 A-02] guard before reading _config.value — see above.
        ensureConfigLoaded()
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return@synchronized
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) = synchronized(configLock) {
        // [fix/dv23-a1 A-02] guard before reading _config.value — see above.
        ensureConfigLoaded()
        val cur = _config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return@synchronized
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    // T186: reorder helpers — UI keeps a local mutable copy during
    // drag and pushes the final order through here on drop. Validates
    // that [newOrder] is a permutation of the current list to defend
    // against a stale dragged-from-different-snapshot reorder slipping
    // in mid-write (e.g. a cascade-cleanup raced with the drag).
    //
    // [reorder-unify] Validation now goes through [permuteById] — the same
    // guard the Model Groups reorder uses. That's stricter than the previous
    // `toSet() != cur` check, which compared sets only: a list holding a
    // duplicate id (possible in configs persisted before the agent-loop
    // dedup fix) has a set equal to a shorter deduped newOrder, so the old
    // check would accept an order that silently dropped an element. Length
    // and duplicate-collapse are now both rejected.
    // The `.toList()` copies are deliberate: [permuteById] walks `current`
    // more than once (map + associateBy), and these are the live
    // MutableLists inside the published config. Reading them directly while
    // a background writer publishes a new config is the exact
    // ConcurrentModificationException class that mutationSnapshot() and
    // ProviderConfigSnapshotTest exist to prevent.
    // [fix/dv23-a1 A-02] Both reorders now read the list INSIDE configLock and
    // call ensureConfigLoaded() first. Before, they read _config.value outside
    // the lock and without the load guard: during the cold-start window the
    // placeholder config was read and the reorder then persisted it — wiping
    // the user's pins. The lock also closes the read/write asymmetry against
    // the async loader's emit (ensureConfigLoaded is itself synchronized on
    // configLock, so re-entering it on the same monitor is safe).
    fun reorderAgentLoopEntries(newOrder: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val cur = _config.value.agentLoopModelEntryIds.toList()
        val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized
        setAgentLoopEntryIds(reordered)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val cur = _config.value.agentLoopGroupIds.toList()
        val reordered = permuteById(cur, newOrder) { it } ?: return@synchronized
        setAgentLoopGroupIds(reordered)
    }

    /**
     * [reorder-unify] Persist a user-arranged order for the Model Groups list.
     *
     * Unlike [reorderAgentLoopGroups], this order is presentation-only: which
     * group is the default primary/sub is decided by the explicit
     * [defaultPrimaryGroupId] / [defaultSubGroupId] ids, and routing inside a
     * group is decided by its own strategy. Nothing reads `modelGroups`
     * positionally to make a decision — so reordering here only changes what
     * the user sees, never behaviour. It's still worth persisting: the list is
     * hand-curated and grows, and users want their common groups on top.
     *
     * [newOrder] is a list of group ids. As with the agent-loop reorders, it
     * must be a permutation of the currently persisted set; anything else is
     * a stale snapshot (e.g. a cascade cleanup removed a group mid-drag) and
     * is dropped rather than applied, so a race can't silently delete or
     * duplicate a group. Reordering is done by lookup (not by index) so the
     * ModelGroup objects themselves are carried over untouched.
     */
    fun reorderGroups(newOrder: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        val reordered = permuteById(config.modelGroups, newOrder) { it.id }
            ?: return@synchronized
        config.modelGroups.clear()
        config.modelGroups.addAll(reordered)
        saveConfig(config)
    }

    /**
     * [reorder-providers] Persist a user-arranged order for the AI provider
     * list (drag-to-reorder on ProviderListScreen).
     *
     * Presentation-only, like [reorderGroups]: nothing reads `instances`
     * positionally to make a routing decision — the primary/sub defaults live
     * on model groups and the list UI buckets instances by providerType.
     * Persisting the drag order keeps the user's arrangement across restarts
     * and backup round-trips for free: [ProviderConfig.toSnapshot] writes
     * `sortOrder = list index`, so the order survives the DB snapshot /
     * legacy-JSON mirror / restore paths unchanged. No schema change needed.
     *
     * [newOrder] must be a permutation of the currently persisted set; anything
     * else is a stale snapshot (e.g. a cascade cleanup removed an instance
     * mid-drag) and is dropped rather than applied, so a race can't silently
     * delete or duplicate an instance. Reordering is done by lookup (not by
     * index) so the ProviderInstance objects are carried over untouched.
     */
    fun reorderInstances(newOrder: List<String>) = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        val reordered = permuteById(config.instances, newOrder) { it.id }
            ?: return@synchronized
        config.instances.clear()
        config.instances.addAll(reordered)
        saveConfig(config)
    }

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Expands groups to their members, unions with individual entries, dedupes by ID,
     * and filters to entries of enabled provider instances. Mirrors iOS
     * ProviderConfigStore.resolvedAgentLoopEntries.
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId !in enabledIds) return
            if (!seen.add(entry.id)) return
            out.add(entry)
        }

        // Individual entries first (preserves the order the user arranged in UI)
        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        // Then group members, in group order
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }

    fun group(id: String): ModelGroup? =
        _config.value.modelGroups.find { it.id == id }

    var defaultPrimaryGroupId: String?
        get() = _config.value.defaultPrimaryGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = mutationSnapshot(_config.value)
            config.defaultPrimaryGroupId = value
            saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = _config.value.defaultSubGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = mutationSnapshot(_config.value)
            config.defaultSubGroupId = value
            saveConfig(config)
        }

    // --- Voice groups [T-android-provider-voice] ---

    var voiceInputGroupId: String?
        get() = _config.value.voiceInputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = mutationSnapshot(_config.value)
            config.voiceInputGroupId = value
            saveConfig(config)
        }

    var voiceOutputGroupId: String?
        get() = _config.value.voiceOutputGroupId
        set(value) = synchronized(configLock) {
            ensureConfigLoaded()
            val config = mutationSnapshot(_config.value)
            config.voiceOutputGroupId = value
            saveConfig(config)
        }

    /**
     * Ensure a default Voice INPUT group exists and is bound when the user
     * hasn't configured one. Seeds "Voice Input" with the System ASR sentinel
     * entries (device SpeechRecognizer online/offline). Sentinel composite ids
     * match iOS so a config moved cross-platform keeps its selection. No-op
     * when a valid binding already exists. Mirrors iOS
     * ProviderConfigStore.ensureDefaultVoiceInputGroup.
     */
    fun ensureDefaultVoiceInputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.voiceInputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return@synchronized gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Input",
            memberEntryIds = mutableListOf(
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            ),
        )
        config.modelGroups.add(group)
        config.voiceInputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Input group ${group.id.take(8)} [System ASR online+offline]")
        return@synchronized group.id
    }

    /**
     * Ensure a default Voice OUTPUT group exists and is bound. Seeds
     * "Voice Output" with the System TTS auto sentinel (device TextToSpeech,
     * best voice per reply language). Mirrors iOS ensureDefaultVoiceOutputGroup.
     */
    fun ensureDefaultVoiceOutputGroup(): String? = synchronized(configLock) {
        ensureConfigLoaded()
        val config = mutationSnapshot(_config.value)
        config.voiceOutputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return@synchronized gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Output",
            memberEntryIds = mutableListOf("$sentinel/${SystemVoiceIds.SYSTEM_TTS}"),
        )
        config.modelGroups.add(group)
        config.voiceOutputGroupId = group.id
        saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Output group ${group.id.take(8)} [System Voice (Auto)]")
        return@synchronized group.id
    }

    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector (mirrors iOS VoiceSelectionStore.inputEntryId).
     * Values: a System sentinel composite id ("<sentinel>/system-asr-online" /
     * "-offline"), a provider entry composite id, or null = follow the Voice
     * Input group binding. Per-device (prefs), not part of the synced config.
     */
    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = config.revision + 1)
            }
        }

    /**
     * The resolved ACTIVE voice-input choice for the panel: either the on-device
     * System engine (with an online/offline preference) or a provider entry.
     * Resolution order mirrors iOS VoiceProviderResolver: explicit override
     * first, then the Voice Input group's members in fallback order (a System
     * sentinel member selects the System engine), then the System default.
     */
    data class VoiceInputChoice(
        /** null → provider-backed; non-null → System engine (true = prefer offline). */
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    fun resolveVoiceInputChoice(): VoiceInputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioInput) return null
            return inst to entry
        }

        voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            providerEntry(override)?.let { return VoiceInputChoice(null, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                providerEntry(memberId)?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }

    /** Bound Voice Input group's display name, or null (chip's "Group · Model"). */
    fun voiceInputGroupName(): String? {
        val gid = _config.value.voiceInputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * [T-android-provider-voice] Resolve the current provider-backed voice
     * INPUT selection: honours the explicit override first, then walks the
     * Voice Input group's members in order (fallback semantics). System
     * sentinel members are skipped — they are served by
     * SystemSpeechRecognitionEngine, not a cloud provider.
     */
    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceInputChoice().entry

    /**
     * [T-voice-asr-group-failover] Ordered ASR fail-over candidates: the
     * explicit override first (if usable and provider-backed), then every
     * usable member of the Voice Input group, ordered per the group's routing
     * strategy — `fallback` keeps group order, `loadBalance` rotates the start
     * position by [loadBalanceSeed] so separate capture sessions spread across
     * members (mirrors ModelGroupRouter's per-session rotation for text chat,
     * and iOS VoiceProviderResolver.resolvedInputCandidates). An explicit
     * System override returns [] — the caller uses the System engine directly.
     * System sentinel group members are skipped: they never serve cloud ASR.
     */
    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioInput) return null
            return inst to entry
        }

        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        voiceInputOverrideEntryId?.let { override ->
            // Explicit System override → System engine, no cloud candidates.
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            providerEntry(override)?.let { out.add(it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            var members = group.memberEntryIds
                .filter { !it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { providerEntry(it) }
            if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
                val offset = kotlin.math.abs(loadBalanceSeed) % members.size
                members = members.drop(offset) + members.take(offset)
            }
            for (m in members) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }

    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---
    //
    // Mirror of the voice-INPUT resolution above. Before this existed,
    // `voiceOutputGroupId` was write-only: the user could bind a Voice Output
    // group in settings and it was persisted, but nothing at runtime ever read
    // it back, so read-aloud always fell through to the on-device engine and
    // every provider TTS voice was unreachable outside the Quick Test sheet.

    /**
     * Explicit voice-output voice override picked in a selector (mirrors iOS
     * VoiceSelectionStore.outputEntryId, and [voiceInputOverrideEntryId] on the
     * input side). Values: a System sentinel composite id
     * ("<sentinel>/system-tts"), a provider entry composite id, or null =
     * follow the Voice Output group binding. Per-device (prefs), not part of
     * the synced config.
     */
    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(configLock) {
                val config = _config.value
                _config.value = config.copy(revision = config.revision + 1)
            }
        }

    /**
     * The resolved ACTIVE voice-output choice for read-aloud: either the
     * on-device System TextToSpeech or a provider entry. Same shape and
     * resolution order as [VoiceInputChoice].
     */
    data class VoiceOutputChoice(
        /** null → provider-backed; true → System TextToSpeech. */
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    /**
     * Resolution order mirrors [resolveVoiceInputChoice] exactly: explicit
     * override first, then the Voice Output group's members in fallback order
     * (a System sentinel member selects the device engine), then the System
     * default as the terminal fallback.
     *
     * The capability gate is `hasAudioOutput` (vs `hasAudioInput` on the input
     * side) — a model that only *accepts* audio must never be picked to
     * *produce* it.
     */
    fun resolveVoiceOutputChoice(): VoiceOutputChoice {
        ensureConfigLoaded()
        val config = _config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !effectiveEntryModel(entry).hasAudioOutput) return null
            return inst to entry
        }

        voiceOutputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceOutputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                providerEntry(memberId)?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }

    /** Bound Voice Output group's display name, or null. */
    fun voiceOutputGroupName(): String? {
        val gid = _config.value.voiceOutputGroupId ?: return null
        return _config.value.modelGroups.find { it.id == gid }?.name
    }

    /**
     * Resolve the current provider-backed voice OUTPUT selection, or null when
     * read-aloud should use the on-device engine. Counterpart of
     * [resolveVoiceInputEntry].
     */
    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry

    // --- Shadow Voice Providers [T-android-provider-voice] ---
    // Port of iOS [T-mimo-shadow-voice]: a "Voice Service" is NOT a stored
    // entity — it's a runtime read-only MIRROR of any ordinary instance that
    // owns audio-modality model entries (dual-purpose vendors like MiMo serve
    // text + voice on one host). Voice ability is a per-MODEL concern; no
    // base-URL "voice-only" whitelist.

    /** True if [instanceId] has ANY model entry with an audio modality. */
    fun hasVoiceModels(instanceId: String): Boolean =
        _config.value.modelEntries.any {
            it.providerInstanceId == instanceId && effectiveEntryModel(it).hasVoiceModality
        }

    /**
     * Per-instance "hide from Voice Services" flag ("I only want the text
     * models"). Absent = shadow shown by default. Stored in prefs (mirrors iOS
     * UserDefaults voiceShadowDisabled.<id>), not the synced config.
     */
    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
        prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
        // Bump revision so Compose collectors re-read the shadow list. Read
        // `_config.value` INSIDE the lock — reading it outside could pick up a
        // value a concurrent writer is about to replace, publishing a stale
        // config with only its revision advanced.
        synchronized(configLock) {
            val config = _config.value
            _config.value = config.copy(revision = config.revision + 1)
        }
    }

    /**
     * A read-only mirror of an instance's voice capability, surfaced in Voice
     * Services. Shares the underlying instance's credential + endpoint.
     */
    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,   // audio-in entries (ASR)
        val outputModels: List<ModelEntry>,  // audio-out entries (TTS)
    )

    /**
     * All shadow voice providers: one per enabled instance that has audio
     * models and isn't shadow-disabled, FOLDED by normalized base URL so two
     * instances on one host show a single deterministic representative row.
     */
    fun shadowVoiceProviders(): List<ShadowVoiceProvider> {
        ensureConfigLoaded()
        val config = _config.value
        val candidates = config.instances.filter { inst ->
            inst.isEnabled && hasVoiceModels(inst.id) && !isVoiceShadowDisabled(inst.id) &&
                com.rikkaminis.app.provider.voice.VoiceProviderFactory.supports(inst, loadApiKey(inst.id))
        }
        val byKey = candidates.groupBy { inst ->
            normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
        }

        fun mostRecentModified(instanceId: String): Long =
            config.modelEntries
                .filter { it.providerInstanceId == instanceId }
                .mapNotNull { it.userModifiedAt }
                .maxOrNull() ?: Long.MIN_VALUE

        return byKey.values.map { insts ->
            // Representative selection — deterministic across devices: enabled
            // first, then most-recently-modified entries, then oldest createdAt,
            // then id.
            val rep = insts.sortedWith(
                compareByDescending<ProviderInstance> { it.isEnabled }
                    .thenByDescending { mostRecentModified(it.id) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            ).first()
            val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
            ShadowVoiceProvider(
                instanceId = rep.id,
                displayName = rep.label,
                inputModels = entries.filter { effectiveEntryModel(it).hasAudioInput },
                outputModels = entries.filter { effectiveEntryModel(it).hasAudioOutput },
            )
        }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
    }

    /**
     * True when ≥2 enabled instances share a normalized base URL AND have voice
     * models — the folded-duplicate case; UI shows a non-destructive hint.
     */
    fun hasFoldedShadowDuplicates(): Boolean {
        val seen = mutableSetOf<String>()
        for (inst in _config.value.instances) {
            if (!inst.isEnabled || !hasVoiceModels(inst.id)) continue
            val key = normalizedShadowKey(inst.customBaseURL)
            if (key.isEmpty()) continue
            if (!seen.add(key)) return true
        }
        return false
    }


    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
    ): ModelRefreshResult {
        val apiKey = loadApiKey(instance.id)

        android.util.Log.i("ProviderRepo", "refreshModels: id=${instance.id} type=${instance.providerType} credential=${instance.credentialType} hasKey=${apiKey != null} keyLen=${apiKey?.length ?: 0} baseURL=${instance.effectiveBaseURL} forceRefresh=$forceRefresh")

        // Step 1: Try provider API (requires API key)
        val customBase = instance.customBaseURL
        val isThirdParty = customBase != null
            && !customBase.lowercase().let {
                it.contains("api.openai.com") || it.contains("chatgpt.com") || it.contains("openrouter.ai")
            }

        if (apiKey != null) {
            val models = try {
                // [T8-1] Dispatch through the registry instead of importing
                // provider implementations directly (data→provider reverse
                // dependency). Registry returns empty list for types without
                // a registered fetcher.
                // [fix/da1020-models-cache] Forward our Context so the fetcher's
                // disk cache is reachable: every *ModelsApi short-circuits on
                // `context != null`, so dropping it here silently disabled the
                // 7-day cache read, the 401/403 invalidate and the save alike.
                ModelListProviderRegistry.fetchModels(instance, apiKey, isThirdParty, forceRefresh, context)
            } catch (e: Exception) {
                android.util.Log.e("ProviderRepo", "refreshModels fetch error: ${e.message}", e)
                emptyList()
            }
            android.util.Log.i("ProviderRepo", "refreshModels: got ${models.size} models")

            // Step 2: If API returned results, use them
            if (models.isNotEmpty()) {
                replaceEntries(instance.id, models)
                return ModelRefreshResult.SUCCESS_API
            }
        }

        // [model-list-source] The provider's live API is the ONLY source of
        // truth for the model list. No static-catalog fallback (models.dev
        // registry / bundled snapshot): a missing or invalid key must leave
        // the list empty (or untouched), never silently filled from a stale
        // directory. enrichModels() in the *ModelsApi layer still uses
        // models.dev purely to backfill metadata (context window, output,
        // reasoning, modalities) onto models the API already returned.
        if (apiKey == null) {
            android.util.Log.i("ProviderRepo", "refreshModels: no API key — nothing to load for ${instance.label}")
            return ModelRefreshResult.NO_KEY
        }
        if (isThirdParty) {
            android.util.Log.i("ProviderRepo", "Third-party endpoint, fetch failed — preserving existing models for ${instance.label}")
            return ModelRefreshResult.PRESERVED
        }
        return ModelRefreshResult.FAILURE
    }

    /**
     * Auto-refresh variant: skips instances where the user has added custom models,
     * so we never overwrite hand-edited entries. Mirrors iOS `autoRefreshModels(for:)`.
     */
    private suspend fun autoRefreshModels(instance: ProviderInstance) {
        val hasCustom = _config.value.modelEntries.any {
            it.providerInstanceId == instance.id && it.isCustom
        }
        if (hasCustom) {
            android.util.Log.i("ProviderRepo", "[ModelList] autoRefresh SKIP ${instance.label} — has custom models")
            return
        }
        refreshModels(instance)
    }

    /**
     * Stale-while-revalidate helper for UI code. Callers (e.g. the model
     * picker sheet) invoke this on open: the currently-persisted
     * `config.modelEntries` is returned immediately via the StateFlow
     * (no waiting), and if any instance's cache is older than
     * [MODEL_CACHE_TTL_MS] we kick a background refresh that updates the
     * StateFlow when the network fetch completes.
     *
     * Concurrent-call safe: per-instance `autoRefreshModels` is idempotent
     * and the global daily-refresh flag prevents cold-start double fetch.
     * This helper bypasses the daily flag because it runs per-instance — it's
     * meant for targeted "user is looking at this picker now" revalidation.
     */
    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) {
        val stale = _config.value.instances.filter { it.isEnabled && isInstanceStale(it.id) }
        if (stale.isEmpty()) return
        android.util.Log.i("ProviderRepo", "[ModelList] SWR refresh — ${stale.size} stale instance(s)")
        for (instance in stale) {
            scope.launch { autoRefreshModels(instance) }
        }
    }

    /**
     * Refresh model lists for all enabled instances, at most once per calendar day.
     * Mirrors iOS `refreshAllModelsIfNeeded()` — called from Application.onCreate.
     * Refreshes run in parallel; failures are logged but don't block other instances.
     */
    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) {
        val key = "lastModelsRefreshDate"
        val lastMs = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (lastMs > 0L && isSameCalendarDay(lastMs, now)) {
            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — already refreshed today")
            return
        }

        // [T-android-startup-config-stall] Config now loads asynchronously, so
        // at cold start `_config.value` may still be the empty placeholder when
        // this fires from MinisApp.onCreate. Wait for the load before reading
        // the enabled-instance set, otherwise the daily refresh would no-op on
        // "no enabled instances" and skip this launch entirely. Runs on the
        // caller's (IO) scope — does not touch the main thread.
        scope.launch {
            awaitConfigLoaded()
            val enabled = _config.value.instances.filter { it.isEnabled }
            if (enabled.isEmpty()) {
                android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded SKIP — no enabled instances")
                return@launch
            }

            android.util.Log.i("ProviderRepo", "[ModelList] refreshAllModelsIfNeeded FIRE — ${enabled.size} instances")
            prefs.edit().putLong(key, now).apply()

            for (instance in enabled) {
                scope.launch { autoRefreshModels(instance) }
            }
        }
    }

    // API Key management
    fun saveApiKey(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).commit()
    }

    fun loadApiKey(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").commit()
    }

    // -- Import / Export --

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? {
        ensureConfigLoaded()
        val instance = instance(instanceId) ?: return null
        // Export the FULL catalog for this instance (visible + hidden).
        // Previously assembled as `visibleEntries + filter(isHidden)` — two
        // disjoint slices that sum to "everything" but read as if they might
        // overlap or drop entries. Single filter is the same set, clearer.
        val entries = _config.value.modelEntries.filter {
            it.providerInstanceId == instanceId
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    // [T-fix-import-id-roundtrip] Export the reasoning-capability
                    // pair the Room/kotlinx path already carries. Additive +
                    // optional: readers without the keys ignore them, and the
                    // import below reads each independently with null defaults.
                    // Without these, a backup restore evaporates the effort-tier
                    // list (ThinkingLevelCatalog ceiling) and the
                    // declaresNoEffortTiers flag (xAI grok-build must NOT receive
                    // reasoning_effort) for gateway models absent from models.dev.
                    entry.baseModel.reasoningEffortValues?.takeIf { it.isNotEmpty() }?.let {
                        put("reasoningEffortValues", JSONArray(it))
                    }
                    entry.baseModel.declaresNoEffortTiers?.let { put("declaresNoEffortTiers", it) }
                    // [T-fix-backup-field-evap] Export the user-visible ModelEntry
                    // fields that the backup/restore round-trip must preserve.
                    // These four fields (costTier, userModifiedAt, maxThinkingLevel,
                    // createdAt) were missing from the hand-written org.json
                    // serializer — only the Room/kotlinx path carried them.
                    // Each key is additive + optional: older readers ignore
                    // unknown keys, and the import below reads each independently
                    // with null defaults for missing keys.
                    entry.costTier?.let { put("costTier", it) }
                    entry.userModifiedAt?.let { put("userModifiedAt", it) }
                    // [T-provider-export-model-overrides] Serialize the FULL
                    // ModelOverrides layer, not just displayName/maxOutputTokens.
                    // contextWindow / supportsReasoning / modality (input+output)
                    // were previously dropped — a hand-corrected proxied model
                    // lost those edits on round-trip. Each key is additive +
                    // optional: older builds ignore unknown keys, and import
                    // below reads each independently so a partial override
                    // object restores exactly the fields present.
                    //
                    // For cross-platform interop with iOS we ALSO write a
                    // `modalityOverride` bitfield (Int, matching
                    // ios/Providers/LLMTypes.swift ModelModality OptionSet):
                    // textInput=1, textOutput=2, imageInput=4, pdfInput=8,
                    // audioInput=16, videoInput=32, imageOutput=64,
                    // audioOutput=128, videoOutput=256. Android natively
                    // carries inputModalities/outputModalities as string lists;
                    // the bitfield is purely an interop wire-format that iOS
                    // can consume directly. On import, the native list fields
                    // win when present (Android↔Android lossless), bitfield is
                    // the iOS→Android fallback.
                    if (!entry.overrides.isEmpty) {
                        val o = JSONObject()
                        entry.overrides.displayName?.let { o.put("displayName", it) }
                        entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                        entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                        entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                        entry.overrides.inputModalities?.let {
                            o.put("inputModalities", JSONArray(it))
                        }
                        entry.overrides.outputModalities?.let {
                            o.put("outputModalities", JSONArray(it))
                        }
                        val bitfield = modalityBitfieldFromLists(
                            entry.overrides.inputModalities,
                            entry.overrides.outputModalities,
                        )
                        if (bitfield != 0) o.put("modalityOverride", bitfield)
                        // [T-fix-backup-field-evap] Export the user-set
                        // thinking ceiling. Additive + optional: old readers
                        // ignore the unknown key, and import reads it
                        // independently with null fallback.
                        entry.overrides.maxThinkingLevel?.let {
                            o.put("maxThinkingLevel", it.name)
                        }
                        put("overrides", o)
                    }
                    // Mirror iOS export of baseModel.modalityOverride — when
                    // the base model itself carries explicit modality info,
                    // serialize an interop bitfield so iOS can faithfully
                    // restore it. Android's native baseModel uses string
                    // lists too; this is purely additive for iOS readers.
                    run {
                        val bf = modalityBitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            // [T-fix-backup-field-evap] Preserve the instance creation time
            // across backup/restore. Previously importInstanceJSON always
            // minted a fresh ProviderInstance whose createdAt fell back to
            // System.currentTimeMillis(), silently resetting the timestamp.
            // Additive + optional: old readers ignore it, import reads it
            // with a now() fallback for old backups.
            put("createdAt", instance.createdAt)
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            // [T-provider-custom-user-agent] Additive, optional. Only written
            // when set; old/new readers without the key decode to null →
            // default UA. Field name matches iOS for cross-platform interop.
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
            // [RC5 / P0-pinned + GH#68] Additive provider run-config fields.
            // These were previously NEVER written by the backup export, so a
            // config restore / device migration silently reset them to
            // defaults (pinned=false, enabled=true, azure/image mode cleared).
            // Same root cause as the Room-layer P0-pinned & GH#68 fixes — the
            // JSON backup serializer drifted from the model. Each key is
            // additive + optional: older readers ignore unknown keys, and the
            // import below reads each independently with defaults for missing
            // keys so old backups still restore cleanly. Delegated to the pure
            // [writeRunConfigFields] seam so the round-trip is JVM-testable.
            writeRunConfigFields(this, instance)
        }
        return obj.toString(2)
    }

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? {
        ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        // Resolve label conflict
        val existingLabels = _config.value.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        // [T-provider-custom-user-agent] Additive: old exports lack the key →
        // empty → null → default UA. Field name matches iOS.
        // [RC5 / P0-pinned + GH#68] Restore the run-config fields written by
        // [exportInstanceJSON]. Each defaults to the model's own default when
        // the key is absent (old backup) so nothing crashes and a legacy
        // restore behaves exactly as before. Enum names parsed via
        // runCatching + fallback, mirroring ProviderConfigMapping — an unknown
        // enum name from a future build degrades to auto / no cache instead of
        // throwing away the whole import. [fix/dv16-a A-08] The decode lives
        // in the shared [readImportInstanceFields] seam so the merge path
        // (same-(type,label) restore) decodes identical fields.
        val f = readImportInstanceFields(dict)

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = f.customBaseURL,
            appendV1Suffix = f.appendV1Suffix,
            useResponsesAPI = f.useResponsesAPI,
            customUserAgent = f.customUserAgent,
            isEnabled = f.runConfig.isEnabled,
            azureMode = f.runConfig.azureMode,
            imageEndpointMode = f.runConfig.imageEndpointMode,
            imageEndpointResolved = f.runConfig.imageEndpointResolved,
            pinned = f.runConfig.pinned,
            // [T-fix-backup-field-evap] Restore the original creation time.
            // Old backups lack the key → fall back to now() so a legacy
            // restore behaves exactly as before. Positive guard: a malformed
            // non-positive value (0 / negative) would otherwise corrupt the
            // sort/display order, so clamp to now() in that case too.
            createdAt = dict.optLong("createdAt", 0L).takeIf { it > 0 }
                ?: System.currentTimeMillis(),
        )
        addInstance(instance)

        // Restore credentials (API key, manual OAuth bearer, structured OAuth
        // login, Gemini account metadata) from the payload onto the new
        // instance. Shared with the merge path (see [mergeImportInstanceJSON])
        // so a restore applies keys even when the provider already exists
        // under the same label.
        importInstanceCredentials(dict, instance)

        // Import models (replace built-in defaults)
        val importedEntries = parseImportedModelEntries(dict, instance.id)
        if (importedEntries.isNotEmpty()) {
            // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list)
            synchronized(configLock) {
                val cfg = mutationSnapshot(_config.value)
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(importedEntries)
                saveConfig(cfg)
            }
        }

        return resolvedLabel
    }

    /**
     * [T-backup-restore-credentials] Apply the credential fields of an
     * exported provider JSON onto [instance] (the instance id the credentials
     * must be stored under — a fresh append or an existing merge target).
     * Covers: apiKey (OAuth token fields were removed with the OAuth login
     * flow). Shared by [importInstanceJSON] and [mergeImportInstanceJSON]
     * (restores always apply the backup's keys onto the target instance).
     */
    private fun importInstanceCredentials(dict: JSONObject, instance: ProviderInstance) {
        // Decode API key (base64 or plain text)
        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue // plain text fallback
            }
            saveApiKey(instance.id, apiKey)
        }

    }

    /**
     * [T-backup-dedup] Parse the `models` array of an exported provider JSON
     * into [ModelEntry]s bound to [instanceId]. Shared by [importInstanceJSON]
     * (which replaces the instance's entries wholesale) and
     * [mergeImportInstanceJSON] (which upserts models missing from an existing
     * instance). Returns an empty list when the payload carries no models.
     */
    private fun parseImportedModelEntries(dict: JSONObject, instanceId: String): List<ModelEntry> {
        val models = dict.optJSONArray("models") ?: return emptyList()
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return emptyList() }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return emptyList() }
        val entries = mutableListOf<ModelEntry>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val modelId = m.optString("modelId", "")
            if (modelId.isEmpty()) continue
            val displayName = m.optString("displayName", modelId)
            val isCustom = m.optBoolean("isCustom", false)
            val isHidden = m.optBoolean("isHidden", false)
            val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
            val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
            val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
            val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
            // [T-fix-import-id-roundtrip] Restore the reasoning-capability pair
            // the export side now writes. Each key is read independently with a
            // null default when absent (old backup) — a legacy restore behaves
            // exactly as before. Empty lists / null / explicit-null degrade to
            // null ("unknown") instead of an empty list.
            val reasoningEffortValues = m.optJSONArray("reasoningEffortValues")?.let { arr ->
                (0 until arr.length())
                    .mapNotNull { idx -> arr.optString(idx, "").takeIf { it.isNotBlank() } }
                    .takeIf { it.isNotEmpty() }
            }
            val declaresNoEffortTiers = if (m.has("declaresNoEffortTiers") && !m.isNull("declaresNoEffortTiers")) {
                m.optBoolean("declaresNoEffortTiers")
            } else null
            // [T-fix-backup-field-evap] Restore the user-visible ModelEntry
            // metadata that the hand-written serializer previously dropped.
            // Each key is read independently with a null default when absent
            // (old backup) — a legacy restore behaves exactly as before.
            val costTier = m.optInt("costTier", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }
            val userModifiedAt = m.optLong("userModifiedAt", Long.MIN_VALUE)
                .takeIf { it != Long.MIN_VALUE }
            // [T-provider-export-model-overrides] Restore baseModel
            // modalities. Android-native list fields win when present;
            // otherwise fall back to iOS's `modalityOverride` bitfield so
            // a provider exported on iOS retains its capability info.
            val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
            val model = LLMModel(
                id = modelId,
                displayName = displayName,
                provider = providerType.displayName,
                contextWindow = contextWindow,
                maxOutputTokens = maxOutputTokens,
                supportsReasoning = supportsReasoning,
                interleavedReasoningField = interleavedReasoningField,
                inputModalities = baseIn,
                outputModalities = baseOut,
                reasoningEffortValues = reasoningEffortValues,
                declaresNoEffortTiers = declaresNoEffortTiers,
            )
            val overridesObj = m.optJSONObject("overrides")
            val overrides = if (overridesObj != null) {
                // [T-provider-export-model-overrides] Read the full
                // overrides layer. Each key is read independently — a
                // missing key (old export, partial object) simply stays
                // null → the field falls back to baseModel / defaults.
                val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                ModelOverrides(
                    displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                    maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                    contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                    supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                    inputModalities = ovIn,
                    outputModalities = ovOut,
                    // [T-fix-backup-field-evap] Restore the user-set thinking
                    // ceiling. Unknown enum name from a future build degrades
                    // to null (inherit) instead of throwing away the import —
                    // mirrors the Room path's coerceInputValues semantics.
                    maxThinkingLevel = overridesObj.optString("maxThinkingLevel", "")
                        .takeIf { it.isNotBlank() }
                        ?.let { raw -> runCatching { ThinkingLevel.valueOf(raw) }.getOrNull() },
                )
            } else {
                ModelOverrides()
            }
            entries.add(ModelEntry(
                providerInstanceId = instanceId,
                baseModel = model,
                overrides = overrides,
                isCustom = isCustom,
                isHidden = isHidden,
                // [T-fix-backup-field-evap] Restore metadata lost on the old
                // backup path: costTier (feeds RoutingStrategy.cheapestFirst —
                // losing it silently re-ranked every entry as "most expensive")
                // and userModifiedAt (user-edit timestamp).
                costTier = costTier,
                userModifiedAt = userModifiedAt,
            ))
        }
        return entries
    }

    /**
     * [T-backup-dedup] Restore a provider onto an install that already has it.
     *
     * The old backup import appended unconditionally: [importInstanceJSON]
     * renames on label conflict, so restoring onto a non-empty install
     * produced "OpenAI (2)" duplicates, and model groups referencing the
     * original entries were remapped onto the freshly minted copies. This
     * method instead *merges* into the existing instance when its
     * (providerType, label) matches the export:
     *   - the existing instance id is kept, so groups / defaults referencing
     *     the backup's entries remap onto the live instance;
     *   - models are upserted by baseModel.id: models the existing instance
     *     lacks are added; models it already has have the backup's values
     *     written onto them — a restore is a restore, all the way down
     *     [fix/dv20-a2 A-08]. The write goes through [updateEntry] (the same
     *     whole-entry face the UI editor uses) with the existing entry's id
     *     kept, so the entryIdMap and every reference hanging off it stays
     *     valid; unchanged entries skip the write (local values identical to
     *     the backup's are a no-op, and the historical "local wins" reading
     *     of this bullet was the same stale claim the credentials bullet
     *     below already corrected).
     *   - credentials (the backup's apiKey — the only credential field
     *     [importInstanceCredentials] writes) ARE written onto the existing
     *     instance — a restore is a restore. (An earlier revision of this doc
     *     claimed they were deliberately NOT touched; that was stale — the
     *     code has always written them. [fix/dv16-a A-08] The instance-config
     *     fields (customBaseURL / appendV1Suffix / useResponsesAPI /
     *     customUserAgent + the five run-config fields) are now written too,
     *     through the shared [applyImportInstanceFields] face — an earlier
     *     revision dropped them on this merge path.)
     *
     * @param srcEntryIds the backup-layer `_entryIds` annotation, positionally
     *   paired with the `models` array (same append order — visible and hidden
     *   interleaved — as [exportInstanceJSON] emits). Used only to build the
     *   returned id map.
     * @return the resolved (existing) instance id and a map of source entry id
     *   → entry id on this install (existing or newly added); null when the
     *   JSON is unparseable or no matching instance exists — the caller then
     *   falls back to the classic append path.
     */
    /**
     * [T-backup-dedup] Merge an exported provider JSON into the existing
     * instance with the same (type, label), returning its id and a source
     * entry id → restored entry id map. The backup's credentials (the
     * apiKey — the only credential field [importInstanceCredentials] writes)
     * are always written onto the existing instance —
     * a restore is a restore. [fix/dv16-a A-08] The instance-config fields the
     * append path decodes are now applied too, through the shared
     * [applyImportInstanceFields] face. [fix/dv20-a2 A-08] The model-level
     * fields are applied the same way, through the shared whole-entry
     * [updateEntry] face (identity pinned to the existing entry id).
     */
    fun mergeImportInstanceJSON(
        jsonStr: String,
        srcEntryIds: List<String>,
    ): Pair<String, Map<String, String>>? {
        ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val existing = _config.value.instances.firstOrNull {
            it.providerType == providerType && it.label == label
        } ?: return null

        // [T-backup-restore-credentials] A restore must bring the backup's
        // keys with it even when the provider already exists under the same
        // label.
        importInstanceCredentials(dict, existing)

        // [fix/dv16-a A-08] A restore is a restore: the instance-config fields
        // the append path decodes (customBaseURL / appendV1Suffix /
        // useResponsesAPI / customUserAgent + the five run-config fields) are
        // applied onto the existing instance too — through the same shared
        // write face ([readImportInstanceFields] / [applyImportInstanceFields]),
        // so a same-(type,label) restore no longer drops them. Same
        // restore-is-restore contract as the credentials above: the backup's
        // values win over the local ones (the user's revert path is to edit
        // the field after the restore, or to re-import after re-editing).
        val fields = readImportInstanceFields(dict)
        val updated = applyImportInstanceFields(existing, fields)
        if (updated != existing) updateInstance(updated)

        val entries = parseImportedModelEntries(dict, existing.id)
        val entryMap = HashMap<String, String>()
        for (i in entries.indices) {
            val srcId = srcEntryIds.getOrNull(i) ?: ""
            val entry = entries[i]
            val hit = _config.value.modelEntries.firstOrNull {
                it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
            }
            val resolvedEntryId = if (hit != null) {
                // [fix/dv20-a2 A-08] A restore is a restore — the same contract
                // the instance-config fields got in dv16-a A-08 (same merge
                // path, one level down): the backup's model-level values are
                // written onto the existing entry instead of being dropped
                // behind a successful-looking import. The field set and the
                // identity rule live in [mergeImportedEntryOntoLocal] (single
                // source, derived from ModelEntry itself — no whitelist here);
                // the write goes through [updateEntry], the same whole-entry
                // face the UI editor uses, so nothing can drift between the
                // restore path and the editor path.
                // The returned id is used as-is, not assumed == hit.id: if the
                // existing entry still carries a legacy uuid, updateEntry's
                // rename-integrity decision canonicalizes it to the composite
                // key and the id map must follow. A null return means the
                // write was rejected and the untouched existing id is still
                // the correct mapping target. A no-op merge (imported values
                // already identical) skips the write entirely.
                // [fix/dv21-a3 A-03] …and the write is a RESTORE, not an edit:
                // stampUserModified = false carries the backup's own
                // userModifiedAt instead of "now". The baseline shape stamped
                // it, which defeated the no-op short-circuit above on the
                // second import (the local entry carried t_import while the
                // backup carried null/t_backup, so `restored == local` could
                // never hold again) and let a restore of one instance jump
                // shadowVoiceProviders' mostRecentModified arbitration over a
                // genuinely edited sibling.
                val updated = mergeImportedEntryOntoLocal(hit, entry)
                if (updated == null) hit.id else (updateEntry(updated, stampUserModified = false) ?: hit.id)
            } else {
                addEntry(entry)
                // [T-fix-import-id-roundtrip] addEntry 落盘后 _config.value 已被
                // saveConfig 的 toSnapshot→toProviderConfig canonicalize 为复合键
                // 形状（"{instanceId}/{modelId}"）——裸返 entry.id 会把一次性随机
                // UUID 交给 entryIdMap，令模型组 / agentLoopEntries /
                // compactModelEntryId / 会话 binding 的重映射全部悬空。Requery 返回
                // 落盘后的真实 id；addEntry 的 exists 跳过谓词与上面的 hit 谓词
                // 逐字相同（hit==null ⇒ 不会被跳过），但 requery 仍比裸算
                // compositeEntryKey 更稳——若未来 skip 谓词漂移，它返回的是既有行
                // 的真实 id 而非新算值。
                _config.value.modelEntries.firstOrNull {
                    it.providerInstanceId == existing.id && it.baseModel.id == entry.baseModel.id
                }?.id ?: compositeEntryKey(existing.id, entry.baseModel.id)
            }
            if (srcId.isNotEmpty()) entryMap[srcId] = resolvedEntryId
        }
        return existing.id to entryMap
    }
}

/** Outcome of a [ProviderRepository.refreshModels] call, surfaced to the UI
 *  so refresh feedback (spinner → toast) can distinguish success source,
 *  no-key, and fallback. Added [provider-mgmt-opt]. */
enum class ModelRefreshResult {
    /** Live provider /v1/models succeeded. */
    SUCCESS_API,
    /** No API key configured — nothing loaded (model list stays empty/untouched). */
    NO_KEY,
    /** Third-party custom base whose live fetch failed; kept existing list. */
    PRESERVED,
    /** All known sources failed. */
    FAILURE,
}

/**
 * [RC5 / P0-pinned + GH#68] Pure JSON seam for the ProviderInstance
 * run-config fields on the backup export/import path
 * ([exportInstanceJSON] → [importInstanceJSON]). Hosted as internal
 * file-scope functions (not methods on [ProviderRepository], which needs an
 * Android Context) so the round-trip is JVM-unit-testable without Robolectric.
 *
 * History: these five fields were NEVER written by the backup export, so a
 * config restore / device migration silently reset them to defaults
 * (pinned=false, enabled=true, azureMode=false, image mode → auto). Same root
 * cause as the Room-layer P0-pinned & GH#68 fixes — the JSON backup serializer
 * drifted from the ProviderInstance model. Every writer key is additive +
 * optional: older readers ignore unknown keys, and [readRunConfigFields]
 * defaults each field when the key is absent so old backups still restore
 * cleanly (no crash, byte-for-byte previous behavior).
 */
private const val RCF_IS_ENABLED = "isEnabled"
private const val RCF_AZURE_MODE = "azureMode"
private const val RCF_IMG_MODE = "imageEndpointMode"
private const val RCF_IMG_RESOLVED = "imageEndpointResolved"
private const val RCF_PINNED = "pinned"

/** Round-tripped run-config of a [ProviderInstance] decoded from backup JSON. */
internal data class RunConfigSnapshot(
    val isEnabled: Boolean,
    val azureMode: Boolean,
    val imageEndpointMode: ImageEndpointMode,
    val imageEndpointResolved: ImageEndpointMode?,
    val pinned: Boolean,
)

/**
 * Write the five run-config fields onto [obj]. Enum names use
 * [ImageEndpointMode.name] — matching [ProviderConfigMapping]'s persistence
 * convention — so a restored config is byte-identical to a Room round-trip.
 */
internal fun writeRunConfigFields(obj: org.json.JSONObject, instance: ProviderInstance) {
    obj.put(RCF_IS_ENABLED, instance.isEnabled)
    obj.put(RCF_AZURE_MODE, instance.azureMode)
    obj.put(RCF_IMG_MODE, instance.imageEndpointMode.name)
    instance.imageEndpointResolved?.name?.let { obj.put(RCF_IMG_RESOLVED, it) }
    obj.put(RCF_PINNED, instance.pinned)
}

/**
 * Read the five run-config fields from [dict], each defaulting to the model's
 * own default when the key is absent (old backup). Enum names parsed via
 * runCatching + fallback, mirroring [ProviderConfigMapping]: an unknown enum
 * name from a future build degrades to `auto` / no cache instead of throwing
 * away the whole import.
 */
internal fun readRunConfigFields(dict: org.json.JSONObject): RunConfigSnapshot {
    val imageEndpointMode = dict.optString(RCF_IMG_MODE, "")
        .takeIf { it.isNotBlank() }
        ?.let { m -> runCatching { ImageEndpointMode.valueOf(m) }.getOrNull() }
        ?: ImageEndpointMode.auto
    val imageEndpointResolved = dict.optString(RCF_IMG_RESOLVED, "")
        .takeIf { it.isNotBlank() }
        ?.let { m -> runCatching { ImageEndpointMode.valueOf(m) }.getOrNull() }
    return RunConfigSnapshot(
        isEnabled = dict.optBoolean(RCF_IS_ENABLED, true),
        azureMode = dict.optBoolean(RCF_AZURE_MODE, false),
        imageEndpointMode = imageEndpointMode,
        imageEndpointResolved = imageEndpointResolved,
        pinned = dict.optBoolean(RCF_PINNED, false),
    )
}

// --- [fix/dv16-a A-08] Shared instance-field decode / write face for the two
// backup restore paths. Top-level in this file (not a ProviderRepository
// method) so the JVM round-trip tests can drive it without a Context. ---

/**
 * Single decode point for the instance-config fields of an exported provider
 * JSON payload — customBaseURL / appendV1Suffix / useResponsesAPI /
 * customUserAgent + the five run-config fields ([readRunConfigFields]).
 * Shared by [importInstanceJSON] (fresh append) and [mergeImportInstanceJSON]
 * (same (type, label) merge) so the two restore paths cannot drift: a field
 * decoded on the append path decodes identically on the merge path. Before
 * this seam the merge path decoded NONE of these, so a same-(type,label)
 * restore silently dropped the user's base URL / format toggles / run-config.
 */
internal data class ImportInstanceFields(
    val customBaseURL: String?,
    val appendV1Suffix: Boolean,
    val useResponsesAPI: Boolean,
    val customUserAgent: String?,
    val runConfig: RunConfigSnapshot,
)

internal fun readImportInstanceFields(dict: org.json.JSONObject): ImportInstanceFields =
    ImportInstanceFields(
        customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null },
        appendV1Suffix = dict.optBoolean("appendV1Suffix", true),
        useResponsesAPI = dict.optBoolean("useResponsesAPI", false),
        customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null },
        runConfig = readRunConfigFields(dict),
    )

/**
 * Apply the decoded instance-config fields onto [base]. Copy-on-write:
 * returns a NEW instance, never mutates the caller's object (the object the
 * config store hands back is the live instance concurrent readers iterate).
 * Deliberately touches ONLY the restore fields — id / label / providerType /
 * credentialType / createdAt (the merge match key and the live instance's
 * identity) are untouched, so the "同名同类型 ⇒ 合并" matching and the
 * caller-resolved label stay intact.
 */
internal fun applyImportInstanceFields(base: ProviderInstance, f: ImportInstanceFields): ProviderInstance =
    base.copy(
        customBaseURL = f.customBaseURL,
        appendV1Suffix = f.appendV1Suffix,
        useResponsesAPI = f.useResponsesAPI,
        customUserAgent = f.customUserAgent,
        isEnabled = f.runConfig.isEnabled,
        azureMode = f.runConfig.azureMode,
        imageEndpointMode = f.runConfig.imageEndpointMode,
        imageEndpointResolved = f.runConfig.imageEndpointResolved,
        pinned = f.runConfig.pinned,
    )


/**
 * Merge a parsed import entry into the local entry list, single-sourced so the
 * merge path's "hit" branch is one decision point (a second copy of this logic
 * is exactly the drift [applyImportInstanceFields] exists to prevent).
 *
 * Semantics — [fix/dv20-a2 A-08] a restore is a restore, one level below
 * [applyImportInstanceFields]:
 *  - The backup's model-level values (base model metadata / overrides /
 *    isCustom / isHidden / costTier) are written onto the existing entry.
 *  - The entry's identity is kept: [ModelEntry.uuid] stays the local id, so
 *    entryIdMap / model-group members / agentLoop ids referencing it stay
 *    valid. (providerInstanceId is equal by construction — the caller's hit
 *    predicate matches on it.)
 *  - [ModelEntry.userModifiedAt] rides the backup like every other field
 *    ([fix/dv21-a3 A-03]) — it is NOT restamped with "now" by the writer. The
 *    restored value is what makes a second import of the same backup a true
 *    no-op (`restored == local` above), and a fresh stamp would push this
 *    instance's [shadowVoiceProviders] arbitration past a sibling the user
 *    really edited. The write face honors it through [updateEntry]'s
 *    `stampUserModified = false` mode; the UI editor path keeps the default
 *    (true).
 *  - A no-op merge returns null: nothing to write, the caller keeps the
 *    existing id. That is the unchanged "identical restore does not touch the
 *    entry" behavior.
 *
 * The full field set rides [ModelEntry] itself rather than a hand-written
 * whitelist here — adding a field to the data class must not need a second
 * edit in this function (four-way-sync lesson).
 */
internal fun mergeImportedEntryOntoLocal(local: ModelEntry, imported: ModelEntry): ModelEntry? {
    val restored = imported.copy(uuid = local.id)
    return if (restored == local) null else restored
}

// --- [da1023-C] Pure entry-key / compact-pin decision helpers. Top-level in
// this file (not ProviderRepositoryUtils.kt, which other 1023 fix branches
// may own) to stay inside this branch's exclusive file set; same package
// keeps them JVM-testable. ---

/** [da1023-C] Outcome of the addEntry composite-key guard. */
internal data class EntryAddDecision(
    /** false = a live entry already owns this (instance, modelId) composite key. */
    val allowed: Boolean,
    /** Composite key of the new entry; the surviving entry's id on duplicate. */
    val persistedId: String,
)

internal fun decideEntryAdd(
    providerInstanceId: String,
    modelId: String,
    existing: List<Pair<String, String>>,
): EntryAddDecision {
    val newKey = "$providerInstanceId/$modelId"
    val hit = existing.firstOrNull { it.second == newKey }
    return if (hit != null) EntryAddDecision(false, hit.first) else EntryAddDecision(true, newKey)
}

/** [da1023-C] Compact-pin rewrite decision on a rename (null = no rewrite). */
internal fun decideCompactPinMigration(pinEntryId: String, oldKey: String, newKey: String): String? =
    if (pinEntryId.isNotEmpty() && pinEntryId == oldKey && oldKey != newKey) newKey else null

/** [da1023-C] Compact-pin reset decision on removal. */
internal fun decideCompactPinCleanup(pinEntryId: String, removedEntryIds: Collection<String>): Boolean =
    pinEntryId.isNotEmpty() && removedEntryIds.contains(pinEntryId)

/**
 * [da1024-C] True when the prefs-held secondary reference [ref] points at an
 * entry id that is going away. One predicate for `lastUsedEntryId` and the two
 * voice overrides — all `String?` SharedPreferences values, so the null case
 * (and the System voice sentinels, which never equal a provider entry id) is
 * spelled out exactly once instead of at every removal path.
 */
internal fun decideStaleRefCleanup(ref: String?, removedEntryIds: Collection<String>): Boolean =
    ref != null && removedEntryIds.contains(ref)
