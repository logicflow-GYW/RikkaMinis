package com.rikkaminis.app.diagnostics

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import java.io.File

/**
 * Records when a process launch begins and when the previous launch ended
 * cleanly. Lets us distinguish "the user backgrounded and we got cleaned
 * up by MIUI / LMK" (no clean-exit marker; previous run's last tick was
 * recent, in foreground) from "we crashed" (ACRA / native handler already
 * wrote a `.log` file) or "normal launch after explicit exit" (clean-exit
 * marker present).
 *
 * The beacon is one small JSON-ish line per launch under
 * `filesDir/logs/launch-beacon.log`. We don't roll it — entries are tiny
 * and a long history helps spot MIUI-kill patterns over days.
 *
 * Bug 2 in the v1.8-dev MIUI feedback report ("app crash" with no
 * crash-*.log file): adding this beacon means the next launch can log
 * "previous run had no clean exit and no crash report — likely LMK or
 * MIUI background cleanup", which is what we want to confirm.
 */
object LaunchCycleBeacon {

    private const val FILE_NAME = "launch-beacon.log"
    private const val TAG = "LaunchBeacon"

    /**
     * [T-android-render-breaker] True when the PREVIOUS app cycle ended in
     * crash_or_stall (set once at [recordLaunch]). Consumed by the chat Resume
     * banner: in the ANR-restart loop the banner was an unguarded "continue"
     * button that re-entered the exact load that killed the previous cycle —
     * with this flag it warns and requires a confirming second tap.
     */
    @Volatile
    var lastCycleWasCrash: Boolean = false
        private set

    /**
     * [T-android-larky-longsession-followup] Snapshot of `restartCount` from
     * the most recent [recordLaunch] call (the (launches − clean_exits) tail
     * count computed in the crash_or_stall branch). Stored verbatim so the
     * launch resolver can gate on it without re-reading the beacon file.
     *
     * Only updated when the prior verdict starts with "crash_or_stall" — for
     * any other verdict (clean_exit / silent_kill / first_launch /
     * no_prior_launch) this stays at 0, which means [shouldForceHomeOnLaunch]
     * naturally returns false for users who didn't actually crash.
     *
     * Reset semantics: the field is process-scoped. It's recomputed once per
     * process at [recordLaunch] (called from MinisApp.onCreate). A single
     * cycle that does NOT end in crash_or_stall — even silent_kill, which
     * dwarfs real crashes for daily users — resets the field to 0 on the
     * very next launch, restoring auto-recovery. So a user who hits a
     * crash-loop, then closes the app cleanly (or even gets MIUI-killed in
     * the background), is back to normal launch behavior on their next tap.
     */
    @Volatile
    var lastRestartCount: Int = 0
        private set

    /**
     * [T-android-larky-longsession-followup] Threshold for the
     * "skip auto-recovery" breaker. When the previous launch ended in
     * crash_or_stall AND [lastRestartCount] exceeds this value (strictly
     * greater than), the launch resolver lands on the session list instead
     * of auto-recovering the previous chat — protects against being thrown
     * straight back into a session that's killing the process.
     *
     * The "> 3" wording in the spec means a fourth consecutive bad cycle
     * triggers it; choosing 3 as the strict-greater-than threshold matches
     * that interpretation while leaving the user 3 free retries (which can
     * be normal if e.g. they hit a transient OOM that won't repeat).
     */
    const val RESTART_COUNT_FORCE_HOME_THRESHOLD: Int = 3

    /**
     * [T-android-larky-longsession-followup] True when the previous cycle
     * ended in crash_or_stall AND the rolling restart-count exceeds
     * [RESTART_COUNT_FORCE_HOME_THRESHOLD]. Joined with the existing
     * HangDetector / CrashFrequencyDetector breakers in AppNavigation's
     * launch resolver — any one of them flips the launch to mode 3 (home),
     * the others stay untouched.
     *
     * Always false for users whose previous cycle was clean_exit, silent_kill,
     * first_launch, or no_prior_launch — those reset [lastRestartCount] to 0
     * (it's only written in the crash_or_stall branch of [recordLaunch]).
     */
    fun shouldForceHomeOnLaunch(): Boolean =
        lastCycleWasCrash && lastRestartCount > RESTART_COUNT_FORCE_HOME_THRESHOLD

