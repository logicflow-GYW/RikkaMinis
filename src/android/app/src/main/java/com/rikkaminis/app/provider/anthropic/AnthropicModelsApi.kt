package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.provider.executeOrCancel

import android.content.Context
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

object AnthropicModelsApi {
    private val client = OkHttpClient()

    /**
     * Fetch models from Anthropic API.
     * @param apiKey The API key
     * @param baseURL Optional custom base URL
     * @param context Optional Android [Context]; when provided, results are cached
     *   for 7 days at `cacheDir/models-cache/<sha256(credential)>.json` (matches
     *   iOS `ModelsCache`). When null, the fetch bypasses the cache entirely.
     * @param forceRefresh When true, skip the cache read but still write-through
     *   on success. Used for explicit "refresh models" UI actions.
     */
    suspend fun fetchModels(
        apiKey: String,
        baseURL: String? = null,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [T-provider-custom-user-agent] Per-provider UA override; null/blank
        // keeps the default UA. Threaded from ProviderRepository.refreshModels.
        customUserAgent: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        // Cache key: base URL included so the same API key across proxies doesn't
        // share cached model lists (vLLM vs api.anthropic.com can differ).
        val cacheKey = (baseURL ?: "") + "|" + apiKey
        if (context != null && !forceRefresh) {
            AnthropicModelsCache.load(context, cacheKey)?.let { return@withContext it }
        }

        // [R5-E F-07 / C-06①] Official-ness is a HOST property, not "the user typed
        // something into the base-URL field". `effectiveBaseURL` appends `/v1` when
        // `appendV1Suffix` is on (the default), so a user who pastes the public
        // endpoint hands us `https://api.anthropic.com/v1` — the old `baseURL != null`
        // test called that a relay: the models fetch then sent `Authorization: Bearer`
        // instead of `x-api-key` (401 from the real endpoint), and the 401/empty
        // split below answered a revoked-looking key with an empty list. Mirrors
        // `isOfficialGeminiBase` (GeminiModelsApi.kt) / OpenAI's `isOfficialOpenAI`.
        val isCustomEndpoint = !isOfficialAnthropicHost(baseURL)

        // Build candidate bases: start with the user-supplied base, then climb the
        // path up to 3 levels. Lets vendor-specific bases like
        // `https://api.deepseek.com/anthropic` discover `/v1/models` at the host root.
        val candidateBases = mutableListOf<String?>(baseURL)
        if (baseURL != null) {
            var current: String = baseURL
            for (i in 0 until 3) {
                val parent = parentPath(current) ?: break
                if (parent == current) break
                candidateBases.add(parent)
                current = parent
            }
        }

        var lastFailureCode: Int = -1
        var lastFailureBody: String = ""
        for ((idx, candidate) in candidateBases.withIndex()) {
            // idx == 0 is the user-supplied base, which arrives already resolved by
            // ProviderConfig.effectiveBaseURL (appendV1Suffix honored). Trust it as-is
            // and only append /models — never force /v1, or the "append /v1" toggle is
            // overridden and services without /v1 return 404. idx > 0 are climbed parent
            // paths used purely for host-root discovery (e.g. deepseek.com/anthropic ->
            // host root /v1/models); those keep the /v1 auto-append.
            val url = buildURL(candidate, forceV1Discovery = idx > 0)
            val requestBuilder = Request.Builder()
                .url(url)
                .header("anthropic-version", "2023-06-01")
                // [T-provider-custom-user-agent] models-list UA override.
                .applyUserAgentOverride(customUserAgent)

            if (candidate != null && !isOfficialAnthropicHost(candidate)) {
                // Relay: Bearer auth
                requestBuilder.header("Authorization", "Bearer $apiKey")
            } else {
                // Official Anthropic host (user default or pasted into the
                // custom-base field): the endpoint only honours x-api-key.
                requestBuilder.header("x-api-key", apiKey)
            }

            val request = requestBuilder.build()
            android.util.Log.d("AnthropicModels", "Fetching models (level=$idx): ${request.url.scheme}://${request.url.host}${request.url.encodedPath} headerKeys=${request.headers.names()} authPresent=${request.headers["Authorization"] != null}")

            val response: Response = try {
                // [FIX-1 / F-209] Cancellable execute — see provider/CallCancellation.kt.
                // Matters most here: this is a per-level fallback loop, so an
                // uncancellable fetch stacked its cost once per candidate base.
                client.newCall(request).executeOrCancel()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AnthropicModels", "Fetch error (level=$idx): ${e.message}")
                continue
            }
            // [fix/audit-s4m1] close on every path — this loop `continue`s on
            // failures and returns on success, and each level previously leaked
            // a Response; the leak amplified across fallback levels.
            try {
                val body = response.body?.string() ?: run {
                    android.util.Log.w("AnthropicModels", "Empty response body (level=$idx)")
                    ""
                }

                if (!response.isSuccessful) {
                    android.util.Log.e("AnthropicModels", "Fetch failed (level=$idx): ${response.code} ${body.take(200)}")
                    // 401/403 almost always means the credential rotated; drop any stale
                    // cache entry so the next lookup hits the network again.
                    if (context != null && (response.code == 401 || response.code == 403)) {
                        AnthropicModelsCache.invalidate(context, cacheKey)
                    }
                    lastFailureCode = response.code
                    lastFailureBody = body
                    continue
                }

                if (idx > 0) {
                    android.util.Log.i("AnthropicModels", "Parent-path fallback succeeded at level=$idx base=$candidate")
                }
                android.util.Log.d("AnthropicModels", "Response ${response.code}, body length: ${body.length}")
                return@withContext parseAndCache(body, isCustomEndpoint, context, cacheKey)
            } finally {
                response.close()
            }
        }

        // All candidate bases failed.
        android.util.Log.e("AnthropicModels", "All ${candidateBases.size} fallback levels failed; lastCode=$lastFailureCode")
        // [r3-b08 F-06] An auth answer is not "this endpoint has no model list".
        // The builtin catalog stays the NETWORK-failure net for the official
        // instance; a 401/403 means the credential was rejected, and answering
        // it with the static list made `refreshModels` report SUCCESS_API and
        // replace the rows (Gemini reaches the same verdict via [FIX-1 / F-196]).
        return@withContext if (usesBuiltinCatalogOnFailure(isCustomEndpoint, lastFailureCode)) {
            LLMModel.allAnthropic
        } else {
            emptyList()
        }
    }

