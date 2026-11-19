package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [fullscan-1119 F-07] `resumeQueueAfterCancel()` checked `_isStreaming` and
 * claimed it with a synchronous disk read in between.
 *
 * Two drain coroutines (double Stop, Stop + the compact-success kick) could
 * both pass the check while one was inside the prompt build and both claim —
 * the second `streamJob =` overwrote the first reference while BOTH agent
 * loops ran, interleaving two writers into one session transcript.
 *
 * The interleaving test below is forced rather than hoped for: both threads
 * are released together by a barrier, and the mint step — which production
 * runs inside the critical section — sleeps long enough that an unsynchronized
 * check-then-act deterministically lets both through. With the monitor exactly
 * one wins.
 */
class QueuedDrainClaimRaceTest {

    @Test
    fun `an idle flag is claimed and raised`() {
        val flag = MutableStateFlow(false)
        var minted = 0
        val won = claimQueuedDrainSlot(flag) { f ->
            minted++
            f.value = true
        }
        assertTrue("an idle slot must be claimable", won)
        assertEquals("the mint step must run exactly once", 1, minted)
        assertTrue("the flag must be raised by the winner", flag.value)
    }

    @Test
    fun `an already-streaming flag is never claimed`() {
        val flag = MutableStateFlow(true)
        var minted = 0
        val won = claimQueuedDrainSlot(flag) { f ->
            minted++
            f.value = true
        }
        assertFalse("a live stream must block the claim", won)
        assertEquals("the loser must not mint a claim token", 0, minted)
        assertTrue("the live stream's flag must stay raised", flag.value)
    }

    @Test
    fun `two concurrent claims - exactly one wins`() {
        val flag = MutableStateFlow(false)
        val winners = AtomicInteger(0)
        val start = CyclicBarrier(2)
        val workers = (1..2).map {
            thread(name = "drain-claim-$it") {
                start.await()
                val won = claimQueuedDrainSlot(flag) { f ->
                    // Widen the check→claim window. In production this window
                    // is the synchronous prompt build; without the monitor two
                    // callers both read false here and both claim.
                    Thread.sleep(80)
                    f.value = true
                }
                if (won) winners.incrementAndGet()
            }
        }
        workers.forEach { it.join() }
        assertEquals("exactly one drain may own the session's stream slot", 1, winners.get())
        assertTrue("the winner's flag must be raised", flag.value)
    }

    @Test
    fun `the drain claims atomically after the disk read`() {
        val body = ChatSourceSlices.function(
            "ChatQueueInterruption.kt",
            "internal fun ChatViewModel.resumeQueueAfterCancel()",
        )
        val read = body.indexOf("val baseSystemPrompt = systemPromptForSession()")
        val claim = body.indexOf("claimQueuedDrainSlot(_isStreaming)")
        assertTrue("the prompt build must be the disk read", read >= 0)
        assertTrue("the drain must claim through claimQueuedDrainSlot", claim >= 0)
        assertTrue(
            "the disk read must stay OUTSIDE the critical section (before the claim)",
            read < claim,
        )
        // An early `if (_isStreaming.value) return` may stay as a fast path —
        // what must NOT come back is a raw flag flip outside the claim: that is
        // the check-then-act shape the monitor exists to close. Judged on
        // statement lines only (prose mentions the old shape in the KDoc).
        val rawFlip = body.lines().any { it.trim() == "_isStreaming.value = true" }
        assertFalse("the flag may only be raised inside the atomic claim", rawFlip)
        assertTrue(
            "the winner must raise the flag inside the claim block",
            body.contains("flag.value = true"),
        )
        assertTrue(
            "the loser must bail out of this drain attempt",
            body.substring(claim).contains("return@launch"),
        )
    }
}
