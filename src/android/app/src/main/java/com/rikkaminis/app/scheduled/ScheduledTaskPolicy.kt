package com.rikkaminis.app.scheduled

/**
 * [feat/scheduled-tasks-l0] Pure decision logic for one foreground check pass.
 *
 * android-free by design so JVM unit tests can drive every branch with fixed
 * clock inputs (see ScheduledTaskPolicyTest).
 */
object ScheduledTaskPolicy {

    /** Consecutive completed failures that suspend a task (brief §5.3). */
    const val FUSE_THRESHOLD = 3

    /** Why a task was not fired this pass. `wire` values land in [scheduled-run] logs. */
    enum class SkipReason(val wire: String) {
        DISABLED("disabled"),
        FUSE("fuse"),
        ALREADY_RAN("already-ran"),
        OUTSIDE_WINDOW("outside-window"),
        INVALID_WINDOW("invalid-window"),
        OVERLAP("overlap"),
    }

    sealed interface PassVerdict {
        val taskId: String

        data class Fire(override val taskId: String) : PassVerdict
        data class Skip(override val taskId: String, val reason: SkipReason) : PassVerdict
        data class Miss(override val taskId: String) : PassVerdict
    }

    /**
     * "HH:MM" -> minutes since midnight. Strict: exactly two digits, two digits,
     * hour 0..23, minute 0..59. Null when the value is not a valid wall-clock time.
     */
    fun parseWindowMinuteOfDay(value: String): Int? {
        if (value.length != 5 || value[2] != ':') return null
        val hh = value.substring(0, 2).toIntOrNull() ?: return null
        val mm = value.substring(3, 5).toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }

    /** Half-open window: `[start, end)` in minutes-of-day. */
    fun isWithinWindow(nowMinuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean =
        nowMinuteOfDay >= startMinute && nowMinuteOfDay < endMinute

    /**
     * Decide, for every task in list order, what this pass should do.
     *
     * Single-flight (brief §5.2): at most ONE task may carry [PassVerdict.Fire]
     * per pass — the first due task in list order. When [runningTaskId] is
     * non-null a scheduled run is already in flight (or being finalised) and
     * every otherwise-due task degrades to [SkipReason.OVERLAP].
     *
     * @param nowDate local calendar day, "YYYY-MM-DD"
     * @param nowMinuteOfDay local wall-clock minutes since midnight
     */
    fun evaluate(
        nowDate: String,
        nowMinuteOfDay: Int,
        tasks: List<ScheduledTask>,
        runningTaskId: String?,
    ): List<PassVerdict> {
        var firing = runningTaskId != null
        val verdicts = ArrayList<PassVerdict>(tasks.size)
        for (task in tasks) {
            verdicts += verdictFor(task, nowDate, nowMinuteOfDay) {
                if (firing) {
                    PassVerdict.Skip(task.id, SkipReason.OVERLAP)
                } else {
                    firing = true
                    PassVerdict.Fire(task.id)
                }
            }
        }
        return verdicts
    }

    private inline fun verdictFor(
        task: ScheduledTask,
        nowDate: String,
        nowMinuteOfDay: Int,
        eligible: () -> PassVerdict,
    ): PassVerdict {
        if (!task.enabled) return PassVerdict.Skip(task.id, SkipReason.DISABLED)
        if (task.suspendedByFuse) return PassVerdict.Skip(task.id, SkipReason.FUSE)
        val start = parseWindowMinuteOfDay(task.windowStart)
        val end = parseWindowMinuteOfDay(task.windowEnd)
        if (start == null || end == null || start >= end) {
            return PassVerdict.Skip(task.id, SkipReason.INVALID_WINDOW)
        }
        if (task.lastRunDate == nowDate) {
            return PassVerdict.Skip(task.id, SkipReason.ALREADY_RAN)
        }
        if (nowMinuteOfDay < start) return PassVerdict.Skip(task.id, SkipReason.OUTSIDE_WINDOW)
        if (nowMinuteOfDay >= end) return PassVerdict.Miss(task.id)
        return eligible()
    }
}

object ScheduledTaskGenerationPolicy {
    fun admits(
        run: ScheduledRunRef,
        taskGeneration: Long,
        globalGeneration: Long,
        documentGeneration: Long,
    ): Boolean = run.taskGeneration == taskGeneration &&
        run.globalGeneration == globalGeneration && run.documentGeneration == documentGeneration