    fun recordLaunch(context: Context) {
        val file = beaconFile(context)
        val previousTail = readTail(file)
        val now = System.currentTimeMillis()
        val nowIso = isoLocal(now)

        // Inspect previous record to classify the prior cycle.
        val previousVerdict = classifyPrevious(previousTail, context, now)
        lastCycleWasCrash = previousVerdict.startsWith("crash_or_stall")
        AppLogger.info(TAG, "launch verdict for previous cycle: $previousVerdict")

        // [T-android-perf-logging] When the previous cycle ended in
        // crash_or_stall, surface a structured Perf line so a low-memory
        // ANR repro can be correlated against a recovery loop. restartCount
        // = how many of the recent launch records are themselves preceded by
        // crash artefacts (i.e. consecutive bad cycles) — a climbing count is
        // the signature of "recovery keeps re-crashing on the same session".
        if (previousVerdict.startsWith("crash_or_stall")) {
            val restartCount = countRecentCrashLaunches(previousTail)
            // [T-android-larky-longsession-followup] Snapshot the count so
            // shouldForceHomeOnLaunch() can gate on it without re-reading
            // the beacon. Cleared by the path above whenever the previous
            // cycle is anything other than crash_or_stall (the var stays at
            // its initialization value of 0 unless this branch overrides).
            lastRestartCount = restartCount
            val lastSessionId = readLastSessionId(context)
            AppLogger.warning(
                TAG,
                "[Perf][LongCtx] step=launchBeacon.crashOrStall " +
                    "reason=${previousVerdict.substringBefore(' ')} " +
                    "detail=${previousVerdict.substringAfter('(', "").substringBefore(')')} " +
                    "restartCount=$restartCount lastSessionId=$lastSessionId",
            )
        } else {
            // Explicit reset for clarity: any verdict that's not
            // crash_or_stall resets the gate count. Important when a user
            // who previously hit the breaker has a single non-crash cycle
            // (clean_exit / silent_kill) — they should be back to normal
            // auto-recovery on the very next launch.
            lastRestartCount = 0
        }

        appendLine(
            file,
            "[$nowIso] launch pid=${android.os.Process.myPid()} " +
                "verdict=${previousVerdict.substringBefore(' ')}",
        )
    }

    /**
     * [T-android-perf-logging] Count launch records in the beacon tail that
     * sit between crash/stall artefacts — a rough "consecutive bad cycles"
     * gauge. Cheap heuristic: number of `launch` lines minus the number
     * followed by a `clean_exit`. Not exact, but a rising value across
     * launches is what flags a recovery loop.
     */
    private fun countRecentCrashLaunches(tail: String): Int {
        val lines = tail.split('\n').filter { it.isNotBlank() }
        // +1 for the cycle being recorded right now: the caller only reaches
        // this path when the previous verdict was crash_or_stall.
        return consecutiveCrashLaunches(lines) + 1
    }

    /**
     * [audit-0914] Length of the consecutive run of crash cycles at the END of
     * [lines], NOT counting the launch being recorded right now.
     *
     * The previous gauge was `launches − clean_exit` over the 16 KB tail. On a
     * production device that degenerates to "how many times this app has been
     * started at all", because `clean_exit` is only written from
     * `Application.onTerminate()` — which Android documents as unreliable and
     * skips for most processes. Measured on 2026-09-14: 9 `launch` lines, 0
     * `clean_exit` lines, and the logged restartCount tracked cold starts
     * exactly (2 → 7), so a single already-recovered 3 s stall in the window
     * was enough to trip the Safe Start gate on the next launch.
     *
     * Each launch line now carries `verdict=<previous cycle's verdict>`, and a
     * line ends the run when its verdict is anything else — or when it has no
     * `verdict=` field at all (legacy line ⇒ unknown ⇒ assume recovered, so
     * upgrading never inherits a phantom crash loop).
     */
    internal fun consecutiveCrashLaunches(lines: List<String>): Int {
        var count = 0
        for (line in lines.asReversed()) {
            if (!line.contains(" launch ")) continue
            val verdict = VERDICT_IN_LINE.find(line)?.groupValues?.get(1) ?: return count
            if (!verdict.startsWith("crash_or_stall")) return count
            count++
        }
        return count
    }

