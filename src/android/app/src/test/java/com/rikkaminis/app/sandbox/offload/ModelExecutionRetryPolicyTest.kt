package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R8-B B-F01] The two retry budgets must be counted SEPARATELY.
 *
 * The field failure: the user replaces a quota-exhausted API key.
 *  - dispatch #1 → the worker writes `stale_key_cache` (a typed, pre-provider
 *    rejection) → retry after 500ms;
 *  - dispatch #2 → the poisoned worker is already self-reaping, so it dies with
 *    no result at all;
 *  - with ONE shared counter (`attemptsMade`) the death branch read
 *    `2 < WORKER_DEATH_ATTEMPTS(2) == false` and gave up — yet dispatch #3 is the
 *    one that spawns a fresh process and reads the NEW key.
 *
 * So `decide` takes `workerDeathAttempts` as its own required argument, and
 * [dispatchWithRetry] advances both counters independently.
 *
 * Expected values are hand-written literals (evidence-discipline): nothing here
 * is derived from the constants under test, so flipping a budget constant
 * changes the failure instead of the expectation.
 */
class ModelExecutionRetryPolicyTest {

    // ── the reported sequence ────────────────────────────────────────────────

    @Test
    fun `a typed rejection followed by a worker death still re-dispatches`() {
        // Attempt 2 died. The typed rejection spent nothing from the death budget,
        // so this is death #1 of 2 → immediate re-dispatch (the one that revives
        // the channel with the new key).
        assertEquals(
            ModelExecutionRetryPolicy.Decision.RetryAfter(0L),
            ModelExecutionRetryPolicy.decide(
                attemptsMade = 2,
                errorCode = WorkerKeyFreshness.STALE_KEY_CACHE,
                workerDied = true,
                workerDeathAttempts = 1,
            ),
        )
    }

    @Test
    fun `the second worker death gives up`() {
        assertEquals(
            ModelExecutionRetryPolicy.Decision.GiveUp,
            ModelExecutionRetryPolicy.decide(
                attemptsMade = 3,
                errorCode = null,
                workerDied = true,
                workerDeathAttempts = 2,
            ),
        )
    }

    @Test
    fun `the whole stale-key-then-death sequence reaches a third dispatch`() {
        val events = mutableListOf<String>()
        val attempts = java.util.ArrayDeque(
            listOf(
                ModelDispatchAttempt("""{"error":"stale_key_cache"}""", workerDied = false),
                ModelDispatchAttempt(null, workerDied = true),
                ModelDispatchAttempt("""{"text":"ok"}""", workerDied = false),
            ),
        )
        val delays = mutableListOf<Long>()

        val out = runBlocking {
            dispatchWithRetry(
                attempt = {
                    events += "dispatch"
                    attempts.removeFirst()
                },
                waitBeforeRetry = { delays += it },
            )
        }

        assertEquals("""{"text":"ok"}""", out.resultJson)
        assertEquals("three dispatches: rejection, death, recovery", 3, events.size)
        assertEquals(listOf(500L, 0L), delays)
    }

    @Test
    fun `a rejection-only sequence still caps at three dispatches`() {
        // The typed budget is unchanged by the split: initial + 2 retries.
        val worker = { ModelDispatchAttempt("""{"error":"worker_queue_full"}""", workerDied = false) }
        var dispatches = 0
        val out = runBlocking {
            dispatchWithRetry(
                attempt = { dispatches++; worker() },
                waitBeforeRetry = { },
            )
        }
        assertEquals(3, dispatches)
        assertEquals("""{"error":"worker_queue_full"}""", out.resultJson)
    }

    @Test
    fun `death-only sequence caps at the death budget`() {
        var dispatches = 0
        val out = runBlocking {
            dispatchWithRetry(
                attempt = { dispatches++; ModelDispatchAttempt(null, workerDied = true) },
                waitBeforeRetry = { },
            )
        }
        assertEquals("initial + 1 death retry", 2, dispatches)
        assertNull(out.resultJson)
    }

    @Test
    fun `the loop still refuses to retry a timeout`() {
        var dispatches = 0
        val out = runBlocking {
            dispatchWithRetry(
                attempt = { dispatches++; ModelDispatchAttempt(null, workerDied = false) },
                waitBeforeRetry = { },
            )
        }
        assertEquals("a timeout may still be generating — never re-dispatched", 1, dispatches)
        assertNull(out.resultJson)
    }

    // ── parameter shape: the counter cannot be defaulted away ────────────────

    @Test
    fun `workerDeathAttempts has no default value in the production source`() {
        // A defaulted parameter would let a future caller silently keep the old
        // shared-counter behaviour — the bug — so the signature must demand it.
        // Source shape (not reflection): Kotlin's synthetic `decide$default` is an
        // implementation detail, while the missing `=` is the contract.
        val src = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionRetryPolicy.kt",
        )
        assertTrue(
            "decide must declare workerDeathAttempts",
            src.contains("workerDeathAttempts: Int,"),
        )
        assertTrue(
            "workerDeathAttempts must NOT be defaulted",
            !src.contains("workerDeathAttempts: Int ="),
        )
        assertTrue(
            "the loop must pass the death counter explicitly",
            src.contains("workerDeathAttempts = workerDeaths,"),
        )
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }
}