    fun invalidated(
        run: ScheduledRunRef,
        removedTaskIds: Set<String>?,
        watermark: ScheduledTaskRemovalWatermark,
    ): Boolean = run.documentGeneration < watermark.documentGeneration ||
        (removedTaskIds != null && run.taskId in removedTaskIds &&
            run.taskGeneration < (watermark.generations[run.taskId] ?: run.taskGeneration))
}

object ScheduledTaskRunOwnership {

    /**
     * Whether [marker] names the exact run that is settling.
     *
     * Identity = task id + the run's session id. A scheduled dispatch mints a
     * FRESH session ([com.rikkaminis.app.debug.HeadlessChatRunner.ensureSession]
     * inserts a new row with a random UUID) and never reuses one, so the
     * session id is the run id: two runs of the same task never share it, and
     * a deleted/re-created or restored task with the same id cannot inherit it.
     * The marker is persisted, so the comparison survives the failure paths
     * that read the document back from disk.
     *
     * A null/blank session id is never an identity: a settlement that cannot
     * name its run writes nothing (fail-closed).
     */
    fun ownsMarker(
        marker: ScheduledRunMarker?,
        run: ScheduledRunRef,
    ): Boolean = marker != null &&
        !run.sessionId.isNullOrBlank() &&
        marker.taskId == run.taskId &&
        marker.sessionId == run.sessionId

    /**
     * Whether the in-process reservation ([activeTaskId] / [activeSessionId])
     * belongs to the run that is settling — i.e. whether a settle may clear it.
     *
     * Same identity as [ownsMarker], applied to the runner's memory instead of
     * the document: an old run's late finalize must not drop the lock a newer
     * run of the same task holds. A reservation that never got a session
     * (fire failed before `prepareSession` returned) matches only a settle that
     * also carries no session.
     */
    fun ownsActiveRun(
        activeTaskId: String?,
        activeSessionId: String?,
        run: ScheduledRunRef,
    ): Boolean = activeTaskId != null &&
        activeTaskId == run.taskId &&
        activeSessionId == run.sessionId
}

/**
 * [feat/scheduled-tasks-l0] Pure state transitions applied to the whole file
 * document. Every function is total: unknown task ids pass the document
 * through unchanged so callers never have to guard for races with deletion.
 */
object ScheduledTaskTransitions {

    data class Result(
        val file: ScheduledTasksFile,
        val task: ScheduledTask?,
        val fuseTripped: Boolean,
    )

    /**
     * A scheduled run settled. [outcome] is one of "ok" / "error" / "timeout".
     *
     * Success advances lastRunDate (daily gate) and resets the streak; a failed
     * run leaves lastRunDate untouched so the task retries on the next eligible
     * foreground beat inside the same window (brief §3 crash semantics share
     * this shape). The persisted run marker is cleared either way.
     *
     * [A03 late settlement] The write is gated on run IDENTITY: only the run
     * [file]'s `running` marker names may be credited — see
     * [ScheduledTaskRunOwnership.ownsMarker]. A delete, a backup restore, or a
     * re-created task with the same id leaves no marker carrying this session,
     * so the late settle returns [file] UNCHANGED (identity: the store skips the
     * write) and cannot stamp `lastRunDate` / `result` / `failStreak` onto a
     * task this run never ran. A newer run of the same task holds the marker
     * under a different session id, so the stale settle leaves it alone too.
     */
    fun completeRun(
        file: ScheduledTasksFile,
        run: ScheduledRunRef,
        outcome: String,
        today: String,
    ): Result {
        if (!ScheduledTaskRunOwnership.ownsMarker(file.running, run)) {
            return Result(file, null, fuseTripped = false)
        }
        return settleRun(file, run.taskId, outcome, today)
    }

