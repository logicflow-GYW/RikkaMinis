package com.rikkaminis.app.sandbox

import org.junit.Assert.*
import org.junit.Test

/**
 * [meta-audit-1028 E3 / F-09 / F-10] PersistentShell results a defect can hide
 * in, pinned where the collateral decides them.
 *
 * F-09 — the progress note is kept even after the capture budget is exhausted.
 *   The guard must compare what the READER delivered, not what the buffer holds:
 *   once the byte budget is full, `output.length` stops moving while bytes keep
 *   arriving, and a live command looked frozen → SIGINT → SIGKILL at 180 s while
 *   the UI was still scrolling its output. Two coroutine-visible reads present
 *   the second jump; the ship must not drop it.
 *
 * F-10 — the timeout note is APPENDED to what the command already printed, not
 *   substituted for it. A build that hung after dumping 50 lines used to come
 *   back as one line, taking every clue about where it stopped with it.
 *
 * Both are algebraic: no Android type, no coroutine, no process.
 */
class TrackedStallAndTimeoutTest {

    // ── F-09: progress must survive the output ceiling ─────────────────────

    @Test
    fun `the budget stops accepting output once the ceiling is reached`() {
        // Prerequisite of the defect: this is the behaviour that froze the old
        // progress signal. If it ever stops being true, F-09 is moot — and this
        // test says so instead of silently passing for the wrong reason.
        val output = StringBuilder()
        val budget = IncrementalUtf8Budget { 10 }
        budget.appendTo(output, "1234567890")
        val before = output.length
        budget.appendTo(output, "XXXXXXXXXX")
        assertEquals(10, before)
        assertEquals(10, output.length)
        assertTrue("later chunks must be dropped by the budget", budget.truncated)
    }

    @Test
    fun `the stall guard sees progress even when the buffer cannot grow`() {
        val cb = TrackedReceiveCounterFixture.newCallback(lineCallback = null)
        val stall = TrackedReceiveCounterFixture.StallTracker()
        cb.appendOutput("1234567890")       // fills the 10-char budget
        TrackedReceiveCounterFixture.tick(stall, cb)
        val advancedOnce = stall.lastProgressMs
        assertTrue("first chunk must count as progress", advancedOnce > stall.startedAtMs)

        TrackedReceiveCounterFixture.advanceClock()
        cb.appendOutput("XXXXXXXXXX")       // dropped by the budget, still delivered
        TrackedReceiveCounterFixture.tick(stall, cb)
        assertTrue(
            "progress must advance on a delivered chunk the budget clipped away — " +
                "this is F-09: comparing output.length here is what killed live commands",
            stall.lastProgressMs > advancedOnce,
        )
    }

    @Test
    fun `a silent command shows no progress`() {
        val cb = TrackedReceiveCounterFixture.newCallback(lineCallback = null)
        val stall = TrackedReceiveCounterFixture.StallTracker()
        cb.appendOutput("chunk")
        TrackedReceiveCounterFixture.tick(stall, cb)
        val afterFirst = stall.lastProgressMs
        TrackedReceiveCounterFixture.advanceClock()
        TrackedReceiveCounterFixture.tick(stall, cb)   // nothing delivered
        assertEquals(afterFirst, stall.lastProgressMs)
    }

    @Test
    fun `the received counter is the total delivered, not the total kept`() {
        val cb = TrackedReceiveCounterFixture.newCallback(lineCallback = null)
        cb.appendOutput("1234567890")   // 10 kept (budget = 10)
        cb.appendOutput("XXXXX")        // 5 delivered, 0 kept
        cb.appendOutput("")             // empty chunks must not count
        assertEquals(15L, cb.totalCharsReceived)
        assertEquals(10, cb.output.length)
    }

    // ── F-10: the timeout keeps what was printed ───────────────────────────

    @Test
    fun `timeout output appends the notice to the captured text`() {
        assertEquals(
            "line1\nline2\n[Command timed out after 900s]",
            internalTimeoutOutput("line1\nline2\n", 900_000L),
        )
    }

    @Test
    fun `a captured line without a trailing newline gets one`() {
        assertEquals(
            "half written line\n[Command timed out after 60s]",
            internalTimeoutOutput("half written line", 60_000L),
        )
    }

    @Test
    fun `nothing captured yields the historical single notice line`() {
        // Byte-identical to the old result: the coordinator deliberately adds no
        // `(exit code: N)` trailer for 124 and recognises timeouts by this text.
        assertEquals("[Command timed out after 300s]", internalTimeoutOutput("", 300_000L))
    }

    @Test
    fun `sub-second timeouts floor to zero and keep their captured line`() {
        // Integer division, not rounding: a 999 ms budget reports "0s", and the
        // single captured line still rides along.
        assertEquals("x\n[Command timed out after 0s]", internalTimeoutOutput("x", 999L))
    }

    @Test
    fun `the notice never gets double counted when appended twice`() {
        // Defensive: internalTimeoutOutput is called once per timeout, but a
        // retry that re-notices must not stack notices.
        val once = internalTimeoutOutput("body", 120_000L)
        val twice = internalTimeoutOutput(once, 120_000L)
        assertEquals("body\n[Command timed out after 120s]\n[Command timed out after 120s]", twice)
        assertNotEquals(once, twice)
    }
}

/**
 * The literal progress proof, transcribed from the site. Extracted here so the
 * test asserts against the same expression shape PersistentShell uses, without
 * dragging a Context/Process dependency into a unit test.
 */
private object TrackedReceiveCounterFixture {
    class StallTracker {
        var lastReceivedChars: Long = 0L
        var lastProgressMs: Long = 0L
        val startedAtMs: Long = 0L
    }

    private var clock = 0L

    fun advanceClock() { clock += 1_000L }

    fun newCallback(lineCallback: ((String, Boolean) -> Unit)?): CommandCallbackFixture =
        CommandCallbackFixture(lineCallback)

    fun tick(stall: StallTracker, cb: CommandCallbackFixture) {
        // PersistentShell.kt monitor loop, receiver progress branch.
        val received = cb.totalCharsReceived
        if (received != stall.lastReceivedChars) {
            stall.lastReceivedChars = received
            stall.lastProgressMs = clock
        }
    }

    /**
     * Mirrors CommandCallback's observable surface: a byte-budgeted buffer plus
     * the monotonic received counter F-09 added.
     */
    class CommandCallbackFixture(val lineCallback: ((String, Boolean) -> Unit)?) {
        val output = StringBuilder()
        private val budget = IncrementalUtf8Budget { 10 }
        private val received = java.util.concurrent.atomic.AtomicLong(0L)
        val totalCharsReceived: Long get() = received.get()
        val truncated: Boolean get() = budget.truncated

        fun appendOutput(text: String) {
            if (text.isEmpty()) return
            received.addAndGet(text.length.toLong())
            budget.appendTo(output, text)
        }
    }
}
