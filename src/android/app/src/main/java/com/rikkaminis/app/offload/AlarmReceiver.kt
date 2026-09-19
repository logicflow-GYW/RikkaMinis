package com.rikkaminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rikkaminis.app.logging.AppLogger

/**
 * BroadcastReceiver that fires when an alarm or timer triggers.
 * Shows a high-priority notification with the alarm label.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIFICATION_GROUP = "minis_alarm_group"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // [audit-0919 F-279] BOOT_COMPLETED is declared in the manifest so the
        // receiver is (re)instantiated at boot, but there is nothing to do
        // here. The comment that used to live at this spot claimed boot
        // "keeps the receiver resident so the AlarmManager re-fires the
        // persisted alarms after a reboot" — that mechanism does not exist:
        // Android's AlarmManager does NOT retain alarms across a reboot (they
        // must be re-registered from a BOOT_COMPLETED handler), and this repo
        // has no reschedule implementation at all (`grep -rn 'fun .*[Rr]eschedul'`
        // == 0). Since T266/T268 alarm scheduling belongs to the system Clock
        // app, whose alarms deskclock persists and re-arms itself — the app
        // has nothing to re-register. Do not add rescheduling logic here on
        // the strength of the old comment.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.info(TAG, "BOOT_COMPLETED — nothing to reschedule (scheduling owned by system Clock, T266)")
            return
        }

        val alarmId = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_ID) ?: "unknown"
        val label = intent.getStringExtra(AlarmOffloadManager.EXTRA_ALARM_LABEL) ?: "Alarm"

        AppLogger.debug(TAG, "Alarm triggered: id=$alarmId label=$label")

        // Constructing the manager also creates the notification channel the
        // notification below needs — so it must happen before `nm.notify()`.
        // The ledger cleanup it exposes is invoked further down, AFTER the
        // notification was posted (see the [audit-0919 F-278] note there).
        val manager = AlarmOffloadManager(context)

        val notificationId = alarmId.hashCode() and 0x7FFFFFFF

        // Launch intent – open the app when the notification is tapped
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            PendingIntent.getActivity(
                context,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, AlarmOffloadManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("RikkaMinis Alarm")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "Cannot post notification – missing POST_NOTIFICATIONS permission: ${e.message}")
        }

        // [audit-0919 F-278] Post-then-cleanup, matching
        // [ScheduledNotificationReceiver] (which carries an [audit-0917]
        // rationale for this exact order): clearing the ledger first meant a
        // SecurityException (POST_NOTIFICATIONS revoked) or process death in
        // between dropped the record AND swallowed the notification, leaving
        // the user with no alarm at all. The notification is the user-visible
        // must-happen action; the ledger write is bookkeeping, so it goes
        // second. The failure is escalated from warning to error so it lands
        // in the error snapshot rather than scrolling away — it is not folded
        // into the notification text because there is no user action that
        // could fix it.
        if (alarmId != "unknown") {
            try {
                manager.onAlarmFired(alarmId)
            } catch (e: Throwable) {
                AppLogger.error(TAG, "onAlarmFired($alarmId) failed: ${e.message}")
            }
        }
    }
}
