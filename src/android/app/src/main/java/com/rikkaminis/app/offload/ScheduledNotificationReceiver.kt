package com.rikkaminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.rikkaminis.app.R
import com.rikkaminis.app.logging.AppLogger

/**
 * Fires when an `android-notification schedule` AlarmManager alarm
 * triggers. Posts the notification immediately and removes the entry
 * from the pending-notifications prefs so a follow-up `pending` call
 * no longer lists it.
 *
 * Mirrors the AlarmReceiver pattern but for the user-scheduled
 * notification path (apple-notification schedule), which on iOS goes
 * through UNUserNotificationCenter directly. Android has no equivalent
 * "scheduled local notification" API, so we use AlarmManager + a
 * receiver as the moral equivalent.
 */
class ScheduledNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ID = "scheduled_notification_id"
        const val EXTRA_TITLE = "scheduled_notification_title"
        const val EXTRA_BODY = "scheduled_notification_body"
        const val CHANNEL_ID = "minis_agent_notifications"
        private const val TAG = "ScheduledNotifReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: run {
            // [audit-0917] Without an id there is nothing to remove — the old
            // "unknown" fallback was passed to remove() and could delete an
            // unrelated entry that happened to be keyed "unknown".
            AppLogger.warning(TAG, "scheduled notification fired without EXTRA_ID — ignored")
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "RikkaMinis"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        AppLogger.debug(TAG, "scheduled notification fired: id=$id title='$title'")

        val notifId = id.hashCode() and 0x7FFFFFFF
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi = if (launchIntent != null) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(context, notifId, launchIntent, flags)
        } else null

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .apply { if (contentPi != null) setContentIntent(contentPi) }
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notifId, notification)
            // [audit-0917] Drop the pending record only AFTER the notification
            // was actually posted. Removing first meant a SecurityException
            // (POST_NOTIFICATIONS revoked) or process death in between lost the
            // notification with no pending record left to retry from.
            try {
                ScheduledNotificationStore(context).remove(id)
            } catch (e: Throwable) {
                AppLogger.warning(TAG, "remove($id) failed: ${e.message}")
            }
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "post denied — POST_NOTIFICATIONS missing: ${e.message}")
        }
    }
}
