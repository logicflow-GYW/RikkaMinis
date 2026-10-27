package com.rikkaminis.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rikkaminis.app.R
import com.rikkaminis.app.data.storage.StorageBudget
import com.rikkaminis.app.logging.AppLogger

/**
 * [feat/storage-budget-loop] The alarm half of the DECIDE edge.
 *
 * A user only learns the app is eating 14 GB by digging into Android Settings,
 * where the app's own words ("Terminal Shell") don't appear and the number
 * reads "user data". This posts one notification when
 * [StorageBudget.Usage.overBudget] flips true (re-armed weekly or on 25% growth
 * — see `StorageBudget.shouldNotify`), deep-linking into the Storage screen
 * where the per-category breakdown already lives.
 *
 * Permission posture matches [BackgroundTaskNotifier]: pre-Tiramisu post
 * directly, Tiramisu+ silently no-op when notifications are disabled, and a
 * `SecurityException` from `notify()` is swallowed with a log line. A missing
 * permission must never crash or block app startup — the sample itself still
 * runs and still feeds the agent prompt.
 */
object StorageBudgetNotifier {

    const val CHANNEL_ID = "minis_storage_budget"

    /** Stable id: one storage row, never a stack of them. */
    private const val NOTIFICATION_ID = 0x5354_0001

    private const val TAG = "StorageBudgetNotifier"

    fun post(context: Context, usage: StorageBudget.Usage) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            AppLogger.info(TAG, "notify skipped (notifications disabled)")
            return
        }
        ensureChannel(context)

        val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("minis://settings/storage")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = context.getString(
            R.string.notif_storage_budget_body,
            StorageBudget.fmt(usage.appBytes),
            StorageBudget.fmt(usage.thresholdBytes),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(ContextCompat.getColor(context, R.color.notification_brand_color))
            .setContentTitle(context.getString(R.string.notif_storage_budget_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (se: SecurityException) {
            AppLogger.info(TAG, "notify denied (POST_NOTIFICATIONS not granted)")
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "notify failed: ${t.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_storage_budget_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_storage_budget_channel_description)
            setShowBadge(true)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
