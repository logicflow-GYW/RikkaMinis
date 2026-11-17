package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D3-F03] The non-streaming dispatch path when the CALLER is
 * cancelled mid-poll.
 *
 * The defect: `dispatchOnce` wrapped only the poll loop in
 * `withTimeoutOrNull`; the `writeCancel` / settle / delete block lived after it
 * with no `try`/`finally`. Caller-side cancellation (user stop, session
 * teardown, an outer `withTimeout`) unwinds through the poll's `delay`, so that
 * whole block was skipped: the worker kept running — and billing — a request
 * nobody wanted, and the run dir was left with neither `terminal.json` nor
 * `cancel`, the two markers `ModelExecutionOrphanReaper` needs to classify it.
 *
 * The decision ("did the poll finish, and is there anyone left to read a
 * cancel?") is a pure predicate so the truth table is pinned here; the wiring
 * side checks — on comment-stripped source — that `dispatchOnce` really consults
 * it under a `finally`, and that the cleanup goes through the cancellation-safe
 * helper.
 */
class ModelExecutionAbandonedPollTest {

    // ── 1. the decision table ────────────────────────────────────────────

    @Test
    fun `an abandoned poll with a live worker must be cancelled`() {
        assertTrue(internalShouldCancelAbandonedRun(completedNormally = false, workerDied = false))
    }

    @Test
    fun `a confirmed-dead worker is left for the orphan reaper`() {
        // Nobody is left to read `cancel`; the normal path deliberately leaves
        // such a dir as an orphan (no cancel marker), and the reaper's dead-pid
        // branch handles it.
        assertFalse(internalShouldCancelAbandonedRun(completedNormally = false, workerDied = true))
    }

    @Test
    fun `a normal exit is never re-cancelled`() {
        // Both a result and an internal 3-minute timeout reach here having
        // already decided what to do (ack / cancel+settle). A second cancel from
        // the finally would re-open a dir we just settled.
        for (workerDied in listOf(false, true)) {
            assertFalse(
                "completedNormally=true, workerDied=$workerDied",
                internalShouldCancelAbandonedRun(completedNormally = true, workerDied = workerDied),
            )
        }
    }

    // ── 2. wiring ────────────────────────────────────────────────────────

    @Test
    fun `dispatchOnce consults the predicate inside a finally`() {
        val code = stripComments(
            readRepoFile(
                "app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt",
            ),
        )
        assertTrue(
            "the abandoned-poll branch must exist and be driven by the predicate",
            code.contains("if (internalShouldCancelAbandonedRun(pollFinished, workerDied))"),
        )
        assertTrue(
            "the cancel must reach the worker as step one of the helper",
            code.contains("writeCancel = { ModelExecutionMailbox.writeCancel(dir) }"),
        )
        assertTrue(
            "the settle must be the helper's cleanup step",
            code.contains("internalRunCancelCleanup("),
        )
        // The flag must be set the moment the poll is over — BEFORE the
        // post-poll decisions — so a cancel arriving while those settle a
        // finished run cannot shove a second `cancel` at a worker that already
        // answered. Position, not just presence: "set it right before the
        // return" would look identical to a token grep and is exactly the wrong
        // ordering.
        val flagAt = code.indexOf("pollFinished = true")
        val postPollAt = code.indexOf("if (result != null) {")
        val returnAt = code.indexOf("return DispatchOutcome(result, workerDied)")
        assertTrue("all three markers must be present", flagAt > 0 && postPollAt > 0 && returnAt > postPollAt)
        assertTrue("the flag must be set before the post-poll decisions", flagAt < postPollAt)
        assertTrue(
            "the finally block must close the same try that opens before the poll",
            code.indexOf("} finally {", returnAt) > 0,
        )
    }

    @Test
    fun `the poll loop still short-circuits on a confirmed dead worker`() {
        // Regression guard for the neighbouring contract: the new finally must not
        // turn the dead-worker fast path into a 3-minute wait. `workerDied` is
        // still assigned inside the loop and still ends the poll early.
        val code = stripComments(
            readRepoFile(
                "app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt",
            ),
        )
        assertTrue(code.contains("workerDied = true"))
        assertTrue(code.contains("if (workerDiedWithoutResult(dir))"))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "src/android/app").isDirectory) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String = File(repoRoot(), "src/android/$relative").readText()

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 } else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }
}
