package com.openminis.app.provider.openai

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.normalizeModalities
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object OpenAIModelsApi {
    private const val TAG = "OpenAIModelsApi"
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("openai")

    // Chat-capable model prefixes (matching iOS)
    private val chatPrefixes = listOf("gpt-", "o1", "o3", "o4-", "codex-", "chatgpt-")

    // Suffixes to exclude (matching iOS)
    private val excludeSuffixes = listOf(
        "-instruct", "-realtime", "-audio", "-transcribe", "-tts", "-embedding"
    )

    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI

        val cacheKey = (baseURL ?: "") + "|" + apiKey
        val ctx = context
        if (shouldConsultCache(ctx != null, forceRefresh) && ctx != null) {
            cache.load(ctx, cacheKey)?.let { return@withContext it }
        }
        val url = buildURL(baseURL)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

        val response = client.newCall(request).execute()
        // [fix/audit-s4m1] try/finally guarantees close across every early
        // return@withContext below (body-null, !isSuccessful, empty data,
        // parse throw). Previously a successful models-list fetch leaked a
        // Response + connection handle.
        try {
            val body = response.body?.string() ?: return@withContext fallback

            if (!response.isSuccessful) {
                if (context != null && (response.code == 401 || response.code == 403)) {
                    cache.invalidate(context, cacheKey)
                }
                return@withContext fallback
            }

            val models = try {
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext fallback
                val parsed = mutableListOf<LLMModel>()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val id = obj.getString("id")

                    // Only filter by chat prefixes for official OpenAI endpoints;
                    // custom endpoints (vLLM, Ollama) may serve any model ID.
                    if (!isCustomBase) {
                        if (!chatPrefixes.any { id.startsWith(it) }) continue
                        if (excludeSuffixes.any { id.contains(it) }) continue
                        if (id.contains(":ft-")) continue
                    }

                    val displayName = obj.optString("name", id)
                    // Third-party gateways (vLLM, OpenRouter-compat proxies) often
                    // report per-model modalities under `architecture.{input,output}_modalities`
                    // the same way OpenRouter does — pick them up so vision/audio
                    // models are routable without waiting for models.dev enrichment.
                    val arch = obj.optJSONObject("architecture")
                    // OpenAI / OpenRouter return modalities as `image_input` / `text_output` with
                    // suffixes; the rest of the codebase (models.dev, capability fragments,
                    // ModelEntryDetailScreen toggles) uses the bare form. Normalize at the parse
                    // boundary so persisted overrides round-trip correctly through the toggles.
                    val inputModalities = arch?.optJSONArray("input_modalities")?.toStringList().normalizeModalities()
                    val outputModalities = arch?.optJSONArray("output_modalities")?.toStringList().normalizeModalities()

                    // T119: known reasoning families (GPT-5.x, o-series, Codex
                    // Mini) get supportsReasoning pre-set to true so the
                    // Thinking pill enables before models.dev enrichment lands
                    // — for brand-new ids (e.g. gpt-5.5) the catalog rarely has
                    // the `reasoning` flag yet, and without this the pill
                    // stays disabled.
                    val idLower = id.lowercase()
                    val knownReasoning = idLower.startsWith("gpt-5") ||
                        idLower.startsWith("o1") ||
                        idLower.startsWith("o3") ||
                        idLower.startsWith("o4") ||
                        idLower.contains("codex")

                    parsed.add(
                        LLMModel(
                            id = id,
                            displayName = displayName,
                            provider = if (isCustomBase) "Custom" else "OpenAI",
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                            supportsReasoning = if (knownReasoning) true else null,
                        )
                    )
                }
                if (parsed.isEmpty()) return@withContext fallback
                ModelsDevApi.enrichModels(parsed)
            } catch (_: Exception) {
                return@withContext fallback
            }

            if (context != null) cache.save(context, cacheKey, models)
            models
        } finally {
            response.close()
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val s = optString(i, "")
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** Check if a base URL points to official OpenAI endpoints. */
    private fun isOfficialOpenAI(baseURL: String): Boolean {
        val lower = baseURL.lowercase()
        return lower.contains("api.openai.com") || lower.contains("chatgpt.com")
    }

    /**
     * Pure decision for whether the 7-day [`ProviderModelsCache`] should be
     * consulted before issuing a live HTTP fetch. A brand-new provider must
     * NOT re-use a stale cache row for the same URL+key, so callers that just
     * added a provider pass `forceRefresh=true` to bypass the cache and
     * re-validate against the live /models endpoint.
     *
     * Extracted out of [fetchModels] so this contract is JVM-testable without
     * an Android `Context`/`SharedPreferences` runtime (the surrounding cache
     * [ProviderModelsCache] API itself needs an Android `Context`).
     */
    internal fun shouldConsultCache(hasContext: Boolean, forceRefresh: Boolean): Boolean =
        hasContext && !forceRefresh

    private fun buildURL(baseURL: String?): String {
        if (baseURL == null) return "https://api.openai.com/v1/models"
        // baseURL is ProviderConfig.effectiveBaseURL — it already has /v1 iff the
        // appendV1Suffix toggle is on. Only append /models; never force /v1, or the
        // toggle is overridden and services without /v1 return 404.
        val base = baseURL.trimEnd('/')
        return "$base/models"
    }
}
