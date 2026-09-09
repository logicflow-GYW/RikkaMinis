package com.openminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * T-android-keystore-aead-fail: self-healing wrapper around
 * [EncryptedSharedPreferences.create].
 *
 * The default flow throws `AEADBadTagException` (wrapped as
 * `GeneralSecurityException`) on launch when the AndroidKeystore master
 * key can no longer decrypt the Tink keyset blob — observed on Samsung
 * One UI / Android 16 after backup-restore or biometric re-enroll. The
 * exception bubbles to the main thread and the app dies in a relaunch
 * loop because every cold start hits the same lazy init.
 *
 * Strategy:
 *  1. Try the normal create.
 *  2. On any crypto error: drop the encrypted XML file + the on-disk
 *     Tink keyset prefs file + the AndroidKeystore alias, then retry
 *     once. The user loses stored credentials (they need to re-paste
 *     their API key / re-login OAuth) but the app boots.
 *  3. If recreate still fails: fall back to a PLAIN-TEXT
 *     SharedPreferences so the rest of the app sees an empty,
 *     read-write store and never crashes. Plain-text fallback is a
 *     last-resort safety net — the on-disk file is named with a
 *     "_plain_fallback" suffix so it's distinguishable from real
 *     encrypted state and never gets promoted back to the encrypted
 *     slot on the next launch.
 */
object EncryptedPrefsFactory {
    private const val TAG = "EncryptedPrefsFactory"

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        runCatching { return build(context, fileName) }
            .onFailure { Log.w(TAG, "first create($fileName) failed: ${it.message}") }

        // [T8-M3] Stage 1 — drop only THIS store's XML. That is the common
        // corruption case (one file's ciphertext no longer decrypts) and the
        // only recovery step that leaves the other encrypted stores intact.
        deleteOwnPrefsFile(context, fileName)
        runCatching { return build(context, fileName) }
            .onFailure {
                Log.w(TAG, "rebuild($fileName) after dropping its own XML failed: ${it.message}")
            }

        // [T8-M3] Stage 2 — escalate to the SHARED state. security-crypto keeps
        // ONE Tink keyset blob (__androidx_security_crypto_encrypted_prefs__.xml)
        // and ONE AndroidKeystore alias behind every EncryptedSharedPreferences
        // instance, so deleting them resets all of them (provider secrets,
        // WebDAV, env vars, OAuth, device identity, worker key). Only reachable
        // when stage 1 proved the damage is in the shared keyset — previously
        // this ran unconditionally, so a single corrupt file wiped everything.
        Log.w(TAG, "escalating: shared keyset unreadable — resetting ALL encrypted stores for $fileName")
        wipeSharedKeyset(context)

        runCatching { return build(context, fileName) }
            .onFailure {
                Log.e(TAG, "rebuild($fileName) after shared-key reset failed: ${it.message}", it)
            }

        Log.w(TAG, "falling back to in-memory SharedPreferences for $fileName — credentials lost, nothing persisted")
        return InMemorySharedPreferences()
    }

    private fun build(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

    /** Drop just this store's XML — the least destructive recovery step. */
    private fun deleteOwnPrefsFile(context: Context, fileName: String) {
        runCatching { File(prefsDir(context), "$fileName.xml").delete() }
            .onFailure { Log.w(TAG, "delete own prefs file failed: ${it.message}") }
    }

    /**
     * Drop the state shared by every encrypted store: the single Tink keyset
     * blob plus the AndroidKeystore master alias. Destructive — callers must
     * have already established that the damage is in the shared keyset.
     */
    private fun wipeSharedKeyset(context: Context) {
        runCatching {
            File(prefsDir(context), "__androidx_security_crypto_encrypted_prefs__.xml").delete()
        }.onFailure { Log.w(TAG, "wipe shared keyset file failed: ${it.message}") }

        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "wipe master-key alias failed: ${it.message}") }
    }
}

/**
 * Fail-closed in-memory [SharedPreferences] used when
 * [EncryptedSharedPreferences] can't be created after a wipe+rebuild.
 *
 * Every read returns the default; every write is kept only in process
 * memory and is never persisted to disk. This guarantees secrets can
 * never silently fall back to plaintext storage. The app continues to
 * work (users see "no credentials stored"), and the next launch retries
 * the encrypted path.
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun edit(): SharedPreferences.Editor = InMemoryEditor(values)
}

private class InMemoryEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    private val staged = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { staged[key] = values; return this }
    override fun putInt(key: String, value: Int): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor { staged[key] = value; return this }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { staged[key] = value; return this }
    override fun remove(key: String): SharedPreferences.Editor { removed.add(key); return this }
    override fun clear(): SharedPreferences.Editor { staged.clear(); removed.addAll(values.keys); return this }
    override fun commit(): Boolean {
        removed.forEach { values.remove(it) }
        staged.forEach { (k, v) -> values[k] = v }
        return true
    }
    override fun apply() { commit() }
}
