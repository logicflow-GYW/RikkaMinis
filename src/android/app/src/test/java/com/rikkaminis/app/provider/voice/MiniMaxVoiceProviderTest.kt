package com.rikkaminis.app.provider.voice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MiniMax's native TTS contract is deliberately tested through the provider's
 * real request builder and HTTP synthesize path, not through a duplicate parser.
 * Expected wire values are literal so a mutation of the adapter cannot update
 * the assertions through a shared constant.
 */
class MiniMaxVoiceProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: MiniMaxVoiceProvider

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        provider = MiniMaxVoiceProvider(
            providerId = "minimax-unit-test",
            baseURL = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
        )
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `default request uses MiniMax multiplier units and hex output`() {
        val request = provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "hello"))
        val body = requestBody(request)
        val voice = body.getJSONObject("voice_setting")

        assertEquals("/v1/t2a_v2", request.url.encodedPath)
        assertEquals("speech-2.8-hd", body.getString("model"))
        assertEquals("hello", body.getString("text"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals("hex", body.getString("output_format"))
        assertEquals("female-shaonv", voice.getString("voice_id"))
        assertEquals(1.0, voice.getDouble("speed"), 0.0)
        assertEquals(1.0, voice.getDouble("vol"), 0.0)
        assertEquals(0, voice.getInt("pitch"))
    }

    @Test
    fun `boundary speeds are sent as float multipliers`() {
        val lower = requestBody(
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "x", speed = 0.5f)),
        )
        val upper = requestBody(
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "x", speed = 2.0f)),
        )

        assertEquals(0.5, lower.getJSONObject("voice_setting").getDouble("speed"), 0.0)
        assertEquals(2.0, upper.getJSONObject("voice_setting").getDouble("speed"), 0.0)
    }

    @Test
    fun `speeds outside the official range fail closed`() {
        assertThrows(VoiceProviderException.Parse::class.java) {
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "x", speed = 0.25f))
        }
        assertThrows(VoiceProviderException.Parse::class.java) {
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "x", speed = 4.0f))
        }
        assertThrows(VoiceProviderException.Parse::class.java) {
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "x", speed = Float.NaN))
        }
    }

    @Test
    fun `synthesize reads official data audio hex through the real HTTP path`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"data":{"audio":"494433","status":2},"base_resp":{"status_code":0,"status_msg":"success"}}""",
            ),
        )

        val audio = provider.synthesize(VoiceOutputRequest(input = "hello"))

        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33), audio)
        val sent = server.takeRequest()
        assertEquals("/v1/t2a_v2", sent.path)
        assertEquals("Bearer test-key", sent.getHeader("Authorization"))
        val sentBody = JSONObject(sent.body.readUtf8())
        assertEquals("hex", sentBody.getString("output_format"))
        assertEquals(1.0, sentBody.getJSONObject("voice_setting").getDouble("vol"), 0.0)
    }

    @Test
    fun `nonzero base response is a parse error with vendor message`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"base_resp":{"status_code":1004,"status_msg":"authentication failed"}}""",
            ),
        )

        val error = assertThrows(VoiceProviderException.Parse::class.java) {
            runBlocking { provider.synthesize(VoiceOutputRequest(input = "hello")) }
        }

        assertTrue(error.message!!.contains("1004"))
        assertTrue(error.message!!.contains("authentication failed"))
    }

    @Test
    fun `missing or malformed audio is never returned as success`() {
        val malformed = listOf(
            """{"audio":{"audio":"SUQz"},"base_resp":{"status_code":0}}""",
            """{"data":{},"base_resp":{"status_code":0}}""",
            """{"data":{"audio":"494"},"base_resp":{"status_code":0}}""",
            """{"data":{"audio":"49GG"},"base_resp":{"status_code":0}}""",
            """{"data":{"audio":"494433"}}""",
            "not-json",
        )

        for (body in malformed) {
            server.enqueue(MockResponse().setBody(body))
            assertThrows(VoiceProviderException.Parse::class.java) {
                runBlocking { provider.synthesize(VoiceOutputRequest(input = "hello")) }
            }
        }
    }

    private fun requestBody(request: okhttp3.Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }
}
