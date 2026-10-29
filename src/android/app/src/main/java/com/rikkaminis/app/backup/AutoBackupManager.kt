package com.rikkaminis.app.backup

import android.content.Context
import android.util.Log
import com.rikkaminis.app.MinisApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [T-auto-backup-assets] Daily automatic backup of the agent's ASSETS —
 * capability (config/providers/thinking rules/skills/env vars/MCP) and
 * output (shared/ artifacts, knowledge-graph data, memory) — with chat
 * transcripts deliberately excluded: this is a task-runner, not a chat app,
 * and the 90% AI-generated chat stream is process byproduct. The full
 * payload that manual export builds minus chat (chatRepo = null) is written
 * locally under `filesDir/backup-autos/` (rotating
 * [WebDavSync.AUTO_BACKUP_KEEP]) and
 * pushed to the configured WebDAV server under the `auto/` subdirectory
 * as `rikkaminis-backup-auto-*` (rotated remotely by
 * WebDavSync.pruneAutoBackups) — a dedicated folder of its own, so
 * automatic copies never mix with the curated manual backups in the backup
 * root and a second device can manage the same folder (restore/fetch/delete)
 * from its own auto-backup section.
 * Credentials do NOT ride along by default: the automatic copy is built
 * with `includeSecrets = false` [D1-R3, 2026-10-13 product decision] —
 * an unattended push to the user's WebDAV server never carries plaintext
 * API keys / OAuth tokens / env var values. The local copy under
 * `filesDir/backup-autos/` is the same secret-free shape. On restore day
 * the user re-enters providers / envVars / MCP secrets by hand (or makes a
 * separate manual export with "include secrets" checked).
 * [fix/sweep1014-debug-backup D3] KDoc wording aligned with the shipped
 * behavior; behavior unchanged.
 *
 * Trigger: once per calendar day, on the first foreground transition, via
 * MinisApp's existing activity-lifecycle hook. No alarm/workmanager: if the
 * app is never opened that day nothing runs, avoiding a background service.
 */
object AutoBackupManager {

    private const val TAG = "AutoBackup"
    private const val PREFS = "backup_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_LAST_RUN = "auto_backup_last_run"
    private const val DIR = "backup-autos"

