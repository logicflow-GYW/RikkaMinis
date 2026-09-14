package com.rikkaminis.app.data

import android.content.Context
import android.content.SharedPreferences
import com.rikkaminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Persists a downloaded-but-not-yet-installed APK across Activity recreate /
 * process death.
 *
 * Why this exists: the original update flow held the downloaded [File]
 * reference in a Composable `remember{}` slot. When the user tapped "Open
 * Settings" to grant "install unknown apps" permission, the system pushed
 * Minis to the background; on return the Activity often recreated, the slot
 * was reset, and the UI silently asked the user to download the APK again.
 *
 * Storage: a single SharedPreferences key holding a small JSON blob. We
 * deliberately avoid DataStore here — this object is touched at most a
 * couple times per update flow, blocking access is fine, and SharedPreferences
 * is already initialised elsewhere.
 *
 * Freshness: a [PendingUpdate] older than [MAX_AGE_MS] (24 h) is discarded
 * on read so a stale APK can't auto-install on cold start a week later
 * after the GitHub release was re-rolled.
 *
 * Integrity: we compute and store sha256 of the downloaded file if
 * [setPending] is given the file bytes; if absent, [verify] falls back to
 * (size == expectedSize). Since [fix/update-digest-verify] the record also
 * carries the digest the *release publisher* declared for that asset, so
 * [verify] can fail a file that matches our own re-hash but is not the
 * published build (e.g. a swapped file whose pending record was rewritten).
 */
object PendingUpdateStore {

    private const val TAG = "PendingUpdateStore"
    private const val PREFS = "pending_update"
    private const val KEY = "pending"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    data class PendingUpdate(
        val targetVersionName: String,
        val apkPath: String,
        val apkSize: Long,
        val sha256: String?,
        val downloadedAtMs: Long,
        /**
         * SHA-256 hex the release publisher declared for this asset, when it
         * declared one. Null = the release carries no digest (size-only path).
         * [verify] checks the file against BOTH this and [sha256] so a
         * cross-session swap has to satisfy the publisher's value too.
         */
        val publisherDigest: String? = null,
    )

    private var prefs: SharedPreferences? = null

    /** Idempotent. Safe to call from MinisApp.onCreate. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun requirePrefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    fun setPending(context: Context, pending: PendingUpdate) {
        requirePrefs(context).edit().putString(KEY, encodePending(pending)).apply()
        AppLogger.info(
            TAG,
            "setPending version=${pending.targetVersionName} size=${pending.apkSize} sha256=${pending.sha256 != null} publisherDigest=${pending.publisherDigest != null}",
        )
    }

    /**
     * Returns the persisted pending update, or null when:
     *  - nothing stored
     *  - JSON malformed (treated as gone, cleared)
     *  - older than [MAX_AGE_MS] (cleared)
     *  - target version no longer newer than the running build
     */
    fun getPending(context: Context): PendingUpdate? {
        val p = requirePrefs(context)
        val raw = p.getString(KEY, null) ?: return null
        val pending = decodePending(raw)
        if (pending == null) {
            AppLogger.warning(TAG, "stored JSON malformed, discarding")
            p.edit().remove(KEY).apply()
            return null
        }
        val age = System.currentTimeMillis() - pending.downloadedAtMs
        if (age > MAX_AGE_MS) {
            AppLogger.info(TAG, "pending update expired age=${age}ms; clearing")
            clearPending(context)
            return null
        }
        return pending
    }

    /**
     * [fix/update-digest-verify] Serialization is a pure function pair so the
     * field set can be round-trip tested on the JVM. This is the layer where
     * the "added a field, forgot a serialization side" bug class lives: the
     * four places a [PendingUpdate] field must appear are the data class
     * above, [encodePending] (write), [decodePending] (read) and the consumer
     * ([verify]). A missing write side silently drops the value on save; a
     * missing read side silently drops it on load. Nothing else would fail —
     * `publisherDigest` would just always be null after an app restart, and
     * the digest check would quietly degrade to size-only.
     */
    internal fun encodePending(pending: PendingUpdate): String = JSONObject().apply {
        put("targetVersionName", pending.targetVersionName)
        put("apkPath", pending.apkPath)
        put("apkSize", pending.apkSize)
        if (pending.sha256 != null) put("sha256", pending.sha256) else put("sha256", JSONObject.NULL)
        put("downloadedAtMs", pending.downloadedAtMs)
        if (pending.publisherDigest != null) {
            put("publisherDigest", pending.publisherDigest)
        } else {
            put("publisherDigest", JSONObject.NULL)
        }
    }.toString()

    /**
     * Inverse of [encodePending]. Returns null when the JSON is unparseable
     * (caller clears the record). Missing keys decode to their defaults, so
     * records written before `publisherDigest` existed still load — with
     * `publisherDigest = null`, which is exactly the semantics those records
     * were written under.
     */
    internal fun decodePending(raw: String): PendingUpdate? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return PendingUpdate(
            targetVersionName = obj.optString("targetVersionName"),
            apkPath = obj.optString("apkPath"),
            apkSize = obj.optLong("apkSize"),
            sha256 = obj.optString("sha256", "").takeIf { it.isNotEmpty() && it != "null" },
            downloadedAtMs = obj.optLong("downloadedAtMs"),
            publisherDigest = obj.optString("publisherDigest", "")
                .takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    fun clearPending(context: Context) {
        requirePrefs(context).edit().remove(KEY).apply()
        AppLogger.info(TAG, "clearPending")
    }

    /**
     * Strict integrity check used before firing the install intent on resume:
     *  - file exists
     *  - length matches recorded size
     *  - if sha256 was recorded, recomputed hash matches
     *  - if the publisher's digest was recorded, the recomputed hash matches
     *    that too ([fix/update-digest-verify])
     *
     * Returns the File when valid, null when corrupt/missing (caller should
     * clear the pending record and re-download).
     */
    fun verify(pending: PendingUpdate): File? {
        val f = File(pending.apkPath)
        if (!f.exists()) {
            AppLogger.warning(TAG, "verify: file missing ${pending.apkPath}")
            return null
        }
        if (f.length() != pending.apkSize) {
            AppLogger.warning(TAG, "verify: size mismatch expected=${pending.apkSize} actual=${f.length()}")
            return null
        }
        if (pending.sha256 != null || pending.publisherDigest != null) {
            val actual = runCatching { sha256(f) }.getOrNull()
            if (actual == null) {
                AppLogger.warning(TAG, "verify: sha256 unavailable (read failed)")
                return null
            }
            if (pending.sha256 != null && !actual.equals(pending.sha256, ignoreCase = true)) {
                AppLogger.warning(TAG, "verify: sha256 mismatch expected=${pending.sha256} actual=$actual")
                return null
            }
            if (pending.publisherDigest != null &&
                !actual.equals(pending.publisherDigest, ignoreCase = true)
            ) {
                AppLogger.warning(
                    TAG,
                    "verify: publisher digest mismatch expected=${pending.publisherDigest} actual=$actual",
                )
                return null
            }
        }
        return f
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
