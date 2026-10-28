package com.rikkaminis.app.provider.gemini

import com.rikkaminis.app.provider.executeOrCancel

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
    private const val TAG = "GeminiModelsApi"

    /**
     * [fix/audit-b22 / T5-L6] Mirror GeminiProvider's base handling: the base
     * carries the API version (`.../v1beta`), but users often paste either
     * form into the provider settings, so tolerate both.
     *
     * [deep-audit-1024 ISSUE-02] Delegates to [GeminiBasePath.normalize] instead of
     * keeping a copy of the rule. The copy stripped each suffix **once**, so the
     * `.../v1beta/v1` shape that `appendV1Suffix` (default `true`) produces on a
     * `.../v1beta` base collapsed to `.../v1beta/v1beta` → the model list 404'd for
     * exactly the custom/relay bases this helper exists to tolerate, while chat (which
     * already delegated) worked. Blank input still falls back to the same public
     * `.../v1beta` endpoint, now taken from [GeminiBasePath.DEFAULT_BASE] — the two
     * literals were identical, so the default is unchanged.
     */
    private fun normalizeBase(baseUrl: String?): String = GeminiBasePath.normalize(baseUrl)

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
        // [fix/audit-b22 / T5-L6] Mirror GeminiProvider's base handling: the base
        // carries the API version (`.../v1beta`), but users often paste either
        // form into the provider settings, so tolerate both.
        baseUrl: String? = null,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [FIX-1 / F-196] True when this instance authenticates with OAuth. The
        // documented 403 fallback belongs to exactly that case (a Cloud Code
        // Assist token without the generative-language scope); an API-key 403
        // means the key was rejected, and answering it with the static catalog
        // hides that from the user.
        oauthCredential: Boolean = false,
        // [T-provider-custom-user-agent] models-list UA override (da1020
        // ISSUE-05: Gemini/OpenRouter were the only fetchers not accepting it).
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini

        val base = normalizeBase(baseUrl)
        // [r3-b08 F-02] A third-party relay is NOT Google: when its model list
        // cannot be read there is no "builtin catalog" to answer with, and
        // returning `LLMModel.allGemini` made a 404/502/parse failure look like a
        // successful refresh whose result happened to be five Google models —
        // ProviderRepository then replaced the relay's rows (and pruned group
        // memberships) instead of preserving them. Same verdict the OpenAI
        // fetcher reaches with `isCustomBase`.
        val fallback: List<LLMModel> =
            if (isOfficialGeminiBase(baseUrl)) LLMModel.allGemini else emptyList()
        val cacheKey = "key|$base|" + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val builder = Request.Builder()
        builder.url("$base/models?key=$apiKey")

        // [T-provider-custom-user-agent] models-list UA override; falls back to
        // the branded default when the instance has no custom UA.
        builder.applyUserAgentOverride(customUserAgent)
        // [audit-0917] execute() moved INSIDE the try. It was outside, so an
        // IOException (offline, DNS failure, TLS reset — the common case for a
        // model-list refresh) propagated out of fetchModels instead of
        // returning the built-in fallback the way every HTTP error path does.
        val response = try {
            // [FIX-1 / F-209] Cancellable execute — see provider/CallCancellation.kt.
            client.newCall(builder.build()).executeOrCancel()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled fetch must unwind, not masquerade as a network error
            // and hand back the builtin list.
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "models fetch failed (fallback=${fallback.size} rows): ${e.message}")
            return@withContext fallback
        }
        try {
            val body = response.body?.string() ?: return@withContext fallback

            if (!response.isSuccessful) {
                // [FIX-1 / F-196] 401/403 is an AUTH answer, not "this endpoint
                // does not do model lists". Returning the builtin catalog for it
                // made a revoked key, a WAF block or a regional denial look like
                // a successful refresh whose result happened to be the static
                // list — the user got no signal and the UI reported success. The
                // 403 fallback exists for one specific case: an OAuth token that
                // carries no generative-language scope (Cloud Code Assist), where
                // the builtin list is genuinely the best answer. Restrict it to
                // that case and let an api-key 401/403 surface as a failure.
                if (response.code == 401 || response.code == 403) {
                    if (context != null) cache.invalidate(context, cacheKey)
                    if (oauthCredential) {
                        // The documented case: OAuth without the
                        // generative-language scope. Builtin list is the best
                        // available answer, matching iOS.
                        return@withContext LLMModel.allGemini
                    }
                    android.util.Log.w(
                        TAG,
                        "models fetch auth failure code=${response.code} — not falling back to builtin list: ${body.take(300)}",
                    )
                    return@withContext emptyList()
                }
                return@withContext fallback
            }

            val models = try {
                val json = JSONObject(body)
                val arr = json.optJSONArray("models") ?: return@withContext fallback
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
                if (result.isEmpty()) return@withContext fallback
                ModelsDevApi.enrichModels(result)
            } catch (_: Exception) {
                return@withContext fallback
            }

            if (context != null) cache.save(context, cacheKey, models)
            models
        } finally {
            response.close()
        }
    }
}

/**
 * [r3-b08 F-02] Whether this model-list request addresses Google itself.
 *
 * Blank/null (no custom base) means the public endpoint — the historical
 * official instance. Any other host is a user relay, where the builtin catalog
 * is not an answer to "what models does this endpoint serve".
 *
 * Host-based rather than `customBaseURL != null` so a user who pasted the
 * public endpoint into the custom-base field is still treated as official.
 *
 * Callers branch on this because an official instance keeps the builtin Google
 * list as its network-failure net, while a relay gets EMPTY: `ProviderRepository
 * .refreshModels` then takes its `models.isEmpty()` path and PRESERVES the rows
 * the user already has instead of replacing them with five Google models. (The
 * `oauthCredential` 403 case is handled separately and deliberately keeps the
 * builtin list — the documented Cloud Code Assist scope fallback,
 * `[FIX-1 / F-196]`.)
 */
internal fun isOfficialGeminiBase(baseUrl: String?): Boolean {
    val lower = baseUrl?.trim()?.lowercase()
    if (lower.isNullOrEmpty()) return true
    return lower.contains("generativelanguage.googleapis.com") ||
        lower.contains("cloudcode-pa.googleapis.com")
}