    private val VERDICT_IN_LINE = Regex("verdict=(\\S+)")

    /**
     * [T-android-perf-logging] Best-effort last-opened session id, read from
     * the most recent stall-*.log header if present (the HangDetector writes
     * the session id into its stall report). Returns "unknown" when no stall
     * artefact carries one — keeps the log line populated without throwing.
     */
    private fun readLastSessionId(context: Context): String {
        return try {
            val logsDir = File(context.filesDir, "logs")
            val stall = logsDir.listFiles()
                ?.filter { it.name.startsWith("stall-") }
                ?.maxByOrNull { it.lastModified() }
                ?: return "unknown"
            val head = stall.bufferedReader().use { reader ->
                    val chars = CharArray(2000)
                    val count = reader.read(chars)
                    if (count <= 0) "" else String(chars, 0, count)
                }
            Regex("session[=:]\\s*([0-9a-fA-F-]{8,})").find(head)?.groupValues?.get(1) ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    fun recordCleanExit(context: Context) {
        val now = System.currentTimeMillis()
        appendLine(beaconFile(context), "[${isoLocal(now)}] clean_exit pid=${android.os.Process.myPid()}")
    }

    private fun beaconFile(context: Context): File =
        File(File(context.filesDir, "logs").also { it.mkdirs() }, FILE_NAME)

    /** Last ~16 KB of the beacon log — enough to find the previous launch line. */
    private fun readTail(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            val len = file.length()
            val start = (len - 16 * 1024).coerceAtLeast(0)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val bytes = ByteArray((len - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "tail read failed: ${t.message}")
            ""
        }
    }

    private fun classifyPrevious(tail: String, context: Context, now: Long): String {
        if (tail.isBlank()) return "first_launch"
        val lines = tail.split('\n').filter { it.isNotBlank() }
        val lastLaunch = lines.lastOrNull { it.contains(" launch ") } ?: return "no_prior_launch"
        val lastCleanExit = lines.lastOrNull { it.contains(" clean_exit ") }
        // Order matters: if a clean_exit line follows the most recent launch,
        // the previous cycle ended cleanly.
        val cleanExitAfterLaunch = lastCleanExit != null &&
            lines.indexOf(lastCleanExit) > lines.indexOf(lastLaunch)
        if (cleanExitAfterLaunch) return "clean_exit"

        // Look for crash artefacts produced between previous launch and now.
        val logsDir = File(context.filesDir, "logs")
        val previousLaunchMs = parseTs(lastLaunch) ?: return "ambiguous_no_timestamp"
        val crashLogs = logsDir.listFiles()?.filter { f ->
            val name = f.name
            (name.startsWith("crash-") || name.startsWith("native-crash-") || name.startsWith("stall-")) &&
                f.lastModified() in previousLaunchMs..now
        }.orEmpty()
        if (crashLogs.isNotEmpty()) {
            return "crash_or_stall (${crashLogs.joinToString { it.name }})"
        }
        // No crash, no clean exit — likely OOM / LMK / MIUI background cleanup.
        val ageMs = now - previousLaunchMs
        return "silent_kill (uptime_was=${ageMs}ms)"
    }

    private fun parseTs(line: String): Long? {
        // Lines look like "[2026-05-13T01:23:45.678] launch pid=…"
        val open = line.indexOf('[')
        val close = line.indexOf(']')
        if (open != 0 || close <= 0) return null
        val iso = line.substring(1, close)
        return try {
            val fmt = java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getDefault() }
            fmt.parse(iso)?.time
        } catch (_: Throwable) {
            null
        }
    }

    private fun isoLocal(ms: Long): String {
        val fmt = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getDefault() }
        return fmt.format(java.util.Date(ms))
    }

    private fun appendLine(file: File, line: String) {
        try {
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "append failed: ${t.message}")
        }
    }
}
