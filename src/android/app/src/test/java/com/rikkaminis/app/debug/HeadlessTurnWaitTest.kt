package com.rikkaminis.app.debug

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D9-F01] `wait = true` entries of [HeadlessChatRunner] must end the
 * turn when the CALLER is cancelled.
 *
 * The rule was established in `[INCR-1117 A-F04]` for `prompt()` and then
 * copied — minus its `catch (CancellationException)` — into `retry`,
 * `rerunFromToolBlock` and `compact`. Those three stream on the session's own
 * `viewModelScope`, so dropping the wait left the child running tools and
 * spending tokens for an answer nobody waits for. [abandoningWaitStopsRun] is
 * that rule, extracted so all four entries share one implementation and so the
 * behaviour is testable without an Android `ViewModel`.
 *
 * The other half of the contract is that a **timeout is not a cancellation**:
 * "the answer is late" must leave the run alone and answer the ordinary
 * timeout envelope.
 */
class HeadlessTurnWaitTest {

    @Test
    fun `cancelling the caller stops the run exactly once`() = runBlocking {
        val stops = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<String>()
        val job = launch {
            abandoningWaitStopsRun({ stops.incrementAndGet() }) {
                started.complete(Unit)
                never.await()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, stops.get())
    }

    @Test
    fun `the caller still learns it was a stop`() = runBlocking {
        val stops = AtomicInteger(0)
        val thrown = runCatching {
            abandoningWaitStopsRun({ stops.incrementAndGet() }) {
                throw CancellationException("caller went away")
            }
        }.exceptionOrNull()
        assertTrue("expected the CancellationException to propagate, got $thrown", thrown is CancellationException)
        assertEquals(1, stops.get())
    }

    @Test
    fun `a stop that throws does not replace the cancellation`() = runBlocking {
        val thrown = runCatching {
            abandoningWaitStopsRun({ throw IllegalStateException("cancelStream blew up") }) {
                throw CancellationException("caller went away")
            }
        }.exceptionOrNull()
        assertTrue("stop failures must not mask the stop, got $thrown", thrown is CancellationException)
    }

    @Test
    fun `a timeout is not a cancellation and leaves the run alone`() = runBlocking {
        val stops = AtomicInteger(0)
        val result = abandoningWaitStopsRun({ stops.incrementAndGet() }) {
            withTimeoutOrNull(30L) {
                CompletableDeferred<String>().await()
            }
        }
        assertNull(result)
        assertEquals("a timed-out dispatch must NOT stop the run", 0, stops.get())
    }

    @Test
    fun `a normal completion never stops the run`() = runBlocking {
        val stops = AtomicInteger(0)
        val result = abandoningWaitStopsRun({ stops.incrementAndGet() }) { "settled" }
        assertEquals("settled", result)
        assertEquals(0, stops.get())
    }
}
