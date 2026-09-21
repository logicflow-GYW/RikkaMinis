package com.rikkaminis.app.sandbox

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
