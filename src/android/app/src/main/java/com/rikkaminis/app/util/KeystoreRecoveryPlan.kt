package com.rikkaminis.app.util

import java.io.File

/**
 * [F-1 / F-2 · T-android-keystore-aead-fail] The *decision* half of
 * [EncryptedPrefsFactory]'s recovery path, kept free of Android types so a
 * plain JVM test can drive the real production file.
 *
 * ## Why the decision is its own file
 *
 * The old flow answered every `EncryptedSharedPreferences.create` failure the
 * same way: destroy this store's XML, rebuild, destroy the shared Tink keyset +
 * AndroidKeystore alias, rebuild, then hand out an in-memory store. Two of those
 * three steps are irreversible, and the first one fired on *any* exception —
 * including the transient ones (two threads racing the first MasterKey
 * creation, keystore briefly busy) where the very next attempt would have
 * succeeded. The recovery path was therefore more destructive than the failure
 * it was recovering from.
 *
 * Two changes live here, both expressible without a `Context`:
 *
 * 1. **Retry before destroying** — [RETRY_DELAYS_MS] buys three attempts with a
 *    0/50/150 ms backoff. Transient contention is absorbed; only exhaustion of
 *    all three unlocks a destructive step.
 * 2. **Back up before destroying** — every destructive step is preceded by a
 *    copy to `<name>.recovered-<epochMillis>` ([KeystorePrefsBackup], newest
 *    [KeystoreRecoveryPlan.KEEP_BACKUPS] kept). A wrong decision now costs disk
 *    space instead of credentials.
 *
 * No exception-type classification on purpose: keystore exception types and
 * messages are not stable across Android versions, so a classifier would itself
 * become a silent failure point — the retry absorbs the transient cases and the
 * backup makes the permanent ones survivable.
 *
 * The ordered script is [KeystoreRecoveryPlan.steps]; [runKeystoreRecovery]
 * executes it against a caller-supplied attempt lambda + [KeystoreRecoveryIo].
 * I/O policy (what a "backup" or a "drop" means on disk) stays in the factory —
 * this file only decides *what happens in which order*.
 */

/** The two pieces of on-disk state the factory may destroy, least destructive first. */
internal enum class KeystoreRecoveryTarget {
    /** This store's own `shared_prefs/<fileName>.xml` — losing it costs one store. */
    PrefsFile,

    /**
     * The single Tink keyset blob (`__androidx_security_crypto_encrypted_prefs__.xml`)
     * plus the AndroidKeystore master alias behind it — losing it costs every
     * encrypted store in the app.
     */
    SharedKeyset,
}

/** One step of the recovery script. */
internal sealed interface KeystoreRecoveryAction {
    /** Wait [delayMs], then call `EncryptedSharedPreferences.create` once. */
    data class Retry(val delayMs: Long) : KeystoreRecoveryAction

    /** Copy [target] aside (best effort), then delete it. Backup always precedes the delete. */
    data class BackupThenDrop(val target: KeystoreRecoveryTarget) : KeystoreRecoveryAction

    /** No attempt is left — serve the in-memory store and report degradation. */
    data object Fallback : KeystoreRecoveryAction
}

/** What a single backup/drop step must be able to do. Implemented by the factory. */
internal interface KeystoreRecoveryIo {
    /**
     * Copy [target] to a rotating backup file. Best effort: returning `false`
     * (or throwing) never aborts the recovery — the drop still happens, because
     * a bootable app with lost credentials beats an app that cannot start.
     */
    fun backup(target: KeystoreRecoveryTarget): Boolean

    /** Delete [target]. Always called after [backup] for the same target. */
    fun drop(target: KeystoreRecoveryTarget)
}

