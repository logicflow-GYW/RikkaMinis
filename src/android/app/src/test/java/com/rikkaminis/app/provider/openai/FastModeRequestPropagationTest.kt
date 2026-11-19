package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.FastModePrefs
import com.rikkaminis.app.data.model.LLMMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [fix/r2-fastmode-1119 / F-19] Fast Mode must survive the process boundary.
 *
 * The defect: FastModePrefs is primed once per process and its prefs listener
 * does not cross process boundaries, so a still-alive `:modelservice` kept
 * sending `service_tier: priority` (premium billing) after the user turned the
 * toggle OFF — and kept omitting it after they turned it ON.
 *
 * Assertions here are on the WIRE (the actual JSON body the provider put on the
 * request), not on booleans handed around: a flag that is computed correctly and
 * then dropped at assembly still bills the user.
 */
class FastModeRequestPropagationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        setLocalCache(false)
    }

    // -- Wire assertions (real /responses body) --

    @Test
    fun `explicit off beats a stale local cache that says on`() = runBlocking {
        setLocalCache(true)
        val body = captureResponsesBody(override = false)
        assertFalse(
            "main process said OFF; the worker cache saying ON must not send a premium tier",
            body.has("service_tier"),
        )
    }

    @Test
    fun `explicit on beats a stale local cache that says off`() = runBlocking {
        setLocalCache(false)
        val body = captureResponsesBody(override = true)
        assertEquals("priority", body.getString("service_tier"))
    }

    @Test
    fun `absent override falls back to the local cache when it says on`() = runBlocking {
        setLocalCache(true)
        val body = captureResponsesBody(override = null)
        assertEquals("priority", body.getString("service_tier"))
    }

    @Test
    fun `absent override falls back to the local cache when it says off`() = runBlocking {
        setLocalCache(false)
        val body = captureResponsesBody(override = null)
        assertFalse(body.has("service_tier"))
    }

    @Test
    fun `override is an explicit true or false, never inferred from absence`() {
        // Pin the tri-state shape: `Boolean?` means "nothing carried". A non-null
        // `Boolean` with a `false` default-fold would silently turn every
        // in-process caller into an explicit OFF.
        val source = providerSource().readText()
        assertTrue(
            "fastModeOverride must stay a nullable Boolean",
            Regex("""internal\s+var\s+fastModeOverride\s*:\s*Boolean\?\s*=\s*null""")
                .containsMatchIn(source),
        )
    }

    @Test
    fun `provider has exactly one Fast Mode read and it consults the override first`() {
        val source = providerSource().readText()
        val reads = Regex("""FastModePrefs\s*\.\s*isEnabled\s*\(\s*\)""").findAll(source).count()
        assertEquals(
            "a second read site would re-open the cross-process hole this fix closes",
            1,
            reads,
        )
        assertTrue(
            "the single read must be the tri-state fallback of the override",
            source.contains("fastModeOverride ?: com.rikkaminis.app.data.FastModePrefs.isEnabled()"),
        )
    }

    // -- Helpers --

    private suspend fun captureResponsesBody(override: Boolean?): JSONObject {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"response.completed\",\"response\":{" +
                        "\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n",
                ),
        )
        val provider = OpenAIProvider(
            apiKey = "fixture-not-a-key",
            basePath = server.url("/v1").toString(),
            useResponsesAPI = true,
        )
        provider.fastModeOverride = override
        try {
            provider.streamMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")), null, 64,
            ).toList()
        } catch (_: Exception) {
            // The body is already on the wire; parse/terminal handling is not
            // what this test measures.
        }
        return JSONObject(server.takeRequest().body.readUtf8())
    }

    /**
     * FastModePrefs has no JVM-settable seam (its only writer needs a Context),
     * so the process cache is written through the field the production reader
     * reads. A rename fails loudly here instead of silently greening the test.
     */
    private fun setLocalCache(value: Boolean) {
        val field = FastModePrefs::class.java.getDeclaredField("cachedEnabled")
        field.isAccessible = true
        field.setBoolean(FastModePrefs, value)
    }

    private fun providerSource(): File {
        val candidates = listOf(
            "src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt",
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f
        }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (c in candidates) {
                val f = File(dir, c)
                if (f.exists()) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("OpenAIProvider.kt not found from ${File("").absolutePath}")
    }
}
