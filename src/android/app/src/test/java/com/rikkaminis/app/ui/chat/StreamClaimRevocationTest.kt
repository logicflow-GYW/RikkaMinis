package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fullscan-1119 F-04] Stop pressed while a turn is still being prepared
 * (the prompt build) could still start a stream.
 *
 * Two defects, pinned separately:
 *  1. `runRerunStreamTail`'s revocation guard compared only epoch + token.
 *     `cancelStream()` clears `_isStreaming` synchronously but never re-mints
 *     epoch/token (only a NEW turn does), so a stopped turn still matched
 *     itself — the guard could not see a Stop. [streamClaimStillOwned] adds
 *     the flag and the claiming coroutine's liveness.
 *  2. the tail launched the stream with `viewModelScope.launch(...)` — a
 *     SIBLING of the setup shell. `viewModelScope` is not cancelled by Stop,
 *     so a launch issued from an already-cancelled shell still ran.
 *     [launchTurnStream] launches a CHILD of the caller, which inherits the
 *     cancellation (created already-cancelled, body never runs).
 *
 * The interleaving is forced, not hoped for: the shell is parked in its
 * prompt-build suspension when Stop lands, then it is put back and attempts
 * the launch — the worst case the guard races against.
 */
class StreamClaimRevocationTest {

    private val token = Any()

    // ── 1. the revocation predicate ──────────────────────────────────────

    @Test
    fun `a stop during the prompt build revokes the claim`() {
        // cancelStream(): _isStreaming=false, epoch/token untouched.
        assertFalse(
            "Stop only clears the streaming flag — the guard must see it",
            streamClaimStillOwned(
                claimIsActive = true,
                streaming = false,
                sendEpoch = 7L,
                claimEpoch = 7L,
                callerToken = token,
                currentToken = token,
            ),
        )
    }

    @Test
    fun `a newer turn revokes the claim`() {
        assertFalse(
            "a newer claim epoch must revoke this turn",
            streamClaimStillOwned(true, true, sendEpoch = 7L, claimEpoch = 8L, token, token),
        )
        assertFalse(
            "a re-minted claim token must revoke this turn",
            streamClaimStillOwned(true, true, sendEpoch = 7L, claimEpoch = 7L, token, Any()),
        )
    }

    @Test
    fun `a cancelled setup coroutine revokes the claim`() {
        assertFalse(
            "a cancelled shell may not start a stream",
            streamClaimStillOwned(false, true, sendEpoch = 7L, claimEpoch = 7L, token, token),
        )
    }

    @Test
    fun `a live claim still starts the stream`() {
        // Normal completion path: everything still ours → the tail launches.
        assertTrue(streamClaimStillOwned(true, true, sendEpoch = 7L, claimEpoch = 7L, token, token))
    }

    // ── 2. the launch scope ──────────────────────────────────────────────

    @Test
    fun `a stream launched from a cancelled setup shell never runs`() = runBlocking {
        var ran = false
        var launched: Job? = null
        val enteredBuild = CompletableDeferred<Unit>()
        val shell = launch(Dispatchers.Default) {
            try {
                enteredBuild.complete(Unit)
                delay(60_000) // the prompt build
            } catch (_: CancellationException) {
                // Stop landed here. The production guard returns false at this
                // point; force the worst case and attempt the launch anyway.
            }
            launched = launchTurnStream(coroutineContext) { ran = true }
        }
        withTimeout(5_000) { enteredBuild.await() }
        shell.cancel() // Stop during preparation
        shell.join()
        assertNotNull("the shell must have attempted the launch", launched)
        assertTrue("the zombie stream job must be cancelled", launched!!.isCancelled)
        assertFalse("a stream started after Stop (zombie stream)", ran)
    }

    @Test
    fun `a stream launched from a live setup shell runs`() = runBlocking {
        val ran = CompletableDeferred<Unit>()
        val shell = launch(Dispatchers.Default) {
            launchTurnStream(coroutineContext) { ran.complete(Unit) }
        }
        withTimeout(5_000) { ran.await() }
        shell.join() // structured: the shell completes with its stream child
    }

    // ── 3. wiring: the tail actually uses both pieces ────────────────────

    @Test
    fun `the tail guards with the predicate and launches as a child`() {
        val body = ChatSourceSlices.function(
            "ChatTurnPersistence.kt",
            "internal suspend fun ChatViewModel.runRerunStreamTail(",
        )
        assertTrue(
            "the tail must guard with streamClaimStillOwned",
            body.contains("streamClaimStillOwned("),
        )
        assertTrue(
            "the tail must launch through launchTurnStream",
            body.contains("streamJob = launchTurnStream(coroutineContext)"),
        )
        assertFalse(
            "the sibling launch under viewModelScope is the audited zombie-stream shape",
            body.contains("streamJob = viewModelScope.launch("),
        )
    }
}