    /**
     * The crediting body behind [completeRun]. The caller has already
     * established that the settling run is the one the document names (or, for
     * [crashFailure], that the marker has no in-process owner at all).
     */
    private fun settleRun(
        file: ScheduledTasksFile,
        taskId: String,
        outcome: String,
        today: String,
    ): Result {
        val idx = file.tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) {
            // Task deleted while its run was in flight: just clear the marker.
            return Result(clearMarker(file, taskId), null, fuseTripped = false)
        }
        val ok = outcome == ScheduledTask.RESULT_OK
        val updated = file.tasks[idx].let { t ->
            if (ok) {
                t.copy(
                    lastRunDate = today,
                    lastResult = ScheduledTask.RESULT_OK,
                    failStreak = 0,
                )
            } else {
                val streak = t.failStreak + 1
                val fuse = streak >= ScheduledTaskPolicy.FUSE_THRESHOLD
                t.copy(
                    lastResult = outcome,
                    failStreak = streak,
                    enabled = if (fuse) false else t.enabled,
                    suspendedByFuse = if (fuse) true else t.suspendedByFuse,
                )
            }
        }
        val next = clearMarker(file, taskId).let { f ->
            f.copy(tasks = f.tasks.toMutableList().also { it[idx] = updated })
        }
        return Result(
            file = next,
            task = updated,
            fuseTripped = !ok && updated.failStreak >= ScheduledTaskPolicy.FUSE_THRESHOLD,
        )
    }

    /**
     * A dispatch that never got as far as a session (session creation or the
     * scheduled-title write threw). It wrote no `running` marker, so it has no
     * identity to prove itself with — see [ScheduledTaskRunOwnership].
     *
     * The credit is therefore allowed only when the document carries NO marker
     * at all: under the single-flight gate a marker at this instant belongs to
     * some other run, and crediting through it would be the very defect this
     * change removes. Callers pass [ScheduledTask.RESULT_ERROR] only — a run
     * that never started cannot have succeeded — so the worst a stale credit
     * can do is count one failure, never fake a successful run or advance
     * [ScheduledTask.lastRunDate].
     */
    fun completeUnstartedRun(
        file: ScheduledTasksFile,
        taskId: String,
        outcome: String,
        today: String,
    ): Result {
        if (file.running != null) return Result(file, null, fuseTripped = false)
        return settleRun(file, taskId, outcome, today)
    }

    /**
     * The persisted marker has no matching in-process run: the previous process
     * died mid-run. Counts one failure (brief §3 "并计 failStreak") and clears
     * the marker. No-op when the marker does not match [taskId].
     *
     * Deliberately takes the non-gated [settleRun]: a crash has no settling
     * session to name itself with — the marker IS the evidence, and the check
     * just above ("no in-process run owns it") is what authorises the credit.
     */
    fun crashFailure(file: ScheduledTasksFile, taskId: String): Result {
        if (file.running?.taskId != taskId) return Result(file, null, fuseTripped = false)
        return settleRun(file, taskId, ScheduledTask.RESULT_ERROR, today = "")
    }

    /** Manual resume from the settings UI: clears the fuse and re-enables. */
    fun resume(file: ScheduledTasksFile, taskId: String): ScheduledTasksFile {
        val idx = file.tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return file
        val updated = file.tasks[idx].copy(
            suspendedByFuse = false,
            failStreak = 0,
            enabled = true,
        )
        return file.copy(tasks = file.tasks.toMutableList().also { it[idx] = updated })
    }

