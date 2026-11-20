package com.rikkaminis.app.scheduled

import org.json.JSONArray
import org.json.JSONObject

/**
 * [feat/scheduled-tasks-l0] One user-authored scheduled task.
 *
 * L0 semantics (task brief B): no cron, no alarm — a task is due when the app
 * comes to the foreground on a new day and the local wall-clock time sits
 * inside the task's daily window. "Fire once per calendar day, on the first
 * eligible foreground beat" mirrors AutoBackupManager's trigger shape.
 *
 * Persisted shape is defined by the brief §3; see [ScheduledTasksCodec].
 */
data class ScheduledTask(
    val id: String,
    val title: String,
    val prompt: String,
    /** "HH:MM" local time, inclusive start of the daily window. */
    val windowStart: String,
    /** "HH:MM" local time, exclusive end of the daily window. */
    val windowEnd: String,
    val enabled: Boolean = false,
    val maxTurns: Int = DEFAULT_MAX_TURNS,
    val deadlineSec: Int = DEFAULT_DEADLINE_SEC,
    val maxEstimatedTokens: Long = DEFAULT_MAX_TOKENS,
    /** "YYYY-MM-DD" (local) of the last COMPLETED run; failures do not advance it. */
    val lastRunDate: String? = null,
    /** "ok" | "error" | "timeout" — outcome of the last completed run. */
    val lastResult: String? = null,
    val failStreak: Int = 0,
    val suspendedByFuse: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_TURNS = 30
        const val DEFAULT_DEADLINE_SEC = 600
        const val DEFAULT_MAX_TOKENS = 100_000L

        const val RESULT_OK = "ok"
        const val RESULT_ERROR = "error"
        const val RESULT_TIMEOUT = "timeout"
    }
}

/**
 * Persisted crash-recovery marker: written right before a scheduled run is
 * dispatched and cleared when it settles. A marker found on a fresh process
 * (no matching in-memory dispatch) means the process died mid-run — brief §3:
 * "进程死 = 运行失败，lastRunDate 不推进，下个窗口重试并计 failStreak".
 */
data class ScheduledRunMarker(
    val taskId: String,
    val sessionId: String,
    val startedAt: Long,
)

/**
 * The identity of ONE scheduled run: which task it ran, and the session it
 * ran in.
 *
 * [A03 late settlement] A task id alone does not identify a run: the same task
 * id can be deleted, restored from a backup, re-created and dispatched again
 * while the previous run is still in flight, and every one of those documents
 * uses the SAME id. The session id does identify it — a scheduled dispatch
 * always mints a FRESH session ([com.rikkaminis.app.debug.HeadlessChatRunner.ensureSession]
 * inserts a new row with a random UUID) and never reuses one — so
 * (taskId, sessionId) is the run's identity, and it is what the settlement
 * presents to the document.
 *
 * It is a value object on purpose: the settlement funnels through ONE store
 * call whose argument list is fixed by `scripts/scan/schedule_date_source_check.py`
 * (app, run, outcome, gateDate), so the run's identity travels as ONE
 * argument instead of two positional ones that could be swapped.
 */
data class ScheduledRunRef(
    val taskId: String,
    /** `null` when the run never got a session (it never reached the document). */
    val sessionId: String?,
)

/**
 * Whole `scheduled-tasks.json` document. [globalEnabled] is the master switch
 * (§5.4, factory-off); [running] is the crash-recovery marker above.
 */
data class ScheduledTasksFile(
    val globalEnabled: Boolean = false,
    val running: ScheduledRunMarker? = null,
    val tasks: List<ScheduledTask> = emptyList(),
)

/**
 * Pure JSON <-> model codec, android-free so JVM tests can exercise it
 * directly (unit tests run against the real org.json artifact on the JVM).
 *
 * Parsing is defensive: one malformed task entry is dropped, never the whole
 * file; unknown keys are ignored (forward compatibility).
 */
object ScheduledTasksCodec {

    const val VERSION = 1

    fun parse(raw: String): ScheduledTasksFile {
        val root = JSONObject(raw)
        val tasks = ArrayList<ScheduledTask>()
        val arr = root.optJSONArray("tasks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val window = o.optJSONObject("window") ?: JSONObject()
            val budget = o.optJSONObject("budget") ?: JSONObject()
            tasks += ScheduledTask(
                id = id,
                title = o.optString("title", ""),
                prompt = o.optString("prompt", ""),
                // Missing window halves parse to "" -> policy reports
                // INVALID_WINDOW and the task never fires (honest skip + log).
                windowStart = window.optString("start", ""),
                windowEnd = window.optString("end", ""),
                enabled = o.optBoolean("enabled", false),
                maxTurns = budget.optInt("maxTurns", 0).takeIf { it > 0 } ?: ScheduledTask.DEFAULT_MAX_TURNS,
                deadlineSec = budget.optInt("deadlineSec", 0).takeIf { it > 0 } ?: ScheduledTask.DEFAULT_DEADLINE_SEC,
                maxEstimatedTokens = budget.optLong("maxEstimatedTokens", 0L).takeIf { it > 0 } ?: ScheduledTask.DEFAULT_MAX_TOKENS,
                lastRunDate = optNullableString(o, "lastRunDate"),
                lastResult = optNullableString(o, "lastResult"),
                failStreak = o.optInt("failStreak", 0).coerceAtLeast(0),
                suspendedByFuse = o.optBoolean("suspendedByFuse", false),
            )
        }
        return ScheduledTasksFile(
            globalEnabled = root.optBoolean("globalEnabled", false),
            running = parseMarker(root.optJSONObject("running")),
            tasks = tasks,
        )
    }

    fun encode(file: ScheduledTasksFile): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("globalEnabled", file.globalEnabled)
        file.running?.let { r ->
            root.put(
                "running",
                JSONObject().apply {
                    put("taskId", r.taskId)
                    put("sessionId", r.sessionId)
                    put("startedAt", r.startedAt)
                },
            )
        }
        val arr = JSONArray()
        for (t in file.tasks) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("prompt", t.prompt)
                    put(
                        "window",
                        JSONObject().apply {
                            put("start", t.windowStart)
                            put("end", t.windowEnd)
                        },
                    )
                    put("enabled", t.enabled)
                    put(
                        "budget",
                        JSONObject().apply {
                            put("maxTurns", t.maxTurns)
                            put("deadlineSec", t.deadlineSec)
                            put("maxEstimatedTokens", t.maxEstimatedTokens)
                        },
                    )
                    t.lastRunDate?.let { put("lastRunDate", it) }
                    t.lastResult?.let { put("lastResult", it) }
                    put("failStreak", t.failStreak)
                    put("suspendedByFuse", t.suspendedByFuse)
                },
            )
        }
        root.put("tasks", arr)
        return root.toString(2)
    }

    fun newTaskId(): String =
        "t-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    private fun optNullableString(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.optString(key).ifBlank { null } else null

    private fun parseMarker(o: JSONObject?): ScheduledRunMarker? {
        if (o == null) return null
        val taskId = o.optString("taskId", "").ifBlank { return null }
        return ScheduledRunMarker(
            taskId = taskId,
            sessionId = o.optString("sessionId", ""),
            startedAt = o.optLong("startedAt", 0L),
        )
    }
}
