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
     */
    fun completeRun(
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
     * The persisted marker has no matching in-process run: the previous process
     * died mid-run. Counts one failure (brief §3 "并计 failStreak") and clears
     * the marker. No-op when the marker does not match [taskId].
     */
    fun crashFailure(file: ScheduledTasksFile, taskId: String): Result {
        if (file.running?.taskId != taskId) return Result(file, null, fuseTripped = false)
        return completeRun(file, taskId, ScheduledTask.RESULT_ERROR, today = "")
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
