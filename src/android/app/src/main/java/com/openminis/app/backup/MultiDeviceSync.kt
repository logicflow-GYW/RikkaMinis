package com.openminis.app.backup

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import okhttp3.OkHttpClient

/**
 * Multi-device auto-sync of light configuration state.
 *
 * Scope matched against the user's decision: ONLY the settings a user expects
 * to carry between their own devices — app config (BACKED_UP_SCOPES),
 * providers, model groups, env vars and the shared user-maintained GLOBAL.md.
 * Skills, MCP servers, chat history, MEMORY-ROLLUP.md and the per-device
 * YYYY-MM-DD daily memory logs are deliberately excluded:
 *  - skills live on GitHub already (cross-device by construction)
 *  - MCP servers have OAuth leases that always need re-auth
 *  - chat history is a per-device audit copy, not a shared resource
 *  - daily logs are a record of *each* device's agent activity — syncing them
 *    as a whole file overwrites the receiving device's same-day entries
 *  - MEMORY-ROLLUP.md is distilled from those (now per-device) daily logs, so
 *    whole-file syncing it would clobber one device's distillation with the
 *    other's
 *
 * Transport is the SAME WebDAV pipeline as manual backups (WebDavClient +
 * WebDavSync), but under a distinct [WebDavSync.SYNC_PREFIX] filename so
 * auto-sync snapshots never pollute the manual remote-backup list. Restore
 * reuses ConfigBackup.import() unchanged — a sync payload is a normal backup
 * document minus the skills/mcp/chat sections, which import() skips silently.
 *
 * #### Merge model ([T-sync-merge])
 * The shipped sync was de-facto "last writer wins" over one canonical file,
 * which silently reverts sibling edits (a stale device re-pushing its whole
 * config) and resurrects deleted objects (union-merge with no tombstones).
 * The fix routes every push/pull through [SyncMerge.reconcile]: a Lamport-style
 * per-object/per-field version fold that (a) keeps the newest edit per unit,
 * (b) propagates deletions as tombstones, and (c) converges to one document.
 * The version clock lives in a small persisted [SyncMerge.Store] plus the
 * `_sid`/`_ver`/`_tombstones`/`_fieldVers` annotations on the wire (all
 * ignored by [ConfigBackup.import] and older builds). The transport's
 * optimistic lock (If-Match) remains the backstop for two devices editing the
 * *same* object within one sync window — such objects aren't auto-mergeable.
 */
object MultiDeviceSync {
    private const val TAG = "MultiDeviceSync"

    /** Preferences key for the user-facing master switch. */
    const val PREF_KEY_ENABLED = "multi_device_sync_enabled"

    /** Preferences key: whether the user has confirmed that auto-sync
     *  snapshots may contain API keys / OAuth tokens / env var values.
     *  Not confirmed → sync pushes without secrets (includeSecrets=false). */
    const val PREF_KEY_SECRETS_CONFIRMED = "multi_device_sync_secrets_confirmed"

    /** Preferences key holding the serialized [SyncMerge.Store]. */
    const val PREF_KEY_SYNC_STORE = "multi_device_sync_store_v1"

    /**
     * Shared, stable memory files auto-sync carries. Daily logs are excluded —
     * they are per-device audit copies. MEMORY-ROLLUP.md is also excluded: it
     * is distilled from the (now per-device) daily logs, so syncing it as a
     * whole file would clobber one device's distillation with the other's.
     * GLOBAL.md is the one genuinely shared file — the user-maintained
     * persistent-preferences / conventions document, small and low-churn.
     */
    private val SYNC_MEMORY_FILES: Set<String> = setOf("GLOBAL.md")

