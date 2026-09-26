package com.rikkaminis.app.provider

import android.content.Context
import android.util.Log
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.thinking.GatewayEffortTruth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches and caches the models.dev provider registry.
 * Used as a fallback when a provider's /v1/models endpoint is unavailable,
 * and as the source of truth for model capabilities (context window, output limit, reasoning).
 *
 * Three-tier cache: in-memory → disk cache → bundled asset fallback.
 */
object ModelsDevApi {
    private const val TAG = "ModelsDevApi"
    private const val SOURCE_URL = "https://models.dev/api.json"
    private const val CACHE_TTL_MS = 48 * 3600 * 1000L // 48 hours

    // Provider-key mapping for enrichment lookups (matches iOS)
    private val providerKeyMap = mapOf(
        "Anthropic" to listOf("anthropic"),
        "Google" to listOf("google", "google-vertex"),
        "OpenAI" to listOf("openai"),
        "OpenRouter" to listOf("openrouter"),
        "Antigravity" to emptyList(), // Custom proxy, no public models.dev entry
        // [FIX-1 / F-212] These two families were missing, so their 14 models
        // fell through to the all-providers scan below and were enriched from
        // whichever relay happened to declare the same bare id first
        // (models.dev is ordered by document, and a relay's numbers win:
        // grok-4.5 came back with aihubmix's 1,000,000 context instead of
        // xai's 500,000; kimi-k3 got a 5-tier effort ladder including
        // `minimal` from greenpt instead of moonshotai's 3). Key names are the
        // models.dev provider ids, same shape as the entries above.
        "xAI" to listOf("xai"),
        "Kimi" to listOf("moonshotai", "moonshotai-cn"),
    )

    private var cachedRegistry: Map<String, ProviderEntry>? = null
    private var cacheTimestamp: Long = 0L
    private val isRefreshing = AtomicBoolean(false)
    private var appContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Must be called once at app startup with application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // MARK: - Public: Fetch models by base URL (fallback)

    fun fetchModels(forBaseURL: String): List<LLMModel> {
        val registry = loadRegistry() ?: return emptyList()

        // Phase 1: Exact API base match (with/without /v1)
        val candidates = normalizedCandidates(forBaseURL)
        for ((_, provider) in registry) {
            val api = provider.api ?: continue
            if (api.isEmpty()) continue
            val normalizedAPI = stripTrailingSlash(api)
            for (candidate in candidates) {
                if (candidate == normalizedAPI) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Exact match ${provider.id} (api=$api) — ${models.size} models")
                    return models
                }
            }
        }

        // Phase 2: Hostname fallback
        val inputHost = extractHost(forBaseURL)
        if (inputHost != null) {
            for ((_, provider) in registry) {
                val api = provider.api ?: continue
                val providerHost = extractHost(api) ?: continue
                if (inputHost == providerHost) {
                    val models = buildModels(provider)
                    Log.d(TAG, "Host match ${provider.id} (host=$providerHost) — ${models.size} models")
                    return models
                }
            }
        }