    /**
     * [R5-E C-06①] HTTP 200 but a body that is not a usable model list is NOT a
     * network failure — it is a wrong-endpoint answer. Whether that means "keep
     * the user's rows" (relay) or "stand in the builtin catalog" (official) is
     * decided by the SAME host judgement as the auth header: gating this fallback
     * through [isOfficialAnthropicHost] keeps a relay's 200-HTML/login-page
     * answer from being read as `{"data":[]}` → builtin catalog, and keeps the
     * official instance's malformed-JSON answer on the builtin net.
     */
    private suspend fun parseAndCache(
        body: String,
        isCustomEndpoint: Boolean,
        context: Context?,
        cacheKey: String,
    ): List<LLMModel> {
        val fallback: List<LLMModel> = if (isCustomEndpoint) emptyList() else LLMModel.allAnthropic
        val models = try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return fallback
            val parsed = mutableListOf<LLMModel>()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val id = obj.getString("id")
                val displayName = obj.optString("display_name", id)
                // [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850)
                // /v1/models returns no capability metadata, so without this the
                // model lands with supportsReasoning=null and ChatViewModel hides
                // the Deep Thinking toggle (gate is `== true`). Stamp it up front
                // for Claude thinking models — same approach as OpenAIModelsApi
                // hard-stamping gpt-5.x. enrichModels() below still overlays
                // models.dev's `reasoning` when present (it prefers devModel.reasoning),
                // but this guarantees the toggle for new models models.dev hasn't
                // catalogued yet (e.g. Opus 4.8). Both OAuth and API-key direct
                // Anthropic instances flow through here.
                val reasoning = if (AnthropicProvider.supportsThinking(id)) true else null
                parsed.add(LLMModel(id, displayName, "Anthropic", supportsReasoning = reasoning))
            }
            android.util.Log.i("AnthropicModels", "Parsed ${parsed.size} models: ${parsed.map { it.id }.joinToString()}")
            if (parsed.isEmpty()) return fallback
            ModelsDevApi.enrichModels(parsed)
        } catch (_: Exception) {
            return fallback
        }

        if (context != null) {
            AnthropicModelsCache.save(context, cacheKey, models)
        }
        return models
    }

    /**
     * Build the /models endpoint.
     *
     * For the user-supplied base ([forceV1Discovery] == false) the incoming value is
     * already ProviderConfig.effectiveBaseURL — it has /v1 iff the appendV1Suffix toggle
     * is on — so we only append /models and never inject /v1. Forcing /v1 here would
     * override the toggle and 404 on services that don't expose /v1.
     *
     * For climbed parent candidates ([forceV1Discovery] == true) we still try the
     * host-root /v1/models convention to keep deepseek.com/anthropic-style discovery
     * working; those bases never passed through effectiveBaseURL.
     */
    private fun buildURL(baseURL: String?, forceV1Discovery: Boolean = false): String {
        if (baseURL == null) return "https://api.anthropic.com/v1/models?limit=512"
        val base = baseURL.trimEnd('/')
        val endpoint = when {
            base.endsWith("/v1") -> "$base/models"
            forceV1Discovery -> "$base/v1/models"
            else -> "$base/models"
        }
        return "$endpoint?limit=512"
    }

    /**
     * Strip the last path segment from [base], returning a URL one level up.
     * Returns null when [base] is invalid or already at `scheme://host[:port]`
     * with no remaining path. Mirrors iOS `parentPath(of:)`.
     */
    private fun parentPath(base: String): String? {
        val httpUrl = base.toHttpUrlOrNull() ?: return null
        // pathSegments always contains at least one element (possibly ""). Drop
        // trailing empty segment caused by a trailing slash, then drop the last
        // real segment.
        val segments = httpUrl.pathSegments.toMutableList()
        while (segments.isNotEmpty() && segments.last().isEmpty()) {
            segments.removeAt(segments.size - 1)
        }
        if (segments.isEmpty()) return null
        segments.removeAt(segments.size - 1)
        val builder = httpUrl.newBuilder()
        // Reset path: clear all segments by rebuilding from scratch.
        // okhttp's HttpUrl.Builder doesn't expose a direct setter, so iterate.
        for (i in httpUrl.pathSegments.indices.reversed()) {
            builder.removePathSegment(i)
        }
        for (seg in segments) {
            builder.addPathSegment(seg)
        }
        return builder.build().toString().trimEnd('/')
    }
}

