package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.FastModePrefs
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r2-fastmode-1119 / F-19] Both halves of the cross-process Fast Mode
 * contract, in one place because they are only correct TOGETHER:
 *
 *  - main process writes the value on EVERY request (explicit true AND false —
 *    a present-only key cannot say "the user turned it off");
 *  - `:modelservice` reads it at BOTH provider-assembly sites and only overrides
 *    when the key is present (absent = old caller = keep the local cache).
 *
 * The previous branch was forbidden to touch ModelExecutionService, so fixing
 * the writer alone would have been inert; that is why this test pins the service
 * sites too instead of trusting the dispatcher test.
 */
class FastModeWirePayloadTest {

    @After
    fun tearDown() {
        setLocalCache(false)
    }

    // -- Writer: unconditional, explicit both ways --

    @Test
    fun `writer emits explicit false when the toggle is off`() {
        setLocalCache(false)
        val json = buildRequest()
        assertTrue("the writer must send the value even when it is off", json.has("fast_mode"))
        assertEquals(false, json.getBoolean("fast_mode"))
    }

    @Test
    fun `writer emits explicit true when the toggle is on`() {
        setLocalCache(true)
        val json = buildRequest()
        assertTrue(json.has("fast_mode"))
        assertEquals(true, json.getBoolean("fast_mode"))
    }

    @Test
    fun `writer line is not behind a present-only guard`() {
        val source = dispatcherSource().readText()
        assertTrue(
            "the writer must always put the key",
            Regex("""put\(\s*"fast_mode"\s*,\s*com\.rikkaminis\.app\.data\.FastModePrefs\.isEnabled\(\)\s*\)""")
                .containsMatchIn(source),
        )
        assertFalse(
            "a conditional put would make the OFF direction inexpressible",
            Regex("""if\s*\([^)\n]*\)\s*put\(\s*"fast_mode\"""")
                .containsMatchIn(source),
        )
    }

    // -- Readers: two independent assembly sites --

    @Test
    fun `both provider assembly sites read and stamp the override`() {
        val source = serviceSource().readText()
        val nonStream = methodSlice(source, "private fun executeRun(", "private fun executeStreamingRun(")
        val streaming = methodSlice(source, "private fun executeStreamingRun(", "private fun awaitStreamAckBarrier(")
        for ((name, body) in listOf("executeRun" to nonStream, "executeStreamingRun" to streaming)) {
            assertTrue(
                "$name must gate on key presence (absent = old caller = local cache)",
                body.contains("""req.has("fast_mode")"""),
            )
            assertTrue(
                "$name must stamp the override onto the OpenAI provider",
                body.contains("""fastModeOverride = req.optBoolean("fast_mode")"""),
            )
        }
        assertEquals(
            "more than two read sites means a third path was added without a test",
            2,
            Regex("""req\.has\(\s*"fast_mode"\s*\)""").findAll(source).count(),
        )
    }

    // -- Helpers --

    private fun buildRequest(): JSONObject = JSONObject(
        ModelExecutionDispatcher.buildRequestJson(
            instance = ProviderInstance(
                id = "inst-fast-mode",
                label = "openai",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
            ),
            model = LLMModel(id = "gpt-5", displayName = "GPT-5", provider = "openai"),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 64,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ),
    )

    private fun setLocalCache(value: Boolean) {
        val field = FastModePrefs::class.java.getDeclaredField("cachedEnabled")
        field.isAccessible = true
        field.setBoolean(FastModePrefs, value)
    }

    private fun methodSlice(text: String, start: String, end: String): String {
        val a = text.indexOf(start)
        val b = text.indexOf(end, a + 1)
        assertTrue("method boundary not found: $start", a >= 0 && b > a)
        return text.substring(a, b)
    }

    private fun dispatcherSource(): File =
        appSource("sandbox/offload/ModelExecutionDispatcher.kt")

    private fun serviceSource(): File =
        appSource("sandbox/offload/ModelExecutionService.kt")

    private fun appSource(relative: String): File {
        val candidates = listOf(
            "src/main/java/com/rikkaminis/app/$relative",
            "src/android/app/src/main/java/com/rikkaminis/app/$relative",
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
        throw AssertionError("$relative not found from ${File("").absolutePath}")
    }
}
