package com.rikkaminis.app.data.storage

import android.content.Context
import android.os.StatFs
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.RootfsUsageScanner
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [feat/storage-budget-loop] The missing DECIDE edge of the storage loop.
 *
 * Why this exists (2026-10-27): the app could ALREADY measure itself — the
 * Storage Management screen runs [RootfsUsageScanner] over the whole rootfs and
 * every session dir — but that scan only happened when the user opened that
 * screen. `WorkManager`/periodic work: zero hits in the tree. So the loop was
 * SENSE-on-demand → (no DECIDE) → ACT-on-click, and the practical result was a
 * user discovering 14.45 GB of "user data" in Android Settings, where the app's
 * own vocabulary ("Terminal Shell") never appears.
 *
 * This object adds the two cheap pieces:
 *  - a daily, off-main-thread sample (cached in memory + a persisted timestamp
 *    so a process restart doesn't rescan), and
 *  - a budget verdict ([Usage.overBudget]) that a notification and the agent
 *    system prompt can both read.
 *
 * Deliberately NOT done here: hard quotas (they would break `apk add` — the
 * core "the agent can install what it needs" experience, and packages have no
 * graceful degradation path) and automatic session deletion (silent data loss).
 * Registered as a negative decision in `shared/backlog.md` §2608.
 */
object StorageBudget {

    const val PREFS = "minis_storage_budget_prefs"
    internal const val KEY_LAST_SAMPLE_AT = "lastSampleAt"
    internal const val KEY_LAST_NOTIFIED_AT = "lastNotifiedAt"
    internal const val KEY_LAST_NOTIFIED_BYTES = "lastNotifiedBytes"
    internal const val KEY_USAGE_APP = "usageAppBytes"
    internal const val KEY_USAGE_SHELL = "usageShellBytes"
    internal const val KEY_USAGE_FREE = "usageFreeBytes"
    internal const val KEY_USAGE_TOTAL = "usageTotalBytes"

    /**
     * Warning floor: 6 GiB. A freshly installed app with a bare rootfs sits
     * well under 1 GiB, so 6 GiB means "something accumulated", not "the app
     * is big". Phones with a huge /data get a larger threshold via the 5% arm
     * below, so the warning never fires purely because the partition is large.
     */
    internal const val MIN_THRESHOLD_BYTES = 6L * 1024 * 1024 * 1024

    /** Sample at most once per day; the rootfs walk is seconds of IO. */
    internal const val SAMPLE_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Re-notify at most weekly unless the footprint grew by [RENOTIFY_GROWTH_RATIO]. */
    internal const val RENOTIFY_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    internal const val RENOTIFY_GROWTH_RATIO = 0.25

    private const val TAG = "StorageBudget"

    data class Usage(
        /** Everything this app owns under /data/data/<pkg> that we can measure. */
        val appBytes: Long,
        /** The Alpine rootfs subset of [appBytes] — the part the agent grows. */
        val shellBytes: Long,
        val freeBytes: Long,
        val totalBytes: Long,
        val sampledAt: Long,
    ) {
        val thresholdBytes: Long get() = thresholdFor(totalBytes)
        val overBudget: Boolean get() = appBytes >= thresholdBytes
        val freeRatio: Float get() = if (totalBytes <= 0L) 1f else freeBytes.toFloat() / totalBytes
    }

    /** 6 GiB floor, or 5% of the data partition when that is larger. */
    internal fun thresholdFor(totalBytes: Long): Long =
        if (totalBytes <= 0L) MIN_THRESHOLD_BYTES else maxOf(MIN_THRESHOLD_BYTES, totalBytes / 20)

    /**
     * Notification gate. First crossing notifies; afterwards a weekly nudge or a
     * 25% growth re-notify. Shrinking back under budget resets silently (the
     * next crossing notifies immediately because [lastNotifiedBytes] is stale —
     * it is only rewritten when a notification is actually posted).
     */
    internal fun shouldNotify(
        now: Long,
        lastNotifiedAt: Long,
        lastNotifiedBytes: Long,
        usage: Usage,
    ): Boolean {
        if (!usage.overBudget) return false
        if (lastNotifiedAt <= 0L) return true
        if (lastNotifiedBytes > 0L &&
            usage.appBytes >= lastNotifiedBytes + (lastNotifiedBytes * RENOTIFY_GROWTH_RATIO).toLong()
        ) {
            return true
        }
        return now - lastNotifiedAt >= RENOTIFY_INTERVAL_MS
    }

    /** Non-null once a sample has been taken in this process. */
    @Volatile
    private var cachedUsage: Usage? = null

    fun cached(): Usage? = cachedUsage

    /** Test seam: lets prompt/notify logic be exercised without a device walk. */
    internal fun setCachedForTest(usage: Usage?) {
        cachedUsage = usage
    }

    /**
     * One line for the agent system prompt — the cheapest of the three fixes
     * and the only one aimed at the actual producer.
     *
     * The agent is what creates the growth (`apk add`, builds, big offload
     * trees), yet it currently has no idea it is eating the user's disk. No IO
     * here: reads the cached sample only, so prompt assembly stays allocation-
     * cheap and byte-stable between the daily samples (prompt caching cares).
     */
    fun promptLine(): String? {
        val u = cachedUsage ?: return null
        val base = "- App storage: ${fmt(u.appBytes)} used by this app " +
            "(sandbox shell ${fmt(u.shellBytes)}), device free ${fmt(u.freeBytes)}"
        return if (u.overBudget) {
            base + " — OVER the ${fmt(u.thresholdBytes)} budget. Before leaving large " +
                "trees behind (clone/build/fixture dirs, apk caches), clean them up " +
                "with `minis-fastio rm -r` and tell the user what is taking the space."
        } else {
            base
        }
    }

    /**
     * Measure once. Walks `filesDir` (which includes the rootfs and every
     * session dir) plus the databases dir, and reads partition free/total.
     */
    suspend fun sample(
        context: Context,
        sizeStat: RootfsUsageScanner.Stat = RootfsUsageScanner.androidStat(),
    ): Usage = withContext(Dispatchers.IO) {
        val files = context.filesDir
        val report = RootfsUsageScanner.scan(files, sizeStat)
        val shellBytes = report.entries.firstOrNull { it.name == "alpine-rootfs" }?.bytes ?: 0L
        val dbBytes = databaseDirBytes(context, sizeStat)
        val stat = StatFs(files.path)
        Usage(
            appBytes = report.totalBytes + dbBytes,
            shellBytes = shellBytes,
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
            sampledAt = System.currentTimeMillis(),
        )
    }

    private fun databaseDirBytes(context: Context, sizeStat: RootfsUsageScanner.Stat): Long = try {
        val dir = context.getDatabasePath("minis-storage-probe.db")?.parentFile
        if (dir != null && dir.exists()) RootfsUsageScanner.scan(dir, sizeStat).totalBytes else 0L
    } catch (t: Throwable) {
        AppLogger.warning(TAG, "databaseDirBytes failed: ${t.message}")
        0L
    }

    /**
     * Daily entry point, called from `MinisApp.onCreate` on `applicationScope`.
     * Fail-safe: a failed sample still stamps the timestamp (otherwise a
     * permanently failing walk would retry on every cold start, which is the
     * expensive outcome we are trying to avoid).
     */
    suspend fun runDailyCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SAMPLE_AT, 0L)
        // [fix/composer-paste-lifecycle F-A-03] Restore BEFORE the freshness
        // gate. The old flow only wrote the in-memory sample on the process
        // that actually sampled, so after the (very common) process recycle
        // every later process read cachedUsage == null and the agent — and the
        // notifier — lost its own bill for the rest of the day.
        if (cachedUsage == null) cachedUsage = restoreUsage(prefs, last)
        if (last > 0L && now - last < SAMPLE_INTERVAL_MS) return
        try {
            val usage = sample(context)
            cachedUsage = usage
            prefs.edit()
                .putLong(KEY_LAST_SAMPLE_AT, now)
                .putLong(KEY_USAGE_APP, usage.appBytes)
                .putLong(KEY_USAGE_SHELL, usage.shellBytes)
                .putLong(KEY_USAGE_FREE, usage.freeBytes)
                .putLong(KEY_USAGE_TOTAL, usage.totalBytes)
                .apply()
            AppLogger.info(
                TAG,
                "sample app=${fmt(usage.appBytes)} shell=${fmt(usage.shellBytes)} " +
                    "free=${fmt(usage.freeBytes)}/${fmt(usage.totalBytes)} " +
                    "threshold=${fmt(usage.thresholdBytes)} over=${usage.overBudget}",
            )
            val lastNotifiedAt = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
            val lastNotifiedBytes = prefs.getLong(KEY_LAST_NOTIFIED_BYTES, 0L)
            if (shouldNotify(now, lastNotifiedAt, lastNotifiedBytes, usage)) {
                com.rikkaminis.app.notification.StorageBudgetNotifier.post(context, usage)
                prefs.edit()
                    .putLong(KEY_LAST_NOTIFIED_AT, now)
                    .putLong(KEY_LAST_NOTIFIED_BYTES, usage.appBytes)
                    .apply()
                AppLogger.info(TAG, "over budget — notified ${fmt(usage.appBytes)}")
            }
        } catch (t: Throwable) {
            prefs.edit().putLong(KEY_LAST_SAMPLE_AT, now).apply()
            AppLogger.warning(TAG, "sample failed: ${t.message}")
        }
    }

    /**
     * [fix/composer-paste-lifecycle F-A-03] Rebuild the in-memory sample from
     * the persisted one. Returns null when nothing usable is on file (no
     * sample ever taken, or a failed walk — its timestamp write carries no
     * usage numbers, so `usageTotalBytes` stays 0).
     *
     * Deliberately does NOT check freshness: the caller's 24h gate already
     * decided this sample is the freshest fact we have, and a 20-hour-old
     * bill is still a far better answer than "no idea" for the dozens of
     * processes this app spawns per day.
     */
    private fun restoreUsage(prefs: android.content.SharedPreferences, sampledAt: Long): Usage? =
        usageFromPersisted(
            appBytes = prefs.getLong(KEY_USAGE_APP, 0L),
            shellBytes = prefs.getLong(KEY_USAGE_SHELL, 0L),
            freeBytes = prefs.getLong(KEY_USAGE_FREE, 0L),
            totalBytes = prefs.getLong(KEY_USAGE_TOTAL, 0L),
            sampledAt = sampledAt,
        )

    /**
     * [fix/composer-paste-lifecycle F-A-03] Pure half of the cold-start
     * restore: persisted numbers → the in-memory sample. Returns null when
     * nothing usable is on file (no sample ever taken, or a failed walk — its
     * timestamp write carries no usage numbers, so `totalBytes` stays 0).
     *
     * Deliberately does NOT check freshness: the caller's 24h gate already
     * decided this sample is the freshest fact we have, and a 20-hour-old
     * bill is still a far better answer than "no idea" for the dozens of
     * processes this app spawns per day.
     */
    internal fun usageFromPersisted(
        appBytes: Long,
        shellBytes: Long,
        freeBytes: Long,
        totalBytes: Long,
        sampledAt: Long,
    ): Usage? {
        if (sampledAt <= 0L) return null
        if (totalBytes <= 0L) return null
        return Usage(
            appBytes = appBytes,
            shellBytes = shellBytes,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            sampledAt = sampledAt,
        )
    }

    /** Test-facing formatter (pure; no android.text.format dependency). */
    internal fun fmt(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%d B", bytes)
    }
}
