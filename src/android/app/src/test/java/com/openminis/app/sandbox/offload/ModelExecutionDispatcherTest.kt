package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the cross-process model-execution protocol serialization
 * ([ModelExecutionDispatcher.buildRequestJson]).
 *
 * The protocol is the contract between the main process and the isolated
 * `:modelservice` process: every field the service needs to reconstruct
 * ProviderInstance / LLMModel / messages must be present in the request
 * JSON. These tests pin the field coverage so a future ProviderInstance
 * field addition (the "four-way sync" trap) fails here instead of
 * silently breaking remote execution.
 */
class ModelExecutionDispatcherTest {

    private fun sampleInstance() = ProviderInstance(
        id = "inst-123",
        label = "deepseek",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = "https://api.deepseek.com/v1/",
        appendV1Suffix = true,
        customUserAgent = "minis-test",
        useResponsesAPI = false,
        imageEndpointMode = com.openminis.app.data.model.ImageEndpointMode.auto,
        imageEndpointResolved = null,
        azureMode = false,
        pinned = true,
    )

    private fun sampleModel() = LLMModel(
        id = "deepseek-v4-flash",
        displayName = "DeepSeek V4 Flash",
        provider = "deepseek",
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("text"),
        contextWindow = 65536,
    )

    private fun sampleMessages() = listOf(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = "hello",
            audioParts = listOf(LLMMessage.AudioPart("wav", "QUJDRA==")),
        ),
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "hi"),
    )

    @Test
    fun `instance fields are serialized`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertEquals("inst-123", json.getString("instance_id"))
        assertEquals("deepseek", json.getString("instance_label"))
        assertEquals("openAI", json.getString("provider_type"))
        assertEquals("apiKey", json.getString("credential_type"))
        assertEquals("https://api.deepseek.com/v1/", json.getString("base_url"))
        assertTrue(json.getBoolean("append_v1"))
        assertEquals("minis-test", json.getString("user_agent"))
        assertFalse(json.getBoolean("use_responses_api"))
        assertEquals("auto", json.getString("image_endpoint_mode"))
        assertFalse(json.has("image_endpoint_resolved"))
        assertFalse(json.getBoolean("azure_mode"))
    }

    @Test
    fun `model fields are serialized`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertEquals("deepseek-v4-flash", json.getString("model_id"))
        assertEquals("DeepSeek V4 Flash", json.getString("model_display_name"))
        assertEquals("deepseek", json.getString("model_provider"))
        assertEquals(listOf("text", "image"), json.getJSONArray("input_modalities").toList())
        assertEquals(listOf("text"), json.getJSONArray("output_modalities").toList())
        assertEquals(65536, json.getInt("context_window"))
    }

    @Test
    fun `messages serialize role content and audio parts`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = sampleMessages(),
            systemPrompt = "sys",
            maxTokens = 100,
            temperature = 0.7,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        val msgs = json.getJSONArray("messages")
        assertEquals(2, msgs.length())
        val first = msgs.getJSONObject(0)
        assertEquals("user", first.getString("role"))
        assertEquals("hello", first.getString("content"))
        val audio = first.getJSONArray("audio_parts")
        assertEquals(1, audio.length())
        assertEquals("wav", audio.getJSONObject(0).getString("format"))
        assertEquals("QUJDRA==", audio.getJSONObject(0).getString("data"))
        assertEquals("assistant", msgs.getJSONObject(1).getString("role"))
        assertEquals("sys", json.getString("system_prompt"))
        assertEquals(100, json.getInt("max_tokens"))
        assertEquals(0.7, json.getDouble("temperature"), 0.001)
    }

    @Test
    fun `image parts serialize data and mime type`() {
        val img = LLMMessage.ImagePart(
            data = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            linuxPath = "/var/minis/workspace/x.png",
        )
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = listOf(img),
            inputJson = "",
            outputExt = null,
        ))
        val parts = json.getJSONArray("image_parts")
        assertEquals(1, parts.length())
        val p = parts.getJSONObject(0)
        // 4 bytes base64 → "AQIDBA=="
        assertEquals("AQIDBA==", p.getString("data"))
        assertEquals("image/png", p.getString("mime_type"))
        assertEquals("/var/minis/workspace/x.png", p.getString("linux_path"))
    }

    @Test
    fun `input json and output ext are passed through`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "{\"extra_body\":{\"temperature\":2}}",
            outputExt = "png",
        ))
        assertEquals("{\"extra_body\":{\"temperature\":2}}", json.getString("input_json"))
        assertEquals("png", json.getString("output_ext"))
    }

    @Test
    fun `custom knobs are serialized`() {
        // [T-provider-extra-headers/body] Removed feature — the dispatcher no
        // longer serializes custom_headers / custom_body_fields, so a fresh
        // request JSON must NOT contain either key.
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertFalse(json.has("custom_headers"))
        assertFalse(json.has("custom_body_fields"))
    }

    @Test
    fun `empty custom knobs omit the keys`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertFalse(json.has("custom_headers"))
        assertFalse(json.has("custom_body_fields"))
    }

    @Test
    fun `nullables are omitted not null`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance().copy(customBaseURL = null, customUserAgent = null),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertFalse(json.has("base_url"))
        assertFalse(json.has("user_agent"))
        assertFalse(json.has("system_prompt"))
        assertFalse(json.has("temperature"))
        assertFalse(json.has("output_ext"))
        assertFalse(json.has("messages"))
    }

    @Test
    fun `empty messages omit the key`() {
        val json = JSONObject(ModelExecutionDispatcher.buildRequestJson(
            instance = sampleInstance(),
            model = sampleModel(),
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 4096,
            temperature = null,
            imageParts = emptyList(),
            inputJson = "",
            outputExt = null,
        ))
        assertFalse(json.has("messages"))
    }

    private fun JSONArray.toList(): List<String> =
        (0 until length()).map { getString(it) }
}