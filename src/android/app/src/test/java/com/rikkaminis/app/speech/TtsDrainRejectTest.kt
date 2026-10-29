package com.rikkaminis.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-B11-F07] `TtsDrainTracker` rollback for an utterance the engine REFUSED.
 *
 * `TextToSpeech.speak` answers `ERROR` (-1) instead of enqueueing when it will
 * not speak (text over `getMaxSpeechInputLength()`, or a service that died) —
 * and then delivers no `onDone`/`onError` at all. Without a rollback the
 * counter registered for that utterance stayed up forever, so `isSpeaking`
 * stayed true (the UI shows the "speaking" state, read-aloud buttons look
 * busy) until some unrelated `stop()`.
 *
 * `TtsDrainTracker` is pure JVM code (`AtomicLong`/`AtomicInteger` only), so
 * these run on the same JVM as CI instead of needing a device.
 */
class TtsDrainRejectTest {

    @Test
    fun `refused queued utterance with nothing else pending drains the queue`() {
        val drain = TtsDrainTracker()
        drain.newSession() // the QUEUE_FLUSH utterance this call replaces
        drain.invalidate() // speak() refused it → counters cleared, nothing pending
        drain.enqueue() // speakQueued registered one

        assertTrue("nothing else is pending, so the queue must read as drained", drain.rejectPending())
    }

    @Test
    fun `refused queued utterance behind a live one keeps the queue open`() {
        val drain = TtsDrainTracker()
        val epoch = drain.newSession() // QUEUE_FLUSH utterance, still playing
        drain.enqueue() // QUEUE_ADD utterance the engine refuses

        assertFalse("the flushed utterance is still pending", drain.rejectPending())
        // …and when that live utterance finishes, the queue drains normally.
        assertTrue(drain.onFinished(drain.utteranceId(epoch)))
    }

    @Test
    fun `duplicate rollback floors at zero`() {
        val drain = TtsDrainTracker()
        drain.newSession()
        drain.invalidate()
        assertTrue(drain.rejectPending())
        assertTrue("a second rollback must not go negative", drain.rejectPending())
    }

    @Test
    fun `rollback also counts a refused flush session`() {
        // speak() uses invalidate(), but the invariant must hold either way:
        // after rolling back the only registered utterance the tracker reports
        // drained instead of waiting for a callback that will never come.
        val drain = TtsDrainTracker()
        drain.newSession()
        assertTrue(drain.rejectPending())
    }
}