    /**
     * Whether auto-sync is turned on for this install.
     */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_ENABLED, false)
    }

    /**
     * Whether the user has confirmed that auto-sync snapshots may carry
     * API keys and credentials. Before this is true, sync runs with
     * includeSecrets=false (provider keys and env var values omitted).
     * Persisted alongside [PREF_KEY_ENABLED] in the same prefs file.
     */
    fun hasConfirmedSecretsSync(context: Context): Boolean {
        return context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY_SECRETS_CONFIRMED, false)
    }

    /** Mark the secrets-sync confirmation as given (one-shot, permanent). */
    fun markSecretsSyncConfirmed(context: Context) {
        context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY_SECRETS_CONFIRMED, true).apply()
    }

    /**
     * Load the persisted [SyncMerge.Store], or an empty one when absent/
     * unreadable (first sync, or a malformed write — recoverable by rebuilding
     * from the remote doc).
     */
    private fun loadStore(prefs: android.content.SharedPreferences): SyncMerge.Store {
        val raw = prefs.getString(PREF_KEY_SYNC_STORE, null) ?: return SyncMerge.Store()
        return SyncMerge.storeFromJson(raw)
    }

    private fun persistStore(prefs: android.content.SharedPreferences, store: SyncMerge.Store) {
        runCatching { prefs.edit().putString(PREF_KEY_SYNC_STORE, SyncMerge.storeToJson(store)).apply() }
            .onFailure { Log.w(TAG, "failed to persist sync store: ${it.message}") }
    }

    /**
     * Compute the sync-subset payload — a ConfigBackup document containing only
     * the light state (config + providers + groups + env vars + shared memory),
     * sized KB not 70MB. Achieved by passing null repos for the excluded
     * sections: ConfigBackup.export() guards each section on a non-null repo,
     * so passing null skillRepo / mcpRepo / chatRepo makes those sections
     * vanish while the rest of the (unchanged) export body runs untouched.
     */
    suspend fun exportSyncPayload(
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository?,
        memoryRepo: MemoryRepository?,
        includeSecrets: Boolean,
    ): String {
        // chatWindowDays=0 documents that chat is never part of an auto-sync
        // snapshot. includeHiddenModels=false: a sync snapshot carries only
        // what the user actually selected (visible + custom models), never the
        // provider's full public catalog cache — hidden non-custom models are
        // re-pullable from /models, dominate the payload size, and must not
        // leak to sibling devices as if they were user state. [T-sync-hide-prune]
        return ConfigBackup.export(
            providerRepo = providerRepo,
            includeSecrets = includeSecrets,
            envVarRepo = envVarRepo,
            skillRepo = null,          // skills excluded (GitHub already syncs them)
            memoryRepo = memoryRepo,
            mcpRepo = null,            // MCP excluded (OAuth needs re-auth)
            chatRepo = null,           // chat excluded (per-device audit copy)
            chatWindowDays = 0,
            includeHiddenModels = false,
            memoryFileNames = SYNC_MEMORY_FILES,
            // [T-auto-backup-assets] Sync carries no thinking rules yet (no
            // per-object merge kind for them in SyncMerge) and never carries
            // artifact files (per-device outputs, last-writer-wins clobbers).
            includeThinkingRules = false,
        )
    }

    /**
     * The complete "sync now" cycle run on app start / resume (when enabled):
     *  1. Export the local light state.
     *  2. Pull the newest remote snapshot (if any).
     *  3. [SyncMerge.reconcile] both into one merged document (version-fold —
     *     sibling edits survive, deletions propagate as tombstones).
     *  4. Apply the sibling's deletions to this device's repositories.
     *  5. Import the merged document (entry-level merge, isSyncMerge=true).
     *  6. Push the merged document down the optimistic-lock path, skipped when
     *     its content hash matches the last successful push (no-op detection).
     *
     * @return a short descriptor for logging / the settings screen status.
     */
    suspend fun syncNow(
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository?,
        memoryRepo: MemoryRepository?,
        config: WebDavConfig,
        client: OkHttpClient,
        includeSecrets: Boolean = false,
    ): String {
        val prefs = context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)
        val store = loadStore(prefs)

        // (1) Export the local light state. Failure (e.g. payload over
        // MAX_PAYLOAD_BYTES) degrades gracefully — never crash the
        // foreground-triggered coroutine.
        val localPayload = runCatching {
            exportSyncPayload(
                providerRepo = providerRepo,
                envVarRepo = envVarRepo,
                memoryRepo = memoryRepo,
                includeSecrets = includeSecrets,
            )
        }.getOrElse {
            return "export-failed: ${it.message}"
        }

        // (2) Pull the newest remote snapshot. pulled == null means the server
        // holds no sync file yet — the push below then always runs.
        val pulled = runCatching { WebDavSync.pullLatestSync(config, client) }.getOrElse {
            return "pull-failed: ${it.message}"
        }

        // (3) Reconcile local + remote into one merged document.
        val result = SyncMerge.reconcile(localPayload, pulled?.json, store)

        // (4) Apply sibling deletions to local repositories BEFORE importing
        // (import re-adds what it finds in the merged doc; the deleted objects
        // are absent from it, so this ordering leaves them removed).
        applyDeletions(providerRepo, envVarRepo, result.deletions)

        // (5) Import the merged document locally (per-entry merge; keep local
        // secrets — isSyncMerge=true). Best-effort: a broken merged doc must
        // not crash the foreground coroutine.
        runCatching {
            ConfigBackup.import(
                providerRepo = providerRepo,
                json = result.mergedJson,
                envVarRepo = envVarRepo,
                skillRepo = null,
                memoryRepo = memoryRepo,
                mcpRepo = null,
                chatRepo = null,
                isSyncMerge = true,
            )
        }

        // (6) Push the merged doc when it changed since our last push (and the
        // server holds a file). [T-backup-sync-change-detect] The pull/import
        // side is intentionally NOT gated — it is the only way to receive
        // sibling changes. The store is persisted on a successful push so a
        // conflict (412) leaves the previous store intact for the retry.
        if (!result.changed && pulled != null) {
            persistStore(prefs, result.store)
            return "no-change: skipped push"
        }
        return try {
            val name = WebDavSync.pushSync(
                config,
                result.mergedJson,
                client,
                pulledEtag = pulled?.etag,
                expectAbsent = pulled == null,
            )
            persistStore(prefs, result.store)
            "pushed: $name"
        } catch (t: WebDavException) {
            if (t.statusCode == 412) "conflict: remote changed, retry"
            else "push-failed: ${t.message}"
        } catch (t: Throwable) {
            "push-failed: ${t.message}"
        }
    }

    /**
     * Remove the objects a sibling deleted. Memory deletions are ignored by
     * policy: the synced memory files (GLOBAL.md / the rollup) are content-
     * additive shared files, and deleting GLOBAL.md from a sibling's absence
     * would destroy user-maintained content — so memory only ever merges
     * forward; it never deletes.
     */
    private fun applyDeletions(
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository?,
        deletions: List<SyncMerge.Deletion>,
    ) {
        for (d in deletions) {
            when (d.kind) {
                SyncMerge.Kind.PROVIDER -> {
                    val target = providerRepo.instances.firstOrNull {
                        it.providerType.name == d.a && it.label == d.b
                    } ?: continue
                    providerRepo.removeInstance(target.id)
                }
                SyncMerge.Kind.GROUP -> {
                    val target = providerRepo.config.value.modelGroups
                        .firstOrNull { it.name == d.a } ?: continue
                    providerRepo.removeGroup(target.id)
                }
                SyncMerge.Kind.ENV_VAR -> {
                    val repo = envVarRepo ?: continue
                    val target = repo.entries.value.firstOrNull {
                        it.key.equals(d.a, ignoreCase = true)
                    } ?: continue
                    repo.delete(target.id)
                }
                SyncMerge.Kind.MEMORY -> { /* forward-merge only, never delete */ }
            }
        }
    }
}