package com.rikkaminis.app.offload

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rikkaminis.app.MainActivity
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

        /**
         * [GH#116] The tap target for an `android-notification` notification.
         *
         * A notification with no contentIntent is INERT — tapping it does
         * nothing, and `setAutoCancel(true)` merely dismisses it. That is
         * exactly what #116 reported: `android-notification send` (the plain,
         * most common invocation) posted a notification that did nothing when
         * tapped. Android has no implicit "tap opens the app" behaviour; iOS's
         * UNUserNotificationCenter does, which is how the gap survived a port
         * that otherwise mirrors apple-notification.
         *
         * Shared by BOTH post sites (the immediate path in
         * NotificationOffloadHandler and the deferred fire here) on purpose:
         * they each build their own notification and had drifted apart — the
         * deferred path set a contentIntent, the immediate path never did. One
         * helper means a future change cannot re-open that gap.
         *
         * Targets MainActivity explicitly rather than
         * getLaunchIntentForPackage(): an explicit component cannot resolve to
         * null the way a package-manager query can (and stays correct if the
         * launcher filter ever moves again).
         *
         * NEW_TASK because we post from a receiver / background context with no
         * Activity on the stack; CLEAR_TOP so MainActivity (singleTask) reuses
         * its existing instance instead of stacking a duplicate.
         *
         * FLAG_IMMUTABLE is mandatory, not defensive: targetSdk 35 means
         * Android 12+ throws if a PendingIntent declares neither mutability. It
         * also stops a malicious app from filling in extras on an intent that
         * would then be sent AS RikkaMinis.
         */
        fun contentIntentFor(context: Context, notifId: Int): PendingIntent {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                // Unique per notification id: with FLAG_UPDATE_CURRENT a shared
                // request code would let one notification's intent overwrite
                // another's once these carry per-notification extras.
                notifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
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

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            // [GH#116] Shared with the immediate path — see contentIntentFor.
            .setContentIntent(contentIntentFor(context, notifId))
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
