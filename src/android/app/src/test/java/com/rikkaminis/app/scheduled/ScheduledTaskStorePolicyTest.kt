package com.rikkaminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R3-E F-04 / F-07] JVM tests for the two write-side decisions the store
 * makes — table-driven over the pure [ScheduledTaskStorePolicy] core, plus the
 * pure-function replay of the settle-vs-check race the F-04A mutex fix closes.
 *
 * The store itself is Android-bound (Context / AtomicFileWrite / AppLogger);
 * the convention for exactly this split is ScheduledTasksStoreRevisionTest's
 * policy core. Every expectation is a literal, never derived from the code
 * under test.
 */
class ScheduledTaskStorePolicyTest {

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun task(
        id: String,
        enabled: Boolean = true,
        lastRunDate: String? = null,
    ) = ScheduledTask(
        id = id,
        title = "title-$id",
        prompt = "prompt-$id",
        windowStart = "07:00",
        windowEnd = "23:00",
        enabled = enabled,
        lastRunDate = lastRunDate,
    )

    private fun docWith(
        globalEnabled: Boolean = true,
        running: ScheduledRunMarker? = null,
        tasks: List<ScheduledTask>,
    ) = ScheduledTasksFile(globalEnabled = globalEnabled, running = running, tasks = tasks)

    // ─── visibleAfterWrite (F-07: a write that did not land is not the truth) ─

    @Test
    fun `a landed write hands back the new document`() {
        val current = docWith(tasks = listOf(task("t-1")))
        val next = docWith(tasks = listOf(task("t-1"), task("t-2")))
        assertSame(next, ScheduledTaskStorePolicy.visibleAfterWrite(current, next, writeSucceeded = true))
    }

    @Test
    fun `a write that did not land hands back the pre-write document`() {
        val current = docWith(tasks = listOf(task("t-1")))
        val next = docWith(tasks = listOf(task("t-1"), task("t-2")))
        assertSame(current, ScheduledTaskStorePolicy.visibleAfterWrite(current, next, writeSucceeded = false))
    }

    // ─── markRunningDecision (F-04B: no marker for a task that is gone) ──────

    @Test
    fun `a live enabled task under a global switch gets its marker`() {
        val file = docWith(tasks = listOf(task("t-1")))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertEquals("t-1", next.running?.taskId)
        assertEquals("s-1", next.running?.sessionId)
        assertEquals(1234L, next.running?.startedAt)
        // The tasks themselves are untouched.
        assertEquals(1, next.tasks.size)
    }

    @Test
    fun `a task deleted mid-fire is refused and the document is untouched`() {
        val file = docWith(tasks = listOf(task("t-other")))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertSame("refusal must return the SAME instance so mutate skips the write", file, next)
        assertEquals(null, next.running)
    }

    @Test
    fun `a disabled task is refused`() {
        val file = docWith(tasks = listOf(task("t-1", enabled = false)))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertSame(file, next)
        assertEquals(null, next.running)
    }

    @Test
    fun `a fused task (enabled false) is refused too`() {
        val file = docWith(tasks = listOf(task("t-1", enabled = false).copy(suspendedByFuse = true)))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertSame(file, next)
    }

    @Test
    fun `a document with the master switch off is refused`() {
        val file = docWith(globalEnabled = false, tasks = listOf(task("t-1")))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertSame(file, next)
        assertEquals(null, next.running)
    }

    @Test
    fun `an existing marker for another task is replaced by admission`() {
        val file = docWith(running = ScheduledRunMarker("t-old", "s-old", 1L), tasks = listOf(task("t-1")))
        val next = ScheduledTaskStorePolicy.markRunningDecision(
            file = file, taskId = "t-1", sessionId = "s-1", startedAt = 1234L,
        )
        assertEquals("t-1", next.running?.taskId)
    }

    @Test
    fun `markerLanded only accepts a marker carrying this task id`() {
        assertTrue(
            ScheduledTaskStorePolicy.markerLanded(
                docWith(running = ScheduledRunMarker("t-1", "s-1", 1L), tasks = listOf(task("t-1"))),
                "t-1",
            ),
        )
        // Nothing written / a different task's marker / a stale marker all read
        // as "not landed" — the caller must abandon the dispatch in each case.
        assertFalse(ScheduledTaskStorePolicy.markerLanded(docWith(tasks = listOf(task("t-1"))), "t-1"))
        assertFalse(
            ScheduledTaskStorePolicy.markerLanded(
                docWith(running = ScheduledRunMarker("t-2", "s-2", 1L), tasks = listOf(task("t-1"), task("t-2"))),
                "t-1",
            ),
        )
        assertFalse(ScheduledTaskStorePolicy.markerLanded(docWith(tasks = emptyList()), "t-1"))
    }

    // ─── F-04A: the settle-vs-check race, replayed at the pure-function layer ─

    /**
     * The defect mechanism, pinned as a literal: `evaluate` pairing a STALE
     * task list (the day's run not yet credited) with a FRESH null
     * runningTaskId returns Fire — ALREADY_RAN sees yesterday's lastRunDate and
     * OVERLAP sees no run. This is the exact interleaving the old lock-free
     * `finalizeRun` could produce between `checkAndFireToday`'s `load` and its
     * `evaluate`. The mutex fix makes this PAIRING unreachable (check and
     * settle now exclude each other); the test pins WHY the fix is load-bearing.
     */
    @Test
    fun `the stale-tasks-plus-null-lock pairing is what fires twice`() {
        val stale = listOf(task("t-1", lastRunDate = "2026-10-28"))
        val verdicts = ScheduledTaskPolicy.evaluate(
            nowDate = "2026-10-29",
            nowMinuteOfDay = 8 * 60 + 5,
            tasks = stale,
            runningTaskId = null,
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Fire("t-1"),
            verdicts.single(),
        )
    }

    /**
     * Safe sequence 1 — check wins the lock first: `evaluate` still sees the
     * in-flight run's lock (OVERLAP), so no second fire even with the stale
     * list.
     */
    @Test
    fun `check-before-settle sees the in-flight lock and overlaps`() {
        val stale = listOf(task("t-1", lastRunDate = "2026-10-28"))
        val verdicts = ScheduledTaskPolicy.evaluate(
            nowDate = "2026-10-29",
            nowMinuteOfDay = 8 * 60 + 5,
            tasks = stale,
            runningTaskId = "t-1",
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.OVERLAP),
            verdicts.single(),
        )
    }

    /**
     * Safe sequence 2 — settle wins the lock first: the persisted document
     * carries today's lastRunDate, so the next check's FRESH load reads
     * ALREADY_RAN. (The check's snapshot is taken under the same mutex that
     * serializes the settlement — that is the fix.)
     */
    @Test
    fun `settle-before-check credits the day and the fresh load reads already-ran`() {
        val preSettle = docWith(tasks = listOf(task("t-1", lastRunDate = "2026-10-28")))
        val settled = ScheduledTaskTransitions.completeRun(
            preSettle, "t-1", ScheduledTask.RESULT_OK, today = "2026-10-29",
        )
        assertEquals("2026-10-29", settled.file.tasks.single().lastRunDate)
        val verdicts = ScheduledTaskPolicy.evaluate(
            nowDate = "2026-10-29",
            nowMinuteOfDay = 8 * 60 + 5,
            tasks = settled.file.tasks,
            runningTaskId = null,
        )
        assertEquals(
            ScheduledTaskPolicy.PassVerdict.Skip("t-1", ScheduledTaskPolicy.SkipReason.ALREADY_RAN),
            verdicts.single(),
        )
    }
}
