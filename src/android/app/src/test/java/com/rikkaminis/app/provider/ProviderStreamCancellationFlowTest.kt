package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-177] End-to-end contract for a cancelled stream, on the real topology.
 *
 * Reproduces the production frame measured in `f177/exp_f177i` and `exp_f177o`:
 *
 *   worker collect  → throws ModelExecutionCancelledException when the main
 *                     process wrote `run-<uuid>/cancel` (:1280 / :1301)
 *     └─ LLMProvider.streamMessage  { emitAll(streamMessageClamped) }
 *          └─ failOnSilentEmptyCompletion { collect { emit } }
 *               └─ provider rawStreamMessage = callbackFlow { SSE loop }
 *                    catch -> the code under test
 *
 * The assertions are the ones the bug violated:
 *   1. a user cancel must NOT be reported as a stream error
 *   2. a real stream failure must STILL be reported as one
 *   3. the worker's cancel contract (`t is ModelExecutionCancelledException`)
 *      must keep matching, and the enclosing job must stay usable
 */
class ProviderStreamCancellationFlowTest {

    /** Mirrors ModelExecutionService's private ModelExecutionCancelledException. */
    private class WorkerCancelled : CancellationException("cancelled")

    private class Recorded(val level: String, val message: String)

    private fun providerFlow(recorded: MutableList<Recorded>): Flow<String> = callbackFlow {
        try {
            var i = 0
            while (true) {
                send("chunk-$i")
                i++
                delay(1)
            }
        } catch (e: Exception) {
            // the exact shape of the production fix
            val consumerCancel = e.asConsumerSideCancellation()
            if (consumerCancel != null) {
                recorded += Recorded("INFO", "stream cancelled by consumer: ${e.causeChainSummary()}")
                cancel(consumerCancel)
            } else {
                recorded += Recorded("ERROR", "stream parse exception: ${e.causeChainSummary()}")
                cancel("Stream error", e)
            }
        }
        awaitClose { }
    }

    private fun providerStack(recorded: MutableList<Recorded>): Flow<String> = flow {
        // failOnSilentEmptyCompletion-shaped pass-through
        emitAll(providerFlow(recorded).let { outer -> flow { outer.collect { emit(it) } } })
    }

    private fun workerFrame(consumerThrows: Throwable): Pair<List<Recorded>, String> {
        val recorded = mutableListOf<Recorded>()
        var workerVerdict = "?"
        runBlocking {
            try {
                runBlocking {
                    withTimeoutOrNull(2_000L) {
                        providerStack(recorded).collect { throw consumerThrows }
                    }
                }
            } catch (t: Throwable) {
                workerVerdict = "${t is WorkerCancelled}"
            }
        }
        return recorded to workerVerdict
    }

    @Test
    fun `user cancel is not reported as a stream error`() {
        val (recorded, workerVerdict) = workerFrame(WorkerCancelled())
        assertEquals("exactly one line", 1, recorded.size)
        assertEquals("a user cancel must not be logged as an error", "INFO", recorded[0].level)
        assertFalse(
            "the line must not claim a parse exception: ${recorded[0].message}",
            recorded[0].message.contains("parse exception"),
        )
        assertEquals("the worker's cancel contract must still match", "true", workerVerdict)
    }

    @Test
    fun `a real consumer-side failure is still reported as a stream error`() {
        // The pre-fix production shape: a consumer that died of a real error
        // makes kotlinx wrap it in a CE *with* a cause. Must stay ERROR.
        val (recorded, _) = workerFrame(java.io.IOException("stream was reset: CANCEL"))
        assertEquals(1, recorded.size)
        assertEquals("a real failure must stay an error", "ERROR", recorded[0].level)
        assertTrue(
            "the real cause must appear in the line: ${recorded[0].message}",
            recorded[0].message.contains("stream was reset"),
        )
    }

    @Test
    fun `the enclosing job survives a caught consumer cancel`() {
        // Making the worker's cancel type a CancellationException must not poison
        // the coroutine that caught it — later suspending work has to keep running.
        var stillUsable = false
        runBlocking {
            try {
                runBlocking {
                    withTimeoutOrNull(2_000L) {
                        providerStack(mutableListOf()).collect { throw WorkerCancelled() }
                    }
                }
            } catch (_: Throwable) {
            }
            stillUsable = coroutineContext[Job]?.isActive == true
            delay(1) // a suspending call must still work
        }
        assertTrue("the enclosing job must remain active after a caught cancel", stillUsable)
    }

    @Test
    fun `a real error also keeps the enclosing job usable`() {
        var stillUsable = false
        runBlocking {
            try {
                runBlocking {
                    withTimeoutOrNull(2_000L) {
                        providerStack(mutableListOf()).collect { throw IllegalStateException("boom") }
                    }
                }
            } catch (_: Throwable) {
            }
            stillUsable = coroutineContext[Job]?.isActive == true
            delay(1)
        }
        assertTrue(stillUsable)
    }
}
