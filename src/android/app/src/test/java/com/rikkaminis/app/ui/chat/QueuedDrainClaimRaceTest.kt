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
        val won = claimStreamingSlot(flag) {
            minted++
            flag.value = true
        }
        assertTrue("an idle slot must be claimable", won)
        assertEquals("the mint step must run exactly once", 1, minted)
        assertTrue("the flag must be raised by the winner", flag.value)
    }

    @Test
    fun `an already-streaming flag is never claimed`() {
        val flag = MutableStateFlow(true)
        var minted = 0
        val won = claimStreamingSlot(flag) {
            minted++
            flag.value = true
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
                val won = claimStreamingSlot(flag) {
                    // Widen the check→claim window. In production this window
                    // is the synchronous prompt build; without the monitor two
                    // callers both read false here and both claim.
                    Thread.sleep(80)
                    flag.value = true
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
        val claim = body.indexOf("claimStreamingSlot(_isStreaming)")
        assertTrue("the prompt build must be the disk read", read >= 0)
        assertTrue("the drain must claim through claimStreamingSlot", claim >= 0)
        assertTrue(
            "the disk read must stay OUTSIDE the critical section (before the claim)",
            read < claim,
        )
        // An early `if (_isStreaming.value) return` may stay as a fast path —
        // what must NOT come back is a flag raise OUTSIDE the claim: that is
        // the check-then-act shape the monitor exists to close. The raise is
        // judged relative to the claim call, and only on statement lines
        // (prose mentions the old shape in the KDoc).
        val flips = body.lines().withIndex().filter { it.value.trim() == "_isStreaming.value = true" }
        assertTrue("the flag must still be raised somewhere", flips.isNotEmpty())
        val claimLine = body.substring(0, claim).count { it == '\n' }
        assertTrue(
            "every flag raise must sit inside the atomic claim (after its call line)",
            flips.all { it.index > claimLine },
        )
        assertTrue(
            "the loser must bail out of this drain attempt",
            body.substring(claim).contains("return@withContext"),
        )
    }

    @Test
    fun `the claim token is minted outside the drain coroutine`() {
        // dv29-B01 tautology rule: a token read INSIDE the coroutine and
        // compared against the field it was read from can never fire. The
        // identity must be captured before the launch.
        val body = ChatSourceSlices.function(
            "ChatQueueInterruption.kt",
            "internal fun ChatViewModel.resumeQueueAfterCancel()",
        )
        val mint = body.indexOf("val drainToken = Any()")
        val launch = body.indexOf("viewModelScope.launch(Dispatchers.IO)")
        assertTrue("the drain identity must be minted", mint >= 0)
        assertTrue("the mint must precede the coroutine launch", mint < launch)
        assertTrue(
            "the claim must publish the pre-minted token, not a fresh one",
            body.contains("streamingClaimToken = drainToken"),
        )
        assertFalse(
            "a token minted inside the coroutine makes the revocation check a tautology",
            body.substring(launch).contains("streamingClaimToken = Any()"),
        )
    }

    @Test
    fun `the stream start re-checks the claim before launching`() {
        val body = ChatSourceSlices.function(
            "ChatQueueInterruption.kt",
            "internal fun ChatViewModel.resumeQueueAfterCancel()",
        )
        val guard = body.indexOf("if (!isActive || !_isStreaming.value || launchEpoch != streamingClaimEpoch || drainToken !== streamingClaimToken)")
        val site = body.indexOf("streamJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY)")
        assertTrue("the stream start must carry a revocation guard", guard >= 0)
        assertTrue("the guard must run before the stream starts", guard < site)
        assertTrue(
            "the guard must bail out instead of starting the stream",
            body.substring(guard, site).contains("return@withContext"),
        )
    }
}
