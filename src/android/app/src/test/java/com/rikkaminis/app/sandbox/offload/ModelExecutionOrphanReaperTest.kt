package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the cancelled-orphan reclaim branch of the orphan
 * reaper ([fix/sweep1014-d2a]): a run dir with a `cancel` marker but no
 * `terminal.json` used to be skipped forever by BOTH the reaper (terminal
 * barrier) and the cold-start recovery policy (SKIP_CANCELLED), leaving
 * MB-sized request payloads unreclaimable.
 *
 * The tested function is the pure decision
 * [ModelExecutionOrphanReaper.shouldReapCancelledOrphan]; the path guard
 * (canonicalize + direct-child-of-staging-root) is exercised by the
 * caller and unchanged by this fix. Expected values are hardcoded
 * literals, not derived from [ModelExecutionOrphanReaper.ORPHAN_AGE_MS],
 * so a constant change cannot silently flip a mutation into a PASS.
 */
class ModelExecutionOrphanReaperTest {

    private fun decide(
        cancel: Boolean = true,
        beatAlive: Boolean = false,
        procAlive: Boolean = false,
        ageMs: Long = 600_000L, // 10 min literal, NOT derived from ORPHAN_AGE_MS
        cancelDeath: Boolean = false,
    ) = ModelExecutionOrphanReaper.shouldReapCancelledOrphan(
        cancelPresent = cancel,
        beatAlive = beatAlive,
        workerLivenessAlive = procAlive,
        ageMs = ageMs,
        cancelDeathPresent = cancelDeath,
    )

    @Test
    fun cancelledOrphan_aged_workerNotAlive_reaps() {
        // cancel present, no terminal (branch precondition), beat stale,
        // pid probe not alive, dir aged past the orphan window → reclaim.
        assertTrue(decide())
    }

    @Test
    fun cancelledOrphan_recent_skipped() {
        // Cancel was just written (the write itself refreshes the dir mtime) —
        // an alive worker acks the cancel and finishes within seconds; never
        // race that window.
        assertFalse(decide(ageMs = 60_000L))
    }

    @Test
    fun cancelledOrphan_ageExactlyAtWindow_reaps() {
        assertTrue(decide(ageMs = 600_000L))
    }

    @Test
    fun cancelledOrphan_ageJustUnderWindow_skipped() {
        assertFalse(decide(ageMs = 599_999L))
    }

    @Test
    fun noCancel_noTerminal_stillSkipped() {
        // The existing skip is unchanged: without a cancel the old
        // "absent terminal is NEVER deleted" rule still holds.
        assertFalse(decide(cancel = false))
    }

    @Test
    fun freshBeat_skipped() {
        // A fresh liveness beat proves the worker alive for THIS run even
        // where /proc is unreadable (TF-J2 hidepid).
        assertFalse(decide(beatAlive = true))
    }

    @Test
    fun procProbeAlive_skipped() {
        // The pid probe is the only reliable-POSITIVE liveness signal —
        // never delete under a worker it can see and match.
        assertFalse(decide(procAlive = true))
    }

    @Test
    fun deathCancelledOrphan_neverReaped() {
        // [LOOP-1030 R2 M2] `cancel.death` marks the cancel as written by the
        // client's death cleanup: stream.jsonl holds a recoverable half answer,
        // not content "discarded by design". Reclaiming it here would delete
        // the answer ~10 min after the crash, before cold-start recovery runs.
        // Every other condition satisfied (aged, no beat, no proc) — still no.
        assertFalse(decide(cancelDeath = true))
    }

    @Test
    fun deathCancelledOrphan_recent_skipped() {
        assertFalse(decide(cancelDeath = true, ageMs = 60_000L))
    }
}
