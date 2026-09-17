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
        runCatching {
            val dir = cacheDir(context)
            val cutoff = System.currentTimeMillis() - ttlMs
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".json") && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 3600 * 1000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
