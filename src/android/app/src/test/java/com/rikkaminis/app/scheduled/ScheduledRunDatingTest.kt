package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [deep-audit-1016c ISSUE-09A] JVM tests for [ScheduledRunDating] — the pure
 * decision core behind `ScheduledTaskRunner.finalizeRun`'s daily-gate write.
 *
 * Defect shape being pinned (fix-f report §A): `finalizeRun` read the settle-day
 * clock (`LocalDate.now()` at settle time). A run fired 23:55 that settled at
 * 00:06 pinned TOMORROW's date into [ScheduledTask.lastRunDate], so
 * [ScheduledTaskPolicy.evaluate] answered ALREADY_RAN for the whole of tomorrow
 * — a full day of scheduled work silently skipped while nothing ran.
 *
 * The fix credits the SCHEDULED day (captured at fire time in
 * `Dispatch.scheduledDate`, threaded through onSessionSettled / reap / both
 * fire-failure paths). `gateDateOnSettle` returns it verbatim.
 *
 * Every expectation below is a literal; the negative control documented on
 * [ScheduledRunDating.gateDateOnSettle] is exercised by MUT-A (swap the body to
 * `settleDate`) which must flip exactly the cross-midnight arms red.
 */
class ScheduledRunDatingTest {

    // ─── cross-midnight settlement (the defect arm) ─────────────────────────

    @Test
    fun `run fired before midnight settling after midnight credits the scheduled day`() {
        // 2026-10-15 23:55 fire → 2026-10-16 00:06 settle (defect's exact shape).
        // OLD code pinned 2026-10-16 → tomorrow evaluated ALREADY_RAN all day.
        assertEquals(
            "2026-10-15",
            ScheduledRunDating.gateDateOnSettle(
                scheduledDate = "2026-10-15",
                settleDate = "2026-10-16",
                outcome = ScheduledTask.RESULT_OK,
            ),
        )
    }

    @Test
    fun `run fired before midnight settling at midnight zero credits the scheduled day`() {
        // Boundary: settlement lands exactly at 00:00 — still the scheduled day.
        assertEquals(
            "2026-10-15",
            ScheduledRunDating.gateDateOnSettle(
                scheduledDate = "2026-10-15",
                settleDate = "2026-10-16",
                outcome = ScheduledTask.RESULT_OK,
            ),
        )
    }

    // ─── same-day settlement (unchanged behavior must survive) ───────────────

    @Test
    fun `run fired and settled on the same day keeps that day`() {
        assertEquals(
            "2026-10-16",
            ScheduledRunDating.gateDateOnSettle(
                scheduledDate = "2026-10-16",
                settleDate = "2026-10-16",
                outcome = ScheduledTask.RESULT_OK,
            ),
        )
    }

    @Test
    fun `settle and scheduled days are independent facts - scheduled wins whatever the settle day`() {
        // Month / year rollovers: the decision must not key off the settle date.
        assertEquals(
            "2025-12-31",
            ScheduledRunDating.gateDateOnSettle("2025-12-31", "2026-01-01", ScheduledTask.RESULT_OK),
        )
        assertEquals(
            "2026-02-28",
            ScheduledRunDating.gateDateOnSettle("2026-02-28", "2026-03-01", ScheduledTask.RESULT_OK),
        )
    }

    // ─── failure semantics (gate must never advance on non-OK, brief §3) ─────

    @Test
    fun `error outcome still carries the scheduled day - store ignores it for non-OK`() {
        // ScheduledTaskTransitions.completeRun leaves lastRunDate untouched for
        // non-OK outcomes; the dating decision must stay scheduled-day-shaped
        // for those too (no settle-day leakage through the failure path).
        assertEquals(
            "2026-10-15",
            ScheduledRunDating.gateDateOnSettle("2026-10-15", "2026-10-16", ScheduledTask.RESULT_ERROR),
        )
        assertEquals(
            "2026-10-15",
            ScheduledRunDating.gateDateOnSettle("2026-10-15", "2026-10-16", ScheduledTask.RESULT_TIMEOUT),
        )
    }

    // ─── shape: the caller contract, not just the return value ───────────────

