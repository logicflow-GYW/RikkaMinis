package com.rikkaminis.app.backup

import android.content.Context
import android.os.Build
import android.os.Environment
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

    /** Path inside the shared volume. A sibling of the app's filesDir by design. */
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
     * Whether a target exists right now, cheaply — no disk walk.
     *
     * The settings row needs the answer every minute, and [pendingCount] is a
     * ~1800-stat walk. The only way the target can disappear is a revoked
     * grant, so the grant is the whole question. Reads exactly the same
     * [MirrorPlan.targetKind] call [openTarget] does, so the two cannot
     * disagree about which devices can write.
     */
    fun isTargetReachable(context: Context): Boolean {
        val app = context.applicationContext
        return runCatching { currentTargetKind(app) }.getOrNull() != null
    }

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

    /** Stored layout version. 1 = the MediaStore-backed tree (retired), 2 =
     *  plain files. A missing pref means "this device has not migrated yet",
     *  which is exactly the case that needs the one-time move below. */
    private const val KEY_LAYOUT = "external_mirror_layout"
    private const val LAYOUT_PLAIN_FILES = 2

    /** The v1 tree inside `backup/`, and where it is parked (never deleted). */
    private const val LEGACY_TREE = "mirror"
    private const val LEGACY_PARK = "legacy-mirror-v1"

    /**
     * The public Downloads root, as a plain path.
     *
     * `getExternalStorageDirectory` is deprecated on R+ but still answers the
     * primary shared volume, which is exactly what this constant names. The
     * scoped-storage replacement (MediaStore) is the API this file just stopped
     * using, so the suppression stays local and explained rather than
     * reintroducing the thing that broke the mirror.
     */
    @Suppress("DEPRECATION")
    private fun publicBase(): File = File(Environment.getExternalStorageDirectory(), ROOT_REL)

    private fun appExternalBase(app: Context): File? =
        app.getExternalFilesDir(null)?.let { File(it, "RikkaMinis/backup") }

    /** The one place the two grants are read, so the row and the writer can
     *  never disagree about which devices can write. */
    private fun currentTargetKind(app: Context): MirrorPlan.TargetKind? =
        MirrorPlan.targetKind(
            sdkInt = Build.VERSION.SDK_INT,
            allFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager(),
            legacyWriteGranted = app.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )

    /**
     * Resolve the I/O target for this device.
     *
     * [MirrorPlan.targetKind] owns the API/permission decision (pure, pinned by
     * the JVM harness). What stays here is the part that needs a Context: the
     * two base directories and reading the grant. It performs NO writes:
     * `pendingCount` calls it on the settings row's read path, so anything that
     * mutates the target belongs in `syncNow` instead.
     *
     * A device that cannot reach the public folder reports NO target instead of
     * falling back to an app-private directory. That fallback still exists for
     * API 26–28 (where public Downloads needs a runtime permission a background
     * sync should not request); on 29+ the visible folder IS the feature, so
     * quietly writing somewhere the user cannot see would rebuild the original
     * defect — a copy that exists, but not where the user was told it is.
     */
    internal fun openTarget(context: Context): MirrorTarget? {
        val app = context.applicationContext
        return try {
            val kind = currentTargetKind(app)
            when (kind) {
                MirrorPlan.TargetKind.PUBLIC_DOWNLOADS -> FileTarget(publicBase())
                MirrorPlan.TargetKind.APP_EXTERNAL -> appExternalBase(app)?.let { FileTarget(it) }
                null -> {
                    Log.w(TAG, "no mirror target: all-files access not granted")
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "no mirror target: ${t.message}")
            null
        }
    }

    /**
     * One-time move off the v1 (MediaStore) layout.
     *
     * v1 addressed every file as a `Downloads` row whose DISPLAY_NAME was the
     * logical name. MediaProvider rewrote `memory/x.md` to `x.md.txt` (measured
     * on device), so the tree on disk is not the tree the manifest describes —
     * and the manifest's records are all intact, so a fresh sync would diff
     * clean and decide there is nothing to do. The stale files would stay
     * forever, next to the new correct ones. The tree therefore has to be
     * retired, not reused.
     *
     * Files are MOVED ASIDE, never deleted. `current/` mirrors live files, but
     * a file deleted from the source since it was mirrored exists only here;
     * 49 MB of maybe-only-copy is not something a migration should discard
     * unattended. The parked directory says what it is, is never touched again,
     * and removing it is a one-tap decision for the user.
     *
     * Idempotent: every step answers "already done" without side effects, and
     * the layout pref is written only after the legacy tree is out of the way,
     * so a run killed midway resumes instead of leaving two live trees.
     */
    private fun migrateLegacyLayout(app: Context): Boolean {
        val prefs = prefs(app)
        if (prefs.getInt(KEY_LAYOUT, 1) >= LAYOUT_PLAIN_FILES) return true

        val base = publicBase()
        val legacy = File(base, LEGACY_TREE)

        // 1. Park the tree FIRST, before touching any row.
        //
        // Order matters and it is not the obvious one: deleting a MediaStore row
        // also deletes the file it points at. Doing that first would destroy the
        // very files this migration exists to preserve, and then the park below
        // would find nothing to park. Moving the directory aside makes every
        // legacy row point at a path that no longer exists, so step 2 removes
        // only index entries.
        //
        // Parked, not deleted: `current/` mirrors live files, but a file deleted
        // from the source since it was mirrored exists only here. 49 MB of
        // maybe-only-copy is not something a migration should discard
        // unattended — the directory says what it is and one file-manager tap
        // removes it.
        val park: File?
        if (!legacy.exists()) {
            park = null
        } else {
            val dest = uniqueParkDir(base)
            if (!legacy.renameTo(dest)) {
                // Leave the rows alone too: this run changes nothing, and the
                // next sync retries. Racing it would trade a retry for data.
                Log.w(TAG, "legacy mirror tree is still at $legacy — retrying next sync")
                return false
            }
            park = dest
        }

        // 2. Drop the v1 index entries. They now describe paths that no longer
        //    exist; leaving them would put ghost rows back on the exact paths the
        //    plain-file writer is about to reuse — a UNIQUE-constraint fight with
        //    MediaProvider, which is what the 2026-11-14 incident actually was.
        val rows = deleteLegacyRows(app)

        // 3. Only now is the device on v2. A run killed before this line simply
        //    re-enters: the tree is already parked (`legacy.exists()` false), so
        //    the retry goes straight to steps 2–3.
        prefs.edit().putInt(KEY_LAYOUT, LAYOUT_PLAIN_FILES).apply()
        Log.i(
            TAG,
            "mirror layout v2: removed $rows legacy MediaStore row(s), " +
                (park?.let { "parked the old tree at $it (safe to delete from a file manager)" }
                    ?: "no legacy tree present"),
        )
        return true
    }

    private fun uniqueParkDir(base: File): File {
        val first = File(base, LEGACY_PARK)
        if (!first.exists()) return first
        return File(base, "$LEGACY_PARK-${BackupRotation.stamp(System.currentTimeMillis())}")
    }

    /**
     * Delete the v1 `Downloads` rows under the **parked tree only**. Returns how
     * many were removed (0 on any device that never had them, incl. API 26–28).
     *
     * The scope deliberately stops at `mirror/`: v1 also wrote the daily full
     * snapshots under `auto/` through MediaStore, and deleting a row deletes the
     * file it points at. Those files are not parked, so their rows must stay —
     * the row is the only index left describing them.
     */
    private fun deleteLegacyRows(app: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        return runCatching {
            app.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                selection,
                arrayOf("$MIRROR_REL/%"),
            )
        }.getOrDefault(0)
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
            // A legacy tree we could not move aside means the v1 paths are
            // still live on top of the tree v2 writes into — and the next
            // run's park would move our own fresh files into the "legacy"
            // pile. Refuse this round; the next sync retries the move.
            if (!migrateLegacyLayout(app)) {
                val blocked = Outcome.Failed("legacy mirror tree could not be moved aside")
                record(app, outcomeText(blocked))
                return@withContext blocked
            }
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
            val rel = currentPath(e.relPath)
            if (!target.write(rel, bytes)) {
                throw IOException("write failed: ${e.relPath}")
            }
            // Read back what we just wrote. `write()` returning true only means
            // the bytes reached a stream — it cannot notice a writer that puts
            // them somewhere else, and that is not hypothetical: the retired
            // MediaStore target reported success while MediaProvider had renamed
            // the row, so `current/` served 16-day-old bytes with a healthy row
            // and pending 0 (2026-11-14). One read per changed file, per sync.
            if (!mirrorBytesMatch(target, rel, bytes)) {
                throw IOException("write not readable back: ${e.relPath}")
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

    /**
     * The bytes on disk are the bytes we asked to write.
     *
     * `write()` is an interface method with a boolean contract; this is the only
     * place that can prove it kept it. It cannot notice a writer that puts the
     * bytes somewhere else than the name it was given — and that is not
     * hypothetical: the retired MediaStore target reported success while
     * MediaProvider had renamed the row, so `current/` served 16-day-old bytes
     * with a healthy row and pending 0 (2026-11-14).
     *
     * One read per actually-changed file, once per sync. The selected trees are
     * text documents (38 MB total across ~1800 files), so a full rebuild costs
     * one extra pass over the same bytes the write just produced.
     */
    private fun mirrorBytesMatch(target: MirrorTarget, relPath: String, expected: ByteArray): Boolean {
        val readBack = target.read(relPath) ?: return false
        return readBack.size == expected.size && readBack.contentEquals(expected)
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
            val rel = "$AUTO_REL/$name"
            if (!target.write(rel, bytes)) return false
            // Same contract as the mirror loop: a write that cannot be read back
            // is not a write. This one matters more, not less — `auto/` holds the
            // daily full snapshot, and the rotation below counts on `list()` to
            // decide what to keep. A snapshot that landed under a different name
            // would be invisible to the rotation AND would leave the local copy
            // (the only real backup) silently out of step with the external one.
            if (!mirrorBytesMatch(target, rel, bytes)) {
                Log.w(TAG, "auto snapshot mirror failed: wrote $rel but could not read it back")
                return false
            }

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
