package com.rikkaminis.app.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-provider-key-roulette] LRU multi-key rotation for provider API keys
 * (RikkaHub KeyRoulette parity). When a provider instance's stored key
 * contains MULTIPLE keys separated by whitespace/commas, each new provider
 * build picks the least-recently-used key; single keys are returned verbatim.
 *
 * ## Choke-point rule (read this before calling it)
 * Rotation belongs at the single entry each *subsystem* funnels through, never
 * at individual call sites — a stored key reaches the network through more
 * doors than just the chat provider:
 *  - chat / agent / offload worker → `ProviderFactory.create`
 *  - model-list fetch (`/v1/models`) → `ModelListProviderRegistry.fetchModels`
 *  - voice (ASR/TTS) → `VoiceProviderFactory.make`
 *  - debug connectivity probe → `ProviderMutationMethods` models probe
 * The 2026-09 history is the reason this list is spelled out: rotation was
 * "unified" in ProviderFactory, which fixed chat only, and every non-chat door
 * kept sending `Bearer k1, k2, k3` → 401 (model refresh silently kept the old
 * list, voice tests failed). Add the door to this list when you add one.
 *
 * - LRU state persists to `key_roulette.json` under the app cache dir. There
 *   is no time-based expiry: the persisted slice is rebuilt from the
 *   provider's *current* key list on every draw, so a key removed from the
 *   stored key string drops out on the next draw, and a key that simply goes
 *   unused stays at the oldest position — which is exactly what makes it the
 *   next one selected. (T4-L3: the old 24h-expiry KDoc described a check that
 *   compared a monotonic draw counter against a millisecond window.)
 * - The in-memory map is the source of truth during a process lifetime;
 *   the file is a cold-start hint.
 * - Thread-safe via a synchronized block; the map itself is concurrent.
 * - Fully synchronous (cheap file I/O via runCatching) so callers on the
 *   main thread (selectEntry etc.) can call it directly.
 */
object KeyRoulette {

    private val SPLIT = Regex("[\\s,]+")
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
     * Cleaned, de-duplicated key list for [keys] — the rotation candidates.
     * A single key yields a one-element list; blank input yields none.
     */
    fun candidates(keys: String): List<String> = split(keys)

    /**
     * Pick the next key for [providerId] from a possibly multi-key [keys]
     * string. A key string that splits into a single cleaned token (one key,
     * duplicated tokens like "k1, k1", or stray whitespace) is returned as
     * that cleaned token; fully blank input falls through verbatim. No state
     * is touched on these paths.
     */
    fun next(keys: String, providerId: String = ""): String {
        val list = split(keys)
        // [T-provider-key-roulette] A list that collapses to one token must
        // hand back the CLEANED token, not the raw input — sending "k1, k1"
        // as a Bearer value would fail auth. Empty list (blank input) is left
        // verbatim for the caller to handle.
        if (list.size <= 1) return list.firstOrNull() ?: keys
        synchronized(lock) {
            // [T-provider-key-roulette] Monotonic draw counter is the true LRU
            // key — wall-clock ms collides when draws land in the same
            // millisecond (bursty retry loops), biasing rotation toward one key.
            val now = ++drawCounter
            val stale = list.map { it to (lastUsed["$providerId|$it"] ?: 0L) }
                .minByOrNull { it.second }?.first ?: list.first()
            lastUsed["$providerId|$stale"] = now
            persistLocked(providerId, list)
            return stale
        }
    }

    private fun split(key: String): List<String> =
        key.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun stateFile(): File? = cacheDir?.let { File(it, FILE_NAME) }

    private fun persistLocked(providerId: String, list: List<String>) {
        val f = stateFile() ?: return
        runCatching {
            // Rewrite only the current provider's slice; other providers' state
            // comes from the in-memory map (source of truth this process).
            val root = f.takeIf { it.exists() }?.let {
                runCatching { org.json.JSONObject(it.readText()) }.getOrNull()
            } ?: org.json.JSONObject()
            val slice = org.json.JSONObject()
            for (k in list) slice.put(k, lastUsed["$providerId|$k"] ?: 0L)
            // T4-L3: no pruning pass here. The slice only ever contains the
            // provider's current keys, so removed keys vanish on the next draw;
            // the previous `now - stamp < EXPIRE_MS` check compared the
            // monotonic draw counter against a 24h millisecond window and was
            // therefore always true (i.e. it never pruned anything).
            root.put(providerId, slice)
            f.writeText(root.toString())
        }
    }
}
