package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the shell auto-retry decision logic extracted from
 * [ExecutionCoordinator].
 *
 * [ExecutionCoordinator] itself depends on Android (Context, Debug, etc.),
 * so these tests focus on the top-level [internalShouldRetryCommand] function.
 *
 * The retry logic answers: "should the command be re-run on a rebuilt shell?"
 * Retry is triggered when the shell process died mid-command (exitCode == -1
 * from PersistentShell.readLoop, or the process is no longer alive), AND we
 * have not exhausted the retry budget.
 *
 * [audit-0916] exitCode == 124 (timeout) is deliberately NOT retried — see
 * [internalShouldRetryCommand]. A timeout already consumed the command's whole
 * window; the shell is still reclaimed (ExhaustedTimeoutReclaimTest) but the
 * command is not re-run.
 */
class ExecutionCoordinatorRetryTest {

    // ── should NOT retry: normal successful commands ──────────────────

    @Test
    fun `should not retry successful exit code 0 with alive shell`() {
        assertRetry(exitCode = 0, alive = true, attempt = 1, expected = false)
    }

    @Test
    fun `should not retry successful exit code 0 with alive shell on attempt 2`() {
        assertRetry(exitCode = 0, alive = true, attempt = 2, expected = false)
    }

    // ── should NOT retry: non-zero exit but shell alive ───────────────

    @Test
    fun `should not retry script error exit code 1 with alive shell`() {
        assertRetry(exitCode = 1, alive = true, attempt = 1, expected = false)
    }

    @Test
    fun `should not retry command not found exit code 127 with alive shell`() {
        assertRetry(exitCode = 127, alive = true, attempt = 1, expected = false)
    }

    // ── should retry: shell died mid-command ──────────────────────────

    @Test
    fun `should retry exit code -1 on first attempt`() {
        // -1 = PersistentShell.readLoop returned after process exited
        assertRetry(exitCode = -1, alive = false, attempt = 1, expected = true)
    }

    @Test
    fun `should retry exit code -1 on first attempt even if shell appears alive`() {
        // Edge case: exitCode -1 but isAlive still true (race condition)
        assertRetry(exitCode = -1, alive = true, attempt = 1, expected = true)
    }

    @Test
    fun `should not retry exit code -1 when retries exhausted`() {
        assertRetry(exitCode = -1, alive = false, attempt = 2, expected = false)
    }

    @Test
    fun `should not retry exit code -1 on attempt 3`() {
        assertRetry(exitCode = -1, alive = false, attempt = 3, expected = false)
    }

    // ── should NOT retry: timeout (124) ───────────────────────────────
    //
    // [audit-0916] These three cases previously asserted the opposite. A
    // timeout means the command burned its entire window, so re-running it
    // repeats the same expensive work (and, for a non-idempotent command, its
    // side effects) while the agent is told nothing. Reclaiming the shell is
    // still required and is covered unconditionally by
    // internalShouldReclaimOnExhaustedTimeout (see ExhaustedTimeoutReclaimTest).

    @Test
    fun `should not retry timeout exit code 124 on first attempt`() {
        assertRetry(exitCode = 124, alive = true, attempt = 1, expected = false)
    }

    @Test
    fun `should not retry timeout exit code 124 even with a dead shell`() {
        // The timeout guard is checked BEFORE the shell-alive check, so a
        // timeout is never re-run regardless of what the shell reports.
        assertRetry(exitCode = 124, alive = false, attempt = 1, expected = false)
    }

    @Test
    fun `should not retry timeout exit code 124 when retries exhausted`() {
        assertRetry(exitCode = 124, alive = true, attempt = 2, expected = false)
    }

    // ── should retry: shell not alive ─────────────────────────────────

    @Test
    fun `should retry when shell is dead with exit code 0`() {
        // Shell died between command completion and result processing
        assertRetry(exitCode = 0, alive = false, attempt = 1, expected = true)
    }

    @Test
    fun `should not retry when shell is dead on attempt 2`() {
        assertRetry(exitCode = 0, alive = false, attempt = 2, expected = false)
    }

    // ── boundary: attempt counting ────────────────────────────────────

    @Test
    fun `should retry on attempt 1 for any shell death signal`() {
        assertRetry(exitCode = -1, alive = false, attempt = 1, expected = true)
    }

    @Test
    fun `should not retry on attempt 2 for any shell death signal`() {
        assertRetry(exitCode = -1, alive = false, attempt = 2, expected = false)
    }

    @Test
    fun `attempt 0 with shell death still triggers retry per pure semantics`() {
        // attempt starts at 1 in the production loop, so 0 never occurs in
        // practice — but the pure function's contract is "retry while
        // attempt < maxRetries", and 0 < 2, so it must return true.
        assertRetry(exitCode = -1, alive = false, attempt = 0, expected = true)
    }

    @Test
    fun `attempt 2 with alive shell never retries`() {
        // Even at the retry boundary, a live shell with a normal exit is a
        // success and must not be re-run.
        assertRetry(exitCode = 0, alive = true, attempt = 2, expected = false)
        assertRetry(exitCode = 1, alive = true, attempt = 2, expected = false)
    }

    @Test
    fun `exit code 130 with alive shell never retries`() {
        // 130 = SIGINT (Ctrl+C) — user abort, not an infra failure.
        assertRetry(exitCode = 130, alive = true, attempt = 1, expected = false)
    }

    @Test
    fun `exit code 130 with dead shell retries on first attempt`() {
        // Shell died (separate from the exit code) → infra failure → retry.
        assertRetry(exitCode = 130, alive = false, attempt = 1, expected = true)
    }

    // ── custom maxRetries ─────────────────────────────────────────────

    @Test
    fun `should retry with custom maxRetries of 3`() {
        assertRetry(exitCode = -1, alive = false, attempt = 1, maxRetries = 3, expected = true)
        assertRetry(exitCode = -1, alive = false, attempt = 2, maxRetries = 3, expected = true)
        assertRetry(exitCode = -1, alive = false, attempt = 3, maxRetries = 3, expected = false)
    }

    @Test
    fun `should not retry with maxRetries of 0`() {
        assertRetry(exitCode = -1, alive = false, attempt = 1, maxRetries = 0, expected = false)
    }

    // ── [fix/memory-hardening-stall] stall-killed commands ────────────

    @Test
    fun `stall-killed command is never retried even though the shell died`() {
        // The stall guard hard-kills the shell at the process level, so this
        // looks exactly like an infra death (-1/124 semantics) — but retrying
        // would re-run the command that just hung for its whole window.
        assertRetry(exitCode = STALL_EXIT_CODE, alive = false, attempt = 1, expected = false)
        assertRetry(exitCode = STALL_EXIT_CODE, alive = false, attempt = 2, expected = false)
        assertRetry(exitCode = STALL_EXIT_CODE, alive = true, attempt = 1, expected = false)
        assertRetry(exitCode = STALL_EXIT_CODE, alive = true, attempt = 1, maxRetries = 5, expected = false)
    }

    // ── helper ────────────────────────────────────────────────────────

    private fun assertRetry(
        exitCode: Int,
        alive: Boolean,
        attempt: Int,
        maxRetries: Int = 2,
        expected: Boolean,
    ) {
        val actual = internalShouldRetryCommand(exitCode, alive, attempt, maxRetries)
        val msg = "shouldRetryCommand(exitCode=$exitCode, alive=$alive, attempt=$attempt, maxRetries=$maxRetries)" +
            " → expected=$expected, actual=$actual"
        assertEquals(msg, expected, actual)
    }
}