    /**
     * Apply an editor save: the editable SPEC fields come from [task], every
     * run-state field stays whatever the store already holds.
     *
     * Why this exists (deep-audit-1030 ISSUE-08): the editor used to hand its
     * whole record to `upsertTask`, which wrote it over the stored one. But a
     * `ScheduledTask` carries run state (`lastRunDate` / `lastResult` /
     * `failStreak` / `suspendedByFuse`) and `enabled` in the SAME record, and
     * the editor snapshot is taken when the screen opens. Any run (including a
     * fuse trip) that settles during the edit window — up to the full 60 s
     * prompt budget — was silently erased by the save. Editorial intent is
     * "change the spec"; run state is owned by the runner and [ScheduledTasksStore],
     * so a save must not be able to speak about it at all.
     *
     * `enabled` is deliberately NOT carried over either: the editor has no
     * switch for it (the row switch in ScheduledTasksScreen is the explicit
     * control, and the editor's own snapshot is a copy of it), so a stale copy
     * is the only thing that field could contribute — and it would defeat a
     * fuse trip or a manual disable that happened mid-edit.
     *
     * An unknown id is appended as-is: a brand-new task has no run state to
     * preserve, and the editor already seeds new tasks disabled (brief §5.4).
     *
     * [deep-audit-1046 S-11] `allowInsert` exists because "unknown id" has TWO
     * meanings and a save must tell them apart. A new task really is unknown
     * (the editor minted the id a moment ago) — that one appends. An EDIT whose
     * target vanished is also unknown, and appending it resurrects a record the
     * user (or a backup restore) has deleted, carrying whatever run state the
     * form snapshot held (`enabled = prior.enabled`, the last `lastRunDate`, the
     * previously tripped fuse). So the editing surface passes
     * `allowInsert = false`: an unknown id then leaves the document untouched
     * (the very same instance, so `ScheduledTasksStore.mutate` skips the write
     * and the revision does not bump) and the caller reports the target as
     * missing instead of pretending the save succeeded.
     */
    fun mergeUpsertTask(
        file: ScheduledTasksFile,
        task: ScheduledTask,
        allowInsert: Boolean = true,
    ): ScheduledTasksFile {
        val idx = file.tasks.indexOfFirst { it.id == task.id }
        if (idx < 0) {
            if (!allowInsert) return file
            return file.copy(tasks = file.tasks + task)
        }
        val existing = file.tasks[idx]
        val merged = existing.copy(
            title = task.title,
            prompt = task.prompt,
            windowStart = task.windowStart,
            windowEnd = task.windowEnd,
            maxTurns = task.maxTurns,
            deadlineSec = task.deadlineSec,
            maxEstimatedTokens = task.maxEstimatedTokens,
        )
        return file.copy(tasks = file.tasks.toMutableList().also { it[idx] = merged })
    }

    private fun clearMarker(file: ScheduledTasksFile, taskId: String): ScheduledTasksFile =
        if (file.running?.taskId == taskId) file.copy(running = null) else file

    /**
     * [WAVE-1118 D7 F-05] Drop the persisted `running` marker for [taskId]
     * WITHOUT crediting any result.
     *
     * The settlement path ([ScheduledTasksStore.completeRun]) both credits the
     * run and clears the marker in one write; when that write never lands (the
     * runner's `finalizeLocked` catch block), the marker stayed on disk while
     * the in-memory single-flight lock was released. The next beat reads a
     * marker with no matching in-process run and concludes "the process died
     * mid-run" ([crashFailure]) — one failure the run did not earn, `failStreak`
     * +1, and enough of those trips the fuse and DISABLES the user's task.
     * Clearing the marker loses the credit for that run (same shape as any
     * failed settlement: `lastRunDate` stays empty and the task may retry inside
     * its window), which is recoverable; a phantom failure is not.
     *
     * [A03 late settlement] The clear is identity-scoped ([sessionId]): an old
     * run's compensation must not erase the marker a NEWER run of the same task
     * wrote. Identity when the marker names another task/session (or there is
     * none) means the document is returned unchanged, so a caller can detect
     * "nothing to do".
     */
    fun clearRunningMarker(
        file: ScheduledTasksFile,
        run: ScheduledRunRef,
    ): ScheduledTasksFile =
        if (ScheduledTaskRunOwnership.ownsMarker(file.running, run)) {
            file.copy(running = null)
        } else {
            file
        }