/** Result of running the script, in the terms the visibility layer ([F-2]) needs. */
internal data class KeystoreRecoveryOutcome(
    /** True when some attempt produced a real encrypted store. */
    val succeeded: Boolean,
    /** Steps actually executed, in order — truncated at the winning attempt. */
    val executed: List<KeystoreRecoveryAction>,
    /** `create` calls made. */
    val attempts: Int,
    /** Destructive steps taken (0–2). */
    val drops: Int,
) {
    /** True when the caller ends up on [EncryptedPrefsFactory]'s in-memory store. */
    val degraded: Boolean get() = !succeeded

    /** True when this run destroyed nothing — the transient-failure case. */
    val nonDestructive: Boolean get() = drops == 0
}

internal object KeystoreRecoveryPlan {
    /**
     * Attempt wrapper for the 3-try burst. `0` means "try immediately"; the two
     * positive values are short enough not to matter at launch (200 ms total)
     * and long enough to outlast a concurrent MasterKey creation, which is what
     * the old code mistook for permanent corruption.
     */
    val RETRY_DELAYS_MS: List<Long> = listOf(0L, 50L, 150L)

    /** Backup files per source kept on disk (newest win). */
    const val KEEP_BACKUPS: Int = 3

    /**
     * The frozen script. Deliberately a value, not control flow spread over the
     * factory: the order (retry ×3 → prefs file → retry → shared keyset → retry
     * → fallback) is what the tests assert, and it is the same order the KDoc
     * above describes.
     */
    fun steps(): List<KeystoreRecoveryAction> = buildList {
        RETRY_DELAYS_MS.forEach { add(KeystoreRecoveryAction.Retry(it)) }
        add(KeystoreRecoveryAction.BackupThenDrop(KeystoreRecoveryTarget.PrefsFile))
        add(KeystoreRecoveryAction.Retry(0L))
        add(KeystoreRecoveryAction.BackupThenDrop(KeystoreRecoveryTarget.SharedKeyset))
        add(KeystoreRecoveryAction.Retry(0L))
        add(KeystoreRecoveryAction.Fallback)
    }
}

/**
 * Runs [KeystoreRecoveryPlan.steps] once.
 *
 * [attempt] must return true when a real encrypted store was obtained. [sleep]
 * is injectable so tests do not spend 200 ms per arm.
 */
internal fun runKeystoreRecovery(
    attempt: () -> Boolean,
    io: KeystoreRecoveryIo,
    sleep: (Long) -> Unit = { delayMs -> if (delayMs > 0) Thread.sleep(delayMs) },
): KeystoreRecoveryOutcome {
    val executed = mutableListOf<KeystoreRecoveryAction>()
    var attempts = 0
    var drops = 0
    for (step in KeystoreRecoveryPlan.steps()) {
        executed += step
        when (step) {
            is KeystoreRecoveryAction.Retry -> {
                sleep(step.delayMs)
                attempts++
                if (attempt()) {
                    return KeystoreRecoveryOutcome(
                        succeeded = true,
                        executed = executed.toList(),
                        attempts = attempts,
                        drops = drops,
                    )
                }
            }

            is KeystoreRecoveryAction.BackupThenDrop -> {
                io.backup(step.target)
                io.drop(step.target)
                drops++
            }

            KeystoreRecoveryAction.Fallback -> {
                return KeystoreRecoveryOutcome(
                    succeeded = false,
                    executed = executed.toList(),
                    attempts = attempts,
                    drops = drops,
                )
            }
        }
    }
    // Unreachable: the script ends in Fallback. Kept for exhaustiveness.
    return KeystoreRecoveryOutcome(false, executed.toList(), attempts, drops)
}

/**
 * Rotating backups for the files the recovery path deletes.
 *
 * A backup is a byte-for-byte copy in the SAME directory (`shared_prefs/`) named
 * `<source name>.recovered-<epochMillis>` — no new directory, so a user who
 * wants their credentials back can find the file next to the original, and
 * `EncryptedSharedPreferences.create` never mistakes it for live state (it
 * looks for `<fileName>.xml` exactly).
 */
internal object KeystorePrefsBackup {
    const val SUFFIX: String = ".recovered-"

