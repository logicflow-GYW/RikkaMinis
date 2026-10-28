package com.rikkaminis.app.ui.settings

/**
 * [meta-audit-1028 E5 / B06 F-09] Global newest-first ordering for the crash
 * list that LogManagementScreen merges out of two repositories of files.
 *
 * The defect this exists for: the screen concatenated Java/Kotlin crash files
 * (`crash-<local stamp>.log`) with NDK ones (`native-crash-<UTC stamp>.log`)
 * and sorted the union by FILE NAME. Names do not carry a comparable time
 * across the two families — `native-crash-…` starts with `n` and `crash-…`
 * with `c`, so every native report sorted above every Java report no matter
 * when it happened, and the 100-row cap then dropped the newest Java crashes
 * first (a native crash loop on launch could starve the Java list entirely).
 * Even without the cap the visible order lied: a Java crash from a minute ago
 * rendered below a native one from last week.
 *
 * The real timestamp is already captured per row by
 * `AppLogger.listLogFileMetas` (`lastModified`, epoch millis, one stat at
 * listing time) — no filename parsing, no `T`-vs-`_` separator and no UTC-vs-
 * local trap. This keeps the two families comparable by construction.
 *
 * Generic over the row type so the ordering can be JVM-tested without
 * `AppLogger.LogFileMeta` (that file is Android-bound and cannot be compiled
 * off-device); the caller passes its own accessors.
 */
internal fun <T> newestFirstCapped(
    entries: List<T>,
    limit: Int,
    nameOf: (T) -> String,
    modifiedOf: (T) -> Long,
): List<T> = entries
    // Name is a deterministic tie-break only: two reports can share an mtime
    // (same-second writes), and a stable order keeps the list from shuffling
    // between recompositions. It never dominates the real timestamp.
    .sortedWith(compareByDescending<T> { modifiedOf(it) }.thenByDescending { nameOf(it) })
    .take(limit.coerceAtLeast(0))
