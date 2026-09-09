package com.rikkaminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit-RC7] JVM tests for the exhausted-timeout reclaim decision.
 *
 * Invariant: a command that timed out (exitCode == 124) on its FINAL retry
 * must still trigger shell reclamation — `withTimeoutOrNull` only cancels
 * the coroutine, the command keeps running inside the PTY, and its late
 * output (with the stale __MINIS_DONE_<marker>__ line) would otherwise be
 * scanned into the next command's output on the same shell.
 */
class ExhaustedTimeoutReclaimTest {

    @Test
    fun `timeout on final attempt must reclaim shell`() {
        assertTrue(internalShouldReclaimOnExhaustedTimeout(exitCode = 124))
    }

    @Test
    fun `shell death reported as -1 needs no timeout reclaim`() {
        // readLoop already observed the death; the dead-shell path handles it
        assertFalse(internalShouldReclaimOnExhaustedTimeout(exitCode = -1))
    }

    @Test
    fun `normal exits never trigger reclaim`() {
        assertFalse(internalShouldReclaimOnExhaustedTimeout(exitCode = 0))
        assertFalse(internalShouldReclaimOnExhaustedTimeout(exitCode = 1))
        assertFalse(internalShouldReclaimOnExhaustedTimeout(exitCode = 127))
    }

    @Test
    fun `reclaim decision is independent of retry budget`() {
        // Timeout must reclaim even when retries remain — the caller invokes
        // this decision ONLY on the no-retry path, so independence from the
        // attempt counter is what makes the invariant unconditional.
        // attempt=1 is the FIRST attempt: retry IS allowed (1 < maxRetries=2),
        // and the reclaim decision STILL fires unconditionally on timeout.
        assertTrue(internalShouldRetryCommand(124, shellAlive = true, attempt = 1))
        assertTrue(internalShouldReclaimOnExhaustedTimeout(124))
        assertFalse(internalShouldRetryCommand(124, shellAlive = true, attempt = 2))
        assertTrue(internalShouldReclaimOnExhaustedTimeout(124))
    }
}
