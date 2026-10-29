package com.rikkaminis.app.provider.openai

import android.content.Context
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.normalizeModalities
import com.rikkaminis.app.provider.ModelsDevApi
import com.rikkaminis.app.provider.ProviderModelsCache
import com.rikkaminis.app.provider.applyUserAgentOverride
import com.rikkaminis.app.provider.executeOrCancel
import kotlinx.coroutines.CancellationException
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
        // [da1030 ISSUE-06] Azure instances authenticate with the `api-key`
        // header, not `Authorization: Bearer` — without this the models-list
        // fetch got a hard 401 on every Azure provider. Threaded from
        // ProviderInstance.azureMode by ModelListProviderAdapters.
        azureMode: Boolean = false,
        // [dv18-A-08] The family name to stamp on every returned row. A vendor
        // adapter that reuses this fetcher (Kimi Code) fetches from its OWN
        // vendor base URL, which this fetcher cannot recognise as "official
        // OpenAI" — so without this the rows came back labelled "Custom" and
        // `ModelsDevApi`'s provider-aware enrichment (providerKeyMap) could
        // never hit for them, dropping them into the order-dependent
        // all-providers scan (the F-212 failure).
        //
        // null keeps the legacy decision verbatim ("Custom" for a third-party
        // base URL, "OpenAI" for the official ones) — see
        // [resolveProviderName], the single place that decision is made. The
        // `isCustomBase` semantics are untouched either way (chat-prefix
        // filtering, built-in fallback, cache rows, gateway truth).
        providerName: String? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val isCustomBase = baseURL != null && !isOfficialOpenAI(baseURL)
        // For third-party endpoints (vLLM, Ollama, etc.), return empty on failure
        // so the caller preserves existing models instead of replacing with built-in GPT list.
        val fallback = if (isCustomBase) emptyList() else LLMModel.allOpenAI

        val cacheKey = modelsCacheKey(baseURL, apiKey, azureMode)
        val ctx = context
        if (shouldConsultCache(ctx != null, forceRefresh) && ctx != null) {
            // [T-sensenova-effort-enum] A cache entry written before the gateway-truth
            // override existed would still carry models.dev's under-declared tier set
            // and cap the picker, so the correction is re-applied on the cache path too
            // (it is a no-op for every host we have no measurement for).
            cache.load(ctx, cacheKey)?.let {
                return@withContext ModelsDevApi.applyGatewayEffortTruth(
                    it.withProviderName(isCustomBase, providerName), baseURL
                )
            }
        }
        val url = buildModelsURL(baseURL, azureMode)
        val request = buildModelsRequest(url, apiKey, azureMode, customUserAgent)

        // [fix/audit0917-b8] Call cancellation is now wired into the request:
        // `execute()` is blocking and has no cancellation hook, so a cancelled
        // fetchModels (user leaves the screen / switches provider) used to run
        // to completion — including `cache.save` — and hold an OkHttp thread.
        // The completion hook cancels the Call, which makes `execute()` throw
        // immediately; the catch below asks the coroutine whether *it* is still
        // alive and only falls back if it is.
        //
        // [FIX-1 / F-209] This was the ORIGINAL of that idiom and the only
        // place it lived; four sibling fetchers kept the bare execute(). The
        // hook now lives in provider/CallCancellation.kt and every fetcher —
        // including this one — calls it, so there is one implementation instead
        // of five copies of which one was correct.
        val call = client.newCall(request)
        val response = try {
            call.executeOrCancel()
        } catch (e: CancellationException) {
            // The caller must unwind, not fall back.
            throw e
        } catch (_: Throwable) {
            return@withContext fallback
        }
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
                // [r3-b08 F-06] An AUTH answer is not "this endpoint does not do
                // model lists". Gemini already refuses to answer 401/403 with
                // its builtin catalog ([FIX-1 / F-196]); the two other official
                // instances still did, which made a revoked key look like a
                // successful refresh whose result happened to be the static
                // list (ProviderRepository.refreshModels then replaces the
                // user's rows and reports SUCCESS_API).
                return@withContext if (usesBuiltinCatalogOnFailure(isCustomBase, response.code)) {
                    LLMModel.allOpenAI
                } else {
                    emptyList()
                }
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
                            provider = resolveProviderName(isCustomBase, providerName),
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                            supportsReasoning = if (knownReasoning) true else null,
                        )
                    )
                }
                if (parsed.isEmpty()) return@withContext fallback
                // [T-sensenova-effort-enum] For endpoints whose real reasoning_effort
                // enum is measured (see GatewayEffortTruth), the measurement outranks
                // what models.dev declares for these ids — it under-declares every one
                // of them, which silently capped the picker below the tiers the gateway
                // accepts. Host-scoped, so other providers' same-named models are
                // untouched.
                ModelsDevApi.applyGatewayEffortTruth(ModelsDevApi.enrichModels(parsed), url)
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
     * [dv18-A-08] The single decision point for the `provider` field of a row
     * this fetcher returns.
     *
     * [providerName] is the family name the caller's adapter identified itself
     * with (see [fetchModels]); when it is null the legacy decision stands:
     * a third-party base URL yields "Custom", an official OpenAI endpoint
     * yields "OpenAI". Extracted — like [shouldConsultCache] — so the decision
     * is JVM-testable without an Android runtime, and so a caller that wires
     * the parameter but ignores it is visible in one place.
     */
    internal fun resolveProviderName(isCustomBase: Boolean, providerName: String?): String =
        providerName ?: if (isCustomBase) "Custom" else "OpenAI"

    /**
     * [dv18-A-08] Re-label rows that came out of a *cache* row rather than the
     * live parse path: the 48-hour [ProviderModelsCache] row is keyed on
     * URL+key, so it belongs to the requesting family, but entries written
     * before the caller started naming itself still carry the old label.
     * Routes through [resolveProviderName] so the naming decision keeps
     * exactly one home.
     *
     * A null [providerName] returns the SAME list instance — no copy, so the
     * unnamed (legacy) path is byte-for-byte what it was.
     *
     * ponytail: cache rows are only relabelled, never re-enriched — capability
     *   numbers baked in by the old blind scan survive until the TTL lapses.
     *   | ceiling: a user who fetched the Kimi list before this fix sees
     *   wrong context/effort numbers for at most 48h (forceRefresh re-fetches
     *   immediately and the live path enriches with the right key now).
     *   | upgrade trigger: a report that a Kimi model's context window / effort
     *   ladder disagrees with models.dev while its provider field is right —
     *   then the cache path needs `ModelsDevApi.enrichModels` too.
     */
    private fun List<LLMModel>.withProviderName(
        isCustomBase: Boolean,
        providerName: String?,
    ): List<LLMModel> {
        if (providerName == null) return this
        val name = resolveProviderName(isCustomBase, providerName)
        return map { it.copy(provider = name) }
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

    /**
     * [da1030 ISSUE-06] Pure request builder for the models list, extracted so
     * the auth-header + UA contract is JVM-testable without a network stack.
     * Auth goes through [applyOpenAIKeyAuth] — the same single source the chat
     * builders use — so an Azure instance sends `api-key` here too.
     */
    internal fun buildModelsRequest(
        url: String,
        apiKey: String,
        azureMode: Boolean,
        customUserAgent: String?,
    ): Request =
        Request.Builder()
            .url(url)
            .applyOpenAIKeyAuth(azureMode, apiKey)
            // [T-provider-custom-user-agent] models-list UA override.
            .applyUserAgentOverride(customUserAgent)
            .build()

    /**
     * [da1030 ISSUE-06] The 7-day cache row must not be shared between the two
     * auth schemes: an Azure instance and a plain OpenAI-compatible instance can
     * point at the same URL with the same key while only one of them can
     * actually authenticate. The suffix keeps them in separate rows.
     */
    internal fun modelsCacheKey(baseURL: String?, apiKey: String, azureMode: Boolean): String =
        (baseURL ?: "") + "|" + apiKey + "|" + if (azureMode) "azure" else "bearer"
}

