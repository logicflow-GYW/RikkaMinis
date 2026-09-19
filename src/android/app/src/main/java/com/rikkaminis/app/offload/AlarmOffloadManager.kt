package com.rikkaminis.app.offload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.rikkaminis.app.logging.AppLogger
import org.json.JSONArray

/**
 * Repeat mode for the `android-alarm` CLI. Consumed by
 * [com.rikkaminis.app.sandbox.offload.AlarmOffloadHandler], which translates
 * it into the system Clock app's `SET_ALARM` extras (T266/T268) — it no
 * longer reaches any AlarmManager scheduling code in this file.
 */
enum class RepeatMode { ONCE, DAILY, WEEKDAYS }

/**
 * Remaining responsibility of the former internal alarm scheduler: the
 * notification channel [AlarmReceiver] posts into, plus the ledger cleanup
 * that runs when a pre-T266 alarm fires.
 *
 * [audit-0919 F-280] T266/T268 moved alarm/timer scheduling out of the app
 * and into the system Clock app (`AlarmClock.ACTION_SET_ALARM` /
 * `ACTION_SET_TIMER`, see [com.rikkaminis.app.sandbox.offload.AlarmOffloadHandler]).
 * The *write* side was deleted then; the scheduling / listing / cancelling
 * half of this class was left behind and had zero production callers:
 * `scheduleAlarm`, `scheduleTimer`, `listAlarms`, `sweepExpiredOnce`,
 * `cancelAlarm`, `cancelAllAlarms`, `persistAlarm`, `buildPendingIntent` and
 * `skipToNextWeekday` were each referenced only from this file (count == 1,
 * i.e. their own declaration). They have been removed rather than kept as a
 * misleading "restore point": the product decision was to hand scheduling to
 * the OS Clock app, and `MinisApp.migrateGhostAlarms()` clears
 * `minis_alarms_prefs` unconditionally on first launch, so nothing can
 * repopulate the ledger they read.
 *
 * What survives is the one path that still runs: a pre-T266 PendingIntent
 * left in AlarmManager by an older build fires [AlarmReceiver], which calls
 * [onAlarmFired] to drop the entry from the (now frozen) prefs blob.
 *
 * ponytail: 本类与 AlarmReceiver 只服务「旧版本残留的一次性 PendingIntent」
 *   | 天花板: pre-T266 用户全部升级后（本轮之后），onAlarmFired 永远走
 *     "no matching prefs entry" 分支，本类与 CHANNEL_ID 可整块删除
 *   | 升级触发: 连续两个版本无 `alarm id=… fired but no matching prefs entry`
 *     日志，或 manifest 里 AlarmReceiver 的 intent-filter 只剩 BOOT_COMPLETED
 */
class AlarmOffloadManager(private val context: Context) {

    companion object {
        private const val TAG = "AlarmOffloadManager"
        private const val PREFS_NAME = "minis_alarms_prefs"
        private const val KEY_ALARMS = "alarms_json"
        const val CHANNEL_ID = "minis_alarms"
        private const val CHANNEL_NAME = "RikkaMinis Alarms & Timers"

        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_ALARM_LABEL = "alarm_label"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarms and timers scheduled by the RikkaMinis agent"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Called from [AlarmReceiver] right after an alarm fires. Looks up the
     * entry in prefs and drops it iff it's a ONCE alarm or a timer; for
     * DAILY/WEEKDAYS the OS re-fires on its own and we keep the entry.
     *
     * [audit-0919 F-280] The ledger this reads is frozen: nothing writes it
     * any more (see the class KDoc), so the common outcome for a real
     * broadcast is the "no matching prefs entry" warning at the bottom —
     * which is the correct, expected result for a ghost alarm replayed after
     * [com.rikkaminis.app.MinisApp.migrateGhostAlarms] cleared the blob.
     */
    fun onAlarmFired(alarmId: String) {
        val alarms = loadAlarms()
        for (i in 0 until alarms.length()) {
            val a = alarms.getJSONObject(i)
            if (a.optString("id") != alarmId) continue
            val mode = a.optString("repeatMode", "ONCE")
            val nonRepeating = a.optString("type") == "timer" || mode == "ONCE"
            if (!nonRepeating) {
                AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — keeping entry (repeats)")
                return
            }
            alarms.remove(i)
            prefs.edit().putString(KEY_ALARMS, alarms.toString()).apply()
            AppLogger.info(TAG, "alarm id=$alarmId mode=$mode fired — removed from prefs")
            return
        }
        AppLogger.warning(TAG, "alarm id=$alarmId fired but no matching prefs entry")
    }

    private fun loadAlarms(): JSONArray {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
