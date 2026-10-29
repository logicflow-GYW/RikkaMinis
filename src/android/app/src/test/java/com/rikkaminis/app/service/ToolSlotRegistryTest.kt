package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-U F-06] Table-driven contract of the per-session tool registry that backs
 * `SessionActivityTracker`'s display flows.
 *
 * The defect this pins: the tracker held ONE global tool slot, so with two
 * sessions streaming the fast session's `clearToolRunning()` blanked the slow
 * session's mid-execution tool. Every row below is an interleaving that used to
 * end in a wrong display, with the expectation written as a literal (not
 * derived from the implementation).
 */
class ToolSlotRegistryTest {

    private sealed interface Op {
        data class Start(
            val key: String,
            val name: String?,
            val status: String,
            val running: Boolean,
        ) : Op

        data class Finish(val key: String) : Op
        data class Drop(val key: String) : Op
    }

    private data class Row(
        val label: String,
        val ops: List<Op>,
        val activeSessions: Set<String> = emptySet(),
        val expectName: String?,
        val expectRunning: Boolean,
    )

    private fun runRow(row: Row) {
        val reg = ToolSlotRegistry()
        row.ops.forEach { op ->
            when (op) {
                is Op.Start -> reg.start(
                    sessionId = op.key,
                    toolName = op.name,
                    toolTitle = null,
                    status = op.status,
                    isRunning = op.running,
                )
                is Op.Finish -> reg.finish(op.key)
                is Op.Drop -> reg.drop(op.key)
            }
        }
        val shown = reg.representative(row.activeSessions)
        assertEquals(row.label, row.expectName, shown?.toolName)
        assertEquals(row.label, row.expectRunning, shown?.isRunning == true)
    }

    @Test
    fun representativeSelectionTable() {
        val rows = listOf(
            // The defect as reported: two sessions, fast one finishes first.
            // start(fast=A) start(slow=U) finish(U) — U's finish must NOT blank
            // A's running tool (the old single-slot code nulled the name here).
            Row(
                label = "fast finish keeps slow session's tool visible",
                ops = listOf(
                    Op.Start("session-a", "browser_use", "Opening page", true),
                    Op.Start(ToolSlotRegistry.UNATTRIBUTED, "shell_execute", "Running cmd", true),
                    Op.Finish(ToolSlotRegistry.UNATTRIBUTED),
                ),
                activeSessions = setOf("session-a"),
                expectName = "browser_use",
                expectRunning = true,
            ),
            // ...and only when the LAST tool finishes does the display go idle.
            Row(
                label = "last finish drops the entry",
                ops = listOf(
                    Op.Start("session-a", "browser_use", "Opening page", true),
                    Op.Start(ToolSlotRegistry.UNATTRIBUTED, "shell_execute", "Running cmd", true),
                    Op.Finish(ToolSlotRegistry.UNATTRIBUTED),
                    Op.Finish("session-a"),
                ),
                activeSessions = emptySet(),
                expectName = null,
                expectRunning = false,
            ),
            // Plain start→finish: nothing left to render.
            Row(
                label = "single start then finish",
                ops = listOf(
                    Op.Start("solo", "shell_execute", "Running cmd", true),
                    Op.Finish("solo"),
                ),
                activeSessions = emptySet(),
                expectName = null,
                expectRunning = false,
            ),
            // An ACTIVE session's running entry beats a more recent entry that
            // belongs to nobody (rule 1+2 of the selection order).
            Row(
                label = "active session's running tool wins over newer orphan",
                ops = listOf(
                    Op.Start(ToolSlotRegistry.UNATTRIBUTED, "shell_execute", "Running cmd", true),
                    Op.Start("session-b", "browser_use", "Opening page", true),
                ),
                activeSessions = setOf("session-b"),
                expectName = "browser_use",
                expectRunning = true,
            ),
            // A stale orphan entry must not shadow the active session's own
            // entry: rule 1 filters to the active session first, so the orphan
            // (even though it is "running") is invisible while that session is
            // streaming.
            Row(
                label = "stale orphan does not shadow active session entry",
                ops = listOf(
                    Op.Start(ToolSlotRegistry.UNATTRIBUTED, "shell_execute", "Running cmd", true),
                    Op.Start("session-b", null, "Thinking", false),
                ),
                activeSessions = setOf("session-b"),
                expectName = null,
                expectRunning = false,
            ),
            // drop() ignores depth: an ended session owns no running tool even
            // if its finish never arrived.
            Row(
                label = "drop removes despite open depth",
                ops = listOf(
                    Op.Start("gone", "browser_use", "Opening page", true),
                    Op.Drop("gone"),
                ),
                activeSessions = setOf("other"),
                expectName = null,
                expectRunning = false,
            ),
        )
        rows.forEach(::runRow)
    }