private const val OFFICIAL_MODELS_URL = "https://api.openai.com/v1/models"

/**
 * [r3-b08 F-07] The models-list URL, Azure-aware.
 *
 * [baseURL] is `ProviderConfig.effectiveBaseURL` for every non-Azure
 * instance — it already carries `/v1` iff the appendV1Suffix toggle is on, so
 * only `/models` is appended. Azure is different in TWO ways and both used
 * to be handled on the chat path only:
 *
 *  1. its base is `https://{res}.openai.azure.com[/openai]?api-version=…`,
 *     and the data-plane list endpoint is `{root}/openai/models` — a path
 *     segment that must go BEFORE the query string. Appending `/models` to
 *     the whole string produced
 *     `…azure.com?api-version=2024-10-21/v1/models`, i.e. `/v1/models`
 *     inside the api-version VALUE (and the request 400s/404s every time);
 *  2. `effectiveBaseURL` appends `/v1` to that query-carrying string, so the
 *     Azure branch must receive the RAW `customBaseURL` (see
 *     ModelListProviderAdapters) and strip a stray `/v1`/`/models` here.
 *
 * Same split-then-rejoin shape as `OpenAIProvider.azureUrl`, which is what
 * the chat path already does.
 */
internal fun buildModelsURL(baseURL: String?, azureMode: Boolean): String {
    if (baseURL.isNullOrBlank()) return OFFICIAL_MODELS_URL
    if (azureMode) {
        val raw = baseURL.trim()
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var path = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (path.endsWith("/v1")) path = path.dropLast(3).trimEnd('/')
        if (path.endsWith("/models")) path = path.dropLast(7).trimEnd('/')
        if (!path.endsWith("/openai")) path = "$path/openai"
        return "$path/models$query"
    }
    // Non-Azure: baseURL is ProviderConfig.effectiveBaseURL — it already has
    // /v1 iff the appendV1Suffix toggle is on. Only append /models; never
    // force /v1, or the toggle is overridden and services without /v1 404.
    return "${baseURL.trimEnd('/')}/models"
}

/**
 * [r3-b08 F-06] Whether a non-2xx models answer may be swapped for the
 * builtin catalog.
 *
 * Custom bases: never (the caller preserves the existing rows — a vLLM/Ollama
 * endpoint failing is not a licence to relabel its models as OpenAI's).
 * Official instance: only for non-auth failures, where the builtin list is
 * the historical network-failure net. A 401/403 means the credential was
 * REJECTED, and answering it with the static list is what made
 * `ProviderRepository.refreshModels` report `SUCCESS_API` for a revoked key
 * (Gemini reaches the same verdict through `[FIX-1 / F-196]`).
 *
 * Boolean rather than "return the list" so the table is unit-testable
 * without constructing a provider catalog (see ModelsApiFallbackTest).
 */
internal fun usesBuiltinCatalogOnFailure(isCustomBase: Boolean, statusCode: Int): Boolean =
    !isCustomBase && statusCode != 401 && statusCode != 403
