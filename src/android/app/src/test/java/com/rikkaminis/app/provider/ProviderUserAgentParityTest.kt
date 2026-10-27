package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.provider.gemini.GeminiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [deep-audit-1019 ISSUE-04] `customUserAgent` parity across providers.
 *
 * `ProviderInstance.customUserAgent` is settable for every apiKey provider
 * (settings screen + `android provider`), but `ProviderFactory.create` only
 * forwarded it on `openAI` and on the custom-base `anthropic` arm — the
 * `anthropic` else arm, both `gemini` arms, `openRouter`, `xAI` and `kimiCode`
 * dropped it silently, and `GeminiProvider` had no parameter to receive it at
 * all (both builders hard-coded `.applyUserAgentOverride(null)`).
 *
 * Two layers of evidence:
 *  - behaviour: a MockWebServer round-trip asserts the wire User-Agent for the
 *    real Gemini request builders (this is the layer that can actually observe
 *    the override reaching the socket);
 *  - shape: source pins for the parts a JVM test cannot execute (the factory's
 *    branch wiring, and the two hard-coded call sites).
 *
 * Expected values are hand-written literals.
 */
class ProviderUserAgentParityTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    /** Deliberately not the app's default UA — the assertion must not pass by accident. */
    private val customUa = "Minis-Unit-Test-Agent/9.9 (opaque)"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(customUserAgent: String?): GeminiProvider = GeminiProvider(
        apiKey = "test-key",
        model = LLMModel.gemini25Flash,
        basePath = baseUrl,
        customUserAgent = customUserAgent,
    )

    private fun enqueueSse(sseBody: String) {
        server.enqueue(MockResponse().setBody(sseBody).setHeader("Content-Type", "text/event-stream"))
    }

    private fun textOnlySse(): String = buildString {
        appendLine("""data: {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        appendLine()
    }

    // ── behaviour: the override reaches the socket ─────────────────────────

    @Test
    fun `streaming request carries the instance customUserAgent`() = runBlocking {
        enqueueSse(textOnlySse())

        provider(customUa)
            .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
            .toList()

        assertEquals(customUa, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `non-streaming request carries the instance customUserAgent`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""))

        provider(customUa).sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 100)

        assertEquals(customUa, server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `no override falls back to the branded default UA`() = runBlocking {
        enqueueSse(textOnlySse())

        provider(null)
            .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
            .toList()

        val ua = server.takeRequest().getHeader("User-Agent")
        assertTrue("expected Minis-branded UA, got $ua", ua != null && ua.startsWith("Minis/"))
    }

    // ── behaviour: tool-only EOF is truncated ─────────────────────────────

    private fun toolOnlySse(withFinishReason: Boolean): String = buildString {
        val finish = if (withFinishReason) ""","finishReason":"STOP"""" else ""
        appendLine(
            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Singapore"}}}]}$finish}]}""",
        )
        appendLine()
    }

    @Test
    fun `EOF after tool-only output is flagged truncated`() = runBlocking {
        enqueueSse(toolOnlySse(withFinishReason = false))

        val chunks = provider(null)
            .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
            .toList()

        val completes = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(1, completes.size)
        assertEquals("get_weather", completes[0].name)

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        // ISSUE-04: contentChars is 0 here (no text/thinking), so before the fix
        // this was a clean Finished("end_turn") and the incomplete tool turn was
        // never retried.
        assertNull(finished[0].stopReason)
        assertTrue(finished[0].truncated)
    }

    @Test
    fun `a finishReason after tool calls stays a clean finish`() = runBlocking {
        enqueueSse(toolOnlySse(withFinishReason = true))

        val chunks = provider(null)
            .streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
            .toList()

        val finished = chunks.filterIsInstance<LLMStreamChunk.Finished>()
        assertEquals(1, finished.size)
        assertEquals("end_turn", finished[0].stopReason)
        assertEquals(false, finished[0].truncated)
    }

    // ── shape: the wiring a JVM test cannot execute ───────────────────────

    @Test
    fun `provider factory forwards the instance UA on all eight constructor sites`() {
        val factory = readRepoFile(FACTORY_REL)

        // Hand-written literal for the tree this branch fixes: 2 anthropic arms,
        // 2 gemini arms, openAI, openRouter, xAI, kimiCode.
        assertEquals(8, countOf(factory, "customUserAgent = instance.customUserAgent"))
        // The else arm of anthropic used to drop it while the if arm forwarded it.
        assertTrue(
            factory.contains("else AnthropicProvider(effectiveKey, model, customUserAgent = instance.customUserAgent)"),
        )
        assertTrue(
            factory.contains("else GeminiProvider(effectiveKey, model, customUserAgent = instance.customUserAgent)"),
        )
    }

    @Test
    fun `gemini provider has no hard-coded null UA override left`() {
        val gemini = readRepoFile(GEMINI_REL)

        assertEquals(0, countOf(gemini, ".applyUserAgentOverride(null)"))
        assertEquals(2, countOf(gemini, ".applyUserAgentOverride(customUserAgent)"))
        assertTrue(gemini.contains("private val customUserAgent: String? = null"))
    }

    @Test
    fun `gemini tool payloads and media bytes feed the EOF activity counters`() {
        val gemini = readRepoFile(GEMINI_REL)

        assertTrue(gemini.contains("toolChars += toolCallActivityChars(fcName, fcArgs.toString())"))
        assertTrue(gemini.contains("contentChars += media.data.size"))
        assertTrue(gemini.contains("if (shouldFlagEofTruncated(lastFinishReason, contentChars, toolChars))"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count += 1
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val FACTORY_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/ProviderFactory.kt"
        const val GEMINI_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/gemini/GeminiProvider.kt"
    }
}