    /** Auto-backup local file prefix (public for the settings UI list). */
    const val LOCAL_FILE_PREFIX = "rikkaminis-auto-"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Local auto-backup files, newest first.
     *
     *  [FIX-1028 / B-12] "Newest" is the stamp in the NAME, not `lastModified`:
     *  a run that happens after a clock rollback writes a file whose mtime
     *  looks older than yesterday's, and mtime ordering then makes rotation
     *  delete the copy it just wrote. Files whose name carries no stamp (a
     *  fetched copy keeps the server's own stamp) sort last and are never
     *  rotation victims — see [BackupRotation.rotationVictims]. */
    fun listLocal(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        // [FIX-P1B / FINDING-14] One matching rule for the list, rotation and
        // the settings screen (AutoBackupLocalStore.isBackupName): a finished
        // backup must end with .json, so a `.json.tmp` left by a killed run is
        // invisible here instead of showing up as the newest "backup".
        //
        // [FIX-1028 / B-12] The naming rule (writer) and the stamp reader
        // (order) must agree, or every backup becomes "age unknown" and
        // rotation stops reclaiming anything — BackupRotationTest pins the
        // writer/reader pair, AutoBackupFetchHardeningTest the selection.
        return dir.listFiles { f -> f.isFile && AutoBackupLocalStore.isBackupName(f.name, LOCAL_FILE_PREFIX) }
            ?.let { files ->
                BackupRotation.newestFirst(
                    files.toList(),
                    name = { it.name },
                    key = { BackupRotation.stampMillis(it.name, LOCAL_FILE_PREFIX) },
                )
            } ?: emptyList()
    }

    /** [FIX-1028 / B-12] Rotation key for one local auto backup: the stamp in
     *  its name, or null when it has none. Exposed so the settings screen's
     *  fetch-to-local path and the nightly run order/rotate identically. */
    fun localStampMillis(file: File): Long? =
        BackupRotation.stampMillis(file.name, LOCAL_FILE_PREFIX)

    /**
     * [fix/da1023-backup-streaming-fetch] Pure selection behind
     * [rotateLocalAutoBackups]: given a newest-first local list (the order
     * [listLocal] guarantees), everything beyond the first [keep] entries is
     * a rotation victim. [keep] <= 0 means "no local history" — every file is
     * a victim, which is what the caller asked for; both branches are
     * explicit so the boundary is unit-testable.
     */
    fun selectLocalRotationVictims(
        files: List<File>,
        keep: Int,
        // [FIX-1028 / B-12] Deletion eligibility is a separate question from
        // position: production passes "the name carries a stamp", so a copy
        // whose age cannot be established is kept rather than evicted. The
        // default (everything eligible) is the pure boundary this function has
        // always been, and its tests pin exactly that.
        victimEligible: (File) -> Boolean = { true },
        // [FIX-R3-D / F-01] …and position is a separate question from
        // OWNERSHIP: the copy this process just wrote is not history, it is the
        // artifact the caller is about to upload / show. A rolled-back clock
        // makes its name stamp the OLDEST in the directory, so `drop(keep)`
        // puts it first in line for deletion — exactly the case
        // [BackupRotation.rotationVictims]'s `protect` exists for. Null = no
        // caller-supplied protection (pure-boundary default).
        protectName: String? = null,
    ): List<File> =
        (if (keep <= 0) files else files.drop(keep))
            .filter(victimEligible)
            .filter { protectName == null || it.name != protectName }

    /**
     * [FIX-R3-D / F-01] The name of the copy the current thread just wrote into
     * the auto-backup directory, or null.
     *
     * Why a scoped mark instead of a parameter on [rotateLocalAutoBackups]:
     * that call is pinned by `backup_streaming_export_check` as the literal
     * shape `rotateLocalAutoBackups(app)` / `rotateLocalAutoBackups(context)`
     * (the gate exists because a dead-guarded rotation let
     * `filesDir/backup-autos` grow without bound — see that gate's arm 1), and
     * this branch may not touch the scan scripts. The rule itself lives where
     * it is testable ([selectLocalRotationVictims]'s `protectName`); the scoped
     * mark is only the plumbing that hands the rule its argument.
     */
    private val freshlyWritten: ThreadLocal<String?> = ThreadLocal()

    /**
     * [FIX-R3-D / F-01] Run [block] with [file] marked as "written by this
     * run", so a rotation inside [block] can never delete it.
     *
     * Thread-scoped and restored on exit: an exception cannot leave a stale
     * protection behind, and the nightly run and a settings-screen fetch (the
     * two writers) never see each other's mark.
     */
    fun <T> withFreshlyWritten(file: File, block: () -> T): T {
        val previous = freshlyWritten.get()
        freshlyWritten.set(file.name)
        try {
            return block()
        } finally {
            freshlyWritten.set(previous)
        }
    }

    /**
     * [FIX-R3-D / F-01] The name the current thread marked as freshly written,
     * or null. Exposed (internal) so the scoping contract — visible inside the
     * block, gone after it, restored rather than cleared when nested — is
     * pinned instead of assumed.
     */
    internal fun freshlyWrittenName(): String? = freshlyWritten.get()

    /**
     * [fix/da1023-backup-streaming-fetch] Enforce the local auto-backup cap:
     * keep the newest [WebDavSync.AUTO_BACKUP_KEEP] copies, delete the rest
     * (best-effort, mirroring the previous inline behavior in runLocked).
     * Shared between the nightly run and the settings screen's
     * fetch-to-local path, which previously wrote a fetched copy WITHOUT any
     * rotation — every fetch added an unbounded local copy.
     *
     * [FIX-R3-D / F-01] Never deletes the copy [withFreshlyWritten] marked:
     * after a clock rollback (or a fetch of an old server-side copy) that copy
     * sorts as the OLDEST, so rotation used to delete it in the same breath as
     * writing it — and the WebDAV push that follows read a file that no longer
     * existed (`FileNotFoundException`) while the settings page still claimed
     * the day's backup was done.
     */
    fun rotateLocalAutoBackups(context: Context) {
        selectLocalRotationVictims(
            files = listLocal(context),
            keep = WebDavSync.AUTO_BACKUP_KEEP,
            // [FIX-1028 / B-12] Never delete a copy whose age we cannot read off
            // its name: "unknown" is not "oldest".
            victimEligible = { localStampMillis(it) != null },
            protectName = freshlyWrittenName(),
        ).forEach { runCatching { it.delete() } }
    }

    /** Human-readable last-run timestamp, or null when never run. */
    fun lastRunLabel(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_RUN, null)

    /** Daily gate — call from the app's foreground hook. No-op unless
     *  enabled and not yet run this calendar day. */
    fun runIfDue(context: Context) {
        if (!isEnabled(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString(KEY_LAST_RUN, null) == today) return
        runAsync(context.applicationContext)
    }

    /** [audit-0908] Single-flight guard. Two overlapping runs would race on
     *  the same-second filename (`yyyyMMdd-HHmmss`) and interleave two
     *  writers into one corrupt JSON — silent damage the user only discovers
     *  on restore day. Overlap windows are real: a quick double
     *  background→foreground transition, or the foreground beat landing while
     *  the settings screen's "back up now" is still running (or vice versa).
     *  A run that lands while one is in flight just joins it silently. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Trigger one automatic backup now (ignores the daily gate). */
    fun runAsync(context: Context): kotlinx.coroutines.Job? {
        val app = context.applicationContext as? MinisApp ?: return null
        return app.applicationScope.launch(Dispatchers.IO) {
            try {
                runNow(app)
            } catch (t: Throwable) {
                Log.w(TAG, "auto backup failed: ${t.message}")
            }
        }
    }

    /** Synchronous auto-backup body (IO thread). Exposed so the settings
     *  screen can drive it inside its own gate/scope and refresh UI state
     *  afterwards. Throws on failure — callers decide how to surface it. */
    suspend fun runNow(app: MinisApp) {
        // [audit-0908] Single-flight entry (see inFlight). CAS failure means
        // a backup is already running on another path — join it by returning.
        // KEY_LAST_RUN is then left unset, so tomorrow's foreground beat
        // retries: a skipped beat self-heals, a corrupt file does not.
        if (!inFlight.compareAndSet(false, true)) return
        try {
            runLocked(app)
        } finally {
            inFlight.set(false)
        }
    }

    private suspend fun runLocked(app: MinisApp) {
        // [sweep1013-D1/F-4] Unattended backups must not ship credentials to
        // the remote (WebDAV is pushed to without a user prompt). Without
        // secrets the restore needs values refilled (providers/envVars/MCP
        // headers+env) — a deliberate product-posture change; if a user ever
        // wants secret-bearing auto-backups that must be an explicit opt-in
        // setting, not a silent default.
        //
        // [fix/da1023-backup-streaming-fetch] The document is streamed
        // straight into the local file (ConfigBackup.exportToWriter) instead
        // of being assembled as one String first (ConfigBackup.export). The
        // auto payload excludes chat but still reaches tens of MB on a heavy
        // install (skills + memory + shared/ artifacts + knowledge graph):
        // the String path materializes the whole document AND its
        // toString()/StringBuilder twin in UTF-16 before the first byte
        // touches disk — that is the OOM shape the manual export path already
        // removed for itself ([T-backup-streaming-export]). Same assembly
        // (buildSections is the single source of truth for both), so the
        // written document is the same shape as before.
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        // [FIX-P1B / FINDING-14] Reclaim `.json.tmp` files from a run that was
        // killed mid-write (listLocal cannot see them by design, so nothing
        // else would). Best-effort: a failed delete must never fail the backup.
        AutoBackupLocalStore.deleteStaleTemps(dir, LOCAL_FILE_PREFIX)
        // [FIX-1028 / B-12] One stamp format for writer and reader.
        val stamp = BackupRotation.stamp()
        val local = File(dir, AutoBackupLocalStore.fileName(LOCAL_FILE_PREFIX, stamp))
        // exportToWriter returns the count of fields it could not read. The
        // unattended path has no UI to surface them in, so a nonzero count is
        // logged and the backup still lands (the String path dropped those
        // fields the same way; only the reporting channel differs).
        //
        // [FIX-P1B / FINDING-14] Streamed into a `.json.tmp` sibling and renamed
        // only on success — writing the final name directly is what left a
        // truncated document under a legal name (see AutoBackupLocalStore). A
        // throw here also leaves KEY_LAST_RUN unset, so the next foreground
        // beat retries instead of waiting a day.
        val unreadableFields = AutoBackupLocalStore.writeAtomically(local) { writer ->
            ConfigBackup.exportToWriter(
                providerRepo = app.providerRepository,
                includeSecrets = false,
                envVarRepo = app.envVarRepository,
                skillRepo = app.skillRepository,
                memoryRepo = app.memoryRepository,
                mcpRepo = app.mcpRepository,
                chatRepo = null, // process byproduct — see class doc
                chatWindowDays = 0,
                artifactRoots = listOf(
                    File(app.filesDir, "minis-global/shared"),
                    File(app.filesDir, "minis-global/mcp-servers"),
                ),
                webDavConfig = WebDavConfigStore(app).load(),
                scheduledTasks = com.rikkaminis.app.scheduled.ScheduledTasksStore
                    .exportDocument(app),
                writer = writer,
            )
        }
        if (unreadableFields > 0) {
            Log.w(TAG, "auto backup wrote $unreadableFields unreadable field(s)")
        }

        // Local rotation: keep the newest AUTO_BACKUP_KEEP auto files
        // (shared helper — the settings screen's fetch path rotates the same
        // way, so the cap has a single implementation).
        //
        // [FIX-R3-D / F-01] The copy just written is marked for the duration of
        // this call: after a clock rollback its name stamp sorts OLDEST, so a
        // plain rotation deleted it here and the push below read a missing
        // file. See AutoBackupManager.withFreshlyWritten.
        withFreshlyWritten(local) { rotateLocalAutoBackups(app) }

        // [feat/auto-backup-external-mirror] Second copy outside filesDir.
        // The 2026-10-29 incident rebuilt filesDir and took the working copy
        // and this directory's only backup with it. The external folder is
        // owned by MediaStore, so it survives that, an uninstall, and
        // "clear app data". Best-effort by the same contract as the WebDAV push
        // below: the local copy already exists, so a failure only means the
        // external layer lags a day.
        runCatching { MirrorStore.mirrorAutoSnapshot(app, local) }
            .onFailure { Log.w(TAG, "external auto mirror failed: ${it.message}") }

        // Remote push is best-effort: local copy already exists, so a failed
        // upload only means the offsite copy lags until tomorrow. The push
        // uploads the file we just wrote (bytes read off disk), never a
        // re-assembled String.
        val cfg = WebDavConfigStore(app).load()
        if (cfg != null && cfg.url.isNotBlank() && cfg.username.isNotBlank()) {
            runCatching {
                // [FIX-1028 / B-12] The copy just uploaded is never a rotation
                // victim, even when a rolled-back clock gave it an old-looking
                // name (the remote list orders by that same name stamp).
                val remoteName = WebDavSync.backupAuto(cfg, local)
                WebDavSync.pruneAutoBackups(
                    cfg,
                    keep = WebDavSync.AUTO_BACKUP_KEEP,
                    protectName = remoteName,
                )
            }.onFailure { Log.w(TAG, "remote push failed: ${it.message}") }
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_RUN, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            .apply()
    }
}