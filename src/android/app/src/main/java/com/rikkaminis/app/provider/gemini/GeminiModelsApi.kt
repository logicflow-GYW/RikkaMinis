package com.rikkaminis.app.provider.gemini

import android.content.Context
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.ProviderModelsCache
import com.rikkaminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object GeminiModelsApi {
    private const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * [fix/audit-b22 / T5-L6] Mirror GeminiProvider's base handling: the base
     * carries the API version (`.../v1beta`), but users often paste either
     * form into the provider settings, so tolerate both.
     */
    private fun normalizeBase(baseUrl: String?): String {
        val raw = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return DEFAULT_BASE
        // effectiveBaseURL appends "/v1" when the instance has
        // appendV1Suffix=true (the OpenAI-shaped default). Gemini's version
        // segment is "/v1beta", so collapse either suffix before adding ours —
        // otherwise a relay base became ".../v1/v1beta/models".
        val stripped = raw.removeSuffix("/v1beta").removeSuffix("/v1")
        return "$stripped/v1beta"
    }

    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("gemini")

    /**
     * Fetch the Gemini model catalog. Three auth modes matching iOS
     * `GeminiModelsAPI`:
     *   - API key via `?key=<k>` query param
     *   - OAuth via `Authorization: Bearer <token>` (no key param)
     *   - Cloud Code Assist: no public list endpoint — caller passes
     *     `cloudCodeFallback=true` to short-circuit straight to the built-in
     *     `LLMModel.allGemini` list.
     *
     * The spec also requires a **403 fallback** on OAuth: when the OAuth
     * token lacks the `generative-language` scope (common for Cloud Code
     * Assist tokens) the endpoint returns 403 — we fall back to the built-in
     * list instead of surfacing an error, matching iOS.
     *
     * @param context When provided, enables the 7-day disk cache at
     *   `cacheDir/models-cache/gemini/<sha256>.json`.
     */
    suspend fun fetchModels(
        apiKey: String,
        // [fix/audit-b22 / T5-L6] The Anthropic and OpenAI adapters pass
        // instance.effectiveBaseURL here; Gemini hardcoded the public endpoint,
        // so a relay/proxy base URL silently listed the wrong catalog.
        baseUrl: String? = null,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini

        val base = normalizeBase(baseUrl)
        val cacheKey = "key|$base|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val builder = Request.Builder()
        builder.url("$base/models?key=$apiKey")

        // [T-android-default-ua] brand outbound /v1beta/models request.
        builder.applyUserAgentOverride(null)
        val response = client.newCall(builder.build()).execute()
        try {
            val body = response.body?.string() ?: return@withContext LLMModel.allGemini

            if (!response.isSuccessful) {
                if (context != null && (response.code == 401 || response.code == 403)) {
                    cache.invalidate(context, cacheKey)
                }
                return@withContext LLMModel.allGemini
            }

            val models = try {
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: return@withContext LLMModel.allGemini
                val result = mutableListOf<LLMModel>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.getString("name").removePrefix("models/")
                    val displayName = obj.optString("displayName", name)
                    // Filter to chat-capable models (matching iOS).
                    val supportsGen = obj.optJSONArray("supportedGenerationMethods")
                        ?.let { methods ->
                            (0 until methods.length()).any {
                                methods.getString(it).contains("generateContent")
                            }
                        } == true
                    if (supportsGen) {
                        result.add(LLMModel(name, displayName, "Google"))
                    }
                }
                if (result.isEmpty()) return@withContext LLMModel.allGemini
                ModelsDevApi.enrichModels(result)
            } catch (_: Exception) {
                return@withContext LLMModel.allGemini
            }

            if (context != null) cache.save(context, cacheKey, models)
            models
        } finally {
            response.close()
        }
    }
}
