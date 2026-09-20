package com.rikkaminis.app.provider

import android.content.Context
import com.rikkaminis.app.data.model.LLMModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Per-provider disk cache for `/v1/models`-style responses, keyed by a
 * SHA-256 hash of the credential so raw API keys / OAuth tokens never land
 * in cache file names. Mirrors iOS `ModelsCache` (7-day TTL, cacheDir-scoped)
 * but namespaces each provider under its own subdirectory so rotating one
 * credential can't poison another family's cache.
 *
 * Construct once per provider (e.g. `ProviderModelsCache("openrouter")`),
 * then call [load] / [save] / [invalidate] with a credential + optional
 * baseURL in the key.
 */
internal class ProviderModelsCache(
    private val namespace: String,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    @Serializable
    private data class Entry(val models: List<LLMModel>, val savedAt: Long)

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "models-cache/$namespace").apply { mkdirs() }

    private fun keyFile(context: Context, cacheKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), "$hex.json")
    }

    fun load(context: Context, cacheKey: String): List<LLMModel>? {
        val file = keyFile(context, cacheKey)
        if (!file.isFile) return null
        val entry = runCatching { JSON.decodeFromString<Entry>(file.readText()) }
            .getOrNull() ?: return null
        if (System.currentTimeMillis() - entry.savedAt > ttlMs) return null
        return entry.models
    }

    fun save(context: Context, cacheKey: String, models: List<LLMModel>) {
        val file = keyFile(context, cacheKey)
        runCatching {
            // [fix/audit0917-b8] tmp + rename (the repo's atomic-write idiom,
            // see RootfsEventLog.writeBootId / MCPOAuthStore). writeText
            // truncates in place, so a mid-write process death left a torn
            // .json that load() silently discards via runCatching — forcing a
            // network refetch that looks like "the cache never worked".
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSON.encodeToString(Entry(models, System.currentTimeMillis())))
            if (!tmp.renameTo(file)) {
                // rename can fail across a bind-mount boundary; fall back to a
                // direct write so the cache is still populated.
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun invalidate(context: Context, cacheKey: String) {
        runCatching { keyFile(context, cacheKey).delete() }
        // [fix/audit0917-b8] Also sweep orphans. Files are named by a hash of
        // the credential + baseURL, so rotating a key (or editing the baseURL)
        // left the previous file behind forever — `load` is only ever called
        // with the *current* key, and the TTL check lives on the read path, so
        // nothing ever deleted them. `invalidate` is the one place we already
        // know the cache for this provider is suspect, and it runs on the
        // failure path (rare), so one directory listing is cheap. The OS also
        // reclaims cacheDir under pressure — this just stops unbounded growth
        // in the meantime.
        //
        // [FIX-1 / F-210] The sweep itself moved to sweepOrphanCacheFiles() so
        // AnthropicModelsCache — the twin implementation of this class — can
        // share it. That twin got the atomic-write half of fix/audit0917-b8 but
        // not this half, which is exactly the "same semantics, two call sites,
        // one fixed" shape this repo keeps re-finding. One shared function
        // means a third drift is not expressible.
        sweepOrphanCacheFiles(cacheDir(context), ttlMs)
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 3600 * 1000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/**
 * [FIX-1 / F-210] Delete cache files in [dir] that are older than [ttlMs].
 *
 * Shared by [ProviderModelsCache] and `AnthropicModelsCache` — the two
 * independent implementations of the same on-disk model cache. Credential-
 * keyed file names mean a rotated key or edited baseURL leaves the previous
 * file behind forever (`load` is only ever called with the *current* key and
 * the TTL check lives on the read path), so the sweep is what bounds growth.
 * Kept as a top-level function rather than a method so the second caller does
 * not have to depend on the first class's constructor.
 */
internal fun sweepOrphanCacheFiles(dir: File, ttlMs: Long) {
    runCatching {
        val cutoff = System.currentTimeMillis() - ttlMs
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".json") && f.lastModified() < cutoff) f.delete()
        }
    }
}
