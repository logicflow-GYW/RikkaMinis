package com.rikkaminis.app.scheduled

import android.content.Context
import com.rikkaminis.app.MinisApp
import com.rikkaminis.app.R
import com.rikkaminis.app.agent.runtime.AgentRunBudgetCeilings
// debug-ok: scheduled dispatch goes through the same headless chain as SessionsOffloadHandler (brief §5.5, user-consented); no DebugServer/5321 markers in this closure
import com.rikkaminis.app.debug.ChatMutationMethods
// debug-ok: same audited scheduled dispatch chain (HeadlessChatRunner.ensureSession)
import com.rikkaminis.app.debug.HeadlessChatRunner
import com.rikkaminis.app.diagnostics.MemorySpikeRecorder
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.service.SessionActivityTracker
import com.rikkaminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * [feat/scheduled-tasks-l0] The scheduled-task executor.
 *
 * Trigger shape mirrors AutoBackupManager: a foreground beat calls [runIfDue]
 * (cheap no-op unless the feature is enabled), the real check runs on
 * [MinisApp.applicationScope], and a task that is due fires exactly once per
 * local calendar day inside its window, on the first eligible beat.
 *
 * Dispatch goes through the same headless path as the debug/offload RPC
 * (`ChatMutationMethods.prompt`, wait=false) so the run occupies the normal
 * session pipeline and the existing concurrency gate. Completion is observed
 * through the SessionActivityTracker completion listener (chained in MinisApp,
 * next to the task-completed notification); a dispatch that never produces a
 * stream is reaped on a later beat. The crash-recovery marker in
 * scheduled-tasks.json lets the NEXT process count a run that died with the
 * app (brief §3).
 *
 * Logging: every decision emits one `[scheduled-run]` line through AppLogger
 * with verdicts fire/skip/miss/done/fail/suspend/abort — the §8 two-week
 * measurement greps these.
 */
object ScheduledTaskRunner {

    private const val TAG = "scheduled-run"

    /** Grace on top of the run deadline before an abandoned dispatch is reaped. */
    private const val REAP_GRACE_MS = 180_000L

    private data class Dispatch(
        val taskId: String,
        val deadlineSec: Int,
        val startedAtMs: Long,
        /**
         * [deep-audit-1016c ISSUE-09A] The local calendar day this run was
         * FIRED on ("YYYY-MM-DD") — the same day [ScheduledTaskPolicy.evaluate]
         * compared [ScheduledTask.lastRunDate] against when it decided to fire.
         * Threaded to [finalizeRun] so the settlement credits the scheduled day,
         * not the (possibly next) day the run happened to settle on.
         */
        val scheduledDate: String,
    )

    /** In-process single-flight gate (brief §5.2); mirrored by the persisted marker. */
    @Volatile
    private var runningTaskId: String? = null

    /**
     * [B27] Serializes the whole read-evaluate-mark-running pass: two
     * foreground beats landing close together used to run [checkAndFireToday]
     * concurrently — both observed the same due task between the
     * [runningTaskId] check and its placement, and the daily task fired
     * twice (the crash-recovery branch double-counted the same way).
     */
    private val checkMutex = Mutex()

    /** sessionId -> dispatch record for runs this process started and awaits settling. */
    private val dispatched = ConcurrentHashMap<String, Dispatch>()

