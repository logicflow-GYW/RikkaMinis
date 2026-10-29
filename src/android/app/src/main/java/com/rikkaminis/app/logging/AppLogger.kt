package com.rikkaminis.app.logging

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Daily-rotating file logger that mirrors iOS LoggingManager.
 * Writes log entries to files named yyyy-MM-dd.log in app's files/logs/ directory.
 * Retains logs for 14 days by default.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    // [T-android-log-retention-15d] 15 days, matching iOS logRetentionDays
    // (d83bc894). The previous 14 came from the March parity-scaffolding
    // batch with no recorded rationale — plain historical drift, not intent.
    private const val MAX_AGE_DAYS = 15
    // [T-android-log-size-cap] Hard cap on total log storage. Time-based
    // pruning (MAX_AGE_DAYS) handles routine daily rotation; this guards
    // against a single runaway day generating hundreds of MB. Today's file
    // is always excluded from size-pruning (see pruneOldLogs).
    private const val MAX_TOTAL_SIZE_BYTES = 200L * 1024 * 1024
    // [T8-M2] DateTimeFormatter is immutable and thread-safe. The previous
    // shared SimpleDateFormat instances were formatted concurrently from the UI
    // thread, the LogcatTailer thread and the stdout/stderr capture streams;
    // interleaved internal state produced garbled dates (which then opened the
    // wrong log file) and could throw ArrayIndexOutOfBounds. log() did the
    // formatting outside any lock, so the failure could also bubble into a
    // caller as a crash.
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val timestampFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
    // [T-logging-full-coverage] Error-snapshot file names: error-snapshot-<stamp>-<pid>.log
    private val errorSnapshotFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss", Locale.US)

    private fun todayStamp(): String = LocalDate.now().format(dateFormat)

    private fun timeStamp(): String = LocalTime.now().format(timestampFormat)

    private fun dateStampFor(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(dateFormat)

    // [da1028-B / ISSUE-04] `internal`, not private: the Settings → Logs screen
    // registers an OnSharedPreferenceChangeListener against this same prefs
    // file so a `minis-config set logs.enabled …` / backup-restore write is
    // reflected in the switch instead of leaving it on the value captured at
    // open time. A screen that must name the key has to reference one
    // declaration — the private form forces a second string copy in the UI and
    // that is precisely how the F-239 key-name drift started (same principle
    // chat_menu_prefs_listener_check arm 4 enforces for ChatMenuPrefs).
    internal const val PREF_NAME = "logging_prefs"
    internal const val KEY_ENABLED = "logging_enabled"

    private var logDir: File? = null
    private var currentDate: String = ""

    // [T-android-log-async-writer] The writer state (writer/currentDate) is
    // guarded by writerLock — NOT the AppLogger object monitor. Two reasons:
    //  1. stopCapture() holds the object monitor while flushAndStop() joins
    //     the drain thread; if the drain thread needed the object monitor to
    //     write a line, the join would deadlock until its timeout on every
    //     toggle-off.
    //  2. The whole getWriter+println+close span must be one critical section,
    //     otherwise clearLogs()/stopCapture() could close the writer between
    //     getWriter() returning it and println() using it, silently dropping
    //     that line.
    private val writerLock = Any()
    private var writer: PrintWriter? = null

    // [T-logging-full-coverage] Debug-channel writer state, same lock/guard
    // contract as the daily writer. The debug file holds EVERY DEBUG line the
    // app produces (including the previously-muted high-volume categories) so
    // a provider failure can be diagnosed after the fact. Bounded by
    // DEBUG_MAX_BYTES per day via rename-to-".N" rotation (indices shift, so
    // a new roll never overwrites the previous one's bytes) — bounded at
    // (DEBUG_MAX_ROLLS + 1) × cap.
    private const val DEBUG_MAX_BYTES = 5L * 1024 * 1024

    /**
     * How many rolled `.N.log` siblings to keep per day+process. A new roll
     * evicts only the OLDEST roll; the previous rolls' bytes survive until
     * they age into the eviction slot (T-log-roll-1012: the old fixed `.1`
     * name let every new roll destroy the previous roll's evidence — a
     * 211 MB roll survived ~7 minutes on 2026-10-12).
     */
    private const val DEBUG_MAX_ROLLS = 3
    private var debugWriter: PrintWriter? = null
    private var debugDate: String = ""
    private var debugBytesWritten: Long = 0

    // [T-logging-full-coverage] Recent-lines ring for error snapshots.
    // @Volatile: swapped by startCapture/stopCapture like writeQueue.
    @Volatile private var ring: LogRingBuffer? = null

    // Read from every producer thread (log()/writeFileLine/writeLogcatLine)
    // and swapped by setEnabled() on the settings thread.
    @Volatile private var enabled: Boolean = false

    // Saved references to the JVM's original stdout/stderr. Captured on the
    // first startCapture() so stopCapture() can restore them — without this we
    // would never be able to detach our PrintStream wrapper, and the redirection
    // would survive the toggle being flipped off.
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var captureActive: Boolean = false

    // Logcat tail child process — captures Log.d/i/w/e/v from the framework,
    // third-party libraries, and project code that calls android.util.Log
    // directly. Without this only stdout/stderr (println, stack traces) end
    // up in the file, which is < 1% of the actual log volume on Android.
    private var logcatTailer: LogcatTailer? = null

    // [T-android-log-async-writer] Hot-path producers (UI thread, tailer
    // reader, Default workers) enqueue here instead of taking the writer
    // lock. The drain thread owns the file; see LogWriteQueue for the
    // bound/drop contract. @Volatile: swapped by startCapture/stopCapture.
    @Volatile private var writeQueue: LogWriteQueue? = null

    // [FIX-R4-T F-09] Three logger entry points were doing disk I/O on their
    // CALLER's thread, and every caller of them is main-thread reachable:
    //   * error() → dumpErrorSnapshot() → file.writeText() (snapshot worker,
    //     below);
    //   * setEnabled() → stopCapture() → LogWriteQueue.flushAndStop(2_000)
    //     = Thread.join up to 2 s, called from the Settings → Logs switch;
    //   * init() → pruneOldLogs() = three full directory walks, called from
    //     Application.onCreate.
    // All are now scheduled on their own threads. Ordering contracts kept:
    //  - capture control is a SINGLE-thread queue, so start/stop/prune can
    //    never interleave and the prune still runs after startCapture created
    //    the async writer its deferral WARN needs ([T-log-warn-reach-1012]);
    //  - snapshots keep their own worker so flushing them from stopCapture
    //    (which itself runs on the control thread) cannot deadlock on itself.
    // Deliberately NOT reusing a shared pool: AppLogger is initialised first
    // thing in Application.onCreate, before any dispatcher exists.
    private val controlExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "AppLogger-Control").apply { isDaemon = true } }
    private val snapshotExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "AppLogger-Snapshot").apply { isDaemon = true } }

    /**
     * Initialize the logger with app context. Call once from Application.onCreate().
     * If logging was previously enabled (persisted in SharedPreferences),
     * automatically begins capturing stdout/stderr — mirrors iOS
     * `LoggingManager.startIfEnabled()`.
     */
    // [T-log-single-writer] Resolved once per process at init: null in the main
    // process, "<tag>" elsewhere — every daily log file this process writes
    // (main + debug channels) carries the suffix, so no file ever has two
    // writers.
    private var processSuffix: String? = null

    fun init(context: Context) {
        processSuffix = processLogSuffix(readOwnCmdline(), context.packageName)
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        enabled = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        // [T-log-warn-reach-1012] pruneOldLogs() may emit the "size prune
        // deferred" WARN. That line only reaches the daily file when the
        // async writer exists (log() enqueues; it does not write), so the
        // prune must run AFTER startCapture() — previously it ran before and
        // the deferral notice existed only in logcat, where our own tailer
        // filters AppLogger-tagged lines: effectively silent.
        if (enabled) startCapture()
        // [FIX-R4-T F-09] init() runs on Application.onCreate's (main) thread
        // and pruneOldLogs() is three full directory walks plus a stat and a
        // possible delete per entry — a UI freeze of arbitrary length on a
        // busy flash. Scheduled on the capture-control queue rather than a
        // bare thread so it cannot overtake startCapture() above: the prune's
        // "size prune deferred" WARN only reaches the file when the async
        // writer already exists.
        submitControl { pruneOldLogs() }
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        // [FIX-R4-T F-09] stopCapture() used to run on the caller (the
        // Settings → Logs switch callback, main thread) and joined the drain
        // thread for up to 2 s inside flushAndStop — a UI freeze whenever the
        // queue was still flushing. The toggle-off is scheduled on the
        // capture-control queue instead. startCapture() stays synchronous in
        // init() (its contract: capture is live before onCreate continues)
        // but the setEnabled path goes through the same single-thread queue,
        // so on/off can never interleave.
        //
        // Intent re-check: several setEnabled() calls can queue up (a switch
        // flicked twice); only the LAST intent may win. Without the re-check,
        // [stop → start → queued stop] would run the queued stop last and
        // silently disable a logger the user just turned on.
        submitControl {
            if (enabled == value) {
                if (value) startCapture() else stopCapture()
            }
        }
    }

    /**
     * [FIX-R4-T F-09] Hand a control action to the capture-control queue.
     * Falls back to running it inline if the executor rejects (correctness
     * beats latency) — the logger must never lose a state change because its
     * worker thread could not be scheduled.
     */
    private fun submitControl(block: () -> Unit) {
        try {
            controlExecutor.execute(block)
        } catch (_: Exception) {
            runCatching { block() }
        }
    }

    /**
     * Redirect [System.out] and [System.err] through line-buffered writers
     * that prepend a timestamp to each line and append it to today's log file
     * before forwarding the original bytes to the previous stream. The forward
     * is essential — without it, anything written through stdout (println,
     * Throwable.printStackTrace, third-party libs that write to System.err)
     * would silently disappear from logcat.
     *
     * `Log.d/i/w/e` calls go through the Android native logging bridge and
     * are NOT captured by this redirection — only stdout/stderr.
     */
    @Synchronized
    private fun startCapture() {
        if (captureActive) return
        if (logDir == null) return // init() not called yet
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err
        System.setOut(PrintStream(LineCapturingStream(originalOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineCapturingStream(originalErr!!, "STDERR"), true))
        // [T-android-log-async-writer] Start the async writer BEFORE anything
        // can produce a line — from here on every producer only enqueues.
        writeQueue = LogWriteQueue(
            sink = { date, line -> writeQueuedLine(date, line) },
            notice = { n -> writeDropNotice(n) },
        ).also { it.start() }
        // Spawn the logcat tail before emitting the session-start marker so
        // the marker itself shows up in the captured stream as a sanity check.
        logcatTailer = LogcatTailer { line -> writeLogcatLine(line) }.also { it.start() }
        // [T-logging-full-coverage] Error-snapshot ring — fresh per session.
        ring = LogRingBuffer()
        captureActive = true
        info("AppLogger", "Logging session started — capturing stdout/stderr + logcat tail")
    }

    @Synchronized
    private fun stopCapture() {
        if (!captureActive) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        logcatTailer?.stop()
        logcatTailer = null
        // [T-android-log-async-writer] Drain + stop the writer BEFORE closing
        // the file so toggling logging off loses nothing already enqueued.
        // (writeQueuedLine deliberately ignores `enabled` — by the time this
        // runs, setEnabled() has already flipped it to false, and these lines
        // were produced while logging was still on.)
        writeQueue?.flushAndStop(2_000)
        writeQueue = null
        // [FIX-R4-T F-09] Snapshots in flight on their worker must land
        // before the ring goes away — a toggle-off at the wrong moment would
        // otherwise lose the last error scene. Bounded wait: the worker is a
        // different thread (no self-deadlock) and the write is one small file.
        flushSnapshots(500)
        ring = null
        captureActive = false
        // Close the daily writer so any buffered bytes are flushed; getWriter()
        // will reopen on the next file write. writerLock (not the object
        // monitor) — the drain thread above has already exited, and this must
        // not contend with anything still holding the object monitor.
        synchronized(writerLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
            currentDate = ""
            try {
                debugWriter?.close()
            } catch (_: Exception) {
            }
            debugWriter = null
            debugDate = ""
            debugBytesWritten = 0
        }
    }

    /**
     * Append a logcat tail line to today's log file. Lines that AppLogger
     * itself produced (tag prefix `Minis.`) are skipped — [log] already wrote
     * them via [writer], so without this filter every `info()` / `warning()`
     * / etc. call would appear twice in the file (once from [log], once
     * echoed back through logcat).
     */
    private fun writeLogcatLine(rawLine: String) {
        if (!enabled) return
        // [T-worker-log-noise] The worker's ToolChain[Provider] debug tag
        // emits one RAW SSE line per streaming chunk carrying the FULL
        // payload — thousands per response. It never aids diagnosis (the
        // parsed counters land in the [T321] SSE delta debug lines, which are
        // muted at the category level), so drop it at the file boundary.
        if (rawLine.contains("RAW SSE:")) return
        // logcat -v time format: "MM-DD HH:MM:SS.mmm L/Tag(pid): message"
        // Extract the tag to filter our own output.
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx >= 0 && parenIdx > slashIdx) {
            val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
            if (tag.startsWith("Minis.") || tag == "AppLogger") return
            // [T-android-log-dedupe] System.out/err lines arrive here only as
            // the echo of our own stdout forwarding — LineCapturingStream has
            // already written them as `[STDOUT]`/`[STDERR]` lines. Keeping the
            // echo wrote every stdout line to the file twice (measured
            // 2026-09-15: 2624/6814 tail lines were System.out duplicates).
            if (tag == "System.out" || tag == "System.err") return
        }
        // [T-android-log-async-writer] Enqueue only — the drain thread writes.
        writeQueue?.enqueue(todayStamp(), "[LOGCAT] $rawLine", keep = false)
    }

    /**
     * OutputStream wrapper that:
     *   1. Forwards every byte to [delegate] (the original stdout/stderr) so
     *      logcat / adb still receives the output unchanged.
     *   2. Buffers bytes into [buffer] until a `\n` arrives, then writes the
     *      complete line — prefixed with `[HH:mm:ss.SSS] [LEVEL] [tag]` — to
     *      the daily log file. Partial lines are flushed on close().
     */
    private class LineCapturingStream(
        private val delegate: PrintStream,
        private val tag: String,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            // Always forward first; any failure to capture must NOT swallow output.
            delegate.write(b)
            if (b == '\n'.code) {
                emitLine()
            } else {
                buffer.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            var lineStart = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) {
                    if (i > lineStart) buffer.write(b, lineStart, i - lineStart)
                    emitLine()
                    lineStart = i + 1
                }
            }
            if (lineStart < end) buffer.write(b, lineStart, end - lineStart)
        }

        override fun flush() {
            delegate.flush()
        }

        private fun emitLine() {
            val line = try {
                buffer.toString("UTF-8")
            } catch (_: Exception) {
                buffer.toString()
            }
            buffer.reset()
            // Drop empty lines so the file isn't full of bare timestamps.
            if (line.isEmpty()) return
            writeFileLine(tag, line)
        }
    }

    /**
     * Append a captured stdout/stderr line to today's log file. The line is
     * enqueued and written by the drain thread — this must stay allocation-
     * light and lock-free, it runs on whatever thread printed to stdout.
     */
    private fun writeFileLine(channel: String, line: String) {
        if (!enabled) return
        // Timestamps are taken HERE (producer thread) so a lagging drain
        // thread cannot skew the recorded time.
        writeQueue?.enqueue(todayStamp(), "[${timeStamp()}] [$channel] $line", keep = true)
    }

    /**
     * Log a message at INFO level with a category tag.
     */
    fun info(category: String, message: String) {
        log("INFO", category, message)
    }

    fun warning(category: String, message: String) {
        log("WARN", category, message)
    }

    fun error(category: String, message: String) {
        // [F-169] The formatted line is built ONCE here and handed to the
        // snapshot: log() only enqueues it, and the drain thread records it in
        // the ring later — a dump that read the ring alone raced that drain and
        // shipped a scene missing the one line it exists to explain.
        val line = log("ERROR", category, message)
        dumpErrorSnapshot(line)
    }

    /**
     * [T-logging-full-coverage] Error-scene snapshot: on every ERROR episode
     * (deduped by LogRingBuffer.SNAPSHOT_MIN_INTERVAL_MS), write the ring of
     * the most recent delivered lines — all channels, all levels — to
     * `error-snapshot-<timestamp>-<pid>.log`. Every error carries its own scene, so
     * "what did the app log right before the 400?" no longer requires
     * watching the 64KiB kernel logcat ring live.
     *
     * [F-169] [triggerLine] is the ERROR line that caused this dump. It is
     * composed into the snapshot when the drain thread has not recorded it
     * yet, so the trigger is always present. Purely a composition step — the
     * ring itself is untouched (see [LogRingBuffer.contentIncluding]), so the
     * drain thread remains its single writer and no line is duplicated.
     */
    private fun dumpErrorSnapshot(triggerLine: String? = null) {
        val buffer = ring ?: return
        if (!buffer.shouldSnapshot(System.currentTimeMillis())) return
        val dir = logDir ?: return
        val stamp = java.time.LocalDateTime.now().format(errorSnapshotFormat)
        try {
            // §26 The stamp is second-granular, and the app process plus the
            // :modelservice worker both log into this directory — measured on
            // device: 31 of 46 snapshots contained >= 2 pids, and the later
            // write silently overwrote the earlier one (a snapshot named
            // 183246 held no `429` while the worker recorded an HTTP 429 at
            // 18:32:46.007). Appending the pid keeps both scenes. Suffix (not
            // prefix) so every name-parsing site — which matches the
            // `error-snapshot-` prefix and treats `.log` as the extension —
            // stays correct; see listLogFiles / listLogFileMetas /
            // pruneOldLogs / ErrorSnapshotOrderingTest.
            val file = File(dir, "error-snapshot-$stamp-${android.os.Process.myPid()}.log")
            // [FIX-R4-T F-09] Compose on the CALLER thread, write off it ONLY
            // for the main thread. error() is reachable from the UI (every
            // failure log raised by a composable / Activity path) and the
            // write is up to ~500 ring lines — a real, if short, stall on
            // busy flash. The composition must stay here: contentIncluding
            // guarantees the trigger line is appended under the ring's own
            // lock, and the caller thread is the only place that line is
            // certainly correct.
            //
            // An already-off-main caller keeps the old INLINE write: nothing
            // is blocked there, and "the error that is about to kill the
            // process" is exactly the case where landing on disk beats
            // latency. (It also keeps ErrorSnapshotOrderingTest deterministic
            // — its KDoc promises "no drain thread, no race" — because a
            // plain JVM has no Looper and therefore takes the inline path.)
            //
            // Durability note: the old synchronous writeText never called
            // fsync either — "synchronous" only bought order within THIS
            // call, not survival of a hard kill (the kernel may hold dirty
            // pages for seconds). Ordering across calls is kept by the
            // single-thread worker, and stopCapture flushes it.
            val body = buffer.contentIncluding(triggerLine).joinToString(separator = "\n") + "\n"
            if (!isMainThread()) {
                file.writeText(body)
                return
            }
            try {
                snapshotExecutor.execute {
                    try {
                        file.writeText(body)
                    } catch (_: Exception) {
                        // Snapshot must never take the logger down.
                    }
                }
            } catch (_: Exception) {
                // Executor rejected/unavailable: degrade to the old inline
                // write rather than lose the scene.
                file.writeText(body)
            }
        } catch (_: Exception) {
            // Snapshot must never take the logger down.
        }
    }

    /**
     * [FIX-R4-T F-09] True when the caller is the Android main thread.
     *
     * Fails closed (false = "not main") wherever Looper is unavailable — a
     * plain JVM test or a non-Android host — which is the SAFE answer: the
     * inline write is what the code did before this fix.
     */
    private fun isMainThread(): Boolean {
        return try {
            val main = android.os.Looper.getMainLooper()
            main != null && android.os.Looper.myLooper() === main
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * [FIX-R4-T F-09] Wait up to [timeoutMs] for snapshots already handed to
     * the worker to reach disk. Runs on the capture-control thread inside
     * stopCapture (a different thread than the worker — no self-deadlock).
     */
    private fun flushSnapshots(timeoutMs: Long) {
        try {
            val latch = CountDownLatch(1)
            snapshotExecutor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    /**
     * Categories whose DEBUG lines skip the logcat emission (previously:
     * silently dropped everywhere). [T-logging-full-coverage] They now still
     * reach the `debug-<date>.log` file — the mute only suppresses the
     * `Log.d → liblog → LogcatTailer` hop, keeping logcat traffic bounded for
     * per-frame/per-token categories while the file channel captures them.
     * Gating at the caller would require touching dozens of sites; gating
     * here keeps the routing single-source.
     */
    // [T-worker-log-capture] "OpenAIProvider" executes in the :modelservice
    // worker, and its DEBUG diagnostics are per-SSE-delta counters
    // ([T321] SSE delta: … / [T321] SSE responses type=…) — one line per
    // streaming token, i.e. thousands per response once the worker's capture
    // is on (see MinisApp's [T-worker-log-capture]). INFO/WARN/ERROR from the
    // same category — [T321] → REQ url=…, ← RSP status=…, ← HTTP <code>
    // error body: … — still flow, and those are the lines that diagnose a
    // provider failure. Extend this list from real logs only, never
    // speculatively.
    private val mutedDebugCategories = setOf("ChatScrollFollow", "OpenAIProvider")

    fun debug(category: String, message: String) {
        // [T-logging-full-coverage] Every DEBUG line reaches the debug file;
        // muted categories only skip the logcat emission (hot-path cost).
        if (category !in mutedDebugCategories) {
            Log.d("Minis.$category", message)
        }
        if (!enabled) return
        writeQueue?.enqueue(
            todayStamp(),
            "[${timeStamp()}] [DEBUG] [$category] $message",
            keep = false,
        )
    }

    /**
     * Format, emit to logcat and enqueue one line. Returns the exact formatted
     * line that was enqueued (empty when logging is disabled) so a caller that
     * needs the line itself — [error] handing its trigger line to the snapshot
     * — reuses this one formatting/timestamp instead of rebuilding it.
     */
    private fun log(level: String, category: String, message: String): String {
        // Also output to logcat (unchanged — adb debugging still sees
        // everything, and Minis.* lines are filtered out of the file capture).
        val logcatTag = "Minis.$category"
        when (level) {
            "ERROR" -> Log.e(logcatTag, message)
            "WARN" -> Log.w(logcatTag, message)
            "DEBUG" -> Log.d(logcatTag, message)
            else -> Log.i(logcatTag, message)
        }

        // Write to file (only if enabled). Timestamps are taken here so the
        // recorded time is the call time, not the drain time.
        if (!enabled) return ""
        val line = "[${timeStamp()}] [$level] [$category] $message"
        writeQueue?.enqueue(
            todayStamp(),
            line,
            // DEBUG is droppable under backlog; INFO/WARN/ERROR always keep.
            keep = level != "DEBUG",
        )
        return line
    }

    /**
     * Open (or reuse) the daily writer for [date]. Callers on the write path
     * invoke this INSIDE `synchronized(writerLock)` so the returned writer
     * cannot be closed before the caller finishes writing — see [writerLock].
     *
     * [T-log-tail-heal-1012] On a fresh open the file tail is checked: a
     * torn write (multi-process shared name + 8 KB buffered encoder cut at a
     * kill/silent_kill boundary, measured 2026-10-12: 26 main / 78 worker
     * torn lines in one day, every one followed by the next process's
     * session-start line) leaves a trailing fragment WITHOUT a newline, and
     * the next append glues onto it — corrupting two log lines. Healing
     * appends a separator so both fragments become parseable lines again.
     */
    private fun getWriter(date: String): PrintWriter {
        synchronized(writerLock) {
            if (date != currentDate || writer == null) {
                writer?.close()
                val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
                val file = File(dir, channelFileName("minis", date, processSuffix))
                healTornTail(file)
                writer = PrintWriter(FileWriter(file, true))
                currentDate = date
            }
            return writer!!
        }
    }

    /**
     * [T-log-tail-heal-1012] If [file] exists and does not end with `\n`,
     * append a healing separator (newline + a marker line) so the torn
     * fragment stops gluing onto the next process's first line. Appending
     * (not truncating) — the fragment itself may still hold the only copy of
     * that log line. See [healTornTail] in LogFileName.kt (pure JVM, tested).
     */
    private fun healTornTail(file: File) {
        healTornTail(file, "[${timeStamp()}] [INFO] [AppLogger] --- healed torn tail (interrupted write, no trailing newline) ---")
    }

    /**
     * [T-android-log-async-writer] Drain-side sink — the only place that
     * touches [writer] on the write path. Runs on the LogWriteQueue drain
     * thread (or briefly on the caller of flushAndStop). Deliberately does
     * NOT check [enabled]: by the time flushAndStop drains, setEnabled() has
     * already flipped it off, and those lines were produced while it was on.
     */
    private fun writeQueuedLine(date: String, line: String) {
        // [T-logging-full-coverage] Record into the error-snapshot ring FIRST
        // (single drain-thread producer, cheap append) — every delivered line
        // across all channels lands here.
        ring?.append(line)
        try {
            when (logChannelFor(line)) {
                LogChannel.DEBUG -> synchronized(writerLock) {
                    val w = getDebugWriter(date)
                    w.println(line)
                    // [FIX-R4-T F-07] Count the bytes that actually land on
                    // disk, not UTF-16 chars. The threshold
                    // ([DEBUG_MAX_BYTES]), the seed (`file.length()` in
                    // getDebugWriter) and the FileWriter's UTF-8 encoder are
                    // all byte-denominated; `line.length + 1` counted a CJK
                    // char as 1 while it occupies 3 bytes, so the counter
                    // under-read the file by 2-3x and the "5 MB" channel only
                    // rotated at 12-15 MB of real bytes (4 rolls ⇒ 50-60 MB).
                    // Costs one branch walk per delivered line on the drain
                    // thread; no allocation (see [utf8ByteLength]).
                    debugBytesWritten += utf8ByteLength(line) + 1L
                    if (debugBytesWritten > DEBUG_MAX_BYTES) {
                        rotateDebugFile(date)
                    }
                }
                LogChannel.MAIN -> synchronized(writerLock) {
                    getWriter(date).println(line)
                }
            }
        } catch (_: Exception) {
            // Must not feed back into the logger.
        }
    }

    /**
     * Open (or reuse) the debug-channel writer for [date], inside
     * `synchronized(writerLock)` like [getWriter].
     *
     * [T-log-rotation-1012] The byte counter is SEEDED FROM THE FILE, not
     * reset to zero: the file name is shared by every process of this family
     * for the whole day (short-lived `:modelservice` workers come and go),
     * so a fresh process starting at 0 never reached DEBUG_MAX_BYTES — each
     * single worker writes at most a few MB before dying, while the shared
     * file itself grew unbounded (measured 2026-10-12: 202 MB before the
     * first rotation ever fired). Seeding from `file.length()` makes the
     * rotation decision track the FILE's true size regardless of how many
     * processes have appended to it.
     */
    private fun getDebugWriter(date: String): PrintWriter {
        if (date != debugDate || debugWriter == null) {
            try {
                debugWriter?.close()
            } catch (_: Exception) {
            }
            val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
            val file = File(dir, channelFileName("debug", date, processSuffix))
            healTornTail(file) // [T-log-tail-heal-1012] same tear risk as the main channel
            debugWriter = PrintWriter(FileWriter(file, true))
            debugDate = date
            debugBytesWritten = file.length()
        }
        return debugWriter!!
    }

    /**
     * [T-logging-full-coverage] Debug-file rotation: the current debug file
     * exceeded DEBUG_MAX_BYTES → rename it to `…<N+1>.log` (shifting indices,
     * see [debugRollFileName]) and reopen fresh. Called INSIDE writerLock
     * from the write path; the next write reopens the writer. Bounded at
     * (DEBUG_MAX_ROLLS + 1) × cap per day+process.
     *
     * [T-log-rotation-1012] The old fixed `.1` name meant every roll DELETED
     * the previous roll (a 211 MB roll survived ~7 minutes before being
     * overwritten). Now indices shift: the newest roll takes the highest
     * index, older rolls keep their bytes until they are the oldest and
     * [DEBUG_MAX_ROLLS] is exceeded — then only the oldest roll is deleted,
     * never the freshest evidence.
     */
    private fun rotateDebugFile(date: String) {
        try {
            debugWriter?.close()
        } catch (_: Exception) {
        }
        val dir = logDir
        if (dir != null) {
            val base = channelFileName("debug", date, processSuffix)
            val file = File(dir, base)
            // -1: the not-yet-created roll takes the next free index.
            val highest = highestRollIndex(dir.list()?.toList() ?: emptyList(), base)
            val rolled = File(dir, debugRollFileName(base, highest + 1))
            if (!file.renameTo(rolled)) file.delete()
            // Evict the OLDEST rolls until at most DEBUG_MAX_ROLLS remain —
            // counting the one just created above. `(1..highest)` is already
            // in index order; sorting by NAME would put ".10.log" before
            // ".7.log" and delete the wrong end once a day passes 9 rotations
            // (2026-10-12 measured ~40 at 200 MB/day). A stray missing index
            // cannot stop the sweep — [rollEvictionIndices] takes from the
            // sorted present set, not a contiguous run.
            val present = (1..highest).filter { i ->
                File(dir, debugRollFileName(base, i)).exists()
            }
            for (i in rollEvictionIndices(present, DEBUG_MAX_ROLLS)) {
                File(dir, debugRollFileName(base, i)).delete()
            }
        }
        debugWriter = null
        debugBytesWritten = 0
    }

    /**
     * Backlog notice — the queue dropped [n] lines because the disk could not
     * keep up. Emitted at most once per backlog episode (see LogWriteQueue).
     */
    private fun writeDropNotice(n: Long) {
        try {
            synchronized(writerLock) {
                getWriter(todayStamp()).println(
                    "[${timeStamp()}] [WARN] [AppLogger] $n log lines dropped (write backlog)",
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * List available log files, newest first.
     */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Lightweight metadata for a single log file. Captures name + size +
     * mtime ONCE so the UI never re-stat's the file during recomposition
     * (LogManagementScreen used to call file.length() per row per
     * recomposition, which scaled badly once dozens of crash files piled
     * up alongside the daily logs).
     */
    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * Capped, prefix-filtered log listing for the UI.
     *
     * - `prefix`: filename starts-with filter (e.g. `"minis-"` for daily
     *   logs, `"crash-"` / `"native-crash-"` for crash reports). Empty
     *   string returns all `.log` files.
     * - `limit`: keep at most this many files, sorted by name descending
     *   (newest first, since both daily and crash filenames embed
     *   YYYY-MM-DD prefixes that sort correctly).
     *
     * Captures size + mtime per file via a single `stat` per entry, so a
     * caller iterating the result list never has to re-stat. Total size
     * is computed by the caller (sum of `sizeBytes`) — no second
     * directory walk needed.
     *
     * Pure data; safe to call from `Dispatchers.IO`.
     */
    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> {
        val dir = logDir ?: return emptyList()
        val files = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        // Sort then cap BEFORE the per-file stat — File.listFiles already
        // populated name internally, but length()/lastModified() are
        // separate stat syscalls we'd rather skip on the tail.
        return files
            .sortedByDescending { it.name }
            .take(limit)
            .map { LogFileMeta(it.name, it.length(), it.lastModified()) }
    }

    /**
     * [audit-0917] Resolve a caller-supplied log filename inside [logDir].
     * Returns null when the name is not a plain basename or the canonical
     * result escapes the log directory — File(dir, name) otherwise resolves
     * "../.." and turns a log read into arbitrary file disclosure.
     */
    private fun resolveLogFile(filename: String): File? {
        val dir = logDir ?: return null
        val name = File(filename).name
        if (name != filename || name.isEmpty() || name == "." || name == "..") {
            // [T-log-warn-reach-1012] warning(), not Log.w: the raw logcat
            // line is filtered out of the file by our own tailer (tag ==
            // "AppLogger"), so the refusal notice never reached the file.
            warning(TAG, "refused a non-basename log name: \"$filename\"")
            return null
        }
        val file = File(dir, name)
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (canonical.parentFile != root) {
            warning(TAG, "refused an out-of-dir log path: ${canonical.path}")
            return null
        }
        return file
    }

    /**
     * Read content of a specific log file.
     */
    fun readLog(filename: String): String? {
        val file = resolveLogFile(filename) ?: return null
        return if (file.exists()) file.readText() else null
    }

    /**
     * Result of a bounded segment read — [content] holds at most [bytesRead]
     * bytes starting at the requested offset, and [truncated] tells the caller
     * whether more data remains past the end of [content].
     *
     * [FIX-R4-T F-14] The slice is BYTE-aligned, not line- or character-
     * aligned: a caller that pages through a multi-MB file with
     * `offset += bytesRead` can cut a multi-byte UTF-8 character in half and
     * see one `U+FFFD` at each seam. That is the deliberate trade — aligning
     * to lines would make `offset + bytesRead` land INSIDE the last returned
     * line, so a paging client would re-read (or skip) that line forever
     * instead of making progress. Single-shot readers (`offset = 0`) are
     * unaffected.
     */
    data class LogSegment(
        val totalSize: Long,
        val content: String,
        val bytesRead: Int,
        val truncated: Boolean,
    )

    fun readLogSegment(filename: String, offset: Int, limit: Int): LogSegment? {
        // [audit-0917] Same traversal guard as readLog — this overload takes a
        // caller-supplied name too.
        val file = resolveLogFile(filename) ?: return null
        if (!file.exists()) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileSize = raf.length()
                // [FIX-R4-T F-14] `limit` is caller-supplied over RPC and used
                // to be trusted verbatim: `debug.logs.read` with limit=50_000_000
                // allocated `(end - start)` = the whole file (up to the 200 MB
                // storage cap) in one ByteArray, then a same-size String, then
                // the JSON body — a 3x amplification of the largest log file on
                // an RPC thread. [segmentBounds] applies the same 4 MB bound
                // handleReadFile already used.
                val bounds = segmentBounds(fileSize, offset.toLong(), limit)
                val buf = ByteArray(bounds.length)
                raf.seek(bounds.start)
                // Short-read tolerant instead of readFully: a concurrent
                // rotate/clear/truncate between `length()` and the read made
                // readFully throw EOFException, which the catch-all collapsed
                // into `null`, which DebugRPCHandler reports as
                // `-32602 "Log file not found"` — an I/O race disguised as a
                // missing file. Now the caller gets the bytes that existed.
                val read = readAtMost(raf, buf)
                val content = if (read == buf.size) buf else buf.copyOf(read)
                LogSegment(
                    totalSize = fileSize,
                    content = String(content, Charsets.UTF_8),
                    bytesRead = content.size,
                    truncated = (bounds.start + content.size) < fileSize,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fill as much of [buf] as the file currently holds. Returns the number of
     * bytes actually read (`< buf.size` when the file shrank mid-read).
     * [FIX-R4-T F-14] — the short-read-tolerant counterpart of readFully.
     *
     * `internal` (not private) so the JVM probe can pin the difference: on a
     * file seeked past its end this returns 0, while readFully throws
     * EOFException — the exact swap that turned a concurrent rotation into
     * `-32602 "Log file not found"`.
     */
    internal fun readAtMost(raf: java.io.RandomAccessFile, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = raf.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /**
     * Delete all log files.
     */
    @Synchronized
    fun clearLogs() {
        // [T-logging-zombie-fd-android] Close the writer BEFORE deleting: a
        // FileWriter on an unlinked inode would keep writing to the zombie
        // file (invisible on disk) until currentDate changes. Ordering close
        // first makes the zombie window impossible instead of just short.
        // [T-android-log-async-writer] writerLock shares the write path's
        // critical section so a drain-thread println can't interleave.
        synchronized(writerLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
            currentDate = ""
            try {
                debugWriter?.close()
            } catch (_: Exception) {
            }
            debugWriter = null
            debugDate = ""
            debugBytesWritten = 0
            logDir?.listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * Total size of all log files in bytes.
     */
    fun totalSize(): Long {
        return logDir?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneOldLogs() {
        val now = System.currentTimeMillis()
        val ageCutoff = now - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        val today = dateStampFor(now)
        // Covers the main file AND per-process variants (minis-<date>.modelservice.log)
        // — a worker file being written right now must not be size-pruned.
        // (The debug-<date> family is protected via todayPrefixes below.)

        // Phase 1: time-based — delete files older than MAX_AGE_DAYS.
        logDir?.listFiles()?.forEach { file ->
            if (file.lastModified() < ageCutoff) {
                file.delete()
            }
        }

        // Phase 2: size-based — if total still exceeds cap, delete oldest
        // non-protected files until under threshold. Selection (and the
        // reasons a file is protected) lives in LogPrunePolicy — pure JVM,
        // unit-tested; this site only executes the deletions and reports a
        // deferral (never silently wrong: D8).
        val dir = logDir ?: return
        var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= MAX_TOTAL_SIZE_BYTES) return

        val allFiles = dir.listFiles()?.toList() ?: return
        val candidates = LogPrunePolicy.sizePruneCandidates(
            allFiles, now, listOf("minis-$today", "debug-$today"),
        )

        for (file in candidates) {
            if (total <= MAX_TOTAL_SIZE_BYTES) break
            total -= file.length()
            file.delete()
        }

        if (total > MAX_TOTAL_SIZE_BYTES) {
            val protectedBytes = allFiles.sumOf { it.length() } -
                candidates.sumOf { it.length() }
            // One line, only on deferral: the cap is exceeded but every
            // candidate is protected. Say WHY instead of shrinking silently.
            //
            // [T-log-warn-reach-1012] Routed through AppLogger.warning() (not
            // android.util.Log): the raw logcat line is filtered out of the
            // file by our own tailer (tag == "AppLogger"), and before the
            // writer queue exists this was the ONLY destination — the notice
            // was effectively unreachable (measured 2026-10-12: 0 occurrences
            // in the files during a full day above the cap). warning() both
            // mirrors to logcat and lands in the daily file. init() runs the
            // prune after startCapture() so the queue exists.
            warning(
                "AppLogger",
                "size prune deferred: cap exceeded by ~${total / (1024L * 1024)}MB, " +
                    "${allFiles.size - candidates.size} file(s) " +
                    "(~${protectedBytes / (1024L * 1024)}MB) protected " +
                    "(today / evidence / <${LogPrunePolicy.RECENT_GRACE_MS / 60000}min grace)",
            )
        }
    }
}

/**
 * [FIX-R4-T F-07] UTF-8 encoded length of [line] without materialising the
 * byte array.
 *
 * The debug channel's size accounting has to speak ONE unit. The rotation
 * threshold (`DEBUG_MAX_BYTES`), the counter's seed (`file.length()`) and the
 * FileWriter's encoder are all byte-denominated; the only thing that used to
 * speak UTF-16 characters was the per-line increment, so the nominal 5 MB cap
 * was only reached at 12-15 MB of real bytes for CJK/tool-output workloads.
 *
 * Hand-rolled rather than `line.toByteArray(Charsets.UTF_8).size`: this runs
 * once per delivered line on the drain thread (tens of thousands of lines in
 * a streaming session) and that array would be pure garbage. Semantics match
 * the JDK encoder exactly, including `'?'` (1 byte) for an unpaired surrogate.
 */
internal fun utf8ByteLength(line: String): Long {
    var bytes = 0L
    var i = 0
    val n = line.length
    while (i < n) {
        val c = line[i]
        val code = c.code
        if (code < 0x80) {
            bytes += 1
        } else if (code < 0x800) {
            bytes += 2
        } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(line[i + 1])) {
            bytes += 4
            i++
        } else if (code in 0xD800..0xDFFF) {
            bytes += 1
        } else {
            bytes += 3
        }
        i++
    }
    return bytes
}

/**
 * [FIX-R4-T F-14] Upper bound for a single `debug.logs.read` segment — the
 * same 4 MiB `handleReadFile` clamps to. Kept next to the reader (not in the
 * RPC layer) so every caller of [AppLogger.readLogSegment] inherits the
 * bound: the RPC handler reads raw `params.optInt` and would otherwise hand
 * an unbounded value straight to the allocation.
 */
internal const val MAX_LOG_SEGMENT_BYTES = 4 * 1024 * 1024

/** Resolved byte window of a segment read: [length] bytes starting at [start]. */
internal data class SegmentBounds(val start: Long, val length: Int)

/**
 * [FIX-R4-T F-14] Clamp an untrusted `(fileSize, offset, limit)` triple to a
 * readable window. Pure and total: negative or absurd inputs collapse to an
 * empty read at a valid offset instead of throwing — a bad RPC request must
 * not become an exception on the handler thread.
 */
internal fun segmentBounds(fileSize: Long, offset: Long, limit: Int): SegmentBounds {
    val size = fileSize.coerceAtLeast(0L)
    val start = offset.coerceIn(0L, size)
    val capped = limit.coerceIn(0, MAX_LOG_SEGMENT_BYTES)
    val length = (size - start).coerceAtMost(capped.toLong()).toInt()
    return SegmentBounds(start, length)
}
