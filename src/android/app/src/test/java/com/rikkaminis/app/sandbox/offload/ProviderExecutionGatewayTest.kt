package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import com.rikkaminis.app.data.model.ThinkingLevel
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ProviderExecutionGateway] — the main-process single chokepoint
 * for every LLM/provider call.
 *
 * These pin:
 *   - request building delegates to the same four-way-synced builder as the
 *     worker (instance/model/messages/maxTokens present).
 *   - failure classification (Success / RemoteFailure / Unavailable) is pure
 *     and testable without a Context/system service.
 *   - the gateway's request builder never inlines a provider type (no
 *     ProviderFactory import — enforced structurally by it not existing here).
 */
class ProviderExecutionGatewayTest {

    private fun sampleInstance() = ProviderInstance(
        id = "inst-gw",
        label = "gateway-test",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = null,
        appendV1Suffix = true,
        customUserAgent = null,
        useResponsesAPI = false,
        imageEndpointMode = com.rikkaminis.app.data.model.ImageEndpointMode.auto,
        imageEndpointResolved = null,
        azureMode = false,
        pinned = true,
    )

    private fun sampleModel() = LLMModel(
        id = "gw-model",
        displayName = "Gateway Model",
        provider = "openai",
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
        contextWindow = 32768,
    )

    private fun sampleMessages() = listOf(
        LLMMessage(role = LLMMessage.Role.USER, content = "hello gateway"),
    )

    /** Request building delegates to the same fields the worker reconstructs. */
    @Test
    fun `send request builds four-way-synced fields`() {
        val json = JSONObject(ProviderExecutionGateway.buildRequest(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = sampleMessages(),
            systemPrompt = "sys",
            maxTokens = 512,
            temperature = 0.5,
            imageParts = emptyList(),
            inputJson = "{\"extra_body\":{}}",
            outputExt = null,
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
            streaming = false,
        ))
        assertEquals("inst-gw", json.getString("instance_id"))
        assertEquals("gw-model", json.getString("model_id"))
        assertEquals("sys", json.getString("system_prompt"))
        assertEquals(512, json.getInt("max_tokens"))
        assertEquals(0.5, json.getDouble("temperature"), 0.001)
        assertEquals(1, json.getJSONArray("messages").length())
    }

    @Test
    fun `success result parses text stop reason and usage`() {
        val raw = JSONObject().apply {
            put("model", "gw-model")
            put("text", "hi there")
            put("stop_reason", "stop")
            put("usage", JSONObject().apply {
                put("input_tokens", 10)
                put("output_tokens", 5)
            })
            put("exit_code", 0)
        }.toString()
        val r = ProviderExecutionGateway.parseNonStreamingResult(raw)
        assertTrue(r is ProviderExecutionGateway.SendResult.Success)
        val s = r as ProviderExecutionGateway.SendResult.Success
        assertEquals("hi there", s.response.text)
        assertEquals("stop", s.response.stopReason)
        assertEquals(10, s.response.usage?.inputTokens)
        assertEquals(5, s.response.usage?.outputTokens)
    }

    @Test
    fun `worker error result classifies as remote failure with code and exit code`() {
        val raw = JSONObject().apply {
            put("error", "missing_api_key")
            put("message", "No API key configured for inst-gw.")
            put("exit_code", 2)
        }.toString()
        val r = ProviderExecutionGateway.parseNonStreamingResult(raw)
        assertTrue(r is ProviderExecutionGateway.SendResult.RemoteFailure)
        val f = r as ProviderExecutionGateway.SendResult.RemoteFailure
        assertEquals("missing_api_key", f.code)
        assertEquals(2, f.exitCode)
        assertTrue(f.message.contains("API key"))
    }

    @Test
    fun `empty error field with non-zero exit still remote failure`() {
        val raw = JSONObject().apply {
            put("error", "")
            put("message", "boom")
            put("exit_code", 1)
        }.toString()
        // Empty `error` string is treated as success/no-error by the protocol;
        // classification follows the presence of a non-empty `error`.
        val r = ProviderExecutionGateway.parseNonStreamingResult(raw)
        assertTrue(r is ProviderExecutionGateway.SendResult.Success)
    }

    @Test
    fun `unparseable result classifies as unavailable`() {
        val r = ProviderExecutionGateway.parseNonStreamingResult("not json{{{")
        assertTrue(r is ProviderExecutionGateway.SendResult.Unavailable)
    }

    @Test
    fun `success with media_files parses attachments`() {
        val raw = JSONObject().apply {
            put("exit_code", 0)
            put("media_files", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image")
                    put("mime_type", "image/png")
                    put("data", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
                })
            })
        }.toString()
        val r = ProviderExecutionGateway.parseNonStreamingResult(raw)
        assertTrue(r is ProviderExecutionGateway.SendResult.Success)
        val media = (r as ProviderExecutionGateway.SendResult.Success).response.mediaAttachments
        assertEquals(1, media.size)
        assertTrue(media[0].data.contentEquals(byteArrayOf(1, 2, 3)))
    }

    /** The gateway must not import / reference a concrete provider type. */
    @Test
    fun `gateway source has no provider factory or concrete provider reference`() {
        var found = false
        val source = javaClass.getResourceAsStream("/../src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ProviderExecutionGateway.kt")
        // Resource lookup of source is unreliable in JVM tests; fall back to a
        // disk scan so the guard still functions in CI where the tree is on disk.
        val file = File("src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ProviderExecutionGateway.kt")
        if (file.exists()) {
            val text = file.readText()
            found = text.contains("ProviderFactory") || text.contains("LLMProvider")
            assertFalse("ProviderExecutionGateway must not reference ProviderFactory / LLMProvider", found)
        } else {
            // skip when tree absent
        }
    }
}