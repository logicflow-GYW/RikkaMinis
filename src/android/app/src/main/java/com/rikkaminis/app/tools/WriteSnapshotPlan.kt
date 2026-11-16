package com.rikkaminis.app.tools

/**
 * [fix/append-only-write-guard-1116] Decides whether a whole-file write should
 * leave a snapshot of the bytes it is about to replace.
 *
 * This is the second half of the non-symmetric guard: [AppendOnlyWriteGuard]
 * makes the destructive path *harder* (explicit flag + reason), and this makes
 * the residual damage *recoverable* without anyone remembering to `cp` first.
 *
 * Two triggers, deliberately different in nature:
 *  - **append-only target**: always snapshot. These files are the ones with a
 *    history of being lost, and they are already known to be large.
 *  - **ordinary target that shrinks hard**: snapshot when a file of at least
 *    [MIN_TARGET_BYTES] is replaced by something under half its size. A model
 *    rewriting a 2 MB document with a 1 KB "summary" is the same accident class
 *    as incident #6, just aimed at a different file.
 *
 * Normal writes pay nothing: growing a file, rewriting a small file, and
 * creating a new file all skip the copy. That asymmetry is the point — the
 * snapshot cost is charged only where damage is possible.
 *
 * Pure by design (no Android, no IO) so the thresholds are JVM-testable.
 */
internal object WriteSnapshotPlan {

    /**
     * Below this size a shrink is not worth a snapshot: a human-authored notes
     * file routinely goes from a few KB to a few hundred bytes, and paying a
     * copy + a `.bak-*` file on every such edit would make normal use noisier
     * for no recovery value. 8 KiB is roughly where "a document that somebody
     * would miss" starts; the six backlog incidents all involved files three
     * orders of magnitude larger.
     */
    const val MIN_TARGET_BYTES = 8_192L

    /** Incoming must be under 1/[SHRINK_DIVISOR] of the current size. */
    private const val SHRINK_DIVISOR = 2L

    /**
     * @param append the caller's `append` flag. Appending cannot destroy
     *   anything, so it never snapshots — otherwise every append to a ledger
     *   would copy megabytes first.
     * @param appendOnly whether [AppendOnlyWriteGuard.isAppendOnly] matched.
     * @param exists whether the target is already on disk. Nothing to snapshot
     *   otherwise.
     * @param currentBytes size of the file about to be replaced.
     * @param incomingBytes size of the payload about to be written.
     */
    fun shouldSnapshot(
        append: Boolean,
        appendOnly: Boolean,
        exists: Boolean,
        currentBytes: Long,
        incomingBytes: Long,
    ): Boolean {
        if (append) return false
        if (!exists) return false
        if (appendOnly) return true
        if (currentBytes < MIN_TARGET_BYTES) return false
        // Multiplication rather than division: no rounding at the boundary, and
        // no divide-by-zero shape to reason about.
        return incomingBytes * SHRINK_DIVISOR < currentBytes
    }

    /**
     * Sibling snapshot path: `backlog.md` → `backlog.md.bak-20261116-150312`.
     *
     * A sibling (not a hidden directory) because that is the convention every
     * past recovery actually reached for — `.bak-*` next to the file — and
     * because it survives `rm -rf` of a work directory the way a copy outside
     * the tree would not.
     */
    fun snapshotPath(targetPath: String, stamp: String): String = "$targetPath.bak-$stamp"
}
