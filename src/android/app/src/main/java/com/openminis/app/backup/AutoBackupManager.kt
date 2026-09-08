package com.openminis.app.backup

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
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
 * locally under `filesDir/backup-autos/` (rotating [AUTO_BACKUP_KEEP]) and
 * pushed to the configured WebDAV server as `rikkaminis-backup-auto-*`
 * (rotated remotely by WebDavSync.pruneAutoBackups). Credentials always
 * ride along: the local copy stays in app-private storage and the remote is
 * the user's own server — a backup without keys can't restore a thing.
 *
 * Trigger: once per calendar day, on the first foreground transition, via
 * MinisApp's existing activity-lifecycle hook (same beat as multi-device
 * sync). No alarm/workmanager: if the app is never opened that day nothing
 * runs, matching the sync design and avoiding a background service.
 */
object AutoBackupManager {

    private const val TAG = "AutoBackup"
    private const val PREFS = "backup_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_LAST_RUN = "auto_backup_last_run"
    private const val DIR = "backup-autos"

    /** Auto-backup local file prefix (public for the settings UI list). */
    const val LOCAL_FILE_PREFIX = "rikkaminis-auto-"
    private const val AUTO_BACKUP_KEEP = 7

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Local auto-backup files, newest first. */
    fun listLocal(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(LOCAL_FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
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
        val payload = ConfigBackup.export(
                providerRepo = app.providerRepository,
                includeSecrets = true,
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
            )

        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        File(dir, "$LOCAL_FILE_PREFIX$stamp.json").writeText(payload)

        // Local rotation: keep the newest AUTO_BACKUP_KEEP auto files.
        listLocal(app)
            .drop(AUTO_BACKUP_KEEP)
            .forEach { runCatching { it.delete() } }

        // Remote push is best-effort: local copy already exists, so a failed
        // upload only means the offsite copy lags until tomorrow.
        val cfg = WebDavConfigStore(app).load()
        if (cfg != null && cfg.url.isNotBlank() && cfg.username.isNotBlank()) {
            runCatching {
                WebDavSync.backupAuto(cfg, payload)
                WebDavSync.pruneAutoBackups(cfg, keep = AUTO_BACKUP_KEEP)
            }.onFailure { Log.w(TAG, "remote push failed: ${it.message}") }
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_RUN, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            .apply()
    }
}