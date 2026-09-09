package com.rikkaminis.app.sandbox

import com.rikkaminis.app.agent.runtime.CommandFailureKind
import com.rikkaminis.app.agent.runtime.RetryOutcome
import com.rikkaminis.app.agent.runtime.RetryPolicy
import com.rikkaminis.app.agent.runtime.RetrySafety
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T3-retry-side-effects] JVM tests for the side-effect-aware retry policy.
 *
 * Covers the blueprint T3 test matrix:
 * - read-only + shell death → allowed retry;
 * - unknown + shell death → OutcomeUnknown, NOT a transparent re-run;
 * - non-idempotent + timeout → no re-run;
 * - idempotent + verification available → retryable;
 * - attempt budget exhausted → stop;
 * - output truncated but shell alive → never misread as "command not run";
 * - result succeeded but marker lost → status check before re-run;
 * - side effects happened but result lost → OutcomeUnknown.
 */
class RetryPolicyTest {

    // ── read-only + shell death: allowed one retry ────────────────────

    @Test
    fun `read only plus shell died allows retry on first attempt`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.READ_ONLY,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 1,
        )
        assertEquals(RetryOutcome.SafeToRetry, outcome)
    }

    @Test
    fun `read only plus timeout allows retry on first attempt`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.READ_ONLY,
            failure = CommandFailureKind.TIMEOUT,
            attempt = 1,
        )
        assertEquals(RetryOutcome.SafeToRetry, outcome)
    }

    // ── unknown + shell death: OutcomeUnknown, no transparent re-run ──

    @Test
    fun `unknown plus shell died returns OutcomeUnknown not re-run`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.UNKNOWN,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    @Test
    fun `unknown plus timeout returns OutcomeUnknown not re-run`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.UNKNOWN,
            failure = CommandFailureKind.TIMEOUT,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── non-idempotent + timeout: no re-run ───────────────────────────

    @Test
    fun `non idempotent plus timeout never re-runs`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.NON_IDEMPOTENT_WRITE,
            failure = CommandFailureKind.TIMEOUT,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    @Test
    fun `non idempotent plus shell died never re-runs`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.NON_IDEMPOTENT_WRITE,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── idempotent + verification available: retryable ────────────────

    @Test
    fun `idempotent with verification plus shell died allows retry`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.IDEMPOTENT_WRITE,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 1,
            hasVerification = true,
        )
        assertEquals(RetryOutcome.SafeToRetry, outcome)
    }

    @Test
    fun `idempotent without verification plus shell died requires verify first`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.IDEMPOTENT_WRITE,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 1,
            hasVerification = false,
        )
        assertEquals(RetryOutcome.MustVerifyFirst, outcome)
    }

    // ── attempt budget exhausted: stop ────────────────────────────────

    @Test
    fun `read only stops when attempt budget exhausted`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.READ_ONLY,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 2, // maxRetries default = 2 → exhausted
        )
        assertEquals(RetryOutcome.DoNotRetry, outcome)
    }

    @Test
    fun `idempotent with verification stops as OutcomeUnknown when budget exhausted`() {
        // Budget exhausted: must NOT pretend success — result is unknown.
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.IDEMPOTENT_WRITE,
            failure = CommandFailureKind.SHELL_DIED,
            attempt = 2,
            hasVerification = true,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── output truncated but shell alive: not "command not run" ───────

    @Test
    fun `unknown plus output truncated returns OutcomeUnknown not retry`() {
        // Truncation does NOT mean the command never ran — side effects may
        // have happened. Must not be transparently re-run.
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.UNKNOWN,
            failure = CommandFailureKind.OUTPUT_TRUNCATED,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    @Test
    fun `non idempotent plus output truncated returns OutcomeUnknown`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.NON_IDEMPOTENT_WRITE,
            failure = CommandFailureKind.OUTPUT_TRUNCATED,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── result succeeded but marker lost: status check first ──────────

    @Test
    fun `idempotent with verification plus result lost requires verify not re-run`() {
        // Status check takes priority over re-running.
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.IDEMPOTENT_WRITE,
            failure = CommandFailureKind.RESULT_LOST,
            attempt = 1,
            hasVerification = true,
        )
        assertEquals(RetryOutcome.MustVerifyFirst, outcome)
    }

    @Test
    fun `idempotent without verification plus result lost returns OutcomeUnknown`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.IDEMPOTENT_WRITE,
            failure = CommandFailureKind.RESULT_LOST,
            attempt = 1,
            hasVerification = false,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── side effects happened but result lost: OutcomeUnknown ─────────

    @Test
    fun `unknown plus result lost returns OutcomeUnknown`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.UNKNOWN,
            failure = CommandFailureKind.RESULT_LOST,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    @Test
    fun `non idempotent plus result lost returns OutcomeUnknown`() {
        val outcome = RetryPolicy.decideRetry(
            safety = RetrySafety.NON_IDEMPOTENT_WRITE,
            failure = CommandFailureKind.RESULT_LOST,
            attempt = 1,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── non-zero exit with alive shell: completed failure, never retry ─

    @Test
    fun `non zero exit never retries regardless of safety`() {
        for (safety in RetrySafety.entries) {
            val outcome = RetryPolicy.decideRetry(
                safety = safety,
                failure = CommandFailureKind.NON_ZERO_EXIT,
                attempt = 1,
            )
            assertEquals("safety=$safety should DoNotRetry on NON_ZERO_EXIT", RetryOutcome.DoNotRetry, outcome)
        }
    }

    // ── internalClassifyShellFailure: raw-signal mapping ──────────────

    @Test
    fun `classify exit -1 as shell died`() {
        assertEquals(CommandFailureKind.SHELL_DIED, internalClassifyShellFailure(-1, false, false))
    }

    @Test
    fun `classify exit 124 as timeout`() {
        assertEquals(CommandFailureKind.TIMEOUT, internalClassifyShellFailure(124, true, false))
    }

    @Test
    fun `classify truncated with alive shell as output truncated`() {
        // Critical: exit 0 + truncated + alive must NOT be treated as success
        // for retry purposes — the command ran, output was cut.
        assertEquals(CommandFailureKind.OUTPUT_TRUNCATED, internalClassifyShellFailure(0, true, true))
    }

    @Test
    fun `classify dead shell with zero exit as result lost`() {
        // Marker lost: exit 0 but shell died before returning the result.
        assertEquals(CommandFailureKind.RESULT_LOST, internalClassifyShellFailure(0, false, false))
    }

    @Test
    fun `classify non zero exit with alive shell as non zero exit`() {
        assertEquals(CommandFailureKind.NON_ZERO_EXIT, internalClassifyShellFailure(1, true, false))
    }

    @Test
    fun `classify zero exit alive shell as success null`() {
        assertEquals(null, internalClassifyShellFailure(0, true, false))
    }

    // ── internalDecideShellRetry: end-to-end wiring ───────────────────

    @Test
    fun `decide shell retry read only shell death is safe to retry`() {
        val outcome = internalDecideShellRetry(
            exitCode = -1, shellAlive = false, truncated = false,
            attempt = 1, safety = RetrySafety.READ_ONLY,
        )
        assertEquals(RetryOutcome.SafeToRetry, outcome)
    }

    @Test
    fun `decide shell retry unknown shell death is outcome unknown`() {
        val outcome = internalDecideShellRetry(
            exitCode = -1, shellAlive = false, truncated = false,
            attempt = 1, safety = RetrySafety.UNKNOWN,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    @Test
    fun `decide shell retry success is do not retry`() {
        val outcome = internalDecideShellRetry(
            exitCode = 0, shellAlive = true, truncated = false,
            attempt = 1, safety = RetrySafety.READ_ONLY,
        )
        assertEquals(RetryOutcome.DoNotRetry, outcome)
    }

    @Test
    fun `decide shell retry truncated unknown is outcome unknown not safe retry`() {
        // Truncated output with a live shell is NOT a retryable condition for
        // UNKNOWN commands — side effects may already have happened.
        val outcome = internalDecideShellRetry(
            exitCode = 0, shellAlive = true, truncated = true,
            attempt = 1, safety = RetrySafety.UNKNOWN,
        )
        assertEquals(RetryOutcome.OutcomeUnknown, outcome)
    }

    // ── legacy internalShouldRetryCommand still works (contract guard) ─

    @Test
    fun `legacy retry decision still retries shell death`() {
        assertEquals(true, internalShouldRetryCommand(-1, false, 1))
    }

    @Test
    fun `legacy retry decision still refuses retry on live shell error`() {
        assertEquals(false, internalShouldRetryCommand(1, true, 1))
    }
}
