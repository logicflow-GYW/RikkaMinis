package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real sockets pin cancellation timing; owned responses pin all cleanup paths. */
class CallResponseConsumptionTest {
    private val request = Request.Builder().url("http://127.0.0.1/models").build()

    private class OwnedBody(private val failRead: Boolean = false) : ResponseBody() {
        val closes = AtomicInteger()
        private val buffered = object : Source {
            private val data = Buffer().writeUtf8("{}")
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failRead) throw IOException("body transport failed")
                return data.read(sink, byteCount)
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closes.incrementAndGet() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = buffered
    }

    private class OwnedCall(private val response: Response) : Call {
        var cancelled = false
        override fun request(): Request = response.request
        override fun execute(): Response = response
        override fun enqueue(responseCallback: Callback) = error("synchronous fixture")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = true
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = OwnedCall(response)
    }

    private fun owned(body: OwnedBody): OwnedCall = OwnedCall(
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body).build()
    )

    @Test
    fun cancelAfterHeadersInterruptsBodyAndClosesResponse() = runBlocking {
        val closed = AtomicInteger()
        val client = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body!!
                val source = object : ForwardingSource(body.source()) {
                    override fun close() { closed.incrementAndGet(); super.close() }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            }.build()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"data\":[]}")
                .setBodyDelay(1800, TimeUnit.MILLISECONDS))
            val call = client.newCall(Request.Builder().url(server.url("/models")).build())
            val headers = CompletableDeferred<Unit>()
            val caught = AtomicReference<Throwable?>()
            val readFinished = AtomicBoolean()
            val job = launch(Dispatchers.IO) {
                try {
                    call.executeOrCancel() { response ->
                        headers.complete(Unit)
                        response.body!!.string()
                        readFinished.set(true)
                    }
                } catch (e: Throwable) { caught.set(e); if (e is CancellationException) throw e }
            }
            withTimeout(3000) { headers.await() }
            delay(50)
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("body cancellation took ${elapsed}ms", elapsed < 900)
            assertTrue("Call.cancel must tear down the body read", call.isCanceled())
            assertTrue("IO cancellation must be rethrown as CE", caught.get() is CancellationException)
            assertFalse("cancelled body must not reach success", readFinished.get())
            assertTrue("cancelled Response must be closed", closed.get() > 0)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun cancelBeforeHeadersRethrowsCancellation() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient.Builder().readTimeout(4, TimeUnit.SECONDS).build()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{}").setHeadersDelay(1800, TimeUnit.MILLISECONDS))
            val call = client.newCall(Request.Builder().url(server.url("/models")).build())
            val caught = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.IO) {
                try { call.executeOrCancel() { it.body!!.string() } }
                catch (e: Throwable) { caught.set(e); if (e is CancellationException) throw e }
            }
            assertNotNull("request must be in flight", server.takeRequest(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("header cancellation took ${elapsed}ms", elapsed < 900)
            assertTrue(call.isCanceled())
            assertTrue(caught.get() is CancellationException)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun tinyBodyCompletesWithoutCancellingHealthyCall() = runBlocking {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))
            val call = client.newCall(Request.Builder().url(server.url("/models")).build())
            assertEquals("{\"data\":[]}", call.executeOrCancel() { it.body!!.string() })
            assertFalse("watcher cleanup must not cancel a healthy Call", call.isCanceled())
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun successfulConsumerClosesEvenUnreadResponse() = runBlocking {
        val body = OwnedBody()
        val call = owned(body)
        assertEquals(7, call.executeOrCancel() { 7 })
        assertEquals(1, body.closes.get())
        assertFalse(call.cancelled)
    }

    @Test
    fun parseFailureClosesResponseAndPreservesFailure() = runBlocking {
        val body = OwnedBody()
        val failure = IllegalArgumentException("malformed models")
        try {
            owned(body).executeOrCancel<Unit> { throw failure }
            fail("parse failure swallowed")
        } catch (e: IllegalArgumentException) {
            // Coroutine debug stack recovery may copy exceptions at scope boundaries.
            assertEquals("malformed models", e.message)
        }
        assertEquals(1, body.closes.get())
    }

    @Test
    fun bodyNetworkFailureClosesResponseAndStaysIOException() = runBlocking {
        val body = OwnedBody(failRead = true)
        val call = owned(body)
        try {
            call.executeOrCancel() { it.body!!.string() }
            fail("network failure swallowed")
        } catch (e: IOException) { assertEquals("body transport failed", e.message) }
        assertTrue(body.closes.get() > 0)
        assertFalse(call.cancelled)
    }

    @Test
    fun consumerCancellationIsNotSwallowedAndClosesResponse() = runBlocking {
        val body = OwnedBody()
        val failure = CancellationException("consumer cancelled")
        try {
            owned(body).executeOrCancel<Unit> { throw failure }
            fail("consumer CE swallowed")
        } catch (e: CancellationException) {
            assertEquals("consumer cancelled", e.message)
        }
        assertEquals(1, body.closes.get())
    }
}
