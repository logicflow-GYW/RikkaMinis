package com.rikkaminis.app.provider.openrouter

import com.rikkaminis.app.provider.executeOrCancel

import android.content.Context
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.normalizeModalities
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.provider.ProviderModelsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenRouterModelsApi {
    private const val TAG = "OpenRouterModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openrouter")

    /**
     * Fetch OpenRouter's model catalog. OpenRouter returns rich metadata
     * alongside each model (context window, max output, reasoning support,
     * input/output modalities) — we parse it here instead of waiting for
     * `ModelsDevApi.enrichModels` to match, matching iOS `OpenRouterModelsAPI`.
     *
     * @param context When provided, results are cached for 7 days at
     *   `cacheDir/models-cache/openrouter/<sha256(apiKey)>.json`. Pass null to
     *   bypass the cache entirely (matches existing callers).
     * @param forceRefresh Skip the cache read but still write-through on success.
     */
    suspend fun fetchModels(
        apiKey: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] models-list UA override (da1020
        // ISSUE-05: Gemini/OpenRouter were the only fetchers not accepting it).
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val cacheKey = apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/OpenMinis/OpenMinis")
            .header("X-Title", "Minis App")
            // [T-provider-custom-user-agent] models-list UA override; falls back
            // to the branded default when the instance has no custom UA.
            .applyUserAgentOverride(customUserAgent)
            .build()

        // [FIX-1 / F-209] Cancellable execute — see provider/CallCancellation.kt.
        // [da1020 ISSUE-05] executeOrCancel moved INSIDE the try, matching
        // GeminiModelsApi: it was outside, so an IOException (offline, DNS
        // failure, TLS reset — the common case for a model-list refresh)
        // propagated out of fetchModels instead of returning the empty fallback
        // the way every HTTP error path does; on a single-key instance the
        // exception then climbed all the way to
        // ProviderRepository.refreshModels, losing the fallback semantics.
        val response = try {
            client.newCall(request).executeOrCancel() { response ->
                val body = response.body?.string() ?: return@executeOrCancel emptyList()

                if (!response.isSuccessful) {
                    if (context != null && (response.code == 401 || response.code == 403)) {
                        cache.invalidate(context, cacheKey)
                    }
                    return@executeOrCancel emptyList()
                }

                val models = try {
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data") ?: return@executeOrCancel emptyList()
                    parseModels(data)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@executeOrCancel emptyList()
                }

                val enriched = ModelsDevApi.enrichModels(models)
                if (context != null) cache.save(context, cacheKey, enriched)
                enriched
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled fetch must unwind, not masquerade as a network error
            // and hand back the empty list.
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "models fetch failed, using empty list: ${e.message}")
            return@withContext emptyList()
        }
        response
    }

    private fun parseModels(data: JSONArray): List<LLMModel> {
        val models = mutableListOf<LLMModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val id = obj.optString("id")
            if (id.isEmpty()) continue
            val name = obj.optString("name", id)

            // OpenRouter exposes `architecture.input_modalities` / `output_modalities`
            // as arrays of short strings (e.g. "text", "image", "audio"). Fall back
            // to `[text, text]` when absent — most text-only models omit these.
            val arch = obj.optJSONObject("architecture")
            // OpenRouter returns modalities as `image_input` / `text_output` with suffixes;
            // the rest of the codebase (models.dev, capability fragments,
            // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
            // boundary so persisted overrides round-trip correctly through the toggles.
            val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
            val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

            val contextWindow = obj.optInt("context_length").takeIf { it > 0 }
            val maxOutputTokens = obj.optJSONObject("top_provider")
                ?.optInt("max_completion_tokens")?.takeIf { it > 0 }

            val supportedParams = obj.optJSONArray("supported_parameters")?.toStringList() ?: emptyList()
            val supportsReasoning = "reasoning" in supportedParams

            models.add(
                LLMModel(
                    id = id,
                    displayName = name,
                    provider = "OpenRouter",
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = if (supportsReasoning) true else null,
                    inputModalities = inputModalities,
                    outputModalities = outputModalities,
                )
            )
        }
        return models
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
