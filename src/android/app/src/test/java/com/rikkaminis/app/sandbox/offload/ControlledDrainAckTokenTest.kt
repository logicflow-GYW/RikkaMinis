package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [S4-ack-token-leak] Structural guard for the controlled-drain ACK token.
 *
 * The bug this locks out: `scheduleControlledDrain`'s stale branch
 * (`requestGeneration` changed during the grace window) returned BEFORE the
 * `releaseAckToken` call, so that run's token stayed in `pendingAckTokens`
 * forever. `ModelExecutionLifecycle.isQuiescent` requires `unackedResponses ==
 * 0`, so once one token leaked the worker could never be quiescent again and
 * never self-reaped. Measured on device 2026-09-21: pid 20731 alive 3h54m
 * across 1492 requests with `pendingAck` pinned at 9-11, while 310 sibling pids
 * each died after a single request; the same window logged 14x
 * `controlled drain stale` (11 of them in 20731) and 4x
 * `controlled drain aborted: active/queued/pending work present`.
 *
 * The behavioural proof lives in the JVM harness
 * (`/var/minis/shared/s4-0921/jvm-s4`, which slices these two functions out of
 * this very file and asserts `pendingAckTokens.isEmpty()` after each of the five
 * exit paths, with a pre-fix negative control that leaks exactly one token).
 * This test is the in-repo tripwire: the harness is not part of the build, so
 * without it nothing here would notice the release point moving back below the
 * generation check.
 *
 * Shape follows the existing source-scanning guards in this package
 * (see [ProviderExecutionGatewayTest]); it is skipped when the tree is not on
 * disk rather than failing the build for the wrong reason.
 */
class ControlledDrainAckTokenTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"

    private fun drainBody(): String? {
        val file = File(sourcePath)
        if (!file.exists()) return null
        val text = file.readText()
        val start = text.indexOf("private fun scheduleControlledDrain(")
        if (start < 0) return null
        val end = text.indexOf("private class ModelExecutionCancelledException", start)
        return if (end > start) text.substring(start, end) else text.substring(start)
    }

    private fun lineOf(body: String, needle: String): Int {
        val idx = body.indexOf(needle)
        assertTrue("expected to find `$needle` in scheduleControlledDrain", idx >= 0)
        return body.substring(0, idx).count { it == '\n' } + 1
    }

    @Test
    fun `drain releases the ack token before its first early return`() {
        val body = drainBody() ?: return
        val release = lineOf(body, "runId?.let { releaseAckToken(it) }")
        val generationCheck = lineOf(body, "if (requestGeneration.get() != genAtSchedule)")
        val firstEarlyReturn = lineOf(body, "return@synchronized")
        assertTrue(
            "the ack token must be released BEFORE the generation check " +
                "(release at line $release, check at $generationCheck) — " +
                "releasing after it leaks a token on every stale drain",
            release < generationCheck,
        )
        assertTrue(
            "the ack token must be released BEFORE the first early return " +
                "(release at line $release, first return at $firstEarlyReturn)",
            release < firstEarlyReturn,
        )
    }

    @Test
    fun `drain has a finally that releases the ack token`() {
        val body = drainBody() ?: return
        assertTrue(
            "scheduleControlledDrain must keep a finally that releases the token: " +
                "the grace loop can throw before reaching the locked release",
            body.contains("} finally {") && body.contains("runId?.let { releaseAckToken(it) }"),
        )
        // Exactly one release inside the lock + one in the finally. A third
        // would mean someone re-added the pre-fix call site.
        assertEquals(
            "expected exactly two releaseAckToken call sites in scheduleControlledDrain",
            2,
            Regex("runId\\?\\.let \\{ releaseAckToken\\(it\\) \\}").findAll(body).count(),
        )
    }

    @Test
    fun `drain still refuses to reap when terminal is absent and no result exists`() {
        val body = drainBody() ?: return
        assertTrue(
            "the terminal-absent / no-result guard must stay: releasing the token " +
                "does not license reaping a run whose data was never committed",
            body.contains("controlled drain aborted: terminal absent and no result"),
        )
    }
}
