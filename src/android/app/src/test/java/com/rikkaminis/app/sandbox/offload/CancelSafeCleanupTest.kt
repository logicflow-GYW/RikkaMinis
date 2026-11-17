package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D3-F02] Cancellation-safe run-dir cleanup.
 *
 * The defect: `ChatStreamOffloadHandler.stream`'s `finally` called the
 * `delay`-driven `awaitTerminalAndWorkerExitThenDelete` **bare**. A user "stop"
 * cancels the collecting coroutine, so the first `delay` inside that wait threw
 * `CancellationException`, the surrounding `catch (_: Exception) {}` swallowed
 * it, and both the `client.ack` and the dir delete were skipped — the worker then
 * held its native heap for its ack timeout + drain grace (~45 s) on a stream
 * nobody was reading.
 *
 * Two sides, deliberately not derived from each other:
 *  1. BEHAVIOUR — [internalRunCancelCleanup] runs both steps under real
 *     cancellation, and the same shape WITHOUT the helper does not (that negative
 *     control is what proves the assertion has teeth; it is the pre-fix code).
 *  2. WIRING — the production `finally` routes through the helper, checked on
 *     comment-stripped source so a KDoc mention cannot satisfy it.
 *
 * Measured pre-fix with `shared/work/incr1118/t2probe/`: `cancel` written,
 * `client.ack` false, run dir kept.
 */
class CancelSafeCleanupTest {

    // ── 1. behaviour ─────────────────────────────────────────────────────

    @Test
    fun `cleanup runs to completion even when the calling job is cancelled`() = runBlocking {
        var cancelWritten = false
        var settled = false
        var reported = false
        val job = launch {
            try {
                awaitCancellation()
            } finally {
                reported = internalRunCancelCleanup(
                    writeCancel = { cancelWritten = true },
                    cleanup = {
                        // A real `delay` is the load-bearing part: this is exactly
                        // where the pre-fix wait died. `settled` must be set AFTER
                        // it, otherwise the assertion would only prove that the
                        // non-suspending step ran (measured: dropping
                        // NonCancellable left an earlier version of this test green).
                        delay(30)
                        settled = true
                    },
                )
            }
        }
        // Let the job reach `awaitCancellation`, then cancel it the way a user
        // "stop" does.
        yield()
        job.cancelAndJoin()
        assertTrue("cancel marker must be written on the cancelled path", cancelWritten)
        assertTrue("the SUSPENDING step must complete after cancellation", settled)
        assertTrue("and the helper must report that it completed", reported)
        assertFalse("the job really was cancelled", job.isActive)
    }

    @Test
    fun `an unwrapped suspending cleanup is skipped — the negative control`() = runBlocking {
        // Verbatim shape of the pre-fix production code (ChatStreamOffloadHandler
        // `finally`): write the marker, then call the `delay`-driven wait with no
        // NonCancellable. This arm must FAIL to clean up, otherwise the case above
        // would pass for the wrong reason.
        var cancelWritten = false
        var waitedToCompletion = false
        val job = launch {
            try {
                awaitCancellation()
            } finally {
                try {
                    cancelWritten = true
                    delay(30)
                    waitedToCompletion = true
                } catch (_: CancellationException) {
                    // This swallow is the defect: everything below it is skipped.
                }
            }
        }
        yield()
        job.cancelAndJoin()
        assertTrue("the synchronous step still runs (this is why the bug looked partial)", cancelWritten)
        assertFalse("without NonCancellable the suspending step is skipped", waitedToCompletion)
    }

    @Test
    fun `a throwing cleanup is reported, not swallowed silently`() = runBlocking {
        val reported = internalRunCancelCleanup(
            writeCancel = {},
            cleanup = { throw IllegalStateException("worker dir already gone") },
        )
        assertFalse("a failed cleanup must be distinguishable from a skipped one", reported)
    }

    @Test
    fun `a throwing cancel-write does not block the wait`() = runBlocking {
        var waited = false
        val reported = internalRunCancelCleanup(
            writeCancel = { throw IllegalStateException("read-only run dir") },
            cleanup = { waited = true },
        )
        assertTrue("the settle must still run when the marker could not be written", waited)
        assertTrue("nothing failed inside cleanup()", reported)
    }

    // ── 2. wiring ────────────────────────────────────────────────────────

    @Test
    fun `the streaming finally routes its worker wait through the helper`() {
        val code = stripComments(
            readRepoFile(
                "app/src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamOffloadHandler.kt",
            ),
        )
        assertTrue(
            "the cancel-write must be the helper's first step",
            code.contains("writeCancel = {") &&
                code.contains("ModelExecutionMailbox.writeCancel(") &&
                code.contains("ModelExecutionMailbox.writeCancelDeath("),
        )
        assertTrue(
            "the terminal/worker-exit wait must be the helper's cleanup step",
            code.contains("cleanup = { awaitTerminalAndWorkerExitThenDelete(dir, runId) }"),
        )
        assertTrue(
            "and both must be handed to internalRunCancelCleanup",
            code.contains("internalRunCancelCleanup("),
        )
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

    /**
     * Strip line and block comments (keeping string literals) so an assertion cannot
     * be satisfied by a KDoc mention of the identifier it is looking for.
     */
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
