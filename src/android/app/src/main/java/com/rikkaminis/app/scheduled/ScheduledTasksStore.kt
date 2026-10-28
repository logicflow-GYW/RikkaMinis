package com.rikkaminis.app.scheduled

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.tools.AtomicFileWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * [feat/scheduled-tasks-l0] Persistent store for scheduled tasks.
 *
 * Location: `filesDir/minis-config/scheduled-tasks.json` — the app's existing
 * config directory (same convention as MountedFoldersStore), intentionally
 * OUTSIDE minis-global/ so agents and the DocumentsProvider cannot see or
 * write it. Rides the user-config backup via ConfigBackup's
 * "scheduledTasks" section.
 *
 * All reads and read-modify-writes funnel through one Mutex, so the foreground
 * check pass, the UI editor and the run-settlement path can never interleave
 * a lost update. Writes are atomic (temp + rename).
 */
object ScheduledTasksStore {

    private const val DIR_NAME = "minis-config"
    private const val FILE_NAME = "scheduled-tasks.json"
    private const val TAG = "scheduled-run"

    private val lock = Mutex()

    private val _revision = MutableStateFlow(0L)

    /**
     * [deep-audit-1046 S-11] Bumped exactly once per SUCCESSFUL atomic write.
     *
     * Every read in this store is a one-shot `load`, so a screen that rendered
     * once rendered that document forever: the list kept showing a task as
     * "never ran" after a background run settled, and never showed the fuse
     * trip (the Resume button only appeared after leaving and re-entering the
     * screen). Screens now key their reloads on this value.
     *
     * It is a change TICKER, not a document version: the value means nothing
     * beyond "different from before". The bump lives in [writeLocked] — the one
     * funnel every write already goes through — so a future write helper cannot
     * forget it (the alternative, one bump per write method, silently misses
     * the seventh one).
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun fileFor(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /**
     * The whole document as a JSON object for the config backup's
     * "scheduledTasks" section (ConfigBackup.export param [scheduledTasks]).
     */
    suspend fun exportDocument(context: Context): JSONObject = lock.withLock {
        JSONObject(loadLocked(context).let(ScheduledTasksCodec::encode))
    }