/**
 * [r3-b08 F-06] Whether the builtin Anthropic catalog may stand in for a failed
 * model-list fetch.
 *
 * Custom endpoint: never — the caller preserves the existing rows, and a
 * DashScope-style relay often has no `/v1/models` at all. Official: yes as a
 * network-failure net, but a 401/403 is an AUTH answer — answering it with the
 * static list hid a revoked key behind `ModelRefreshResult.SUCCESS_API`
 * (Gemini's `[FIX-1 / F-196]` reaches the same verdict).
 *
 * Pure + top-level for direct JVM testing (see ModelsApiFallbackTest).
 */
internal fun usesBuiltinCatalogOnFailure(isCustomEndpoint: Boolean, lastFailureCode: Int): Boolean =
    !isCustomEndpoint && lastFailureCode != 401 && lastFailureCode != 403

/**
 * [R5-E F-07] Whether [url] points at the official Anthropic endpoint.
 *
 * Host-based rather than "null = official" so a user who pastes the public
 * endpoint into the custom-base field is still treated as official:
 * `ProviderInstance.effectiveBaseURL` appends `/v1` by default, so the value
 * that reaches the provider is `https://api.anthropic.com/v1`, not the bare
 * constant the old `basePath != "https://api.anthropic.com"` test compared
 * against. Tolerated shapes: no scheme (`api.anthropic.com`), trailing slash,
 * `/v1` suffix, any case; null/blank = official default. Anything whose
 * parsed HOST is not exactly `api.anthropic.com` (ports, paths, look-alike
 * domains like `api.anthropic.com.evil.com`, userinfo tricks) is a relay.
 *
 * Pure + top-level for direct JVM testing (see AnthropicOfficialHostTest).
 */
internal fun isOfficialAnthropicHost(url: String?): Boolean {
    val trimmed = url?.trim()
    if (trimmed.isNullOrEmpty()) return true
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val host = try {
        URI(withScheme).host
    } catch (_: Exception) {
        return false
    }
    return host != null && host.equals(OFFICIAL_ANTHROPIC_HOST, ignoreCase = true)
}

private const val OFFICIAL_ANTHROPIC_HOST = "api.anthropic.com"
