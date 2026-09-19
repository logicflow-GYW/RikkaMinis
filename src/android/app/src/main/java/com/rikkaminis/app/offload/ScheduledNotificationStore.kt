package com.rikkaminis.app.offload

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight SharedPreferences-backed registry of currently-scheduled
 * `android-notification schedule` notifications.
 *
 * Used so that:
 *   - `android-notification pending` can list what's still queued
 *   - `android-notification cancel --id` can remove a specific entry
 *   - the receiver can drop entries on fire so they don't linger
 *
 * The app already maintains a similar prefs registry for alarms in
 * AlarmOffloadManager; we deliberately keep this separate so the two
 * surfaces don't collide on key names or fire semantics.
 */
internal class ScheduledNotificationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // [audit-0919 F-283] Every write below is a whole-blob read-modify-write
    // (`loadAll()` → mutate → `putString(entire JSON)`). Without mutual
    // exclusion two concurrent writers each read the same snapshot and the
    // second write silently discards the first writer's entry. The handler
    // that owns this store runs on the NativeOffloadServer worker pool
    // (NativeOffload.kt:242, sized by ConcurrencyPrefs.maxConcurrentSessions(),
    // default 2), so two overlapping `android-notification schedule` calls are
    // enough to trigger it — reproduced at 20/26/29/32/37 surviving entries out
    // of 100 (63-80% loss) in wave2-d1/exp_d1/ExpA.java.
    //
    // The consequence is worse than a lost bookkeeping row: the AlarmManager
    // alarm is registered BEFORE `add()` (NotificationOffloadHandler
    // `scheduleDeferred`: `am.setExactAndAllowWhileIdle(...)` then
    // `store.add(entry)`), so a dropped entry leaves an alarm that fires but
    // cannot be listed by `pending` nor cancelled by `cancel --id` (get()
    // returns null) — the user can only wait for it.
    private val lock = Any()

    /**
     * [audit-0919 F-283] Soft ceiling. Exceeding it triggers a sweep of
     * already-expired entries plus a loud log — it deliberately does NOT drop
     * the oldest entry: dropping one would recreate exactly the "alarm is
     * registered but the ledger has no row" state this class is being fixed
     * for. 1000 rows ≈ 15 KB of JSON, so unbounded growth is bounded by the
     * sweep, not by data loss.
     */
    fun add(entry: JSONObject) {
        synchronized(lock) {
            val list = loadAll()
            list.put(entry)
            val capped = if (list.length() > MAX_ENTRIES) sweepExpiredIn(list) else list
            if (capped.length() > MAX_ENTRIES) {
                AppLogger.error(
                    TAG,
                    "scheduled-notification ledger has ${capped.length()} entries (> $MAX_ENTRIES) — " +
                        "not dropping any (an orphan alarm cannot be cancelled); " +
                        "run `android-notification pending` to sweep",
                )
            }
            prefs.edit().putString(KEY, capped.toString()).apply()
        }
    }

    fun remove(id: String): Boolean {
        synchronized(lock) {
            val list = loadAll()
            for (i in 0 until list.length()) {
                if (list.getJSONObject(i).optString("id") == id) {
                    list.remove(i)
                    prefs.edit().putString(KEY, list.toString()).apply()
                    return true
                }
            }
            return false
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    fun get(id: String): JSONObject? {
        val list = loadAll()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun loadAll(): JSONArray {
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    /**
     * Drop entries whose triggerAtMs has already passed. Receiver also
     * cleans up on fire, but if it was killed by the OS between schedule
     * and trigger the prefs entry would otherwise stick around.
     */
    fun sweepExpired() {
        synchronized(lock) {
            val list = loadAll()
            val kept = sweepExpiredIn(list)
            if (kept.length() != list.length()) {
                prefs.edit().putString(KEY, kept.toString()).apply()
            }
        }
    }

    /**
     * [audit-0919 F-283] Pure filter extracted so both [add]'s cap path and
     * [sweepExpired] share one definition of "expired". Caller holds [lock].
     */
    private fun sweepExpiredIn(list: JSONArray): JSONArray {
        val now = System.currentTimeMillis()
        val kept = JSONArray()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optLong("trigger_at_ms", Long.MAX_VALUE) > now) {
                kept.put(o)
            }
        }
        return kept
    }

    companion object {
        private const val TAG = "ScheduledNotifStore"
        private const val PREFS = "minis_scheduled_notifications"
        private const val KEY = "scheduled"
        private const val MAX_ENTRIES = 1_000
    }
}
