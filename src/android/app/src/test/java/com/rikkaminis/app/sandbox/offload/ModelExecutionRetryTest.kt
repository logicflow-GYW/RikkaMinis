package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [meta1028-D2 F-01] Non-streaming dispatch retry policy.
 *
 * The defect: since `feat/provider-exec-concurrency`, `:modelservice` writes a
 * TYPED result for a stale-key (`stale_key_cache`) or queue-full
 * (`worker_queue_full`) rejection instead of dying silently — and
 * `ModelExecutionDispatcher.dispatch` short-circuited on any non-null result,
 * so its own "retry onto a fresh process" branch became unreachable for
 * exactly the case it was written for. After the user replaced an API key, every
 * non-streaming call (`minis-model-use run`, titles, compaction, QuickTest)
 * hard-failed until the stale worker happened to die.
 *
 * Three sides, deliberately not derived from each other:
 *  1. the DECISION TABLE by literal (which codes may be retried, and why a
 *     timeout may not);
 *  2. the LOOP by behaviour — a fake `attempt` feeds the real loop and records
 *     the delays, so the attempt budget is measured, not read off a comment;
 *  3. the WIRING by source shape — `dispatch` must consult the policy, and the
 *     dead one-shot short-circuit must be gone.
 */
class ModelExecutionRetryTest {

    // ── 1. decision table ────────────────────────────────────────────────

    @Test
    fun `the worker's pre-provider rejections are retryable`() {
        for (code in listOf("stale_key_cache", "worker_queue_full")) {
            assertTrue("$code must be retryable", ModelExecutionRetryPolicy.isRetryableCode(code))
            assertEquals(
                "$code after one attempt must retry once, 500ms later",
                ModelExecutionRetryPolicy.Decision.RetryAfter(500L),
                ModelExecutionRetryPolicy.decide(1, code, workerDied = false),
            )
        }
    }

    @Test
    fun `nothing else is retryable — spelling variants included`() {
        for (code in listOf(
            "missing_api_key", "model_use_failed", "invalid_args", "readonly_mount",
            "STALE_KEY_CACHE", "stale_key_cache ", " worker_queue_full", "", null,
        )) {
            assertFalse("$code must not be retryable", ModelExecutionRetryPolicy.isRetryableCode(code))
            assertEquals(
                "$code must give up on the first attempt",
                ModelExecutionRetryPolicy.Decision.GiveUp,
                ModelExecutionRetryPolicy.decide(1, code, workerDied = false),
            )
        }
    }

    @Test
    fun `a retryable rejection is retried twice and then surfaced`() {
        val code = "stale_key_cache"
        assertEquals(
            ModelExecutionRetryPolicy.Decision.RetryAfter(500L),
            ModelExecutionRetryPolicy.decide(2, code, workerDied = false),
        )
        assertEquals(
            "3 dispatches is the cap (initial + 2)",
            ModelExecutionRetryPolicy.Decision.GiveUp,
            ModelExecutionRetryPolicy.decide(3, code, workerDied = false),
        )
    }

    @Test
    fun `a confirmed worker death keeps its historical single immediate retry`() {
        assertEquals(
            ModelExecutionRetryPolicy.Decision.RetryAfter(0L),
            ModelExecutionRetryPolicy.decide(1, errorCode = null, workerDied = true),
        )
        assertEquals(
            ModelExecutionRetryPolicy.Decision.GiveUp,
            ModelExecutionRetryPolicy.decide(2, errorCode = null, workerDied = true),
        )
    }

    @Test
    fun `a timeout is never retried — the worker may still be generating`() {
        // No result and no death evidence = timeout / IO / service
        // unavailable. Re-dispatching would start a SECOND provider call
        // (duplicate billing / duplicate image) — the reason the
        // `fix/audit-s3l2` comment exists.
        assertEquals(
            ModelExecutionRetryPolicy.Decision.GiveUp,
            ModelExecutionRetryPolicy.decide(1, errorCode = null, workerDied = false),
        )
    }

    @Test
    fun `errorCodeOf reads only a real error field`() {
        assertEquals(
            "stale_key_cache",
            ModelExecutionRetryPolicy.errorCodeOf("""{"error":"stale_key_cache"}"""),
        )
        assertEquals(
            "worker_queue_full",
            ModelExecutionRetryPolicy.errorCodeOf(
                """{"error":"worker_queue_full","message":"provider worker rejected: worker queue full (queued=6)","exit_code":3}""",
            ),
        )
        assertNull(
            "empty error field = the worker's success envelope",
            ModelExecutionRetryPolicy.errorCodeOf("""{"error":"","text":"hi"}"""),
        )
        assertNull(ModelExecutionRetryPolicy.errorCodeOf("""{"text":"hi"}"""))
        assertNull(
            "unparseable result must not be treated as no-error (we cannot prove nothing was billed)",
            ModelExecutionRetryPolicy.errorCodeOf("<html>captive portal</html>"),
        )
        assertNull(ModelExecutionRetryPolicy.errorCodeOf(null))
        assertNull(ModelExecutionRetryPolicy.errorCodeOf(""))
    }

    // ── 2. the loop, driven by a fake worker ─────────────────────────────

