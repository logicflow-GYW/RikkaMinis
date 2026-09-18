package com.rikkaminis.app.logging

/**
 * File-channel routing for lines produced by the logging pipeline.
 *
 * [MAIN] = the daily `minis-<date>.log` file (INFO/WARN/ERROR/STDOUT/STDERR/
 * [LOGCAT] tail). [DEBUG] = the `debug-<date>.log` file — every DEBUG-level
 * line the app produces, including the previously-muted high-volume
 * categories (per-SSE-delta provider counters, scroll-follow traces).
 *
 * Routing happens at the drain-side sink (the single place that touches the
 * writers) so the hot-path producers stay allocation-light and never parse.
 */
enum class LogChannel { MAIN, DEBUG }

/**
 * Pure routing decision for one pre-formatted pipeline line.
 *
 * Line shapes seen at the sink (all produced inside AppLogger):
 *  - `[HH:mm:ss.SSS] [LEVEL] [category] message`   — AppLogger.log()
 *  - `[HH:mm:ss.SSS] [CHANNEL] line`               — stdout/stderr capture
 *    (CHANNEL is `STDOUT` or `STDERR`)
 *  - `[LOGCAT] raw logcat line`                    — LogcatTailer tail
 *
 * Rule: the second bracket token decides. `DEBUG` → [DEBUG]; everything else
 * (INFO/WARN/ERROR/STDOUT/STDERR/LOGCAT/unparseable) → [MAIN]. An
 * unparseable line stays on MAIN — never discard a log line because its
 * shape drifted; that is how the 2026-09-18 worker-log gap stayed invisible.
 *
 * Pure JVM — unit-tested in LogChannelTest.
 */
fun logChannelFor(line: String): LogChannel {
    if (!line.startsWith("[")) return LogChannel.MAIN
    // Second bracket token decides: the first is the timestamp.
    var idx = 0
    var token = ""
    for (i in 1..2) {
        val open = line.indexOf('[', idx)
        val close = line.indexOf(']', open)
        if (open < 0 || close <= open + 1) return LogChannel.MAIN
        token = line.substring(open + 1, close)
        idx = close + 1
    }
    return if (token == "DEBUG") LogChannel.DEBUG else LogChannel.MAIN
}
