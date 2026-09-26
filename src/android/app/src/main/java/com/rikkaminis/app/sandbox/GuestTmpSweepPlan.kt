package com.rikkaminis.app.sandbox

import java.io.File
import java.nio.file.Files

/**
 * [P2-guest-tmp-reclaim] Pure predicates behind [ExecutionCoordinator]'s
 * guest-rootfs temp sweeper.
 *
 * Top-level (not object members) so the JVM test can exercise them without
 * loading [ExecutionCoordinator], which depends on `android.content.Context`
 * and `android.os.Debug` — same extraction shape as
 * [internalShouldRetryCommand] / [shouldContinueNativeReclaim].
 */

/**
 * True when a temp entry is old enough for the sweeper to remove.
 *
 * [lastModified] is epoch millis; `0` means "unknown" on some filesystems and
 * is treated as aged (the entry is definitely not being written right now),
 * matching the conservative-on-purpose direction of the neighbouring orphan
 * reaper's mtime gate.
 */
internal fun internalGuestTmpIsAged(lastModified: Long, now: Long, maxAgeMs: Long): Boolean {
    if (lastModified <= 0L) return true
    return now - lastModified >= maxAgeMs
}

/**
 * True when a failed deletion should be retried by descending into [isDirectory]
 * and re-applying the age gate to its children.
 *
 * A symlink is never descended into: the link itself is the entry we failed to
 * unlink, and walking through it would put the target's files under the age
 * gate (deleting files outside the temp dir). A plain file has nothing to
 * descend into.
 */
internal fun internalGuestTmpShouldDescend(isDirectory: Boolean, isSymbolicLink: Boolean): Boolean =
    isDirectory && !isSymbolicLink

/**
 * [guest-tmp-keep-marker] Marker file that pins a guest-temp entry against the
 * sweeper, e.g. `/tmp/my-workspace/.minis-keep`.
 *
 * Deliberately NOT `.keep`: git repos and tar layouts ship `.keep`/`.gitkeep`
 * of their own, so a generic name would pin unrelated directories by accident
 * and leak the space the sweeper exists to reclaim. The name has to be one
 * nobody creates without meaning it.
 *
 * Honoured only at the top level of an entry: a marker nested inside a
 * checkout does not pin the checkout, which keeps "what is pinned?" answerable
 * by looking at `/tmp` alone.
 */
internal const val GUEST_TMP_KEEP_MARKER = ".minis-keep"

/** [guest-tmp-keep-marker] True when [entry] carries the pin marker. */
internal fun internalGuestTmpIsPinned(entry: File): Boolean =
    File(entry, GUEST_TMP_KEEP_MARKER).exists()

/**
 * [guest-tmp-activity-probe] Depth budget for [internalGuestTmpNewestActivityMs].
 *
 * The probe reads the children of every directory it dequeues, so this reaches
 * paths up to `GUEST_TMP_ACTIVITY_MAX_DEPTH + 1` components below the entry:
 * 3 covers a checkout's `.git/index` (two down) and a build's object dirs
 * (three down). Lowering it starts reaping live work again.
 */
internal const val GUEST_TMP_ACTIVITY_MAX_DEPTH = 3

/**
 * [guest-tmp-activity-probe] Cap on how many directories one probe may open,
 * which keeps a sweep over a huge tree flat on its 60 s cadence. Best effort by
 * construction: a tree whose only recent write sits beyond the cap is still
 * reaped, and that direction is deliberate — the sweeper exists to bound `/tmp`.
 */
internal const val GUEST_TMP_ACTIVITY_MAX_ENTRIES = 1024

/**
 * [guest-tmp-activity-probe] Newest mtime (epoch millis) inside [root],
 * breadth-first, stopping after [maxDepth] levels or [maxEntries] directories.
 * `0` means "nothing observed", which the caller reads as "nothing recent" —
 * the same conservative direction as [internalGuestTmpIsAged].
 *
 * WHY this exists: the age gate reads the entry's OWN mtime, and a directory's
 * mtime only changes when an entry is added or removed *directly inside it*.
 * Everything that writes within a directory — `git commit`, `git checkout`,
 * appending to a file, a build dropping objects into `build/` — leaves the
 * parent's mtime untouched. An hours-old work tree that is being actively used
 * therefore looks exactly like abandoned junk and was reaped mid-use (a 214 MB
 * clone was deleted at the one-hour mark while commits were being made inside
 * it).
 *
 * WHY bounded: the caller runs this for every candidate on a 60 s cadence, and
 * an unbounded walk of a large tree is the scan-storm shape this codebase
 * avoids elsewhere (cf. the storage screen's cached-rootfs-scan note). Depth 3
 * covers the shapes that matter — a checkout's `.git/index` is two levels
 * down, a build's object dirs three — and the entry cap keeps the worst case
 * flat. A tree whose only recent write is deeper than both bounds is still
 * reaped: best effort in the direction that keeps `/tmp` bounded, which is
 * what the sweeper is for.
 */
internal fun internalGuestTmpNewestActivityMs(root: File, maxDepth: Int, maxEntries: Int): Long {
    if (maxEntries <= 0) return 0L
    var newest = 0L
    var visited = 0
    val queue = ArrayDeque<Pair<File, Int>>()
    queue.add(root to 0)
    while (queue.isNotEmpty() && visited < maxEntries) {
        val (dir, depth) = queue.removeFirst()
        visited++
        val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
        for (child in children) {
            val mtime = runCatching { child.lastModified() }.getOrDefault(0L)
            if (mtime > newest) newest = mtime
            if (depth >= maxDepth) continue
            val symlink = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)
            if (!symlink && child.isDirectory) queue.add(child to (depth + 1))
        }
    }
    return newest
}

/**
 * [guest-tmp-activity-probe] True when [newestActivityMs] is recent enough that
 * the entry has to be treated as in use.
 *
 * Boundary mirrors [internalGuestTmpIsAged] exactly: an mtime exactly
 * [maxAgeMs] old is aged and therefore NOT live, so the two predicates never
 * disagree about the same timestamp.
 */
internal fun internalGuestTmpLooksLive(newestActivityMs: Long, now: Long, maxAgeMs: Long): Boolean =
    newestActivityMs > 0L && now - newestActivityMs < maxAgeMs