    /**
     * [deep-audit-1046 S-11] Refresh the RUN half of an editor's snapshot.
     *
     * The editor keeps `original` as "the record the save is an edit OF": its
     * spec fields feed nothing (the form's own text state does) but its run
     * state is what the save carries. When the store publishes a change while
     * the form is open, only that run half may move — the spec fields the user
     * is typing must come from the form, never from a refresh, or a background
     * run settling mid-typing would overwrite the title being edited.
     */
    fun refreshRunState(original: ScheduledTask, fresh: ScheduledTask): ScheduledTask =
        original.copy(
            enabled = fresh.enabled,
            lastRunDate = fresh.lastRunDate,
            lastResult = fresh.lastResult,
            failStreak = fresh.failStreak,
            suspendedByFuse = fresh.suspendedByFuse,
        )
}

/**
 * [deep-audit-1046 S-11] Decisions behind [ScheduledTasksStore]'s revision
 * stream — android-free so JVM tests can pin them with fixed inputs.
 *
 * Why a stream at all: every store read is a one-shot `load`, so a screen that
 * rendered once kept rendering that document forever. The list therefore never
 * saw a background run settle (`lastRunDate` / `lastResult` / `failStreak`) and
 * never saw the fuse trip — a suspended task's Resume button only appeared
 * after an ON_RESUME round-trip through the editor — and the editor never saw
 * its own target disappear.
 */
object ScheduledTaskRevisionPolicy {

    /**
     * The next revision value. A failed atomic write leaves the file exactly as
     * it was, so it must NOT bump: a bump would tell every open screen to
     * re-read a document that cannot have changed (and a retry loop would spin
     * the whole UI for nothing). Only a write that landed is a change.
     */
    fun nextRevision(current: Long, writeSucceeded: Boolean): Long =
        if (writeSucceeded) current + 1L else current

    /**
     * "My target is gone" — but only once the form actually loaded something.
     * On the very first frame the load effect owns the not-found decision; a
     * refresh that fires before it (e.g. a revision that moved while this screen
     * was still composing) must not pre-empt it with an empty form.
     */
    fun shouldMarkMissing(loadedOnce: Boolean, stillExists: Boolean): Boolean =
        loadedOnce && !stillExists
}

/**
 * [RC-U13] The editor's two save-time decisions, extracted so the JVM harness
 * can pin them without a Context (same split as [ScheduledTaskRevisionPolicy]).
 *
 * Context: brand-new tasks enter OFF on purpose (§5.4 — a task fires a prompt
 * and spends quota, so an accidental save must not arm it). What was missing was
 * the opposite intent: a user who built a task in order to run it had to save,
 * go back to the list, and flip the row switch — and on a fresh install also the
 * master switch. The default stays OFF; the opt-in switch is the one tap.
 */
object ScheduledTaskEditorPolicy {

    /** Notice the editor owes the user after a save that LANDED. */
    enum class SaveNotice {
        /** Enabled and the master switch is on — nothing to say. */
        NONE,

        /** Saved, but the task itself is off. */
        TASK_OFF,

        /** Saved, but the master switch keeps every task from running. */
        GLOBAL_OFF,
    }

    /**
     * What `enabled` the save writes.
     *
     * An edit keeps the stored state: the row switch is the control for that,
     * and an edit must never silently re-arm a task the user turned off (or one
     * the fuse suspended). A new task follows the "enable after saving" switch,
     * which defaults to false — §5.4's safe default, unchanged.
     */
    fun enabledOnSave(priorEnabled: Boolean?, enableAfterSave: Boolean): Boolean =
        priorEnabled ?: enableAfterSave

