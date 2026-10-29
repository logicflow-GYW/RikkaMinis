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

    /**
     * [R5-B2 C-02②] Suffix of the verbatim copy [preserveUnreadableLocked]
     * keeps when a whole-document restore is about to replace bytes we could
     * not read. Deliberately NOT a `.json` name: nothing may pick the copy up
     * as a tasks document, and nothing globs this directory.
     */
    private const val UNREADABLE_COPY_SUFFIX = ".unreadable"

    /** How many unreadable copies to keep (newest first, older ones pruned). */
    private const val UNREADABLE_COPY_KEEP = 3

    /**
     * [R5-B2 C-02③] Marker for a `version` value that is present but not a
     * positive integer (`0`, a negative number, a string). Never a real
     * version: real versions count up from 1.
     */
    private const val INVALID_DECLARED_VERSION = -1

    private val lock = Mutex()

    /**
     * [FIX-R3-E F-07] `scheduled-tasks.json` exists but cannot be turned into a
     * document — unparseable bytes, or a `version` this build does not read.
     *
     * Read failures used to be folded into `ScheduledTasksFile()` (the same
     * value as "this device really has no tasks"), and the NEXT mutation then
     * persisted that empty document over the user's tasks: one tap on the
     * global switch erased every automation. Same failure shape
     * [MountedFoldersStore.diskUnreadable] was fixed for (E2-F12).
     *
     * Contract: while this is set, every read-modify-write path REFUSES to
     * write (returns the pre-write document) and says so in the log. The file
     * is deliberately NOT moved aside: `AtomicFileWrite` is what a repair
     * path would use, and a spurious quarantine of a file we merely failed to
     * read once would be its own data loss. A whole-document restore
     * ([importDocument]) is exempt — it replaces the file with bytes we do
     * have, which is exactly the recovery path a corrupt local file needs.
     */
    @Volatile
    private var unreadable = false

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
     *
     * [FIX-R3-E F-07] When the live file is unreadable this exports an EMPTY
     * task set — the signature cannot carry the failure (every call site lives
     * under `backup/` or the backup screens, which this branch must not
     * touch), and the restore side already refuses an empty section over a
     * non-empty device ([ScheduledTasksRestorePlan.decide]), so the worst that
     * survives is a backup that cannot delete tasks elsewhere. Logged loudly
     * so the export is not mistaken for a good one.
     * and the restore side already refuses an empty section over a non-empty
     * device ([ScheduledTasksRestorePlan.decide]), so the worst that survives
     * is a backup that cannot delete tasks elsewhere. Logged loudly so the
     * export is not mistaken for a good one.
     */
    suspend fun exportDocument(context: Context): JSONObject = lock.withLock {
        val doc = loadLocked(context)
        if (unreadable) {
            AppLogger.warning(TAG, "export: scheduled-tasks.json is unreadable — exporting an EMPTY task set")
        }
        JSONObject(ScheduledTasksCodec.encode(doc))
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
            // [FIX-R3-E F-07] While the local file is unreadable, `live` parsed
            // to empty, so the plan's empty-set guard sees `liveTasks == 0` and
            // would let an EMPTY backup section through — writing "no tasks"
            // over bytes we could not read. A non-empty restore stays allowed
            // (see the [unreadable] KDoc: replacing the file with bytes we do
            // have is the recovery path), an empty one does not.
            if (unreadable && parsed.tasks.isEmpty()) {
                AppLogger.warning(TAG, "restore refused: local file unreadable and backup section is empty")
                return@withLock ScheduledTasksRestoreOutcome(
                    applied = false,
                    notice = "this device's scheduled-tasks.json is unreadable and the backup carries no " +
                        "task; nothing was overwritten",
                )
            }
            if (decision.applied) {
                // [FIX-R3-E F-07] A restore that did not land must not report
                // "applied": ConfigBackup turns that flag into
                // `scheduledTasksImported = true` and the restore UI shows no
                // skip line, so a full disk used to report a clean restore of
                // tasks that are still the old ones.
                //
                // [R5-B2 C-02②] This is the one write allowed to run while
                // [unreadable] is set, and the bytes it replaces are the ONLY
                // copy of whatever we could not read (a newer build's document,
                // or corrupt bytes). The pre-restore snapshot cannot stand in
                // for them: it recorded the tasks THIS build could parse, i.e.
                // none. Keep a verbatim copy first.
                if (unreadable) preserveUnreadableLocked(context)
                if (writeLocked(context, parsed.copy(running = null))) {
                    // The bytes on disk are now a document we just produced, so
                    // an earlier unreadable verdict no longer applies. (A
                    // whole-document restore is the one write path allowed to
                    // run while [unreadable] is set — see its KDoc.)
                    unreadable = false
                    ScheduledTasksRestoreOutcome(applied = true, notice = decision.notice)
                } else {
                    ScheduledTasksRestoreOutcome(
                        applied = false,
                        notice = "could not write this device's scheduled-tasks.json; " +
                            "kept the existing task(s) as-is",
                    )
                }
            } else {
                ScheduledTasksRestoreOutcome(applied = false, notice = decision.notice)
            }
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
            // [FIX-R3-E F-07] An unreadable file parsed to an EMPTY document;
            // transforming it and writing the result back would persist "no
            // tasks" as the truth. Refuse and hand back the pre-write
            // document — the same value every refused mutation returns, so a
            // caller that checks "is my change in there" (the editor's
            // `saved.tasks.any { it.id == id }`) sees the failure instead of a
            // phantom success.
            if (unreadable) {
                AppLogger.warning(TAG, "mutation refused: scheduled-tasks.json is unreadable")
                return@withLock current
            }
            val next = transform(current)
            if (next === current) return@withLock next
            val wrote = writeLocked(context, next)
            if (!wrote) {
                AppLogger.warning(TAG, "mutation not persisted; handing back the pre-write document")
            }
            // [FIX-R3-E F-07] A verified atomic write is the only thing that
            // may be reported back as the document; anything else hands back
            // what is actually on disk.
            ScheduledTaskStorePolicy.visibleAfterWrite(current, next, wrote)
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
        // [R5-B2 F-04 = C-02①] A REFUSED delete must not release anything.
        // `mutate` hands back the PRE-write document when the write did not
        // land (disk full, or the `unreadable` guard — see
        // [ScheduledTaskStorePolicy.visibleAfterWrite]), so "the task is still
        // in the returned document" is exactly "nothing was deleted". The
        // sibling restore path has the same guard (`if (!outcome.applied)
        // return outcome`, above); without it here, a failed delete cleared the
        // runner's in-memory lock while the persisted `running` marker stayed
        // on disk — the next minute beat then reads
        // `marker.taskId != runningTaskId` and books a `process-restart`
        // crash failure against a task that is running perfectly well.
        if (next.tasks.any { it.id == taskId }) {
            AppLogger.warning(
                TAG,
                "delete of task=$taskId not persisted; runner state left alone",
            )
            return next
        }
        // [da1016 B-7c] Deleting the task that is running right now only cleared
        // the persisted marker; the runner's in-memory lock (runningTaskId +
        // dispatched) kept pointing at the deleted task, so every other task
        // degraded to `overlap` until reapAbandonedDispatch's deadline+grace
        // (30 min by default). Release it here — after the store lock, see the
        // LOCK ORDER note on ScheduledTaskRunner.onTasksRemoved. Unconditional
        // (not guarded on the deleted id matching a run) on purpose: the plan
        // decides, and a delete that LANDS can never be forgotten. The guard
        // above is about a delete that never happened, not about the run: a
        // real deletion always releases, whether or not a run is in flight.
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

    /**
     * Persist the crash-recovery marker for a run the runner is about to
     * dispatch.
     *
     * [FIX-R3-E F-04B] Returns the file the marker actually landed on, which
     * is NOT always the input: if the task disappeared from the document
     * while [ScheduledTaskRunner.fire] was suspended in its `prepareSession`
     * window (user delete / backup restore — both release the store lock
     * before taking the runner's [ScheduledTaskRunner.onTasksRemoved]
     * `checkMutex`), the marker is NOT written and the returned file's
     * `running` stays null. The runner re-checks the return value before
     * dispatching, so a deleted task can no longer resurrect its own
     * `running` marker on disk or start a headless session.
     */
    suspend fun markRunning(
        context: Context,
        taskId: String,
        sessionId: String,
        startedAt: Long,
    ): ScheduledTasksFile = mutate(context) {
        ScheduledTaskStorePolicy.markRunningDecision(
            file = it,
            taskId = taskId,
            sessionId = sessionId,
            startedAt = startedAt,
        )
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
            // [FIX-R3-E F-07] Refuse on unreadable, like every other
            // read-modify-write: the settlement would otherwise write an empty
            // document over the task list. (The caller only logs the result.)
            if (unreadable) {
                AppLogger.warning(TAG, "settlement refused: scheduled-tasks.json is unreadable")
                return@withLock ScheduledTaskTransitions.Result(current, null, fuseTripped = false)
            }
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
            if (unreadable) {
                AppLogger.warning(TAG, "crash recovery refused: scheduled-tasks.json is unreadable")
                return@withLock ScheduledTaskTransitions.Result(current, null, fuseTripped = false)
            }
            val result = ScheduledTaskTransitions.crashFailure(current, taskId)
            if (result.file !== current) writeLocked(context, result.file)
            result
        }
    }

    // ------------------------------------------------------------------

    /**
     * [R5-B2 C-02②] Keep the bytes of an unreadable `scheduled-tasks.json`
     * before a whole-document restore replaces them.
     *
     * The defect: while [unreadable] is set, `load` produces an EMPTY document,
     * so `exportDocument` gave the pre-restore snapshot an empty tasks section —
     * and `importDocument` then overwrote the original file (a newer build's
     * document, or corrupt-but-maybe-salvageable bytes). The user was left with
     * neither the old file nor a snapshot holding its tasks. The snapshot
     * cannot be fixed from here (it is ConfigBackup's payload), so the recovery
     * net this store CAN lay down is a verbatim copy of the file it is about to
     * destroy: `scheduled-tasks.json.unreadable-<stamp>` next to it, pruned to
     * the newest [UNREADABLE_COPY_KEEP].
     *
     * Failure to copy must not block the restore — the restore is the user's
     * requested action and the copy is a safety net; it is logged instead.
     */
    private fun preserveUnreadableLocked(context: Context) {
        val file = fileFor(context)
        if (!file.exists()) return
        val dir = file.parentFile ?: return
        val copy = File(dir, file.name + UNREADABLE_COPY_SUFFIX + "-" + System.currentTimeMillis())
        try {
            file.copyTo(copy, overwrite = false)
            val pruned = (dir.listFiles { f -> f.name.startsWith(file.name + UNREADABLE_COPY_SUFFIX) }
                ?: emptyArray())
                .sortedByDescending { it.name }
                .drop(UNREADABLE_COPY_KEEP)
            var removed = 0
            for (old in pruned) if (old.delete()) removed++
            AppLogger.warning(
                TAG,
                "kept the unreadable scheduled-tasks.json as ${copy.name} before replacing it" +
                    if (removed > 0) " (pruned $removed older copy/copies)" else "",
            )
        } catch (t: Throwable) {
            AppLogger.warning(
                TAG,
                "could not keep a copy of the unreadable scheduled-tasks.json " +
                    "(${t.javaClass.simpleName}) — a restore is about to replace it",
            )
        }
    }

    /**
     * [R5-B2 C-02③] The declared `version`, read strictly.
     *
     * `optInt("version", ScheduledTasksCodec.VERSION)` mapped `0`, a negative
     * number, `"v2"`, `null` and a missing key all onto "this build's version".
     * The first two and the string are NOT a version this build reads: they
     * were parsed leniently and then re-written as v1 by the next mutation,
     * silently stripping whatever the writer had added —
     * [ScheduledTasksRestorePlan.decide] already refuses a declared version it
     * does not read, and the live file is the copy that gets overwritten blind,
     * so it must be at least as strict.
     *
     * @return `null` when the key is absent (a pre-versioning document, which is
     *   exactly this build's own shape: accepted as v1), the declared number
     *   when it is a positive integer, or [INVALID_DECLARED_VERSION] when the
     *   value exists but is not one.
     */
    private fun declaredVersionOf(root: JSONObject): Int? {
        if (!root.has("version")) return null
        val number = root.opt("version") as? Number ?: return INVALID_DECLARED_VERSION
        val value = number.toInt()
        if (number.toDouble() != value.toDouble() || value <= 0) return INVALID_DECLARED_VERSION
        return value
    }

    private fun loadLocked(context: Context): ScheduledTasksFile {
        val file = fileFor(context)
        if (!file.exists()) {
            unreadable = false
            return ScheduledTasksFile()
        }
        return try {
            val raw = file.readText()
            // [FIX-R3-E F-07] A zero-length / whitespace-only file is "this
            // device really has no tasks" — NOT a read failure. Treating it as
            // unreadable would lock the user out of saving anything (the only
            // escape would be a whole-document restore) for a state that holds
            // no data to protect. Only non-empty bytes that fail to become a
            // document count as unreadable.
            if (raw.isBlank()) {
                unreadable = false
                return ScheduledTasksFile()
            }
            val parsed = ScheduledTasksCodec.parse(raw)
            // [FIX-R3-E F-07] A document written by a newer build must not be
            // re-encoded by this one: `parse` reads unknown fields leniently,
            // so a v2 file round-trips through this codec as a v1 document and
            // the next mutation would silently strip whatever v2 added. The
            // backup-restore path already refuses foreign versions
            // ([ScheduledTasksRestorePlan.decide]); the live file gets the
            // same gate here, because it is the one that gets overwritten
            // blind. Counts as [unreadable], like any other read failure.
            //
            // [R5-B2 C-02③] The gate reads `version` STRICTLY
            // ([declaredVersionOf]): the old `optInt("version", VERSION)` mapped
            // `0`, a negative number and a non-numeric value onto "this build's
            // version", so a document that declared something else entirely was
            // accepted as v1 and then re-written as v1 by the next mutation.
            val declared = declaredVersionOf(JSONObject(raw))
            if (declared != null &&
                (declared == INVALID_DECLARED_VERSION || declared > ScheduledTasksCodec.VERSION)
            ) {
                unreadable = true
                AppLogger.warning(
                    TAG,
                    "tasks.json declares " +
                        (if (declared == INVALID_DECLARED_VERSION) "an unusable version value" else "format v$declared") +
                        ", this build reads v${ScheduledTasksCodec.VERSION} — refusing writes until it is replaced",
                )
                return ScheduledTasksFile()
            }
            unreadable = false
            parsed
        } catch (t: Throwable) {
            // [FIX-R3-E F-07] "Cannot read" is NOT "the device has no tasks".
            // It used to be folded into an empty document, and the next
            // mutation (one switch tap) persisted that empty set over the
            // user's tasks. Now the failure is remembered: every
            // read-modify-write refuses until the file is replaced by a
            // whole-document restore (the one path that writes bytes we just
            // parsed).
            unreadable = true
            AppLogger.warning(TAG, "tasks.json unreadable (${t.javaClass.simpleName}) — refusing writes until it is replaced")
            ScheduledTasksFile()
        }
    }

    /** Returns whether the write LANDED (the revision bumps only when it did). */
    private fun writeLocked(context: Context, file: ScheduledTasksFile): Boolean {
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
        return wrote
    }
}
