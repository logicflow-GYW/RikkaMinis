package com.rikkaminis.app.sandbox.offload

/**
 * [fix/stream-recovery-grace-race] Pure decision table for one staging run
 * dir at cold start, split out of PartialStreamRecovery.kt verbatim so the
 * real source + its tests compile standalone on the JVM (the Android-side
 * [PartialStreamRecovery.recover] consumes it verbatim — same package, no
 * import).
 *
 * Order matters: recovered-barrier first (a duplicate insert is the worst
 * outcome — the delete after insert can fail), then terminal / cancel (their
 * owners already committed or discarded the content), then the recent-write
 * grace, then empty, then no-meta.
 *
 * [fix/stream-recovery-grace-race] The grace now outranks the empty /
 * no-meta checks. A live worker's NON-streaming run (title generation,
 * QuickTest, compaction dispatches) writes no stream.jsonl and no meta —
 * under the old order, a main-process restart racing a still-running
 * :modelservice read that dir as `empty`/`no meta` and deleted it. An empty
 * stream or missing meta proves nothing while the dir's beat is fresh; the
 * grace is the only signal that the run may still be alive. After the grace
 * an empty/no-meta dir is cleanup as before (with a beatAlive re-check at
 * the delete site in [PartialStreamRecovery.recover]).
 */
internal object PartialStreamRecoveryPolicy {

    enum class Decision { RECOVER, SKIP_TERMINAL, SKIP_CANCELLED, SKIP_EMPTY, SKIP_ACTIVE, SKIP_RECOVERED, SKIP_NO_META }

    /**
     * Grace for dirs touched very recently: at cold start the :modelservice
     * process is normally dead too, but a service that outlived a main-process
     * restart may still be appending — never race an active run.
     */
    const val RECENT_WRITE_GRACE_MS: Long = 30_000L

    fun decide(
        streamLen: Long,
        terminalPresent: Boolean,
        cancelPresent: Boolean,
        recoveredPresent: Boolean,
        hasSessionMeta: Boolean,
        mtimeAgeMs: Long,
    ): Decision = when {
        recoveredPresent -> Decision.SKIP_RECOVERED
        terminalPresent -> Decision.SKIP_TERMINAL
        cancelPresent -> Decision.SKIP_CANCELLED
        mtimeAgeMs < RECENT_WRITE_GRACE_MS -> Decision.SKIP_ACTIVE
        streamLen <= 0L -> Decision.SKIP_EMPTY
        !hasSessionMeta -> Decision.SKIP_NO_META
        else -> Decision.RECOVER
    }
}
