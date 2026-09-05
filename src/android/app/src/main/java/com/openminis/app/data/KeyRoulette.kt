package com.openminis.app.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-provider-key-roulette] LRU multi-key rotation for provider API keys
 * (RikkaHub KeyRoulette parity). When a provider instance's stored key
 * contains MULTIPLE keys separated by whitespace/commas, each new provider
 * build picks the least-recently-used key; single keys are returned verbatim.
 *
 * - LRU state persists to `key_roulette.json` under the app cache dir and
 *   expires after 24h, so a dead key does not stay pinned forever.
 * - The in-memory map is the source of truth during a process lifetime;
 *   the file is a cold-start hint.
 * - Thread-safe via a synchronized block; the map itself is concurrent.
 * - Fully synchronous (cheap file I/O via runCatching) so callers on the
 *   main thread (selectEntry etc.) can call it directly.
 */
object KeyRoulette {

    private val SPLIT = Regex("[\\s,]+")
    private const val EXPIRE_MS = 24 * 60 * 60 * 1000L
    private const val FILE_NAME = "key_roulette.json"

    private val lastUsed = ConcurrentHashMap<String, Long>()
    // [T-provider-key-roulette] Monotonic draw counter is the true LRU key —
    // wall-clock ms collides when draws land in the same millisecond (bursty
    // retry loops), biasing rotation toward one key.
    private var drawCounter = 0L
    private val lock = Any()
    private var cacheDir: File? = null

    /** Call once at app start (cheap, reads a tiny file if present). */
    fun init(cacheDir: File) {
        this.cacheDir = cacheDir
        val f = stateFile() ?: return
        runCatching {
            val raw = f.readText()
            if (raw.isBlank()) return
            org.json.JSONObject(raw).let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val providerId = keys.next()
                    val inner = obj.optJSONObject(providerId) ?: continue
                    val ikeys = inner.keys()
                    while (ikeys.hasNext()) {
                        val k = ikeys.next()
                        lastUsed.putIfAbsent("$providerId|$k", inner.optLong(k, 0L))
                    }
                }
            }
            // Restored stamps are wall-clock epoch ms — huge vs a fresh 0-based
            // counter. Lift the counter above the max restored stamp so LRU
            // ordering survives the cold-start handoff.
            val maxRestored = lastUsed.values.maxOrNull() ?: 0L
            if (maxRestored > drawCounter) drawCounter = maxRestored
        }
    }

    /**
     * Pick the next key for [providerId] from a possibly multi-key [keys]
     * string. Single-key input returns as-is (no state touched).
     */
    fun next(keys: String, providerId: String = ""): String {
        val list = split(keys)
        if (list.size <= 1) return keys
        synchronized(lock) {
            // [T-provider-key-roulette] Monotonic draw counter is the true LRU
            // key — wall-clock ms collides when draws land in the same
            // millisecond (bursty retry loops), biasing rotation toward one key.
            val now = ++drawCounter
            val stale = list.map { it to (lastUsed["$providerId|$it"] ?: 0L) }
                .minByOrNull { it.second }?.first ?: list.first()
            lastUsed["$providerId|$stale"] = now
            persistLocked(providerId, list, now)
            return stale
        }
    }

    private fun split(key: String): List<String> =
        key.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun stateFile(): File? = cacheDir?.let { File(it, FILE_NAME) }

    private fun persistLocked(providerId: String, list: List<String>, now: Long) {
        val f = stateFile() ?: return
        runCatching {
            // Rewrite only the current provider's slice; other providers' state
            // comes from the in-memory map (source of truth this process).
            val root = f.takeIf { it.exists() }?.let {
                runCatching { org.json.JSONObject(it.readText()) }.getOrNull()
            } ?: org.json.JSONObject()
            val slice = org.json.JSONObject()
            for (k in list) slice.put(k, lastUsed["$providerId|$k"] ?: 0L)
            // Prune entries older than the expiry window so the file does not
            // accumulate dead keys forever.
            val pruned = org.json.JSONObject()
            val pkeys = slice.keys()
            while (pkeys.hasNext()) {
                val k = pkeys.next()
                if (now - slice.optLong(k, 0L) < EXPIRE_MS) pruned.put(k, slice.optLong(k, 0L))
            }
            root.put(providerId, pruned)
            f.writeText(root.toString())
        }
    }
}
