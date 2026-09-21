package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [fix/zero-chunk-cancel] Regression test for the cancellation BRIDGE, on a real
 * socket.
 *
 * The sibling [ProviderStreamCancellationFlowTest] drives a synthetic flow with
 * no socket, so it can never exercise the bridge: the bridge exists precisely to
 * break a BLOCKING read, and its `call.cancel()` makes that read fail with
 * `SocketException: Socket closed` — an exception that says "the socket closed",
 * not *why*. Without carrying the bridge's own cancellation cause across, that
 * exception re-enters the catch looking exactly like a transport failure and the
 * user's cancel is logged as `stream parse exception` again (F-177's bug, back
 * through a new door).
 *
 * Both arms run here, so the test also proves it is not a dummy:
 *   - bridged   (production shape)  -> INFO, and the cancel still lands fast
 *   - unbridged (pre-bridge shape)  -> must NOT look like a clean cancel
 */
class OpenAIStreamCancelBridgeTest {

    private class WorkerCancelled : CancellationException("cancelled")

    private class Recorded(val level: String, val message: String)

    /**
     * A real SSE server that answers with headers and then streams rows forever,
     * never closing and never sending a terminator — so the client's blocking
     * `readLine()` parks mid-stream. MockWebServer cannot express this: a finite
     * body ends and the read returns null, which makes the flow finish on its
     * own and hides the very behaviour under test.
     */
    private class StreamingSseServer {
        private val server = ServerSocket(0)
        val port: Int get() = server.localPort

        init {
            Thread {
                try {
                    while (!server.isClosed) {
                        val s = server.accept()
                        Thread { serve(s) }.apply { isDaemon = true }.start()
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(socket: Socket) {
            runCatching {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                // Consume the request headers only; waiting for EOF would block
                // forever because the client holds the connection open.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n".toByteArray())
                out.flush()
                var i = 0
                while (true) {
                    out.write("data: {\"n\":$i}\n\n".toByteArray())
                    out.flush()
                    i++
                    Thread.sleep(40L)
                }
            }
        }

        fun close() {
            runCatching { server.close() }
        }
    }

    /**
     * Transcription of the patched `OpenAIProvider.rawStreamMessage` topology:
     * bridge registered BEFORE the blocking read, its cancellation cause
     * captured before `call.cancel()`, and that cause offered to the same
     * predicate F-177 uses.
     */
    private fun providerFlow(
        client: OkHttpClient,
        url: String,
        bridged: Boolean,
        recorded: MutableList<Recorded>,
    ): Flow<String> = callbackFlow {
        val call: Call = client.newCall(Request.Builder().url(url).build())
        val streamCompleted = AtomicBoolean(false)
        val bridgeCancelCause = AtomicReference<Throwable?>(null)
        val cancelBridge = if (bridged) launch {
            // `catch`, not `finally`: a cancelled coroutine's CE is its
            // cancellation cause, and this is the only public way to read it.
            val cause: Throwable? = try {
                awaitCancellation()
                null
            } catch (e: CancellationException) {
                e
            }
            if (!streamCompleted.get()) {
                bridgeCancelCause.set(cause)
                try { call.cancel() } catch (_: Exception) {
                }
            }
        } else null
        try {
            val response = call.execute()
            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotEmpty()) send(line)
            }
        } catch (e: Exception) {
            val consumerCancel = e.asConsumerSideCancellation()
                ?: bridgeCancelCause.get()?.asConsumerSideCancellation()
            if (consumerCancel != null) {
                recorded += Recorded("INFO", "stream cancelled by consumer: ${e.causeChainSummary()}")
                cancel(consumerCancel)
            } else {
                recorded += Recorded("ERROR", "stream parse exception: ${e.causeChainSummary()}")
                cancel("Stream error", e)
            }
        } finally {
            streamCompleted.set(true)
        }
        cancelBridge?.cancel()
        awaitClose { try { call.cancel() } catch (_: Exception) {} }
    }.flowOn(Dispatchers.IO)

    /** Runs the worker frame: a watcher cancels the collecting job, as production does. */
    private fun workerFrame(
        bridged: Boolean,
        cancelAfterMs: Long = 300L,
        budgetMs: Long = 20_000L,
    ): Pair<List<Recorded>, Long> {
        val server = StreamingSseServer()
        val client = OkHttpClient.Builder()
            .readTimeout(600, TimeUnit.SECONDS)
            .build()
        val recorded = mutableListOf<Recorded>()
        val t0 = System.currentTimeMillis()
        try {
            runBlocking {
                runBlocking {
                    val collectJob = coroutineContext[Job]!!
                    val watcher = launch {
                        delay(cancelAfterMs)
                        collectJob.cancel(WorkerCancelled())
                    }
                    try {
                        withTimeoutOrNull(budgetMs) {
                            providerFlow(client, "http://127.0.0.1:${server.port}/v1/chat", bridged, recorded)
                                .collect { }
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        val elapsed = System.currentTimeMillis() - t0
        server.close()
        return recorded to elapsed
    }

    @Test
    fun `bridged cancel is logged as a consumer cancel, not a parse error`() {
        val (recorded, elapsed) = workerFrame(bridged = true)
        assertEquals("exactly one line", 1, recorded.size)
        assertEquals(
            "the bridge's own SocketException must not be reported as a parse error: ${recorded[0].message}",
            "INFO",
            recorded[0].level,
        )
        assertTrue("the cancel must still land promptly, took ${elapsed}ms", elapsed < 5_000L)
    }

    @Test
    fun `without the bridge the blocking read is not interruptible`() {
        // Pre-bridge shape: `call.cancel()` is only reached via awaitClose,
        // which the wedged read never gets to. The flow therefore runs out its
        // budget instead of stopping at the cancel deadline. Recorded so a
        // future change that drops the bridge cannot look green.
        //
        // Only the TIMING is asserted: once the budget finally expires and the
        // scope unwinds, the pre-existing F-177 predicate legitimately logs a
        // consumer cancel for the teardown that follows — that line is not what
        // this test is about, and asserting its absence would be asserting the
        // wrong thing.
        val (_, elapsed) = workerFrame(bridged = false, cancelAfterMs = 300L, budgetMs = 3_000L)
        assertTrue(
            "without the bridge the cancel must not complete promptly (took ${elapsed}ms)",
            elapsed >= 2_000L,
        )
    }
}
