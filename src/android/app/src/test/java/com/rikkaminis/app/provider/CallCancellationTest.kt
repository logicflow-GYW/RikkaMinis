package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [W1118 D6-F05 / R5-F04] `executeOrCancel()` must actually cancel while the
 * blocking read is in flight — and must not touch a healthy call.
 *
 * The pre-fix hook was `currentCoroutineContext().job.invokeOnCompletion { … }`.
 * Completion handlers only run when a job COMPLETES, and a suspend body cannot
 * complete while it is parked inside the synchronous `execute()` — so the hook
 * could not fire during the very read it existed to interrupt. Cancelling the
 * caller therefore left the OkHttp thread blocked until the read timeout
 * (measured before this fix as `promptCancelCount=0`, see the [da1020 ISSUE-05]
 * probe notes in `provider/voice/VoiceProvider.kt`, which is why that one call
 * site had grown its own local watcher).
 *
 * Both arms are measured, not inferred:
 *  - cancel latency: the regression is "waits out the whole read timeout"
 *    (3 s here, 30 min on the generation streams). The bound is 1.5 s.
 *  - `call.isCanceled()`: only an explicit `cancel()` sets it, so this is what
 *    distinguishes "the socket was torn down" from "the read eventually failed
 *    on its own".
 *
 * The third test is the opposite direction: a call that completes normally must
 * keep its body readable — a watcher that fired on its own cleanup would close
 * the connection the caller is still reading.
 */
class CallCancellationTest {

    /** Accepts one connection and answers nothing: the classic wedged upstream. */
    private class SilentServer {
        val socket = ServerSocket(0)
        private val accepted = java.util.concurrent.atomic.AtomicReference<Socket?>(null)
        private val acceptor = thread(isDaemon = true) {
            runCatching { accepted.set(socket.accept()) }
        }

        fun port(): Int = socket.localPort

        fun close() {
            runCatching { accepted.get()?.close() }
            runCatching { socket.close() }
            acceptor.join(2_000L)
        }
    }

    /** Answers immediately with a tiny body, then holds the socket open. */
    private class EchoServer(private val body: String) {
        val socket = ServerSocket(0)
        private val acceptor = thread(isDaemon = true) {
            runCatching {
                val peer = socket.accept()
                val input = peer.getInputStream()
                // Drain the request headers so the client's write completes.
                var l = input.read()
                while (l >= 0) {
                    if (l == '\n'.code) break
                    l = input.read()
                }
                val bytes = body.toByteArray()
                val header = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n\r\n").toByteArray()
                peer.getOutputStream().write(header)
                peer.getOutputStream().write(bytes)
                peer.getOutputStream().flush()
                Thread.sleep(1_000L)
                peer.close()
            }
        }

        fun port(): Int = socket.localPort

        fun close() {
            runCatching { socket.close() }
            acceptor.join(2_000L)
        }
    }

    private val clients = mutableListOf<OkHttpClient>()

    private fun client(readTimeoutSeconds: Long): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()
            .also { clients += it }

    @After
    fun tearDown() {
        for (c in clients) runCatching {
            c.dispatcher.executorService.shutdown()
            c.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancelling the caller tears the blocked execute down without waiting for the read timeout`() = runBlocking {
        val server = SilentServer()
        val client = client(readTimeoutSeconds = 3)
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.port()}/models").build())
        try {
            val job = launch(Dispatchers.IO) {
                try {
                    call.executeOrCancel().close()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A cancellation normally surfaces as IOException("Canceled");
                    // executeOrCancel rethrows it as CancellationException, and the
                    // job's own cancellation state is what the test reads below.
                }
            }
            // Let the request reach the (silent) server so the read is really blocked.
            delay(300L)
            assertFalse("precondition: the call must not be finished yet", call.isCanceled())

            val t0 = System.nanoTime()
            job.cancelAndJoin()
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L

            assertTrue(
                "expected the blocked execute to be torn down promptly (<1500ms), took ${elapsedMs}ms",
                elapsedMs < 1_500L,
            )
            assertTrue("the Call itself must be cancelled, not left to time out", call.isCanceled())
        } finally {
            server.close()
        }
    }

    @Test
    fun `a normal response body survives the watcher cleanup`() = runBlocking {
        val body = "{\"data\":[{\"id\":\"m1\"}]}"
        val server = EchoServer(body)
        val client = client(readTimeoutSeconds = 5)
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.port()}/models").build())
        try {
            val text = call.executeOrCancel().use { response -> response.body?.string() }
            assertEquals("the caller must still be able to read the body", body, text)
            assertFalse("a completed call must not be cancelled by the watcher cleanup", call.isCanceled())
        } finally {
            server.close()
        }
    }
}
