package com.rikkaminis.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

import com.rikkaminis.app.logging.AppLogger

private const val TAG = "EncryptedPrefsFactory"

/** The single Tink keyset blob shared by every encrypted store (security-crypto). */
private const val SHARED_KEYSET_FILE = "__androidx_security_crypto_encrypted_prefs__.xml"

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
 * Strategy (decided by [KeystoreRecoveryPlan], executed here):
 *  1. Try the normal create, up to 3 times with a 0/50/150 ms backoff. Most
 *     observed failures are transient — a second process creating the MasterKey
 *     at the same moment, keystore briefly busy — and the old code destroyed
 *     this store's XML on the first one. [F-1] Nothing is deleted until the
 *     retry burst is exhausted.
 *  2. Stage 1: back up this store's XML to `shared_prefs/<fileName>.xml.recovered-<epoch>`
 *     (newest 3 kept), then drop only THIS store's XML. That is the common
 *     corruption case (one file's ciphertext no longer decrypts) and the only
 *     recovery step that leaves the other encrypted stores intact.
 *  3. Stage 2: back up + drop the SHARED state. security-crypto keeps ONE Tink
 *     keyset blob and ONE AndroidKeystore alias behind every
 *     EncryptedSharedPreferences instance, so deleting them resets all of them
 *     (provider secrets, WebDAV, env vars, OAuth, device identity, worker key).
 *     Only reachable when stage 1 proved the damage is in the shared keyset.
 *     The copies left behind are the difference between "credentials gone" and
 *     "credentials recoverable by hand".
 *  4. If recreate still fails: fall back to an in-memory SharedPreferences so
 *     the rest of the app sees an empty, read-write store and never crashes.
 *     Fallback is a last-resort safety net — the on-disk file stays untouched
 *     (nothing is promoted back into the encrypted slot) and the degradation is
 *     [F-2] *visible*: [isDegraded] / [degradedStores] report it, the fallback
 *     `commit()` returns `false` (the SharedPreferences contract for "not
 *     persisted"), and the downgrade is logged at error level.
 *
 * Instances are cached per `fileName` ([F-2]), so callers that used to build a
 * fresh store per call (`DeviceIdentity`, `ModelExecutionService`) share one —
 * in particular they share ONE degraded map instead of each getting its own
 * empty one.
 */
object EncryptedPrefsFactory {

    private val registry = DegradeRegistry<SharedPreferences>()

    fun safeCreate(context: Context, fileName: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return registry.resolve(fileName) {
            val created = createOrRecover(appContext, fileName)
            if (created.degraded) {
                // [FIX-R4-T F-08] Record degradation BEFORE announcing it, so a
                // consumer woken by the error snapshot already sees
                // isDegraded(fileName) == true.
                registry.markDegraded(fileName)
                // [FIX-R4-T F-08] Until this line NOTHING in production read
                // `isDegraded` / `degradedStores` (only the definition site and
                // one unit test): a keystore-degraded store silently served an
                // empty, memory-only map, so every credential the user typed
                // looked saved and vanished on the next cold start with no
                // trace anywhere. The class KDoc already claimed this exact
                // line existed ("visible to telemetry through an AppLogger
                // error line whose message carries `step=keystore_degraded`
                // into `error-snapshot-*.log`") — the contract was written,
                // never implemented; the only signal was android.util.Log.e,
                // which reaches `error-snapshot-*.log` never and the daily file
                // only while the logcat tailer happens to be running.
                //
                // One ERROR line (not a new UI surface): the snapshot ring
                // captures the surrounding scene and the message carries the
                // verdict in a greppable form. A user-facing banner and the
                // InMemorySharedPreferences locking are tracked separately —
                // locking is DESIGN-08, already registered.
                AppLogger.error(
                    "EncryptedPrefs",
                    "step=keystore_degraded file=$fileName attempts=${created.attempts} " +
                        "drops=${created.drops} consequence=credentials_in_memory_only " +
                        "next_cold_start_starts_empty reentry_required=true",
                )
            }
            created.prefs
        }
    }

    /** True when [fileName] is currently served from process memory only. */
    fun isDegraded(fileName: String): Boolean = registry.isDegraded(fileName)

    /** Every store name currently served from process memory only. */
    fun degradedStores(): Set<String> = registry.degradedStores()

    private fun createOrRecover(context: Context, fileName: String): Created {
        var obtained: SharedPreferences? = null
        var attemptIndex = 0
        val outcome = runKeystoreRecovery(
            attempt = {
                attemptIndex++
                val result = runCatching { build(context, fileName) }
                result.onFailure {
                    Log.w(TAG, "create($fileName) attempt $attemptIndex failed: ${it.message}")
                }
                obtained = result.getOrNull()
                obtained != null
            },
            io = FileRecoveryIo(context, fileName),
        )

        val prefs = obtained
        if (prefs != null && outcome.succeeded) {
            if (outcome.attempts > 1 || !outcome.nonDestructive) {
                Log.i(
                    TAG,
                    "recovered $fileName after ${outcome.attempts} attempts / " +
                        "${outcome.drops} destructive step(s) — backups left in shared_prefs/",
                )
            }
            return Created(prefs, degraded = false, attempts = outcome.attempts, drops = outcome.drops)
        }

        Log.e(
            TAG,
            "DEGRADED: $fileName is served from process memory only — credentials will NOT " +
                "persist: a cold start starts empty and needs them re-entered. " +
                "Keystore recovery exhausted ${outcome.attempts} attempt(s), " +
                "${outcome.drops} destructive step(s); backups (if any) are " +
                "\"shared_prefs/${fileName}.xml${KeystorePrefsBackup.SUFFIX}<epoch>\".",
        )
        return Created(InMemorySharedPreferences(), degraded = true, attempts = outcome.attempts, drops = outcome.drops)
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
}

private data class Created(
    val prefs: SharedPreferences,
    val degraded: Boolean,
    /** [FIX-R4-T F-08] Recovery attempts / destructive steps that led here. */
    val attempts: Int,
    val drops: Int,
)

private fun prefsDir(context: Context) = File(context.applicationInfo.dataDir, "shared_prefs")

/**
 * Executes [KeystoreRecoveryAction.BackupThenDrop] against the real
 * `shared_prefs/` directory: copy aside first, delete second, always logging so
 * a wrong recovery decision leaves a trail.
 */
private class FileRecoveryIo(
    private val context: Context,
    private val fileName: String,
) : KeystoreRecoveryIo {

    override fun backup(target: KeystoreRecoveryTarget): Boolean {
        val source = fileFor(target)
        if (!source.isFile) return true // nothing to lose
        val destination = runCatching { KeystorePrefsBackup.backup(source) }.getOrNull()
        return if (destination == null) {
            Log.w(TAG, "backup of ${source.name} failed — dropping it anyway (recovery continues)")
            false
        } else {
            Log.w(TAG, "recovery: backed up ${source.name} → ${destination.name}")
            true
        }
    }

    override fun drop(target: KeystoreRecoveryTarget) {
        val file = fileFor(target)
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "delete ${file.name} failed: ${it.message}") }

        if (target != KeystoreRecoveryTarget.SharedKeyset) return
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }.onFailure { Log.w(TAG, "wipe master-key alias failed: ${it.message}") }
    }

    private fun fileFor(target: KeystoreRecoveryTarget): File = when (target) {
        KeystoreRecoveryTarget.PrefsFile -> File(prefsDir(context), "$fileName.xml")
        KeystoreRecoveryTarget.SharedKeyset -> File(prefsDir(context), SHARED_KEYSET_FILE)
    }
}

