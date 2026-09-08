package com.openminis.app.backup

import okhttp3.OkHttpClient
import java.time.Instant

/**
 * Backup-domain operations on top of [WebDavClient]: pushing the JSON payload
 * produced by [ConfigBackup.export], listing/restoring/deleting remote copies.
 *
 * Mirrors rikkahub's WebDavSync (AGPL-3.0) responsibilities — filename
 * convention filtering, descending sort, directory auto-creation — minus the
 * zip packing (RikkaMinis backups are a single self-contained JSON document,
 * so upload is a raw PUT and restore a raw GET feeding ConfigBackup.import).
 * Pure JVM, no Android imports, unit-testable against MockWebServer.
 */
object WebDavSync {

    /** Filename convention for remote copies, matching
     *  [ConfigBackup.suggestedFileName]. Only files matching this prefix are
     *  shown in the remote list, so unrelated files in the user's WebDAV
     *  folder never surface as backups. */
    const val BACKUP_PREFIX = "rikkaminis-backup-"

    /** Pre-rename convention (openminis-backup-*). Still matched so copies
     *  pushed before the rename remain visible and restorable. */
    const val LEGACY_BACKUP_PREFIX = "openminis-backup-"

    const val BACKUP_SUFFIX = ".json"

    /**
     * Filename convention for multi-device auto-sync snapshots
     * ([MultiDeviceSync]). Distinct from [BACKUP_PREFIX] on purpose: automatic
     * sync produces a *subset* payload (config + providers + env vars + memory,
     * no skills / chat) named under its own prefix, so it never mixes with the
     * full manual backups a user chooses to keep in the same folder.
     */
    const val SYNC_PREFIX = "rikkaminis-sync-"

    /**
     * On the WebDAV server the auto-sync state lives in its own *subdirectory*
     * (`<backup-path>/sync/`) rather than mixed alongside the manual full
     * backups in the backup root. That way the sync state — auto-generated and
     * key-bearing — never shares a folder with the curated manual backups the
     * user chooses to keep.
     */
    const val SYNC_SUBDIR = "sync"

    /** Canonical filename for the single merged auto-sync state document.
     *  [RC16-sync-if-match] The sync snapshot converges onto ONE file rather
     *  than an accumulating, pruned set of timestamped snapshots: with a
     *  stable name the pull→conditional-push cycle can carry an `If-Match`
     *  precondition (overwrite only the exact version we just pulled) so a
     *  concurrent sibling push is refused with a 412 instead of silently
     *  clobbering our freshly pulled state. */
    const val SYNC_STATE_FILE = SYNC_PREFIX + "latest" + BACKUP_SUFFIX

    /** Result of [pullLatestSync]: the merged doc body plus the server ETag
     *  it was read under, for the conditional re-push ([RC16-sync-if-match]). */
    data class PulledSync(val json: String, val etag: String?)

    /** Verify the server + credentials. Throws on failure. */
    fun testConnection(config: WebDavConfig, client: OkHttpClient = WebDavClient.defaultClient()) {
        WebDavClient(config, client).testConnection()
    }

