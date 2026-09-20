package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-177] Provider-side stream-failure classification.
 *
 * Guards the predicate that decides whether a `catch (e: Exception)` around a
 * provider's SSE loop is looking at "the consumer asked us to stop" or "the
 * stream broke". Getting it wrong in the permissive direction silently swallows
 * real stream errors; getting it wrong in the strict direction restores the bug
 * (a user cancel logged as `stream parse exception`, 12–25×/day).
 *
 * The shapes below are copied from the measured kotlinx behaviour
 * (`f177/exp_f177i`), not invented:
 *
 *   consumer threw RuntimeException        -> provider sees CE(cause = that RE)
 *   consumer threw CancellationException   -> provider sees CE(cause = null)
 *   consumer threw IOException             -> provider sees CE(cause = that IOE)
 */
class ProviderStreamCancellationTest {

    private class WorkerCancel : CancellationException("cancelled")

    @Test
    fun `cause-less CE is a consumer-side cancellation`() {
        val wrapper = CancellationException("Channel was consumed, consumer had failed")
        // [F-177] after the type change the worker's own exception IS the CE the
        // producer sees — this is the production shape that must reclassify.
        assertNotNull("a cause-less CE is a stop request, not an error", wrapper.asConsumerSideCancellation())
        assertEquals(wrapper, wrapper.asConsumerSideCancellation())
    }

    @Test
    fun `worker cancel typed as CE is recognised`() {
        val e = WorkerCancel()
        assertNotNull("the worker's own cancel type must classify as a stop", e.asConsumerSideCancellation())
    }

    @Test
    fun `CE carrying a real cause stays an error`() {
        // The pre-fix production shape: consumer died of a real IOException, so
        // kotlinx wrapped it in a CE whose cause is that IOException. This MUST
        // NOT be reclassified as a cancel.
        val wrapper = CancellationException("Channel was consumed, consumer had failed")
            .apply { initCause(java.io.IOException("stream was reset: CANCEL")) }
        assertNull("a CE with a cause carries a real failure", wrapper.asConsumerSideCancellation())
    }

    @Test
    fun `ordinary exceptions are never cancellations`() {
        assertNull(java.io.IOException("stream was reset: CANCEL").asConsumerSideCancellation())
        assertNull(IllegalStateException("json parse boom").asConsumerSideCancellation())
        assertNull(RuntimeException("Stream error").asConsumerSideCancellation())
    }

    @Test
    fun `cause chain summary names the underlying failure`() {
        // The whole point of F-177's second half: one log line must answer
        // "why did this stream die" without grepping another process's log.
        val wrapper = CancellationException("Channel was consumed, consumer had failed")
            .apply { initCause(IllegalStateException("REAL-CAUSE")) }
        val summary = wrapper.causeChainSummary()
        assertTrue("summary must include the wrapper: $summary", summary.contains("CancellationException"))
        assertTrue("summary must include the real cause: $summary", summary.contains("REAL-CAUSE"))
        assertTrue("summary must show the chain direction: $summary", summary.contains("<-"))
    }

    @Test
    fun `cause chain summary is bounded`() {
        // A self-referential chain must not turn a log line into a hang.
        var cur: Throwable = RuntimeException("d0")
        for (i in 1..12) cur = RuntimeException("d$i").apply { initCause(cur) }
        val summary = cur.causeChainSummary()
        assertEquals(4, summary.split("<-").size)
    }

    @Test
    fun `cause chain summary tolerates a null message`() {
        val summary = RuntimeException().causeChainSummary()
        assertTrue("a message-less throwable must still render: $summary", summary.contains("(no message)"))
    }
}
