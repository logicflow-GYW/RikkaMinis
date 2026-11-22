package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [A03 late scheduled settlement] A run that settles AFTER its task was
 * deleted, after a backup restore re-created the same id, or while a newer run
 * of the same task holds the marker must not credit anything.
 *
 * The rule under test is the production decision itself
 * ([ScheduledTaskTransitions.completeRun], compiled from the real
 * `ScheduledTaskPolicy.kt`), not a copy: a settle is accepted only when the
 * document's `running` marker names the settling run — task id + run session,
 * the session being the UUID-minted id `HeadlessChatRunner.ensureSession`
 * creates for every dispatch (two runs of one task never share it).
 *
 * The "identity" in the assertions is literal data ("s-old" / "s-new"), never
 * derived from the code under test, so removing the identity check flips these
 * arms red instead of passing vacuously.
 */
class ScheduledTaskLateSettlementTest {

    // ─── fixtures ────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        lastRunDate: String? = null,
        lastResult: String? = null,
        failStreak: Int = 0,
    ) = ScheduledTask(
        id = id,
        title = "title-$id",
        prompt = "prompt-$id",
        windowStart = "07:00",
        windowEnd = "23:00",
        enabled = true,
        lastRunDate = lastRunDate,
        lastResult = lastResult,
        failStreak = failStreak,
    )

    private fun marker(taskId: String, sessionId: String) =
        ScheduledRunMarker(taskId = taskId, sessionId = sessionId, startedAt = 1_000L)

    private fun doc(
        tasks: List<ScheduledTask>,
        running: ScheduledRunMarker? = null,
    ) = ScheduledTasksFile(globalEnabled = true, running = running, tasks = tasks)

    // ─── the scenarios the audit named ───────────────────────────────────────

    /**
     * The defect, pure: pre-fix, `completeRun("same-id", ok)` on a document that
     * was RESTORED (marker cleared, task re-created by the backup) stamped
     * `lastRunDate` / `lastResult` onto a run the NEW task never performed. The
     * audit reproduced that write (evidence/A-runtime/LateSettlementProbeTest);
     * here the same call must leave the document untouched.
     */
    @Test
    fun `restored same-id task is not credited by the old run's settle`() {
        val restored = doc(tasks = listOf(task("same-id")))

        val res = ScheduledTaskTransitions.completeRun(restored, ScheduledRunRef("same-id", "s-old"), ScheduledTask.RESULT_OK, "2026-11-20")

        assertSame("a refused settle must hand the document back unchanged (no write)", restored, res.file)
        assertNull("nothing may be reported as this run's outcome", res.task)
        assertFalse(res.fuseTripped)
        assertNull(restored.tasks.single().lastRunDate)
        assertNull(restored.tasks.single().lastResult)
    }

    @Test
    fun `deleted task's late settle is not credited anywhere`() {
        val afterDelete = doc(tasks = listOf(task("t-other")))

        val res = ScheduledTaskTransitions.completeRun(afterDelete, ScheduledRunRef("t-deleted", "s-old"), ScheduledTask.RESULT_OK, "2026-11-20")

        assertSame(afterDelete, res.file)
        assertNull(res.task)
        assertNull(afterDelete.tasks.single().lastRunDate)
    }

    /**
     * Deleted, then re-created with the same id AND dispatched again: the new
     * run's marker (s-new) must survive the old run's settle untouched — the
     * old settle may neither credit the task nor erase the evidence that a
     * different run is in flight.
     */
    @Test
    fun `old settle leaves a newer run's marker alone`() {
        val withNewRun = doc(
            tasks = listOf(task("same-id")),
            running = marker("same-id", "s-new"),
        )

        val res = ScheduledTaskTransitions.completeRun(withNewRun, ScheduledRunRef("same-id", "s-old"), ScheduledTask.RESULT_OK, "2026-11-20")

        assertSame(withNewRun, res.file)
        assertNull(res.task)
        assertEquals("the newer run still owns the marker", "s-new", res.file.running?.sessionId)
    }

    @Test
    fun `old failure cannot bump the new task's streak`() {
        val withNewRun = doc(
            tasks = listOf(task("same-id", failStreak = 0)),
            running = marker("same-id", "s-new"),
        )

        val res = ScheduledTaskTransitions.completeRun(withNewRun, ScheduledRunRef("same-id", "s-old"), ScheduledTask.RESULT_ERROR, "2026-11-20")

        assertSame(withNewRun, res.file)
        assertNull(res.task)
        assertEquals(0, res.file.tasks.single().failStreak)
        assertNull(res.file.tasks.single().lastResult)
    }

    @Test
    fun `a settle for another task's marker is refused`() {
        val otherRunning = doc(
            tasks = listOf(task("t-1"), task("t-2")),
            running = marker("t-2", "s-2"),
        )

        val res = ScheduledTaskTransitions.completeRun(otherRunning, ScheduledRunRef("t-1", "s-1"), ScheduledTask.RESULT_OK, "2026-11-20")

        assertSame(otherRunning, res.file)
        assertNull(res.task)
        assertEquals("s-2", res.file.running?.sessionId)
        assertNull(res.file.tasks.first { it.id == "t-1" }.lastRunDate)
    }

    // ─── the run that IS the document's run still settles ────────────────────

    @Test
    fun `the current run's own success is credited and clears the marker`() {
        val running = doc(tasks = listOf(task("t-1")), running = marker("t-1", "s-current"))

        val res = ScheduledTaskTransitions.completeRun(running, ScheduledRunRef("t-1", "s-current"), ScheduledTask.RESULT_OK, "2026-11-20")

        assertEquals("2026-11-20", res.task?.lastRunDate)
        assertEquals(ScheduledTask.RESULT_OK, res.task?.lastResult)
        assertEquals(0, res.task?.failStreak)
        assertNull("a settled run clears its own marker", res.file.running)
    }

    @Test
    fun `the current run's own failure bumps the streak and clears the marker`() {
        val running = doc(tasks = listOf(task("t-1", failStreak = 1)), running = marker("t-1", "s-current"))

        val res = ScheduledTaskTransitions.completeRun(running, ScheduledRunRef("t-1", "s-current"), ScheduledTask.RESULT_ERROR, "2026-11-20")

        assertEquals(2, res.task?.failStreak)
        assertEquals(ScheduledTask.RESULT_ERROR, res.task?.lastResult)
        assertNull(res.file.running)
    }

    /** A repeated settle (tracker fires twice) is a no-op, not a second credit. */
    @Test
    fun `duplicate settle after the first one is refused`() {
        val running = doc(tasks = listOf(task("t-1")), running = marker("t-1", "s-current"))
        val first = ScheduledTaskTransitions.completeRun(running, ScheduledRunRef("t-1", "s-current"), ScheduledTask.RESULT_ERROR, "2026-11-20")
        assertEquals(1, first.task?.failStreak)

        val second = ScheduledTaskTransitions.completeRun(first.file, ScheduledRunRef("t-1", "s-current"), ScheduledTask.RESULT_ERROR, "2026-11-20")

        assertSame(first.file, second.file)
        assertNull(second.task)
        assertEquals("the streak must not grow twice for one run", 1, first.file.tasks.single().failStreak)
    }

    @Test
    fun `a settle without a run identity writes nothing`() {
        val running = doc(tasks = listOf(task("t-1")), running = marker("t-1", "s-current"))

        val res = ScheduledTaskTransitions.completeRun(running, ScheduledRunRef("t-1", null), ScheduledTask.RESULT_OK, "2026-11-20")

        assertSame(running, res.file)
        assertNull(res.task)
        assertEquals("s-current", res.file.running?.sessionId)
    }

    // ─── a run that never started (no session, so no marker) ────────────────

    /**
     * `prepareSession` threw: no session, therefore no `running` marker was ever
     * written. Pre-existing semantics still count that attempt as a failure so
     * the day's window stays retryable — but only while the document really has
     * no marker. If a marker exists it belongs to another run, and crediting
     * through it is exactly the defect this change removes.
     */
    @Test
    fun `an unstarted run still counts one failure when no marker exists`() {
        val idle = doc(tasks = listOf(task("t-1", failStreak = 0)))

        val res = ScheduledTaskTransitions.completeUnstartedRun(idle, "t-1", ScheduledTask.RESULT_ERROR, "2026-11-20")

        assertEquals(1, res.task?.failStreak)
        assertEquals(ScheduledTask.RESULT_ERROR, res.task?.lastResult)
        assertNull("a failure never advances the daily gate", res.file.tasks.single().lastRunDate)
    }

    @Test
    fun `an unstarted run cannot credit through another run's marker`() {
        val otherRun = doc(tasks = listOf(task("t-1", failStreak = 0)), running = marker("t-1", "s-new"))

        val res = ScheduledTaskTransitions.completeUnstartedRun(otherRun, "t-1", ScheduledTask.RESULT_ERROR, "2026-11-20")

        assertSame(otherRun, res.file)
        assertNull(res.task)
        assertEquals(0, res.file.tasks.single().failStreak)
        assertEquals("s-new", res.file.running?.sessionId)
    }

    // ─── the marker compensation is scoped the same way ──────────────────────

    @Test
    fun `clearing the marker only touches the settling run`() {
        val mine = doc(tasks = listOf(task("t-1")), running = marker("t-1", "s-current"))
        assertNull(ScheduledTaskTransitions.clearRunningMarker(mine, ScheduledRunRef("t-1", "s-current")).running)

        val newerRun = doc(tasks = listOf(task("t-1")), running = marker("t-1", "s-newer"))
        assertSame(
            "an old run's compensation must not erase a newer run's marker",
            newerRun,
            ScheduledTaskTransitions.clearRunningMarker(newerRun, ScheduledRunRef("t-1", "s-old")),
        )
    }

    // ─── crash recovery keeps its fail-closed semantics ──────────────────────

    /**
     * A marker with no in-process run = the process died mid-run. That settles
     * WITHOUT a session to name, and must keep doing so: the marker itself is
     * the evidence, and the one-beat failure count is the brief §3 contract.
     */
    @Test
    fun `crash recovery still counts one failure and clears the marker`() {
        val crashed = doc(tasks = listOf(task("t-1", failStreak = 1)), running = marker("t-1", "s-dead"))

        val res = ScheduledTaskTransitions.crashFailure(crashed, "t-1")

        assertEquals(2, res.task?.failStreak)
        assertNull(res.file.running)
    }

    @Test
    fun `crash recovery only answers for the marker's own task`() {
        val crashed = doc(tasks = listOf(task("t-1"), task("t-2")), running = marker("t-2", "s-dead"))

        val res = ScheduledTaskTransitions.crashFailure(crashed, "t-1")

        assertSame(crashed, res.file)
        assertNull(res.task)
        assertEquals("s-dead", res.file.running?.sessionId)
    }

    // ─── the runner's lock follows the same identity ─────────────────────────

    @Test
    fun `ownership of a marker needs task and session to match`() {
        val m = marker("t-1", "s-1")
        assertTrue(ScheduledTaskRunOwnership.ownsMarker(m, ScheduledRunRef("t-1", "s-1")))
        assertFalse(
            "a different run of the same task must not match",
            ScheduledTaskRunOwnership.ownsMarker(m, ScheduledRunRef("t-1", "s-2")),
        )
        assertFalse(ScheduledTaskRunOwnership.ownsMarker(m, ScheduledRunRef("t-2", "s-1")))
        assertFalse(ScheduledTaskRunOwnership.ownsMarker(null, ScheduledRunRef("t-1", "s-1")))
        assertFalse(
            "no identity at all is never a match",
            ScheduledTaskRunOwnership.ownsMarker(m, ScheduledRunRef("t-1", null)),
        )
        assertFalse(ScheduledTaskRunOwnership.ownsMarker(m, ScheduledRunRef("t-1", "  ")))
    }

    @Test
    fun `the in-process reservation is released only by the run that holds it`() {
        // A newer run of the same task holds the lock under a different session.
        assertFalse(
            "an old run's late finalize must not free a newer run's lock",
            ScheduledTaskRunOwnership.ownsActiveRun("t-1", "s-new", ScheduledRunRef("t-1", "s-old")),
        )
        assertTrue(ScheduledTaskRunOwnership.ownsActiveRun("t-1", "s-1", ScheduledRunRef("t-1", "s-1")))
        assertFalse(ScheduledTaskRunOwnership.ownsActiveRun(null, null, ScheduledRunRef("t-1", "s-1")))
        assertFalse(ScheduledTaskRunOwnership.ownsActiveRun("t-2", "s-1", ScheduledRunRef("t-1", "s-1")))
        // A reservation placed before the session existed is released only by a
        // settle that also has no session.
        assertTrue(ScheduledTaskRunOwnership.ownsActiveRun("t-1", null, ScheduledRunRef("t-1", null)))
        assertFalse(ScheduledTaskRunOwnership.ownsActiveRun("t-1", null, ScheduledRunRef("t-1", "s-1")))
    }

    // ─── end-to-end: a refused settle leaves the daily gate untouched ────────

    @Test
    fun `a refused stale settle keeps the task due today`() {
        val restored = doc(tasks = listOf(task("same-id")))
        val refused = ScheduledTaskTransitions.completeRun(restored, ScheduledRunRef("same-id", "s-old"), ScheduledTask.RESULT_OK, "2026-11-20")

        val verdicts = ScheduledTaskPolicy.evaluate("2026-11-20", 8 * 60, refused.file.tasks, null)
        assertEquals(
            "the old run must not fake today's run — the task stays due",
            ScheduledTaskPolicy.PassVerdict.Fire("same-id"),
            verdicts.single(),
        )
    }
}
