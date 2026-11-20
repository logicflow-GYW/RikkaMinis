package com.rikkaminis.app.sandbox.offload

/**
 * [fix/stream-recovery-grace-race] Pure decision table for one staging run
 * dir at cold start, split out of PartialStreamRecovery.kt verbatim so the
 * real source + its tests compile standalone on the JVM (the Android-side
 * [PartialStreamRecovery.recover] consumes it verbatim — same package, no
 * import).
 *
 * Order matters, and it now encodes OWNERSHIP before content:
 *
 *  1. `recovered` marker — a duplicate insert is the worst outcome (the delete
 *     after an insert can fail), and this marker is the recovery's own
 *     "already handled" record.
 *  2. [surgery-1120 A-01 review] `ownerLive` — THIS process still has a
 *     collector attached to the dir. The late (90 s) recovery pass runs while
 *     the app streams; a dir's own mtime never moves when the worker appends
 *     `stream.jsonl` inside it, so content gates cannot tell an active run
 *     from an abandoned one. Only the owner can.
 *  3. a user cancel (`cancel` without `cancel.death`) — discarded by design.
 *  4. the recent-write grace. [fix/stream-recovery-grace-race] A live worker's
 *     NON-streaming run (title generation, QuickTest, compaction dispatches)
 *     writes no stream.jsonl and no meta; an empty stream or missing meta
 *     proves nothing while the dir is fresh, so the grace outranks both.
 *  5. [surgery-1120 A-01 review] `workerMayWrite` — a fresh heartbeat or a
 *     locally-visible matching worker pid. The grace alone was a timer; this
 *     is the ownership fact it stood in for.
 *  6. empty stream → cleanup.
 *  7. missing session meta → unattributable, cleanup.
 *  8. a terminal WITHOUT a clean DONE frame is the old error/non-chat shape:
 *     no transcript row is expected, keep the old skip semantics.
 *  9. [surgery-1120 A-01] a terminal WITH a committed transcript marker is
 *     already delivered.
 * 10. otherwise RECOVER.
 *
 * Steps 8/9 sit after the ownership gates on purpose: a terminal dir whose
 * worker is still alive, or whose collector is still attached, is not evidence
 * of anything — and the two "keep" answers are behaviourally identical, so
 * moving them cannot resurrect or drop content. What DOES change is that a
 * terminal dir carrying content can now be recovered instead of skipped: that
 * is exactly the crash window (DONE read, Room row not yet written).
 */
internal object PartialStreamRecoveryPolicy {

    enum class Decision {
        RECOVER,
        SKIP_TERMINAL,
        SKIP_CANCELLED,
        SKIP_EMPTY,
        SKIP_ACTIVE,
        SKIP_RECOVERED,

        /** [surgery-1120 A-01 review] This process still owns the dir. */
        SKIP_OWNED,
        SKIP_NO_META,
    }

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
        cancelDeathPresent: Boolean = false,
        terminalCommitted: Boolean = true,
        /**
         * Reads the clean-DONE frame. A function (not a boolean) so the
         * recovery scans the spool ONLY for dirs whose decision depends on it
         * — an active, cancelled or committed dir is answered without touching
         * its content. The scan is memoised by the caller, so this is one
         * bounded pass per candidate dir, never two.
         */
        readDone: () -> Boolean = { false },
        /** [surgery-1120 A-01 review] this process still owns the dir. */
        ownerLive: Boolean = false,
        /** [surgery-1120 A-01 review] a worker is provably alive for it. */
        workerMayWrite: Boolean = false,
    ): Decision = when {
        recoveredPresent -> Decision.SKIP_RECOVERED
        ownerLive -> Decision.SKIP_OWNED
        // [LOOP-1030 R2 M2] `cancel` alone = user stop → discard by design.
        // `cancel` + `cancel.death` = the client's DEATH cleanup
        // (ModelWorkerDiedException): stream.jsonl holds real model output the
        // client never appended, so it must not be skipped here — it falls
        // through to the SAME gates as a silent death (grace → empty → meta).
        // The grace still matters: a frozen worker that thaws may append more
        // before its own `cancel` takes effect.
        cancelPresent && !cancelDeathPresent -> Decision.SKIP_CANCELLED
        mtimeAgeMs < RECENT_WRITE_GRACE_MS -> Decision.SKIP_ACTIVE
        workerMayWrite -> Decision.SKIP_ACTIVE
        streamLen <= 0L -> Decision.SKIP_EMPTY
        !hasSessionMeta -> Decision.SKIP_NO_META
        // [surgery-1120 A-01] Only a clean DONE whose transcript marker is
        // absent is the uncommitted-normal-completion window. Error terminals
        // and older non-chat terminal runs retain their old skip semantics.
        terminalPresent && !readDone() -> Decision.SKIP_TERMINAL
        terminalPresent && terminalCommitted -> Decision.SKIP_TERMINAL
        else -> Decision.RECOVER
    }
}