    /**
     * Which hint (if any) to show. The global check wins because it is the
     * wider gate: with the master switch off, an enabled task still does not
     * run, and telling the user only "the task is off" sends them back to a
     * screen where everything they can touch already looks enabled.
     */
    fun noticeAfterSave(taskEnabled: Boolean, globalEnabled: Boolean): SaveNotice = when {
        !globalEnabled -> SaveNotice.GLOBAL_OFF
        !taskEnabled -> SaveNotice.TASK_OFF
        else -> SaveNotice.NONE
    }
}

/**
 * [FIX-R3-E F-07 / F-04B] The two decisions [ScheduledTasksStore] makes around
 * every write, extracted so the JVM harness can drive them without a Context
 * (the store itself is Android-bound; the existing convention for exactly this
 * split is [ScheduledTaskRevisionPolicy]).
 *
 * Why these two:
 *  - **Visible-after-write**: `mutate` used to hand back the in-memory result
 *    whether or not the atomic write landed, and `importDocument` reported
 *    `applied = true` the same way. Both surfaces (the editor's "is my change
 *    in there" check, the restore report's `scheduledTasksImported`) then
 *    claimed a success that disk never saw.
 *  - **Marker admission**: `markRunning` wrote the crash-recovery marker for
 *    whatever id it was handed. A task deleted while `fire()` was suspended in
 *    its `prepareSession` window had its marker resurrected on disk (and its
 *    headless run started) by the fire pass that was already past the
 *    evaluation gate.
 */
internal object ScheduledTaskStorePolicy {

    /**
     * The document a read-modify-write hands back to its caller.
     *
     * @param current the pre-write document (what is still on disk).
     * @param next the transform's output.
     * @param writeSucceeded whether the atomic write landed.
     * @return [next] only when it was persisted; [current] otherwise — a caller
     *   that inspects the result (the editor checks `tasks.any { id }`) then
     *   sees the failure instead of a phantom success.
     */
    fun visibleAfterWrite(
        current: ScheduledTasksFile,
        next: ScheduledTasksFile,
        writeSucceeded: Boolean,
    ): ScheduledTasksFile = if (writeSucceeded) next else current

    /**
     * Whether the crash-recovery marker may be written for [taskId], and what
     * document to hand back.
     *
     * Admission requires the task to still be in the document — and enabled,
     * and the master switch on: all three are the fire pass's own preconditions
     * ([ScheduledTaskPolicy.evaluate] checks enabled + the window; the caller
     * checks `globalEnabled` once per pass). Between that pass and this write
     * stretch the whole `prepareSession` suspension, so any of them may have
     * been revoked by the user; a run whose marker no longer has a task to
     * belong to is a run nobody can settle.
     *
     * Refusal returns [file] UNCHANGED (identity), so `mutate` skips the write
     * entirely — no revision bump, no disk touch.
     */
    fun markRunningDecision(
        file: ScheduledTasksFile,
        taskId: String,
        sessionId: String,
        startedAt: Long,
    ): ScheduledTasksFile {
        if (!file.globalEnabled) return file
        if (file.tasks.none { it.id == taskId && it.enabled }) return file
        return file.copy(
            running = ScheduledRunMarker(taskId = taskId, sessionId = sessionId, startedAt = startedAt),
        )
    }

    /**
     * Whether [markRunningDecision] actually admitted [taskId] — the check
     * [ScheduledTaskRunner.fire] performs on the file it got back before
     * dispatching. Separate from the decision so the runner's wiring can be
     * shape-asserted while the decision stays table-driven.
     */
    fun markerLanded(file: ScheduledTasksFile, taskId: String): Boolean =
        file.running?.taskId == taskId
}
