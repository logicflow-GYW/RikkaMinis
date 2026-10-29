package com.rikkaminis.app.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.rikkaminis.app.MinisApp
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [feat/auto-backup-external-mirror] The external copy under
 * `Download/RikkaMinis/backup/`.
 *
 * Why this exists (2026-10-29 incident): the daily auto backup lives in
 * `filesDir/backup-autos/` — the SAME `filesDir` as the data it protects. When
 * that directory was rebuilt mid-morning, the working copy and its only backup
 * went together. A second copy owned by MediaStore, in a user-visible folder on
 * the shared volume, survives that failure mode, an uninstall, and "clear app
 * data" — and the user can pull it out with a file manager without this app at
 * all.
 *
 * Layout (user-approved 2026-10-29):
 * ```
 * Download/RikkaMinis/backup/
 *   mirror/current/<relPath>        newest state, one copy of everything selected
 *   mirror/previous/<stamp>/<relPath>  only the bytes overwritten at that sync
 *   mirror/manifest.json            what [MirrorPlan] diffs against
 *   auto/rikkaminis-auto-<stamp>.json  the daily full snapshot, newest KEEP_AUTO
 * ```
 *
 * `current` answers "the thing I wrote this morning is gone" (copy back, one
 * step). `previous` answers "I wrote it into a broken state" — the mirror would
 * otherwise faithfully carry the broken version. `auto/` is the only copy of
 * provider/MCP/scheduled-task configuration that exists outside `filesDir`.
 *
 * All decisions live in [MirrorPlan]/[MirrorScope] (pure, JVM-tested). This
 * file is the I/O shell: it resolves a [MirrorTarget] and applies the plan.
 */
object MirrorStore {

    private const val TAG = "MirrorStore"

    /** MediaStore-relative root. A sibling of the app's filesDir by design. */
    const val ROOT_REL = "Download/RikkaMinis/backup"
    const val MIRROR_REL = "$ROOT_REL/mirror"
    const val CURRENT_REL = "$MIRROR_REL/current"
    const val PREVIOUS_REL = "$MIRROR_REL/previous"
    const val AUTO_REL = "$ROOT_REL/auto"

    /** Daily snapshots kept under `auto/` — 7 days of history, ~18 MB each on
     *  the measured install (2026-10-29). Independent from the local
     *  `AUTO_BACKUP_KEEP = 3`: local is a fast rollback, external is the copy
     *  that survives the app's own storage. */
    const val KEEP_AUTO = 7

    private const val PREFS = "backup_prefs"
    private const val KEY_ENABLED = "external_mirror_enabled"
    private const val KEY_LAST_MS = "external_mirror_last_ms"
    private const val KEY_RESULT = "external_mirror_result"

    /** Narrow helper list of the three trees, in the mirror's own namespace. */
    private val SOURCES = listOf(
        "memory" to MirrorScope::selectMemory,
        "skills" to MirrorScope::selectSkills,
        "shared" to MirrorScope::selectShared,
    )

    // ------------------------------------------------------------- settings

    /**
     * Whether the mirror runs.
     *
     * Default TRUE — deliberately unlike [AutoBackupManager.isEnabled] (false,
     * and the reason the 2026-10-29 copy never existed). This is a
     * non-destructive, bounded write (text documents the user authored, ~38 MB
     * total, 1810 files) into a folder named after the app. The whole point is
     * redundancy that exists before the user thinks about it; an opt-in default
     * reintroduces exactly the failure the feature was built for. It stays
     * switchable off in Backup settings.
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun lastSyncMs(context: Context): Long = prefs(context).getLong(KEY_LAST_MS, 0L)

    /** Human-readable outcome of the last run, for the settings row. */
    fun lastResult(context: Context): String? = prefs(context).getString(KEY_RESULT, null)

