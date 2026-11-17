package com.rikkaminis.app.service

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WAVE-1118 R4 F-01] `_suspendedSessions` must not accumulate one entry per
 * queued RUN — it is a per-SESSION display list.
 *
 * The queue is keyed by `runId` (a session may have several concurrent runs),
 * while `suspendedSessions` is keyed by `sessionId`. Queueing the same session
 * twice appended the id twice, and every removal site used `List - sessionId`,
 * which drops only the FIRST match. The second copy therefore outlived every
 * waiter: `isSuspended(sessionId)` reported "queued" forever with nothing
 * queued or running, and the UI kept the session on "waiting".
 *
 * Every expectation here is a literal list/count, never derived from
 * production constants, so a re-introduced duplicate append turns these red.
 */
class SuspendedSessionsResidueTest {

    @Before
    fun resetManager() {
        SessionConcurrencyManager.resetForTesting()
        // Pin the memory gate to NORMAL: the CI runner's real RSS must not
        // inject reclaim/delay into these arms.
        MemoryPressureGate.metricsReader = { MemoryPressureGate.Metrics(0L, 147L) }
        MemoryPressureGate.reclaimHook = {}
        MemoryPressureGate.pressureListener = { _, _, _, _ -> }
    }

    /** Two runs of one session queue → one display entry, and none left after both promote. */
    @Test
    fun twoQueuedRunsOfOneSessionLeaveNoSuspendedResidueAfterPromotion() = runTest {
        val a = async { SessionConcurrencyManager.acquireSlot("a") }
        val b = async { SessionConcurrencyManager.acquireSlot("b") }
        runCurrent()
        a.await(); b.await()
        assertEquals(2, SessionConcurrencyManager.occupancy().active)

        val s1run1 = async { SessionConcurrencyManager.acquireSlot("s1") }
        val s1run2 = async { SessionConcurrencyManager.acquireSlot("s1") }
        runCurrent()
        assertEquals(
            "one session queued twice is still ONE suspended line",
            listOf("s1"),
            SessionConcurrencyManager.suspendedSessions.value,
        )

        SessionConcurrencyManager.releaseSlot("a")
        runCurrent()
        assertTrue("first run of s1 must be promoted", s1run1.isCompleted)
        assertTrue("s1 still has a waiter, so it stays listed", SessionConcurrencyManager.isSuspended("s1"))

        SessionConcurrencyManager.releaseSlot("b")
        runCurrent()
        assertTrue("second run of s1 must be promoted", s1run2.isCompleted)
        assertFalse(
            "with nothing queued or running, s1 must not report as suspended",
            SessionConcurrencyManager.isSuspended("s1"),
        )
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)

        SessionConcurrencyManager.releaseSlot("s1")
        SessionConcurrencyManager.releaseSlot("s1")
        assertEquals(0, SessionConcurrencyManager.occupancy().active)
    }

    /** Same session queued twice, both cancelled → the display list must empty out. */
    @Test
    fun cancellingEveryQueuedRunOfOneSessionClearsTheSuspendedLine() = runTest {
        val a = async { SessionConcurrencyManager.acquireSlot("a") }
        val b = async { SessionConcurrencyManager.acquireSlot("b") }
        runCurrent()
        a.await(); b.await()

        val s1run1 = launch { SessionConcurrencyManager.acquireSlot("s1") }
        val s1run2 = launch { SessionConcurrencyManager.acquireSlot("s1") }
        runCurrent()
        assertEquals(listOf("s1"), SessionConcurrencyManager.suspendedSessions.value)

        s1run1.cancel()
        runCurrent()
        assertTrue(
            "the other run is still queued, so the line stays",
            SessionConcurrencyManager.isSuspended("s1"),
        )

        s1run2.cancel()
        runCurrent()
        assertFalse(
            "no waiter left ⇒ s1 must not stay on the suspended list",
            SessionConcurrencyManager.isSuspended("s1"),
        )
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)
        assertEquals(0, SessionConcurrencyManager.occupancy().waiting)

        SessionConcurrencyManager.releaseSlot("a")
        SessionConcurrencyManager.releaseSlot("b")
    }

    /** The suspended list stays a set-semantics view across mixed promote/cancel. */
    @Test
    fun mixedPromoteAndCancelOfTwoRunsOfOneSessionConvergesToEmpty() = runTest {
        val a = async { SessionConcurrencyManager.acquireSlot("a") }
        val b = async { SessionConcurrencyManager.acquireSlot("b") }
        runCurrent()
        a.await(); b.await()

        val s1run1 = async { SessionConcurrencyManager.acquireSlot("s1") }
        val s1run2 = launch { SessionConcurrencyManager.acquireSlot("s1") }
        runCurrent()

        SessionConcurrencyManager.releaseSlot("a")
        runCurrent()
        s1run1.await()
        assertTrue(
            "the remaining queued run keeps s1 listed",
            SessionConcurrencyManager.isSuspended("s1"),
        )

        s1run2.cancel()
        runCurrent()
        assertFalse(
            "promoted run + cancelled run ⇒ nothing queued ⇒ not suspended",
            SessionConcurrencyManager.isSuspended("s1"),
        )
        assertEquals(emptyList<String>(), SessionConcurrencyManager.suspendedSessions.value)

        SessionConcurrencyManager.releaseSlot("b")
        SessionConcurrencyManager.releaseSlot("s1")
    }
}
