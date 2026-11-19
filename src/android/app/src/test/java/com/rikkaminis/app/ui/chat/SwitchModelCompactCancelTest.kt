package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fullscan-1119 F-05] `switchModelAndRerun()` never cancelled `compactJob`.
 *
 * A mid-stream model switch left the OLD turn's compaction running. It
 * finished after the switch's rewind and published its summary + marker over
 * the NEW model's transcript (greyed rows + a recap of the conversation the
 * switch had just rewound); with a later new-turn compaction it could overwrite
 * that turn's own marker.
 *
 * Cancelling the job alone is not the fix — its commit block (marker insert →
 * in-memory publish) is a live window. `awaitCancelledCompact` awaits the
 * cancelled job BEFORE the rewind, so:
 *  • a compact cancelled before its commit writes nothing (timing 1), and
 *  • a compact that already committed is strictly ordered before the new
 *    turn's marker — it can never land after it (timing 2).
 */
class SwitchModelCompactCancelTest {

    @Test
    fun `a compact cancelled before its commit writes nothing`() = runBlocking {
        val markers = mutableListOf<String>()
        var wroteOldMarker = false
        val compact = launch(Dispatchers.Default) {
            delay(60_000) // summary generation — the long, cancellable part
            wroteOldMarker = true
            markers += "old"
        }
        compact.cancel() // the model switch
        awaitCancelledCompact(compact)
        assertFalse("a cancelled compact must not write its marker after the switch", wroteOldMarker)
        markers += "new" // the new turn's own compaction
        assertEquals("the new turn's marker must be the only one", listOf("new"), markers)
    }

    @Test
    fun `a compact already past its commit cannot overwrite the new marker`() = runBlocking {
        val markers = mutableListOf<String>()
        val commitDone = CompletableDeferred<Unit>()
        val compact = launch(Dispatchers.Default) {
            markers += "old"      // the marker insert already landed
            commitDone.complete(Unit)
            awaitCancellation()   // …then the job parks in withContext(Main)
        }
        withTimeout(5_000) { commitDone.await() }
        compact.cancel()
        awaitCancelledCompact(compact)
        markers += "new"
        assertEquals(
            "the old marker (if any) must be ordered BEFORE the new turn's marker",
            listOf("old", "new"),
            markers,
        )
        assertEquals("nothing may be appended after the new marker", "new", markers.last())
    }

    @Test
    fun `a null compact job is a no-op`() = runBlocking {
        awaitCancelledCompact(null) // must not throw
    }

    @Test
    fun `the switch cancels and awaits the compact before the rewind`() {
        val body = ChatSourceSlices.function(
            "ChatModelRouting.kt",
            "internal fun ChatViewModel.switchModelAndRerun(",
        )
        val cancel = body.indexOf("preSwitchCompact?.cancel()")
        val claim = body.indexOf("_isStreaming.value = true")
        val await = body.indexOf("awaitCancelledCompact(preSwitchCompact)")
        val read = body.indexOf("chatRepository.loadMessages(sid)")
        val drop = body.indexOf("dropCompactMarkerIfRewound(sid)")
        assertTrue("the switch must cancel the pre-switch compact", cancel >= 0)
        assertTrue("cancel must happen in Phase 1, before the new claim", cancel < claim)
        assertTrue("the shell must await the cancelled compact", await >= 0)
        assertTrue("the await must come before the DB re-sync", await < read)
        assertTrue("the await must come before the marker drop", await < drop)
        assertTrue("the rewind must still drop an orphaned marker", drop > 0)
    }
}
