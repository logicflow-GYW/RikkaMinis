package com.rikkaminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [gateway-context-1112] Contract tests for the endpoint-metadata parser.
 *
 * The defect this pins: the OpenAI-compatible `/v1/models` parser read only
 * `id`, `name` and `architecture`, so everything else the endpoint reported —
 * `context_length`, `top_provider.max_completion_tokens`, `display_name` and
 * the four `supports_*` flags — was dropped. The app then fell back to the
 * models.dev registry and, when that missed too, to an id-shape guess. The
 * user saw 128K for a gateway that was advertising 960 000.
 *
 * Every expected value is a hand-written literal; the fixtures are the shapes
 * real gateways emit (the local AI-Gateway, OpenRouter, the new-api family).
 */
class OpenAIModelEndpointMetadataTest {

    // ── context window ─────────────────────────────────────────────────────

    @Test
    fun `context_length is read`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"codebuddy-cn/hy4free","context_length":960000}"""),
        )
        assertEquals(960000, meta.contextWindow)
    }

    @Test
    fun `max_input_tokens is read as a context alias`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","max_input_tokens":128000}"""),
        )
        assertEquals(128000, meta.contextWindow)
    }

    @Test
    fun `context_window camelCase alias is read`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","context_window":200000}"""),
        )
        assertEquals(200000, meta.contextWindow)
    }

    @Test
    fun `top_provider max_completion_tokens is NOT a context window`() {
        // OpenRouter nests the OUTPUT cap under top_provider; reading it as
        // the window would be wrong (it is the per-request output limit).
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","top_provider":{"max_completion_tokens":8192}}"""),
        )
        assertNull("the output cap must not become the window", meta.contextWindow)
    }

    @Test
    fun `absent context stays null`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m"}"""))
        assertNull(meta.contextWindow)
    }

    @Test
    fun `zero context is treated as not reported`() {
        // The gateway itself only emits a positive value (`if > 0`), so 0
        // means "no number", not "a zero-token window".
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","context_length":0}"""),
        )
        assertNull(meta.contextWindow)
    }

    @Test
    fun `context_length wins over max_input_tokens when both are present`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","context_length":960000,"max_input_tokens":128000}"""),
        )
        assertEquals(960000, meta.contextWindow)
    }

    @Test
    fun `a zero first alias falls through to the next alias`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","context_length":0,"max_input_tokens":128000}"""),
        )
        assertEquals(128000, meta.contextWindow)
    }

    // ── output cap ─────────────────────────────────────────────────────────

    @Test
    fun `max_output_tokens is read`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","max_output_tokens":32768}"""),
        )
        assertEquals(32768, meta.maxOutputTokens)
    }

    @Test
    fun `max_completion_tokens is read as an output alias`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","max_completion_tokens":16384}"""),
        )
        assertEquals(16384, meta.maxOutputTokens)
    }

    @Test
    fun `absent output cap stays null`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m"}"""))
        assertNull(meta.maxOutputTokens)
    }

    // ── display name ───────────────────────────────────────────────────────

    @Test
    fun `name is read`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m","name":"GLM-5.3"}"""))
        assertEquals("GLM-5.3", meta.displayName)
    }

    @Test
    fun `display_name is read as an alias`() {
        // The local gateway emits `display_name`, NOT `name`.
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","display_name":"Hy4"}"""),
        )
        assertEquals("Hy4", meta.displayName)
    }

    @Test
    fun `name wins when both names are present`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":"Primary","display_name":"Secondary"}"""),
        )
        assertEquals("Primary", meta.displayName)
    }

    @Test
    fun `blank name is treated as absent`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","name":"","display_name":"Fallback"}"""),
        )
        assertEquals("Fallback", meta.displayName)
    }

    @Test
    fun `absent display name stays null`() {
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m"}"""))
        assertNull(meta.displayName)
    }

    // ── capability flags ───────────────────────────────────────────────────

    @Test
    fun `supports_reasoning true is read`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","supports_reasoning":true}"""),
        )
        assertEquals(true, meta.supportsReasoning)
    }

    @Test
    fun `supports_reasoning false is read as false`() {
        // An endpoint that says "false" knows the model has no reasoning
        // mode; that must NOT be confused with "the endpoint said nothing".
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","supports_reasoning":false}"""),
        )
        assertEquals(false, meta.supportsReasoning)
    }

    @Test
    fun `an absent flag stays null rather than becoming false`() {
        // optBoolean() would answer false for a missing key, which would
        // permanently disable the thinking toggle for a gateway that simply
        // does not report the flag.
        val meta = parseOpenAIModelEndpointMetadata(JSONObject("""{"id":"m"}"""))
        assertNull(meta.supportsReasoning)
        assertNull(meta.supportsTools)
        assertNull(meta.supportsVision)
        assertNull(meta.supportsAudio)
    }

    @Test
    fun `an explicit null flag stays null`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","supports_vision":null}"""),
        )
        assertNull(meta.supportsVision)
    }

    @Test
    fun `all four capability flags are read`() {
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject(
                """{"id":"m","supports_reasoning":true,"supports_tools":true,"supports_vision":false,"supports_audio":true}""",
            ),
        )
        assertEquals(true, meta.supportsReasoning)
        assertEquals(true, meta.supportsTools)
        assertEquals(false, meta.supportsVision)
        assertEquals(true, meta.supportsAudio)
    }

    // ── the real local-gateway payload ─────────────────────────────────────

    @Test
    fun `the local gateway row parses to its advertised window`() {
        // Verbatim shape from GET http://127.0.0.1:8790/v1/models on
        // 2026-11-12 — the row the user was looking at when it showed 128K.
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject(
                """{"id":"codebuddy-cn/hy4free","object":"model","created":1753600000,"owned_by":"codebuddy","display_name":"Hy4","context_length":960000,"supports_reasoning":false,"supports_tools":false,"supports_vision":false,"supports_audio":false}""",
            ),
        )
        assertEquals(960000, meta.contextWindow)
        assertEquals("Hy4", meta.displayName)
        assertEquals(false, meta.supportsReasoning)
    }
}