    /**
     * How many files the next sync would touch (writes + deletions), or null
     * when the target is unreachable.
     *
     * Read-only and lock-free on purpose: §5 of the behaviour spec makes
     * "freshness + pending count" the operator's signal that the apparatus is
     * alive, so it must answer even while a sync is in flight. It shares the
     * exact selection and diff the writer uses, so the number cannot drift
     * from what `syncNow` would actually do.
     */
    suspend fun pendingCount(context: Context): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val app = context.applicationContext
            val target = openTarget(app) ?: return@withContext null
            val manifestRel = "$MIRROR_REL/${MirrorPlan.MANIFEST_NAME}"
            val manifest = MirrorPlan.decodeManifest(target.read(manifestRel)?.toString(Charsets.UTF_8))
            val current = ArrayList<MirrorPlan.Entry>(2048)
            for ((dir, pick) in SOURCES) {
                for (f in pick(File(app.filesDir, "minis-global/$dir"))) {
                    current += MirrorPlan.Entry(f.relPath, f.size, f.modifiedMs)
                }
            }
            val diff = MirrorPlan.diff(manifest?.entries ?: emptyList(), current)
            diff.toWrite.size + diff.removed.size
        }.getOrNull()
    }

    /** "MM-dd HH:mm" of the last WRITTEN sync, or null before the first one.
     *  Mirrors [AutoBackupManager.lastRunLabel]: the label is read on IO by the
     *  entry LaunchedEffect, never inside remember. */
    fun lastSyncLabel(context: Context): String? {
        val ms = lastSyncMs(context)
        if (ms <= 0L) return null
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------- outcomes

    sealed class Outcome {
        /** User switched it off. */
        object Disabled : Outcome()

        /** A sync is already running (single-flight). */
        object Busy : Outcome()

        /** Nothing changed since the last written manifest. */
        object NoChanges : Outcome()

        /** Changed, but inside [MirrorPlan.WRITE_COALESCE_MS]. */
        object Throttled : Outcome()

        /** No usable target (no external volume, API guard failed). */
        object Unavailable : Outcome()

        data class Synced(
            val written: Int,
            val deleted: Int,
            val archived: Int,
            val previousPoints: Int,
            val totalFiles: Int,
        ) : Outcome() {
            val summary: String
                get() = "ok ${written}w/${deleted}d/${archived}a of $totalFiles"
        }

        data class Failed(val reason: String) : Outcome()
    }

    // ------------------------------------------------------------- triggers

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Fire-and-forget sync, single-flight. Safe to call from stream settlement
     * and from the foreground beat: the second caller returns immediately, and
     * [MirrorPlan.shouldWrite] re-checks the throttle inside the lock so a
     * burst of settlements costs one stat walk, not N.
     */
    fun runAsync(context: Context): Job? {
        val app = context.applicationContext as? MinisApp ?: return null
        if (!isEnabled(app)) return null
        if (inFlight.get()) return null
        return app.applicationScope.launch(Dispatchers.IO) {
            try {
                val outcome = syncNow(app)
                if (outcome is Outcome.Failed) Log.w(TAG, "mirror failed: ${outcome.reason}")
            } catch (t: Throwable) {
                Log.w(TAG, "mirror failed: ${t.message}")
            }
        }
    }

    // --------------------------------------------------------------- target

    /**
     * MediaStore target. Every path is a `Downloads` row whose RELATIVE_PATH is
     * the mirror directory and whose DISPLAY_NAME is the last segment — the
     * folder tree the user sees in a file manager is produced by MediaStore
     * from those two columns.
     *
     * Overwrite uses `openOutputStream(uri, "wt")` rather than delete+insert:
     * MediaStore would rename a same-name insert to `foo (1).md` instead of
     * replacing (measured behaviour, not a guess), which would silently fork the
     * mirror. New files insert with IS_PENDING=1 and are published only after
     * the bytes are on disk, so a killed write cannot leave a half file under a
     * visible name.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    internal class MediaStoreTarget(private val context: Context) : MirrorTarget {

        private val resolver get() = context.contentResolver
        private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        private fun split(relPath: String): Pair<String, String> {
            val i = relPath.lastIndexOf('/')
            return if (i <= 0) "" to relPath else relPath.substring(0, i) to relPath.substring(i + 1)
        }

        private fun dirWithSlash(dir: String): String = if (dir.endsWith("/")) dir else "$dir/"

        private fun findUri(relPath: String): Uri? {
            val (dir, name) = split(relPath)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val args = arrayOf(dirWithSlash(dir), name)
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { c -> if (c.moveToFirst()) return Uri.withAppendedPath(collection, c.getString(0)) }
            return null
        }

        override fun read(relPath: String): ByteArray? = runCatching {
            val uri = findUri(relPath) ?: return null
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

        override fun write(relPath: String, bytes: ByteArray): Boolean = runCatching {
            val existing = findUri(relPath)
            if (existing != null) {
                return overwrite(existing, relPath, bytes)
            }
            val (dir, name) = split(relPath)
            val mime = mimeFor(name)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dirWithSlash(dir))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                    resolver.delete(uri, null, null)
                    return false
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                true
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }.getOrDefault(false)

        /**
         * [R5-A / F-01] Replace the bytes of an existing row.
         *
         * `"wt"` truncates at OPEN, so a failed write used to leave a 0-byte or
         * half-written document under a visible name in the user's Downloads —
         * and inside the `previous/` point-spacing window there is no archived
         * copy to fall back to (accepted trade, see runLocked). "Non-atomic
         * overwrite that fails" therefore has no recovery: the ROW is what is
         * wrong, so the row is dropped. The next sync takes the IS_PENDING
         * insert path (which cannot expose a partial file) and rebuilds it from
         * the source tree.
         *
         * Deliberately NOT delete-then-insert up front: a same-name insert makes
         * MediaStore rename the new row to `foo (1).md` (measured behaviour, see
         * the class doc), forking the mirror on every single overwrite.
         *
         * The re-diff hazard — dropping the row while the manifest still
         * describes the old bytes — cannot swallow the retry: a file that
         * reaches this call is in `diff.toWrite`, i.e. the manifest does NOT
         * match the source, so the next run classifies it as changed again.
         */
        private fun overwrite(existing: Uri, relPath: String, bytes: ByteArray): Boolean {
            val failure: Throwable? = try {
                val stream = resolver.openOutputStream(existing, "wt")
                    ?: return dropHalfWritten(existing, relPath, "no output stream")
                stream.use { it.write(bytes) }
                null
            } catch (t: Throwable) {
                t
            }
            return if (failure == null) true else dropHalfWritten(existing, relPath, failure.message)
        }

        private fun dropHalfWritten(existing: Uri, relPath: String, reason: String?): Boolean {
            Log.w(
                TAG,
                "overwrite of $relPath failed ($reason) — dropping the truncated row so the " +
                    "next sync rebuilds it through IS_PENDING",
            )
            runCatching { resolver.delete(existing, null, null) }
            return false
        }

        override fun delete(relPath: String): Boolean = runCatching {
            val uri = findUri(relPath) ?: return true
            resolver.delete(uri, null, null) > 0
        }.getOrDefault(false)

        override fun deleteTree(relDirPrefix: String): Boolean = runCatching {
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("$relDirPrefix%")
            val uris = ArrayList<Uri>()
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) uris.add(Uri.withAppendedPath(collection, c.getString(idCol)))
                }
            var ok = true
            for (u in uris) if (resolver.delete(u, null, null) <= 0) ok = false
            ok
        }.getOrDefault(false)

        override fun list(relDirPrefix: String): List<String> = runCatching {
            // RELATIVE_PATH is per-directory, so a prefix search over it returns
            // only direct children of one folder level — walk is not required
            // for the mirror (flat topic dirs), but supplied for completeness by
            // querying the exact dir plus its one-level subdirs lazily.
            val out = ArrayList<String>()
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                ),
                selection,
                arrayOf("$relDirPrefix%"),
                null,
            )?.use { c ->
                val dirCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val dir = c.getString(dirCol) ?: continue
                    val name = c.getString(nameCol) ?: continue
                    out.add(dir.removePrefix(if (relDirPrefix.endsWith("/")) relDirPrefix else "$relDirPrefix/") + name)
                }
            }
            out
        }.getOrDefault(emptyList())

        private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "csv" -> "text/csv"
            "xml" -> "text/xml"
            "html" -> "text/html"
            else -> "text/plain"
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreTarget(context: Context): MirrorTarget = MediaStoreTarget(context)

    /** Resolve the I/O target for this device. */
    internal fun openTarget(context: Context): MirrorTarget? {
        val app = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaStoreTarget(app)
            } else {
                // API 26–28: public Downloads needs a runtime permission we do
                // not want to request for a background sync, so the fallback is
                // the app-specific external dir (still outside filesDir, still
                // surviving a reinstall's data wipe of internal storage).
                val base = app.getExternalFilesDir(null) ?: return null
                FileTarget(File(base, "RikkaMinis/backup"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no mirror target: ${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------- sync

    /**
     * One mirror sync (IO thread). Single-flight; records its outcome in prefs
     * so the settings row can show freshness without another walk.
     *
     * [force] skips the coalescing window and nothing else — never the enabled
     * check, never the single-flight guard. Its only caller is the status row's
     * retry action, which exists only for unhealthy states: waiting out another
     * coalescing window is the wrong answer for a mirror that is already behind
     * (2026-10-29 silent-mirror revision — the human-initiated "sync now"
     * button is gone because a mirror of app-generated documents needs no
     * human-initiated sync; the retry is an anomaly action, not a control).
     * It does NOT bypass the `previous/` point spacing: rollback history
     * density is a property of time, not of who asked.
     */
    suspend fun syncNow(context: Context, force: Boolean = false): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!isEnabled(app)) return@withContext Outcome.Disabled
        if (!inFlight.compareAndSet(false, true)) return@withContext Outcome.Busy
        try {
            val target = openTarget(app) ?: return@withContext Outcome.Unavailable.also { record(app, "unavailable") }
            val outcome = runLocked(app, target, force)
            record(app, outcomeText(outcome))
            outcome
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed: ${t.message}")
            val failed = Outcome.Failed(t.message ?: t.javaClass.simpleName)
            record(app, outcomeText(failed))
            failed
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runLocked(app: Context, target: MirrorTarget, force: Boolean): Outcome {
        val now = System.currentTimeMillis()
        val manifestRel = "$MIRROR_REL/${MirrorPlan.MANIFEST_NAME}"
        val manifest = MirrorPlan.decodeManifest(target.read(manifestRel)?.toString(Charsets.UTF_8))

        val selection = ArrayList<MirrorScope.MirrorFile>(2048)
        for ((dir, pick) in SOURCES) {
            pick(File(app.filesDir, "minis-global/$dir")).let(selection::addAll)
        }
        val currentEntries = selection.map { MirrorPlan.Entry(it.relPath, it.size, it.modifiedMs) }
        val diff = MirrorPlan.diff(manifest?.entries ?: emptyList(), currentEntries)
        if (!force && !MirrorPlan.shouldWrite(manifest?.syncedAtMs, now, diff.hasChanges)) {
            return if (diff.hasChanges) Outcome.Throttled else Outcome.NoChanges
        }

        // A `previous/` point is created only when something is being
        // overwritten or deleted — a pure addition (first run, new file) has no
        // old bytes to preserve, and minting an empty point would consume a
        // rotation slot for nothing.
        //
        // Spacing is independent of the write window (2026-10-29): writes
        // coalesce at 60 s, points at 10 minutes, so 3 points still span >= 30
        // minutes of rollback history. An overwrite inside the spacing window
        // therefore does NOT archive its old bytes — accepted trade, the
        // selected trees are app-generated documents (see MirrorPlan).
        val point = if ((diff.changed.isNotEmpty() || diff.removed.isNotEmpty()) &&
            MirrorPlan.shouldMintPoint(manifest?.previousPoints ?: emptyList(), now)
        ) {
            BackupRotation.stamp(now)
        } else null

        // 1. Archive OLD bytes before touching `current`.
        var archived = 0
        if (point != null) {
            val olds = diff.changed.map { it.relPath } + diff.removed
            for (rel in olds) {
                val bytes = target.read(currentPath(rel)) ?: continue
                val ok = target.write(previousPath(point, rel), bytes)
                if (!ok) throw IOException("archive failed: $rel")
                archived++
            }
        }

        // 2. Write new/changed bytes into `current`. Source files come from the
        //    same walk, so a file that vanished between walk and read is skipped
        //    rather than failing the whole sync (it will be picked up as
        //    `removed` next time, or as `changed` if it reappears with a new
        //    mtime).
        val byRel = selection.associateBy { it.relPath }
        var written = 0
        // [R5-A / F-02] Files we could not read this round. They are NOT synced,
        // so step 5 must not record their new (size, mtime) — see
        // MirrorPlan.manifestEntries for why that turns a transient read error
        // into a permanent, invisible hole in the mirror.
        val skipped = LinkedHashSet<String>()
        for (e in diff.toWrite) {
            // No `continue` inside an inline lambda (`?: run { … }`): the project
            // compiles with a language version below 2.2, where that is a hard
            // error (`break continue in inline lambdas`).
            val src = byRel[e.relPath]?.file
            val bytes = src?.let { runCatching { it.readBytes() }.getOrNull() }
            if (src == null || bytes == null) {
                skipped += e.relPath
                continue
            }
            if (!target.write(currentPath(e.relPath), bytes)) {
                throw IOException("write failed: ${e.relPath}")
            }
            written++
        }
        if (skipped.isNotEmpty()) {
            Log.w(TAG, "mirror skipped ${skipped.size} unreadable source file(s): ${skipped.take(3)}")
        }

        // 3. Source deletions propagate to `current` (the old bytes already
        //    landed in `previous` above).
        var deleted = 0
        for (rel in diff.removed) {
            if (!target.delete(currentPath(rel))) throw IOException("delete failed: $rel")
            deleted++
        }

        // 4. Rotate `previous/`: keep the newest PREVIOUS_KEEP points. The rule
        //    (dedupe included) lives in MirrorPlan.rotatePoints — the same call
        //    nextManifest makes, so the listed points and the deleted ones can
        //    never come from two different arithmetics.
        val rotation = MirrorPlan.rotatePoints(manifest?.previousPoints ?: emptyList(), point)
        for (v in rotation.victims) {
            target.deleteTree("$PREVIOUS_REL/$v/")
        }

        // 5. Manifest last: a crash before this line leaves the old manifest,
        //    so the next run re-diffs and re-writes the same bytes. The reverse
        //    order would let a crash claim "synced" for bytes that never landed.
        val nextEntries = MirrorPlan.manifestEntries(
            current = currentEntries,
            previous = manifest?.entries ?: emptyList(),
            skipped = skipped,
        )
        val next = MirrorPlan.Manifest(now, nextEntries, rotation.kept)
        if (!target.write(manifestRel, MirrorPlan.encodeManifest(next).toByteArray(Charsets.UTF_8))) {
            throw IOException("manifest write failed")
        }

        return Outcome.Synced(
            written = written,
            deleted = deleted,
            archived = archived,
            previousPoints = rotation.kept.size,
            totalFiles = currentEntries.size,
        )
    }

    private fun currentPath(relPath: String) = "$CURRENT_REL/$relPath"

    private fun previousPath(point: String, relPath: String) = "$PREVIOUS_REL/$point/$relPath"

    private fun record(app: Context, text: String) {
        val p = prefs(app)
        val ok = text.startsWith("ok ") || text == "no-changes"
        // [R5-A / C-01④ + C-03②] A run that never attempted anything
        // (`throttled`, `busy`) must not overwrite a recorded failure: the
        // settings row's only failure channel is this string, so overwriting it
        // made the alert and its retry button disappear with the mirror still
        // broken. See MirrorPlan.nextRecordedOutcome for the exact rule.
        val stored = MirrorPlan.nextRecordedOutcome(p.getString(KEY_RESULT, null), text)
        p.edit()
            .putLong(KEY_LAST_MS, if (ok) System.currentTimeMillis() else p.getLong(KEY_LAST_MS, 0L))
            .putString(KEY_RESULT, stored)
            .apply()
    }

    private fun outcomeText(o: Outcome): String = when (o) {
        is Outcome.Synced -> o.summary
        Outcome.NoChanges -> "no-changes"
        Outcome.Disabled -> "disabled"
        Outcome.Busy -> "busy"
        Outcome.Throttled -> "throttled"
        Outcome.Unavailable -> "unavailable"
        is Outcome.Failed -> "failed: ${o.reason}"
    }

    // ------------------------------------------------------- auto snapshot

    /**
     * Copy the daily auto-backup document (already written atomically to
     * `filesDir/backup-autos/`) into `auto/`, then keep the newest [KEEP_AUTO].
     *
     * Called from [AutoBackupManager.runLocked] after local rotation. Failure is
     * swallowed by the caller's best-effort contract: the local copy exists, so
     * a failed external copy only means the offsite layer lags a day.
     */
    fun mirrorAutoSnapshot(context: Context, local: File): Boolean {
        if (!local.isFile) return false
        return try {
            val target = openTarget(context) ?: return false
            val name = local.name
            val stampMs = BackupRotation.stampMillis(name, AutoBackupManager.LOCAL_FILE_PREFIX)
                ?: local.lastModified()
            val bytes = local.readBytes()
            if (!target.write("$AUTO_REL/$name", bytes)) return false

            // Rotate by NAME stamp (never mtime): a clock-rolled-back run would
            // otherwise look like the oldest copy and be deleted right after it
            // was written — the same defect B-12 fixed for the local rotation.
            //
            // [R5-A / F-05] The rotation rule itself is in
            // MirrorPlan.autoSnapshotVictims, which reserves this copy's slot:
            // `drop(KEEP_AUTO)` here made the freshly written `name` the FIRST
            // victim whenever a rolled-back clock gave it an old-looking name,
            // while the log line below still reported success.
            val all = target.list("$AUTO_REL/")
                .mapNotNull { rel ->
                    val n = rel.substringAfterLast('/')
                    BackupRotation.stampMillis(n, AutoBackupManager.LOCAL_FILE_PREFIX)?.let { n to it }
                }
            val victims = MirrorPlan.autoSnapshotVictims(all, name, KEEP_AUTO)
            for (v in victims) target.delete("$AUTO_REL/$v")
            Log.i(TAG, "auto snapshot mirrored: $name (keep $KEEP_AUTO, removed ${victims.size})")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "auto snapshot mirror failed: ${t.message}")
            false
        }
    }
}