    @Test
    fun nonRunningUpdateDoesNotRaiseDepth() {
        val reg = ToolSlotRegistry()
        reg.start(sessionId = "a", toolName = null, toolTitle = null, status = "Idle", isRunning = false)
        // A text-only refresh then one "finish": if the text-only update had
        // raised the depth, this finish would leave a phantom running entry.
        assertNull(reg.finish("a"))
        assertNull(reg.representative(setOf("a")))
    }

    @Test
    fun blankStatusKeepsPreviousText() {
        val reg = ToolSlotRegistry()
        reg.start(sessionId = "a", toolName = "shell_execute", toolTitle = null, status = "Running cmd", isRunning = true)
        val after = reg.start(sessionId = "a", toolName = "shell_execute", toolTitle = null, status = "", isRunning = true)
        assertEquals("Running cmd", after.status)
        assertEquals("shell_execute", after.toolName)
    }

    @Test
    fun unattributedKeyIsBlankAndDistinct() {
        // Session ids are uuids; the shared bucket must never collide with one.
        assertTrue(ToolSlotRegistry.UNATTRIBUTED.isEmpty())
        val reg = ToolSlotRegistry()
        reg.start(sessionId = ToolSlotRegistry.UNATTRIBUTED, toolName = "shell_execute", toolTitle = null, status = "Running cmd", isRunning = true)
        assertNull(reg.slot("session-a"))
    }

    // ── [R5-D F-01①] dynamic-key drift ───────────────────────────────────────

    /**
     * The exact interleaving the report calls REGRESSION, driven through the
     * registry the way the tracker drives it:
     *
     * `setActive(A)` → tool starts while A streams ALONE (`toolSlotKey()` = "A")
     * → `setActive(B)` makes the key UNATTRIBUTED → the tool ends and the
     * FINISH names `""`. Before the fix that finish hit nothing, `entries["A"]`
     * kept `depth = 1`, and the notification span forever. The expectation is
     * a literal: after the finish NOTHING is running.
     */
    @Test
    fun finishWithDriftedKeyStillClearsTheRunningSlot() {
        val reg = ToolSlotRegistry()
        // start: key derived from a single active session
        reg.start(sessionId = "A", toolName = "shell_execute", toolTitle = null, status = "Running: shell_execute", isRunning = true)
        val beforeDrift = reg.representative(setOf("A"))
        assertEquals("A", beforeDrift?.sessionId)
        assertTrue(beforeDrift?.isRunning == true)

        // --- key drifts here: a second session became active, so the tracker
        // would now derive UNATTRIBUTED for both finish and further updates.
        val active = setOf("A", "B")
        assertEquals("shell_execute", reg.representative(active)?.toolName)

        // finish arrives under the drifted key — no entry exists under it.
        assertNull("A missed key must decrement, not vanish", reg.finish(ToolSlotRegistry.UNATTRIBUTED))

        val after = reg.representative(active)
        assertNull("the phantom running entry must be gone: $after", after)
        assertFalse("isToolRunning must be false", after?.isRunning == true)
    }

    /** The mirror direction: start under the shared bucket, finish under a real id. */
    @Test
    fun finishWithRealIdClearsTheSharedBucket() {
        val reg = ToolSlotRegistry()
        // start: two sessions streaming ⇒ unattributed bucket
        reg.start(sessionId = ToolSlotRegistry.UNATTRIBUTED, toolName = "browser_use", toolTitle = null, status = "Opening page", isRunning = true)
        // the other session ended first ⇒ size == 1 ⇒ finish derives "B"
        assertNull(reg.finish("B"))
        assertNull(reg.representative(setOf("B")))
    }

