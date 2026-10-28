package com.rikkaminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.tools.AtomicFileWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages environment variables with encrypted value storage.
 * Metadata (key names, IDs) stored in JSON file.
 * Values stored in EncryptedSharedPreferences (AES256-GCM).
 * Mirrors iOS EnvVarStore.
 */
class EnvVarRepository(private val context: Context) {

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val METADATA_FILE = "env-vars.json"

        /**
         * [T-envvar-index-integrity] Where an undecodable index is preserved
         * before the next save overwrites it.
         */
        private const val CORRUPT_METADATA_FILE = "$METADATA_FILE.corrupt"

        private const val ENCRYPTED_PREFS_NAME = "env_var_values"
        private val KEY_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*$")
    }

    data class EnvVarEntry(
        val id: String = UUID.randomUUID().toString(),
        val key: String,
        /**
         * Optional human-readable description of what this variable is for.
         * Empty when omitted. Stored in the JSON metadata file (not secret),
         * mirrors iOS EnvVarEntry.note.
         */
        val note: String = "",
        /**
         * Optional grouping label used to organize the variables list (e.g.
         * "Cloudflare", "GitHub"). Empty string = uncategorized; entries
         * with the same group render under one section header.
         */
        val group: String = "",
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _entries = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val entries: StateFlow<List<EnvVarEntry>> = _entries.asStateFlow()

    /**
     * [T-envvar-index-integrity] True when the on-disk index could not be fully
     * trusted: `env-vars.json` was unparseable, or rows inside it had to be
     * dropped. The in-memory [entries] then may be missing keys that still have
     * (encrypted) values in SharedPreferences — i.e. "we do not know", not
     * "there are none".
     *
     * Only a *successful* load clears it: overwriting the file cannot recover
     * the lost key names, so a write does not repair the flag. Consumers that
     * can degrade safely must be MORE conservative, not less —
     * [com.rikkaminis.app.data.EnvVarRedactor] falls back to shape-based masking
     * while this is set, instead of finding no values and passing secrets
     * through in cleartext with Privacy Mode still shown as ON.
     */
    @Volatile
    var metadataUnreadable: Boolean = false
        private set


    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: self-healing wrapper.
        com.rikkaminis.app.util.EncryptedPrefsFactory.safeCreate(context, ENCRYPTED_PREFS_NAME)
    }

    private val metadataFile: File
        get() = File(context.filesDir, METADATA_FILE)

    init {
        loadMetadata()
    }

    // -- Validation --

    fun isValidKey(key: String): Boolean = KEY_REGEX.matches(key)

    // Keep printable ASCII (0x20-0x7E) and tab (0x09); iOS paste can inject
    // invisible control scalars (e.g. \u009B) that break shell env injection.
    private fun sanitizeValue(value: String): String =
        value.filter { ch ->
            val c = ch.code
            (c in 0x20..0x7E) || c == 0x09
        }

    fun isDuplicateKey(key: String, excludeId: String? = null): Boolean =
        _entries.value.any { it.key.equals(key, ignoreCase = true) && it.id != excludeId }

    // -- CRUD --

    fun add(key: String, value: String, note: String = "", group: String = ""): Boolean {
        val normalizedKey = key.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false
        if (isDuplicateKey(normalizedKey)) return false

        val entry = EnvVarEntry(key = normalizedKey, note = note.trim(), group = group.trim())
        _entries.value = _entries.value + entry
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(value)).apply()
        saveMetadata()
        Log.i(TAG, "Added env var: $normalizedKey")
        return true
    }

    /**
     * Update an entry. [newGroup] is tri-state by design (same shape as the
     * note semantics callers rely on):
     *   - `null`  (default) — keep the entry's current group untouched;
     *   - `""`            — clear the group (back to uncategorized);
     *   - non-empty       — set/replace the group.
     * The default is deliberately "keep", not "": a caller that updates only
     * the note (e.g. the minis-config envvars collection) must not be able to
     * silently flatten the user's grouping by omitting the parameter — the
     * field-evap recurrence class ([T-fix-backup-field-evap],
     * [T-envvar-group-sync]) is exactly what this guards.
     */
    fun update(id: String, newKey: String, newValue: String, newNote: String = "", newGroup: String? = null): Boolean {
        val normalizedKey = newKey.trim().uppercase()
        if (!isValidKey(normalizedKey)) return false

        val current = _entries.value.find { it.id == id } ?: return false

        // Check for duplicate (excluding self)
        if (isDuplicateKey(normalizedKey, excludeId = id)) return false

        // Delete old Keychain entry if key changed
        if (current.key != normalizedKey) {
            encryptedPrefs.edit().remove(current.key).apply()
        }

        // Update metadata
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(key = normalizedKey, note = newNote.trim(), group = (newGroup ?: it.group).trim()) else it
        }

        // Save new value
        encryptedPrefs.edit().putString(normalizedKey, sanitizeValue(newValue)).apply()
        saveMetadata()
        Log.i(TAG, "Updated env var: ${current.key} → $normalizedKey")
        return true
    }

    fun delete(id: String) {
        val entry = _entries.value.find { it.id == id } ?: return
        encryptedPrefs.edit().remove(entry.key).apply()
        _entries.value = _entries.value.filter { it.id != id }
        saveMetadata()
        Log.i(TAG, "Deleted env var: ${entry.key}")
    }

    fun getValue(key: String): String? = encryptedPrefs.getString(key, null)

    /**
     * Returns all env vars as a Map<String, String> for sandbox injection.
     * Reads directly from storage to be thread-safe.
     */
    fun allAsDict(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in _entries.value) {
            val value = encryptedPrefs.getString(entry.key, null)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    // -- Metadata Persistence --

    private fun saveMetadata() {
        try {
            val array = JSONArray()
            for (entry in _entries.value) {
                val obj = JSONObject()
                obj.put("id", entry.id)
                obj.put("key", entry.key)
                if (entry.note.isNotEmpty()) obj.put("note", entry.note)
                if (entry.group.isNotEmpty()) obj.put("group", entry.group)
                obj.put("createdAt", entry.createdAt)
                array.put(obj)
            }
            // [T-envvar-index-integrity] Atomic replace, not `writeText`. The
            // plain write truncates in place, and this process really is killed
            // mid-write (23 silent kills in one evening, 2026-09-15) — the
            // half-written JSON then loads as "the user has no variables", and
            // every consumer (sandbox env, settings list, prompt, redactor)
            // silently degrades. Same idiom as SoulStore.save /
            // AutoBackupLocalStore.writeAtomically.
            AtomicFileWrite.write(metadataFile, array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}")
        }
    }

    /**
     * [T-envvar-index-integrity] Three outcomes, none of them collapsed:
     *
     *  - **file missing** → first launch. An empty list is the truth here.
     *  - **decoded** → the list. If rows had to be dropped, the index is
     *    *partial*: keep what decoded, but raise [metadataUnreadable] so
     *    consumers know the key set is incomplete.
     *  - **unparseable** → the index is *unknown*, not empty. Log it, keep a
     *    `.corrupt` copy of the raw bytes for hand recovery, raise
     *    [metadataUnreadable].
     */
    private fun loadMetadata() {
        val file = metadataFile
        if (!file.exists()) {
            metadataUnreadable = false
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            // Exists but cannot be read: we do not know the index either way,
            // and there is no byte copy to preserve because the read failed.
            metadataUnreadable = true
            AppLogger.warning(TAG, "env-vars.json exists but cannot be read (${e.message}); env-var index is UNKNOWN, not empty")
            return
        }
        when (val decoded = EnvVarMetadataCodec.decode(text)) {
            is EnvVarMetadataCodec.Result.Missing -> metadataUnreadable = false
            is EnvVarMetadataCodec.Result.Decoded -> {
                _entries.value = decoded.entries
                if (decoded.damagedRows == 0) {
                    metadataUnreadable = false
                } else {
                    metadataUnreadable = true
                    quarantineIndex(
                        file,
                        "${decoded.damagedRows}/${decoded.totalRows} rows unusable (${decoded.reason})",
                    )
                    recoverKeysFromValues()
                }
            }
            is EnvVarMetadataCodec.Result.Corrupt -> {
                metadataUnreadable = true
                quarantineIndex(file, "unparseable JSON (${decoded.reason})")
                recoverKeysFromValues()
            }
        }
    }

    /**
     * [T-envvar-index-integrity] The index file holds only the key NAMES; the
     * values live in encrypted preferences keyed by that same name. So a torn
     * index loses names, not data — the names can be rebuilt from the value
     * store, which is the difference between "your variables vanished" and "the
     * list came back".
     *
     * Only runs on a damaged load (a clean load has nothing to recover), and
     * the recovered entries carry no note/group/createdAt because those were
     * only ever in the lost file.
     */
    private fun recoverKeysFromValues() {
        val known = _entries.value.map { it.key }.toSet()
        val recovered = try {
            encryptedPrefs.all.keys
                .filter { it.isNotEmpty() && it !in known }
                .sorted()
                .map { EnvVarEntry(key = it) }
        } catch (e: Exception) {
            // Keystore-backed store unavailable (degraded factory): nothing to
            // recover, and the shape-based redactor fallback still applies.
            Log.e(TAG, "Failed to recover env-var keys from values: ${e.message}")
            return
        }
        if (recovered.isEmpty()) return
        _entries.value = _entries.value + recovered
        AppLogger.warning(
            TAG,
            "recovered ${recovered.size} env-var key(s) from encrypted values after a damaged index",
        )
    }

    /**
     * [T-envvar-index-integrity] Preserve the raw bytes of an index we could not
     * fully decode *before* the next [saveMetadata] overwrites them.
     *
     * Key names are also recoverable from the encrypted value store
     * ([recoverKeysFromValues]), but this file is the ONLY home of `note`,
     * `group`, `createdAt`, and of entries whose value is empty — so it is the
     * difference between "list came back" and "list came back with the user's
     * own annotations". Same shape as SoulStore's backup-before-overwrite.
     *
     * The first quarantine wins: a later one may already be the degenerate file
     * the app wrote after it lost the index, and that is strictly less useful.
     */
    private fun quarantineIndex(file: File, reason: String) {
        val copy = File(file.parentFile, CORRUPT_METADATA_FILE)
        val kept = try {
            if (copy.exists()) {
                true
            } else {
                file.copyTo(copy, overwrite = true)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to quarantine ${file.name}: ${e.message}")
            false
        }
        AppLogger.warning(
            TAG,
            "env-var index unusable: $reason; " +
                if (kept) "raw bytes preserved in $CORRUPT_METADATA_FILE" else "quarantine copy FAILED",
        )
    }
}

/**
 * [T-envvar-index-integrity] Pure decode of `env-vars.json`, split out from
 * [EnvVarRepository] so the missing/valid/partial/corrupt decision is pinned by
 * JVM tests without an Android `Context`.
 *
 * Deliberately does NOT repair: a row that cannot be decoded is dropped and
 * counted, never guessed at. The caller turns `damagedRows > 0` into
 * `metadataUnreadable` so the redactor fails closed.
 */
internal object EnvVarMetadataCodec {

    sealed class Result {
        /** [EnvVarRepository] found no file at all: empty is the truth. */
        object Missing : Result()

        /**
         * The file was a JSON array. [damagedRows] `> 0` means the index is
         * partial — [entries] is what survived, not the full key set.
         */
        class Decoded(
            val entries: List<EnvVarRepository.EnvVarEntry>,
            val totalRows: Int,
            val damagedRows: Int,
            val reason: String,
        ) : Result()

        /** Not a JSON array at all: nothing about the index is known. */
        class Corrupt(val reason: String) : Result()
    }

    /** [text] is `null` when the file does not exist. */
    fun decode(text: String?): Result {
        if (text == null) return Result.Missing
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return Result.Corrupt(e.message ?: "not a JSON array")
        }
        val entries = ArrayList<EnvVarRepository.EnvVarEntry>(array.length())
        var damaged = 0
        var reason = ""
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            // A row with no key cannot be looked up (`getValue("")` is always
            // null) and cannot be redacted against, so it is damage, not data.
            val key = obj?.optString("key", "")?.trim().orEmpty()
            if (obj == null || key.isEmpty()) {
                damaged++
                reason = "row $i " + if (obj == null) "is not an object" else "has no key"
                continue
            }
            entries.add(
                EnvVarRepository.EnvVarEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    key = key,
                    note = obj.optString("note", ""),
                    group = obj.optString("group", ""),
                    createdAt = obj.optLong("createdAt", 0),
                ),
            )
        }
        return Result.Decoded(entries, array.length(), damaged, reason)
    }
}