    /** Misses are day-level facts; log each (task, day) once per process. */
    private val missLogged = ConcurrentHashMap.newKeySet<String>()

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Foreground-beat entry. MUST stay cheap: the check hops to the app scope
     * (IO) so the activity lifecycle callback never touches disk.
     */
    fun runIfDue(context: Context) {
        val app = context.applicationContext as? MinisApp ?: return
        app.applicationScope.launch {
            try {
                checkAndFireToday(app)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "check failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Completion hook, chained from the SessionActivityTracker completion
     * listener in MinisApp (same signal that raises the task-completed
     * notification). Called on the tracker's thread — only the cheap map
     * eviction happens here; the bookkeeping hops to the app scope.
     */
    fun onSessionSettled(context: Context, sessionId: String, isError: Boolean) {
        val app = context.applicationContext as? MinisApp ?: return
        val dispatch = dispatched.remove(sessionId) ?: return
        app.applicationScope.launch {
            finalizeRun(
                app = app,
                taskId = dispatch.taskId,
                outcome = if (isError) ScheduledTask.RESULT_ERROR else ScheduledTask.RESULT_OK,
                sessionId = sessionId,
                scheduledDate = dispatch.scheduledDate,
            )
        }
    }

    /**
     * [da1016 B-7c] The persisted task set changed: tasks were deleted, or the
     * whole document was replaced by a backup restore. Called by
     * [ScheduledTasksStore] — the single funnel every mutation of the stored
     * task list goes through.
     *
     * The store clears the persisted `running` marker for those runs
     * ([ScheduledTasksStore.deleteTask] / [ScheduledTasksStore.importDocument]),
     * but the in-process bookkeeping ([runningTaskId] + [dispatched]) survived
     * the deletion: the deleted task kept the single-flight lock until
     * [reapAbandonedDispatch]'s deadline+grace (30 min for a default task) and
     * every other task degraded to `overlap`, while a backup restore left the
     * lock pointing at a task the restored document knows nothing about.
     *
     * What this does NOT do: it does not cancel the in-flight run. A deleted
     * task's headless run keeps streaming into its own session and finishes on
     * its own; it merely stops being *managed* — its session is dropped from
     * [dispatched], so a late `onSessionSettled` is ignored instead of
     * settling (or resurrecting) a task that no longer exists
     * ([ScheduledTaskTransitions.completeRun] would no-op for a deleted id, but
     * a restore can legally re-create the same id, and then the late settle
     * would count a run this process never dispatched).
     *
     * @param removedTaskIds task ids that disappeared from the document, or
     *   `null` when the whole document was replaced (backup restore) — then
     *   every in-process run is released.
     *
     * LOCK ORDER (do not call this while holding [ScheduledTasksStore]'s
     * mutex): this takes [checkMutex], which [checkAndFireToday] holds across a
     * whole fire pass — and a fire pass calls back into the store
     * (`markRunning`). Release first, then notify: the stores' call sites do
     * exactly that, and `scripts/scan/scheduled_store_release_guard.py` fails
     * if a call ever moves inside the store lock.
     */
    suspend fun onTasksRemoved(removedTaskIds: Set<String>?) {
        checkMutex.withLock {
            val snapshot = dispatched.toMap()
            val plan = InMemoryRunBookkeeping.planRemoval(
                InMemoryRunBookkeeping.State(
                    runningTaskId = runningTaskId,
                    dispatchedTaskIdBySession = snapshot.mapValues { it.value.taskId },
                ),
                removedTaskIds = removedTaskIds,
            )
            if (plan.isEmpty) return@withLock
            AppLogger.info(
                TAG,
                "release removed=${removedTaskIds?.joinToString(",") ?: "all"} " +
                    "sessions=${plan.dropSessions.size} lockReleased=${plan.clearRunningTaskId}",
            )
            for (sid in plan.dropSessions) {
                // Compare-and-remove: only drop the exact dispatch we decided
                // about. (Removal by key alone would be equivalent today — a
                // scheduled run gets a freshly created session id and never
                // reuses one — but the CAS keeps that independent of the
                // session-creation path.)
                val expected = snapshot[sid] ?: continue
                if (dispatched.remove(sid, expected)) {
                    // The abandoned run already snapshotted its budget at run
                    // entry (AgentLoopEngine reads the ceiling once, at
                    // runAgentLoop ENTER), so clearing now cannot loosen a live
                    // run's caps — while leaving it would leak one entry per
                    // released run for the life of the process.
                    AgentRunBudgetCeilings.clear(sid)
                }
            }
            if (plan.clearRunningTaskId) runningTaskId = null
        }
    }

    // ------------------------------------------------------------------
    // Check pass
    // ------------------------------------------------------------------

    // [B27] The whole read-evaluate-mark-running pass — including the fire —
    // is atomic under checkMutex, so two concurrent foreground beats cannot
    // both observe the same due task and fire it twice.
    // [FIX-R3-E F-04A] The lock now also excludes [finalizeRun]'s settlement:
    // `load` snapshot → `evaluate` verdict and "persist the run + reset
    // runningTaskId" can no longer interleave, which was how a daily task
    // could fire twice on the same day (evaluate pairing a stale task list —
    // run not yet credited — with an already-nulled runningTaskId, defeating
    // ALREADY_RAN and OVERLAP at once). LOCK ORDER still holds: this path
    // takes checkMutex -> store lock (load/markRunning/completeRun), and the
    // store's call sites keep onTasksRemoved outside the store lock.
    internal suspend fun checkAndFireToday(app: MinisApp) = checkMutex.withLock {
        val gate = ScheduledTasksStore.load(app)
        if (!gate.globalEnabled) return

        reapAbandonedDispatch(app)

        // Crash recovery: a persisted marker with no in-process run means the
        // process died mid-run — count one failure and continue on the
        // recovered state (brief §3).
        val file = ScheduledTasksStore.load(app)
        var current = file
        val marker = file.running
        if (marker != null && marker.taskId != runningTaskId) {
            val res = ScheduledTasksStore.crashFailure(app, marker.taskId)
            current = res.file
            AppLogger.info(TAG, "abort task=${marker.taskId} reason=process-restart")
            MemorySpikeRecorder.onEvent(TAG, "abort task=${marker.taskId} reason=process-restart")
            maybeNotifyFuse(app, res, marker.sessionId)
        }

        val now = LocalDateTime.now()
        val today = now.toLocalDate().toString()
        val nowMinute = now.hour * 60 + now.minute
        val verdicts = ScheduledTaskPolicy.evaluate(today, nowMinute, current.tasks, runningTaskId)
        for (v in verdicts) logVerdict(v, today)
        val fireId = verdicts.firstOrNull { it is ScheduledTaskPolicy.PassVerdict.Fire }?.taskId ?: return
        val task = current.tasks.firstOrNull { it.id == fireId } ?: return
        // [deep-audit-1016c ISSUE-09A] The scheduled day rides along the
        // dispatch record: the settle path reads it back from [dispatched] and
        // credits THIS day (the day the fire pass observed the task due), not
        // the day the run happens to settle on (a 23:55 run settling at 00:06
        // used to pin tomorrow's date and skip tomorrow's whole window).
        fire(app, task, today)
    }

    /**
     * A dispatch whose message never turned into a stream (sendMessage dropped
     * it) leaves nothing that will ever settle. Free the single-flight gate and
     * count a failure so the task can retry on a later beat.
     */
    private suspend fun reapAbandonedDispatch(app: MinisApp) {
        if (dispatched.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        for ((sid, d) in dispatched) {
            if (nowMs - d.startedAtMs <= d.deadlineSec * 1000L + REAP_GRACE_MS) continue
            if (isRunAlive(sid)) continue
            if (!dispatched.remove(sid, d)) continue
            AppLogger.info(TAG, "abort task=${d.taskId} reason=stale-dispatch")
            MemorySpikeRecorder.onEvent(TAG, "abort task=${d.taskId} reason=stale-dispatch")
            // Already under checkMutex (called from checkAndFireToday) — the
            // locked body, not the locking entry point ([FIX-R3-E F-04A]).
            finalizeLocked(app, d.taskId, ScheduledTask.RESULT_ERROR, sid, d.scheduledDate)
        }
    }

    /** Liveness across the whole dispatch lifetime: queued → streaming → settling. */
    private fun isRunAlive(sessionId: String): Boolean =
        SessionActivityTracker.isActive(sessionId) ||
            SessionConcurrencyManager.isSuspended(sessionId) ||
            SessionConcurrencyManager.runningSessions.value.contains(sessionId)

    // ------------------------------------------------------------------
    // Fire
    // ------------------------------------------------------------------

    private suspend fun fire(app: MinisApp, task: ScheduledTask, scheduledDate: String) {
        runningTaskId = task.id
        var sessionId: String? = null
        try {
            val sid = prepareSession(app, task)
            sessionId = sid
            AgentRunBudgetCeilings.register(
                sid,
                AgentRunBudgetCeilings.Ceiling(
                    deadlineMs = task.deadlineSec * 1000L,
                    maxTurns = task.maxTurns,
                    maxEstimatedTokens = task.maxEstimatedTokens,
                ),
            )
            // [FIX-R3-E F-04B] Re-admission gate. `prepareSession` suspends
            // WITHOUT the store lock; a delete/backup-restore that lands in
            // that window removes the task (and clears any marker), then
            // blocks on [checkMutex] in [onTasksRemoved] until this fire pass
            // releases it. The old code wrote the marker back unconditionally
            // here — resurrecting a `running` entry for a task that no longer
            // exists — and dispatched the headless run anyway. markRunning now
            // refuses to admit a task that is no longer in the document (see
            // [ScheduledTaskStorePolicy.markRunningDecision]); when it
            // refuses, drop everything this fire pass created: the budget
            // ceiling, the in-process single-flight lock — no dispatch record,
            // no prompt. The queued [onTasksRemoved] then finds nothing to
            // release, and no orphan marker is left on disk (the store never
            // wrote one; delete/restore already cleared it).
            val marked = ScheduledTasksStore.markRunning(app, task.id, sid, System.currentTimeMillis())
            if (!ScheduledTaskStorePolicy.markerLanded(marked, task.id)) {
                AppLogger.info(TAG, "abandon task=${task.id} reason=removed-mid-fire")
                MemorySpikeRecorder.onEvent(TAG, "abandon task=${task.id} reason=removed-mid-fire")
                // [R5-B2 C-06①] `prepareSession` already INSERTED this session
                // (source="scheduled", titled after the task) — it is the only
                // thing this fire pass leaves behind on the early return, and
                // it would sit in the user's session drawer forever as an empty
                // "scheduled" chat. It has never carried a prompt (this branch
                // returns before ChatMutationMethods.prompt), so nothing the
                // user wrote or saw lives in it: delete the session this pass
                // created, not "the session" — [sid] is a fresh id from
                // [HeadlessChatRunner.ensureSession], never a reused one.
                dropNewSession(app, sid)
                if (runningTaskId == task.id) runningTaskId = null
                AgentRunBudgetCeilings.clear(sid)
                return
            }
            dispatched[sid] = Dispatch(
                taskId = task.id,
                deadlineSec = task.deadlineSec,
                startedAtMs = System.currentTimeMillis(),
                scheduledDate = scheduledDate,
            )
            AppLogger.info(TAG, "fire task=${task.id} session=$sid")
            MemorySpikeRecorder.onEvent(TAG, "fire task=${task.id}")

            // wait=false: returns "Running" once the message actually started a
            // stream, or "Error" (+ a responseText reason) when the send path
            // refused it — the headless runner reads `isStreaming` right after
            // sendMessage (B-7b), so a dropped send no longer reports
            // "Running" and no longer holds the single-flight lock until the
            // reaper fires. Completion arrives via onSessionSettled(); a run
            // that dies mid-flight is reaped on a later beat.
            val result = ChatMutationMethods.prompt(
                app,
                JSONObject().apply {
                    put("prompt", buildDispatchText(task))
                    put("sessionId", sid)
                    put("wait", false)
                },
            )
            val status = result.optString("status", "")
            if (status != "Running") {
                if (dispatched.remove(sid) != null) {
                    AppLogger.warning(
                        TAG,
                        "dispatch produced no stream task=${task.id} status=$status " +
                            "reason=${result.optString("responseText", "")}",
                    )
                    // Already under checkMutex via checkAndFireToday.
                    finalizeLocked(app, task.id, ScheduledTask.RESULT_ERROR, sid, scheduledDate)
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "dispatch failed task=${task.id}: ${t.javaClass.simpleName}: ${t.message}")
            val sid = sessionId
            val pending = if (sid != null) dispatched.remove(sid) else null
            if (sid == null || pending != null) {
                // No dispatch record -> no settle can ever arrive; finalize now.
                // [ISSUE-09A] The run never started: outcome is RESULT_ERROR so
                // the gate never advances; the scheduled day still attributes
                // the attempt to the day the fire pass observed it due (the
                // fire-time `today` this function received — available even
                // when prepareSession threw before a dispatch record existed).
                // fire() is only ever called from checkAndFireToday, which
                // holds checkMutex — so the locked body is the right call here
                // too ([FIX-R3-E F-04A]: every settlement funnels through the
                // same lock as the check pass).
                finalizeLocked(app, task.id, ScheduledTask.RESULT_ERROR, sid, scheduledDate)
            }
        }
    }

    private suspend fun prepareSession(app: MinisApp, task: ScheduledTask): String {
        val sessionId = HeadlessChatRunner.ensureSession(app)
        // [R5-B2 C-06①] `ensureSession` has already INSERTED the row. If either
        // presentation step below throws, [fire]'s catch block cannot name the
        // session (its `sessionId` local is still null), so the empty
        // `scheduled` session would stay in the drawer forever. Drop the
        // session THIS pass created, then rethrow: the fire path still settles
        // the run exactly as before.
        try {
            app.chatRepository.dao.updateSource(sessionId, "scheduled")
            app.chatRepository.updateSessionTitle(
                sessionId,
                task.title.ifBlank { app.getString(R.string.scheduled_tasks_title) },
            )
        } catch (t: Throwable) {
            dropNewSession(app, sessionId)
            throw t
        }
        return sessionId
    }

    /**
     * [R5-B2 C-06①] Delete a session this fire pass created and never used.
     *
     * The orphan: `fire` used to leave the freshly created `scheduled` session
     * behind on both paths that abandon a run before dispatch — the
     * `!markerLanded` early return (task deleted/disabled, or the marker write
     * refused, during the `prepareSession` suspension) and a throwing
     * `prepareSession`. Nothing was ever prompted into it, so it is an empty
     * chat in the session drawer with a task title, one per abandoned fire.
     *
     * Scope: the CALLER decides that the id is disposable (this pass created
     * it, no prompt was sent), so this helper never looks up ownership — it
     * only contains the failure, because a failed cleanup must not take the
     * fire path's own error handling down with it.
     */
    private suspend fun dropNewSession(app: MinisApp, sessionId: String) {
        try {
            val result = app.chatRepository.deleteSession(sessionId)
            AppLogger.info(
                TAG,
                "dropped unused session=$sessionId" +
                    if (result != null && !result.fullyDeleted) " (partial on-disk reclamation)" else "",
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "could not drop unused session=$sessionId: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Settle a run this process dispatched. [FIX-R3-E F-04A] Takes
     * [checkMutex]: the settlement (persisted `lastRunDate` + the
     * [runningTaskId] reset) used to run OUTSIDE it, so a foreground
     * `checkAndFireToday` interleaving between its `load` snapshot and its
     * [ScheduledTaskPolicy.evaluate] call saw the STALE task list (the run not
     * yet credited) together with the FRESH null [runningTaskId] — `ALREADY_RAN`
     * and `OVERLAP` both failed and the daily task fired a second time the same
     * day. Under [checkMutex] the two sequences are both safe: check first →
     * evaluate sees the still-set [runningTaskId] → OVERLAP; settle first →
     * evaluate's snapshot carries the credited `lastRunDate` → ALREADY_RAN.
     *
     * The callers that are ALREADY under [checkMutex] (the reaper and `fire`'s
     * failure paths, both inside `checkAndFireToday`) call [finalizeLocked]
     * directly — kotlinx Mutex is not reentrant.
     */
    private suspend fun finalizeRun(
        app: MinisApp,
        taskId: String,
        outcome: String,
        sessionId: String?,
        scheduledDate: String,
    ) {
        checkMutex.withLock {
            finalizeLocked(app, taskId, outcome, sessionId, scheduledDate)
        }
    }

    /** The settlement body; caller MUST already hold [checkMutex]. */
    private suspend fun finalizeLocked(
        app: MinisApp,
        taskId: String,
        outcome: String,
        sessionId: String?,
        scheduledDate: String,
    ) {
        sessionId?.let { AgentRunBudgetCeilings.clear(it) }
        val res = try {
            // [deep-audit-1016c ISSUE-09A] Credit the day the run was FIRED on
            // (the same `today` the evaluate gate compared lastRunDate against,
            // threaded through Dispatch.scheduledDate), never the settlement-day
            // clock read. Settlement past local midnight used to pin tomorrow's
            // date here, making the gate see the task as already-run for the
            // whole of tomorrow.
            ScheduledTasksStore.completeRun(
                app,
                taskId,
                outcome,
                ScheduledRunDating.gateDateOnSettle(scheduledDate, LocalDate.now().toString(), outcome),
            )
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "finalize failed task=$taskId: ${t.javaClass.simpleName}")
            if (runningTaskId == taskId) runningTaskId = null
            return
        }
        if (runningTaskId == taskId) runningTaskId = null
        if (outcome == ScheduledTask.RESULT_OK) {
            AppLogger.info(TAG, "done task=$taskId result=ok")
            MemorySpikeRecorder.onEvent(TAG, "done task=$taskId result=ok")
        } else {
            AppLogger.info(TAG, "fail task=$taskId result=$outcome streak=${res.task?.failStreak ?: 0}")
            MemorySpikeRecorder.onEvent(TAG, "fail task=$taskId result=$outcome")
        }
        maybeNotifyFuse(app, res, sessionId)
    }

    private fun maybeNotifyFuse(app: MinisApp, res: ScheduledTaskTransitions.Result, sessionId: String?) {
        val task = res.task ?: return
        if (!res.fuseTripped) return
        AppLogger.info(TAG, "suspend task=${task.id} reason=fail-fuse")
        MemorySpikeRecorder.onEvent(TAG, "suspend task=${task.id} reason=fail-fuse")
        app.backgroundTaskNotifier.notifyWorkCompleted(
            tag = "scheduled-fuse-${task.id}",
            title = app.getString(R.string.scheduled_fuse_notification_title),
            body = app.getString(
                R.string.scheduled_fuse_notification_body,
                task.title,
                ScheduledTaskPolicy.FUSE_THRESHOLD,
            ),
            deepLink = sessionId?.takeIf { it.isNotBlank() }?.let { "minis://session/$it" },
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Prompt-head injection (brief §5.1): identity + budget declaration so the
     * model knows no human is present and to finish inside its caps.
     */
    private fun buildDispatchText(task: ScheduledTask): String = buildString {
        append("[Automated scheduled task — \"").append(task.title).append("\"]\n")
        append("This session was started automatically by the user's scheduler; no human is present right now.\n")
        append("Execute the task below directly — do not ask questions or wait for confirmation. ")
        append("If it is impossible or ambiguous, state why briefly and finish.\n")
        append("Budget: up to ").append(task.maxTurns).append(" agent turns, ")
        append(task.deadlineSec).append(" seconds wall-clock, ~").append(task.maxEstimatedTokens)
        append(" estimated tokens; the run stops when any limit is reached.\n")
        append("---\n")
        append(task.prompt)
    }

    private fun logVerdict(v: ScheduledTaskPolicy.PassVerdict, today: String) {
        when (v) {
            is ScheduledTaskPolicy.PassVerdict.Fire -> Unit // logged by fire() with its session id
            is ScheduledTaskPolicy.PassVerdict.Skip ->
                AppLogger.info(TAG, "skip task=${v.taskId} reason=${v.reason.wire}")
            is ScheduledTaskPolicy.PassVerdict.Miss ->
                if (missLogged.add("${v.taskId}|$today")) {
                    AppLogger.info(TAG, "miss task=${v.taskId} reason=window-passed")
                }
        }
    }
}

/**
 * [da1016 B-7c] Pure decision core for releasing the runner's IN-PROCESS
 * single-flight bookkeeping when the persisted task set changes (task deleted
 * / document replaced by a backup restore). Kept as a separate pure object —
 * the same file-placing convention as `ScheduledTaskTransitions` inside
 * `ScheduledTaskPolicy.kt` — so the JVM tests can drive every branch without
 * instantiating the Android-bound runner.
 *
 * Input is a *snapshot*; the runner applies the plan with compare-and-remove
 * so a concurrent settle/reap still wins its own race against this release.
 */
internal object InMemoryRunBookkeeping {

    /** @param dispatchedTaskIdBySession sessionId → the taskId it is running for. */
    data class State(
        val runningTaskId: String?,
        val dispatchedTaskIdBySession: Map<String, String>,
    )

    /**
     * @param dropSessions session ids whose dispatch entries must be removed
     *   (so a late settle finds nothing and is ignored).
     * @param clearRunningTaskId whether [State.runningTaskId] must be cleared
     *   (freeing the single-flight lock for other tasks).
     */
    data class Plan(
        val dropSessions: List<String>,
        val clearRunningTaskId: Boolean,
    ) {
        val isEmpty: Boolean get() = dropSessions.isEmpty() && !clearRunningTaskId
    }

    /**
     * Decide what in-process state must be released.
     *
     * - `removedTaskIds == null` → the document was replaced wholesale (backup
     *   restore): drop every dispatch and clear the lock whatever it points
     *   at — the restored document has no marker either
     *   (`importDocument` writes `running = null`), so an in-process run the
     *   restore invalidated must not keep managing the new document.
     * - otherwise → drop exactly the sessions running a removed task, and
     *   clear the lock only if it points at a removed task. Deleting an
     *   unrelated (or nonexistent) id must not touch a live run: the lock
     *   stays with the running task.
     */
    fun planRemoval(state: State, removedTaskIds: Set<String>?): Plan {
        val dropSessions = state.dispatchedTaskIdBySession.entries
            .filter { (_, taskId) ->
                removedTaskIds == null || taskId in removedTaskIds
            }
            .map { (sid, _) -> sid }
            .sorted() // deterministic for tests/logs; the CHM order is not.
        val clearRunningTaskId = when (removedTaskIds) {
            null -> state.runningTaskId != null
            else -> state.runningTaskId != null && state.runningTaskId in removedTaskIds
        }
        return Plan(dropSessions = dropSessions, clearRunningTaskId = clearRunningTaskId)
    }
}

/**
 * [deep-audit-1016c ISSUE-09A] Pure decision core for the date a settling run
 * writes into the daily gate. Companion to [InMemoryRunBookkeeping]: pure JVM,
 * so the harness can drive every arm without instantiating the Android-bound
 * runner. The wiring it documents lives in [ScheduledTaskRunner.finalizeRun].
 */
internal object ScheduledRunDating {

    /**
     * The `today` argument a settled run hands to
     * [ScheduledTasksStore.completeRun] — the write that advances the daily
     * gate ([ScheduledTask.lastRunDate], compared by
     * [ScheduledTaskPolicy.evaluate] as `lastRunDate == nowDate` → ALREADY_RAN).
     *
     * Decision: the run is credited to its SCHEDULED local calendar day —
     * captured when the fire pass observed the task due and threaded through
     * [ScheduledTaskRunner.Dispatch.scheduledDate] — never to the day the run
     * happened to settle on. A run fired at 23:55 that settles at 00:06 used
     * to pin tomorrow's date here and skip tomorrow's whole window
     * (ALREADY_RAN) while having run nothing tomorrow; crediting the scheduled
     * day keeps the "one run per local calendar day, credited to the day that
     * was due" contract.
     *
     * @param scheduledDate fire-time local day, "YYYY-MM-DD" (Dispatch.scheduledDate).
     * @param settleDate settle-time local day. Accepted — and deliberately not
     *   used — so the decision is total over the facts the caller has and the
     *   rejected alternative (settle-day pinning, the defect shape) stays an
     *   addressable arm: swapping the return to [settleDate] is exactly the
     *   old behavior and must flip the harness's cross-midnight arm red.
     * @param outcome ScheduledTask.RESULT_OK / RESULT_ERROR / RESULT_TIMEOUT.
     *   Failures never advance the gate — [ScheduledTaskTransitions.completeRun]
     *   ignores the date for non-OK outcomes (brief §3 semantics, preserved
     *   verbatim) — so returning [scheduledDate] for those is inert at the
     *   store; [outcome] is part of the signature to keep that dependency
     *   explicit at the call site and addressable by the failure arm.
     */
    fun gateDateOnSettle(scheduledDate: String, settleDate: String, outcome: String): String = scheduledDate
}
