package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.gemini.GeminiProvider
import com.rikkaminis.app.provider.openai.OpenAIProvider
import com.rikkaminis.app.sandbox.offload.ModelExecutionCancellationRace
import com.rikkaminis.app.sandbox.offload.ModelExecutionStopException
import com.rikkaminis.app.sandbox.offload.nonStreamingFailureResult
import com.rikkaminis.app.sandbox.offload.withModelExecutionCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real provider + worker cancel watcher + HTTP socket, with fake credentials only. */
class WorkerNonStreamingHttpCancellationTest {
    private data class RequestSeen(val socket: Socket, val peerReadClosed: CountDownLatch = CountDownLatch(1))

    /** Minimal loopback HTTP peer. Records when the client really closes its socket. */
    private class ScriptedHttpPeer(
        private val response: (String, Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        private val stopped = AtomicBoolean(false)
        private val requests = java.util.concurrent.ConcurrentHashMap<String, RequestSeen>()
        private val acceptThread = Thread {
            while (!stopped.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread({ handle(socket) }, "a02-http-peer").apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }

        val port: Int get() = server.localPort

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 8_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                var remaining = contentLength
                val scratch = ByteArray(8192)
                while (remaining > 0) {
                    val n = input.read(scratch, 0, minOf(remaining, scratch.size))
                    if (n < 0) return
                    remaining -= n
                }
                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                val entry = RequestSeen(socket)
                requests[path] = entry
                response(path, socket)
                // For a response which intentionally stalls, this observes peer EOF after OkHttp Call.cancel().
                runCatching { input.read() }
                entry.peerReadClosed.countDown()
            } catch (_: Exception) {
                // A reset also proves the client tore down the transport.
                requests.values.firstOrNull { it.socket === socket }?.peerReadClosed?.countDown()
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.US_ASCII.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
            }
            return bytes.toString(Charsets.US_ASCII.name())
        }

        suspend fun awaitRequest(predicate: (String) -> Boolean): Pair<String, RequestSeen> = withTimeout(2_000L) {
            while (true) {
                requests.entries.firstOrNull { predicate(it.key) }?.let { return@withTimeout it.key to it.value }
                kotlinx.coroutines.delay(5L)
            }
            error("unreachable")
        }

        override fun close() {
            stopped.set(true)
            runCatching { server.close() }
            acceptThread.join(500L)
            requests.values.forEach { runCatching { it.socket.close() } }
        }

        companion object {
            fun writeResponse(socket: Socket, body: ByteArray, contentType: String = "application/json") {
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body)
                out.flush()
            }

            fun writePartialBody(socket: Socket, prefix: String, contentType: String) {
                val out = socket.getOutputStream()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\n" +
                    "Content-Length: 8192\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(prefix.toByteArray())
                out.flush()
            }
        }
    }

    private fun openAi(base: String, endpoint: String) = OpenAIProvider(
        apiKey = "test-key",
        model = LLMModel.gpt4oMini,
        basePath = base,
    ).apply { absoluteEndpointOverride = endpoint }

    private fun gemini(base: String) = GeminiProvider(
        apiKey = "test-key",
        model = LLMModel.gemini25Flash,
        basePath = base,
    )

    private fun sse(text: String): ByteArray =
        ("data: {\"choices\":[{\"delta\":{\"content\":\"$text\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n").toByteArray()

    private fun launchRun(
        scope: CoroutineScope,
        cancelFile: File,
        race: ModelExecutionCancellationRace,
        call: suspend () -> Any,
        publish: (Any) -> Unit = { error("cancelled request published") },
    ): Deferred<Any> = scope.async(Dispatchers.IO) {
        withModelExecutionCancellation(
            cancelFile = cancelFile,
            pollMs = 10L,
            cancellation = { ModelExecutionStopException() },
            race = race,
            block = { ensureBeforeDispatch ->
                ensureBeforeDispatch()
                call()
            },
            publish = publish,
        )
    }

    private suspend fun cancelAndVerify(
        request: Pair<String, RequestSeen>,
        cancelFile: File,
        race: ModelExecutionCancellationRace,
        job: Deferred<Any>,
    ) {
        cancelFile.createNewFile()
        val outcome = runCatching { withTimeout(1_500L) { job.await() } }.exceptionOrNull()
        assertTrue("explicit run stop, not timeout: $outcome", outcome is ModelExecutionStopException)
        assertTrue("run-local stop CAS must win", race.cancellationWon)
        assertTrue(
            "real HTTP peer must observe socket closure",
            request.second.peerReadClosed.await(1_500L, TimeUnit.MILLISECONDS),
        )
    }

    @Test
    fun `OpenAI send cancels while waiting for response headers`() = runBlocking {
        val server = ScriptedHttpPeer { _, _ -> Unit }
        val dir = kotlin.io.path.createTempDirectory("a02-oai-head-").toFile()
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val job = launchRun(this, cancel, race, {
            openAi("http://127.0.0.1:${server.port}", "/cancel")
                .sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello")), null, 64)
        })
        try {
            val request = server.awaitRequest { it == "/cancel" }
            cancelAndVerify(request, cancel, race, job)
        } finally {
            job.cancelAndJoin(); dir.deleteRecursively(); server.close()
        }
    }

    @Test
    fun `Gemini non-streaming cancels while response body is incomplete`() = runBlocking {
        val server = ScriptedHttpPeer { _, socket ->
            ScriptedHttpPeer.writePartialBody(socket, "{\"candidates\":[", "application/json")
        }
        val dir = kotlin.io.path.createTempDirectory("a02-gemini-body-").toFile()
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val job = launchRun(this, cancel, race, {
            gemini("http://127.0.0.1:${server.port}/v1beta")
                .sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello")), null, 64)
        })
        try {
            val request = server.awaitRequest { it.contains("generateContent") }
            cancelAndVerify(request, cancel, race, job)
        } finally {
            job.cancelAndJoin(); dir.deleteRecursively(); server.close()
        }
    }

    @Test
    fun `OpenAI passthrough response body is cancellable`() = runBlocking {
        val server = ScriptedHttpPeer { _, socket ->
            ScriptedHttpPeer.writePartialBody(socket, "partial", "application/octet-stream")
        }
        val dir = kotlin.io.path.createTempDirectory("a02-raw-body-").toFile()
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val job = launchRun(this, cancel, race, {
            openAi("http://127.0.0.1:${server.port}", "/raw")
                .rawPassthroughRequest("/raw", "GET", emptyMap(), null)
        })
        try {
            val request = server.awaitRequest { it == "/raw" }
            cancelAndVerify(request, cancel, race, job)
        } finally {
            job.cancelAndJoin(); dir.deleteRecursively(); server.close()
        }
    }

    @Test
    fun `OpenAI image main response body is cancellable`() = runBlocking {
        val server = ScriptedHttpPeer { _, socket ->
            ScriptedHttpPeer.writePartialBody(socket, "{\"data\":[", "application/json")
        }
        val dir = kotlin.io.path.createTempDirectory("a02-image-main-").toFile()
        val cancel = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val job = launchRun(this, cancel, race, {
            openAi("http://127.0.0.1:${server.port}", "/image").generateImage("image")
        })
        try {
            val request = server.awaitRequest { it == "/image" }
            cancelAndVerify(request, cancel, race, job)
        } finally {
            job.cancelAndJoin(); dir.deleteRecursively(); server.close()
        }
    }

    @Test
    fun `OpenAI image second hop cancellation does not stop healthy sibling`() = runBlocking {
        lateinit var server: ScriptedHttpPeer
        server = ScriptedHttpPeer { path, socket ->
            when (path) {
                "/image" -> ScriptedHttpPeer.writeResponse(
                    socket,
                    "{\"data\":[{\"url\":\"http://127.0.0.1:${server.port}/object\"}]}".toByteArray(),
                )
                "/healthy" -> ScriptedHttpPeer.writeResponse(socket, sse("healthy"), "text/event-stream")
                "/object" -> ScriptedHttpPeer.writePartialBody(socket, "partial", "image/png")
            }
        }
        val imageDir = kotlin.io.path.createTempDirectory("a02-image-hop-").toFile()
        val imageCancel = File(imageDir, "cancel")
        val imageRace = ModelExecutionCancellationRace()
        val imageProvider = openAi("http://127.0.0.1:${server.port}", "/image")
        val image = launchRun(this, imageCancel, imageRace, { imageProvider.generateImage("image") })

        val siblingDir = kotlin.io.path.createTempDirectory("a02-sibling-").toFile()
        val siblingCancel = File(siblingDir, "cancel")
        val siblingRace = ModelExecutionCancellationRace()
        val siblingProvider = openAi("http://127.0.0.1:${server.port}", "/healthy")
        val sibling = launchRun(this, siblingCancel, siblingRace, {
            siblingProvider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "keep")), null, 64)
        })
        try {
            val hop = server.awaitRequest { it == "/object" }
            server.awaitRequest { it == "/healthy" }
            cancelAndVerify(hop, imageCancel, imageRace, image)
            val result = withTimeout(2_000L) { sibling.await() } as com.rikkaminis.app.data.model.LLMResponse
            assertEquals("healthy", result.text)
            assertFalse("sibling's cancel marker must stay absent", siblingCancel.exists())
        } finally {
            image.cancelAndJoin(); sibling.cancelAndJoin()
            imageDir.deleteRecursively(); siblingDir.deleteRecursively(); server.close()
        }
    }

    @Test
    fun `ordinary OpenAI 2xx body reset remains model use failure without fake cancel ack`() = runBlocking {
        val server = ScriptedHttpPeer { _, socket ->
            ScriptedHttpPeer.writePartialBody(socket, "data: {truncated", "text/event-stream")
            // Let the client observe 2xx headers + a partial body, then reset the
            // connection before the declared Content-Length is satisfied.
            Thread.sleep(60L)
            socket.close()
        }
        val dir = kotlin.io.path.createTempDirectory("a02-oai-failure-").toFile()
        val race = ModelExecutionCancellationRace()
        val cancel = File(dir, "cancel")
        val cancelAck = File(dir, "cancel.ack")
        val provider = openAi("http://127.0.0.1:${server.port}", "/failure")
        val job = launchRun(this, cancel, race, {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "failure")), null, 64)
        })
        try {
            server.awaitRequest { it == "/failure" }
            val failure = runCatching { withTimeout(3_000L) { job.await() } }.exceptionOrNull()
            assertTrue("the real truncated-body read must fail", failure != null)
            assertFalse("without this run's marker the cancellation source did not win", race.cancellationWon)
            assertFalse("transport failure must not leave a cancel marker", cancel.exists())
            assertFalse("transport failure must not publish fake cancel.ack", cancelAck.exists())
            val envelope = JSONObject(nonStreamingFailureResult(failure!!, race, "imagesGenerations"))
            assertEquals("model_use_failed", envelope.getString("error"))
            assertTrue("provider error details must survive", envelope.getString("message").isNotBlank())
            assertEquals("imagesGenerations", envelope.getString("image_endpoint_resolved"))
        } finally {
            job.cancelAndJoin(); dir.deleteRecursively(); server.close()
        }
    }
}