    /**
     * Restore side of the backup section: parse through the same defensive
     * codec as the live file (malformed entries drop, never the whole file)
     * and write atomically. Runs inside the store mutex — the crash-recovery
     * marker never survives a restore (the restored device has no in-flight
     * run), matching the brief's process-local single-flight truth.
     *
     * [FIX-1028 / A-16 = B-5] Returns what actually happened instead of `Unit`:
     * the decision itself ([ScheduledTasksRestorePlan.decide]) may REFUSE to
     * write, because "the section parsed to zero tasks" is not the same thing
     * as "the backup says this device should have no tasks". Before this the
     * empty set landed on disk and the caller reported a successful restore.
     */
    suspend fun importDocument(
        context: Context,
        document: JSONObject,
    ): ScheduledTasksRestoreOutcome {
        val outcome = lock.withLock {
            val live = loadLocked(context)
            val rawEntries = document.optJSONArray("tasks")?.length() ?: 0
            val declaredVersion = if (document.has("version")) document.optInt("version", -1) else null
            val parsed = try {
                ScheduledTasksCodec.parse(document.toString())
            } catch (t: Throwable) {
                // Whole document unparseable: nothing is written (the parse
                // precedes the write), and the caller surfaces this line instead
                // of the old generic "restore failed" it could not explain.
                AppLogger.warning(TAG, "backup tasks.json unparseable: ${t.javaClass.simpleName}")
                return@withLock ScheduledTasksRestoreOutcome(
                    applied = false,
                    notice = "section could not be parsed (${t.javaClass.simpleName}); " +
                        "kept this device's ${live.tasks.size} task(s) as-is",
                )
            }
            val decision = ScheduledTasksRestorePlan.decide(
                declaredVersion = declaredVersion,
                supportedVersion = ScheduledTasksCodec.VERSION,
                rawEntries = rawEntries,
                parsedTasks = parsed.tasks.size,
                liveTasks = live.tasks.size,
            )
            if (decision.applied) writeLocked(context, parsed.copy(running = null))
            ScheduledTasksRestoreOutcome(decision.applied, decision.notice)
        }
        // [FIX-1028 / A-16] A refused restore removed nothing, so the runner's
        // single-flight state must survive it (releasing the lock would drop a
        // legitimately running task's gate). Early return keeps the release
        // below a top-level statement of this function — the placement the
        // lock-order guard requires.
        if (!outcome.applied) return outcome
        // [da1016 B-7c] The restore replaces the whole task set and clears the
        // persisted marker; the in-process single-flight state must go with it,
        // or the lock keeps pointing at a run the restored document knows
        // nothing about. Called AFTER the store lock is released — the store's
        // mutex and the runner's checkMutex must never nest (LOCK ORDER note on
        // ScheduledTaskRunner.onTasksRemoved); guarded by
        // scripts/scan/scheduled_store_release_guard.py.
        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = null)
        return outcome
    }

    suspend fun load(context: Context): ScheduledTasksFile = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(context) }
    }

    suspend fun mutate(
        context: Context,
        transform: (ScheduledTasksFile) -> ScheduledTasksFile,
    ): ScheduledTasksFile = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val next = transform(current)
            if (next !== current) writeLocked(context, next)
            next
        }
    }

    // ------------------------------------------------------------------
    // Named helpers — thin wrappers so call sites read as intent.
    // ------------------------------------------------------------------

    suspend fun setGlobalEnabled(context: Context, enabled: Boolean): ScheduledTasksFile =
        mutate(context) { it.copy(globalEnabled = enabled) }

    /**
     * Editor save — thin wrapper over [ScheduledTaskTransitions.mergeUpsertTask]:
     * only the editable spec fields are applied, while run state (`lastRunDate` /
     * `lastResult` / `failStreak` / `suspendedByFuse`) and `enabled` stay with the
     * stored record. Writing the caller's whole record here used to erase any run
     * that settled while the editor was open (up to the full prompt budget) —
     * see the merge function for the shape.
     */
    suspend fun upsertTask(
        context: Context,
        task: ScheduledTask,
        allowInsert: Boolean = true,
    ): ScheduledTasksFile =
        mutate(context) { file ->
            ScheduledTaskTransitions.mergeUpsertTask(file, task, allowInsert)
        }

    suspend fun deleteTask(context: Context, taskId: String): ScheduledTasksFile {
        val next = mutate(context) { file ->
            if (file.running?.taskId == taskId) {
                file.copy(tasks = file.tasks.filterNot { it.id == taskId }, running = null)
            } else {
                file.copy(tasks = file.tasks.filterNot { it.id == taskId })
            }
        }
        // [da1016 B-7c] Deleting the task that is running right now only cleared
        // the persisted marker; the runner's in-memory lock (runningTaskId +
        // dispatched) kept pointing at the deleted task, so every other task
        // degraded to `overlap` until reapAbandonedDispatch's deadline+grace
        // (30 min by default). Release it here — after the store lock, see the
        // LOCK ORDER note on ScheduledTaskRunner.onTasksRemoved. Unconditional
        // (not guarded on the deleted id matching a run) on purpose: the plan
        // decides, and a delete that hits a live run can never be forgotten.
        ScheduledTaskRunner.onTasksRemoved(removedTaskIds = setOf(taskId))
        return next
    }

    suspend fun setTaskEnabled(context: Context, taskId: String, enabled: Boolean): ScheduledTasksFile =
        mutate(context) { file ->
            file.copy(
                tasks = file.tasks.map {
                    if (it.id == taskId) {
                        // Turning a fused task back on is the manual resume path.
                        if (enabled && it.suspendedByFuse) {
                            it.copy(enabled = true, suspendedByFuse = false, failStreak = 0)
                        } else {
                            it.copy(enabled = enabled)
                        }
                    } else {
                        it
                    }
                },
            )
        }

    suspend fun resumeTask(context: Context, taskId: String): ScheduledTasksFile =
        mutate(context) { ScheduledTaskTransitions.resume(it, taskId) }

    suspend fun markRunning(
        context: Context,
        taskId: String,
        sessionId: String,
        startedAt: Long,
    ): ScheduledTasksFile = mutate(context) {
        it.copy(running = ScheduledRunMarker(taskId = taskId, sessionId = sessionId, startedAt = startedAt))
    }

    /** A run settled (or was reaped). Applies transitions and clears the marker. */
    suspend fun completeRun(
        context: Context,
        taskId: String,
        outcome: String,
        today: String,
    ): ScheduledTaskTransitions.Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val result = ScheduledTaskTransitions.completeRun(current, taskId, outcome, today)
            if (result.file !== current) writeLocked(context, result.file)
            result
        }
    }

    /** Crash recovery: marker without an in-process run counts one failure. */
    suspend fun crashFailure(
        context: Context,
        taskId: String,
    ): ScheduledTaskTransitions.Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = loadLocked(context)
            val result = ScheduledTaskTransitions.crashFailure(current, taskId)
            if (result.file !== current) writeLocked(context, result.file)
            result
        }
    }

    // ------------------------------------------------------------------

    private fun loadLocked(context: Context): ScheduledTasksFile {
        val file = fileFor(context)
        if (!file.exists()) return ScheduledTasksFile()
        return try {
            ScheduledTasksCodec.parse(file.readText())
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tasks.json unreadable (${t.javaClass.simpleName}) — treating as empty")
            ScheduledTasksFile()
        }
    }

    private fun writeLocked(context: Context, file: ScheduledTasksFile) {
        val wrote = try {
            AtomicFileWrite.write(fileFor(context), ScheduledTasksCodec.encode(file))
            true
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tasks.json write failed: ${t.javaClass.simpleName}")
            false
        }
        // [deep-audit-1046 S-11] The single bump point — AFTER the write landed.
        // Reading the file back instead would need a second parse and could
        // still race; a failed write says so directly, and a would-be bump for
        // a document that never changed makes every open screen re-render stale
        // data for nothing.
        _revision.value = ScheduledTaskRevisionPolicy.nextRevision(_revision.value, wrote)
    }
}
