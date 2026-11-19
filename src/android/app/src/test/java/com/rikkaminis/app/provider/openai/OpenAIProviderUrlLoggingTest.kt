package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.logging.LogWriteQueue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** F-09: exercise the real log call sites; only loopback requests and fake credentials. */
class OpenAIProviderUrlLoggingTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        provider = OpenAIProvider(apiKey = "SECRET123", basePath = server.url("/v1").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stream request diagnostic masks the query key`() = runBlocking {
        provider.absoluteEndpointOverride = "/v1/chat/completions?key=SECRET123&relay=local"
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ),
        )

        val logs = captureLogs {
            provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 128).toList()
        }

        assertEquals("/v1/chat/completions?key=SECRET123&relay=local", requestPath())
        assertRedactedKey(logs, "[T321] → REQ url=", "key=[redacted:9]&relay=local")
    }

    @Test
    fun `raw passthrough diagnostic masks the query key`() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))

        val logs = captureLogs {
            val result = provider.rawPassthroughRequest("/passthrough?key=SECRET123", "GET", emptyMap(), null)
            assertEquals(200, result.status)
        }

        assertEquals("/passthrough?key=SECRET123", requestPath())
        assertRedactedKey(logs, "[ModelUseRoute] route=raw-passthrough", "key=[redacted:9]")
    }

    @Test
    fun `image generation diagnostic masks the query key`() = runBlocking {
        provider.absoluteEndpointOverride = "/v1/images/generations?key=SECRET123"
        enqueueImage("/object.png")
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("test image"))

        val logs = captureLogs {
            assertEquals(1, provider.generateImage("a red square").mediaAttachments.size)
        }

        assertEquals("/v1/images/generations?key=SECRET123", requestPath())
        assertEquals("/object.png", requestPath())
        assertRedactedKey(logs, "[ModelUseRoute] → images/generations url=", "key=[redacted:9]")
    }

    @Test
    fun `failed signed image download keeps its signature out of the logs`() = runBlocking {
        enqueueImage("/object.png?X-Amz-Signature=SECRET123")
        server.enqueue(
            MockResponse().setBody("partial image bytes").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val logs = captureLogs { expectFailedImage() }

        assertEquals("/v1/images/generations", requestPath())
        assertEquals("/object.png?X-Amz-Signature=SECRET123", requestPath())
        val warning = logs.single { it.contains("[ModelUseRoute] failed to download image from") }
        assertTrue(warning.contains("item=0"))
        assertFalse("the download diagnostic must not include signing parameters", warning.contains("X-Amz-Signature"))
        assertFalse("neither provider nor network trace may log the signature", logs.any { it.contains("SECRET123") })
    }

    @Test
    fun `image download rejection does not log echoed signing fields`() = runBlocking {
        enqueueImage("/object.png?sig=SECRET123")
        server.enqueue(MockResponse().setResponseCode(403).setBody("Expired: sig=SECRET123"))

        val logs = captureLogs { expectFailedImage() }

        assertEquals("/v1/images/generations", requestPath())
        assertEquals("/object.png?sig=SECRET123", requestPath())
        assertTrue(logs.any { it.contains("image download HTTP 403 skipped item=0") })
        assertFalse(logs.any { it.contains("SECRET123") })
    }

    private fun enqueueImage(path: String) {
        val data = JSONArray().put(JSONObject().put("url", server.url(path).toString()))
        server.enqueue(MockResponse().setBody(JSONObject().put("data", data).toString()))
    }

    private suspend fun expectFailedImage() {
        try {
            provider.generateImage("a red square")
            throw AssertionError("a failed download must not return an image")
        } catch (_: LLMError.ProviderError) {
            // Expected at the image verdict after the download fails.
        }
    }

    private fun requestPath(): String? = checkNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path

    private fun assertRedactedKey(logs: List<String>, marker: String, expected: String) {
        val line = logs.single { it.contains(marker) }
        assertTrue(line.contains(expected))
        assertFalse(logs.any { it.contains("SECRET123") })
    }

    // Capture the actual file-bound lines without starting logcat or a drain
    // thread; AppLogger already uses this queue for its persistent log sink.
    private suspend fun captureLogs(block: suspend () -> Unit): List<String> {
        val enabled = AppLogger::class.java.getDeclaredField("enabled").apply { isAccessible = true }
        val writer = AppLogger::class.java.getDeclaredField("writeQueue").apply { isAccessible = true }
        val previousEnabled = enabled.getBoolean(AppLogger)
        val previousWriter = writer.get(AppLogger)
        val lines = mutableListOf<String>()
        val capture = LogWriteQueue(sink = { _, line -> lines.add(line) }, notice = {})
        writer.set(AppLogger, capture)
        enabled.setBoolean(AppLogger, true)
        try {
            block()
        } finally {
            enabled.setBoolean(AppLogger, previousEnabled)
            writer.set(AppLogger, previousWriter)
            capture.flushAndStop(0)
        }
        return lines
    }
}
