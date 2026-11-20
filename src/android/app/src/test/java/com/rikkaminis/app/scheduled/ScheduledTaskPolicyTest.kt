package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ScheduledTaskPolicy] and [ScheduledTaskTransitions] — the
 * pure decision core of the L0 scheduler (task brief B §7.1: 窗口判定 / 单飞 /
 * 熔断).
 *
 * Every expectation below is a literal — none is derived from the code under
 * test — so mutating the rule (e.g. the negative control "make the fuse never
 * trip") fails here instead of masquerading as a pass.
 */
class ScheduledTaskPolicyTest {

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun task(
        id: String = "t-1",
        enabled: Boolean = true,
        start: String = "07:00",
        end: String = "23:00",
        lastRunDate: String? = null,
        failStreak: Int = 0,
        suspendedByFuse: Boolean = false,
    ) = ScheduledTask(
        id = id,
        title = "T",
        prompt = "P",
        windowStart = start,
        windowEnd = end,
        enabled = enabled,
        lastRunDate = lastRunDate,
        failStreak = failStreak,
        suspendedByFuse = suspendedByFuse,
    )

    private fun evaluate(
        tasks: List<ScheduledTask>,
        nowMinute: Int = 8 * 60,
        today: String = "2026-10-04",
        runningTaskId: String? = null,
    ): List<ScheduledTaskPolicy.PassVerdict> =
        ScheduledTaskPolicy.evaluate(today, nowMinute, tasks, runningTaskId)

    // ─── window parsing ──────────────────────────────────────────────────────

    @Test
    fun `window parsing accepts strict HH-MM`() {
        assertEquals(0, ScheduledTaskPolicy.parseWindowMinuteOfDay("00:00"))
        assertEquals(420, ScheduledTaskPolicy.parseWindowMinuteOfDay("07:00"))
        assertEquals(1439, ScheduledTaskPolicy.parseWindowMinuteOfDay("23:59"))
    }

    @Test
    fun `window parsing rejects malformed values`() {
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("7:00"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("07:0"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("07-00"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("24:00"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("07:60"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("ab:cd"))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay(""))
        assertNull(ScheduledTaskPolicy.parseWindowMinuteOfDay("07:00:00"))
    }

    @Test
    fun `window is half-open start-inclusive end-exclusive`() {
        // [420, 1380) — 07:00 through 22:59, NOT 23:00.
        assertTrue(ScheduledTaskPolicy.isWithinWindow(420, 420, 1380))
        assertTrue(ScheduledTaskPolicy.isWithinWindow(1379, 420, 1380))
        assertFalse(ScheduledTaskPolicy.isWithinWindow(419, 420, 1380))
        assertFalse(ScheduledTaskPolicy.isWithinWindow(1380, 420, 1380))
    }

    // ─── per-task verdicts ───────────────────────────────────────────────────

    @Test
    fun `due task fires`() {
        val verdicts = evaluate(listOf(task()))
        assertEquals(1, verdicts.size)
        assertEquals(ScheduledTaskPolicy.PassVerdict.Fire("t-1"), verdicts[0])
    }

    @Test
    fun `disabled task skips with reason disabled`() {
        val verdicts = evaluate(listOf(task(enabled = false)))
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.DISABLED),
            verdicts[0],
        )
    }

    @Test
    fun `fused task skips with reason fuse`() {
        val verdicts = evaluate(listOf(task(suspendedByFuse = true)))
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.FUSE),
            verdicts[0],
        )
    }

    @Test
    fun `task already run today skips with reason already-ran`() {
        val verdicts = evaluate(listOf(task(lastRunDate = "2026-10-04")))
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.ALREADY_RAN),
            verdicts[0],
        )
    }

    @Test
    fun `task run yesterday but not today still fires`() {
        val verdicts = evaluate(listOf(task(lastRunDate = "2026-10-03")))
        assertEquals(ScheduledTaskPolicy.PassVerdict.Fire("t-1"), verdicts[0])
    }

    @Test
    fun `before window start skips with reason outside-window`() {
        val verdicts = evaluate(listOf(task()), nowMinute = 6 * 60 + 59)
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.OUTSIDE_WINDOW),
            verdicts[0],
        )
    }

    @Test
    fun `after window end misses with window-passed`() {
        val verdicts = evaluate(listOf(task()), nowMinute = 23 * 60)
        assertEquals(ScheduledTaskPolicy.PassVerdict.Miss("t-1"), verdicts[0])
    }

    @Test
    fun `invalid window skips with reason invalid-window and never fires`() {
        // start >= end is rejected outright (no midnight spanning in L0).
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.INVALID_WINDOW),
            evaluate(listOf(task(start = "23:00", end = "07:00")))[0],
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.INVALID_WINDOW),
            evaluate(listOf(task(start = "07:00", end = "07:00")))[0],
        )
        // Malformed window strings parse to null -> same verdict.
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.INVALID_WINDOW),
            evaluate(listOf(task(start = "", end = "23:00")))[0],
        )
    }

    // ─── single-flight (brief §5.2) ──────────────────────────────────────────

    @Test
    fun `two due tasks fire only the first in list order`() {
        val verdicts = evaluate(listOf(task(id = "t-1"), task(id = "t-2")))
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Fire("t-1"),
            verdicts[0],
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-2", ScheduledTaskPolicy.SkipReason.OVERLAP),
            verdicts[1],
        )
    }

    @Test
    fun `with a run already in flight every due task degrades to overlap`() {
        val verdicts = evaluate(listOf(task(id = "t-1"), task(id = "t-2")), runningTaskId = "t-9")
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.OVERLAP),
            verdicts[0],
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-2", ScheduledTaskPolicy.SkipReason.OVERLAP),
            verdicts[1],
        )
    }

    // ─── transitions: fuse (brief §5.3) ──────────────────────────────────────
    //
    // [A03] A settle is only credited for the run the document names: every
    // fixture below carries the marker its run wrote (task id + run session),
    // and the calls pass that same session. The late-settlement arms (task
    // deleted / restored / re-created, newer run) live in
    // [ScheduledTaskLateSettlementTest].

    /** A document with one task and the marker of ITS run (session [session]). */
    private fun runningDoc(task: ScheduledTask, session: String = "s-run"): ScheduledTasksFile =
        ScheduledTasksFile(
            tasks = listOf(task),
            running = ScheduledRunMarker(task.id, session, 1_000L),
        )

    @Test
    fun `two consecutive failures do not trip the fuse`() {
        val after1 = ScheduledTaskTransitions.completeRun(runningDoc(task(failStreak = 0)), ScheduledRunRef("t-1", "s-run"), ScheduledTask.RESULT_ERROR,
            today = "2026-10-04",
        )
        assertFalse(after1.fuseTripped)
        assertEquals(1, after1.task?.failStreak)
        assertEquals(true, after1.task?.enabled)

        // A failure clears the marker (the gate retries in the same window), so
        // the second attempt is its own run with its own session.
        val after2 = ScheduledTaskTransitions.completeRun(
            after1.file.copy(running = ScheduledRunMarker("t-1", "s-run-2", 2_000L)),
            ScheduledRunRef("t-1", "s-run-2"),
            ScheduledTask.RESULT_ERROR,
            today = "2026-10-04",
        )
        assertFalse(after2.fuseTripped)
        assertEquals(2, after2.task?.failStreak)
        assertEquals(true, after2.task?.enabled)
        assertEquals(false, after2.task?.suspendedByFuse)
    }

    @Test
    fun `third consecutive failure trips the fuse and disables the task`() {
        val file = runningDoc(task(failStreak = 2))
        val res = ScheduledTaskTransitions.completeRun(file, ScheduledRunRef("t-1", "s-run"), ScheduledTask.RESULT_ERROR,
            today = "2026-10-04",
        )
        assertTrue(res.fuseTripped)
        assertEquals(3, res.task?.failStreak)
        assertEquals(false, res.task?.enabled)
        assertEquals(true, res.task?.suspendedByFuse)
    }

    @Test
    fun `timeout outcome also counts toward the fuse`() {
        val res = ScheduledTaskTransitions.completeRun(runningDoc(task(failStreak = 2)), ScheduledRunRef("t-1", "s-run"), ScheduledTask.RESULT_TIMEOUT,
            today = "2026-10-04",
        )
        assertTrue(res.fuseTripped)
        assertEquals("timeout", res.task?.lastResult)
    }

    @Test
    fun `success resets the streak and stamps the day`() {
        val res = ScheduledTaskTransitions.completeRun(runningDoc(task(failStreak = 2)), ScheduledRunRef("t-1", "s-run"), ScheduledTask.RESULT_OK,
            today = "2026-10-04",
        )
        assertFalse(res.fuseTripped)
        assertEquals(0, res.task?.failStreak)
        assertEquals("ok", res.task?.lastResult)
        assertEquals("2026-10-04", res.task?.lastRunDate)
    }

    @Test
    fun `failure does not advance lastRunDate so the task can retry same window`() {
        val res = ScheduledTaskTransitions.completeRun(runningDoc(task(lastRunDate = "2026-10-02")), ScheduledRunRef("t-1", "s-run"), ScheduledTask.RESULT_ERROR,
            today = "2026-10-04",
        )
        assertEquals("2026-10-02", res.task?.lastRunDate)
        assertEquals("error", res.task?.lastResult)
    }

    @Test
    fun `every settled run clears the persisted run marker`() {
        assertEquals(
            null,
            ScheduledTaskTransitions.completeRun(runningDoc(task()), ScheduledRunRef("t-1", "s-run"), "ok", "2026-10-04").file.running,
        )
        assertEquals(
            null,
            ScheduledTaskTransitions.completeRun(runningDoc(task()), ScheduledRunRef("t-1", "s-run"), "error", "2026-10-04").file.running,
        )
    }

    @Test
    fun `settlement of a deleted task clears the marker and changes nothing else`() {
        val file = ScheduledTasksFile(
            running = ScheduledRunMarker("t-gone", "s-9", 1_000L),
            tasks = listOf(task(id = "t-1")),
        )
        val res = ScheduledTaskTransitions.completeRun(file, ScheduledRunRef("t-gone", "s-9"), "ok", "2026-10-04")
        assertNull(res.task)
        assertFalse(res.fuseTripped)
        assertEquals(null, res.file.running)
        assertEquals(1, res.file.tasks.size)
    }

    // ─── transitions: crash recovery (brief §3) ──────────────────────────────

    @Test
    fun `crash failure counts one failure and clears the marker`() {
        val file = ScheduledTasksFile(
            running = ScheduledRunMarker("t-1", "s-1", 1_000L),
            tasks = listOf(task(failStreak = 1)),
        )
        val res = ScheduledTaskTransitions.crashFailure(file, "t-1")
        assertEquals(2, res.task?.failStreak)
        assertEquals(null, res.file.running)
        assertFalse(res.fuseTripped)
    }

    @Test
    fun `crash failure for a mismatched marker is a no-op`() {
        val file = ScheduledTasksFile(
            running = ScheduledRunMarker("t-other", "s-9", 1_000L),
            tasks = listOf(task(failStreak = 1)),
        )
        val res = ScheduledTaskTransitions.crashFailure(file, "t-1")
        assertEquals(1, res.file.tasks[0].failStreak)
        assertEquals("t-other", res.file.running?.taskId)
    }

    // ─── transitions: manual resume (brief §5.3 recovery) ────────────────────

    @Test
    fun `resume clears the fuse resets the streak and re-enables`() {
        val file = ScheduledTasksFile(
            tasks = listOf(task(enabled = false, failStreak = 3, suspendedByFuse = true)),
        )
        val resumed = ScheduledTaskTransitions.resume(file, "t-1")
        val t = resumed.tasks[0]
        assertEquals(false, t.suspendedByFuse)
        assertEquals(0, t.failStreak)
        assertEquals(true, t.enabled)
    }
}
