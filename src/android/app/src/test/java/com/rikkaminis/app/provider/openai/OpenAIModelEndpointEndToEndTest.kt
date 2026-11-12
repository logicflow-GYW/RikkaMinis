package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.mergeRegistryRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [gateway-context-1112] END-TO-END arm: the REAL local-gateway payload,
 * parsed and merged the way the production path does.
 *
 * The other test classes pin the pieces (the field aliases, the precedence
 * rules). This one pins the CHAIN — the thing the user actually observes:
 * "the gateway says 960 000, and the app shows 960 000".
 *
 * The fixture is the verbatim body of `GET http://127.0.0.1:8790/v1/models`
 * captured on 2026-11-12 (the local AI-Gateway on the device). It is
 * embedded as a literal rather than fetched, so the test does not depend on
 * the gateway running.
 */
class OpenAIModelEndpointEndToEndTest {

    /** The real payload, trimmed to the rows that carry a window. */
    private val realGatewayBody = """
    {"object":"list","data":[
      {"id":"chatgpt/gpt-6-luna","object":"model","created":1753600000,"owned_by":"chatgpt","display_name":"GPT-6-Luna","context_length":272000,"supports_reasoning":false,"supports_tools":false,"supports_vision":false,"supports_audio":false},
      {"id":"codebuddy-cn/hy4free","object":"model","created":1753600000,"owned_by":"codebuddy","display_name":"Hy4","context_length":960000,"supports_reasoning":false,"supports_tools":false,"supports_vision":false,"supports_audio":false},
      {"id":"codebuddy-cn/glm-5.3-flash","object":"model","created":1753600000,"owned_by":"codebuddy","display_name":"GLM-5.3-Flash","context_length":1000000,"supports_reasoning":false,"supports_tools":false,"supports_vision":false,"supports_audio":false},
      {"id":"trae-cn/custom_model_gemini","object":"model","created":1753600000,"owned_by":"trae","display_name":"Gemini-3.1-Pro-Preview","supports_reasoning":false,"supports_tools":false,"supports_vision":false,"supports_audio":false}
    ]}
    """.trimIndent()

    /** Parse the payload the way `OpenAIModelsApi.fetchModels` does. */
    private fun parseRows(body: String): List<LLMModel> {
        val data = JSONObject(body).getJSONArray("data")
        val out = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.getString("id")
            val meta = parseOpenAIModelEndpointMetadata(obj)
            out.add(
                LLMModel(
                    id = id,
                    displayName = meta.displayName ?: id,
                    provider = "Custom",
                    contextWindow = meta.contextWindow,
                    maxOutputTokens = meta.maxOutputTokens,
                    supportsReasoning = meta.supportsReasoning,
                ),
            )
        }
        return out
    }

    @Test
    fun `the gateway window survives the whole chain`() {
        val rows = parseRows(realGatewayBody).associateBy { it.id }

        assertEquals(960_000, rows["codebuddy-cn/hy4free"]!!.contextWindow)
        assertEquals(272_000, rows["chatgpt/gpt-6-luna"]!!.contextWindow)
        assertEquals(1_000_000, rows["codebuddy-cn/glm-5.3-flash"]!!.contextWindow)
    }

    @Test
    fun `the gateway display name survives the chain`() {
        val rows = parseRows(realGatewayBody).associateBy { it.id }
        assertEquals("Hy4", rows["codebuddy-cn/hy4free"]!!.displayName)
        assertEquals("GPT-6-Luna", rows["chatgpt/gpt-6-luna"]!!.displayName)
    }

    @Test
    fun `a row the gateway says nothing about keeps a null window`() {
        val rows = parseRows(realGatewayBody).associateBy { it.id }
        assertNull(rows["trae-cn/custom_model_gemini"]!!.contextWindow)
    }

    @Test
    fun `a registry row cannot overwrite the gateway window`() {
        // The models.dev value for hy4free does not exist, but the general
        // shape is what matters: even when the registry HAS a row, the
        // gateway's number must survive.
        val rows = parseRows(realGatewayBody).associateBy { it.id }
        val merged = mergeRegistryRow(
            model = rows["codebuddy-cn/hy4free"]!!,
            registryContextWindow = 200_000,
            registryMaxOutputTokens = 8_192,
            registrySupportsReasoning = true,
            registryInterleavedField = null,
            registryInputModalities = listOf("text", "image"),
            registryOutputModalities = listOf("text"),
            registryReasoningEffortValues = null,
            registryDeclaresNoEffortTiers = false,
        )
        assertEquals(960_000, merged.contextWindow)
    }

    @Test
    fun `the row the user was looking at now resolves to the real window`() {
        // The end-to-end claim: `codebuddy-cn/hy4free` — the model on the
        // user's screen when it showed 128K — now carries 960 000 through
        // parse AND merge.
        val rows = parseRows(realGatewayBody).associateBy { it.id }
        val merged = mergeRegistryRow(
            model = rows["codebuddy-cn/hy4free"]!!,
            registryContextWindow = null,
            registryMaxOutputTokens = null,
            registrySupportsReasoning = null,
            registryInterleavedField = null,
            registryInputModalities = null,
            registryOutputModalities = null,
            registryReasoningEffortValues = null,
            registryDeclaresNoEffortTiers = false,
        )
        assertEquals(960_000, merged.contextWindow)
        assertEquals(LLMModel.ContextWindowSource.EXPLICIT, merged.contextWindowSource)
    }
}