    /**
     * Copies [source] aside, then prunes to [keep] newest backups.
     *
     * Returns the backup file, or `null` when there was nothing to copy
     * ([source] does not exist). Throws only if the copy itself fails — callers
     * are expected to log and continue, never to block recovery on it.
     */
    fun backup(
        source: File,
        keep: Int = KeystoreRecoveryPlan.KEEP_BACKUPS,
        nowMillis: Long = System.currentTimeMillis(),
    ): File? {
        if (!source.isFile) return null
        val dir = source.parentFile ?: return null
        val destination = uniqueDestination(dir, source.name, nowMillis)
        source.copyTo(destination, overwrite = false)
        prune(source, keep)
        return destination
    }

    /** Deletes all but the [keep] newest backups of [source]. Returns how many were removed. */
    fun prune(source: File, keep: Int = KeystoreRecoveryPlan.KEEP_BACKUPS): Int {
        val dir = source.parentFile ?: return 0
        val prefix = source.name + SUFFIX
        val candidates = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && parseStamp(it.name, prefix) != null }
            ?.sortedWith(
                compareByDescending<File> { parseStamp(it.name, prefix)!!.first }
                    .thenByDescending { parseStamp(it.name, prefix)!!.second },
            )
            ?: return 0
        var removed = 0
        candidates.drop(keep).forEach { if (it.delete()) removed++ }
        return removed
    }

    private fun uniqueDestination(dir: File, sourceName: String, nowMillis: Long): File {
        val base = "$sourceName$SUFFIX$nowMillis"
        var candidate = File(dir, base)
        var collision = 0
        while (candidate.exists() && collision < 1000) {
            collision++
            candidate = File(dir, "$base-$collision")
        }
        return candidate
    }

    /** `<prefix><epoch>` or `<prefix><epoch>-<n>` → (epoch, n). Anything else → null. */
    private fun parseStamp(name: String, prefix: String): Pair<Long, Int>? {
        if (!name.startsWith(prefix)) return null
        val rest = name.removePrefix(prefix)
        val dash = rest.indexOf('-')
        val epochPart = if (dash < 0) rest else rest.substring(0, dash)
        val seqPart = if (dash < 0) "0" else rest.substring(dash + 1)
        val epoch = epochPart.toLongOrNull() ?: return null
        val seq = seqPart.toIntOrNull() ?: return null
        return epoch to seq
    }
}

/**
 * [F-2] One resolved instance per store name, plus the set of names currently
 * served from memory.
 *
 * Two behaviours matter and are asserted by tests:
 *
 * - **Reuse by name** — `safeCreate` used to build a fresh
 *   `EncryptedSharedPreferences` per call, and the in-memory fallback per *call*
 *   too: `DeviceIdentity` and `ModelExecutionService` call it on every use, so a
 *   degraded run handed each caller a different empty map. One instance per name
 *   makes the degraded store behave like the real one (writes visible to later
 *   readers in this process).
 * - **Degraded set** — the app can ask "is `provider_secrets` running from
 *   memory?" ([isDegraded] / [degradedStores]) instead of the loss being silent
 *   until the next cold start.
 *
 * Thread-safe; creation happens under the same lock so two launch-path callers
 * cannot both enter the destructive recovery for the same name.
 */
internal class DegradeRegistry<T : Any> {
    private val lock = Any()
    private val instances = HashMap<String, T>()
    private val degraded = LinkedHashSet<String>()

    /**
     * Returns the cached instance for [fileName], creating it with [create] on
     * first use. [create] may safely call [markDegraded] for the same name — the
     * lock is reentrant on this thread and the instance is only cached after
     * [create] returns.
     */
    fun resolve(fileName: String, create: () -> T): T = synchronized(lock) {
        instances[fileName] ?: create().also { instances[fileName] = it }
    }

    fun markDegraded(fileName: String) {
        synchronized(lock) { degraded += fileName }
    }

    fun isDegraded(fileName: String): Boolean = synchronized(lock) { fileName in degraded }

    fun degradedStores(): Set<String> = synchronized(lock) { degraded.toSet() }

    /** The cached instance for [fileName], if it was resolved already. */
    fun peek(fileName: String): T? = synchronized(lock) { instances[fileName] }
}
