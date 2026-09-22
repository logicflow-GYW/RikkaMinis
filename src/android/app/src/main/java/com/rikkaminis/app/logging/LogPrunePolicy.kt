package com.rikkaminis.app.logging

import java.io.File

/**
 * Size-prune candidate selection for AppLogger.pruneOldLogs() — pure JVM, no
 * Android imports, so the selection rules are unit-testable in isolation.
 *
 * Why this exists (D8, 2026-09-22 incident): size pruning used to consider
 * every non-today file deletable. A process launch at 01:06 shrank `logs/`
 * from 241 MB to 69 MB and destroyed a 168 MB `minis-<yesterday>.modelservice.log`
 * that was STILL BEING WRITTEN by the worker process — deleting evidence a
 * live investigation was reading, in exchange for zero user-visible benefit
 * (the cap self-heals on the next prune). Two protections, in priority order:
 *
 *  1. [RECENT_GRACE_MS] — a file modified within the window may be mid-write
 *     by ANOTHER process (the prune runs at every process start, including
 *     `:modelservice` while a chat session streams). Midnight rollover makes
 *     "yesterday's file" the actively-written one, so name-based today
 *     protection alone is structurally insufficient — mtime is the real
 *     "someone may be writing this" signal.
 *
 *  2. [EVIDENCE_PREFIXES] — error snapshots / crash reports are small,
 *     irreplaceable scenes for their triggering event. They stay subject to
 *     the 15-day AGE pruning (phase 1); this shields them from SIZE pruning
 *     only. Their total size is bounded by design (snapshot ring dedup,
 *     one file per crash), so exempting them cannot grow the directory
 *     without bound.
 *
 * Trade-off accepted and bounded: with all candidates protected, the directory
 * may transiently exceed [AppLogger.MAX_TOTAL_SIZE_BYTES] by (grace-window
 * bytes + evidence bytes). The next prune after the grace window lapses
 * recovers the cap. A deferral is reported once by the caller (single WARN
 * line) — the pruner must never be silently wrong (same lesson, applied to
 * the pruner itself).
 */
internal object LogPrunePolicy {

    /** Evidence files are never SIZE-pruned (age pruning still applies). */
    val EVIDENCE_PREFIXES: List<String> = listOf(
        "error-snapshot-",
        "crash-",
        "native-crash-",
    )

    /**
     * Files modified within this window are presumed mid-write by another
     * process and are exempt from size pruning. One hour covers a cross-
     * midnight session with the worker streaming into yesterday's file
     * (the exact D8 shape); short enough that the cap self-heals promptly.
     */
    const val RECENT_GRACE_MS: Long = 60L * 60 * 1000

    /**
     * Select the files that SIZE-pruning may delete, oldest first.
     *
     * Excluded from selection (kept):
     *  - [todayPrefixes] — files being actively written THIS day by any
     *    process (`minis-<today>` and `debug-<today>` families, including
     *    the per-process variants like `minis-<today>.modelservice.log`);
     *  - [EVIDENCE_PREFIXES] files — crash / snapshot scenes;
     *  - files whose lastModified is within [RECENT_GRACE_MS] of [nowMs].
     *
     * Pure function of its inputs (mtime is read by the caller and passed
     * in via [File.lastModified]); deterministic and order-independent.
     */
    fun sizePruneCandidates(
        files: Collection<File>,
        nowMs: Long,
        todayPrefixes: Collection<String>,
    ): List<File> = files
        .asSequence()
        .filter { f -> todayPrefixes.none { f.name.startsWith(it) } }
        .filter { f -> EVIDENCE_PREFIXES.none { f.name.startsWith(it) } }
        .filter { f -> f.lastModified() < nowMs - RECENT_GRACE_MS }
        .sortedBy { it.lastModified() } // oldest first: delete history, not recency
        .toList()
}
