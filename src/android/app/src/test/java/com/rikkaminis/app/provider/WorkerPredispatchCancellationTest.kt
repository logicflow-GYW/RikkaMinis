package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.gemini.GeminiProvider
import com.rikkaminis.app.provider.openai.OpenAIProvider
import com.rikkaminis.app.sandbox.offload.ModelExecutionCancellationRace
import com.rikkaminis.app.sandbox.offload.ModelExecutionStopException
import com.rikkaminis.app.sandbox.offload.withModelExecutionCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real OpenAI image retry/download and the production run-local cancellation policy. */
class WorkerPredispatchCancellationTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)

    private fun provider(server: MockWebServer, afterBody: ((Call) -> Unit)? = null): OpenAIProvider =
        OpenAIProvider("fake-test-key", LLMModel.gpt4oMini, server.url("/").toString().trimEnd('/')).also { p ->
            if (afterBody != null) {
                // Test-only call event injection. Do not add a production hook or provider state.
                val field = OpenAIProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
                val original = field.get(p) as OkHttpClient
                field.set(p, original.newBuilder().eventListener(object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) { afterBody(call) }
                }).build())
            }
        }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun rejected() = json("""{"error":{"message":"response_format b64_json unsupported"}}""", 400)
    private fun emptyImage() = json("""{"data":[]}""")

    private suspend fun <T> run(
        marker: File,
        race: ModelExecutionCancellationRace,
        published: AtomicInteger = AtomicInteger(),
        block: suspend () -> T,
    ): T = withModelExecutionCancellation(
        marker,
        // The first watcher poll runs before block; no further poll can explain these tests.
        60_000L,
        { ModelExecutionStopException() },
        race,
        block = { ensure -> ensure(); block() },
        publish = { published.incrementAndGet() },
    )

    private suspend fun stopped(block: suspend () -> Any?): Throwable {
        val failure = try { withTimeout(5_000L) { block() }; null } catch (t: Throwable) { t }
        assertTrue("explicit run stop required; a deadline is NOT success: $failure", failure is ModelExecutionStopException)
        return failure!!
    }

    @Test
    fun `marker after real image 400 body prevents retry before watcher poll`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(rejected()); enqueue(emptyImage()) }
        val dir = kotlin.io.path.createTempDirectory("a02-retry-").toFile()
        val marker = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val published = AtomicInteger()
        val event = AtomicBoolean()
        val p = provider(server) { if (event.compareAndSet(false, true)) marker.createNewFile() }
        try {
            stopped { run(marker, race, published) { p.generateImage("cancel retry") } }
            assertTrue("first response fully consumed", event.get())
            assertEquals("marker was present before retry execute", 1, server.requestCount)
            assertTrue(race.cancellationWon)
            assertEquals(0, published.get())
            println("RETRY marker-before-execute requests=${server.requestCount} ownCancel=${race.cancellationWon}")
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `marker after real image URL response prevents download next hop`() = runBlocking {
        val download = MockWebServer().apply { start(); enqueue(MockResponse().setBody(Buffer().write(png))) }
        val server = MockWebServer().apply {
            start(); enqueue(json("""{"data":[{"url":"${download.url("/object.png")}"}]}"""))
        }
        val dir = kotlin.io.path.createTempDirectory("a02-next-hop-").toFile()
        val marker = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        val event = AtomicBoolean()
        val p = provider(server) { if (event.compareAndSet(false, true)) marker.createNewFile() }
        try {
            stopped { run(marker, race) { p.generateImage("cancel download") } }
            assertTrue(event.get())
            assertEquals(1, server.requestCount)
            assertEquals("download server must never be visited", 0, download.requestCount)
            assertTrue(race.cancellationWon)
            println("NEXT_HOP generation=${server.requestCount} download=${download.requestCount}")
        } finally { server.shutdown(); download.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `no marker preserves image 400 retry and wire format`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(rejected()); enqueue(emptyImage()) }
        val dir = kotlin.io.path.createTempDirectory("a02-retry-ok-").toFile()
        val race = ModelExecutionCancellationRace()
        try {
            val response = withTimeout(5_000L) { run(File(dir, "cancel"), race) { provider(server).generateImage("healthy") } }
            assertEquals("end_turn", response.stopReason)
            assertEquals(2, server.requestCount)
            val first = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            val second = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("b64_json", first.getString("response_format"))
            assertFalse(second.has("response_format"))
            assertEquals(first.getString("prompt"), second.getString("prompt"))
            assertTrue(race.completionWon)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `no marker preserves generation followed by image download`() = runBlocking {
        val download = MockWebServer().apply { start(); enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))) }
        val server = MockWebServer().apply { start(); enqueue(json("""{"data":[{"url":"${download.url("/image.png")}"}]}""")) }
        val dir = kotlin.io.path.createTempDirectory("a02-hop-ok-").toFile()
        val race = ModelExecutionCancellationRace()
        try {
            val response = withTimeout(5_000L) { run(File(dir, "cancel"), race) { provider(server).generateImage("healthy") } }
            assertArrayEquals(png, response.mediaAttachments.single().data)
            assertEquals(1, server.requestCount)
            assertEquals(1, download.requestCount)
            assertTrue(race.completionWon)
        } finally { server.shutdown(); download.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `cancelled image run does not affect concurrent sibling sharing provider`() = runBlocking {
        val healthySeen = CountDownLatch(1)
        val aRequests = AtomicInteger()
        val bRequests = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val prompt = JSONObject(request.body.readUtf8()).getString("prompt")
                    return if (prompt == "stop") {
                        if (aRequests.incrementAndGet() == 1) rejected() else emptyImage()
                    } else {
                        bRequests.incrementAndGet(); healthySeen.countDown(); emptyImage()
                    }
                }
            }
            start()
        }
        val dir = kotlin.io.path.createTempDirectory("a02-local-runs-").toFile()
        val markerA = File(dir, "cancel-A")
        val raceA = ModelExecutionCancellationRace()
        val raceB = ModelExecutionCancellationRace()
        val p = provider(server) { call ->
            val buffer = Buffer(); call.request().body!!.writeTo(buffer)
            if (JSONObject(buffer.readUtf8()).getString("prompt") == "stop") {
                check(healthySeen.await(3, TimeUnit.SECONDS)) { "healthy sibling never reached HTTP" }
                markerA.createNewFile()
            }
        }
        try {
            supervisorScope {
                val a = async(Dispatchers.IO) { run(markerA, raceA) { p.generateImage("stop") } }
                val b = async(Dispatchers.IO) { run(File(dir, "cancel-B"), raceB) { p.generateImage("healthy") } }
                // Finish the healthy run before examining A, so teardown cannot manufacture success.
                assertEquals("end_turn", withTimeout(5_000L) { b.await() }.stopReason)
                stopped { a.await() }
                assertEquals(1, aRequests.get())
                assertEquals(1, bRequests.get())
                assertTrue(raceA.cancellationWon)
                assertTrue(raceB.completionWon)
                assertFalse(raceB.cancellationWon)
            }
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `guard scope exit leaves later unguarded image call unchanged`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(rejected()); enqueue(emptyImage()); enqueue(emptyImage()) }
        val dir = kotlin.io.path.createTempDirectory("a02-guard-exit-").toFile()
        val marker = File(dir, "cancel")
        val first = AtomicBoolean()
        val p = provider(server) { if (first.compareAndSet(false, true)) marker.createNewFile() }
        try {
            stopped { run(marker, ModelExecutionCancellationRace()) { p.generateImage("stop") } }
            // Marker still exists, same coroutine + same provider, but no worker guard now.
            assertTrue(marker.isFile)
            assertEquals("end_turn", withTimeout(5_000L) { p.generateImage("direct UI-style caller") }.stopReason)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `raw first request synchronously checks marker created after run admission`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(json("{}")) }
        val dir = kotlin.io.path.createTempDirectory("a02-raw-entry-").toFile()
        val marker = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        try {
            stopped { run(marker, race) {
                marker.createNewFile()
                provider(server).rawPassthroughRequest(endpoint = "/raw", method = "GET", headers = emptyMap(), bodyObject = null)
            } }
            assertEquals("the first raw call must not execute", 0, server.requestCount)
            assertTrue(race.cancellationWon)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `Gemini ordinary first send checks marker created after run admission`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(json("{}")) }
        val dir = kotlin.io.path.createTempDirectory("a02-ordinary-entry-").toFile()
        val marker = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        try {
            stopped { run(marker, race) {
                marker.createNewFile()
                GeminiProvider("fake-test-key", LLMModel.gemini25Flash, server.url("/v1beta").toString())
                    .sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "hello")), null, 64)
            } }
            assertEquals(0, server.requestCount)
            assertTrue(race.cancellationWon)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun `unguarded direct caller retains retry and URL download compatibility`() = runBlocking {
        val download = MockWebServer().apply { start(); enqueue(MockResponse().setBody(Buffer().write(png))) }
        val server = MockWebServer().apply {
            start(); enqueue(rejected()); enqueue(json("""{"data":[{"url":"${download.url("/image.png")}"}]}"""))
        }
        try {
            val result = withTimeout(5_000L) { provider(server).generateImage("direct") }
            assertArrayEquals(png, result.mediaAttachments.single().data)
            assertEquals(2, server.requestCount)
            assertEquals(1, download.requestCount)
        } finally { server.shutdown(); download.shutdown() }
    }

    @Test
    fun `headers only bridge shares the same run local first dispatch check`() = runBlocking {
        val server = MockWebServer().apply { start(); enqueue(json("{}")) }
        val dir = kotlin.io.path.createTempDirectory("a02-headers-entry-").toFile()
        val marker = File(dir, "cancel")
        val race = ModelExecutionCancellationRace()
        try {
            stopped { run(marker, race) {
                marker.createNewFile()
                OkHttpClient().newCall(Request.Builder().url(server.url("/first")).build()).executeOrCancel().use { it.code }
            } }
            assertEquals(0, server.requestCount)
            assertTrue(race.cancellationWon)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
