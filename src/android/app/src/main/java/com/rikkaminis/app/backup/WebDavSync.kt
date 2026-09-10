package com.rikkaminis.app.backup

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
        payload: ByteArray,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ) {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        val name = "rikkaminis-backup-${
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
        }.json"
        dav.put(name, payload, "application/json")
    }

    /** [T-backup-streaming-export] String overload kept for callers that
     *  still hold a fully-built document (small sync payloads). Heavy
     *  callers stream to a temp file and pass the bytes instead. */
    fun backup(
        config: WebDavConfig,
        payload: String,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ) = backup(config, payload.toByteArray(Charsets.UTF_8), client)

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
     *  subdirectory so machine-generated daily
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
     *  of the configured backup path (used for auto-backup copies kept in
     *  [AUTO_SUBDIR]); leave empty to delete a file in the backup root. */
    fun deleteBackupFile(
        config: WebDavConfig,
        item: WebDavBackupItem,
        client: OkHttpClient = WebDavClient.defaultClient(),
        subdir: String = "",
    ) {
        val path = if (subdir.isBlank()) item.displayName else "$subdir/${item.displayName}"
        WebDavClient(config, client).delete(path)
    }
}