    /**
     * A missed key must not invent a decrement: with NO running entry anywhere
     * the finish is a no-op, and a text-only (depth 0) entry is still retracted
     * like it was before the fallback existed.
     */
    @Test
    fun missedKeyWithoutAnyRunningEntryIsANoOp() {
        val reg = ToolSlotRegistry()
        assertNull(reg.finish("nobody"))
        reg.start(sessionId = "a", toolName = null, toolTitle = null, status = "Thinking", isRunning = false)
        assertNull(reg.finish("nobody"))
        assertNull(reg.slot("nobody"))
        // …and the named text-only entry is retracted by its own finish.
        assertNull(reg.finish("a"))
        assertNull(reg.slot("a"))
    }

    /**
     * Depth is still a counter, not a flag: a session's parallel tool batch
     * (two starts, one finish each) must keep "running" true until the LAST
     * finish — the fallback must not collapse it to a single finish.
     */
    @Test
    fun parallelBatchNeedsEveryFinish() {
        val reg = ToolSlotRegistry()
        reg.start(sessionId = "a", toolName = "shell_execute", toolTitle = null, status = "Running: shell_execute", isRunning = true)
        reg.start(sessionId = "a", toolName = "file_read", toolTitle = null, status = "Running: file_read", isRunning = true)
        val oneLeft = reg.finish("a")
        assertEquals("one tool still in flight", 1, oneLeft?.depth)
        assertTrue(reg.representative(setOf("a"))?.isRunning == true)
        assertNull(reg.finish("a"))
        assertNull(reg.representative(setOf("a")))
    }

    /** Two running entries + a third unknown key: the fallback refuses to guess. */
    @Test
    fun fallbackDoesNotPickBetweenTwoRunningSessions() {
        val reg = ToolSlotRegistry()
        reg.start(sessionId = "a", toolName = "shell_execute", toolTitle = null, status = "Running", isRunning = true)
        reg.start(sessionId = "b", toolName = "browser_use", toolTitle = null, status = "Running", isRunning = true)
        assertNull(reg.finish("ghost"))
        assertEquals(1, reg.slot("a")?.depth)
        assertEquals(1, reg.slot("b")?.depth)
    }

    /**
     * [R5-D F-01③] The registry is shared by several agent-loop IO coroutines
     * plus the notification/overlay readers. Unlocked, the `LinkedHashMap`
     * links get corrupted (`ConcurrentModificationException` from the
     * representative's `filter`, lost `seqCounter` increments). The assertion
     * is structural — balanced work leaves nothing running — and the run is
     * deterministic once the lock exists.
     */
    @Test
    fun concurrentReadWriteDoesNotCorruptTheRegistry() {
        val reg = ToolSlotRegistry()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        fun worker(key: String) = Thread {
            try {
                repeat(400) { i ->
                    reg.start(sessionId = key, toolName = "shell_execute", toolTitle = null, status = "Running $i", isRunning = true)
                    reg.representative(setOf(key, ToolSlotRegistry.UNATTRIBUTED))
                    reg.finish(key)
                }
            } catch (t: Throwable) {
                failures += t
            }
        }

        val readers = Thread {
            try {
                repeat(1200) { reg.representative(setOf("a", "b", ToolSlotRegistry.UNATTRIBUTED)) }
            } catch (t: Throwable) {
                failures += t
            }
        }

        val threads = listOf(worker("a"), worker("b"), worker(ToolSlotRegistry.UNATTRIBUTED), readers)
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("no exception may escape the registry: $failures", 0, failures.size)
        assertNull("balanced starts/finishes must leave nothing running", reg.representative(emptySet()))
    }

    @Test
    fun clearResetsEverything() {
        val reg = ToolSlotRegistry()
        reg.start(sessionId = "a", toolName = "shell_execute", toolTitle = null, status = "Running cmd", isRunning = true)
        reg.start(sessionId = "b", toolName = "browser_use", toolTitle = null, status = "Opening page", isRunning = true)
        reg.clear()
        assertNull(reg.representative(setOf("a", "b")))
        assertNull(reg.slot("a"))
    }
}