        Log.d(TAG, "No models.dev match for base URL: $forBaseURL")
        return emptyList()
    }

    // MARK: - Public: Enrich models with models.dev data

    fun enrichModel(model: LLMModel): LLMModel {
        val registry = loadRegistry() ?: return model

        // Try mapped provider keys first
        val keys = providerKeyMap[model.provider] ?: emptyList()
        for (key in keys) {
            val prov = registry[key] ?: continue
            val devModel = prov.models[model.id] ?: continue
            return applyDevData(model, devModel)
        }

        // Fallback: scan all providers for the model ID.
        // [FIX-1 / F-212] Prefer the entry whose registry key matches this
        // model's own provider before the order-dependent scan (see the same
        // note in enrichModels).
        registry[model.provider.lowercase()]?.models?.get(model.id)?.let { devModel ->
            return applyDevData(model, devModel)
        }
        for ((_, prov) in registry) {
            val devModel = prov.models[model.id] ?: continue
            return applyDevData(model, devModel)
        }

        return model
    }

    fun enrichModels(models: List<LLMModel>): List<LLMModel> {
        val registry = loadRegistry() ?: return models
        return models.map { model ->
            val keys = providerKeyMap[model.provider] ?: emptyList()
            for (key in keys) {
                val prov = registry[key] ?: continue
                val devModel = prov.models[model.id] ?: continue
                return@map applyDevData(model, devModel)
            }
            // [FIX-1 / F-212] The scan below is the last resort and it is NOT
            // provider-aware: a relay declaring the same bare id wins on
            // document order alone. Prefer an entry whose registry key matches
            // the model's own provider name first, so an unmapped family (or a
            // user's custom provider) still gets the authoritative row when one
            // exists — the unconditional scan stays as the final fallback so no
            // lookup that used to succeed can start failing.
            registry[model.provider.lowercase()]?.models?.get(model.id)?.let { devModel ->
                return@map applyDevData(model, devModel)
            }
            for ((_, prov) in registry) {
                val devModel = prov.models[model.id] ?: continue
                return@map applyDevData(model, devModel)
            }
            model
        }
    }

    /**
     * [T-sensenova-effort-enum] Replace the declared effort tiers with the gateway's
     * MEASURED enum, for endpoints where models.dev is demonstrably wrong.
     *
     * models.dev's `sensenova` row under-declares every model on that gateway — e.g.
     * `sensenova-6.8-flash-lite` is declared `none|low|medium|high` while the gateway
     * accepts `none|low|medium|high|xhigh`. The declared set drives the thinking-level
     * picker ceiling, so the user could not reach a tier the gateway actually serves.
     * (The wire side is corrected independently in OpenAIProvider; this fixes the UI.)
     *
     * Applied at MODEL-SYNC time: the enum is a property of the ENDPOINT, not of the
     * registry row, and scoping it to the base URL's host keeps it from leaking onto
     * another provider that happens to serve the same bare model id.
     *
     * Older saves keep the un-corrected set until that provider's model list is
     * refreshed; nothing breaks in the meantime because the wire clamp falls back to the
     * measurement rather than to the declared set.
     */
    fun applyGatewayEffortTruth(models: List<LLMModel>, baseUrl: String?): List<LLMModel> {
        if (models.isEmpty() || baseUrl.isNullOrBlank()) return models
        val host = runCatching { URL(baseUrl).host }.getOrNull()?.lowercase() ?: return models
        if (!GatewayEffortTruth.isMeasuredHost(host)) return models
        return models.map { model ->
            val measured = GatewayEffortTruth.tiersFor(host, model.id) ?: return@map model
            if (model.reasoningEffortValues == measured) return@map model
            model.copy(reasoningEffortValues = measured)
        }
    }

    // MARK: - Apply models.dev data

    private fun applyDevData(model: LLMModel, devModel: ModelDevEntry): LLMModel {
        return model.copy(
            contextWindow = devModel.contextWindow ?: model.contextWindow,
            maxOutputTokens = devModel.maxOutputTokens ?: model.maxOutputTokens,
            supportsReasoning = devModel.reasoning ?: model.supportsReasoning,
            interleavedReasoningField = devModel.interleavedField ?: model.interleavedReasoningField,
            inputModalities = devModel.inputModalities ?: model.inputModalities,
            outputModalities = devModel.outputModalities ?: model.outputModalities,
            reasoningEffortValues = devModel.reasoningEffortValues ?: model.reasoningEffortValues,
            declaresNoEffortTiers = if (devModel.declaresNoEffortTiers) true else model.declaresNoEffortTiers,
        )
    }

    // MARK: - Build models from provider entry

    private fun buildModels(provider: ProviderEntry): List<LLMModel> {
        return provider.models.values.mapNotNull { model ->
            val family = model.family?.lowercase() ?: ""
            if (family.contains("embedding") || family.contains("moderation")) return@mapNotNull null
            LLMModel(
                id = model.id,
                displayName = model.name ?: model.id,
                provider = provider.name ?: provider.id,
                contextWindow = model.contextWindow,
                maxOutputTokens = model.maxOutputTokens,
                supportsReasoning = model.reasoning,
                interleavedReasoningField = model.interleavedField,
                inputModalities = model.inputModalities,
                outputModalities = model.outputModalities,
                reasoningEffortValues = model.reasoningEffortValues,
                declaresNoEffortTiers = if (model.declaresNoEffortTiers) true else null,
            )
        }
    }

    // MARK: - URL Matching Helpers

    private fun normalizedCandidates(url: String): List<String> {
        val stripped = stripTrailingSlash(url)
        val results = mutableListOf(stripped)
        if (stripped.endsWith("/v1")) {
            results.add(stripped.dropLast(3))
        } else {
            results.add("$stripped/v1")
        }
        return results
    }

    private fun stripTrailingSlash(s: String): String {
        var r = s
        while (r.endsWith("/")) r = r.dropLast(1)
        return r
    }

    private fun extractHost(urlString: String): String? {
        return try {
            URL(stripTrailingSlash(urlString)).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Registry Cache (3-tier)

    @Synchronized
    private fun loadRegistry(): Map<String, ProviderEntry>? {
        // 1. In-memory cache (fresh)
        val cached = cachedRegistry
        if (cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        // 2. In-memory cache exists but stale — return it, schedule refresh
        if (cached != null) {
            scheduleBackgroundRefresh()
            return cached
        }

        // 3. Disk cache
        val diskResult = loadDiskCache()
        if (diskResult != null) {
            val (parsed, diskDate) = diskResult
            cachedRegistry = parsed
            cacheTimestamp = diskDate
            if (System.currentTimeMillis() - diskDate >= CACHE_TTL_MS) {
                scheduleBackgroundRefresh()
            }
            return parsed
        }

        // 4. Bundled fallback
        val bundled = loadBundledRegistry()
        if (bundled != null) {
            cachedRegistry = bundled
            cacheTimestamp = System.currentTimeMillis()
            scheduleBackgroundRefresh()
            return bundled
        }

        // [FIX-1 / F-206] Chicken-and-egg deadlock: the ONLY writer of the disk
        // cache is refreshFromNetwork(), and its ONLY caller is
        // scheduleBackgroundRefresh() — which every branch above calls EXCEPT
        // this last one. So with no in-memory entry, no disk cache and no
        // bundled asset (the asset was removed by design — loadBundledRegistry
        // short-circuits on FileNotFound and logs exactly that), the registry
        // stayed null FOREVER: enrichModel/enrichModels returned the model
        // untouched on every cold start, silently dropping contextWindow,
        // maxOutputTokens, reasoning capability, modalities,
        // interleavedReasoningField and the effort tiers. Observed on device:
        // three cold starts, three "bundled ... absent" lines, and zero
        // "Background-refreshed models.dev" / "Failed to fetch" lines ever.
        // Kick the refresh here so the NEXT call has a disk cache to read. The
        // @Synchronized on this function is safe: refreshFromNetwork() runs on
        // its own thread and never re-enters loadRegistry().
        scheduleBackgroundRefresh()
        return null
    }

    private fun scheduleBackgroundRefresh() {
        if (!isRefreshing.compareAndSet(false, true)) return
        Thread {
            try {
                refreshFromNetwork()
            } finally {
                isRefreshing.set(false)
            }
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun refreshFromNetwork() {
        try {
            val request = Request.Builder().url(SOURCE_URL).build()
            val response = client.newCall(request).execute()
            try {
                if (!response.isSuccessful) {
                    Log.e(TAG, "models.dev HTTP error: ${response.code}")
                    return
                }
                val body = response.body?.string() ?: return

                val parsed = parseRegistry(body)
                if (parsed != null) {
                    synchronized(this) {
                        cachedRegistry = parsed
                        cacheTimestamp = System.currentTimeMillis()
                    }
                    saveDiskCache(body)
                    Log.d(TAG, "Background-refreshed models.dev registry: ${parsed.size} providers")
                }
            } finally {
                // [fix/audit-s4l1] close on all paths — previously a null body
                // (204 no-content) returned early WITHOUT close, leaking the
                // Response.
                response.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models.dev: ${e.message}")
        }
    }

    // MARK: - Parse registry JSON

    private fun parseRegistry(jsonStr: String): Map<String, ProviderEntry>? {
        return try {
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, ProviderEntry>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val provObj = json.optJSONObject(key) ?: continue
                val entry = parseProviderEntry(key, provObj) ?: continue
                result[key] = entry
            }
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse models.dev JSON: ${e.message}")
            null
        }
    }

    private fun parseProviderEntry(id: String, obj: JSONObject): ProviderEntry? {
        val name = obj.optString("name", "").ifEmpty { null }
        val api = obj.optString("api", "").ifEmpty { null }
        val modelsObj = obj.optJSONObject("models") ?: return ProviderEntry(id, name, api, emptyMap())

        val models = mutableMapOf<String, ModelDevEntry>()
        val modelKeys = modelsObj.keys()
        while (modelKeys.hasNext()) {
            val modelKey = modelKeys.next()
            val modelObj = modelsObj.optJSONObject(modelKey) ?: continue
            models[modelKey] = parseModelDevEntry(modelKey, modelObj)
        }
        return ProviderEntry(id, name, api, models)
    }

    private fun parseModelDevEntry(id: String, obj: JSONObject): ModelDevEntry {
        val name = obj.optString("name", "").ifEmpty { null }
        val family = obj.optString("family", "").ifEmpty { null }

        // Parse limits
        val limitObj = obj.optJSONObject("limit")
        val contextWindow = limitObj?.optInt("context", 0)?.takeIf { it > 0 }
        val maxOutputTokens = limitObj?.optInt("output", 0)?.takeIf { it > 0 }

        // Parse reasoning
        val reasoning = if (obj.has("reasoning")) obj.optBoolean("reasoning") else null

        // Parse interleaved (can be bool or object {"field": "reasoning_content"})
        var interleavedField: String? = null
        if (obj.has("interleaved")) {
            val interleaved = obj.opt("interleaved")
            when (interleaved) {
                is JSONObject -> interleavedField = interleaved.optString("field", "").ifEmpty { null }
                // JSON `true` decodes to Boolean.TRUE and is covered here; the
                // old extra `true ->` branch was unreachable (T5-L3).
                is Boolean -> if (interleaved) interleavedField = "reasoning_content"
            }
        }

        // Parse modalities.input / modalities.output arrays
        val modalitiesObj = obj.optJSONObject("modalities")
        fun parseArray(key: String): List<String>? {
            val arr = modalitiesObj?.optJSONArray(key) ?: return null
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out.takeIf { it.isNotEmpty() }
        }
        val inputModalities = parseArray("input")
        val outputModalities = parseArray("output")

        // [T-reasoning-effort-data-driven] reasoning_options is an array of
        // {type, values?, min?, max?}; pick the `effort` entry's values.
        var reasoningEffortValues: List<String>? = null
        val reasoningOptions = obj.optJSONArray("reasoning_options")
        reasoningOptions?.let { arr ->
            for (i in 0 until arr.length()) {
                val opt = arr.optJSONObject(i) ?: continue
                if (opt.optString("type") != "effort") continue
                val vals = opt.optJSONArray("values") ?: continue
                val out = mutableListOf<String>()
                for (j in 0 until vals.length()) {
                    vals.optString(j, "").takeIf { it.isNotEmpty() }?.let { out.add(it.lowercase()) }
                }
                reasoningEffortValues = out.takeIf { it.isNotEmpty() }
                break
            }
        }
        // [OpenMinis#163] The catalog AFFIRMATIVELY says this model has no
        // effort tiers (reasoning_options present but no usable `effort` entry),
        // as opposed to saying nothing at all — the latter must stay permissive
        // so third-party relays the catalog never heard of keep working.
        val declaresNoEffortTiers = reasoningOptions != null && reasoningEffortValues == null

        return ModelDevEntry(
            id = id,
            name = name,
            family = family,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            reasoning = reasoning,
            interleavedField = interleavedField,
            inputModalities = inputModalities,
            outputModalities = outputModalities,
            reasoningEffortValues = reasoningEffortValues,
            declaresNoEffortTiers = declaresNoEffortTiers,
        )
    }

    // MARK: - Bundled Fallback (deprecated — asset removed in fix/model-list)
    // The bundled models-dev-api.json was removed from Android assets (2026-08-14,
    // feat/remove-models-dev-asset).  This function is kept for backwards compatibility
    // with old builds that still have the asset; it will always return null at runtime.
    // enrich now relies solely on the network cache (disk → in-memory).
    // [T-bundled-fallback-shortcircuit] The asset is gone by design, so every
    // call used to throw + log at ERROR level (observed: 21 E-lines in 1.4s at
    // cold start with an empty disk cache - 7 callers hit this fallback each).
    // Remember the first miss and short-circuit; downgrade the miss log to
    // DEBUG so an unexpected failure still shows up without polluting the log
    // as a fake fault. [backlog item 13]
    @Volatile
    private var bundledAssetAbsent = false

    private fun loadBundledRegistry(): Map<String, ProviderEntry>? {
        if (bundledAssetAbsent) return null
        val ctx = appContext ?: return null
        return try {
            val jsonStr = ctx.assets.open("models-dev-api.json").bufferedReader().readText()
            val parsed = parseRegistry(jsonStr)
            Log.d(TAG, "Loaded bundled models.dev registry: ${parsed?.size ?: 0} providers")
            parsed
        } catch (e: java.io.FileNotFoundException) {
            // Expected: asset removed by design (see block comment above).
            bundledAssetAbsent = true
            Log.d(TAG, "bundled models-dev-api.json absent (removed by design) - short-circuiting fallback")
            null
        } catch (e: Exception) {
            Log.d(TAG, "Failed to load bundled models-dev-api.json: ${e.message}")
            null
        }
    }

    // MARK: - Disk Cache

    private fun getCacheFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "models-dev-cache")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "api.json")
    }

    private fun loadDiskCache(): Pair<Map<String, ProviderEntry>, Long>? {
        val file = getCacheFile() ?: return null
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            val parsed = parseRegistry(jsonStr) ?: return null
            Pair(parsed, file.lastModified())
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDiskCache(jsonStr: String) {
        val file = getCacheFile() ?: return
        try {
            file.writeText(jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
    }

    // MARK: - Data classes

    data class ProviderEntry(
        val id: String,
        val name: String?,
        val api: String?,
        val models: Map<String, ModelDevEntry>,
    )

    data class ModelDevEntry(
        val id: String,
        val name: String?,
        val family: String?,
        val contextWindow: Int?,
        val maxOutputTokens: Int?,
        val reasoning: Boolean?,
        val interleavedField: String?,
        // modalities.input / modalities.output from models.dev (e.g. ["text","image"]).
        val inputModalities: List<String>?,
        val outputModalities: List<String>?,
        // [T-reasoning-effort-data-driven] `values` of the reasoning_options
        // entry whose type == "effort"; null when the model declares only
        // toggle / budget_tokens (different mechanisms, not effort control).
        val reasoningEffortValues: List<String>?,
        // [OpenMinis#163] True when reasoning_options was PRESENT but declared
        // no usable effort tier — "reasons, but takes no reasoning_effort".
        val declaresNoEffortTiers: Boolean = false,
    )
}