    /**
     * Uploads [payload] as a new timestamped file into the configured backup
     * folder. The file name uses second precision (yyyyMMdd-HHmmss) rather
     * than [ConfigBackup.suggestedFileName]'s minute precision: a local
     * export and a WebDAV push within the same minute would otherwise
     * silently overwrite each other on the server. The shared
     * `rikkaminis-backup-*.json` convention is kept so local files dropped
     * into the folder manually are still picked up by [listBackupFiles].
     */
    fun backup(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ) {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        val name = "rikkaminis-backup-${
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }.json"
        dav.put(name, payload.toByteArray(Charsets.UTF_8), "application/json")
    }

    /**
     * [T-auto-backup-assets] Push an automatic backup under its own
     * `rikkaminis-backup-auto-*` name into the [AUTO_SUBDIR] subdirectory
     * (auto-created on demand), so [pruneAutoBackups] rotates only
     * auto-created copies and never touches curated manual uploads. Auto
     * backups are listed by [listAutoBackupEntries] and restore through the
     * normal flow.
     */
    fun backupAuto(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        dav.ensureCollectionExists(AUTO_SUBDIR)
        val name = "rikkaminis-backup-auto-${
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }.json"
        dav.put("$AUTO_SUBDIR/$name", payload.toByteArray(Charsets.UTF_8), "application/json")
        return name
    }

    /** [T-auto-backup-assets] Auto-backup filename prefix (subset of
     *  [BACKUP_PREFIX], distinct from manual uploads by the `auto-` token). */
    const val AUTO_BACKUP_PREFIX = "rikkaminis-backup-auto-"

    /** [T-auto-backup-assets] Automatic backups live in their own WebDAV
     *  subdirectory (sibling of [SYNC_SUBDIR]) so machine-generated daily
     *  copies never mix with the curated manual backups in the backup root,
     *  and so a second device can manage the same folder predictably.
     *  Copies pushed by older builds (flat `rikkaminis-backup-auto-*` in the
     *  root) are still listed by [listAutoBackupEntries] for
     *  restore/delete/fetch, but new pushes always land here. */
    const val AUTO_SUBDIR = "auto"

    /**
     * [T-auto-backup-assets] Delete remote auto-backup copies beyond the
     * newest [keep]. Only files under [AUTO_SUBDIR] are eligible — manual
     * `rikkaminis-backup-*` uploads are never pruned, and neither are the
     * legacy flat `rikkaminis-backup-auto-*` stragglers still sitting in the
     * backup root (pushed by pre-subdir builds; the user may want to inspect
     * or migrate those by hand). Best-effort (a deletion failure is
     * swallowed; the next run retries).
     */
    fun pruneAutoBackups(
        config: WebDavConfig,
        keep: Int = AUTO_BACKUP_KEEP,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): Int {
        val dav = WebDavClient(config, client)
        val stale = listAutoBackupEntries(config, client)
            .filter { it.subdir == AUTO_SUBDIR }
            .drop(keep)
        var deleted = 0
        for (entry in stale) {
            val path = "${entry.subdir}/${entry.item.displayName}"
            if (runCatching { dav.delete(path) }.isSuccess) deleted++
        }
        return deleted
    }

    /** How many auto-backup copies to keep on the remote (and locally). */
    const val AUTO_BACKUP_KEEP = 7

    /** Remote *manual* backups, newest first. Automatic backups
     *  ([AUTO_BACKUP_PREFIX]) are deliberately excluded — they live under
     *  [AUTO_SUBDIR] and are managed through [listAutoBackupEntries], so the
     *  two kinds never mix in one list. */
    fun listBackupFiles(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): List<WebDavBackupItem> {
        val dav = WebDavClient(config, client)
        return try {
            dav.list()
                .filter {
                    !it.isCollection &&
                        (it.displayName.startsWith(BACKUP_PREFIX) ||
                            it.displayName.startsWith(LEGACY_BACKUP_PREFIX)) &&
                        !it.displayName.startsWith(AUTO_BACKUP_PREFIX) &&
                        it.displayName.endsWith(BACKUP_SUFFIX)
                }
                .map { resourceToBackupItem(it) }
                .sortedByDescending { it.lastModified }
        } catch (e: WebDavException) {
            // [T-backup-list-nomkcol] A read operation must not create the
            // remote folder (that is upload's job); a missing folder is just
            // an empty backup list, not an error.
            if (e.statusCode == 404) emptyList() else throw e
        }
    }

    /** An automatic backup located on the server: the listing entry plus the
     *  subdirectory it lives in ("" = backup root, for copies pushed by
     *  older builds before the [AUTO_SUBDIR] move). */
    data class AutoBackupEntry(
        val item: WebDavBackupItem,
        val subdir: String,
    )

    /** Remote automatic backups, newest first: files under [AUTO_SUBDIR]
     *  plus any `rikkaminis-backup-auto-*` stragglers still in the backup
     *  root (pushed before the subdir move — kept visible so a second
     *  device can still restore/fetch/delete them). Returns an empty list
     *  when the folder has none yet. */
    fun listAutoBackupEntries(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): List<AutoBackupEntry> {
        val dav = WebDavClient(config, client)
        val inAuto = try {
            dav.list(AUTO_SUBDIR)
                .filter {
                    !it.isCollection &&
                        it.displayName.startsWith(AUTO_BACKUP_PREFIX) &&
                        it.displayName.endsWith(BACKUP_SUFFIX)
                }
                .map { AutoBackupEntry(resourceToBackupItem(it), AUTO_SUBDIR) }
        } catch (e: WebDavException) {
            if (e.statusCode == 404) emptyList() else throw e
        }
        val legacyInRoot = try {
            dav.list()
                .filter {
                    !it.isCollection &&
                        it.displayName.startsWith(AUTO_BACKUP_PREFIX) &&
                        it.displayName.endsWith(BACKUP_SUFFIX)
                }
                .map { AutoBackupEntry(resourceToBackupItem(it), "") }
        } catch (e: WebDavException) {
            if (e.statusCode == 404) emptyList() else throw e
        }
        return (inAuto + legacyInRoot).sortedByDescending { it.item.lastModified }
    }

    /** Download a remote automatic backup (see [listAutoBackupEntries]) and
     *  return its JSON document, ready for [ConfigBackup.import] or for
     *  saving as a local automatic-backup copy. */
    fun restoreAuto(
        config: WebDavConfig,
        entry: AutoBackupEntry,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String {
        val path = if (entry.subdir.isBlank()) entry.item.displayName
        else "${entry.subdir}/${entry.item.displayName}"
        return WebDavClient(config, client)
            .get(path)
            .toString(Charsets.UTF_8)
    }

    private fun resourceToBackupItem(it: WebDavResourceInfo): WebDavBackupItem =
        WebDavBackupItem(
            href = it.href,
            displayName = it.displayName,
            size = it.contentLength,
            lastModified = it.lastModified ?: Instant.EPOCH,
        )

    /** Download a remote backup and return its JSON document, ready for
     *  [ConfigBackup.import]. */
    fun restore(
        config: WebDavConfig,
        item: WebDavBackupItem,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String {
        return WebDavClient(config, client)
            .get(item.displayName)
            .toString(Charsets.UTF_8)
    }

    /** Remove a remote backup. [subdir] scopes the delete to a child folder
     *  of the configured backup path (used for auto-sync snapshots kept in
     *  [SYNC_SUBDIR]); leave empty to delete a file in the backup root. */
    fun deleteBackupFile(
        config: WebDavConfig,
        item: WebDavBackupItem,
        client: OkHttpClient = WebDavClient.defaultClient(),
        subdir: String = "",
    ) {
        val path = if (subdir.isBlank()) item.displayName else "$subdir/${item.displayName}"
        WebDavClient(config, client).delete(path)
    }

    /**
     * Push the merged multi-device sync state ([MultiDeviceSync]) into the
     * canonical [SYNC_STATE_FILE]. Same transport and auto-create semantics
     * as [backup] — transports a *subset* payload (config + providers + env
     * vars + memory) under the sync prefix so it never mixes with manual
     * remote-backup files. Returns the created displayName.
     *
     * [RC16-sync-if-match] Conditional write: pass the [pulledEtag] read by
     * [pullLatestSync] to guard the overwrite with `If-Match` (fails 412 if
     * a sibling pushed first); pass [expectAbsent] when the server is
     * expected to hold nothing yet, guarding the create with
     * `If-None-Match: *` against two simultaneous first pushes. On conflict
     * [WebDavClient.put] throws [WebDavException] with status 412 and the
     * caller surfaces "conflict: remote changed, retry" instead of clobbering.
     */
    fun pushSync(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
        pulledEtag: String? = null,
        expectAbsent: Boolean = false,
    ): String {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        dav.ensureCollectionExists(SYNC_SUBDIR)
        dav.put(
            "$SYNC_SUBDIR/$SYNC_STATE_FILE",
            payload.toByteArray(Charsets.UTF_8),
            "application/json",
            ifMatchETag = pulledEtag,
            ifNoneMatch = expectAbsent,
        )
        return SYNC_STATE_FILE
    }

    /** Remote auto-sync snapshots, newest first. They live in the
     *  [SYNC_SUBDIR] subdirectory, isolated from manual full backups.
     *  Returns an empty list when the subdirectory does not exist yet
     *  (nothing has ever been synced). */
    fun listSyncFiles(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): List<WebDavBackupItem> {
        val dav = WebDavClient(config, client)
        return try {
            dav.list(SYNC_SUBDIR)
                .filter {
                    !it.isCollection &&
                        it.displayName.startsWith(SYNC_PREFIX) &&
                        it.displayName.endsWith(BACKUP_SUFFIX)
                }
                .map {
                    WebDavBackupItem(
                        href = it.href,
                        displayName = it.displayName,
                        size = it.contentLength,
                        lastModified = it.lastModified ?: Instant.EPOCH,
                    )
                }
                .sortedByDescending { it.lastModified }
        } catch (e: WebDavException) {
            if (e.statusCode == 404) emptyList() else throw e
        }
    }

    /** Download the newest auto-sync snapshot, or null when the folder has
     *  none yet. Returns the doc body plus the ETag it was read under so the
     *  caller can re-push it conditionally ([RC16-sync-if-match]). */
    fun pullLatestSync(
        config: WebDavConfig,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): PulledSync? {
        return listSyncFiles(config, client).firstOrNull()?.let { item ->
            val got = WebDavClient(config, client)
                .getWithEtag("$SYNC_SUBDIR/${item.displayName}")
            PulledSync(
                json = got.bytes.toString(Charsets.UTF_8),
                etag = got.etag,
            )
        }
    }
}
