package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real provider + real localhost sockets. No production constructor/test API is added. */
class AnthropicNonStreamingCancellationTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AnthropicProvider
    private lateinit var client: OkHttpClient
    private val calls = CopyOnWriteArrayList<ObservedCall>()
    private val byCall = ConcurrentHashMap<Call, ObservedCall>()

    private class ObservedCall(val call: Call) : EventListener() {
        val headers = CountDownLatch(1)
        val blockedBodyRead = CountDownLatch(1)
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val cancellations = AtomicInteger()
        val networkFailures = AtomicInteger()
        override fun responseHeadersEnd(call: Call, response: Response) { headers.countDown() }
        override fun canceled(call: Call) { cancellations.incrementAndGet() }
        override fun callFailed(call: Call, ioe: IOException) { networkFailures.incrementAndGet() }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = AnthropicProvider(
            apiKey = "synthetic-localhost-only",
            model = LLMModel.claudeHaiku45,
            basePath = server.url("/").toString().trimEnd('/'),
        )
        // Preserve the real provider/client path; reflection installs only test observers
        // and an isolated pool/dispatcher, not a fake Call or an alternate implementation.
        val field = AnthropicProvider::class.java.getDeclaredField("client").apply { isAccessible = true }
        val realClient = field.get(provider) as OkHttpClient
        client = realClient.newBuilder()
            .connectionPool(ConnectionPool())
            .dispatcher(Dispatcher())
            .eventListenerFactory { call ->
                ObservedCall(call).also { calls.add(it); byCall[call] = it }
            }
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addInterceptor response
                val observed = byCall.getValue(chain.call())
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        // The stalled fixture returns one short chunk, then no more
                        // bytes. The second delegate read is the actual body wait.
                        if (observed.reads.incrementAndGet() >= 2) observed.blockedBodyRead.countDown()
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        observed.closes.incrementAndGet()
                        super.close()
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }
            .build()
        field.set(provider, client)
    }

    @After
    fun tearDown() {
        // Cleanup cancellation is deliberately AFTER every observation/assertion.
        // It must never be counted as proof that the caller's Job cancelled a Call.
        client.dispatcher.cancelAll()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun user(text: String = "hi") = listOf(LLMMessage(LLMMessage.Role.USER, text))
    private fun ok() = MockResponse().setBody(OK_BODY)
    private fun awaitStage(latch: CountDownLatch, name: String) {
        assertTrue("did not reach $name", latch.await(3, TimeUnit.SECONDS))
    }
    private fun closedWithoutCancel(observed: ObservedCall) {
        assertTrue("Response body must close on every exit", observed.closes.get() > 0)
        assertFalse("normal/IO/verdict cleanup is not user cancellation", observed.call.isCanceled())
        assertEquals(0, observed.cancellations.get())
    }

    @Test
    fun callerCancellationBeforeHeadersCancelsRealCallAndSettlesWithHealthySibling() = cancellationCase(false)

    @Test
    fun callerCancellationDuringBodyCancelsRealCallAndClosesResponseWithHealthySibling() = cancellationCase(true)

    private fun cancellationCase(duringBody: Boolean) = runBlocking {
        val slowRequested = CountDownLatch(1)
        val releaseSlowHeaders = CountDownLatch(1)
        val siblingRequested = CountDownLatch(1)
        val releaseSibling = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.body.readUtf8().contains("slow")) {
                    slowRequested.countDown()
                    if (duringBody) {
                        // Real headers + partial JSON, with a longer declared body.
                        // KEEP_OPEN stalls the socket (not a sleeping test coroutine)
                        // until cancellation or the production client's read timeout.
                        MockResponse().setBody(OK_BODY.take(12))
                            .setHeader("Content-Length", OK_BODY.toByteArray().size)
                            .setSocketPolicy(SocketPolicy.KEEP_OPEN)
                    } else {
                        check(releaseSlowHeaders.await(10, TimeUnit.SECONDS)) { "header fixture deadline" }
                        ok()
                    }
                } else {
                    siblingRequested.countDown()
                    check(releaseSibling.await(10, TimeUnit.SECONDS)) { "sibling fixture deadline" }
                    ok()
                }
            }
        }
        val published = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        var slow: Job? = null
        try {
            slow = launch(Dispatchers.Default) {
                try {
                    provider.sendMessage(user("slow"), null, 100)
                    published.set(true)
                } catch (e: Throwable) {
                    failure.set(e)
                    if (e is CancellationException) throw e
                }
            }
            awaitStage(slowRequested, "server request receipt")
            val target = calls.single()
            if (duringBody) {
                awaitStage(target.headers, "real response headers")
                awaitStage(target.blockedBodyRead, "second body read on the real socket")
            } else {
                assertEquals("headers must still be withheld", 1L, target.headers.count)
            }
            val sibling = async(Dispatchers.Default) { provider.sendMessage(user("healthy"), null, 100) }
            awaitStage(siblingRequested, "concurrent sibling request")
            val healthyCall = calls.single { it !== target }
            val start = System.nanoTime()
            slow.cancel(CancellationException("caller stopped this request"))
            // A timeout is a FAILURE, not an arbitrary CE accepted as success.
            val settled = withTimeoutOrNull(1500) { slow.join(); true } == true
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            val callCanceled = target.call.isCanceled()
            val cancelEvents = target.cancellations.get()
            val bodyCloses = target.closes.get()
            println("A02_ANTHROPIC phase=${if (duringBody) "body" else "headers"} " +
                "settled=$settled elapsedMs=$elapsedMs callCanceled=$callCanceled " +
                "cancelEvents=$cancelEvents bodyCloses=$bodyCloses " +
                "failure=${failure.get()?.javaClass?.simpleName} published=${published.get()}")
            // Finish the healthy sibling before asserting the captured slow-call
            // verdict: a deliberately failing old implementation must not have
            // its assertion replaced by cleanup-induced sibling IOException.
            assertFalse("a different in-flight Call must survive", healthyCall.call.isCanceled())
            releaseSibling.countDown()
            val result = withTimeout(3000) { sibling.await() }
            assertEquals("ok", result.text)
            closedWithoutCancel(healthyCall)
            assertTrue("caller cancellation missed 1500ms deadline: settled=$settled canceled=$callCanceled events=$cancelEvents", settled)
            assertTrue("must observe the actual Call, not only await/Job CE", callCanceled)
            assertEquals("exactly one real cancellation event before cleanup", 1, cancelEvents)
            assertTrue("cancelled IO must be a CancellationException", failure.get() is CancellationException)
            assertFalse("cancelled call must never publish a success", published.get())
            if (duringBody) assertTrue("body Response must close before settle", bodyCloses > 0)
        } finally {
            releaseSlowHeaders.countDown()
            releaseSibling.countDown()
            // Bound old/mutated implementations too: forcibly tear down sockets
            // only after the measured deadline and verdict have been captured.
            calls.forEach { it.call.cancel() }
            slow?.cancel()
            withTimeout(3000) { slow?.join() }
        }
    }

    @Test
    fun ordinaryBodyPreservesTextUsageStopReasonAndClosesWithoutCancel() = runBlocking {
        server.enqueue(ok())
        val response = provider.sendMessage(user(), null, 100)
        assertEquals("ok", response.text)
        assertEquals("end_turn", response.stopReason)
        assertEquals(3, response.usage?.inputTokens)
        assertEquals(1, response.usage?.outputTokens)
        closedWithoutCancel(calls.single())
    }

    @Test
    fun inBand200ErrorStillMapsVerdictAndClosesWithoutCancel() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"type":"error","error":{"type":"rate_limit_error","message":"quota"}}"""))
        try {
            provider.sendMessage(user(), null, 100)
            fail("in-band API error must not become a success")
        } catch (_: LLMError.RateLimited) { }
        closedWithoutCancel(calls.single())
    }

    @Test
    fun httpAuthenticationFailureKeepsApiClassificationAndClosesWithoutCancel() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody(
            """{"type":"error","error":{"type":"authentication_error","message":"bad key"}}"""))
        try {
            provider.sendMessage(user(), null, 100)
            fail("HTTP authentication failure must propagate")
        } catch (_: LLMError.InvalidApiKey) { }
        closedWithoutCancel(calls.single())
    }

    @Test
    fun ordinaryBodyNetworkFailureRemainsIOExceptionNotUserCancellationAndCloses() = runBlocking {
        server.enqueue(ok().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            provider.sendMessage(user(), null, 100)
            fail("truncated network response must fail")
        } catch (e: Exception) {
            assertTrue("ordinary network error became ${e.javaClass.name}", e is IOException)
            assertFalse(e is CancellationException)
        }
        val observed = calls.single()
        assertTrue("real socket failure must be observed", observed.networkFailures.get() > 0)
        closedWithoutCancel(observed)
    }

    @Test
    fun malformedBodyStillFailsParsingAndClosesWithoutCancel() = runBlocking {
        server.enqueue(MockResponse().setBody("{not-json"))
        try {
            provider.sendMessage(user(), null, 100)
            fail("malformed JSON must not become a success")
        } catch (_: org.json.JSONException) { }
        closedWithoutCancel(calls.single())
    }

    companion object {
        private const val OK_BODY = """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}"""
    }
}
