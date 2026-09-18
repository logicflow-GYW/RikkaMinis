package com.rikkaminis.app.logging

import java.io.File

/**
 * [T-log-single-writer] Per-process daily log file names.
 *
 * The main process keeps the historical `<prefix>-<date>.log`; any other
 * process (`:modelservice`, future `:toolservice`) writes
 * `<prefix>-<date>.<tag>.log`. A single file therefore never has two writers,
 * which makes the O_APPEND interleaving measured on 2026-09-18 structurally
 * impossible: after d09f3adf the worker process began writing the same
 * daily files as the main process, and the two writers' buffered flushes
 * produced out-of-order lines (main lagging the worker by 4-24s on batch
 * flush) plus one garbled line where two fragments landed in a single line.
 *
 * The setting page's `minis-` prefix filter (AppLogger.listLogFileMetas)
 * keeps matching the suffixed variants, so worker files stay visible there.
 */
internal fun channelFileName(prefix: String, date: String, processSuffix: String?): String =
    if (processSuffix.isNullOrEmpty()) "$prefix-$date.log"
    else "$prefix-$date.$processSuffix.log"

/**
 * `/proc/self/cmdline` → per-process suffix: `com.rikkaminis.app:modelservice`
 * → `modelservice`; the main process (name equals [packageName]) / null /
 * empty → null (historical main-process file name).
 */
internal fun processLogSuffix(cmdline: String?, packageName: String): String? {
    val p = cmdline?.replace("\u0000", "")?.trim() ?: return null
    if (p.isEmpty() || p == packageName) return null
    return p.substringAfterLast(':', "").ifEmpty { null }
}

/**
 * One-shot `/proc/self/cmdline` read; null on any failure so the caller falls
 * back to the historical main-process file name rather than failing init.
 */
internal fun readOwnCmdline(): String? = runCatching {
    File("/proc/self/cmdline").readBytes().toString(Charsets.UTF_8)
}.getOrNull()
