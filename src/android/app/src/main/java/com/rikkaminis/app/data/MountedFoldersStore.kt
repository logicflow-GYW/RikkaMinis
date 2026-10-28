package com.rikkaminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.rikkaminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Store for user-mounted external folders (SAF-picked trees). Mirrors iOS
 * `MountedFoldersManager` with the following deliberate differences:
 *
 *   - iOS uses `URL.bookmarkData` + `startAccessingSecurityScopedResource`;
 *     Android uses tree URIs + `takePersistableUriPermission`. The URI
 *     survives process death and reboots once persisted, so there's no
 *     "activation" step — access is always available while the permission
 *     grant is held.
 *   - The shell-level bind-mount at `/var/minis/mounts/<name>` is NOT
 *     implemented in this pass. DocumentFile doesn't expose POSIX paths,
 *     so exposing these trees inside the PRoot / iSH rootfs needs a
 *     FUSE bridge or a periodic mirror pass. Spec §2.9.4 calls out the
 *     tradeoffs; we ship MVP scaffolding (persistence + CRUD + StateFlow)
 *     and leave the shell mount as a follow-up.
 *
 * Persistence: `filesDir/minis-config/mounted-folders.json`. The path is
 * intentionally outside `minis-global/` so it can't leak into the
 * DocumentsProvider-exposed tree.
 */
class MountedFoldersStore(private val context: Context) {

