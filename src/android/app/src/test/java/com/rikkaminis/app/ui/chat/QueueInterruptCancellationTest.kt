package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [fullscan-1119 F-08] The post-tool queue interrupt swallowed cancellation.
 *
 * `AgentLoopEngine` wrapped `host.injectQueuedPromptsAsNewTurn(...)` in a bare
 * `catch (e: Exception)` that logged and returned null. `CancellationException`
 * is an `Exception`, so a Stop landing exactly at that boundary was read as
 * "the injection failed" and the loop carried on into the next turn — which
 * could then start a compaction behind the stop (same family as F-05, second
 * entry).
 *
 * The contract has two halves and both are tested:
 *  • cancellation propagates (no next turn after Stop);
 *  • a real IOException is still absorbed — logged, `null` returned — so the
 *    genuinely queued prompt falls through to the normal dispatch and is never
 *    dropped by a transient failure.
 */
class QueueInterruptCancellationTest {

    private class Outcome(val handled: String?, val logged: String?)

    /** Mirrors the production catch arm exactly (same helper, same null). */
    private fun runQueueInterrupt(block: () -> Unit): Outcome {
        var logged: String? = null
        val handled: String? = try {
            block()
            "injected"
        } catch (e: Exception) {
            rethrowCancellationOr(e) { failure -> logged = failure.message }
            null
        }
        return Outcome(handled, logged)
    }

    @Test
    fun `cancellation propagates out of the queue interrupt`() {
        try {
            runQueueInterrupt { throw CancellationException("stopped") }
            fail("CancellationException was swallowed — the loop walks into the next turn after Stop")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `a real IO failure is logged and the loop falls through`() {
        val outcome = runQueueInterrupt { throw IOException("pipe broke") }
        assertNull("a failed injection must fall through to normal dispatch", outcome.handled)
        assertEquals("real failures keep the audited log", "pipe broke", outcome.logged)
    }

    @Test
    fun `the agent loop catch routes through the shared policy`() {
        val engine = ChatSourceSlices.anySource(
            "app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt",
        )
        val call = engine.indexOf("host.injectQueuedPromptsAsNewTurn(")
        assertTrue("the queue-interrupt call is missing", call >= 0)
        val window = engine.substring(call, minOf(engine.length, call + 1_800))
        assertTrue(
            "the call site must route failures through rethrowCancellationOr",
            window.contains("rethrowCancellationOr(e)"),
        )
        val oldShape = "AppLogger.warning(TAG_STREAM, \"host.injectQueuedPromptsAsNewTurn failed: \$" +
            "{e.message}\")"
        assertFalse(
            "the pre-fix arm turned a Stop into 'injection failed' and continued",
            window.contains(oldShape),
        )
    }
}