    /** Feeds [results] to the real loop and records what it did. */
    private class FakeWorker(vararg results: ModelDispatchAttempt) {
        private val queue = ArrayDeque(results.toList())
        private var last = results.lastOrNull() ?: ModelDispatchAttempt(null, false)
        val delays = mutableListOf<Long>()
        var attempts = 0
            private set

        suspend fun attempt(): ModelDispatchAttempt {
            attempts++
            if (queue.isNotEmpty()) last = queue.removeFirst()
            return last
        }

        suspend fun wait(ms: Long) {
            delays.add(ms)
        }
    }

    private fun run(
        worker: FakeWorker,
    ): ModelDispatchAttempt = runBlocking {
        dispatchWithRetry(attempt = { worker.attempt() }, waitBeforeRetry = { worker.wait(it) })
    }

    @Test
    fun `a first-attempt success dispatches exactly once`() {
        val worker = FakeWorker(ModelDispatchAttempt("""{"text":"42"}""", false))
        val out = run(worker)
        assertEquals("""{"text":"42"}""", out.resultJson)
        assertEquals(1, worker.attempts)
        assertEquals(emptyList<Long>(), worker.delays)
    }

    @Test
    fun `a stale-key rejection recovers on the next dispatch`() {
        val worker = FakeWorker(
            ModelDispatchAttempt("""{"error":"stale_key_cache"}""", false),
            ModelDispatchAttempt("""{"text":"42"}""", false),
        )
        val out = run(worker)
        assertEquals("""{"text":"42"}""", out.resultJson)
        assertEquals(2, worker.attempts)
        assertEquals("one 500ms wait for the poisoned worker's reap", listOf(500L), worker.delays)
    }

    @Test
    fun `persistent rejection stops at the attempt cap and returns the last envelope`() {
        val stale = """{"error":"stale_key_cache"}"""
        val worker = FakeWorker(
            ModelDispatchAttempt(stale, false),
            ModelDispatchAttempt(stale, false),
            ModelDispatchAttempt(stale, false),
            ModelDispatchAttempt("""{"text":"never reached"}""", false),
        )
        val out = run(worker)
        assertEquals(stale, out.resultJson)
        assertEquals(3, worker.attempts)
        assertEquals(listOf(500L, 500L), worker.delays)
    }

    @Test
    fun `a timeout is dispatched once — no delay, no second call`() {
        val worker = FakeWorker(
            ModelDispatchAttempt(null, false),
            ModelDispatchAttempt("""{"text":"must not happen"}""", false),
        )
        val out = run(worker)
        assertNull(out.resultJson)
        assertEquals(1, worker.attempts)
        assertEquals(emptyList<Long>(), worker.delays)
    }

    @Test
    fun `a confirmed worker death retries immediately, once`() {
        val worker = FakeWorker(
            ModelDispatchAttempt(null, true),
            ModelDispatchAttempt("""{"text":"42"}""", false),
        )
        val out = run(worker)
        assertEquals("""{"text":"42"}""", out.resultJson)
        assertEquals(2, worker.attempts)
        assertEquals("the death path never waits", listOf(0L), worker.delays)
    }

    @Test
    fun `recovery from one retryable code does not follow into a non-retryable one`() {
        val worker = FakeWorker(
            ModelDispatchAttempt("""{"error":"worker_queue_full"}""", false),
            ModelDispatchAttempt("""{"error":"missing_api_key","exit_code":2}""", false),
            ModelDispatchAttempt("""{"text":"never reached"}""", false),
        )
        val out = run(worker)
        assertEquals("""{"error":"missing_api_key","exit_code":2}""", out.resultJson)
        assertEquals(2, worker.attempts)
        assertEquals(listOf(500L), worker.delays)
    }

    // ── 3. wiring (source shape) ─────────────────────────────────────────

    private val dispatcherRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionDispatcher.kt"
    private val serviceRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt"

    @Test
    fun `dispatch routes through the policy instead of short-circuiting on any result`() {
        val dispatch = memberSlice(readRepoFile(dispatcherRel), "suspend fun dispatch(")
        assertTrue(
            "dispatch must consult the retry policy",
            dispatch.contains("dispatchWithRetry("),
        )
        assertFalse(
            "the one-shot short-circuit that made the retry branch unreachable must be gone",
            dispatch.contains("if (first.result != null) return first.result"),
        )
        assertTrue(
            "the dispatch outcome is returned verbatim (null = timeout/IO, unchanged)",
            dispatch.contains("return outcome.resultJson"),
        )
    }

    @Test
    fun `the queue-full wire literal has a single source`() {
        val service = readRepoFile(serviceRel)
        assertTrue(
            "the worker must write the policy's constant",
            service.contains("ModelExecutionRetryPolicy.WORKER_QUEUE_FULL"),
        )
        assertFalse(
            "a bare literal would let the writer and the retry policy drift apart",
            service.contains("\"worker_queue_full\""),
        )
        assertEquals(
            "the constant IS the wire literal",
            "worker_queue_full",
            ModelExecutionRetryPolicy.WORKER_QUEUE_FULL,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Lines of the member declared at [marker], up to the next sibling member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            indent <= 4 && line.isNotBlank() && !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*") &&
                Regex("^(private|internal|public|protected|fun|val|var|@)").containsMatchIn(line.trimStart())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