/**
 * Fail-closed in-memory [SharedPreferences] used when
 * [EncryptedSharedPreferences] can't be created after the recovery script is
 * exhausted.
 *
 * Every read returns the default; every write is kept only in process
 * memory and is never persisted to disk. This guarantees secrets can
 * never silently fall back to plaintext storage. The app continues to
 * work (users see "no credentials stored"), and the next launch retries
 * the encrypted path.
 *
 * [F-2] `commit()` returns **false**: the store did not land on disk, and
 * `false` is exactly the SharedPreferences contract for that. Callers that
 * check it (`ProviderRepository.persistToDbAndMirror`) now log the truth
 * instead of a success they did not get. Nothing throws — a degraded app must
 * still start, and `apply()` simply delegates to `commit()`.
 */
internal class InMemorySharedPreferences : SharedPreferences {
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

internal class InMemoryEditor(
    private val values: MutableMap<String, Any?>,
) : SharedPreferences.Editor {
    private val staged = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()

    // [FIX-R4-T F-08] The degraded store has to obey the SharedPreferences
    // contract exactly, because it is indistinguishable from the real one to
    // every caller. Two violations lived here, and both are the same confusion
    // between "the key is absent" and "the key reads back empty":
    //
    //  * `putString(k, null)` stored the null, so `contains(k)` answered true
    //    while `getString(k)` handed back the caller's default — a write that
    //    the framework defines as a REMOVAL became a phantom key.
    //  * `remove(k)` never cleared `staged[k]`, and commit() applied removals
    //    BEFORE staged writes — so `putString(k, x).remove(k)` landed as a
    //    write, and a secret the user deleted came back. Same class of bug as
    //    the one that made credentials "vanish": the operation ran, the effect
    //    did not.
    //
    // Fix keeps the documented semantics: null put == remove, and for a given
    // key the LAST operation wins regardless of the order it was queued in.
    override fun putString(key: String, value: String?): SharedPreferences.Editor =
        if (value == null) remove(key) else stage(key, value)
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
        if (values == null) remove(key) else stage(key, values)
    override fun putInt(key: String, value: Int): SharedPreferences.Editor = stage(key, value)
    override fun putLong(key: String, value: Long): SharedPreferences.Editor = stage(key, value)
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = stage(key, value)
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = stage(key, value)
    override fun remove(key: String): SharedPreferences.Editor {
        staged.remove(key)
        removed.add(key)
        return this
    }

    /** Stage a write and cancel any earlier removal of the same key. */
    private fun stage(key: String, value: Any?): SharedPreferences.Editor {
        staged[key] = value
        removed.remove(key)
        return this
    }
    override fun clear(): SharedPreferences.Editor { staged.clear(); removed.addAll(values.keys); return this }

    /** Applies to memory, then reports `false`: nothing was persisted. */
    override fun commit(): Boolean {
        removed.forEach { values.remove(it) }
        staged.forEach { (k, v) -> values[k] = v }
        return false
    }

    override fun apply() { commit() }
}
