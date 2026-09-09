package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.provider.openai.OpenAIModelsApi.shouldConsultCache
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the `forceRefresh` contract added for the "new provider needs a
 * restart to take effect" bug (custom baseURL + OpenAI-compatible).
 *
 * The 7-day [ProviderModelsCache] decision used to be embedded inline in
 * [OpenAIModelsApi.fetchModels] (`context != null && !forceRefresh`), which
 * is untestable in a pure JVM without an Android `Context`. It is now
 * extracted as the pure [shouldConsultCache] function and this test locks in
 * both halves of the contract:
 *
 *  - `forceRefresh = true`  → never consult the cache (re-validate live)
 *  - `forceRefresh = false` → consult the cache when a context is present
 *
 * Because the production path supplies no `Context` (adapters call
 * `OpenAIModelsApi.fetchModels` without one), [shouldConsultCache] returns
 * false for `hasContext=false` regardless of `forceRefresh` — meaning the
 * cache is only ever reachable from the (context-carrying) test/alternate
 * path. The MockWebServer test below confirms the live request is still wired
 * up so `forceRefresh=true` reaches the network as expected.
 */
class OpenAIModelsApiForceRefreshTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- shouldConsultCache: pure decision, JVM-testable, no Android runtime --

    @Test
    fun `forceRefresh=true never consults cache even with a context`() {
        assertFalse("force override must bypass the cache",
            shouldConsultCache(hasContext = true, forceRefresh = true))
    }

    @Test
    fun `forceRefresh=false consults cache when a context is available`() {
        assertTrue("default path may use the 7-day disk cache",
            shouldConsultCache(hasContext = true, forceRefresh = false))
    }

    @Test
    fun `no context means the cache is never consulted`() {
        // Production adapters call fetchModels without a Context, so both
        // forceRefresh values must skip the cache.
        assertFalse(shouldConsultCache(hasContext = false, forceRefresh = true))
        assertFalse(shouldConsultCache(hasContext = false, forceRefresh = false))
    }

    // -- End-to-end: forceRefresh=true actually issues the live request --

    private val modelsBody = """{"data":[{"id":"gpt-test-model","object":"model"}]}"""

    @Test
    fun `fetchModels with forceRefresh issues a live network request`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(modelsBody)
                .setHeader("Content-Type", "application/json")
        )

        val result = OpenAIModelsApi.fetchModels(
            apiKey = "sk-test",
            baseURL = server.url("/v1").toString(),
            context = null,          // production: adapters pass no context
            forceRefresh = true,     // the add-provider path
        )

        assertTrue("expected at least one model from the live /v1/models", result.isNotEmpty())
        assertTrue("expected the mocked model id", result.any { it.id == "gpt-test-model" })

        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
    }

    @Test
    fun `fetchModels default forceRefresh false with custom base returns models on success`() = runBlocking {
        // Same live fetch path with the default forceRefresh=false; the custom
        // baseURL means the fallback is emptyList and the provider list is
        // still produced by the live response (no chat-prefix filter applied).
        server.enqueue(
            MockResponse()
                .setBody(modelsBody)
                .setHeader("Content-Type", "application/json")
        )

        val result = OpenAIModelsApi.fetchModels(
            apiKey = "sk-test",
            baseURL = server.url("/v1").toString(),
            context = null,
        )

        assertTrue(result.any { it.id == "gpt-test-model" })
    }
}