    @Test
    fun `decision is total over all three facts and outcome does not change the date`() {
        for (outcome in listOf(ScheduledTask.RESULT_OK, ScheduledTask.RESULT_ERROR, ScheduledTask.RESULT_TIMEOUT)) {
            assertEquals("2026-10-15", ScheduledRunDating.gateDateOnSettle("2026-10-15", "2026-10-17", outcome))
        }
    }

    // ─── end-to-end through the REAL transitions + policy (read-only deps) ───
    //
    // The production write is ScheduledTaskTransitions.completeRun(file, id,
    // outcome, gateDateOnSettle(...)); the daily gate is
    // ScheduledTaskPolicy.evaluate(nowDate, ...) → ALREADY_RAN when
    // lastRunDate == nowDate. These two tests pin the CONTRACT of the fix at
    // the seams that exist today (transitions + policy are read-only here):
    // the credited date must leave tomorrow's window eligible.

    /**
     * [A03] The settle carries the run's identity, so the fixture is the state
     * the fire pass leaves behind: the task plus ITS `running` marker (the run
     * session is "s-cross"). A document without that marker refuses the settle
     * by design (see [ScheduledTaskLateSettlementTest]).
     */
    private fun freshFile(): ScheduledTasksFile = ScheduledTasksFile(
        globalEnabled = true,
        tasks = listOf(
            ScheduledTask(
                id = "t-cross",
                title = "nightly",
                prompt = "p",
                windowStart = "23:50",
                windowEnd = "23:59",
                enabled = true,
            ),
        ),
        running = ScheduledRunMarker(taskId = "t-cross", sessionId = "s-cross", startedAt = 1_000L),
    )

    @Test
    fun `cross-midnight settle through real transitions leaves tomorrow eligible`() {
        // Fire pass ran 2026-10-15 (window 23:50-23:59); run settles 00:06 on
        // 2026-10-16. The fix credits "2026-10-15".
        val gateDate = ScheduledRunDating.gateDateOnSettle("2026-10-15", "2026-10-16", ScheduledTask.RESULT_OK)
        val after = ScheduledTaskTransitions.completeRun(freshFile(), ScheduledRunRef("t-cross", "s-cross"), ScheduledTask.RESULT_OK, gateDate)
        assertEquals("2026-10-15", after.file.tasks[0].lastRunDate)

        // Next day, 23:55, inside the next day's window: must be Fire, NOT
        // ALREADY_RAN (the defect shape answered Skip(ALREADY_RAN) all day).
        val verdicts = ScheduledTaskPolicy.evaluate("2026-10-16", 23 * 60 + 55, after.file.tasks, null)
        assertEquals(ScheduledTaskPolicy.PassVerdict.Fire("t-cross"), verdicts[0])
    }

    @Test
    fun `contrast arm - the old settle-day pinning skips tomorrow whole`() {
        // What the OLD code wrote (LocalDate.now() at settle time): the settle
        // day. Pinned as a negative-control contrast: with that write, the next
        // day's beat inside the window is exactly the defect (ALREADY_RAN).
        val after = ScheduledTaskTransitions.completeRun(freshFile(), ScheduledRunRef("t-cross", "s-cross"), ScheduledTask.RESULT_OK, "2026-10-16")
        assertEquals("2026-10-16", after.file.tasks[0].lastRunDate)
        val verdicts = ScheduledTaskPolicy.evaluate("2026-10-16", 23 * 60 + 55, after.file.tasks, null)
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-cross", ScheduledTaskPolicy.SkipReason.ALREADY_RAN),
            verdicts[0],
        )
    }

    @Test
    fun `failure arm - real transitions never advances the gate whatever date is passed`() {
        for (today in listOf("2026-10-15", "2026-10-16", "")) {
            val after = ScheduledTaskTransitions.completeRun(freshFile(), ScheduledRunRef("t-cross", "s-cross"), ScheduledTask.RESULT_ERROR, today)
            assertEquals(null, after.file.tasks[0].lastRunDate)
            assertEquals(ScheduledTask.RESULT_ERROR, after.file.tasks[0].lastResult)
        }
    }
}
