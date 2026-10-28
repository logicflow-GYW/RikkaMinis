package com.rikkaminis.app.data

import java.io.File

/**
 * [E2-F13] Staging-dir housekeeping for downloaded update APKs.
 *
 * `UpdateChecker.download` stages the APK under `filesDir/updates/` (NOT cacheDir
 * — the OS evicted cached APKs mid-flow, see T-android-update-resume-33637), and
 * the file name is keyed by the target version. Nothing in the app ever removed
 * one: not a successful install, not a failed download, not a cancelled one, not
 * `clearPending`. Every version the user had ever downloaded stayed on disk —
 * ~15 MB per release, in internal storage, invisible to the user — so the staging
 * dir grew without a bound for the life of the installation.
 *
 * WHY "every file except the ones in use" AND NOT "files for older versions":
 *   the obvious predicate is a version comparison, but it CANNOT be made safe
 *   here. `PendingUpdateStore` is cleared the moment the install intent fires,
 *   so a file the system installer is still reading is byte-for-byte identical
 *   in metadata to abandoned debris — "older than what I am downloading now"
 *   would delete the APK out from under a live install and the user would see
 *   the install fail with no explanation. So the sweep is ownership-based
 *   (who says it is in use?) plus a wall-clock in-flight window:
 *
 *     delete `*.apk` that is (a) not in [keep] — neither the file being written
 *     now nor the one the pending record points at — and (b) older than
 *     [IN_FLIGHT_WINDOW_MS].
 *
 *   One hour is far longer than the window in which the installer can still be
 *   reading a file whose record was already cleared (the user must open the app
 *   and start another download within it), and it is short enough that the
 *   staging dir holds only what the current flow touched. Residual, stated
 *   plainly: a download started within the last hour is kept until the next one,
 *   so the ceiling is one file per download in an hour rather than per lifetime.
 *
 * Plain `java.io` over a name list, so a JVM probe drives it with no Android.
 */
internal object UpdateStaging {

    /**
     * Files younger than this are never swept: the system installer may still be
     * reading one whose pending record was cleared when its intent fired.
     */
    const val IN_FLIGHT_WINDOW_MS: Long = 60L * 60L * 1000L

    /** One candidate file: name plus last-modified, exactly what a sweep needs. */
    data class Entry(val name: String, val lastModifiedMs: Long)

    /** Snapshot `dir` for [staleApkNames]; an absent directory is simply empty. */
    fun entriesOf(dir: File): List<Entry> =
        dir.listFiles()
            ?.filter { it.isFile }
            ?.map { Entry(it.name, it.lastModified()) }
            ?: emptyList()

    /**
     * Names the sweep may delete: `*.apk` outside [keep] and older than
     * [IN_FLIGHT_WINDOW_MS].
     */
    fun staleApkNames(
        entries: List<Entry>,
        keep: Set<String>,
        nowMs: Long,
    ): List<String> = entries
        .filter { it.name.endsWith(".apk", ignoreCase = true) }
        .filter { it.name !in keep }
        .filter { nowMs - it.lastModifiedMs > IN_FLIGHT_WINDOW_MS }
        .map { it.name }

    /**
     * Delete what [staleApkNames] selects and report the names actually gone.
     * A file that refuses to delete is skipped, not escalated: the caller is
     * background housekeeping and must never fail the download it precedes.
     */
    fun prune(dir: File, keep: Set<String>, nowMs: Long = System.currentTimeMillis()): List<String> {
        val removed = mutableListOf<String>()
        for (name in staleApkNames(entriesOf(dir), keep, nowMs)) {
            if (File(dir, name).delete()) removed.add(name)
        }
        return removed
    }
}
