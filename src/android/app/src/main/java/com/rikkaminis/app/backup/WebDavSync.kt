package com.rikkaminis.app.backup

import okhttp3.OkHttpClient
import java.io.File
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
        // [FIX-1028 / B-12] Name stamp is read back by list/prune — one format.
        val name = "$BACKUP_PREFIX${BackupRotation.stamp()}$BACKUP_SUFFIX"
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
    ): String = putAutoBackup(config, payload.toByteArray(Charsets.UTF_8), client)

    /**
     * [fix/da1023-backup-streaming-fetch] File overload for callers that
     * already have the document on disk — the automatic backup streams its
     * payload straight into the local copy (ConfigBackup.exportToWriter) and
     * must NOT rebuild a whole-document String just to upload it. The bytes
     * are read off disk for the PUT; the file itself stays owned by the
     * caller (the local rotation manages it).
     */
    fun backupAuto(
        config: WebDavConfig,
        file: File,
        client: OkHttpClient = WebDavClient.defaultClient(),
    ): String = putAutoBackup(config, file.inputStream().use { it.readBytes() }, client)

    /** Shared naming + push for both [backupAuto] overloads (same
     *  `auto/` subdir convention, second-precision name). */
    private fun putAutoBackup(
        config: WebDavConfig,
        bytes: ByteArray,
        client: OkHttpClient,
    ): String {
        val dav = WebDavClient(config, client)
        dav.ensureCollectionExists()
        dav.ensureCollectionExists(AUTO_SUBDIR)
        // [FIX-1028 / B-12] Same single stamp format the pruner reads back.
        val name = "$AUTO_BACKUP_PREFIX${BackupRotation.stamp()}$BACKUP_SUFFIX"
        dav.put("$AUTO_SUBDIR/$name", bytes, "application/json")
        return name
    }

    /**
     * [fix/da1023-backup-streaming-fetch] Reduce a remote auto-backup display
     * name (after the [AUTO_BACKUP_PREFIX] / [BACKUP_SUFFIX] strip) to a token
     * that is safe to embed in a LOCAL file name. The display name arrives
     * from the WebDAV server, i.e. it is not under our control: before this,
     * `rikkaminis-backup-auto-../../evil.json` was concatenated straight into
     * `File(dir, LOCAL_FILE_PREFIX + stamp + ".json")` and wrote through the
     * backup-autos directory.
     *
     * Fail-closed: returns null (caller must refuse to write) when the stamp
     *  - is blank (after trimming),
     *  - contains a path separator (`/` or `\`),
     *  - contains a `..` sequence (parent traversal), or
     *  - contains an ASCII control character (C0 or DEL — never legitimate).
     *
     * Everything else is whitelisted character by character: `[A-Za-z0-9._-]`
     * passes through, any other character (space, punctuation, non-ASCII) is
     * replaced with `_`, so a mildly odd but harmless name still fetches
     * instead of failing outright.
     */
    fun sanitizeAutoBackupStamp(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        if (trimmed.contains("..")) return null
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
        // Explicit ASCII whitelist (NOT Char.isLetterOrDigit, which is
        // Unicode-category based and would pass CJK through untouched).
        return trimmed.map {
            if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' ||
                it == '.' || it == '_' || it == '-'
            ) it else '_'
        }.joinToString("")
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
        // [FIX-1028 / B-12] The copy the caller JUST pushed. Rotation must never
        // delete it, which is exactly what happened when a rolled-back clock
        // gave that upload the oldest-looking name. Trailing on purpose: an
        // existing `pruneAutoBackups(cfg, keep = N, client)` call site keeps
        // binding `client` positionally.
        protectName: String? = null,
    ): Int {
        val dav = WebDavClient(config, client)
        // [FIX-1028 / B-12] Order and deletion eligibility both come from the
        // stamp in the display name; a server that omits `getlastmodified`
        // therefore can no longer decide which copy dies (every entry used to
        // collapse to Instant.EPOCH and the order became "whatever the server
        // listed"). Entries whose age cannot be established — no name stamp and
        // no real last-modified — are never victims.
        val stale = BackupRotation.rotationVictims(
            items = listAutoBackupEntries(config, client).filter { it.subdir == AUTO_SUBDIR },
            name = { it.item.displayName },
            key = { autoBackupStampMillis(it.item) },
            keep = keep,
            protect = { protectName != null && it.item.displayName == protectName },
        )
        var deleted = 0
        for (entry in stale) {
            val path = "${entry.subdir}/${entry.item.displayName}"
            if (runCatching { dav.delete(path) }.isSuccess) deleted++
        }
        return deleted
    }

    /** How many auto-backup copies to keep on the remote (and locally).
     *  [fix-autobackup-keep-3] Reduced 7 -> 3: auto copies rotate daily and
     *  the full payload minus chat is still tens of MB, so 7 copies pinned a
     *  long list on the remote and in the UI. 3 days of history keeps a
     *  usable fallback if one day's write/upload goes bad. Single source of
     *  truth: AutoBackupManager and the settings UI read this constant. */
    const val AUTO_BACKUP_KEEP = 3

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
                .let { items ->
                    BackupRotation.newestFirst(items, name = { it.displayName }, key = { manualBackupStampMillis(it) })
                }
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
        // [FIX-1028 / B-12] Newest first by the stamp in the NAME (falling back
        // to a real last-modified), never `lastModified` alone: a server that
        // omits the property returns EPOCH for every entry, and the previous
        // comparator then preserved listing order — i.e. rotation deleted an
        // arbitrary copy, not the oldest one.
        return BackupRotation.newestFirst(
            inAuto + legacyInRoot,
            name = { it.item.displayName },
            key = { autoBackupStampMillis(it.item) },
        )
    }

    /** [FIX-1028 / B-12] Age of a remote auto backup: name stamp, else a real
     *  `getlastmodified`. [resourceToBackupItem] maps a missing property to
     *  `Instant.EPOCH`, so `<= 0` means "the server did not tell us" — null,
     *  not "the beginning of time". */
    private fun autoBackupStampMillis(item: WebDavBackupItem): Long? =
        BackupRotation.stampMillis(item.displayName, AUTO_BACKUP_PREFIX)
            ?: item.lastModified.toEpochMilli().takeIf { it > 0L }

    /** [FIX-1028 / B-12] Same key for the manual list (display order only:
     *  manual copies are never pruned). Accepts both the pre-rename and the
     *  current prefix, plus the minute-precision name [ConfigBackup.suggestedFileName]
     *  gives a local export. */
    private fun manualBackupStampMillis(item: WebDavBackupItem): Long? =
        BackupRotation.stampMillis(item.displayName, BACKUP_PREFIX)
            ?: BackupRotation.stampMillis(item.displayName, LEGACY_BACKUP_PREFIX)
            ?: item.lastModified.toEpochMilli().takeIf { it > 0L }

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
