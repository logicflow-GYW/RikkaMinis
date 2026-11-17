package com.rikkaminis.app.data

import java.io.File
import java.security.MessageDigest
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
 * - LRU state persists to `key_roulette.json` under the app cache dir. The
 *   persisted keys (and the in-memory map keys, same shape) are SHA-256
 *   truncated hashes of the plaintext key — [sweep1014-FIX-7] — so the
 *   plaintext never touches disk, matching the "so the plaintext never
 *   touches disk" rule the cross-process request.json chain already follows.
 *   [rv1014-D2] Legacy files that still store plaintext slice keys are
 *   migrated in place on the first init() after the upgrade (hash + atomic
 *   rewrite, rotation stamps preserved), so the no-plaintext-on-disk claim
 *   holds for pre-existing files too.
 *   There
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
    // [sweep1014-FIX-7] Persisted/in-memory keys are truncated SHA-256 hex of
    // the plaintext key. Files written before this change stored plaintext
    // keys; init() migrates them in place ([rv1014-D2]: hash + atomic rewrite,
    // stamps preserved) so the plaintext does not stay on disk.
    private val HASH_KEY = Regex("[0-9a-f]{32}")

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
            val obj = org.json.JSONObject(raw)
            // [rv1014-D2] One-time migration: files written before FIX-7 store
            // plaintext keys as slice keys. Hash the plaintext keys and
            // rewrite the file (stamps preserved) so the plaintext does not
            // stay on disk forever. Idempotent: an already-hash-only file
            // needs no rewrite. The rewrite is atomic (temp file + move), so
            // a failure leaves the original intact.
            val dropped = mutableListOf<String>()
            val sanitized = sanitizeLegacyRouletteEntries(obj, dropped)
            if (sanitized != null) {
                rewriteSanitized(f, sanitized)
                if (dropped.isNotEmpty()) {
                    println("KeyRoulette: migrated legacy plaintext entries in $FILE_NAME to hash keys (${dropped.size} blank/illegal key(s) dropped)")
                }
            }
            // Restore stamps from the sanitized shape (hash keys) — identical
            // stamps to the legacy file, now under the keys the draw path
            // actually looks up.
            val loadFrom = sanitized ?: obj
            val keys = loadFrom.keys()
            while (keys.hasNext()) {
                val providerId = keys.next()
                val inner = loadFrom.optJSONObject(providerId) ?: continue
                val ikeys = inner.keys()
                while (ikeys.hasNext()) {
                    val k = ikeys.next()
                    // [sweep1014-FIX-7] Only hash-shaped keys are restored;
                    // with [rv1014-D2] in place this guard is a no-op on a
                    // migrated file and only trips on a corrupt entry.
                    if (!HASH_KEY.matches(k)) continue
                    lastUsed.putIfAbsent("$providerId|$k", inner.optLong(k, 0L))
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
     * [rv1014-D2] One-time migration of a legacy `key_roulette.json`: replace
     * plaintext slice keys with their SHA-256 truncated hashes (stamps
     * preserved verbatim, so LRU order survives the upgrade). Returns null
     * when the file needs no migration (all keys already hash-shaped) — the
     * caller then skips the rewrite, making the migration idempotent.
     *
     * Blank/illegal keys cannot be hashed meaningfully and are dropped
     * (collected into [droppedKeys] for logging). Entries whose value is not
     * a JSONObject are preserved verbatim. Never throws.
     *
     * Internal (not private) so the migration semantics are JVM-testable;
     * [hashKey] is the same function the draw path uses, so a migrated stamp
     * is found by the very next draw.
     */
    internal fun sanitizeLegacyRouletteEntries(
        obj: org.json.JSONObject,
        droppedKeys: MutableList<String> = mutableListOf(),
    ): org.json.JSONObject? {
        var changed = false
        val out = org.json.JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val providerId = keys.next()
            val inner = obj.optJSONObject(providerId)
            if (inner == null) {
                out.put(providerId, obj.opt(providerId))
                continue
            }
            val outInner = org.json.JSONObject()
            val ikeys = inner.keys()
            while (ikeys.hasNext()) {
                val k = ikeys.next()
                when {
                    HASH_KEY.matches(k) -> outInner.put(k, inner.optLong(k, 0L))
                    k.isNotBlank() -> {
                        outInner.put(hashKey(k), inner.optLong(k, 0L))
                        changed = true
                    }
                    else -> {
                        // Blank/illegal key: cannot hash, drop and record.
                        droppedKeys.add(k)
                        changed = true
                    }
                }
            }
            out.put(providerId, outInner)
        }
        return if (changed) out else null
    }

    /**
     * [rv1014-D2] Atomically replace the state file: write to a temp file
     * first, then move over the original. A failure at any point leaves the
     * original file untouched (the migration retries on the next cold start)
     * and cleans up the temp file.
     *
     * Also the write path for the hot rotation ([persistLocked], [W1118-D5
     * F-05]) — the payload there is built fresh, not sanitized, but "replace
     * the whole file" needs the same atomicity either way.
     */
    private fun rewriteSanitized(f: File, sanitized: org.json.JSONObject) {
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(sanitized.toString())
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }.onFailure {
            runCatching { tmp.delete() }
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
            // [sweep1014-FIX-7] Map keys are hashes of the plaintext key (same
            // shape as the persisted file) so lookups, pruning and the
            // cold-start restore all speak the same identifier.
            val stale = list.map { it to (lastUsed["$providerId|${hashKey(it)}"] ?: 0L) }
                .minByOrNull { it.second }?.first ?: list.first()
            lastUsed["$providerId|${hashKey(stale)}"] = now
            persistLocked(providerId, list)
            return stale
        }
    }

    private fun split(key: String): List<String> =
        key.split(SPLIT).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /**
     * [sweep1014-FIX-7] SHA-256 of the plaintext key, truncated to the first
     * 16 bytes as lowercase hex. Deterministic and collision-free for LRU
     * purposes; the plaintext is never persisted or logged.
     */
    private fun hashKey(k: String): String =
        MessageDigest.getInstance("SHA-256").digest(k.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    private fun stateFile(): File? = cacheDir?.let { File(it, FILE_NAME) }

    private fun persistLocked(providerId: String, list: List<String>) {
        // [fix/audit0917-b8] Prune the in-memory map for this provider before
        // writing the slice. The old comment was only true of the *file*: a key
        // removed from the stored key string kept its `lastUsed` entry in
        // memory forever (unbounded map growth over a long session, and the
        // stale stamp would be resurrected if the user pasted the key back).
        // Called under [lock], and the map is concurrent, so a snapshot of the
        // keys is enough.
        val prefix = "$providerId|"
        // [sweep1014-FIX-7] keep-set is hashed: the map keys are hashes now.
        val keep = list.map { hashKey(it) }.toHashSet()
        for (existing in lastUsed.keys.toList()) {
            if (existing.startsWith(prefix) && existing.removePrefix(prefix) !in keep) {
                lastUsed.remove(existing)
            }
        }
        val f = stateFile() ?: return
        runCatching {
            // Rewrite only the current provider's slice; other providers' state
            // comes from the in-memory map (source of truth this process).
            val root = f.takeIf { it.exists() }?.let {
                runCatching { org.json.JSONObject(it.readText()) }.getOrNull()
            } ?: org.json.JSONObject()
            val slice = org.json.JSONObject()
            for (k in list) slice.put(hashKey(k), lastUsed["$providerId|${hashKey(k)}"] ?: 0L)
            // T4-L3: no pruning pass here. The slice only ever contains the
            // provider's current keys, so removed keys vanish on the next draw;
            // the previous `now - stamp < EXPIRE_MS` check compared the
            // monotonic draw counter against a 24h millisecond window and was
            // therefore always true (i.e. it never pruned anything).
            root.put(providerId, slice)
            // [W1118-D5 F-05] Same atomic replace the migration uses: a
            // truncate+write here loses the file (or leaves half a JSON object)
            // if the process dies mid-write, and every provider's LRU order is
            // read back from this file on the next cold start.
            rewriteSanitized(f, root)
        }
    }
}