    @Serializable
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        val sourceDisplayName: String,
        val treeUri: String,
        val createdAt: Long = System.currentTimeMillis(),
        var isWritable: Boolean = true,
        var userAllowWrite: Boolean = true,
        /**
         * Cached POSIX host path resolved from the SAF tree URI (e.g.
         * `/storage/emulated/0/Documents/Vault`). null when the URI
         * came from a non-externalstorage provider or resolution failed —
         * UI / coordinator should treat such entries as inactive.
         */
        var resolvedHostPath: String? = null,
    ) {
        /** Final effective writable = OS-level `isWritable` AND user intent. */
        val effectiveWritable: Boolean get() = isWritable && userAllowWrite
    }

    private val storeFile: File by lazy {
        File(context.filesDir, "minis-config/mounted-folders.json").apply {
            parentFile?.mkdirs()
        }
    }

    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /**
     * [E2-F12] Set when [storeFile] exists but could not be read or parsed.
     *
     * The reader used to swallow every failure (`runCatching { … }` with no
     * handler) and leave the list empty, which looks exactly like "the user has
     * no mounts" — and the next mutation ([add] / [remove] / [refreshWritability])
     * then wrote that empty list back through [saveToDisk], permanently erasing
     * `mounted-folders.json`. The file could get into that state without any
     * user action: [saveToDisk] wrote it with a bare `writeText`, so a kill or a
     * full disk mid-write truncated it. Unreadable must therefore mean "keep the
     * in-memory list and leave the bytes alone", never "publish zero mounts".
     */
    @Volatile
    private var diskUnreadable = false

    /**
     * T219-5: fired after every CRUD operation that mutates the persisted
     * list. MinisApp wires this to `PRootKernel.applyMountedFoldersSnapshot`
     * so future shells pick up the new bind specs without a process restart.
     * Note that already-running proot processes are unaffected — proot is
     * one-shot and the next `shell_execute` is what carries the new mounts.
     */
    var onChange: (() -> Unit)? = null

    init {
        loadFromDisk()
    }

    /**
     * Persist a new tree URI as a mount. Caller is responsible for having
     * already called `contentResolver.takePersistableUriPermission(uri, …)`
     * — typically via the SAF picker result in [SafMountHelper.handlePickerResult].
     *
     * Resolves the SAF tree URI to a real POSIX path under
     * `/storage/emulated/0/...` (Option A — see T219 spec §1.3). Returns
     * null when:
     *   - the URI came from a non-externalstorage provider (Drive, Dropbox,
     *     etc. have no POSIX path PRoot can `-b` mount);
     *   - the resolved path doesn't exist or isn't readable by us;
     *   - the name is invalid / duplicate / cap reached.
     */
    suspend fun add(
        treeUri: Uri,
        customName: String,
        userAllowWrite: Boolean = true,
    ): Entry? = mutex.withLock {
        val name = sanitizeName(customName).takeIf { it.isNotEmpty() } ?: return@withLock null
        if (_entries.value.any { it.name.equals(name, ignoreCase = true) }) return@withLock null
        if (_entries.value.size >= MAX_MOUNTS) return@withLock null

        val resolvedHostPath = resolvePosixPath(treeUri, context) ?: run {
            AppLogger.warning(TAG, "add: rejected non-resolvable URI $treeUri")
            return@withLock null
        }

        val sourceDisplayName = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':', treeUri.lastPathSegment.orEmpty())
            .ifEmpty { name }
        // OS-level writability driven by the actual filesystem (Option A path
        // is what PRoot will use, not the SAF grant). isWritePermission can
        // be true while a per-package scoped-storage rule still rejects open(2).
        // [deep-audit-1025 ISSUE-08] probeWritable() is blocking file I/O
        // (create + delete a probe file) and `add()` is reached from a
        // Main-scoped launch in MountedFoldersScreen — on a slow or
        // network-backed mount that stalls frames.
        val probedWritable = withContext(Dispatchers.IO) { probeWritable(resolvedHostPath) }
        val entry = Entry(
            name = name,
            sourceDisplayName = sourceDisplayName,
            treeUri = treeUri.toString(),
            isWritable = probedWritable,
            userAllowWrite = userAllowWrite,
            resolvedHostPath = resolvedHostPath,
        )
        _entries.value = _entries.value + entry
        saveToDisk(_entries.value)
        onChange?.invoke()
        AppLogger.info(
            TAG,
            "add: name=$name host=$resolvedHostPath writable=$probedWritable ${storageDiag(context)}",
        )
        entry
    }

    /**
     * One-line storage-access diagnostic for the mount log. Distinguishes the
     * two reasons a folder can read but not write: on Android 11+ it's All Files
     * Access; on Android 10 it's whether WRITE_EXTERNAL_STORAGE was actually
     * granted at runtime (legacy opt-in alone is not enough) and whether the
     * process still holds the legacy storage view.
     */
    private fun storageDiag(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.R) {
            "sdk=$sdk allFilesAccess=${Environment.isExternalStorageManager()}"
        } else {
            val read = context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val write = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            "sdk=$sdk readGranted=$read writeGranted=$write legacyView=${Environment.isExternalStorageLegacy()}"
        }
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val before = _entries.value
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return@withLock false
        // Release the persisted URI grant so the system stops listing
        // us under "apps with access" and the user can re-pick later.
        before.firstOrNull { it.id == id }?.let { e ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(e.treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        _entries.value = after
        saveToDisk(after)
        onChange?.invoke()
        true
    }

    suspend fun setUserAllowWrite(id: String, allow: Boolean): Boolean = mutex.withLock {
        var changed = false
        _entries.value = _entries.value.map { e ->
            if (e.id == id && e.userAllowWrite != allow) {
                changed = true
                e.copy(userAllowWrite = allow)
            } else e
        }
        if (changed) {
            saveToDisk(_entries.value)
            onChange?.invoke()
        }
        changed
    }

    /**
     * Re-probe OS-level writability against the actual host path. Called
     * on foreground resume so changes (user revoked permission, removed
     * the folder, removable storage unmounted, …) propagate into the UI
     * and the coordinator's bind specs. Entries whose `resolvedHostPath`
     * is null (non-externalstorage) stay marked non-writable.
     */
    suspend fun refreshWritability() = mutex.withLock {
        val before = _entries.value
        val after = before.map { e ->
            // [deep-audit-1025 ISSUE-08] the probe is blocking file I/O, and
            // this reader runs on the caller's thread (Main: ON_RESUME and the
            // storage-permission result both call it), so probe off it.
            val probed = e.resolvedHostPath
                ?.let { p -> withContext(Dispatchers.IO) { probeWritable(p) } }
                ?: false
            if (probed != e.isWritable) e.copy(isWritable = probed) else e
        }
        if (after != before) {
            _entries.value = after
            saveToDisk(after)
            onChange?.invoke()
        }
    }

    /**
     * Decode a SAF tree URI into a POSIX host path PRoot can `-b` mount.
     *
     * Only accepts `com.android.externalstorage.documents` URIs — those
     * encode a `volume:relPath` document id where `volume` is either
     * `primary` (the device's internal shared storage) or a removable
     * storage UUID. Cloud providers (Drive, Dropbox, …) that return tree
     * URIs without a real filesystem mapping are rejected with a null
     * return so [add] can surface the "only on-device folders" error.
     *
     * Returns null on:
     *   - non-externalstorage authority,
     *   - unknown removable volume uuid,
     *   - resolved File doesn't exist / isn't a directory / unreadable.
     */
    suspend fun resolvePosixPath(treeUri: Uri, context: Context): String? =
        withContext(Dispatchers.IO) {
            val authority = treeUri.authority
            if (authority != EXTERNALSTORAGE_AUTHORITY) {
                AppLogger.warning(TAG, "resolvePosixPath: rejecting non-externalstorage authority=$authority")
                return@withContext null
            }
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext null
            val sep = docId.indexOf(':')
            val volume = if (sep < 0) docId else docId.substring(0, sep)
            val relPath = if (sep < 0) "" else docId.substring(sep + 1)

            val volumeRoot = resolveVolumeRoot(context, volume) ?: run {
                AppLogger.warning(TAG, "resolvePosixPath: unknown volume=$volume in docId=$docId")
                return@withContext null
            }
            val full = if (relPath.isEmpty()) File(volumeRoot)
                else File(volumeRoot, relPath)
            if (!full.exists() || !full.isDirectory) {
                AppLogger.warning(TAG, "resolvePosixPath: path missing or not dir: ${full.absolutePath}")
                return@withContext null
            }
            // Read probe: a resolvable dir the app can't actually readdir will
            // mount but show empty. Surface it now so the log explains the
            // eventual empty-folder report instead of leaving it silent.
            val children = full.list()
            if (children == null) {
                AppLogger.warning(
                    TAG,
                    "resolvePosixPath: ${full.absolutePath} canRead=${full.canRead()} but list()=null " +
                        "(readdir blocked by scoped storage) — mount will read EMPTY on this device",
                )
            } else {
                AppLogger.info(TAG, "resolvePosixPath: ${full.absolutePath} ok childCount=${children.size}")
            }
            full.absolutePath
        }

    /**
     * Try to create + delete a hidden probe file under [hostPath]. Captures
     * the same reality PRoot will see — `open(O_WRONLY|O_CREAT)` against
     * the real filesystem — so scoped-storage restrictions or freshly
     * revoked permissions reflect honestly in the badge.
     *
     * Blocking: this is synchronous host file I/O. Every caller must hop off
     * its own thread first (`withContext(Dispatchers.IO) { … }`) —
     * `readonly_mount_write_guard.py` enforces that mechanically
     * [deep-audit-1025 ISSUE-08].
     */
    fun probeWritable(hostPath: String): Boolean {
        val dir = File(hostPath)
        if (!dir.isDirectory) return false
        val probe = File(dir, ".minis-probe-${UUID.randomUUID()}")
        return runCatching {
            probe.outputStream().use { it.write(0) }
            true
        }.onFailure { e ->
            AppLogger.warning(TAG, "probeWritable: $hostPath not writable: ${e.message}")
        }.getOrDefault(false).also {
            runCatching { probe.delete() }
        }
    }

    // [dv20-A4-A06] The volume-root resolver used to live here as a private
    // method, twinned by a drifted copy in `PRootKernel`. It is now the single
    // top-level [resolveVolumeRoot] at the bottom of this file, shared by both
    // readers — see [resolveVolumeRootDecision] for the union rule.

    /**
     * [E2-F12] Read the persisted list. A failure is NOT "zero mounts" — see
     * [diskUnreadable]: the in-memory list is left untouched and the file is
     * quarantined before the next write instead of being overwritten blind.
     */
    private fun loadFromDisk() {
        if (!storeFile.isFile) return
        runCatching {
            val text = storeFile.readText()
            val parsed = JSON.decodeFromString<List<Entry>>(text)
            _entries.value = parsed
        }.onFailure { e ->
            diskUnreadable = true
            AppLogger.warning(
                TAG,
                "loadFromDisk: ${storeFile.name} unreadable (${e.javaClass.simpleName}: ${e.message}) — " +
                    "keeping ${_entries.value.size} in-memory mount(s); the file will be moved aside, not overwritten",
            )
        }
    }

    /**
     * [E2-F12] Persist [list]. Two changes from a bare `writeText`:
     *
     *   1. If the last read failed ([diskUnreadable]) and the file is still
     *      there, the unreadable bytes are moved aside
     *      ([mountStoreQuarantineName]) before anything is written, so the
     *      user's original file survives for recovery instead of being
     *      replaced by a list we could not have read from it. If neither rename
     *      nor copy succeeds the write is refused outright — writing anyway is
     *      exactly the data loss being prevented.
     *   2. The write itself is temp + rename, so a kill or a full disk can no
     *      longer leave a truncated JSON behind (that truncation is how the file
     *      got unreadable in the first place, and it also silently dropped the
     *      mounts on the next launch).
     */
    private suspend fun saveToDisk(list: List<Entry>) = withContext(Dispatchers.IO) {
        if (diskUnreadable && storeFile.isFile) {
            val aside = File(storeFile.parentFile, mountStoreQuarantineName(storeFile.name, System.currentTimeMillis()))
            val preserved = runCatching { storeFile.renameTo(aside) }.getOrDefault(false) ||
                runCatching {
                    storeFile.copyTo(aside, overwrite = true)
                    true
                }.getOrDefault(false)
            if (!preserved) {
                AppLogger.error(
                    TAG,
                    "saveToDisk: ${storeFile.name} is unreadable and could not be moved aside — " +
                        "refusing to write ${list.size} mount(s) over it",
                )
                return@withContext
            }
            AppLogger.warning(TAG, "saveToDisk: unreadable ${storeFile.name} moved aside to ${aside.name}")
            diskUnreadable = false
        }
        runCatching {
            val text = JSON.encodeToString(list)
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(storeFile)) {
                // Some filesystems refuse rename onto an existing path.
                storeFile.writeText(text)
                tmp.delete()
            }
        }.onFailure { e ->
            AppLogger.warning(TAG, "saveToDisk: write failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun sanitizeName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "." || trimmed == "..") return ""
        if (trimmed.contains('/') || trimmed.contains('\u0000')) return ""
        return trimmed.take(MAX_NAME_LENGTH)
    }

    companion object {
        const val MAX_MOUNTS = 10

        /**
         * Upper bound for a mount folder name. [sanitizeName] truncates to
         * this, so the UI must reject longer input up-front: a name that only
         * gets shortened here can collide with an existing mount, and
         * [rename]/[add] then return false while the caller shows no error.
         */
        const val MAX_NAME_LENGTH = 64
        private const val TAG = "MountedFolders"
        private const val EXTERNALSTORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * [E2-F12] Name an unreadable `mounted-folders.json` is moved aside to before a
 * save overwrites it. Pure (name shape only) so a JVM probe can pin the exact
 * form the store looks for after a failed load.
 */
internal fun mountStoreQuarantineName(storeName: String, nowMs: Long): String =
    "$storeName.unreadable-$nowMs"

/**
 * [dv20-A4-A06] One candidate volume for [resolveVolumeRootDecision].
 * [directory] carries the API 30+ `StorageVolume.directory` path; null below
 * API 30 (the platform does not expose it there) or when the volume has no
 * directory.
 */
internal data class VolumeRootCandidate(val uuid: String?, val directory: String?)

/**
 * [dv20-A4-A06] Pure core of SAF volume-root resolution — the single place the
 * decision is made, and the single place the `< API 30` fallback exists.
 *
 * Before this, two same-semantics implementations had drifted apart:
 * `PRootKernel.resolveVolumeRoot` stopped at `volume.directory` (API 30+) and
 * returned null for removable volumes on API 26–29, while this store's private
 * copy carried the `File("/storage/<uuid>")` fallback. A folder mounted on a
 * removable volume therefore resolved here (and landed in
 * `Entry.resolvedHostPath`) but not in `PRootKernel.applyMountedFoldersSnapshot`,
 * which re-resolved from scratch — the mount was silently dropped from
 * `bindMounts` and never got its read-only guard.
 *
 * The decision is the UNION of both former shapes — the fallback wins over "no
 * answer": primary/empty → primary dir; API 30+ volume match with a non-null
 * directory → that path; everything else (API 26–29 match, API 30+ match whose
 * directory is null, unknown volume) → `/storage/<uuid>` when it is a real
 * directory, else null.
 *
 * Android-free on purpose: [resolveVolumeRoot] supplies the platform edges and
 * the JVM probe pins this contract without an Android runtime.
 */
internal fun resolveVolumeRootDecision(
    volumeId: String,
    sdkInt: Int,
    primaryDir: String?,
    volumes: List<VolumeRootCandidate>,
    fallbackIsDirectory: (String) -> Boolean,
): String? {
    if (volumeId.equals("primary", ignoreCase = true) || volumeId.isEmpty()) {
        return primaryDir
    }
    if (sdkInt >= 24) { // StorageManager.storageVolumes
        for (candidate in volumes) {
            if (candidate.uuid?.equals(volumeId, ignoreCase = true) == true) {
                if (sdkInt >= 30) { // StorageVolume.getDirectory
                    candidate.directory?.let { return it }
                }
            }
        }
    }
    // < API 30, and API 30+ volumes whose directory is null: walk the legacy
    // mount point. Removable volumes expose /storage/<uuid> there even though
    // StorageVolume.directory is unavailable below API 30.
    val fallback = "/storage/$volumeId"
    return if (fallbackIsDirectory(fallback)) fallback else null
}

/**
 * [dv20-A4-A06] The one volume-root resolver: platform edges for
 * [resolveVolumeRootDecision]. `PRootKernel` and this store both call it, so
 * the two readers of a mount's host path cannot disagree again.
 */
internal fun resolveVolumeRoot(context: Context, volumeId: String): String? {
    val primaryDir = Environment.getExternalStorageDirectory()?.absolutePath
    val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        sm?.storageVolumes?.map { volume ->
            VolumeRootCandidate(
                uuid = volume.uuid,
                directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath
                } else {
                    null
                },
            )
        }.orEmpty()
    } else {
        emptyList()
    }
    return resolveVolumeRootDecision(
        volumeId = volumeId,
        sdkInt = Build.VERSION.SDK_INT,
        primaryDir = primaryDir,
        volumes = volumes,
        fallbackIsDirectory = { File(it).isDirectory },
    )
}

/**
 * SAF helper — call [buildPickerIntent] from an `ActivityResultContract`,
 * then pipe the resulting Uri back through [handlePickerResult] to persist
 * the permission grant before adding it to [MountedFoldersStore].
 */
object SafMountHelper {
    fun buildPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Take a persistable permission on the picked tree URI so subsequent
     * app launches can still access it without another picker round-trip.
     * Returns true on success.
     */
    fun handlePickerResult(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    /** Sugar over [DocumentsContract.getTreeDocumentId] for display purposes. */
    fun treeDisplayPath(uri: Uri): String =
        DocumentsContract.getTreeDocumentId(uri)
}
